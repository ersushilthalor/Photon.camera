package com.hinnka.mycamera.lut

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MgcEisPresentationTransformTest {

    @Test
    fun nativeOesTransformPreservesFrontMirrorButDropsProducerCropAndScale() {
        val transform = MgcEisPresentationTransform()
        val oesMatrix = FloatArray(16).apply {
            // A front-camera producer transform with horizontal reflection plus crop.
            this[0] = -0.82f
            this[5] = -0.79f
            this[10] = 1f
            this[12] = 0.91f
            this[13] = 0.88f
            this[15] = 1f
        }

        assertTrue(transform.updateFromOes(oesMatrix))
        assertArrayEquals(frontMirrorMatrix(), transform.matrix, 0f)
    }

    @Test
    fun nativeOesTransformCanRefreshAfterCameraHandoff() {
        val transform = MgcEisPresentationTransform()

        assertTrue(transform.updateFromOes(oesMatrix0()))
        assertArrayEquals(identityMatrix(), transform.matrix, 0f)

        assertTrue(transform.updateFromOes(oesMatrix180()))
        assertArrayEquals(frontMirrorMatrix(), transform.matrix, 0f)

        // A subsequent rear-camera frame replaces the reflection without carrying over
        // crop, scale, or orientation state from the previous producer.
        assertTrue(transform.updateFromOes(oesMatrix0()))
        assertArrayEquals(identityMatrix(), transform.matrix, 0f)
    }

    @Test
    fun nativeOesTransformHandlesBothQuarterTurnDirections() {
        val transform = MgcEisPresentationTransform()

        assertTrue(transform.updateFromOes(oesMatrix90()))
        assertArrayEquals(oesPresentationMatrix90(), transform.matrix, 0f)

        assertTrue(transform.updateFromOes(oesMatrix270()))
        assertArrayEquals(oesPresentationMatrix270(), transform.matrix, 0f)
    }

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

    private fun oesMatrix0() = FloatArray(16).apply {
        this[0] = 1f
        this[5] = -1f
        this[10] = 1f
        this[13] = 1f
        this[15] = 1f
    }

    private fun oesMatrix180() = FloatArray(16).apply {
        this[0] = -1f
        this[5] = -1f
        this[10] = 1f
        this[12] = 1f
        this[13] = 1f
        this[15] = 1f
    }

    private fun oesMatrix90() = FloatArray(16).apply {
        this[1] = 1f
        this[4] = -1f
    }

    private fun oesMatrix270() = FloatArray(16).apply {
        this[1] = -1f
        this[4] = 1f
    }

    private fun oesPresentationMatrix90() = FloatArray(16).apply {
        this[4] = -1f
        this[1] = -1f
        this[10] = 1f
        this[12] = 1f
        this[13] = 1f
        this[15] = 1f
    }

    private fun oesPresentationMatrix270() = FloatArray(16).apply {
        this[4] = 1f
        this[1] = 1f
        this[10] = 1f
        this[15] = 1f
    }

    private fun frontMirrorMatrix() = FloatArray(16).apply {
        this[0] = -1f
        this[5] = 1f
        this[10] = 1f
        this[12] = 1f
        this[15] = 1f
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
