package com.hinnka.mycamera.ui.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOrientationTest {

    @Test
    fun appliesHalfTurnWhenFrontSensorIsMountedOppositeToRearSensor() {
        assertEquals(
            180,
            resolvePreviewOrientationOffset(
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
                sensorOrientation = 270,
                rearSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun normalizesOppositeMountHalfTurnInEitherDirection() {
        assertEquals(
            180,
            resolvePreviewOrientationOffset(
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
                sensorOrientation = 90,
                rearSensorOrientation = 270,
            ),
        )
    }

    @Test
    fun leavesFrontPreviewUnchangedWhenSensorsShareOrientation() {
        assertEquals(
            0,
            resolvePreviewOrientationOffset(
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
                sensorOrientation = 90,
                rearSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun neverChangesRearCameraPreview() {
        assertEquals(
            0,
            resolvePreviewOrientationOffset(
                lensFacing = CameraCharacteristics.LENS_FACING_BACK,
                sensorOrientation = 270,
                rearSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun preservesQuarterTurnCorrection() {
        assertEquals(
            90,
            resolvePreviewOrientationOffset(
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
                sensorOrientation = 0,
                rearSensorOrientation = 270,
            ),
        )
    }

    @Test
    fun leavesPreviewUnchangedWhenRearReferenceIsUnavailable() {
        assertEquals(
            0,
            resolvePreviewOrientationOffset(
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
                sensorOrientation = 270,
                rearSensorOrientation = null,
            ),
        )
    }
}
