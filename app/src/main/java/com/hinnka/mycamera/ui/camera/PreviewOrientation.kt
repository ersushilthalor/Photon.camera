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

    // The live front SurfaceTexture has its own presentation transform. Applying
    // a second 180-degree turn in the GL preview produces the reported top/bottom
    // inversion on opposite-mounted front sensors. Keep quarter-turn corrections.
    return if (relativeRotation == 180) 0 else relativeRotation
}
