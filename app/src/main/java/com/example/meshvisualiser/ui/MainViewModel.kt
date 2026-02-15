package com.example.meshvisualiser.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meshvisualiser.ar.CloudAnchorManager
import com.example.meshvisualiser.ar.LineRenderer
import com.example.meshvisualiser.ar.PoseManager
import com.example.meshvisualiser.mesh.MeshManager
import com.example.meshvisualiser.models.MeshState
import com.example.meshvisualiser.models.PeerInfo
import com.example.meshvisualiser.network.NearbyConnectionsManager
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the main AR mesh visualization screen. Coordinates all managers and handles the
 * mesh lifecycle.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val TAG = "MainViewModel"
  }

  // Generate unique local ID
  val localId: Long = Random.nextLong(1, Long.MAX_VALUE)

  // Managers
  private lateinit var nearbyManager: NearbyConnectionsManager
  private lateinit var meshManager: MeshManager
  val cloudAnchorManager = CloudAnchorManager()
  val poseManager = PoseManager()
  val lineRenderer = LineRenderer()

  // State flows
  private val _meshState = MutableStateFlow(MeshState.DISCOVERING)
  val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

  private val _peers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
  val peers: StateFlow<Map<String, PeerInfo>> = _peers.asStateFlow()

  private val _isLeader = MutableStateFlow(false)
  val isLeader: StateFlow<Boolean> = _isLeader.asStateFlow()

  private val _currentLeaderId = MutableStateFlow(-1L)
  val currentLeaderId: StateFlow<Long> = _currentLeaderId.asStateFlow()

  private val _statusMessage = MutableStateFlow("Initializing...")
  val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

  private val _mappingQuality = MutableStateFlow(0)
  val mappingQuality: StateFlow<Int> = _mappingQuality.asStateFlow()

  private val _peerPoses = MutableStateFlow<Map<Long, Triple<Float, Float, Float>>>(emptyMap())
  val peerPoses: StateFlow<Map<Long, Triple<Float, Float, Float>>> = _peerPoses.asStateFlow()

  private var anchorToHost: Anchor? = null

  private var isInitialized = false

  /** Initialize all managers. Call after permissions are granted. */
  fun initialize() {
    if (isInitialized) return

    Log.d(TAG, "Initializing with localId: $localId")

    nearbyManager =
            NearbyConnectionsManager(
                    context = getApplication(),
                    localId = localId,
                    onMessageReceived = { endpointId, message ->
                      meshManager.onMessageReceived(endpointId, message)
                    }
            )

    meshManager =
            MeshManager(
                    localId = localId,
                    nearbyManager = nearbyManager,
                    onBecomeLeader = ::onBecomeLeader,
                    onNewLeader = ::onNewLeader,
                    onPoseUpdate = ::onPoseUpdate
            )

    // Observe nearby peers
    viewModelScope.launch { nearbyManager.peers.collect { peers -> _peers.value = peers } }

    // Observe mesh state
    viewModelScope.launch {
      meshManager.meshState.collect { state ->
        _meshState.value = state
        updateStatusMessage(state)
      }
    }

    // Observe leader
    viewModelScope.launch {
      meshManager.currentLeaderId.collect { leaderId ->
        _currentLeaderId.value = leaderId
        _isLeader.value = leaderId == localId
      }
    }

    isInitialized = true
  }

  /** Set the ARCore session. */
  fun setArSession(session: Session) {
    cloudAnchorManager.setSession(session)
  }

  /** Start the mesh formation process. */
  fun startMesh() {
    if (!isInitialized) {
      Log.e(TAG, "Not initialized!")
      return
    }

    _statusMessage.value = "Discovering peers..."
    meshManager.startMeshFormation()
  }

  /** Called when this device becomes the leader. */
  private fun onBecomeLeader() {
    Log.d(TAG, "We are now the leader!")
    _isLeader.value = true
    _statusMessage.value = "Leader! Waiting for anchor..."

    // The anchor will be set when user places it or automatically
    anchorToHost?.let { anchor -> hostCloudAnchor(anchor) }
  }

  /** Called when a new leader is elected (and we're not it). */
  private fun onNewLeader(leaderId: Long, cloudAnchorId: String) {
    Log.d(TAG, "New leader: $leaderId, anchor: $cloudAnchorId")
    _isLeader.value = false
    _statusMessage.value = "Resolving anchor..."

    // Resolve the Cloud Anchor
    viewModelScope.launch {
      val anchor = cloudAnchorManager.resolveCloudAnchor(cloudAnchorId)
      if (anchor != null) {
        poseManager.setSharedAnchor(anchor)
        meshManager.setConnected()
        _statusMessage.value = "Connected!"
      } else {
        _statusMessage.value = "Failed to resolve anchor"
      }
    }
  }

  /** Called when a peer sends a pose update. */
  private fun onPoseUpdate(peerId: Long, x: Float, y: Float, z: Float) {
    val currentPoses = _peerPoses.value.toMutableMap()
    currentPoses[peerId] = Triple(x, y, z)
    _peerPoses.value = currentPoses
  }

  /** Set an anchor to host (called from AR scene when anchor is placed). */
  fun setAnchorToHost(anchor: Anchor) {
    anchorToHost = anchor

    // If we're already the leader, host immediately
    if (_isLeader.value) {
      hostCloudAnchor(anchor)
    }
  }

  /** Host a Cloud Anchor and broadcast to peers. */
  private fun hostCloudAnchor(anchor: Anchor) {
    viewModelScope.launch {
      _statusMessage.value = "Hosting Cloud Anchor..."

      val cloudAnchorId = cloudAnchorManager.hostCloudAnchor(anchor)

      if (cloudAnchorId != null) {
        val anchor = cloudAnchorManager.getSharedAnchor() ?: run {
          _statusMessage.value = "Failed to get shared anchor"
          return@launch
        }
        poseManager.setSharedAnchor(anchor)
        meshManager.announceLeadership(cloudAnchorId)
        meshManager.setConnected()
        _statusMessage.value = "Connected as leader!"
      } else {
        _statusMessage.value = "Failed to host anchor"
      }
    }
  }

  /** Called every AR frame to update pose and visualizations. */
  fun updateFrame(cameraPose: Pose, myWorldPosition: Position) {
    if (_meshState.value != MeshState.CONNECTED) return

    // Calculate and broadcast our relative pose
    poseManager.calculateRelativePose(cameraPose)?.let { (x, y, z) ->
      meshManager.broadcastPose(x, y, z)
    }

    // Update peer visualizations
    _peerPoses.value.forEach { (peerId, pose) ->
      poseManager.relativeToWorldPose(pose.first, pose.second, pose.third)?.let { worldPose ->
        lineRenderer.updatePeerVisualization(
                peerId = peerId,
                worldPosition = Position(worldPose.tx(), worldPose.ty(), worldPose.tz()),
                myPosition = myWorldPosition
        )
      }
    }
  }

  /** Update mapping quality (0-100). */
  fun updateMappingQuality(quality: Int) {
    _mappingQuality.value = quality
  }

  private fun updateStatusMessage(state: MeshState) {
    _statusMessage.value =
            when (state) {
              MeshState.DISCOVERING -> "Discovering peers..."
              MeshState.ELECTING -> "Electing leader..."
              MeshState.RESOLVING ->
                      if (_isLeader.value) "Hosting anchor..." else "Resolving anchor..."
              MeshState.CONNECTED -> if (_isLeader.value) "Connected (Leader)" else "Connected"
            }
  }

  /** Set parent node for line renderer. */
  fun setVisualizationParent(node: Node) {
    lineRenderer.setParentNode(node)
  }

  override fun onCleared() {
    super.onCleared()

    if (isInitialized) {
      nearbyManager.cleanup()
      meshManager.cleanup()
    }

    cloudAnchorManager.cleanup()
    poseManager.cleanup()
    lineRenderer.cleanup()
  }
}
