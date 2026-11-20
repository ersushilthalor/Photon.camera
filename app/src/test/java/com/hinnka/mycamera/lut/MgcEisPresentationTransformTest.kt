package com.hinnka.mycamera.lut

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MgcEisPresentationTransformTest {

    @Test
    fun cameraKeyChangeResetsAndRecapturesOesPresentationTransform() {
        val transform = MgcEisPresentationTransform()
        val cameraKeyTracker = MgcEisCameraKeyTracker(
            initialKey = MgcEisCameraKey(sensorOrientation = 270, lensFacing = 1),
            presentationTransform = transform,
        )

        assertTrue(transform.captureFromOes(oesMatrix270()))
        val captured270 = transform.matrix.copyOf()
        assertEquals(1f, captured270[4])
        assertEquals(1f, captured270[1])

        assertTrue(
            cameraKeyTracker.update(
                MgcEisCameraKey(sensorOrientation = 90, lensFacing = 1),
            ),
        )
        assertEquals(1L, cameraKeyTracker.generation)
        assertArrayEquals(identityMatrix(), transform.matrix, 0.0001f)

        assertTrue(transform.captureFromOes(oesMatrix90()))
        assertNotEquals(captured270.toList(), transform.matrix.toList())
        assertEquals(-1f, transform.matrix[4])
        assertEquals(-1f, transform.matrix[1])
        assertEquals(1f, transform.matrix[12])
        assertEquals(1f, transform.matrix[13])

        assertFalse(
            cameraKeyTracker.update(
                MgcEisCameraKey(sensorOrientation = 90, lensFacing = 1),
            ),
        )
        assertEquals(1L, cameraKeyTracker.generation)
    }

    private fun oesMatrix270(): FloatArray = FloatArray(16).apply {
        this[1] = -1f
        this[4] = 1f
    }

    private fun oesMatrix90(): FloatArray = FloatArray(16).apply {
        this[1] = 1f
        this[4] = -1f
    }

    private fun identityMatrix(): FloatArray = FloatArray(16).apply {
        this[0] = 1f
        this[5] = 1f
        this[10] = 1f
        this[15] = 1f
    }
}
