package com.example.meshvisualiser.ar

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.FutureState
import com.google.ar.core.HostCloudAnchorFuture
import com.google.ar.core.ResolveCloudAnchorFuture
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages ARCore Cloud Anchor hosting and resolving.
 *
 * Leader: Hosts a Cloud Anchor and shares the ID with followers. Followers: Resolve the shared
 * Cloud Anchor ID to establish shared coordinates.
 */
class CloudAnchorManager {
  companion object {
    private const val TAG = "CloudAnchorManager"
    private const val POLL_INTERVAL_MS = 100L
    private const val MAX_WAIT_TIME_MS = 60000L
    private const val TTL_DAYS = 1
  }

  private var session: Session? = null
  private var hostFuture: HostCloudAnchorFuture? = null
  private var resolveFuture: ResolveCloudAnchorFuture? = null
  private var hostedAnchor: Anchor? = null
  private var resolvedAnchor: Anchor? = null

  private val _hostingState = MutableStateFlow<CloudAnchorState?>(null)
  val hostingState: StateFlow<CloudAnchorState?> = _hostingState.asStateFlow()

  private val _resolvingState = MutableStateFlow<CloudAnchorState?>(null)
  val resolvingState: StateFlow<CloudAnchorState?> = _resolvingState.asStateFlow()

  private val _sharedAnchor = MutableStateFlow<Anchor?>(null)
  val sharedAnchor: StateFlow<Anchor?> = _sharedAnchor.asStateFlow()

  private val _cloudAnchorId = MutableStateFlow<String?>(null)
  val cloudAnchorId: StateFlow<String?> = _cloudAnchorId.asStateFlow()

  /** Set the ARCore session. */
  fun setSession(session: Session) {
    this.session = session
  }

  /**
   * Host a Cloud Anchor (Leader only).
   *
   * @param anchor The local anchor to host
   * @return The Cloud Anchor ID if successful, null otherwise
   */
  suspend fun hostCloudAnchor(anchor: Anchor): String? =
          withContext(Dispatchers.IO) {
            val currentSession =
                    session
                            ?: run {
                              Log.e(TAG, "Session not set")
                              return@withContext null
                            }

            try {
              Log.d(TAG, "Starting Cloud Anchor hosting...")

              // Host the anchor with 1-day TTL
              hostFuture =
                      currentSession.hostCloudAnchorAsync(anchor, TTL_DAYS) { cloudAnchorId, state
                        ->
                        Log.d(TAG, "Host callback: state=$state, id=$cloudAnchorId")
                      }

              // Poll for completion using FutureState
              var elapsed = 0L
              while (elapsed < MAX_WAIT_TIME_MS) {
                val future = hostFuture ?: return@withContext null

                when (future.state) {
                  FutureState.DONE -> {
                    // Future completed - check the result
                    val resultState = future.resultCloudAnchorState
                    _hostingState.value = resultState

                    if (resultState == CloudAnchorState.SUCCESS) {
                      val anchorId = future.resultCloudAnchorId
                      Log.d(TAG, "Cloud Anchor hosted successfully: $anchorId")
                      _cloudAnchorId.value = anchorId
                      // The anchor itself is still the original anchor reference
                      hostedAnchor = anchor
                      _sharedAnchor.value = anchor
                      return@withContext anchorId
                    } else {
                      Log.e(TAG, "Cloud Anchor hosting failed: $resultState")
                      return@withContext null
                    }
                  }
                  FutureState.CANCELLED -> {
                    Log.e(TAG, "Cloud Anchor hosting was cancelled")
                    return@withContext null
                  }
                  FutureState.PENDING -> {
                    // Still processing, continue waiting
                    delay(POLL_INTERVAL_MS)
                    elapsed += POLL_INTERVAL_MS
                  }
                }
              }

              Log.e(TAG, "Cloud Anchor hosting timed out")
              return@withContext null
            } catch (e: Exception) {
              Log.e(TAG, "Error hosting Cloud Anchor", e)
              return@withContext null
            }
          }

  /**
   * Resolve a Cloud Anchor (Followers only).
   *
   * @param anchorId The Cloud Anchor ID to resolve
   * @return The resolved Anchor if successful, null otherwise
   */
  suspend fun resolveCloudAnchor(anchorId: String): Anchor? =
          withContext(Dispatchers.IO) {
            val currentSession =
                    session
                            ?: run {
                              Log.e(TAG, "Session not set")
                              return@withContext null
                            }

            try {
              Log.d(TAG, "Starting Cloud Anchor resolution for: $anchorId")

              // Resolve the anchor
              resolveFuture =
                      currentSession.resolveCloudAnchorAsync(anchorId) { cloudId, state ->
                        Log.d(TAG, "Resolve callback: state=$state")
                      }

              // Poll for completion using FutureState
              var elapsed = 0L
              while (elapsed < MAX_WAIT_TIME_MS) {
                val future = resolveFuture ?: return@withContext null

                when (future.state) {
                  FutureState.DONE -> {
                    // Future completed - check the result
                    val resultState = future.resultCloudAnchorState
                    _resolvingState.value = resultState

                    if (resultState == CloudAnchorState.SUCCESS) {
                      val resultAnchor = future.resultAnchor
                      Log.d(TAG, "Cloud Anchor resolved successfully")
                      _cloudAnchorId.value = anchorId
                      resolvedAnchor = resultAnchor
                      _sharedAnchor.value = resultAnchor
                      return@withContext resultAnchor
                    } else {
                      Log.e(TAG, "Cloud Anchor resolution failed: $resultState")
                      return@withContext null
                    }
                  }
                  FutureState.CANCELLED -> {
                    Log.e(TAG, "Cloud Anchor resolution was cancelled")
                    return@withContext null
                  }
                  FutureState.PENDING -> {
                    // Still processing, continue waiting
                    delay(POLL_INTERVAL_MS)
                    elapsed += POLL_INTERVAL_MS
                  }
                }
              }

              Log.e(TAG, "Cloud Anchor resolution timed out")
              return@withContext null
            } catch (e: Exception) {
              Log.e(TAG, "Error resolving Cloud Anchor", e)
              return@withContext null
            }
          }

  /** Get the current shared anchor. */
  fun getSharedAnchor(): Anchor? = _sharedAnchor.value

  /** Cleanup resources. */
  fun cleanup() {
    hostFuture?.cancel()
    resolveFuture?.cancel()
    hostedAnchor?.detach()
    resolvedAnchor?.detach()
    hostFuture = null
    resolveFuture = null
    hostedAnchor = null
    resolvedAnchor = null
    _sharedAnchor.value = null
    _cloudAnchorId.value = null
    session = null
  }
}
