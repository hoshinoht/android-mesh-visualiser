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
import com.example.meshvisualiser.models.PoseData
import com.example.meshvisualiser.network.NearbyConnectionsManager
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import kotlin.random.Random
import kotlinx.coroutines.delay
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
        private const val ANCHOR_PLACEMENT_TIMEOUT_MS = 30_000L
        private const val RESOLVE_MAX_RETRIES = 5
        private const val RESOLVE_INITIAL_DELAY_MS = 2_000L
        private const val POSE_BROADCAST_MIN_INTERVAL_MS = 100L // 10 Hz max
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

    private val _peerPoses = MutableStateFlow<Map<Long, PoseData>>(emptyMap())
    val peerPoses: StateFlow<Map<Long, PoseData>> = _peerPoses.asStateFlow()

    private var anchorToHost: Anchor? = null
    private var isInitialized = false

    // Pose broadcast throttling (#8)
    private var lastPoseBroadcastMs = 0L

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

    /** Called when this device becomes the leader. (#1: starts anchor placement timeout) */
    private fun onBecomeLeader() {
        Log.d(TAG, "We are now the leader!")
        _isLeader.value = true
        _statusMessage.value = "Leader! Tap to place anchor..."

        // If anchor was already placed before election completed, host immediately
        anchorToHost?.let { anchor ->
            hostCloudAnchor(anchor)
            return
        }

        // #1: Start a timeout — if the leader hasn't placed an anchor, remind them
        viewModelScope.launch {
            delay(ANCHOR_PLACEMENT_TIMEOUT_MS)
            if (_meshState.value == MeshState.RESOLVING && anchorToHost == null) {
                _statusMessage.value = "Please tap a surface to place the anchor!"
                Log.w(TAG, "Anchor placement timeout — still waiting for leader to tap")
            }
        }
    }

    /** Called when a new leader is elected (and we're not it). (#2: retry resolve with backoff) */
    private fun onNewLeader(leaderId: Long, cloudAnchorId: String) {
        Log.d(TAG, "New leader: $leaderId, anchor: $cloudAnchorId")
        _isLeader.value = false
        _statusMessage.value = "Resolving anchor..."

        viewModelScope.launch {
            var retryDelay = RESOLVE_INITIAL_DELAY_MS

            for (attempt in 1..RESOLVE_MAX_RETRIES) {
                Log.d(TAG, "Resolve attempt $attempt/$RESOLVE_MAX_RETRIES")
                _statusMessage.value = "Resolving anchor (attempt $attempt)..."

                val anchor = cloudAnchorManager.resolveCloudAnchor(cloudAnchorId)
                if (anchor != null) {
                    poseManager.setSharedAnchor(anchor)
                    meshManager.setConnected()
                    _statusMessage.value = "Connected!"
                    return@launch
                }

                if (attempt < RESOLVE_MAX_RETRIES) {
                    Log.w(TAG, "Resolve failed, retrying in ${retryDelay}ms...")
                    _statusMessage.value = "Resolve failed, retrying..."
                    delay(retryDelay)
                    retryDelay = (retryDelay * 1.5).toLong() // exponential backoff
                }
            }

            _statusMessage.value = "Failed to resolve anchor after $RESOLVE_MAX_RETRIES attempts"
            Log.e(TAG, "All resolve attempts exhausted")
        }
    }

    /** Called when a peer sends a pose update. */
    private fun onPoseUpdate(peerId: Long, poseData: PoseData) {
        val currentPoses = _peerPoses.value.toMutableMap()
        currentPoses[peerId] = poseData
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
                val sharedAnchor = cloudAnchorManager.getSharedAnchor() ?: run {
                    _statusMessage.value = "Failed to get shared anchor"
                    return@launch
                }
                poseManager.setSharedAnchor(sharedAnchor)
                meshManager.announceLeadership(cloudAnchorId)
                meshManager.setConnected()
                _statusMessage.value = "Connected as leader!"
            } else {
                _statusMessage.value = "Failed to host anchor"
            }
        }
    }

    /**
     * Called every AR frame to update pose and visualizations.
     * #8: Throttled to avoid flooding the Nearby Connections channel.
     */
    fun updateFrame(cameraPose: Pose, myWorldPosition: Position) {
        if (_meshState.value != MeshState.CONNECTED) return

        val now = System.currentTimeMillis()
        val shouldBroadcast = now - lastPoseBroadcastMs >= POSE_BROADCAST_MIN_INTERVAL_MS

        // Calculate and broadcast our relative pose (throttled)
        if (shouldBroadcast) {
            poseManager.calculateRelativePose(cameraPose)?.let { poseData ->
                meshManager.broadcastPose(
                    poseData.x, poseData.y, poseData.z,
                    poseData.qx, poseData.qy, poseData.qz, poseData.qw
                )
                lastPoseBroadcastMs = now
            }
        }

        // Update peer visualizations (every frame for smooth rendering)
        _peerPoses.value.forEach { (peerId, poseData) ->
            poseManager.relativeToWorldPose(poseData)?.let { worldPose ->
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
