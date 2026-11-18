package com.hinnka.mycamera.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialVideoPhysicalCameraPairResolverTest {

    @Test
    fun resolvesOneXAndUltraWideFromRearPhysicalOutputs() {
        val pair = resolveSpatialVideoPhysicalCameraPair(
            selectedCameraIsBackFacing = true,
            candidates = listOf(
                SpatialVideoPhysicalCameraCandidate("rear-main", true, 1.0f),
                SpatialVideoPhysicalCameraCandidate("rear-ultra-wide", true, 0.5f),
                SpatialVideoPhysicalCameraCandidate("rear-tele", true, 3.0f),
            ),
        )

        assertEquals(SpatialVideoPhysicalCameraPair("rear-main", "rear-ultra-wide"), pair)
    }

    @Test
    fun selectedUltraWideLensDoesNotPreventResolvingTheSameRearPair() {
        // The selected zoom ratio is intentionally not an input to the pair resolver.
        // Selecting 0.5x must not make the 1x + 0.5x Special Video pair unavailable.
        val pair = resolveSpatialVideoPhysicalCameraPair(
            selectedCameraIsBackFacing = true,
            candidates = listOf(
                SpatialVideoPhysicalCameraCandidate("rear-main", true, 1.0f),
                SpatialVideoPhysicalCameraCandidate("rear-ultra-wide", true, 0.5f),
            ),
        )

        assertEquals(SpatialVideoPhysicalCameraPair("rear-main", "rear-ultra-wide"), pair)
    }

    @Test
    fun ignoresFrontCameraAndInvalidZoomCandidates() {
        val pair = resolveSpatialVideoPhysicalCameraPair(
            selectedCameraIsBackFacing = true,
            candidates = listOf(
                SpatialVideoPhysicalCameraCandidate("front", false, 1.0f),
                SpatialVideoPhysicalCameraCandidate("bad", true, Float.NaN),
                SpatialVideoPhysicalCameraCandidate("rear-main", true, 1.0f),
                SpatialVideoPhysicalCameraCandidate("rear-ultra-wide", true, 0.5f),
            ),
        )

        assertEquals(SpatialVideoPhysicalCameraPair("rear-main", "rear-ultra-wide"), pair)
    }

    @Test
    fun rejectsFrontFacingSelectionAndIncompleteRearPairs() {
        val rearPair = listOf(
            SpatialVideoPhysicalCameraCandidate("rear-main", true, 1.0f),
            SpatialVideoPhysicalCameraCandidate("rear-ultra-wide", true, 0.5f),
        )

        assertNull(resolveSpatialVideoPhysicalCameraPair(false, rearPair))
        assertNull(
            resolveSpatialVideoPhysicalCameraPair(
                selectedCameraIsBackFacing = true,
                candidates = listOf(SpatialVideoPhysicalCameraCandidate("rear-main", true, 1.0f)),
            ),
        )
    }
}
