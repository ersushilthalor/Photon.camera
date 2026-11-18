package com.hinnka.mycamera.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCapabilitiesResolverTest {

    @Test
    fun onlyAdvertisedFixedRangesAreExposed() {
        val result = resolveStrictAvailableFpsPresets(
            presets = listOf(
                VideoFpsPreset.FPS_24,
                VideoFpsPreset.FPS_25,
                VideoFpsPreset.FPS_30,
                VideoFpsPreset.FPS_50,
                VideoFpsPreset.FPS_60,
            ),
            advertisedRanges = listOf(
                15 to 30,
                30 to 30,
                30 to 60,
                60 to 60,
            ),
            minFrameDurationNs = 16_666_667L,
        )

        assertEquals(
            listOf(VideoFpsPreset.FPS_30, VideoFpsPreset.FPS_60),
            result,
        )
    }

    @Test
    fun unknownMinimumFrameDurationDoesNotClaimAnyRate() {
        val result = resolveStrictAvailableFpsPresets(
            presets = VideoFpsPreset.entries,
            advertisedRanges = listOf(30 to 30),
            minFrameDurationNs = 0L,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun streamDurationMustFitTheFixedFramePeriod() {
        val result = resolveStrictAvailableFpsPresets(
            presets = listOf(VideoFpsPreset.FPS_24, VideoFpsPreset.FPS_30),
            advertisedRanges = listOf(24 to 24, 30 to 30),
            minFrameDurationNs = 33_333_335L,
        )

        assertEquals(listOf(VideoFpsPreset.FPS_24), result)
    }
}
