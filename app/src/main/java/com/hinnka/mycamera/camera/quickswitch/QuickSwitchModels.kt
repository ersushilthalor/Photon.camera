package com.hinnka.mycamera.camera.quickswitch

import android.hardware.camera2.CameraCharacteristics
import com.hinnka.mycamera.camera.CameraInfo
import com.hinnka.mycamera.camera.LensType

/**
 * Dual streaming strategy ported from VCamera for simultaneous preview / instant lens switching.
 */
enum class DualStreamingMode {
    NONE,
    CONCURRENT_DEVICES,
    LOGICAL_MULTI_CAMERA_PHYSICAL,
    PERSISTENT_DUAL_SESSION
}

/**
 * Preview surface stream source identifier.
 */
enum class PreviewStreamSource {
    MAIN,
    ULTRAWIDE
}

/**
 * Alpha and layer ordering state during cross-lens frame-based or hold-time handoff.
 */
data class PreviewOverlapState(
    val isOverlapping: Boolean = false,
    val mainAlpha: Float = 1.0f,
    val ultraWideAlpha: Float = 0.0f,
    val activeSource: PreviewStreamSource = PreviewStreamSource.MAIN,
    val overlapProgress: Float = 0.0f,
    val topSource: PreviewStreamSource = PreviewStreamSource.MAIN
)

/**
 * Standby background camera status reported by Quick Lens Switch.
 */
enum class BackgroundCameraStatus {
    OFF,
    PREPARING,
    READY_QUIET
}

/**
 * Optical lens role mapped from PhotonCamera's CameraInfo / LensType for VCamera calibration and transitions.
 */
enum class QuickLensRole {
    ULTRAWIDE,
    WIDE,
    TELEPHOTO,
    TELEPHOTO_3X,
    FRONT
}

/**
 * Descriptor decoupling VCamera's lens switching logic from UI/discovery models while preserving
 * exact mapping back to PhotonCamera's CameraInfo.
 */
data class QuickLensDescriptor(
    val id: String,
    val cameraId: String,
    val sourceCameraId: String,
    val physicalCameraId: String? = null,
    val role: QuickLensRole,
    val baseZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 10.0f,
    val minZoomRatio: Float = 1.0f,
    val facing: Int = CameraCharacteristics.LENS_FACING_BACK,
    val isPrimaryMain: Boolean = false,
    val supportsPhysicalStream: Boolean = false,
    val equivalent35mmFocalMm: Float = 0f,
    val sensorWidthMm: Float = 0f,
    val focalLengthMm: Float = 0f,
    val sensorOrientation: Int = 0,
    val isZoomPreset: Boolean = false
) {
    /** Maps the quick-switch role back to PhotonCamera's camera-state lens type. */
    val lensType: LensType
        get() = when (role) {
            QuickLensRole.FRONT -> LensType.FRONT
            QuickLensRole.ULTRAWIDE -> LensType.BACK_ULTRA_WIDE
            QuickLensRole.TELEPHOTO, QuickLensRole.TELEPHOTO_3X -> LensType.BACK_TELEPHOTO
            QuickLensRole.WIDE -> LensType.BACK_MAIN
        }
}

object QuickLensResolver {

    fun mapRole(info: CameraInfo): QuickLensRole {
        return when (info.lensType) {
            LensType.FRONT -> QuickLensRole.FRONT
            LensType.BACK_ULTRA_WIDE -> QuickLensRole.ULTRAWIDE
            LensType.BACK_TELEPHOTO -> {
                val ratio = info.displayIntrinsicZoomRatio
                if (ratio >= 2.7f) QuickLensRole.TELEPHOTO_3X else QuickLensRole.TELEPHOTO
            }
            LensType.BACK_MAIN, LensType.BACK_MACRO -> QuickLensRole.WIDE
        }
    }

    fun fromCameraInfo(
        info: CameraInfo,
        sensorWidthMm: Float = 0f
    ): QuickLensDescriptor {
        val openCameraId = runCatching { info.getOpenCameraId() }.getOrDefault(info.cameraId)
        val role = mapRole(info)
        val eqFocal = when {
            info.focalLength35mmEquivalent > 0f -> info.focalLength35mmEquivalent
            role == QuickLensRole.ULTRAWIDE -> CameraOpticalCalibration.DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
            else -> CameraOpticalCalibration.DEFAULT_MAIN_EQUIVALENT_FOCAL_MM *
                info.displayIntrinsicZoomRatio.takeIf { it > 0f }.let { it ?: 1.0f }
        }
        val baseRatio = when (role) {
            QuickLensRole.ULTRAWIDE -> 0.5f
            QuickLensRole.WIDE, QuickLensRole.FRONT -> 1.0f
            QuickLensRole.TELEPHOTO, QuickLensRole.TELEPHOTO_3X ->
                info.displayIntrinsicZoomRatio.takeIf { it > 0.1f } ?: 2.0f
        }
        val maxRatio = maxOf(
            info.maxZoom * info.displayIntrinsicZoomRatio.takeIf { it > 0f }.let { it ?: 1f },
            if (role == QuickLensRole.FRONT) 1.0f else 20.0f
        )
        return QuickLensDescriptor(
            id = info.cameraId,
            cameraId = openCameraId,
            sourceCameraId = info.cameraId,
            physicalCameraId = info.outputPhysicalCameraId,
            role = role,
            baseZoomRatio = baseRatio,
            maxZoomRatio = maxRatio,
            minZoomRatio = if (role == QuickLensRole.ULTRAWIDE) 0.5f else info.minZoom.coerceAtLeast(0.5f),
            facing = info.lensFacing,
            isPrimaryMain = info.lensType == LensType.BACK_MAIN && !info.isVirtualIszLens,
            supportsPhysicalStream = info.outputPhysicalCameraId != null && info.outputPhysicalCameraId != openCameraId,
            equivalent35mmFocalMm = eqFocal,
            sensorWidthMm = sensorWidthMm,
            focalLengthMm = info.focalLength,
            sensorOrientation = info.sensorOrientation,
            isZoomPreset = info.isVirtualIszLens
        )
    }

    fun buildDescriptors(
        cameras: List<CameraInfo>,
        sensorWidthLookup: (CameraInfo) -> Float = { 0f }
    ): List<QuickLensDescriptor> {
        val result = mutableListOf<QuickLensDescriptor>()
        val candidates = cameras.filter {
            !it.isVirtualIszLens && it.lensType != LensType.BACK_MACRO
        }
        candidates.forEach { cam ->
            result.add(fromCameraInfo(cam, sensorWidthLookup(cam)))
        }
        // Also expose physical ultra-wide/tele sub-sensors inside a logical multi-camera if no
        // standalone entry already represents that role for logical+physical simultaneous mode.
        val backMain = candidates.firstOrNull { it.lensType == LensType.BACK_MAIN }
        if (backMain != null && backMain.physicalCameras.isNotEmpty()) {
            val hasUw = result.any { it.facing == backMain.lensFacing && it.role == QuickLensRole.ULTRAWIDE }
            if (!hasUw) {
                val uwPhysical = backMain.physicalCameras
                    .filter { it.intrinsicZoomRatio in 0.25f..0.85f }
                    .minByOrNull { it.intrinsicZoomRatio }
                if (uwPhysical != null) {
                    result.add(
                        QuickLensDescriptor(
                            id = "${backMain.cameraId}#phys_${uwPhysical.cameraId}",
                            cameraId = backMain.getOpenCameraId(),
                            sourceCameraId = backMain.cameraId,
                            physicalCameraId = uwPhysical.cameraId,
                            role = QuickLensRole.ULTRAWIDE,
                            baseZoomRatio = 0.5f,
                            maxZoomRatio = backMain.maxZoom,
                            minZoomRatio = uwPhysical.intrinsicZoomRatio.coerceAtLeast(0.5f),
                            facing = backMain.lensFacing,
                            isPrimaryMain = false,
                            supportsPhysicalStream = true,
                            equivalent35mmFocalMm = uwPhysical.focalLength35mmEquivalent
                                .takeIf { it > 0f }
                                ?: CameraOpticalCalibration.DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM,
                            sensorWidthMm = 0f,
                            focalLengthMm = uwPhysical.focalLength,
                            sensorOrientation = backMain.sensorOrientation,
                            isZoomPreset = false
                        )
                    )
                }
            }
        }
        return result
    }
}
