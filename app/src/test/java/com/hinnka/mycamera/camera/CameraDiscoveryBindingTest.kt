package com.hinnka.mycamera.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDiscoveryBindingTest {

    @Test
    fun boundPhysicalEntryReplacesUnboundLogicalEntryAtSameFocalLength() {
        assertTrue(
            preferPhysicalCameraBindingForSameFocalLength(
                existingHasPhysicalBinding = false,
                candidateHasPhysicalBinding = true
            )
        )
    }

    @Test
    fun unboundEntryNeverReplacesBoundPhysicalEntry() {
        assertFalse(
            preferPhysicalCameraBindingForSameFocalLength(
                existingHasPhysicalBinding = true,
                candidateHasPhysicalBinding = false
            )
        )
    }

    @Test
    fun equalBindingStatusDoesNotReplaceExistingEntry() {
        assertFalse(
            preferPhysicalCameraBindingForSameFocalLength(
                existingHasPhysicalBinding = false,
                candidateHasPhysicalBinding = false
            )
        )
        assertFalse(
            preferPhysicalCameraBindingForSameFocalLength(
                existingHasPhysicalBinding = true,
                candidateHasPhysicalBinding = true
            )
        )
    }
}
