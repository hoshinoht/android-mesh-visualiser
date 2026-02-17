package com.example.meshvisualiser.ui

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meshvisualiser.models.MeshState
import com.example.meshvisualiser.models.PeerInfo
import com.example.meshvisualiser.models.TransmissionMode
import com.example.meshvisualiser.simulation.CsmaState
import com.example.meshvisualiser.simulation.CsmacdState
import com.example.meshvisualiser.ui.components.*
import com.example.meshvisualiser.ui.theme.*
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                Log.d(TAG, "All permissions granted")
                initializeApp()
            } else {
                Toast.makeText(
                    this,
                    "Permissions required for AR mesh functionality",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent { MeshVisualiserTheme { MainScreen(viewModel = viewModel) } }

        // Request permissions
        if (PermissionHelper.hasAllPermissions(this)) {
            initializeApp()
        } else {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
        }
    }

    private fun initializeApp() {
        viewModel.initialize()
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val meshState by viewModel.meshState.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val isLeader by viewModel.isLeader.collectAsStateWithLifecycle()
    val currentLeaderId by viewModel.currentLeaderId.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val dataLogs by viewModel.dataLogs.collectAsStateWithLifecycle()
    val selectedPeerId by viewModel.selectedPeerId.collectAsStateWithLifecycle()
    val peerRttHistory by viewModel.peerRttHistory.collectAsStateWithLifecycle()
    val transmissionMode by viewModel.transmissionMode.collectAsStateWithLifecycle()
    val csmaState by viewModel.csmaState.collectAsStateWithLifecycle()
    val quizState by viewModel.quizState.collectAsStateWithLifecycle()

    var showGraph by remember { mutableStateOf(false) }
    // 0 = Topology, 1 = Connections
    var graphTab by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        // AR Scene
        ARSceneViewComposable(viewModel = viewModel)

        // Top: Status + Peer List
        Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            StatusOverlay(
                localId = viewModel.localId,
                meshState = meshState,
                peerCount = peers.size,
                isLeader = isLeader,
                currentLeaderId = currentLeaderId,
                statusMessage = statusMessage,
                isConnected = meshState == MeshState.CONNECTED,
                onToggleTopology = { showGraph = !showGraph },
                onStartQuiz = { viewModel.startQuiz() }
            )

            if (peers.isNotEmpty()) {
                PeerListPanel(
                    peers = peers,
                    selectedPeerId = selectedPeerId,
                    onSelectPeer = { viewModel.selectPeer(it) }
                )
            }

            // Graph panel (collapsible) — Topology or Connections
            AnimatedVisibility(visible = showGraph && peers.isNotEmpty()) {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        // Tab row to switch between views
                        TabRow(
                            selectedTabIndex = graphTab,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Tab(
                                selected = graphTab == 0,
                                onClick = { graphTab = 0 },
                                text = {
                                    Text("Topology", style = MaterialTheme.typography.labelSmall)
                                }
                            )
                            Tab(
                                selected = graphTab == 1,
                                onClick = { graphTab = 1 },
                                text = {
                                    Text("Connections", style = MaterialTheme.typography.labelSmall)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        if (graphTab == 0) {
                            TopologyView(
                                localId = viewModel.localId,
                                peers = peers,
                                leaderId = currentLeaderId,
                                peerRttHistory = peerRttHistory
                            )
                        } else {
                            ConnectionGraphView(
                                localId = viewModel.localId,
                                peers = peers,
                                dataLogs = dataLogs
                            )
                        }
                    }
                }
            }
        }

        // CSMA/CD overlay (above data exchange panel)
        if (transmissionMode == TransmissionMode.CSMA_CD &&
            csmaState.currentState != CsmaState.IDLE) {
            CsmacdOverlay(
                csmaState = csmaState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 260.dp)
            )
        }

        // Bottom: Data Exchange Panel
        if (peers.isNotEmpty()) {
            DataExchangePanel(
                dataLogs = dataLogs,
                selectedPeerId = selectedPeerId,
                peers = peers,
                transmissionMode = transmissionMode,
                onModeChanged = { viewModel.setTransmissionMode(it) },
                onSendTcp = { viewModel.sendTcpData() },
                onSendUdp = { viewModel.sendUdpData() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Quiz overlay (fullscreen modal)
        if (quizState.isActive) {
            QuizOverlay(
                quizState = quizState,
                onAnswer = { viewModel.answerQuiz(it) },
                onNext = { viewModel.nextQuestion() },
                onClose = { viewModel.closeQuiz() }
            )
        }
    }
}

@Composable
fun ARSceneViewComposable(viewModel: MainViewModel) {
    var arSceneView: ARSceneView? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) { onDispose { arSceneView?.destroy() } }

    AndroidView(
        factory = { ctx ->
            ARSceneView(ctx).apply {
                arSceneView = this

                configureSession { session, config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                }

                onFrame = { frameTime ->
                    this@apply.frame?.let { frame ->
                        val camera = frame.camera

                        if (camera.trackingState == TrackingState.TRACKING) {
                            val cameraPose = camera.pose
                            val cameraPosition =
                                Position(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())

                            viewModel.updateFrame(cameraPose, cameraPosition)

                            val quality = this@apply.session?.let {
                                estimateMappingQuality(it)
                            } ?: 0
                            viewModel.updateMappingQuality(quality)
                        }
                    }
                }

                onSessionCreated = { session ->
                    viewModel.setArSession(session)
                    viewModel.startMesh()
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun estimateMappingQuality(session: Session): Int {
    val trackingPlanes = session.getAllTrackables(Plane::class.java)
        .count { it.trackingState == TrackingState.TRACKING }
    return minOf(trackingPlanes * 20, 100)
}

@Composable
fun StatusOverlay(
    localId: Long,
    meshState: MeshState,
    peerCount: Int,
    isLeader: Boolean,
    currentLeaderId: Long,
    statusMessage: String,
    isConnected: Boolean = false,
    onToggleTopology: () -> Unit = {},
    onStartQuiz: () -> Unit = {}
) {
    val stateColor by animateColorAsState(
        targetValue = when (meshState) {
            MeshState.DISCOVERING -> StatusDiscovering
            MeshState.ELECTING -> StatusElecting
            MeshState.CONNECTED -> StatusConnected
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "statusColor"
    )

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // My ID
            Text(
                text = "My ID: ${localId.toString().takeLast(6)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.labelLarge,
                color = stateColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Chip row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = "Peers: $peerCount",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )

                if (isLeader) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = "★ LEADER",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = StatusLeader.copy(alpha = 0.2f),
                            labelColor = StatusLeader
                        )
                    )
                } else if (currentLeaderId != -1L) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = "Following: ${currentLeaderId.toString().takeLast(6)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                if (isConnected) {
                    AssistChip(
                        onClick = onToggleTopology,
                        label = {
                            Text(
                                text = "Topology",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                    AssistChip(
                        onClick = onStartQuiz,
                        label = {
                            Text(
                                text = "Quiz",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PeerListPanel(
    peers: Map<String, PeerInfo>,
    selectedPeerId: Long?,
    onSelectPeer: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val validPeers = peers.values.filter { it.hasValidPeerId }

    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Peers (${validPeers.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    validPeers.forEach { peer ->
                        val isSelected = peer.peerId == selectedPeerId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    onSelectPeer(if (isSelected) null else peer.peerId)
                                },
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else Color.Transparent,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Connection status dot
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(StatusConnected)
                                )
                                // Peer ID (short)
                                Text(
                                    text = peer.peerId.toString().takeLast(6),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                // Device model
                                Text(
                                    text = peer.deviceModel.ifEmpty { "Unknown" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Text(
                                        text = "TARGET",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DataExchangePanel(
    dataLogs: List<DataLogEntry>,
    selectedPeerId: Long?,
    peers: Map<String, PeerInfo>,
    transmissionMode: TransmissionMode,
    onModeChanged: (TransmissionMode) -> Unit,
    onSendTcp: () -> Unit,
    onSendUdp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(dataLogs.size) {
        if (dataLogs.isNotEmpty()) {
            listState.animateScrollToItem(dataLogs.lastIndex)
        }
    }

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Mode toggle
            ModeSegmentedButton(
                selectedMode = transmissionMode,
                onModeSelected = onModeChanged,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            // Send buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Data Exchange",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = onSendTcp,
                    enabled = selectedPeerId != null,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = LogTcp.copy(alpha = 0.2f)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Send TCP", color = LogTcp, style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(
                    onClick = onSendUdp,
                    enabled = selectedPeerId != null,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = LogUdp.copy(alpha = 0.2f)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Send UDP", color = LogUdp, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (selectedPeerId == null) {
                Text(
                    text = "Select a peer above to send data",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Log area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(4.dp)
            ) {
                if (dataLogs.isEmpty()) {
                    item {
                        Text(
                            text = "No data exchanged yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                items(dataLogs) { entry ->
                    val arrow = if (entry.direction == "OUT") "\u2192" else "\u2190"
                    val color = when (entry.protocol) {
                        "TCP" -> LogTcp
                        "UDP" -> LogUdp
                        "ACK" -> LogAck
                        "DROP", "RETRY" -> LogError
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    val seqStr = entry.seqNum?.let { " #$it" } ?: ""
                    val rttStr = entry.rttMs?.let { " [${it}ms]" } ?: ""
                    val modelStr = entry.peerModel.ifEmpty {
                        entry.peerId.toString().takeLast(6)
                    }

                    Text(
                        text = "[${timeFormat.format(Date(entry.timestamp))}] " +
                            "$arrow ${entry.protocol}$seqStr " +
                            "${if (entry.direction == "OUT") "to" else "from"} $modelStr " +
                            "(${entry.sizeBytes}B) ${entry.payload}$rttStr",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = color,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

