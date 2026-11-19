package com.hinnka.mycamera.ui.camera

import android.hardware.camera2.CameraCharacteristics

/**
 * Returns the preview-only rotation needed to put the front sensor in the rear
 * camera's orientation reference frame. Camera2 sensor orientations are cardinal
 * angles; Math.floorMod also keeps the result valid for unusual vendor values.
 */
internal fun resolvePreviewOrientationOffset(
    lensFacing: Int,
    sensorOrientation: Int,
    rearSensorOrientation: Int?,
): Int {
    if (lensFacing != CameraCharacteristics.LENS_FACING_FRONT) return 0
    val rearOrientation = rearSensorOrientation ?: return 0
    return Math.floorMod(sensorOrientation - rearOrientation, 360)
}
