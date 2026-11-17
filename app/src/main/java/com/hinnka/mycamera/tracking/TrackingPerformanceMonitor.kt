package com.hinnka.mycamera.tracking

import java.util.ArrayDeque
import kotlin.math.max

/** Rolling 60-second performance window plus a two-second low-FPS hysteresis timer. */
internal class TrackingPerformanceMonitor {
    private val frameTimestampsNs = ArrayDeque<Long>()
    private val fallbackFrameTimestampsNs = ArrayDeque<Long>()
    private val detectorLatencies = ArrayDeque<Pair<Long, Long>>()
    private var lowFpsSinceNs = 0L
    private var fallbackApplied = false
    private var lastFrameTimestampNs = 0L

    data class Stats(val fps: Float, val detectorLatencyMs: Float)

    @Synchronized
    fun onFrame(timestampNs: Long): Stats {
        if (timestampNs <= 0L || timestampNs <= lastFrameTimestampNs) return stats()
        lastFrameTimestampNs = timestampNs
        frameTimestampsNs.addLast(timestampNs)
        fallbackFrameTimestampsNs.addLast(timestampNs)
        trimFrames(timestampNs)
        trimFallbackFrames(timestampNs)
        trimLatencies(timestampNs)
        return stats()
    }

    @Synchronized
    fun onDetectorLatency(latencyMs: Long) {
        if (lastFrameTimestampNs <= 0L) return
        detectorLatencies.addLast(lastFrameTimestampNs to latencyMs.coerceAtLeast(0L))
        trimLatencies(lastFrameTimestampNs)
    }

    @Synchronized
    fun shouldFallbackTo720p(
        timestampNs: Long,
        averageFps: Float,
        settings: SubjectTrackingSettings,
    ): Boolean {
        if (!settings.automaticResolutionFallback ||
            settings.inputResolution != SubjectTrackingInputResolution.P1080 ||
            fallbackApplied
        ) {
            lowFpsSinceNs = 0L
            return false
        }
        val fallbackFps = calculateFps(fallbackFrameTimestampsNs).takeIf { it > 0f } ?: averageFps
        if (fallbackFps < 26f) {
            if (lowFpsSinceNs == 0L) lowFpsSinceNs = timestampNs
            if (timestampNs - lowFpsSinceNs >= 2_000_000_000L) {
                fallbackApplied = true
                return true
            }
        } else {
            // Hysteresis: two continuous seconds below 26 FPS are required; never auto-upscale.
            lowFpsSinceNs = 0L
        }
        return false
    }

    @Synchronized
    fun resetFallbackForExplicitResolutionChange() {
        fallbackApplied = false
        lowFpsSinceNs = 0L
    }

    @Synchronized
    fun stats(): Stats {
        val first = frameTimestampsNs.peekFirst()
        val last = frameTimestampsNs.peekLast()
        val elapsedNs = if (first != null && last != null) last - first else 0L
        val fps = calculateFps(frameTimestampsNs)
        val latency = if (detectorLatencies.isNotEmpty()) {
            detectorLatencies.sumOf { it.second }.toFloat() / detectorLatencies.size
        } else 0f
        return Stats(fps = max(0f, fps), detectorLatencyMs = max(0f, latency))
    }

    private fun trimFrames(nowNs: Long) {
        while (frameTimestampsNs.size > 1 &&
            nowNs - (frameTimestampsNs.peekFirst() ?: nowNs) > WINDOW_NS
        ) {
            frameTimestampsNs.removeFirst()
        }
    }

    private fun trimFallbackFrames(nowNs: Long) {
        while (fallbackFrameTimestampsNs.size > 1 &&
            nowNs - (fallbackFrameTimestampsNs.peekFirst() ?: nowNs) > FALLBACK_WINDOW_NS
        ) {
            fallbackFrameTimestampsNs.removeFirst()
        }
    }

    private fun calculateFps(timestamps: ArrayDeque<Long>): Float {
        val first = timestamps.peekFirst()
        val last = timestamps.peekLast()
        val elapsedNs = if (first != null && last != null) last - first else 0L
        return if (timestamps.size >= 2 && elapsedNs > 0L) {
            (timestamps.size - 1) * 1_000_000_000f / elapsedNs.toFloat()
        } else 0f
    }

    private fun trimLatencies(nowNs: Long) {
        while (detectorLatencies.isNotEmpty() &&
            nowNs - (detectorLatencies.peekFirst()?.first ?: nowNs) > WINDOW_NS
        ) {
            detectorLatencies.removeFirst()
        }
    }

    companion object {
        private const val WINDOW_NS = 60_000_000_000L
        private const val FALLBACK_WINDOW_NS = 1_000_000_000L
    }
}
