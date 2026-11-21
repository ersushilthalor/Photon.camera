package com.hinnka.mycamera.ui.camera

import android.hardware.camera2.CameraCharacteristics

/**
 * Returns a preview-only half-turn when the front sensor is mounted opposite to the rear
 * reference sensor. Surface/capture orientation metadata is deliberately left untouched.
 */
internal fun resolvePreviewOrientationOffset(
    lensFacing: Int,
    sensorOrientation: Int,
    rearSensorOrientation: Int?,
): Int {
    if (lensFacing != CameraCharacteristics.LENS_FACING_FRONT) return 0
    val rearOrientation = rearSensorOrientation ?: return 0
    val relativeRotation = Math.floorMod(sensorOrientation - rearOrientation, 360)
    return if (relativeRotation == 180) 180 else 0
}
