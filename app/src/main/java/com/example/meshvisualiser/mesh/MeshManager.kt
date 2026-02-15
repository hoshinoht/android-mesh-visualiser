package com.example.meshvisualiser.mesh

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.meshvisualiser.MeshVisualizerApp
import com.example.meshvisualiser.models.MeshMessage
import com.example.meshvisualiser.models.MeshState
import com.example.meshvisualiser.models.MessageType
import com.example.meshvisualiser.network.NearbyConnectionsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Implements the Bully Algorithm for leader election in the mesh network.
 *
 * Election Rules:
 * 1. Any node can start an election by sending ELECTION to all higher-ID nodes
 * 2. If a node receives ELECTION from a lower-ID node, it replies OK and starts its own election
 * 3. If no OK is received within timeout, the node becomes the leader
 * 4. The leader broadcasts COORDINATOR with the Cloud Anchor ID
 */
class MeshManager(
        private val localId: Long,
        private val nearbyManager: NearbyConnectionsManager,
        private val onBecomeLeader: () -> Unit,
        private val onNewLeader: (leaderId: Long, cloudAnchorId: String) -> Unit,
        private val onPoseUpdate: (peerId: Long, x: Float, y: Float, z: Float) -> Unit
) {
  companion object {
    private const val TAG = "MeshManager"
  }

  private val handler = Handler(Looper.getMainLooper())

  private val _meshState = MutableStateFlow(MeshState.DISCOVERING)
  val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

  private val _currentLeaderId = MutableStateFlow(-1L)
  val currentLeaderId: StateFlow<Long> = _currentLeaderId.asStateFlow()

  private var isWaitingForOk = false
  private var electionTimeoutRunnable: Runnable? = null
  private var meshFormationTimeoutRunnable: Runnable? = null

  val isLeader: Boolean
    get() = _currentLeaderId.value == localId

  /** Handle incoming messages from peers. */
  fun onMessageReceived(endpointId: String, message: MeshMessage) {
    val senderId = message.senderId

    when (message.getMessageType()) {
      MessageType.ELECTION -> handleElectionMessage(endpointId, senderId)
      MessageType.OK -> handleOkMessage(senderId)
      MessageType.COORDINATOR -> handleCoordinatorMessage(senderId, message.data)
      MessageType.POSE_UPDATE -> handlePoseUpdate(senderId, message)
      else -> {
        /* Ignore */
      }
    }
  }

  /** Start mesh formation - begin discovering and wait for peers. */
  fun startMeshFormation() {
    _meshState.value = MeshState.DISCOVERING
    nearbyManager.startDiscoveryAndAdvertising()

    // Start timeout for mesh formation
    meshFormationTimeoutRunnable = Runnable {
      if (_meshState.value == MeshState.DISCOVERING) {
        Log.d(TAG, "Mesh formation timeout - starting election")
        startElection()
      }
    }
    handler.postDelayed(meshFormationTimeoutRunnable!!, MeshVisualizerApp.MESH_FORMATION_TIMEOUT_MS)
  }

  /** Start the Bully Algorithm election. */
  fun startElection() {
    Log.d(TAG, "Starting election (localId: $localId)")
    _meshState.value = MeshState.ELECTING
    isWaitingForOk = true

    // Cancel mesh formation timeout so it doesn't re-trigger election
    meshFormationTimeoutRunnable?.let { handler.removeCallbacks(it) }

    // Cancel any existing election timeout
    electionTimeoutRunnable?.let { handler.removeCallbacks(it) }

    // Find peers with higher IDs
    val validPeers = nearbyManager.getValidPeers()
    val higherPeers = validPeers.filter { it.value.peerId > localId }

    if (higherPeers.isEmpty()) {
      // No higher peers, I become leader immediately
      Log.d(TAG, "No higher peers found, becoming leader")
      becomeLeader()
    } else {
      // Send ELECTION to all higher-ID peers
      Log.d(TAG, "Sending ELECTION to ${higherPeers.size} higher peers")
      higherPeers.keys.forEach { endpointId ->
        nearbyManager.sendMessage(endpointId, MeshMessage.election(localId))
      }

      // Start timeout - if no OK received, I win
      electionTimeoutRunnable = Runnable {
        if (isWaitingForOk) {
          Log.d(TAG, "Election timeout - no OK received, becoming leader")
          becomeLeader()
        }
      }
      handler.postDelayed(electionTimeoutRunnable!!, MeshVisualizerApp.ELECTION_TIMEOUT_MS)
    }
  }

  private fun handleElectionMessage(endpointId: String, senderId: Long) {
    Log.d(TAG, "Received ELECTION from $senderId")

    if (senderId < localId) {
      // I'm bigger, send OK and start my own election
      Log.d(TAG, "Sender $senderId < localId $localId, replying OK and starting election")
      nearbyManager.sendMessage(endpointId, MeshMessage.ok(localId))
      startElection()
    }
    // If sender >= localId, ignore (shouldn't happen in correct Bully)
  }

  private fun handleOkMessage(senderId: Long) {
    Log.d(TAG, "Received OK from $senderId")

    // Someone bigger is alive, wait for COORDINATOR
    isWaitingForOk = false
    electionTimeoutRunnable?.let { handler.removeCallbacks(it) }

    // Stay in ELECTING state, waiting for COORDINATOR
  }

  private fun handleCoordinatorMessage(leaderId: Long, cloudAnchorId: String) {
    Log.d(TAG, "Received COORDINATOR from $leaderId with anchor: $cloudAnchorId")

    _currentLeaderId.value = leaderId
    isWaitingForOk = false
    electionTimeoutRunnable?.let { handler.removeCallbacks(it) }

    if (cloudAnchorId.isNotEmpty()) {
      _meshState.value = MeshState.RESOLVING
      onNewLeader(leaderId, cloudAnchorId)
    }
  }

  private fun handlePoseUpdate(senderId: Long, message: MeshMessage) {
    message.parsePoseData()?.let { (x, y, z) ->
      // Update peer's pose in nearby manager
      val validPeers = nearbyManager.getValidPeers()
      val peerEntry = validPeers.entries.find { it.value.peerId == senderId }
      peerEntry?.value?.updatePose(x, y, z)

      // Notify callback
      onPoseUpdate(senderId, x, y, z)
    }
  }

  private fun becomeLeader() {
    Log.d(TAG, "Becoming leader!")
    _currentLeaderId.value = localId
    isWaitingForOk = false
    _meshState.value = MeshState.RESOLVING

    // Notify that we should host Cloud Anchor
    onBecomeLeader()
  }

  /** Called by leader after Cloud Anchor is hosted. Broadcasts COORDINATOR message to all peers. */
  fun announceLeadership(cloudAnchorId: String) {
    Log.d(TAG, "Announcing leadership with anchor: $cloudAnchorId")
    nearbyManager.broadcastMessage(MeshMessage.coordinator(localId, cloudAnchorId))
    _meshState.value = MeshState.CONNECTED
  }

  /** Broadcast pose update to all peers. */
  fun broadcastPose(x: Float, y: Float, z: Float) {
    nearbyManager.broadcastMessage(MeshMessage.poseUpdate(localId, x, y, z))
  }

  /** Set mesh state to connected (called after anchor is resolved). */
  fun setConnected() {
    _meshState.value = MeshState.CONNECTED
  }

  /** Cleanup resources. */
  fun cleanup() {
    electionTimeoutRunnable?.let { handler.removeCallbacks(it) }
    meshFormationTimeoutRunnable?.let { handler.removeCallbacks(it) }
  }
}
