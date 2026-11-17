package com.hinnka.mycamera.tracking

import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class GrayFrame(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    companion object {
        fun fromImage(image: Image, maximumWidth: Int = 160): GrayFrame {
            val crop = image.cropRect
            val scale = min(1f, maximumWidth.toFloat() / crop.width().coerceAtLeast(1))
            val width = max(32, (crop.width() * scale).toInt())
            val height = max(18, (crop.height() * scale).toInt())
            val plane = image.planes[0]
            val buffer = plane.buffer
            val result = ByteArray(width * height)
            for (y in 0 until height) {
                val sourceY = crop.top + ((y + 0.5f) * crop.height() / height).toInt()
                for (x in 0 until width) {
                    val sourceX = crop.left + ((x + 0.5f) * crop.width() / width).toInt()
                    val index = sourceY * plane.rowStride + sourceX * plane.pixelStride
                    result[y * width + x] = buffer.get(index)
                }
            }
            return GrayFrame(width, height, result)
        }
    }
}

/** HSV histogram sampled from the center/torso region, not from the whole detector box. */
internal object HsvAppearanceModel {
    private const val H_BINS = 12
    private const val S_BINS = 4
    private const val V_BINS = 4
    private const val TOTAL_BINS = H_BINS * S_BINS * V_BINS

    fun fromRgb(
        rgb: ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        contentLeft: Int,
        contentTop: Int,
        contentWidth: Int,
        contentHeight: Int,
        rect: TrackingRect,
    ): FloatArray? {
        if (imageWidth <= 0 || imageHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) return null
        val left = (contentLeft + rect.left * contentWidth).toInt().coerceIn(contentLeft, contentLeft + contentWidth - 1)
        val right = (contentLeft + rect.right * contentWidth).toInt().coerceIn(left + 1, contentLeft + contentWidth)
        // Center torso excludes most of the background and the head/legs.
        val top = (contentTop + (rect.top + rect.height * 0.18f) * contentHeight)
            .toInt().coerceIn(contentTop, contentTop + contentHeight - 1)
        val bottom = (contentTop + (rect.top + rect.height * 0.78f) * contentHeight)
            .toInt().coerceIn(top + 1, contentTop + contentHeight)
        val histogram = FloatArray(TOTAL_BINS)
        var samples = 0
        val stepX = max(1, (right - left) / 22)
        val stepY = max(1, (bottom - top) / 26)
        for (y in top until bottom step stepY) {
            for (x in left until right step stepX) {
                val offset = (y * imageWidth + x) * 3
                if (offset + 2 >= rgb.capacity()) continue
                val red = (rgb.get(offset).toInt() and 0xff) / 255f
                val green = (rgb.get(offset + 1).toInt() and 0xff) / 255f
                val blue = (rgb.get(offset + 2).toInt() and 0xff) / 255f
                val maxChannel = max(red, max(green, blue))
                val minChannel = min(red, min(green, blue))
                val delta = maxChannel - minChannel
                var hue = 0f
                if (delta > 0.0001f) {
                    hue = when (maxChannel) {
                        red -> ((green - blue) / delta) % 6f
                        green -> (blue - red) / delta + 2f
                        else -> (red - green) / delta + 4f
                    } / 6f
                    if (hue < 0f) hue += 1f
                }
                val saturation = if (maxChannel <= 0f) 0f else delta / maxChannel
                val h = (hue * H_BINS).toInt().coerceIn(0, H_BINS - 1)
                val s = (saturation * S_BINS).toInt().coerceIn(0, S_BINS - 1)
                val v = (maxChannel * V_BINS).toInt().coerceIn(0, V_BINS - 1)
                histogram[(h * S_BINS + s) * V_BINS + v] += 1f
                samples += 1
            }
        }
        if (samples < 8) return null
        for (i in histogram.indices) histogram[i] /= samples.toFloat()
        return histogram
    }

    fun similarity(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null || a.size != b.size) return 0f
        var intersection = 0f
        for (i in a.indices) intersection += min(a[i], b[i])
        return intersection.coerceIn(0f, 1f)
    }

    fun ema(template: FloatArray?, observation: FloatArray, alpha: Float = 0.08f): FloatArray {
        if (template == null || template.size != observation.size) return observation.copyOf()
        return FloatArray(template.size) { i ->
            template[i] * (1f - alpha) + observation[i] * alpha
        }
    }
}

/**
 * Tap fallback for regions with no detector box. It tracks a real image patch using normalized
 * cross-correlation on a downscaled grayscale frame until a COCO person detection confirms it.
 */
internal class NormalizedTemplateTracker {
    private var patch: ByteArray? = null
    private var patchWidth = 0
    private var patchHeight = 0
    private var centerX = 0
    private var centerY = 0
    private var misses = 0

    val active: Boolean get() = patch != null

    fun begin(frame: GrayFrame, x: Float, y: Float) {
        val width = min(26, max(12, frame.width / 7))
        val height = min(34, max(14, frame.height / 6))
        centerX = (x * frame.width).toInt().coerceIn(width / 2, frame.width - width / 2 - 1)
        centerY = (y * frame.height).toInt().coerceIn(height / 2, frame.height - height / 2 - 1)
        patchWidth = width
        patchHeight = height
        patch = extractPatch(frame, centerX, centerY, width, height)
        misses = 0
    }

    fun update(frame: GrayFrame): TrackingRect? {
        val template = patch ?: return null
        val radiusX = max(8, frame.width / 8)
        val radiusY = max(8, frame.height / 8)
        val minX = (centerX - radiusX).coerceAtLeast(patchWidth / 2)
        val maxX = (centerX + radiusX).coerceAtMost(frame.width - patchWidth / 2 - 1)
        val minY = (centerY - radiusY).coerceAtLeast(patchHeight / 2)
        val maxY = (centerY + radiusY).coerceAtMost(frame.height - patchHeight / 2 - 1)
        var bestScore = -1f
        var bestX = centerX
        var bestY = centerY
        val coarseStep = 2
        for (y in minY..maxY step coarseStep) {
            for (x in minX..maxX step coarseStep) {
                val score = normalizedCorrelation(template, frame, x, y)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }
        if (bestScore < 0.48f) {
            misses++
            return null
        }
        centerX = bestX
        centerY = bestY
        misses = 0
        // Refresh only very high-confidence patches to avoid template drift.
        if (bestScore > 0.82f) {
            val next = extractPatch(frame, centerX, centerY, patchWidth, patchHeight)
            for (i in template.indices) {
                template[i] = ((template[i].toInt() and 0xff) * 0.94f +
                    (next[i].toInt() and 0xff) * 0.06f).toInt().toByte()
            }
        }
        return TrackingRect(
            centerX / frame.width.toFloat(),
            centerY / frame.height.toFloat(),
            patchWidth / frame.width.toFloat(),
            patchHeight / frame.height.toFloat(),
        ).clamped()
    }

    fun clear() {
        patch = null
        patchWidth = 0
        patchHeight = 0
        misses = 0
    }

    private fun normalizedCorrelation(template: ByteArray, frame: GrayFrame, x: Int, y: Int): Float {
        val count = template.size
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        var sampleCount = 0
        for (dy in 0 until patchHeight step 2) {
            for (dx in 0 until patchWidth step 2) {
                // Use the original patch coordinate when subsampling; compact indexing misaligned
                // every row and made the fallback template compare unrelated pixels.
                val templateIndex = dy * patchWidth + dx
                val a = (template[templateIndex].toInt() and 0xff).toDouble()
                val b = (frame.pixels[(y - patchHeight / 2 + dy) * frame.width + (x - patchWidth / 2 + dx)].toInt() and 0xff).toDouble()
                sumA += a
                sumB += b
                sumAA += a * a
                sumBB += b * b
                sumAB += a * b
                sampleCount++
            }
        }
        val n = max(1, sampleCount).toDouble()
        val covariance = sumAB - sumA * sumB / n
        val varianceA = max(1.0, sumAA - sumA * sumA / n)
        val varianceB = max(1.0, sumBB - sumB * sumB / n)
        return (covariance / sqrt(varianceA * varianceB)).toFloat().coerceIn(-1f, 1f)
    }

    private fun extractPatch(frame: GrayFrame, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val result = ByteArray(width * height)
        var i = 0
        for (dy in 0 until height) {
            val row = (y - height / 2 + dy).coerceIn(0, frame.height - 1)
            for (dx in 0 until width) {
                val column = (x - width / 2 + dx).coerceIn(0, frame.width - 1)
                result[i++] = frame.pixels[row * frame.width + column]
            }
        }
        return result
    }
}

/** Per-object motion uses its own observation interval and a rolling robust speed window. */
internal class ObjectMotionClassifier(
    private val thresholdNormalizedPerSecond: Float = 0.055f,
) {
    private var lastCenterX: Float? = null
    private var lastCenterY: Float? = null
    private var lastTimestampNs: Long? = null
    private val rollingSpeeds = ArrayDeque<Float>()

    fun observe(rect: TrackingRect, timestampNs: Long): Boolean {
        val oldX = lastCenterX
        val oldY = lastCenterY
        val oldTimestamp = lastTimestampNs
        lastCenterX = rect.centerX
        lastCenterY = rect.centerY
        lastTimestampNs = timestampNs
        if (oldX != null && oldY != null && oldTimestamp != null && timestampNs > oldTimestamp) {
            val dt = (timestampNs - oldTimestamp) / 1_000_000_000f
            val distance = kotlin.math.hypot(rect.centerX - oldX, rect.centerY - oldY)
            // Ignore detector quantization/noise before converting displacement to speed.
            if (distance >= 0.006f && dt > 0f) {
                rollingSpeeds.addLast(distance / dt)
                while (rollingSpeeds.size > 7) rollingSpeeds.removeFirst()
            }
        }
        val sorted = rollingSpeeds.sorted()
        val median = sorted.getOrNull(sorted.size / 2) ?: 0f
        return median >= thresholdNormalizedPerSecond
    }

    fun reset() {
        lastCenterX = null
        lastCenterY = null
        lastTimestampNs = null
        rollingSpeeds.clear()
    }
}
