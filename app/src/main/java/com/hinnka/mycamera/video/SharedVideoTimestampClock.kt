package com.hinnka.mycamera.video

import android.os.SystemClock

/**
 * Shares the camera-timestamp to BOOTTIME calibration between the main and ultra-wide
 * Spatial Video encoders. Physical outputs belonging to one logical Camera2 device use
 * the same sensor clock; calibrating each encoder independently can introduce a startup
 * offset when SENSOR_INFO_TIMESTAMP_SOURCE is UNKNOWN.
 */
class SharedVideoTimestampClock {
    @Volatile
    private var cameraToBoottimeOffsetNs: Long = UNSET

    @Synchronized
    fun toBoottimeNs(cameraTimestampNs: Long): Long {
        if (cameraTimestampNs <= 0L) return cameraTimestampNs
        if (cameraToBoottimeOffsetNs == UNSET) {
            cameraToBoottimeOffsetNs = SystemClock.elapsedRealtimeNanos() - cameraTimestampNs
        }
        return cameraTimestampNs + cameraToBoottimeOffsetNs
    }

    companion object {
        private const val UNSET = Long.MIN_VALUE
    }
}
