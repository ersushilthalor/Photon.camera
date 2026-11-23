package com.hinnka.mycamera.video

import android.os.SystemClock

/**
 * Shared BOOTTIME origin for the two independently encoded Special Video files.
 *
 * Photon encodes each camera with its own VideoRecorder, so it does not use VCamera's
 * single-relay callback clock. Both recorders instead map Camera2 sensor timestamps to
 * BOOTTIME and subtract this exact same origin. Camera2 sessions are gated until both
 * streams are configured, preventing one file from starting while the other camera is
 * still being opened/configured.
 */
internal class SpecialVideoRecordingTimeline {
    @Volatile
    private var originUs: Long = UNSET

    @Synchronized
    fun begin(): Long {
        if (originUs == UNSET) {
            originUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        }
        return originUs
    }

    @Synchronized
    fun currentOriginUs(): Long? = originUs.takeIf { it != UNSET }

    @Synchronized
    fun reset() {
        originUs = UNSET
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
