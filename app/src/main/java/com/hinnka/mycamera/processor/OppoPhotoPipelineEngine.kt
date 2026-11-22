package com.hinnka.mycamera.processor

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Oppo-style photo rendering controls applied to the final rendered photo.
 * The input remains immutable; an independent output bitmap is returned.
 */
object OppoPhotoPipelineEngine {
    const val METADATA_KEY = "oppo.customPipeline.v1"

    suspend fun process(source: Bitmap, settingsJson: String?): Bitmap =
        withContext(Dispatchers.Default) {
            if (settingsJson.isNullOrBlank()) return@withContext source
            val params = runCatching { JSONObject(settingsJson) }.getOrNull()
                ?: return@withContext source
            val profile = params.optString("profile", "HASSELBLAD")
            val customMatrix = params.optJSONArray("customColorMatrix")
                ?.takeIf { it.length() == 9 }
                ?.let { values -> FloatArray(9) { index -> values.optDouble(index, if (index % 4 == 0) 1.0 else 0.0).toFloat() } }
            val profileMatrix = when (profile) {
                "CUSTOM" -> customMatrix ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                "HASSELBLAD" -> floatArrayOf(1.01f, -0.01f, 0f, -0.01f, 1.01f, 0f, 0f, -0.01f, 1.01f)
                "SAMSUNG" -> floatArrayOf(1.05f, -0.03f, -0.02f, -0.02f, 1.04f, -0.02f, -0.02f, -0.02f, 1.06f)
                "PIXEL" -> floatArrayOf(1.02f, -0.01f, -0.01f, -0.01f, 1.02f, -0.01f, -0.01f, -0.01f, 1.03f)
                "IPHONE" -> floatArrayOf(1.04f, 0f, -0.03f, -0.01f, 1.02f, -0.01f, -0.01f, 0f, 0.99f)
                else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
            val exposure = 2.0.pow(params.optDouble("exposure", 0.0).coerceIn(-2.0, 2.0)).toFloat()
            val contrast = params.optDouble("contrast", 1.0).toFloat().coerceIn(0.6f, 1.4f)
            val highlights = params.optDouble("highlights", -15.0).toFloat().coerceIn(-100f, 100f) / 100f
            val shadows = params.optDouble("shadows", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val whites = params.optDouble("whites", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val blacks = params.optDouble("blacks", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val highlightRollOff = params.optDouble("highlightRollOff", 15.0).toFloat().coerceIn(0f, 100f) / 100f
            val shadowRecovery = params.optDouble("shadowRecovery", 5.0).toFloat().coerceIn(0f, 100f) / 100f
            val localToneMapping = params.optDouble("localToneMapping", 0.0).toFloat().coerceIn(0f, 100f) / 100f
            val saturation = 1f + params.optDouble("saturation", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val vibrance = params.optDouble("vibrance", 5.0).toFloat().coerceIn(-100f, 100f) / 100f
            val temperature = params.optDouble("temperature", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val tint = params.optDouble("tint", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val sharpness = params.optDouble("sharpness", 18.0).toFloat().coerceIn(0f, 100f) / 100f
            val texture = params.optDouble("texture", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val microContrast = params.optDouble("microContrast", 0.0).toFloat().coerceIn(-100f, 100f) / 100f
            val noiseReduction = params.optDouble("noiseReduction", 10.0).toFloat().coerceIn(0f, 100f) / 100f
            val detailPreservation = params.optDouble("detailPreservation", 88.0).toFloat().coerceIn(0f, 100f) / 100f

            val sharpWeight = sharpness * 0.45f
            val microWeight = microContrast * 0.35f
            val textureWeight = texture * 0.30f
            val noiseReductionStrength = noiseReduction * 0.30f

            val width = source.width
            val height = source.height
            if (width <= 0 || height <= 0) return@withContext source
            // Work in bounded row bands; source and destination pixels stay out of one giant
            // temporary IntArray while spatial detail still sees a one-pixel neighbourhood.
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val chunkRows = 32
                var chunkStart = 0
                while (chunkStart < height) {
                val chunkEnd = min(height, chunkStart + chunkRows)
                val sourceStartRow = max(0, chunkStart - 1)
                val sourceEndRow = min(height, chunkEnd + 1)
                val sourceRows = sourceEndRow - sourceStartRow
                val chunkPixels = IntArray(width * sourceRows)
                source.getPixels(chunkPixels, 0, width, 0, sourceStartRow, width, sourceRows)

                fun pixelAt(x: Int, y: Int): Int =
                    chunkPixels[(y - sourceStartRow) * width + x]
                fun lum(color: Int): Float = (
                    0.2126f * ((color shr 16) and 0xFF) +
                        0.7152f * ((color shr 8) and 0xFF) +
                        0.0722f * (color and 0xFF)
                    ) / 255f
                val outputChunk = IntArray(width * (chunkEnd - chunkStart))

                for (y in chunkStart until chunkEnd) {
                    val localRow = (y - chunkStart) * width
                    for (x in 0 until width) {
                        val outputIndex = localRow + x
                        val color = pixelAt(x, y)
                        var r = ((color shr 16) and 0xFF) / 255f
                        var g = ((color shr 8) and 0xFF) / 255f
                        var b = (color and 0xFF) / 255f

                        r *= exposure * (1f + temperature * 0.25f + tint * 0.12f)
                        g *= exposure * (1f - tint * 0.25f)
                        b *= exposure * (1f - temperature * 0.25f + tint * 0.12f)

                        val mr = profileMatrix[0] * r + profileMatrix[1] * g + profileMatrix[2] * b
                        val mg = profileMatrix[3] * r + profileMatrix[4] * g + profileMatrix[5] * b
                        val mb = profileMatrix[6] * r + profileMatrix[7] * g + profileMatrix[8] * b
                        r = mr; g = mg; b = mb

                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b

                        // Match OppoCam's tone order: shoulder and toe, separate highlight/shadow
                        // adjustments, white/black points, contrast, then local tone compression.
                        if (highlightRollOff > 0.01f && luma > 0.65f) {
                            val highlightFactor = ((luma - 0.65f) / 0.35f).coerceIn(0f, 1f)
                            val shoulder = 1f - kotlin.math.exp(-highlightFactor * 2.2f) * 0.35f
                            val blend = highlightFactor * highlightRollOff * 0.45f
                            val scale = (1f - blend) + blend * shoulder
                            r *= scale; g *= scale; b *= scale
                        }
                        if (shadowRecovery > 0.01f && luma < 0.40f && luma > 0.02f) {
                            val shadowFactor = 1f - luma / 0.40f
                            val lift = shadowFactor * shadowRecovery * 0.35f
                            r += lift * (1f - r) * 0.3f
                            g += lift * (1f - g) * 0.3f
                            b += lift * (1f - b) * 0.3f
                        }
                        if (highlights != 0f && luma > 0.50f) {
                            val factor = ((luma - 0.50f) / 0.50f) * highlights * 0.4f
                            r += factor * r; g += factor * g; b += factor * b
                        }
                        if (shadows != 0f && luma < 0.50f) {
                            val factor = ((0.50f - luma) / 0.50f) * shadows * 0.4f
                            r += factor * (1f - r)
                            g += factor * (1f - g)
                            b += factor * (1f - b)
                        }
                        if (whites != 0f) {
                            val gain = 1f + whites * 0.25f
                            r *= gain; g *= gain; b *= gain
                        }
                        if (blacks != 0f && luma < 0.30f) {
                            val blackFactor = (1f - luma / 0.30f) * blacks * 0.20f
                            r += blackFactor; g += blackFactor; b += blackFactor
                        }
                        if (contrast != 1f) {
                            r = (r - 0.5f) * contrast + 0.5f
                            g = (g - 0.5f) * contrast + 0.5f
                            b = (b - 0.5f) * contrast + 0.5f
                        }
                        if (localToneMapping > 0.01f) {
                            val toneLuma = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0.001f, 1f)
                            val compressed = toneLuma / (1f + toneLuma * (localToneMapping * 0.8f))
                            val ratio = (compressed / toneLuma).coerceIn(0.5f, 2f)
                            val blend = localToneMapping * 0.5f
                            r = r * (1f - blend) + (r * ratio) * blend
                            g = g * (1f - blend) + (g * ratio) * blend
                            b = b * (1f - blend) + (b * ratio) * blend
                        }
                        r = r.coerceIn(0f, 1f)
                        g = g.coerceIn(0f, 1f)
                        b = b.coerceIn(0f, 1f)

                        val maxChannel = max(r, max(g, b))
                        val minChannel = min(r, min(g, b))
                        val currentSaturation = if (maxChannel > 0.001f) {
                            (maxChannel - minChannel) / maxChannel
                        } else 0f
                        val currentLuma = 0.299f * r + 0.587f * g + 0.114f * b
                        val isSkinTone = r > g && g > b &&
                            (r - g) > 0.03f && (r - g) < 0.35f &&
                            currentSaturation in 0.15f..0.75f
                        var saturationMultiplier = saturation
                        if (vibrance != 0f) {
                            val skinProtection = if (isSkinTone) 0.35f else 1f
                            saturationMultiplier +=
                                (1f - currentSaturation) * vibrance * skinProtection
                        }
                        if (kotlin.math.abs(saturationMultiplier - 1f) > 0.005f) {
                            r = (currentLuma + (r - currentLuma) * saturationMultiplier).coerceIn(0f, 1f)
                            g = (currentLuma + (g - currentLuma) * saturationMultiplier).coerceIn(0f, 1f)
                            b = (currentLuma + (b - currentLuma) * saturationMultiplier).coerceIn(0f, 1f)
                        }

                        if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                            val centerByte = lum(color) * 255f
                            val topByte = lum(pixelAt(x, y - 1)) * 255f
                            val bottomByte = lum(pixelAt(x, y + 1)) * 255f
                            val leftByte = lum(pixelAt(x - 1, y)) * 255f
                            val rightByte = lum(pixelAt(x + 1, y)) * 255f
                            val laplacian = 4f * centerByte - topByte - bottomByte - leftByte - rightByte
                            val edgeGradient = kotlin.math.abs(rightByte - leftByte) +
                                kotlin.math.abs(bottomByte - topByte)
                            val edgeAdaptive = (1f - edgeGradient / 255f).coerceIn(0.2f, 1f)

                            var detailDelta = laplacian *
                                (sharpWeight + textureWeight) * edgeAdaptive
                            if (microWeight != 0f) {
                                val localAverage = (centerByte + topByte + bottomByte + leftByte + rightByte) / 5f
                                detailDelta += (centerByte - localAverage) * microWeight
                            }
                            if (
                                noiseReductionStrength > 0.01f &&
                                edgeGradient < 35f * (1f - detailPreservation * 0.5f)
                            ) {
                                val smoothFactor = noiseReductionStrength *
                                    (1f - edgeGradient / 35f)
                                val localAverage = (topByte + bottomByte + leftByte + rightByte) / 4f
                                detailDelta -= (centerByte - localAverage) * smoothFactor
                            }
                            r = (r * 255f + detailDelta).coerceIn(0f, 255f) / 255f
                            g = (g * 255f + detailDelta).coerceIn(0f, 255f) / 255f
                            b = (b * 255f + detailDelta).coerceIn(0f, 255f) / 255f
                        }

                        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
                        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
                        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
                        outputChunk[outputIndex] = (color and -0x1000000) or (ri shl 16) or (gi shl 8) or bi
                    }
                }
                output.setPixels(outputChunk, 0, width, 0, chunkStart, width, chunkEnd - chunkStart)
                chunkStart = chunkEnd
                }
                output
            } catch (error: Throwable) {
                if (!output.isRecycled) output.recycle()
                throw error
            }
        }
}
