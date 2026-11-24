package com.hinnka.mycamera.data

import android.content.Context
import com.hinnka.mycamera.camera.PhotoMegapixelMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class OppoPhotoSettings(
    val photoMegapixelMode: PhotoMegapixelMode = PhotoMegapixelMode.M12,
    val autoHdrEnabled: Boolean = true,
    val fastShutterEnabled: Boolean = true,
    val fastShutterFps: Int = 20,
    val refocusEnabled: Boolean = false,
    val refocusFrameCount: Int = 3,
    val customPipelineEnabled: Boolean = false,
    val pipelineProfile: String = "HASSELBLAD",
    val customColorMatrix: List<Float> = listOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    ),
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val highlightRollOff: Float = 15f,
    val shadowRecovery: Float = 5f,
    val localToneMapping: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 5f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val sharpness: Float = 12f,
    val texture: Float = 0f,
    val microContrast: Float = 0f,
    val noiseReduction: Float = 10f,
    val detailPreservation: Float = 90f,
) {
    fun normalized(): OppoPhotoSettings = copy(
        // Fast Shutter always uses its maximum supported target; Refocus is fixed to 3 frames.
        fastShutterFps = 20,
        refocusFrameCount = 3,
        pipelineProfile = pipelineProfile.takeIf {
            it in setOf("NATURAL", "HASSELBLAD", "SAMSUNG", "PIXEL", "IPHONE", "CUSTOM")
        } ?: "HASSELBLAD",
        customColorMatrix = customColorMatrix.takeIf { it.size == 9 }?.map { it.coerceIn(-1f, 2f) }
            ?: listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        exposure = exposure.coerceIn(-2f, 2f),
        contrast = contrast.coerceIn(0.6f, 1.4f),
        highlights = highlights.coerceIn(-100f, 100f),
        shadows = shadows.coerceIn(-100f, 100f),
        whites = whites.coerceIn(-100f, 100f),
        blacks = blacks.coerceIn(-100f, 100f),
        highlightRollOff = highlightRollOff.coerceIn(0f, 100f),
        shadowRecovery = shadowRecovery.coerceIn(0f, 100f),
        localToneMapping = localToneMapping.coerceIn(0f, 100f),
        saturation = saturation.coerceIn(-100f, 100f),
        vibrance = vibrance.coerceIn(-100f, 100f),
        temperature = temperature.coerceIn(-100f, 100f),
        tint = tint.coerceIn(-100f, 100f),
        sharpness = sharpness.coerceIn(0f, 100f),
        texture = texture.coerceIn(-100f, 100f),
        microContrast = microContrast.coerceIn(-100f, 100f),
        noiseReduction = noiseReduction.coerceIn(0f, 100f),
        detailPreservation = detailPreservation.coerceIn(0f, 100f),
    )

    /** Selects a complete OppoCam rendering preset while keeping Photon’s settings UI. */
    fun withPhotoProfile(profile: String): OppoPhotoSettings {
        val normalizedProfile = profile.uppercase()
        if (normalizedProfile == "CUSTOM") {
            return copy(customPipelineEnabled = true, pipelineProfile = "CUSTOM").normalized()
        }
        val values = when (normalizedProfile) {
            "HASSELBLAD" -> listOf(0.0f, 1.02f, -12f, 6f, -6f, 0f, 42f, 8f, 12f, -1f, 5f, 0f, 0f, 18f, 8f, 10f, 14f, 88f)
            "SAMSUNG" -> listOf(0.08f, 1.10f, -18f, 22f, 8f, -5f, 22f, 22f, 32f, 14f, 18f, 2f, -1f, 38f, 20f, 18f, 26f, 76f)
            "PIXEL" -> listOf(-0.04f, 1.05f, -26f, 32f, 0f, -2f, 30f, 34f, 44f, 6f, 15f, -1f, 0f, 26f, 15f, 14f, 20f, 86f)
            "IPHONE" -> listOf(0.05f, 1.04f, -15f, 16f, -2f, 2f, 36f, 18f, 24f, 4f, 9f, 6f, 2f, 22f, 10f, 12f, 18f, 84f)
            else -> listOf(0f, 1f, 0f, 0f, 0f, 0f, 15f, 5f, 0f, 0f, 0f, 0f, 0f, 12f, 0f, 0f, 10f, 90f)
        }
        return copy(
            customPipelineEnabled = true,
            pipelineProfile = normalizedProfile.takeIf { it in setOf("NATURAL", "HASSELBLAD", "SAMSUNG", "PIXEL", "IPHONE") } ?: "CUSTOM",
            exposure = values[0],
            contrast = values[1],
            highlights = values[2],
            shadows = values[3],
            whites = values[4],
            blacks = values[5],
            highlightRollOff = values[6],
            shadowRecovery = values[7],
            localToneMapping = values[8],
            saturation = values[9],
            vibrance = values[10],
            temperature = values[11],
            tint = values[12],
            sharpness = values[13],
            microContrast = values[14],
            texture = values[15],
            noiseReduction = values[16],
            detailPreservation = values[17],
        ).normalized()
    }

    fun toPipelineJson(): String = JSONObject().apply {
        put("profile", pipelineProfile)
        put("customColorMatrix", JSONArray(customColorMatrix))
        put("exposure", exposure)
        put("contrast", contrast)
        put("highlights", highlights)
        put("shadows", shadows)
        put("whites", whites)
        put("blacks", blacks)
        put("highlightRollOff", highlightRollOff)
        put("shadowRecovery", shadowRecovery)
        put("localToneMapping", localToneMapping)
        put("saturation", saturation)
        put("vibrance", vibrance)
        put("temperature", temperature)
        put("tint", tint)
        put("sharpness", sharpness)
        put("texture", texture)
        put("microContrast", microContrast)
        put("noiseReduction", noiseReduction)
        put("detailPreservation", detailPreservation)
    }.toString()
}

class OppoPhotoSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "oppo_photo_features",
        Context.MODE_PRIVATE,
    )
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<OppoPhotoSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (OppoPhotoSettings) -> OppoPhotoSettings) {
        val updated = transform(_settings.value).normalized()
        _settings.value = updated
        preferences.edit()
            .putBoolean("auto_hdr", updated.autoHdrEnabled)
            .putBoolean("fast_shutter", updated.fastShutterEnabled)
            .putInt("fast_shutter_fps", updated.fastShutterFps)
            .putBoolean("refocus", updated.refocusEnabled)
            .putString("photo_megapixel_mode", updated.photoMegapixelMode.name)
            .putInt("refocus_frame_count", updated.refocusFrameCount)
            .putBoolean("custom_pipeline", updated.customPipelineEnabled)
            .putString("pipeline_profile", updated.pipelineProfile)
            .putString("pipeline_custom_color_matrix", JSONArray(updated.customColorMatrix).toString())
            .putFloat("pipeline_exposure", updated.exposure)
            .putFloat("pipeline_contrast", updated.contrast)
            .putFloat("pipeline_highlights", updated.highlights)
            .putFloat("pipeline_shadows", updated.shadows)
            .putFloat("pipeline_whites", updated.whites)
            .putFloat("pipeline_blacks", updated.blacks)
            .putFloat("pipeline_highlight_rolloff", updated.highlightRollOff)
            .putFloat("pipeline_shadow_recovery", updated.shadowRecovery)
            .putFloat("pipeline_local_tone_mapping", updated.localToneMapping)
            .putFloat("pipeline_saturation", updated.saturation)
            .putFloat("pipeline_vibrance", updated.vibrance)
            .putFloat("pipeline_temperature", updated.temperature)
            .putFloat("pipeline_tint", updated.tint)
            .putFloat("pipeline_sharpness", updated.sharpness)
            .putFloat("pipeline_texture", updated.texture)
            .putFloat("pipeline_micro_contrast", updated.microContrast)
            .putFloat("pipeline_noise_reduction", updated.noiseReduction)
            .putFloat("pipeline_detail_preservation", updated.detailPreservation)
            .apply()
    }

    private fun read(): OppoPhotoSettings {
        val profile = preferences.getString("pipeline_profile", "HASSELBLAD") ?: "HASSELBLAD"
        val preset = OppoPhotoSettings().withPhotoProfile(profile)
        val storedMatrix = runCatching {
            val json = preferences.getString("pipeline_custom_color_matrix", null)
            if (json == null) null else JSONArray(json).let { array ->
                if (array.length() != 9) null else List(9) { index -> array.getDouble(index).toFloat() }
            }
        }.getOrNull()
        return preset.copy(
            autoHdrEnabled = preferences.getBoolean("auto_hdr", true),
            fastShutterEnabled = preferences.getBoolean("fast_shutter", true),
            fastShutterFps = preferences.getInt("fast_shutter_fps", 20),
            refocusEnabled = preferences.getBoolean("refocus", false),
            photoMegapixelMode = runCatching {
                PhotoMegapixelMode.valueOf(preferences.getString("photo_megapixel_mode", PhotoMegapixelMode.M12.name) ?: PhotoMegapixelMode.M12.name)
            }.getOrDefault(PhotoMegapixelMode.M12),
            refocusFrameCount = preferences.getInt("refocus_frame_count", 3),
            customPipelineEnabled = preferences.getBoolean("custom_pipeline", false),
            pipelineProfile = profile,
            customColorMatrix = storedMatrix ?: preset.customColorMatrix,
            exposure = preferences.getFloat("pipeline_exposure", preset.exposure),
            contrast = preferences.getFloat("pipeline_contrast", preset.contrast),
            highlights = preferences.getFloat("pipeline_highlights", preset.highlights),
            shadows = preferences.getFloat("pipeline_shadows", preset.shadows),
            whites = preferences.getFloat("pipeline_whites", preset.whites),
            blacks = preferences.getFloat("pipeline_blacks", preset.blacks),
            highlightRollOff = preferences.getFloat("pipeline_highlight_rolloff", preset.highlightRollOff),
            shadowRecovery = preferences.getFloat("pipeline_shadow_recovery", preset.shadowRecovery),
            localToneMapping = preferences.getFloat("pipeline_local_tone_mapping", preset.localToneMapping),
            saturation = preferences.getFloat("pipeline_saturation", preset.saturation),
            vibrance = preferences.getFloat("pipeline_vibrance", preset.vibrance),
            temperature = preferences.getFloat("pipeline_temperature", preset.temperature),
            tint = preferences.getFloat("pipeline_tint", preset.tint),
            sharpness = preferences.getFloat("pipeline_sharpness", preset.sharpness),
            texture = preferences.getFloat("pipeline_texture", preset.texture),
            microContrast = preferences.getFloat("pipeline_micro_contrast", preset.microContrast),
            noiseReduction = preferences.getFloat("pipeline_noise_reduction", preset.noiseReduction),
            detailPreservation = preferences.getFloat("pipeline_detail_preservation", preset.detailPreservation),
        ).normalized()
    }
}
