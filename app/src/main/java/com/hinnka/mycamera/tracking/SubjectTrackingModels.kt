package com.hinnka.mycamera.tracking

import kotlin.math.max
import kotlin.math.min

enum class SubjectTrackingState { IDLE, LOCKED, OCCLUDED, LOST }
enum class SubjectTrackingSpeed(val smoothingSeconds: Float) {
    SMOOTH(0.62f), NORMAL(0.34f), FAST(0.18f), ULTRA(0.09f)
}
enum class SubjectTrackingInputResolution(val width: Int, val height: Int) {
    P720(1280, 720), P1080(1920, 1080)
}

data class TrackingRect(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
) {
    val left: Float get() = centerX - width * 0.5f
    val top: Float get() = centerY - height * 0.5f
    val right: Float get() = centerX + width * 0.5f
    val bottom: Float get() = centerY + height * 0.5f

    fun clamped(): TrackingRect {
        val w = width.coerceIn(0.012f, 1f)
        val h = height.coerceIn(0.012f, 1f)
        return copy(
            centerX = centerX.coerceIn(w * 0.5f, 1f - w * 0.5f),
            centerY = centerY.coerceIn(h * 0.5f, 1f - h * 0.5f),
            width = w,
            height = h,
        )
    }

    fun iou(other: TrackingRect): Float {
        val intersectionWidth = max(0f, min(right, other.right) - max(left, other.left))
        val intersectionHeight = max(0f, min(bottom, other.bottom) - max(top, other.top))
        val intersection = intersectionWidth * intersectionHeight
        val union = width * height + other.width * other.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    fun centerDistance(other: TrackingRect): Float {
        val dx = centerX - other.centerX
        val dy = centerY - other.centerY
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        fun fromLTRB(left: Float, top: Float, right: Float, bottom: Float): TrackingRect {
            val safeLeft = left.coerceIn(0f, 1f)
            val safeTop = top.coerceIn(0f, 1f)
            val safeRight = right.coerceIn(safeLeft, 1f)
            val safeBottom = bottom.coerceIn(safeTop, 1f)
            return TrackingRect(
                centerX = (safeLeft + safeRight) * 0.5f,
                centerY = (safeTop + safeBottom) * 0.5f,
                width = (safeRight - safeLeft).coerceAtLeast(0.012f),
                height = (safeBottom - safeTop).coerceAtLeast(0.012f),
            ).clamped()
        }
    }
}

data class TrackingDetection(
    val rect: TrackingRect,
    val confidence: Float,
    val appearance: FloatArray?,
    val sourceTimestampNs: Long,
)

data class SubjectTrackingSettings(
    val speed: SubjectTrackingSpeed = SubjectTrackingSpeed.NORMAL,
    val inputResolution: SubjectTrackingInputResolution = SubjectTrackingInputResolution.P720,
    val automaticResolutionFallback: Boolean = true,
    val digitalGimbal: Boolean = true,
    val cinematicAutoPan: Boolean = false,
    val humanEyeAssist: Boolean = true,
    val showTrackingBox: Boolean = true,
    val targetZoom: Float = 3f,
) {
    fun sanitized(): SubjectTrackingSettings = copy(targetZoom = targetZoom.coerceIn(1f, 8f))
}

data class TrackingCrop(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val zoom: Float = 1f,
) {
    companion object {
        val WIDE = TrackingCrop()
    }
}

data class SubjectTrackingSnapshot(
    val state: SubjectTrackingState = SubjectTrackingState.IDLE,
    val subjectId: Long? = null,
    val rect: TrackingRect? = null,
    val confidence: Float = 0f,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val isMoving: Boolean = false,
    val message: String? = null,
    val timestampNs: Long = 0L,
)

data class SubjectTrackingUiState(
    val enabled: Boolean = false,
    val isLoadingModel: Boolean = false,
    val modelError: String? = null,
    val settings: SubjectTrackingSettings = SubjectTrackingSettings(),
    val snapshot: SubjectTrackingSnapshot = SubjectTrackingSnapshot(),
    val averageFps: Float = 0f,
    val detectorLatencyMs: Float = 0f,
)
