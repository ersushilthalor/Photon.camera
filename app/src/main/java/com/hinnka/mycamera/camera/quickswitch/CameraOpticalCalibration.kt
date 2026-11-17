package com.hinnka.mycamera.camera.quickswitch

import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Logarithmic zoom mapping helpers ported from VCamera HorizontalRulerZoomSlider so
 * transition trajectories match logarithmic zoom perception cleanly.
 */
fun zoomToNormalizedLog(zoom: Float, minZ: Float, maxZ: Float): Float {
    val safeMin = minZ.coerceAtLeast(0.3f)
    val safeMax = maxZ.coerceAtLeast(safeMin + 0.05f)
    val logMin = ln(safeMin.toDouble())
    val logMax = ln(safeMax.toDouble())
    if (logMax <= logMin) return 0f
    val logZ = ln(zoom.coerceIn(safeMin, safeMax).toDouble())
    return ((logZ - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)
}

fun normalizedToZoomLog(t: Float, minZ: Float, maxZ: Float): Float {
    val safeMin = minZ.coerceAtLeast(0.3f)
    val safeMax = maxZ.coerceAtLeast(safeMin + 0.05f)
    if (safeMax <= safeMin) return safeMin
    val logMin = ln(safeMin.toDouble())
    val logMax = ln(safeMax.toDouble())
    val logZ = logMin + t.coerceIn(0f, 1f) * (logMax - logMin)
    val rawZoom = exp(logZ).toFloat()
    return rawZoom.coerceIn(safeMin, safeMax)
}

/**
 * Optical and FOV-calibrated camera lens calculations ported from VCamera.
 *
 * Replaces naive focal length ratios with sensor-aware Field Of View (FOV) calibration:
 * - 1.0x is always the calibrated FOV of the primary Main Wide camera.
 * - Ultra-wide optical ratio is derived from actual horizontal FOV.
 * - Minimum Ultra-wide zoom is strictly wider than 1.0x Main.
 * - At 0.9x, Ultra-wide is never more zoomed in than 1.0x Main.
 * - Dynamic crop mapping converts UI zoom to exact physical sensor crop.
 * - Hysteresis thresholds prevent jittery lens switching during continuous user gestures.
 */
object CameraOpticalCalibration {

    const val DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM = 16.0f
    const val DEFAULT_MAIN_EQUIVALENT_FOCAL_MM = 23.0f
    const val MIN_SWITCH_POINT_MM = 23.0f
    const val MAX_SWITCH_POINT_MM = 85.0f
    const val DEFAULT_SWITCH_POINT_MM = 23.0f

    /**
     * Calculates horizontal Field of View in degrees from physical sensor width and focal length.
     * hFOV = 2 * atan(sensorWidth / (2 * focalLength)) * (180 / PI)
     */
    fun calculateHorizontalFovDegrees(sensorWidthMm: Float, focalLengthMm: Float): Float {
        if (sensorWidthMm <= 0f || focalLengthMm <= 0f) return 0f
        return (2.0 * atan(sensorWidthMm.toDouble() / (2.0 * focalLengthMm.toDouble())) * (180.0 / Math.PI)).toFloat()
    }

    /**
     * Calculates vertical Field of View in degrees from physical sensor height and focal length.
     */
    fun calculateVerticalFovDegrees(sensorHeightMm: Float, focalLengthMm: Float): Float {
        if (sensorHeightMm <= 0f || focalLengthMm <= 0f) return 0f
        return (2.0 * atan(sensorHeightMm.toDouble() / (2.0 * focalLengthMm.toDouble())) * (180.0 / Math.PI)).toFloat()
    }

    fun zoomToFocalLengthMm(
        zoom: Float,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return if (zoom <= 1.0f) {
            val t = ((zoom - 0.5f) / 0.5f).coerceIn(0f, 1f)
            uwFocalMm + t * (mainFocalMm - uwFocalMm)
        } else {
            zoom * mainFocalMm
        }
    }

    fun focalLengthMmToZoom(
        focalMm: Float,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return if (focalMm <= mainFocalMm) {
            val t = ((focalMm - uwFocalMm) / (mainFocalMm - uwFocalMm)).coerceIn(0f, 1f)
            0.5f + t * 0.5f
        } else {
            focalMm / mainFocalMm
        }
    }

    fun switchPointToZoom(
        switchPointMm: Float,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val clamped = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        return clamped / mainFocalMm
    }

    fun calculateUltraWideCropForSwitchPoint(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        uwFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): Float {
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val baseUw = if (uwFocalMm > 0f) uwFocalMm else DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
        return ((clampedSwitch / baseUw) * 100f).roundToInt() / 100f
    }

    fun calculateFovMatchedUltraWideCrop(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): Float {
        val safeMainFocalMm = mainEquivalentFocalMm
            .takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
        val safeUwFocalMm = uwEquivalentFocalMm
            .takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
        val safeSwitchPointMm = switchPointMm
            .takeIf { it.isFinite() }
            ?.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
            ?: DEFAULT_SWITCH_POINT_MM

        val targetEquivalentFocalMm = maxOf(safeSwitchPointMm, safeMainFocalMm)
        return (targetEquivalentFocalMm / safeUwFocalMm).coerceAtLeast(1f)
    }

    fun calculateMainCropForSwitchPoint(
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        mainFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val baseMain = if (mainFocalMm > 0f) mainFocalMm else DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
        return ((clampedSwitch / baseMain) * 100f).roundToInt() / 100f
    }

    fun calculateUltraWideCropLimit(
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
    ): Float {
        return calculateUltraWideCropForSwitchPoint(mainEquivalentFocalMm, uwEquivalentFocalMm)
    }

    fun calculateCalibratedOpticalRatio(
        lensFocalLengthMm: Float,
        lensSensorWidthMm: Float,
        mainFocalLengthMm: Float,
        mainSensorWidthMm: Float,
        lensType: QuickLensRole,
        @Suppress("UNUSED_PARAMETER") logicalMinZoomRatio: Float? = null
    ): Float {
        if (lensType == QuickLensRole.WIDE) return 1.0f

        val hasValidMain = mainSensorWidthMm > 0f && mainFocalLengthMm > 0f
        val hasValidLens = lensSensorWidthMm > 0f && lensFocalLengthMm > 0f

        val fovBasedRatio = if (hasValidMain && hasValidLens) {
            val tanHalfMain = mainSensorWidthMm.toDouble() / (2.0 * mainFocalLengthMm.toDouble())
            val tanHalfLens = lensSensorWidthMm.toDouble() / (2.0 * lensFocalLengthMm.toDouble())
            if (tanHalfLens > 0.0) {
                (tanHalfMain / tanHalfLens).toFloat()
            } else 1.0f
        } else if (mainFocalLengthMm > 0f && lensFocalLengthMm > 0f) {
            lensFocalLengthMm / mainFocalLengthMm
        } else {
            when (lensType) {
                QuickLensRole.ULTRAWIDE -> 0.5f
                QuickLensRole.TELEPHOTO -> 2.0f
                QuickLensRole.TELEPHOTO_3X -> 3.0f
                else -> 1.0f
            }
        }

        return when (lensType) {
            QuickLensRole.ULTRAWIDE -> 0.5f
            QuickLensRole.TELEPHOTO -> {
                val rounded = (fovBasedRatio * 10f).roundToInt() / 10f
                rounded.coerceAtLeast(1.8f)
            }
            QuickLensRole.TELEPHOTO_3X -> {
                val rounded = (fovBasedRatio * 10f).roundToInt() / 10f
                rounded.coerceAtLeast(2.8f)
            }
            else -> 1.0f
        }
    }

    fun calculateRequiredDigitalCrop(
        uiZoom: Float,
        lensBaseRatio: Float,
        lensType: QuickLensRole,
        uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
        mainEquivalentFocalMm: Float = DEFAULT_MAIN_EQUIVALENT_FOCAL_MM,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM
    ): Float {
        val base = if (lensType == QuickLensRole.ULTRAWIDE) 0.5f else (if (lensBaseRatio > 0.1f) lensBaseRatio else 1.0f)
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val maxUwCrop = calculateUltraWideCropForSwitchPoint(clampedSwitch, uwEquivalentFocalMm)
        val switchZoom = switchPointToZoom(clampedSwitch, mainEquivalentFocalMm)

        return when (lensType) {
            QuickLensRole.ULTRAWIDE -> {
                if (uiZoom <= 0.5f) {
                    1.0f
                } else if (uiZoom <= switchZoom) {
                    if (uiZoom >= switchZoom - 0.001f) {
                        maxUwCrop
                    } else {
                        val t = ((uiZoom - 0.5f) / (switchZoom - 0.5f).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                        val crop = 1.0f + t * (maxUwCrop - 1.0f)
                        crop.coerceIn(1.0f, maxUwCrop)
                    }
                } else {
                    maxUwCrop * (uiZoom / switchZoom)
                }
            }
            QuickLensRole.WIDE -> uiZoom.coerceAtLeast(1.0f)
            QuickLensRole.TELEPHOTO, QuickLensRole.TELEPHOTO_3X -> (uiZoom / base).coerceAtLeast(1.0f)
            QuickLensRole.FRONT -> uiZoom.coerceAtLeast(1.0f)
        }
    }

    fun shouldKeepUltraWideDuringContinuousZoom(
        currentLensType: QuickLensRole,
        targetZoom: Float,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        isContinuousTransition: Boolean
    ): Boolean {
        if (!isContinuousTransition || currentLensType != QuickLensRole.ULTRAWIDE) return false
        val switchZoom = switchPointToZoom(switchPointMm)
        return targetZoom < switchZoom
    }

    fun resolveTargetLensType(
        currentLensType: QuickLensRole,
        targetZoom: Float,
        hasUltraWide: Boolean,
        hasTelephoto2x: Boolean,
        hasTelephoto3x: Boolean,
        isPresetTap: Boolean,
        switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        isContinuousTransition: Boolean = false
    ): QuickLensRole {
        if (currentLensType == QuickLensRole.FRONT) {
            return QuickLensRole.FRONT
        }
        val clampedSwitch = switchPointMm.coerceIn(MIN_SWITCH_POINT_MM, MAX_SWITCH_POINT_MM)
        val switchZoom = switchPointToZoom(clampedSwitch, DEFAULT_MAIN_EQUIVALENT_FOCAL_MM)

        if (targetZoom >= switchZoom) {
            val isDirectOrTransition = isPresetTap || isContinuousTransition
            return when {
                targetZoom >= 2.8f && hasTelephoto3x && (isDirectOrTransition || currentLensType == QuickLensRole.TELEPHOTO_3X || targetZoom >= 3.15f) && switchZoom <= 2.8f ->
                    QuickLensRole.TELEPHOTO_3X
                targetZoom >= 1.8f && hasTelephoto2x && (isDirectOrTransition || currentLensType == QuickLensRole.TELEPHOTO || targetZoom >= 2.15f) && switchZoom <= 1.8f ->
                    QuickLensRole.TELEPHOTO
                currentLensType == QuickLensRole.TELEPHOTO && targetZoom >= 1.85f && switchZoom <= 1.85f ->
                    QuickLensRole.TELEPHOTO
                currentLensType == QuickLensRole.TELEPHOTO_3X && targetZoom >= 2.85f && switchZoom <= 2.85f ->
                    QuickLensRole.TELEPHOTO_3X
                else -> QuickLensRole.WIDE
            }
        }

        return if (hasUltraWide) QuickLensRole.ULTRAWIDE else QuickLensRole.WIDE
    }

    data class OpticalFramingCalibration(
        val scaleCorrection: Float = 1.0f,
        val offsetXNorm: Float = 0.0f,
        val offsetYNorm: Float = 0.0f
    )

    fun getUltraWideFramingCalibration(
        @Suppress("UNUSED_PARAMETER") switchPointMm: Float = DEFAULT_SWITCH_POINT_MM,
        @Suppress("UNUSED_PARAMETER") uwEquivalentFocalMm: Float = DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
    ): OpticalFramingCalibration = OpticalFramingCalibration()

    fun interpolateUltraWideFraming(
        uiZoom: Float,
        calib: OpticalFramingCalibration,
        switchZoom: Float = 1.0f
    ): OpticalFramingCalibration {
        if (uiZoom <= 0.5f) {
            return OpticalFramingCalibration()
        }
        val t = ((uiZoom - 0.5f) / (switchZoom - 0.5f).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        return OpticalFramingCalibration(
            scaleCorrection = 1.0f,
            offsetXNorm = calib.offsetXNorm * t,
            offsetYNorm = calib.offsetYNorm * t
        )
    }
}
