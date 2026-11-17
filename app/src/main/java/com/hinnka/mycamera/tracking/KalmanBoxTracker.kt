package com.hinnka.mycamera.tracking

import kotlin.math.max

/**
 * Constant-velocity Kalman filter for [cx, cy, w, h, vx, vy].
 *
 * predict() has one owner: SubjectTracker.onCameraFrame(). Detection callbacks only enqueue
 * observations and never advance this filter. Measurement velocity is derived from the time
 * between this track's own observations, not from a prediction residual divided by frame dt.
 */
internal class KalmanBoxTracker(
    initial: TrackingRect,
    initialTimestampNs: Long,
) {
    private var cx = initial.centerX
    private var cy = initial.centerY
    private var width = initial.width
    private var height = initial.height
    private var vx = 0f
    private var vy = 0f

    private var pxPosition = 0.02f
    private var pxCross = 0f
    private var pxVelocity = 0.20f
    private var pyPosition = 0.02f
    private var pyCross = 0f
    private var pyVelocity = 0.20f
    private var pWidth = 0.02f
    private var pHeight = 0.02f

    private var lastPredictionTimestampNs = initialTimestampNs
    private var lastObservationTimestampNs = initialTimestampNs
    private var lastObservedCx = initial.centerX
    private var lastObservedCy = initial.centerY
    private var lastObservedWidth = initial.width
    private var lastObservedHeight = initial.height

    val timestampNs: Long get() = lastPredictionTimestampNs
    val rect: TrackingRect get() = TrackingRect(cx, cy, width, height).clamped()
    val velocityX: Float get() = vx
    val velocityY: Float get() = vy

    /** Predict exactly once for each distinct camera frame timestamp. */
    fun predict(cameraTimestampNs: Long): TrackingRect {
        if (cameraTimestampNs <= lastPredictionTimestampNs) return rect
        val dt = (cameraTimestampNs - lastPredictionTimestampNs) / 1_000_000_000.0
        lastPredictionTimestampNs = cameraTimestampNs
        if (!dt.isFinite() || dt <= 0.0) return rect

        val t = dt.toFloat()
        cx += vx * t
        cy += vy * t

        val q = 0.025f
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t2 * t2
        pxPosition += 2f * t * pxCross + t2 * pxVelocity + q * t4 * 0.25f
        pxCross += t * pxVelocity + q * t3 * 0.5f
        pxVelocity += q * t2
        pyPosition += 2f * t * pyCross + t2 * pyVelocity + q * t4 * 0.25f
        pyCross += t * pyVelocity + q * t3 * 0.5f
        pyVelocity += q * t2
        pWidth = (pWidth + 0.0015f * t).coerceAtLeast(0.0001f)
        pHeight = (pHeight + 0.0015f * t).coerceAtLeast(0.0001f)
        return rect
    }

    /**
     * Correct a possibly delayed detector observation. The velocity observation is calculated
     * from consecutive detections and their real sensor timestamps. It is then used to project
     * the measurement to the filter's current frame time before the position update.
     */
    fun correct(measurement: TrackingRect, observationTimestampNs: Long): TrackingRect {
        if (observationTimestampNs <= lastObservationTimestampNs) return rect

        val observationDt = (observationTimestampNs - lastObservationTimestampNs) / 1_000_000_000f
        if (observationDt > 0.001f && observationDt.isFinite()) {
            val measuredVx = (measurement.centerX - lastObservedCx) / observationDt
            val measuredVy = (measurement.centerY - lastObservedCy) / observationDt
            // Alpha-beta velocity correction uses the observation interval, never camera-frame dt.
            vx = (vx * 0.38f + measuredVx * 0.62f).coerceIn(-2.5f, 2.5f)
            vy = (vy * 0.38f + measuredVy * 0.62f).coerceIn(-2.5f, 2.5f)
        }

        lastObservedCx = measurement.centerX
        lastObservedCy = measurement.centerY
        lastObservedWidth = measurement.width
        lastObservedHeight = measurement.height
        lastObservationTimestampNs = observationTimestampNs

        val ageSeconds = max(0f, (lastPredictionTimestampNs - observationTimestampNs) / 1_000_000_000f)
        val projectedX = measurement.centerX + vx * ageSeconds
        val projectedY = measurement.centerY + vy * ageSeconds
        updateAxisX(projectedX)
        updateAxisY(projectedY)
        updateScalarWidth(measurement.width)
        updateScalarHeight(measurement.height)
        return rect
    }

    /** Template-tracker correction is a position-only observation at the current frame time. */
    fun correctTemplate(measurement: TrackingRect, timestampNs: Long): TrackingRect {
        if (timestampNs < lastPredictionTimestampNs - 1_000_000L) return rect
        updateAxisX(measurement.centerX)
        updateAxisY(measurement.centerY)
        updateScalarWidth(measurement.width)
        updateScalarHeight(measurement.height)
        return rect
    }

    private fun updateAxisX(z: Float) {
        val measurementNoise = 0.0018f
        val innovation = pxPosition + measurementNoise
        val k0 = pxPosition / innovation
        val k1 = pxCross / innovation
        val residual = z - cx
        cx += k0 * residual
        vx += k1 * residual
        val oldPosition = pxPosition
        val oldCross = pxCross
        pxPosition = ((1f - k0) * oldPosition).coerceAtLeast(0.00001f)
        pxCross = (1f - k0) * oldCross
        pxVelocity = (pxVelocity - k1 * oldCross).coerceAtLeast(0.00001f)
    }

    private fun updateAxisY(z: Float) {
        val measurementNoise = 0.0018f
        val innovation = pyPosition + measurementNoise
        val k0 = pyPosition / innovation
        val k1 = pyCross / innovation
        val residual = z - cy
        cy += k0 * residual
        vy += k1 * residual
        val oldPosition = pyPosition
        val oldCross = pyCross
        pyPosition = ((1f - k0) * oldPosition).coerceAtLeast(0.00001f)
        pyCross = (1f - k0) * oldCross
        pyVelocity = (pyVelocity - k1 * oldCross).coerceAtLeast(0.00001f)
    }

    private fun updateScalarWidth(measurement: Float) {
        val gain = pWidth / (pWidth + 0.0025f)
        width = (width + gain * (measurement - width)).coerceIn(0.012f, 1f)
        pWidth = ((1f - gain) * pWidth).coerceAtLeast(0.00001f)
    }

    private fun updateScalarHeight(measurement: Float) {
        val gain = pHeight / (pHeight + 0.0025f)
        height = (height + gain * (measurement - height)).coerceIn(0.012f, 1f)
        pHeight = ((1f - gain) * pHeight).coerceAtLeast(0.00001f)
    }
}
