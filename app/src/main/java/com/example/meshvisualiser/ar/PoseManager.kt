package com.example.meshvisualiser.ar

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Pose

/**
 * Manages pose calculation and broadcasting for AR visualization.
 *
 * All poses are calculated relative to the shared Cloud Anchor to ensure consistent coordinates
 * across all devices.
 */
class PoseManager {
  companion object {
    private const val TAG = "PoseManager"
  }

  private var sharedAnchor: Anchor? = null

  /** Set the shared anchor for relative pose calculations. */
  fun setSharedAnchor(anchor: Anchor) {
    this.sharedAnchor = anchor
    Log.d(TAG, "Shared anchor set")
  }

  /**
   * Calculate camera pose relative to the shared anchor.
   *
   * @param cameraPose The camera's world pose
   * @return Relative pose (x, y, z) or null if no shared anchor
   */
  fun calculateRelativePose(cameraPose: Pose): Triple<Float, Float, Float>? {
    val anchor = sharedAnchor ?: return null

    // Get pose relative to anchor
    val anchorPose = anchor.pose
    val relativePose = anchorPose.inverse().compose(cameraPose)

    return Triple(relativePose.tx(), relativePose.ty(), relativePose.tz())
  }

  /**
   * Convert a relative pose back to world coordinates.
   *
   * @param relativeX Relative X coordinate
   * @param relativeY Relative Y coordinate
   * @param relativeZ Relative Z coordinate
   * @return World pose or null if no shared anchor
   */
  fun relativeToWorldPose(relativeX: Float, relativeY: Float, relativeZ: Float): Pose? {
    val anchor = sharedAnchor ?: return null

    // Create relative pose
    val relativePose = Pose.makeTranslation(relativeX, relativeY, relativeZ)

    // Transform to world coordinates
    return anchor.pose.compose(relativePose)
  }

  /** Get the shared anchor's pose. */
  fun getAnchorPose(): Pose? = sharedAnchor?.pose

  /** Check if we have a valid shared anchor. */
  fun hasSharedAnchor(): Boolean = sharedAnchor != null

  /** Cleanup resources. */
  fun cleanup() {
    sharedAnchor = null
  }
}
