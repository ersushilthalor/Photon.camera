package com.hinnka.mycamera.video

import android.os.SystemClock
import com.hinnka.mycamera.BuildConfig
import com.hinnka.mycamera.utils.PLog

/**
 * Debug-only, allocation-light recording diagnostics.
 *
 * Camera timestamps are in nanoseconds; encoder presentation timestamps are in
 * microseconds. Gaps are estimates against the requested frame interval, not
 * proof that the sensor or codec physically dropped a frame.
 */
internal class VideoCaptureDiagnostics {

    companion object {
        private const val TAG = "VideoDiagnostics"
        private const val LOG_INTERVAL_MS = 1_000L
    }

    private val enabled = BuildConfig.DEBUG

    private var active = false
    private var targetFps = 0
    private var expectedSensorIntervalNs = 0L
    private var expectedEncoderIntervalUs = 0L
    private var windowStartMs = 0L

    private var lastSensorTimestampNs = 0L
    private var lastEncoderPresentationTimeUs = Long.MIN_VALUE

    private var sensorFrames = 0L
    private var sensorDeltaCount = 0L
    private var sensorDeltaTotalNs = 0L
    private var sensorDeltaMinNs = Long.MAX_VALUE
    private var sensorDeltaMaxNs = 0L
    private var estimatedSensorDroppedFrames = 0L
    private var sensorTimestampRegressions = 0L

    private var encodedFrames = 0L
    private var encoderDeltaCount = 0L
    private var encoderDeltaTotalUs = 0L
    private var encoderDeltaMinUs = Long.MAX_VALUE
    private var encoderDeltaMaxUs = 0L
    private var estimatedEncoderDroppedFrames = 0L

    private var captureFailedCount = 0L
    private var captureBufferLostCount = 0L

    @Synchronized
    fun begin(fps: Int) {
        if (!enabled || fps <= 0) return
        active = true
        targetFps = fps
        expectedSensorIntervalNs = 1_000_000_000L / fps
        expectedEncoderIntervalUs = 1_000_000L / fps
        windowStartMs = SystemClock.elapsedRealtime()
        lastSensorTimestampNs = 0L
        lastEncoderPresentationTimeUs = Long.MIN_VALUE
        resetWindowCounters()
        PLog.i(TAG, "Recording diagnostics started: targetFps=$fps")
    }

    @Synchronized
    fun onSensorTimestamp(timestampNs: Long) {
        if (!active || timestampNs <= 0L) return
        if (lastSensorTimestampNs > 0L) {
            val deltaNs = timestampNs - lastSensorTimestampNs
            if (deltaNs > 0L) {
                sensorDeltaCount++
                sensorDeltaTotalNs += deltaNs
                sensorDeltaMinNs = minOf(sensorDeltaMinNs, deltaNs)
                sensorDeltaMaxNs = maxOf(sensorDeltaMaxNs, deltaNs)
                if (deltaNs > expectedSensorIntervalNs + expectedSensorIntervalNs / 2L) {
                    val representedIntervals =
                        ((deltaNs + expectedSensorIntervalNs / 2L) / expectedSensorIntervalNs)
                            .coerceAtLeast(1L)
                    estimatedSensorDroppedFrames += (representedIntervals - 1L).coerceAtLeast(1L)
                }
            } else if (deltaNs < 0L) {
                // A physical-camera handoff can change timestamp domains; do not
                // count that discontinuity as dropped frames.
                sensorTimestampRegressions++
            }
        }
        lastSensorTimestampNs = timestampNs
        sensorFrames++
        maybeLogWindow()
    }

    @Synchronized
    fun onEncoderPresentationTime(presentationTimeUs: Long) {
        if (!active || presentationTimeUs < 0L) return
        if (lastEncoderPresentationTimeUs != Long.MIN_VALUE) {
            val deltaUs = presentationTimeUs - lastEncoderPresentationTimeUs
            if (deltaUs > 0L) {
                encoderDeltaCount++
                encoderDeltaTotalUs += deltaUs
                encoderDeltaMinUs = minOf(encoderDeltaMinUs, deltaUs)
                encoderDeltaMaxUs = maxOf(encoderDeltaMaxUs, deltaUs)
                if (deltaUs > expectedEncoderIntervalUs + expectedEncoderIntervalUs / 2L) {
                    val representedIntervals =
                        ((deltaUs + expectedEncoderIntervalUs / 2L) / expectedEncoderIntervalUs)
                            .coerceAtLeast(1L)
                    estimatedEncoderDroppedFrames += (representedIntervals - 1L).coerceAtLeast(1L)
                }
            }
        }
        lastEncoderPresentationTimeUs = presentationTimeUs
        encodedFrames++
        maybeLogWindow()
    }

    @Synchronized
    fun onCaptureFailed() {
        if (!active) return
        captureFailedCount++
        maybeLogWindow()
    }

    @Synchronized
    fun onCaptureBufferLost() {
        if (!active) return
        captureBufferLostCount++
        maybeLogWindow()
    }

    @Synchronized
    fun finish() {
        if (!active) return
        logWindow(SystemClock.elapsedRealtime(), force = true)
        active = false
        PLog.i(TAG, "Recording diagnostics stopped")
    }

    private fun maybeLogWindow() {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - windowStartMs >= LOG_INTERVAL_MS) {
            logWindow(nowMs, force = false)
        }
    }

    private fun logWindow(nowMs: Long, force: Boolean) {
        val elapsedMs = nowMs - windowStartMs
        if (!force && elapsedMs < LOG_INTERVAL_MS) return
        if (elapsedMs <= 0L) return

        val sensorFps = if (sensorDeltaTotalNs > 0L && sensorDeltaCount > 0L) {
            sensorDeltaCount * 1_000_000_000.0 / sensorDeltaTotalNs.toDouble()
        } else {
            0.0
        }
        val encoderFps = if (encoderDeltaTotalUs > 0L && encoderDeltaCount > 0L) {
            encoderDeltaCount * 1_000_000.0 / encoderDeltaTotalUs.toDouble()
        } else {
            0.0
        }
        val averageSensorDeltaMs = if (sensorDeltaCount > 0L) {
            sensorDeltaTotalNs.toDouble() / sensorDeltaCount.toDouble() / 1_000_000.0
        } else {
            0.0
        }
        val averageEncoderDeltaMs = if (encoderDeltaCount > 0L) {
            encoderDeltaTotalUs.toDouble() / encoderDeltaCount.toDouble() / 1_000.0
        } else {
            0.0
        }

        PLog.i(
            TAG,
            "targetFps=$targetFps, sensorFps=${"%.2f".format(sensorFps)}, " +
                "sensorDeltaMs(avg/min/max)=${"%.2f".format(averageSensorDeltaMs)}/" +
                "${sensorDeltaMinNs.takeIf { it != Long.MAX_VALUE }?.div(1_000_000.0) ?: 0.0}/" +
                "${sensorDeltaMaxNs / 1_000_000.0}, " +
                "sensorGapDrops=$estimatedSensorDroppedFrames, " +
                "encoderFps=${"%.2f".format(encoderFps)}, " +
                "encoderPtsDeltaMs(avg/min/max)=${"%.2f".format(averageEncoderDeltaMs)}/" +
                "${encoderDeltaMinUs.takeIf { it != Long.MAX_VALUE }?.div(1_000.0) ?: 0.0}/" +
                "${encoderDeltaMaxUs / 1_000.0}, " +
                "encoderGapDrops=$estimatedEncoderDroppedFrames, " +
                "captureFailed=$captureFailedCount, bufferLost=$captureBufferLostCount, " +
                "sensorTimestampRegressions=$sensorTimestampRegressions"
        )

        windowStartMs = nowMs
        resetWindowCounters()
    }

    private fun resetWindowCounters() {
        sensorFrames = 0L
        sensorDeltaCount = 0L
        sensorDeltaTotalNs = 0L
        sensorDeltaMinNs = Long.MAX_VALUE
        sensorDeltaMaxNs = 0L
        estimatedSensorDroppedFrames = 0L
        sensorTimestampRegressions = 0L
        encodedFrames = 0L
        encoderDeltaCount = 0L
        encoderDeltaTotalUs = 0L
        encoderDeltaMinUs = Long.MAX_VALUE
        encoderDeltaMaxUs = 0L
        estimatedEncoderDroppedFrames = 0L
        captureFailedCount = 0L
        captureBufferLostCount = 0L
    }
}
