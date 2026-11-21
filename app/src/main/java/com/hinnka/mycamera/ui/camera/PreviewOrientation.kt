package com.hinnka.mycamera.ui.camera

import android.hardware.camera2.CameraCharacteristics

/**
 * Returns the preview-only rotation needed to align front-camera preview axes.
 * Camera2 sensor orientations are cardinal angles; Math.floorMod also keeps
 * the result valid for unusual vendor values.
 */
internal fun resolvePreviewOrientationOffset(
    lensFacing: Int,
    sensorOrientation: Int,
    rearSensorOrientation: Int?,
): Int {
    if (lensFacing != CameraCharacteristics.LENS_FACING_FRONT) return 0
    val rearOrientation = rearSensorOrientation ?: return 0
    val relativeRotation = Math.floorMod(sensorOrientation - rearOrientation, 360)

    // SurfaceTexture normalizes producer-buffer coordinates; it does not replace
    // the sensor-mount alignment required by this shared GL viewfinder. In
    // particular, opposite-mounted front sensors need the full 180-degree turn.
    return relativeRotation
}
