package com.hinnka.mycamera.camera.quickswitch

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ZoomTransitionControllerTest {

    @Test
    fun testTransitionInitializationAndSteadyState() = runTest {
        var currentTime = 1000L
        val controller = ZoomTransitionController(
            uptimeMillisProvider = { currentTime }
        )
        assertFalse(controller.isTransitionActive)

        controller.setSteadyStatePreview(2.0f)
        assertEquals(2.0f, controller.getCurrentSteadyZoom(), 0.001f)
    }

    @Test
    fun testTransitionCurveAndCompletion() = runTest {
        var currentTime = 1000L
        var lastZoom = 0f
        val controller = ZoomTransitionController(
            uptimeMillisProvider = { currentTime }
        )

        controller.startTransition(
            fromZoom = 1.0f,
            toZoom = 2.0f,
            scope = TestScope(coroutineContext),
            canFrameHandoff = false,
            uwEquivalentFocalMm = 13.0f,
            mainEquivalentFocalMm = 24.0f,
            onFrameHandoffTriggered = { _, _ -> },
            onZoomUpdate = { lastZoom = it },
            onComplete = { lastZoom = it }
        )

        assertTrue(controller.isTransitionActive)
        currentTime = 1400L
        controller.onPreviewFrameSourceAvailable(PreviewStreamSource.MAIN)
        assertEquals(2.0f, lastZoom, 0.05f)
    }
}
