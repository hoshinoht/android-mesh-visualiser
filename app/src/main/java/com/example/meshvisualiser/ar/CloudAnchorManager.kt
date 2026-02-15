package com.example.meshvisualiser.ar

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.HostCloudAnchorFuture
import com.google.ar.core.ResolveCloudAnchorFuture
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages ARCore Cloud Anchor hosting and resolving.
 *
 * Leader: Hosts a Cloud Anchor and shares the ID with followers. Followers: Resolve the shared
 * Cloud Anchor ID to establish shared coordinates.
 */
class CloudAnchorManager {
    companion object {
        private const val TAG = "CloudAnchorManager"
        private const val MAX_WAIT_TIME_MS = 60_000L
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
     * Calls the ARCore session on the Main thread (required for thread safety), then suspends
     * until the async callback fires with the result.
     *
     * @param anchor The local anchor to host
     * @return The Cloud Anchor ID if successful, null otherwise
     */
    suspend fun hostCloudAnchor(anchor: Anchor): String? {
        val currentSession = session ?: run {
            Log.e(TAG, "Session not set")
            return null
        }

        return try {
            withTimeoutOrNull(MAX_WAIT_TIME_MS) {
                suspendCancellableCoroutine { cont ->
                    // ARCore session methods must be called on the thread that owns the session.
                    // In SceneView, this is the Main thread.
                    val future = currentSession.hostCloudAnchorAsync(anchor, TTL_DAYS) { cloudAnchorId, state ->
                        Log.d(TAG, "Host callback: state=$state, id=$cloudAnchorId")
                        _hostingState.value = state

                        if (state == CloudAnchorState.SUCCESS && cloudAnchorId != null) {
                            _cloudAnchorId.value = cloudAnchorId
                            hostedAnchor = anchor
                            _sharedAnchor.value = anchor
                            if (cont.isActive) cont.resume(cloudAnchorId)
                        } else {
                            Log.e(TAG, "Cloud Anchor hosting failed: $state")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    hostFuture = future
                    cont.invokeOnCancellation { future.cancel() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hosting Cloud Anchor", e)
            null
        }
    }

    /**
     * Resolve a Cloud Anchor (Followers only).
     *
     * @param anchorId The Cloud Anchor ID to resolve
     * @return The resolved Anchor if successful, null otherwise
     */
    suspend fun resolveCloudAnchor(anchorId: String): Anchor? {
        val currentSession = session ?: run {
            Log.e(TAG, "Session not set")
            return null
        }

        return try {
            withTimeoutOrNull(MAX_WAIT_TIME_MS) {
                suspendCancellableCoroutine { cont ->
                    val future = currentSession.resolveCloudAnchorAsync(anchorId) { _, state ->
                        Log.d(TAG, "Resolve callback: state=$state")
                        _resolvingState.value = state

                        if (state == CloudAnchorState.SUCCESS) {
                            // Retrieve the resolved anchor from the future
                            val resultAnchor = resolveFuture?.resultAnchor
                            Log.d(TAG, "Cloud Anchor resolved successfully")
                            _cloudAnchorId.value = anchorId
                            resolvedAnchor = resultAnchor
                            _sharedAnchor.value = resultAnchor
                            if (cont.isActive) cont.resume(resultAnchor)
                        } else {
                            Log.e(TAG, "Cloud Anchor resolution failed: $state")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    resolveFuture = future
                    cont.invokeOnCancellation { future.cancel() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving Cloud Anchor", e)
            null
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
