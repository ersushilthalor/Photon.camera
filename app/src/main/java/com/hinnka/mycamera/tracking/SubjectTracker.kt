package com.hinnka.mycamera.tracking

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Single-owner state machine. Camera frames call onCameraFrame(); detector callbacks only enqueue
 * timestamped observations. This is the only class that advances the Kalman prediction clock.
 */
internal class SubjectTracker {
    private data class DetectionBatch(val timestampNs: Long, val detections: List<TrackingDetection>)
    private data class TapRequest(val x: Float, val y: Float)

    private val incoming = ConcurrentLinkedQueue<DetectionBatch>()
    private val pendingTap = AtomicReference<TapRequest?>(null)
    private val templateTracker = NormalizedTemplateTracker()
    private val motion = ObjectMotionClassifier()

    private var filter: KalmanBoxTracker? = null
    private var state = SubjectTrackingState.IDLE
    private var subjectId: Long? = null
    private var nextSubjectId = 1L
    private var appearanceTemplate: FloatArray? = null
    private var lastDetections: List<TrackingDetection> = emptyList()
    private var lastGoodTimestampNs = 0L
    private var lastDetectionTimestampNs = 0L
    private var lastFrameTimestampNs = 0L
    private var confidence = 0f
    private var moving = false
    private var currentRect: TrackingRect? = null
    private var templateMode = false
    private var latestHumanFaceRect: TrackingRect? = null
    private var latestHumanFaceTimestampNs = 0L

    fun submitDetections(timestampNs: Long, detections: List<TrackingDetection>) {
        if (timestampNs <= 0L) return
        incoming.offer(DetectionBatch(timestampNs, detections.toList()))
    }

    fun requestTap(normalizedX: Float, normalizedY: Float) {
        pendingTap.set(TapRequest(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f)))
    }

    fun updateHumanFaceRect(rect: TrackingRect, timestampNs: Long) {
        if (timestampNs <= 0L) return
        latestHumanFaceRect = rect.clamped()
        latestHumanFaceTimestampNs = timestampNs
    }

    fun clearHumanFaceRect() {
        latestHumanFaceRect = null
        latestHumanFaceTimestampNs = 0L
    }

    fun unlock() {
        state = SubjectTrackingState.IDLE
        subjectId = null
        filter = null
        appearanceTemplate = null
        lastDetections = emptyList()
        lastGoodTimestampNs = 0L
        lastDetectionTimestampNs = 0L
        confidence = 0f
        moving = false
        currentRect = null
        templateMode = false
        clearHumanFaceRect()
        templateTracker.clear()
        motion.reset()
        pendingTap.set(null)
        incoming.clear()
    }

    /** One call per camera frame, using the camera sensor timestamp (nanoseconds). */
    fun onCameraFrame(timestampNs: Long, grayFrame: GrayFrame): SubjectTrackingSnapshot {
        if (timestampNs <= 0L || timestampNs <= lastFrameTimestampNs) return snapshot(timestampNs)
        lastFrameTimestampNs = timestampNs

        // Exactly one prediction per camera frame. No detector callback or timer predicts.
        val predicted = filter?.predict(timestampNs)
        if (predicted != null) currentRect = predicted

        val tap = pendingTap.getAndSet(null)
        if (tap != null) {
            val tapCandidates = lastDetections
                .filter { it.confidence >= 0.30f }
                .filter { isTapInside(tap, it.rect) }
            val recentFace = latestHumanFaceRect?.takeIf {
                latestHumanFaceTimestampNs > 0L &&
                    kotlin.math.abs(timestampNs - latestHumanFaceTimestampNs) <= HUMAN_FACE_HINT_MAX_AGE_NS
            }
            // Prefer the person detection that contains the stable Human Eye AF face
            // when the tap overlaps multiple people. Non-human targets keep normal selection.
            val faceMatched = if (recentFace != null) {
                tapCandidates.filter { detection ->
                    recentFace.centerX in detection.rect.left..detection.rect.right &&
                        recentFace.centerY in detection.rect.top..detection.rect.bottom
                }
            } else {
                emptyList()
            }
            val candidates = faceMatched.ifEmpty { tapCandidates }
            val candidate = candidates.minByOrNull {
                it.rect.centerDistance(TrackingRect(tap.x, tap.y, 0.01f, 0.01f))
            }
            if (candidate != null) {
                beginDetectionLock(candidate, timestampNs)
            } else {
                // Real template seed: following frames must match this image patch by NCC.
                templateTracker.begin(grayFrame, tap.x, tap.y)
                templateMode = true
                val seed = TrackingRect(
                    tap.x,
                    tap.y,
                    (26f / grayFrame.width).coerceIn(0.05f, 0.25f),
                    (34f / grayFrame.height).coerceIn(0.08f, 0.35f),
                ).clamped()
                filter = KalmanBoxTracker(seed, timestampNs)
                currentRect = seed
                subjectId = nextSubjectId++
                state = SubjectTrackingState.LOCKED
                confidence = 0.35f
                lastGoodTimestampNs = timestampNs
                motion.reset()
            }
        }

        if (templateMode && filter != null) {
            val templateBox = templateTracker.update(grayFrame)
            if (templateBox != null) {
                filter?.correctTemplate(templateBox, timestampNs)
                currentRect = filter?.rect ?: templateBox
                lastGoodTimestampNs = timestampNs
                confidence = max(confidence, 0.52f)
                if (state != SubjectTrackingState.LOST) state = SubjectTrackingState.LOCKED
            }
        }

        while (true) {
            val batch = incoming.poll() ?: break
            if (batch.timestampNs <= lastDetectionTimestampNs) continue
            lastDetections = batch.detections
            processDetectionBatch(batch)
            lastDetectionTimestampNs = batch.timestampNs
        }

        if (state == SubjectTrackingState.LOCKED &&
            lastGoodTimestampNs > 0L &&
            timestampNs - lastGoodTimestampNs > OCCLUDED_AFTER_NS
        ) {
            state = SubjectTrackingState.OCCLUDED
        }
        if (state == SubjectTrackingState.OCCLUDED &&
            lastGoodTimestampNs > 0L &&
            timestampNs - lastGoodTimestampNs > LOST_AFTER_NS
        ) {
            state = SubjectTrackingState.LOST
            confidence = 0f
            templateTracker.clear()
            templateMode = false
        }
        if (state == SubjectTrackingState.LOST) {
            // LOST is terminal for this lock. The crop returns to wide; we never adopt a new ID.
            currentRect = filter?.rect
        }
        return snapshot(timestampNs)
    }

    private fun processDetectionBatch(batch: DetectionBatch) {
        if (state == SubjectTrackingState.IDLE || state == SubjectTrackingState.LOST) return
        val target = filter ?: return
        if (batch.detections.isEmpty()) return

        val predictedRect = target.rect
        val scored = batch.detections
            .filter { it.confidence >= 0.30f }
            .map { detection -> detection to associationScore(predictedRect, detection) }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return

        val best = scored[0]
        val second = scored.getOrNull(1)
        // Ambiguous look-alikes: keep the Kalman/template prediction instead of switching IDs.
        if (second != null && best.second - second.second < AMBIGUITY_MARGIN) return
        if (best.second < MIN_ASSOCIATION_SCORE) return

        val selected = best.first
        target.correct(selected.rect, batch.timestampNs)
        currentRect = target.rect
        confidence = selected.confidence.coerceIn(0f, 1f)
        lastGoodTimestampNs = max(lastGoodTimestampNs, batch.timestampNs)
        state = SubjectTrackingState.LOCKED
        templateMode = false
        templateTracker.clear()
        moving = motion.observe(selected.rect, batch.timestampNs)

        // Slow EMA update only for a confident, isolated match.
        val otherOverlaps = batch.detections.any {
            it !== selected && it.rect.iou(selected.rect) > 0.08f
        }
        if (selected.confidence >= 0.78f && !otherOverlaps && selected.appearance != null) {
            appearanceTemplate = HsvAppearanceModel.ema(appearanceTemplate, selected.appearance)
        }
    }

    private fun associationScore(predicted: TrackingRect, candidate: TrackingDetection): Float {
        val overlap = predicted.iou(candidate.rect)
        val diagonal = max(0.08f, kotlin.math.hypot(predicted.width, predicted.height))
        val distance = predicted.centerDistance(candidate.rect) / diagonal
        val distanceScore = (1f - distance / 3.2f).coerceIn(0f, 1f)
        val appearanceScore = HsvAppearanceModel.similarity(appearanceTemplate, candidate.appearance)

        val score = if (appearanceTemplate == null || candidate.appearance == null) {
            overlap * 0.62f + distanceScore * 0.38f
        } else {
            overlap * 0.42f + distanceScore * 0.30f + appearanceScore * 0.28f
        }
        val appearanceGate = appearanceTemplate != null && candidate.appearance != null &&
            appearanceScore < 0.16f && overlap < 0.18f
        val spatialGate = overlap < 0.015f && distance > 2.8f
        return if (appearanceGate || spatialGate) 0f else score
    }

    private fun beginDetectionLock(detection: TrackingDetection, timestampNs: Long) {
        filter = KalmanBoxTracker(detection.rect, timestampNs)
        currentRect = detection.rect
        subjectId = nextSubjectId++
        appearanceTemplate = detection.appearance?.copyOf()
        confidence = detection.confidence
        state = SubjectTrackingState.LOCKED
        lastGoodTimestampNs = timestampNs
        lastDetectionTimestampNs = detection.sourceTimestampNs
        moving = false
        motion.reset()
        templateMode = false
        templateTracker.clear()
    }

    private fun isTapInside(tap: TapRequest, rect: TrackingRect): Boolean {
        return tap.x >= rect.left - rect.width * 0.08f &&
            tap.x <= rect.right + rect.width * 0.08f &&
            tap.y >= rect.top - rect.height * 0.08f &&
            tap.y <= rect.bottom + rect.height * 0.08f
    }

    private fun snapshot(timestampNs: Long): SubjectTrackingSnapshot {
        val rect = currentRect ?: filter?.rect
        val velocityX = filter?.velocityX ?: 0f
        val velocityY = filter?.velocityY ?: 0f
        val message = when (state) {
            SubjectTrackingState.IDLE -> null
            SubjectTrackingState.LOCKED -> if (templateMode) "Following tapped region" else "Subject locked"
            SubjectTrackingState.OCCLUDED -> "Subject hidden — predicting movement"
            SubjectTrackingState.LOST -> "Subject lost — returned to wide view"
        }
        return SubjectTrackingSnapshot(
            state = state,
            subjectId = subjectId,
            rect = rect,
            confidence = confidence,
            velocityX = velocityX,
            velocityY = velocityY,
            isMoving = moving,
            message = message,
            timestampNs = timestampNs,
        )
    }

    companion object {
        private const val OCCLUDED_AFTER_NS = 450_000_000L
        private const val LOST_AFTER_NS = 4_000_000_000L
        private const val AMBIGUITY_MARGIN = 0.075f
        private const val MIN_ASSOCIATION_SCORE = 0.20f
        private const val HUMAN_FACE_HINT_MAX_AGE_NS = 1_200_000_000L
    }
}
