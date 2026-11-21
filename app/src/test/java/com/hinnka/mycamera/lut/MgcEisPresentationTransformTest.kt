package com.hinnka.mycamera.lut

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MgcEisPresentationTransformTest {

    @Test
    fun sensorOrientationProducesCorrectImageReaderPresentationForAllCardinalAngles() {
        val transform = MgcEisPresentationTransform()

        val cases = listOf(
            0 to matrix0(),
            90 to matrix90(),
            180 to matrix180(),
            270 to matrix270(),
        )

        cases.forEach { (orientation, expected) ->
            transform.reset()
            assertTrue("orientation=$orientation", transform.captureFromSensorOrientation(orientation))
            assertArrayEquals("orientation=$orientation", expected, transform.matrix, 0f)
        }
    }

    @Test
    fun cameraSwitchResetsTransformAndUsesNewCameraSensorOrientation() {
        val transform = MgcEisPresentationTransform()
        val tracker = MgcEisCameraKeyTracker(
            initialKey = MgcEisCameraKey(
                sensorOrientation = 270,
                lensFacing = 1,
                cameraId = "rear",
            ),
            presentationTransform = transform,
        )

        assertTrue(transform.captureFromSensorOrientation(270))
        assertArrayEquals(matrix270(), transform.matrix, 0f)

        assertTrue(
            tracker.update(
                MgcEisCameraKey(
                    sensorOrientation = 90,
                    lensFacing = 0,
                    cameraId = "front",
                ),
            ),
        )
        assertEquals(1L, tracker.generation)
        assertArrayEquals(identityMatrix(), transform.matrix, 0f)

        assertTrue(transform.captureFromSensorOrientation(90))
        assertArrayEquals(matrix90(), transform.matrix, 0f)

        assertFalse(
            tracker.update(
                MgcEisCameraKey(
                    sensorOrientation = 90,
                    lensFacing = 0,
                    cameraId = "front",
                ),
            ),
        )
        assertEquals(1L, tracker.generation)
    }

    @Test
    fun cameraIdChangeInvalidatesTransformEvenWhenFacingAndOrientationMatch() {
        val transform = MgcEisPresentationTransform()
        val tracker = MgcEisCameraKeyTracker(
            initialKey = MgcEisCameraKey(
                sensorOrientation = 90,
                lensFacing = 1,
                cameraId = "rear-main",
            ),
            presentationTransform = transform,
        )

        assertTrue(transform.captureFromSensorOrientation(90))
        assertTrue(
            tracker.update(
                MgcEisCameraKey(
                    sensorOrientation = 90,
                    lensFacing = 1,
                    cameraId = "rear-telephoto",
                ),
            ),
        )
        assertEquals(1L, tracker.generation)
        assertArrayEquals(identityMatrix(), transform.matrix, 0f)
    }

    @Test
    fun invalidSensorOrientationDoesNotFreezeTheTransform() {
        val transform = MgcEisPresentationTransform()

        assertFalse(transform.captureFromSensorOrientation(45))
        assertTrue(transform.captureFromSensorOrientation(180))
        assertArrayEquals(matrix180(), transform.matrix, 0f)
    }

    private fun matrix0() = FloatArray(16).apply {
        this[0] = 1f
        this[5] = 1f
        this[10] = 1f
        this[15] = 1f
    }

    private fun matrix90() = FloatArray(16).apply {
        this[4] = -1f
        this[1] = 1f
        this[10] = 1f
        this[12] = 1f
        this[15] = 1f
    }

    private fun matrix180() = FloatArray(16).apply {
        this[0] = -1f
        this[5] = -1f
        this[10] = 1f
        this[12] = 1f
        this[13] = 1f
        this[15] = 1f
    }

    private fun matrix270() = FloatArray(16).apply {
        this[4] = 1f
        this[1] = -1f
        this[10] = 1f
        this[13] = 1f
        this[15] = 1f
    }

    private fun identityMatrix() = matrix0()
}
