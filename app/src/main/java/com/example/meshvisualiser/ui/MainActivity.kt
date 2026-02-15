package com.example.meshvisualiser.ui

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meshvisualiser.models.MeshState
import com.example.meshvisualiser.ui.theme.*
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.collision.HitResult as SceneViewHitResult
import io.github.sceneview.math.Position

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
    val mappingQuality by viewModel.mappingQuality.collectAsStateWithLifecycle()

    var hasPlacedAnchor by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // AR Scene
        ARSceneViewComposable(viewModel = viewModel, onAnchorPlaced = { hasPlacedAnchor = true })

        // Status Overlay (top)
        StatusOverlay(
            localId = viewModel.localId,
            meshState = meshState,
            peerCount = peers.size,
            isLeader = isLeader,
            currentLeaderId = currentLeaderId,
            statusMessage = statusMessage
        )

        // Instructions (center)
        if (meshState == MeshState.RESOLVING && isLeader && !hasPlacedAnchor) {
            InstructionOverlay(
                message = "Tap to place anchor",
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Mapping Quality (bottom) - shown when hosting
        if (meshState == MeshState.RESOLVING && isLeader && hasPlacedAnchor) {
            MappingQualityOverlay(
                quality = mappingQuality,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }
    }
}

@Composable
fun ARSceneViewComposable(viewModel: MainViewModel, onAnchorPlaced: () -> Unit) {
    var arSceneView: ARSceneView? by remember { mutableStateOf(null) }
    var latestFrame: Frame? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) { onDispose { arSceneView?.destroy() } }

    AndroidView(
        factory = { ctx ->
            ARSceneView(ctx).apply {
                arSceneView = this

                // Configure Cloud Anchors
                configureSession { session, config ->
                    config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                }

                // Set up frame update listener
                onFrame = { frameTime ->
                    session?.let { session ->
                        val frame = session.update()
                        latestFrame = frame
                        val camera = frame.camera

                        if (camera.trackingState == TrackingState.TRACKING) {
                            val cameraPose = camera.pose
                            val cameraPosition =
                                Position(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())

                            // Update frame in view model
                            viewModel.updateFrame(cameraPose, cameraPosition)

                            // Estimate mapping quality from tracking
                            val quality = estimateMappingQuality(frame)
                            viewModel.updateMappingQuality(quality)
                        }
                    }
                }

                // Handle taps using onTouchEvent
                onTouchEvent = { motionEvent: MotionEvent, hitResult: SceneViewHitResult? ->
                    if (motionEvent.action == MotionEvent.ACTION_UP) {
                        latestFrame?.let { frame ->
                            val hits = frame.hitTest(motionEvent.x, motionEvent.y)

                            val planeHit = hits.firstOrNull { hit ->
                                val trackable = hit.trackable
                                trackable is Plane &&
                                    trackable.trackingState == TrackingState.TRACKING
                            }

                            planeHit?.let { hit ->
                                val anchor = hit.createAnchor()
                                viewModel.setAnchorToHost(anchor)
                                onAnchorPlaced()
                                Log.d("ARSceneView", "Anchor placed via ARCore hit test")
                            }
                        }
                    }
                    true
                }

                // Start mesh when scene is ready
                onSessionCreated = { session ->
                    viewModel.setArSession(session)
                    viewModel.startMesh()
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun estimateMappingQuality(frame: Frame): Int {
    val planes = frame.getUpdatedTrackables(Plane::class.java)
    val trackingPlanes = planes.count { it.trackingState == TrackingState.TRACKING }
    return minOf(trackingPlanes * 20, 100)
}

@Composable
fun StatusOverlay(
    localId: Long,
    meshState: MeshState,
    peerCount: Int,
    isLeader: Boolean,
    currentLeaderId: Long,
    statusMessage: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
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

            // State with color
            val stateColor = when (meshState) {
                MeshState.DISCOVERING -> StatusDiscovering
                MeshState.ELECTING -> StatusElecting
                MeshState.RESOLVING -> StatusResolving
                MeshState.CONNECTED -> StatusConnected
            }

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.labelLarge,
                color = stateColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Peer count and leader status in a row
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
            }
        }
    }
}

@Composable
fun InstructionOverlay(message: String, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun MappingQualityOverlay(quality: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Mapping Quality: $quality%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { quality / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = when {
                    quality < 30 -> Color.Red
                    quality < 70 -> Color.Yellow
                    else -> StatusConnected
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
