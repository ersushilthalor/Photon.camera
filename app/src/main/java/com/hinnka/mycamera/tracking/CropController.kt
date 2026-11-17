package com.hinnka.mycamera.tracking

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Sole owner of crop smoothing. Position uses a time-based critically damped spring; zoom has an
 * independent easing time constant. Values are normalized so output behavior is FPS-independent.
 */
internal class CropController {
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var velocityX = 0f
    private var velocityY = 0f
    private var zoom = 1f
    private var lastTimestampNs = 0L
    private var gyroOffsetX = 0f
    private var gyroOffsetY = 0f
    private var lastGyroTimestampNs = 0L

    @Synchronized
    fun onGyroscopeSample(timestampNs: Long, x: Float, y: Float) {
        if (lastGyroTimestampNs > 0L && timestampNs > lastGyroTimestampNs) {
            val dt = ((timestampNs - lastGyroTimestampNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
            gyroOffsetX = (gyroOffsetX - y * dt * GYRO_GAIN).coerceIn(-0.08f, 0.08f)
            gyroOffsetY = (gyroOffsetY + x * dt * GYRO_GAIN).coerceIn(-0.08f, 0.08f)
        }
        lastGyroTimestampNs = timestampNs
    }

    @Synchronized
    fun step(
        snapshot: SubjectTrackingSnapshot,
        timestampNs: Long,
        settings: SubjectTrackingSettings,
    ): TrackingCrop {
        if (timestampNs <= 0L) return TrackingCrop(centerX, centerY, zoom)
        val dt = if (lastTimestampNs > 0L && timestampNs > lastTimestampNs) {
            ((timestampNs - lastTimestampNs) / 1_000_000_000f).coerceIn(0f, 0.12f)
        } else {
            1f / 30f
        }
        lastTimestampNs = timestampNs

        val isFollowing = snapshot.state == SubjectTrackingState.LOCKED ||
            snapshot.state == SubjectTrackingState.OCCLUDED
        val targetZoom = if (isFollowing) settings.sanitized().targetZoom else 1f
        var targetX = if (isFollowing && snapshot.rect != null) {
            snapshot.rect.centerX
        } else {
            0.5f
        }
        // Aim slightly above the box center (upper torso / face zone).
        var targetY = if (isFollowing && snapshot.rect != null) {
            snapshot.rect.top + snapshot.rect.height * 0.34f
        } else {
            0.5f
        }

        if (isFollowing && settings.cinematicAutoPan) {
            targetX += snapshot.velocityX * 0.12f
            targetY += snapshot.velocityY * 0.08f
        }
        if (settings.digitalGimbal) {
            targetX += gyroOffsetX
            targetY += gyroOffsetY
            val decay = exp(-dt / 0.45f)
            gyroOffsetX *= decay
            gyroOffsetY *= decay
        } else {
            gyroOffsetX = 0f
            gyroOffsetY = 0f
        }

        val tau = settings.speed.smoothingSeconds.coerceIn(0.06f, 1.2f)
        val (nextX, nextVelocityX) = criticallyDamped(centerX, velocityX, targetX, tau, dt)
        val (nextY, nextVelocityY) = criticallyDamped(centerY, velocityY, targetY, tau, dt)
        centerX = nextX
        centerY = nextY
        velocityX = nextVelocityX
        velocityY = nextVelocityY

        // Zoom eases separately; a return to 1x is deliberately gentle after LOST/unlock.
        val zoomAlpha = 1f - exp(-dt / ZOOM_TIME_CONSTANT_SECONDS)
        zoom += (targetZoom - zoom) * zoomAlpha
        zoom = zoom.coerceIn(1f, settings.targetZoom.coerceIn(1f, 8f).coerceAtLeast(1f))

        val cropFraction = 1f / zoom
        val halfCrop = cropFraction * 0.5f
        centerX = centerX.coerceIn(halfCrop, 1f - halfCrop)
        centerY = centerY.coerceIn(halfCrop, 1f - halfCrop)
        return TrackingCrop(centerX, centerY, zoom)
    }

    @Synchronized
    fun reset() {
        centerX = 0.5f
        centerY = 0.5f
        velocityX = 0f
        velocityY = 0f
        zoom = 1f
        lastTimestampNs = 0L
        gyroOffsetX = 0f
        gyroOffsetY = 0f
        lastGyroTimestampNs = 0L
    }

    private fun criticallyDamped(
        value: Float,
        velocity: Float,
        target: Float,
        tau: Float,
        dt: Float,
    ): Pair<Float, Float> {
        val omega = 2f / tau
        val error = value - target
        val temp = (velocity + omega * error) * dt
        val decay = exp(-omega * dt)
        val nextError = (error + temp) * decay
        val nextVelocity = (velocity - omega * temp) * decay
        return target + nextError to nextVelocity
    }

    companion object {
        private const val ZOOM_TIME_CONSTANT_SECONDS = 0.23f
        private const val GYRO_GAIN = 0.012f
    }
}
