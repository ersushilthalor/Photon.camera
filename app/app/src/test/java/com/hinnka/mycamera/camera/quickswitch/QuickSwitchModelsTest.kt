package com.hinnka.mycamera.camera.quickswitch

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QuickSwitchModelsTest {

    @Test
    fun testQuickLensDescriptorValues() {
        val descriptor = QuickLensDescriptor(
            cameraId = "0",
            facing = CameraCharacteristics.LENS_FACING_BACK,
            role = QuickLensRole.WIDE,
            equivalent35mmFocalMm = 24.0f,
            minZoom = 1.0f,
            maxZoom = 10.0f,
            isPrimaryMain = true,
            isZoomPreset = false
        )
        assertEquals("0", descriptor.cameraId)
        assertEquals(CameraCharacteristics.LENS_FACING_BACK, descriptor.facing)
        assertEquals(QuickLensRole.WIDE, descriptor.role)
        assertEquals(24.0f, descriptor.equivalent35mmFocalMm, 0.001f)
        assertEquals(1.0f, descriptor.minZoom, 0.001f)
        assertEquals(10.0f, descriptor.maxZoom, 0.001f)
        assertEquals(true, descriptor.isPrimaryMain)
    }

    @Test
    fun testDualStreamingModeEnum() {
        val mode = DualStreamingMode.CONCURRENT_DEVICES
        assertNotNull(mode)
        assertEquals("CONCURRENT_DEVICES", mode.name)
    }
}
