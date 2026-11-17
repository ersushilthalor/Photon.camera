package com.hinnka.mycamera.camera.quickswitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOpticalCalibrationTest {

    @Test
    fun testZoomToNormalizedLogConversion() {
        val uw = 13.0f
        val main = 24.0f
        val tel = 72.0f

        val normUw = CameraOpticalCalibration.zoomToNormalizedLog(1.0f, uw, main, tel)
        val normMain = CameraOpticalCalibration.zoomToNormalizedLog(1.0f, main, main, tel)
        val normTel = CameraOpticalCalibration.zoomToNormalizedLog(3.0f, tel, main, tel)

        assertTrue(normUw.isFinite())
        assertTrue(normMain.isFinite())
        assertTrue(normTel.isFinite())
        assertEquals(0.0f, CameraOpticalCalibration.normalizedToZoomLog(normMain, main, main, tel), 0.001f)
    }
}
