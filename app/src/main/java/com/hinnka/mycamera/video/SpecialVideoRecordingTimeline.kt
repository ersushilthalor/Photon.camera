package com.hinnka.mycamera.video

import android.os.SystemClock
import com.hinnka.mycamera.utils.PLog

/**
 * Shared BOOTTIME timeline for paired video recordings.
 *
 * The origin remains unavailable until both recorders have accepted their first frame. This
 * prevents the recorder that starts delivering frames first from retaining a leading interval
 * that the later-starting, video-only recorder cannot represent.
 */
internal class SpecialVideoRecordingTimeline {
    enum class StreamId(val logName: String) {
        MAIN("1x-main"),
        ULTRA_WIDE("0.5x-ultraWide"),
        SECONDARY("secondary"),
    }

    @Volatile
    private var originUs: Long = UNSET

    @Volatile
    private var stopTimeUs: Long = UNSET

    private var firstMainFramePtsUs: Long = UNSET
    private var firstSecondaryFramePtsUs: Long = UNSET

    @Synchronized
    fun reportFirstFrame(streamId: StreamId, ptsUs: Long) {
        when (streamId) {
            StreamId.MAIN -> {
                if (firstMainFramePtsUs != UNSET) return
                firstMainFramePtsUs = ptsUs
            }
            StreamId.ULTRA_WIDE, StreamId.SECONDARY -> {
                if (firstSecondaryFramePtsUs != UNSET) return
                firstSecondaryFramePtsUs = ptsUs
            }
        }

        PLog.i(TAG, "First accepted video frame: stream=${streamId.logName}, ptsUs=$ptsUs")

        if (originUs == UNSET &&
            firstMainFramePtsUs != UNSET &&
            firstSecondaryFramePtsUs != UNSET
        ) {
            originUs = maxOf(firstMainFramePtsUs, firstSecondaryFramePtsUs)
            PLog.i(
                TAG,
                "Shared video origin selected: mainFirstFramePtsUs=$firstMainFramePtsUs, " +
                    "secondaryFirstFramePtsUs=$firstSecondaryFramePtsUs, originUs=$originUs"
            )
        }
    }

    @Synchronized
    fun currentOriginUs(): Long? = originUs.takeIf { it != UNSET }

    /**
     * Capture one BOOTTIME cutoff before both recorders begin asynchronous shutdown.
     * Each recorder snapshots it before the controller resets this shared timeline.
     */
    @Synchronized
    fun requestStop(): Long {
        if (stopTimeUs == UNSET) {
            stopTimeUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            PLog.i(TAG, "Shared recording stop cutoff selected: stopTimeUs=$stopTimeUs")
        }
        return stopTimeUs
    }

    @Synchronized
    fun currentStopTimeUs(): Long? = stopTimeUs.takeIf { it != UNSET }

    @Synchronized
    fun reset() {
        originUs = UNSET
        stopTimeUs = UNSET
        firstMainFramePtsUs = UNSET
        firstSecondaryFramePtsUs = UNSET
    }

    private companion object {
        const val TAG = "SpecialVideoTimeline"
        const val UNSET = Long.MIN_VALUE
    }
}
