package com.hinnka.mycamera.video

import kotlin.math.abs

/** A physical rear-camera output that can be bound to a logical Camera2 session. */
internal data class SpatialVideoPhysicalCameraCandidate(
    val cameraId: String,
    val isBackFacing: Boolean,
    val intrinsicZoomRatio: Float,
)

internal data class SpatialVideoPhysicalCameraPair(
    val mainCameraId: String,
    val ultraWideCameraId: String,
)

/**
 * Resolves the rear outputs used by Special Video.
 *
 * The selected camera is only the entry point to the logical CameraDevice. Its zoom ratio
 * must not constrain which physical outputs are recorded: this always selects 1x + ultra-wide.
 */
internal fun resolveSpatialVideoPhysicalCameraPair(
    selectedCameraIsBackFacing: Boolean,
    candidates: List<SpatialVideoPhysicalCameraCandidate>,
): SpatialVideoPhysicalCameraPair? {
    if (!selectedCameraIsBackFacing) return null

    val validCandidates = candidates
        .asSequence()
        .filter { it.cameraId.isNotBlank() }
        .filter { it.isBackFacing }
        .filter { it.intrinsicZoomRatio.isFinite() && it.intrinsicZoomRatio > 0f }
        .distinctBy { it.cameraId }
        .toList()

    val main = validCandidates.minByOrNull { abs(it.intrinsicZoomRatio - 1f) }
        ?.takeIf { abs(it.intrinsicZoomRatio - 1f) <= 0.15f }
        ?: return null

    // Require a meaningfully wider physical field of view than the main camera.
    val widestAllowedRatio = minOf(0.85f, main.intrinsicZoomRatio - 0.15f)
    if (widestAllowedRatio < 0.2f) return null
    val ultraWide = validCandidates
        .filter { it.intrinsicZoomRatio in 0.2f..widestAllowedRatio }
        .minByOrNull { it.intrinsicZoomRatio }
        ?: return null

    if (main.cameraId == ultraWide.cameraId) return null
    return SpatialVideoPhysicalCameraPair(
        mainCameraId = main.cameraId,
        ultraWideCameraId = ultraWide.cameraId,
    )
}
