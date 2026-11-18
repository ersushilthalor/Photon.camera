package com.hinnka.mycamera.video

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import android.util.Size
import com.hinnka.mycamera.camera.readMetadataOrNull
import com.hinnka.mycamera.utils.PLog
import kotlin.math.abs
import kotlin.math.ceil

object VideoCapabilitiesResolver {

    private const val TAG = "VideoCapabilitiesResolver"
    private const val VIDEO_PREVIEW_SHORT_EDGE = 1080
    private const val VIDEO_PREVIEW_ASPECT_TOLERANCE = 0.01f

    fun resolve(
        characteristics: CameraCharacteristics,
        requestedConfig: VideoConfig,
        algorithmicStabilizationSupported: Boolean = false,
    ): VideoCapabilitySnapshot {
        // Resolve from one metadata snapshot. Session caches are cleared while reopening,
        // which must not turn a supported user selection into an unsupported capability.
        val availableTonemapModes = characteristics.get(
            CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES
        ) ?: intArrayOf()
        val availableVideoStabilizationModes = characteristics.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        ) ?: intArrayOf()
        val availableOpticalStabilizationModes = characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        ) ?: intArrayOf()
        val isFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewOutputSizes = streamConfigMap?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val yuvOutputSizes = streamConfigMap?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        // VideoRecorder receives camera frames through a SurfaceTexture, not a
        // MediaRecorder surface. Therefore the encoder output preset must be backed
        // by a matching PRIVATE/SurfaceTexture camera stream. Do not advertise an
        // encoded 4K size when the camera source would have to be upscaled.
        val recordingOutputSizes = previewOutputSizes
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val openGateAspect = resolveOpenGatePortraitAspectRatio(activeArray, sensorOrientation)

        val availableResolutions = VideoResolutionPreset.entries.filter { preset ->
            findBestOutputSize(recordingOutputSizes, preset, requestedConfig.aspectRatio, openGateAspect) != null &&
                findBestOutputSize(previewOutputSizes, preset, requestedConfig.aspectRatio, openGateAspect) != null
        }

        val recordingSizesByResolution = availableResolutions.mapNotNull { preset ->
            findBestOutputSize(recordingOutputSizes, preset, requestedConfig.aspectRatio, openGateAspect)
                ?.let { preset to it }
        }.toMap()
        val cameraInputSizesByResolution = availableResolutions.mapNotNull { preset ->
            findBestOutputSize(previewOutputSizes, preset, requestedConfig.aspectRatio, openGateAspect)
                ?.let { preset to it }
        }.toMap()
        val enhancedStabilizationInputSizesByResolution = availableResolutions.mapNotNull { preset ->
            findBestOutputSize(yuvOutputSizes, preset, requestedConfig.aspectRatio, openGateAspect)
                ?.let { preset to it }
        }.toMap()

        val resolvedResolution = requestedConfig.resolution.takeIf { availableResolutions.contains(it) }
            ?: availableResolutions.firstOrNull()
            ?: VideoResolutionPreset.FHD_1080P

        val recordingSize = recordingSizesByResolution[resolvedResolution]
            ?: resolvedResolution.resolveOutputSize(
                requestedConfig.aspectRatio.getPortraitAspectRatio(openGateAspect)
            )

        val fallbackPreviewSize = findFixedPreviewOutputSize(
            outputSizes = previewOutputSizes,
            aspectRatio = requestedConfig.aspectRatio,
            openGatePortraitAspectRatio = openGateAspect
        ) ?: VideoResolutionPreset.FHD_1080P.resolveOutputSize(
            requestedConfig.aspectRatio.getPortraitAspectRatio(openGateAspect)
        )
        val cameraInputSize = cameraInputSizesByResolution[resolvedResolution] ?: fallbackPreviewSize
        // Keep the encoder's camera input at the selected capture size, but use a
        // separate 1080p-class stream for the on-screen viewfinder. The latter is
        // display-bound and does not need a second full-resolution pixel workload.
        val previewSize = fallbackPreviewSize

        val enhancedCameraInputSize =
            enhancedStabilizationInputSizesByResolution[resolvedResolution]
        val availableFps = if (cameraInputSizesByResolution.isEmpty()) {
            emptyList()
        } else {
            resolveAvailableFps(
                characteristics = characteristics,
                cameraInputSize = cameraInputSize,
                previewSize = previewSize,
                enhancedStabilizationInputSize = enhancedCameraInputSize.takeIf {
                    algorithmicStabilizationSupported
                }
            )
        }
        val resolvedFps = requestedConfig.fps.takeIf { availableFps.contains(it) }
            ?: availableFps.firstOrNull()
            ?: requestedConfig.fps

        val enhancedStabilizationAvailable = algorithmicStabilizationSupported &&
            enhancedCameraInputSize != null

        /*PLog.d(
            TAG,
            "Resolved video capabilities: resolution=${resolvedResolution.displayName}, " +
                "recording=${recordingSize.width}x${recordingSize.height}, " +
                "cameraInput=${cameraInputSize.width}x${cameraInputSize.height}, " +
                "preview=${previewSize.width}x${previewSize.height}, " +
                "fps=${availableFps.map { it.fps }}"
        )*/

        val linearTonemapSupported = availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) ||
            availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
        // HLG is generated by the recording renderer and does not require the
        // Camera2 custom tone-map modes used by the vendor Log curves.
        // OFF is the normal camera path; the other choices expose the supported profiles.
        // Natural and HLG10 use OppoCam's ISP curves; Apple Log keeps Photon's log renderer.
        val availableLogProfiles = VideoLogProfile.SELECTABLE_PROFILES
        val resolvedLogProfile = requestedConfig.logProfile.takeIf { availableLogProfiles.contains(it) }
            ?: VideoLogProfile.OFF

        val availableStabilizationModes = mutableListOf(VideoStabilizationMode.OFF)
        if (resolvedFps != VideoFpsPreset.FPS_120) {
            if (supportsElectronicStabilization(availableVideoStabilizationModes)) {
                availableStabilizationModes.add(VideoStabilizationMode.EIS)
            }
            if (availableOpticalStabilizationModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                availableStabilizationModes.add(VideoStabilizationMode.OIS)
            }
            if (enhancedStabilizationAvailable) {
                availableStabilizationModes.add(VideoStabilizationMode.ENHANCED)
            }
        }
        
        val resolvedStabilizationMode = if (availableStabilizationModes.contains(requestedConfig.stabilizationMode)) {
            requestedConfig.stabilizationMode
        } else {
            if (availableStabilizationModes.contains(VideoStabilizationMode.OIS)) VideoStabilizationMode.OIS
            else if (availableStabilizationModes.contains(VideoStabilizationMode.EIS)) VideoStabilizationMode.EIS
            else VideoStabilizationMode.OFF
        }

        return VideoCapabilitySnapshot(
            config = requestedConfig.copy(
                resolution = resolvedResolution,
                fps = resolvedFps,
                logProfile = resolvedLogProfile,
                bitrate = requestedConfig.bitrate,
                stabilizationMode = resolvedStabilizationMode,
                torchEnabled = requestedConfig.torchEnabled && isFlashSupported
            ),
            capabilities = VideoCapabilities(
                availableResolutions = if (requestedConfig.aspectRatio == VideoAspectRatio.OPEN_GATE) {
                    emptyList()
                } else {
                    availableResolutions
                },
                availableFps = availableFps,
                availableAspectRatios = VideoAspectRatio.entries.toList(),
                availableLogProfiles = availableLogProfiles,
                availableBitrates = VideoBitratePreset.entries.toList(),
                cameraInputSizesByResolution = cameraInputSizesByResolution,
                enhancedStabilizationInputSizesByResolution =
                    enhancedStabilizationInputSizesByResolution,
                recordingSizesByResolution = recordingSizesByResolution,
                openGatePortraitAspectRatio = openGateAspect,
                availableStabilizationModes = availableStabilizationModes,
                supportsTorch = isFlashSupported,
                linearTonemapSupported = linearTonemapSupported
            ),
            previewSize = previewSize,
            recordingSize = recordingSize
        )
    }

    private fun supportsElectronicStabilization(availableVideoStabilizationModes: IntArray): Boolean {
        return availableVideoStabilizationModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) ||
            supportsPreviewStabilization(availableVideoStabilizationModes)
    }

    private fun supportsPreviewStabilization(availableVideoStabilizationModes: IntArray): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            availableVideoStabilizationModes.contains(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            )
    }

    private fun findBestOutputSize(
        outputSizes: List<Size>,
        preset: VideoResolutionPreset,
        aspectRatio: VideoAspectRatio,
        openGatePortraitAspectRatio: Float
    ): Size? {
        if (outputSizes.isEmpty()) return null

        val targetAspect = aspectRatio.getPortraitAspectRatio(openGatePortraitAspectRatio)
        if (aspectRatio == VideoAspectRatio.OPEN_GATE) {
            // Ignore the saved resolution preset. Prefer the largest full-sensor
            // stream, allowing the same aspect tolerance as the preview selection.
            val aspectMatchedSizes = outputSizes.filter {
                abs(getPortraitAspectRatio(it) - targetAspect) <= VIDEO_PREVIEW_ASPECT_TOLERANCE
            }
            return if (aspectMatchedSizes.isNotEmpty()) {
                aspectMatchedSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            } else {
                outputSizes.minWithOrNull(
                    compareBy<Size> { abs(getPortraitAspectRatio(it) - targetAspect) }
                        .thenByDescending { it.width.toLong() * it.height.toLong() }
                )
            }
        }
        val nativeSize = outputSizes
            .filter { maxOf(it.width, it.height) >= preset.longEdge }
            .sortedWith(
                compareBy<Size> { abs(getPortraitAspectRatio(it) - targetAspect) }
                    .thenBy { abs(maxOf(it.width, it.height) - preset.longEdge) }
                    .thenByDescending { it.width.toLong() * it.height.toLong() }
            )
            .firstOrNull()
        if (nativeSize != null) return nativeSize

        return null
    }

    private fun findFixedPreviewOutputSize(
        outputSizes: List<Size>,
        aspectRatio: VideoAspectRatio,
        openGatePortraitAspectRatio: Float
    ): Size? {
        val validSizes = outputSizes
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }
        if (validSizes.isEmpty()) return null

        val targetAspect = aspectRatio.getPortraitAspectRatio(openGatePortraitAspectRatio)
        val aspectMatchedSizes = validSizes.filter {
            abs(getPortraitAspectRatio(it) - targetAspect) <= VIDEO_PREVIEW_ASPECT_TOLERANCE
        }
        val candidates = aspectMatchedSizes.ifEmpty { validSizes }
        val sizesWithinLimit = candidates.filter {
            minOf(it.width, it.height) <= VIDEO_PREVIEW_SHORT_EDGE
        }

        return if (sizesWithinLimit.isNotEmpty()) {
            sizesWithinLimit.sortedWith(
                compareByDescending<Size> { minOf(it.width, it.height) }
                    .thenBy { abs(getPortraitAspectRatio(it) - targetAspect) }
                    .thenByDescending { it.width.toLong() * it.height.toLong() }
            ).first()
        } else {
            candidates.sortedWith(
                compareBy<Size> { minOf(it.width, it.height) }
                    .thenBy { abs(getPortraitAspectRatio(it) - targetAspect) }
                    .thenBy { it.width.toLong() * it.height.toLong() }
            ).first()
        }
    }

    private fun resolveAvailableFps(
        characteristics: CameraCharacteristics,
        cameraInputSize: Size,
        previewSize: Size,
        enhancedStabilizationInputSize: Size?
    ): List<VideoFpsPreset> {
        val outputRanges = characteristics.readMetadataOrNull(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ).orEmpty()
        val minFrameDurationNs = resolveMinFrameDurationNs(
            characteristics = characteristics,
            cameraInputSize = cameraInputSize,
            previewSize = previewSize,
            enhancedStabilizationInputSize = enhancedStabilizationInputSize
        )

        // 120 fps is exposed only through Camera2 constrained-high-speed sessions.
        // A range that merely overlaps 120 is not proof of fixed 120 fps operation.
        val highSpeed120Supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            cameraInputSize == previewSize &&
            maxOf(cameraInputSize.width, cameraInputSize.height) == VideoResolutionPreset.FHD_1080P.longEdge &&
            minOf(cameraInputSize.width, cameraInputSize.height) == VideoResolutionPreset.FHD_1080P.shortEdge &&
            runCatching {
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@runCatching false
                val highSpeedSize = map.highSpeedVideoSizes?.any { it == cameraInputSize } == true
                highSpeedSize && map.getHighSpeedVideoFpsRangesFor(cameraInputSize).any { range ->
                    range.lower == 120 && range.upper == 120
                }
            }.getOrDefault(false)

        val standardPresets = VideoFpsPreset.entries.filter { it != VideoFpsPreset.FPS_120 }
        val standardAvailableFps = resolveStrictAvailableFpsPresets(
            presets = standardPresets,
            advertisedRanges = outputRanges.map { it.lower to it.upper },
            minFrameDurationNs = minFrameDurationNs
        )

        return if (highSpeed120Supported) {
            (standardAvailableFps + VideoFpsPreset.FPS_120).distinctBy { it.fps }.sortedBy { it.fps }
        } else {
            standardAvailableFps
        }
    }

    private fun resolveMinFrameDurationNs(
        characteristics: CameraCharacteristics,
        cameraInputSize: Size,
        previewSize: Size,
        enhancedStabilizationInputSize: Size?
    ): Long {
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return 0L

        val cameraInputDurationNs = queryMinFrameDurationNs(
            streamConfigMap = streamConfigMap,
            outputClass = SurfaceTexture::class.java,
            outputSize = cameraInputSize,
            label = "recording SurfaceTexture"
        )
        val previewDurationNs = queryMinFrameDurationNs(
            streamConfigMap = streamConfigMap,
            outputClass = SurfaceTexture::class.java,
            outputSize = previewSize,
            label = "preview SurfaceTexture"
        )
        if (cameraInputDurationNs <= 0L || previewDurationNs <= 0L) return 0L

        var requiredFrameDurationNs = maxOf(cameraInputDurationNs, previewDurationNs)
        enhancedStabilizationInputSize?.let { yuvSize ->
            val yuvMinFrameDurationNs = runCatching {
                streamConfigMap.getOutputMinFrameDuration(ImageFormat.YUV_420_888, yuvSize)
            }.getOrElse {
                PLog.w(TAG, "Failed to query EIS+ YUV min frame duration for $yuvSize: ${it.message}")
                0L
            }
            val yuvStallDurationNs = runCatching {
                streamConfigMap.getOutputStallDuration(ImageFormat.YUV_420_888, yuvSize)
            }.getOrElse {
                PLog.w(TAG, "Failed to query EIS+ YUV stall duration for $yuvSize: ${it.message}")
                0L
            }
            if (yuvMinFrameDurationNs <= 0L) return 0L
            requiredFrameDurationNs = maxOf(
                requiredFrameDurationNs,
                yuvMinFrameDurationNs,
                yuvStallDurationNs
            )
        }
        return requiredFrameDurationNs
    }

    private fun <T> queryMinFrameDurationNs(
        streamConfigMap: android.hardware.camera2.params.StreamConfigurationMap,
        outputClass: Class<T>,
        outputSize: Size,
        label: String
    ): Long {
        return try {
            streamConfigMap.getOutputMinFrameDuration(outputClass, outputSize)
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to query $label min frame duration for $outputSize: ${e.message}")
            0L
        }
    }

    private fun getPortraitAspectRatio(size: Size): Float {
        val width = minOf(size.width, size.height).coerceAtLeast(1)
        val height = maxOf(size.width, size.height).coerceAtLeast(1)
        return width.toFloat() / height.toFloat()
    }
}

/**
 * Returns only rates that the HAL explicitly advertises as fixed-rate ranges and
 * whose slowest configured output stream can sustain the requested frame period.
 *
 * A variable AE range such as 15..30 is deliberately not treated as fixed 30 fps.
 * An unknown (zero) minimum frame duration is also not treated as proof of support.
 */
internal fun resolveStrictAvailableFpsPresets(
    presets: List<VideoFpsPreset>,
    advertisedRanges: List<Pair<Int, Int>>,
    minFrameDurationNs: Long
): List<VideoFpsPreset> {
    if (minFrameDurationNs <= 0L) return emptyList()
    return presets.filter { preset ->
        val hasExactFixedRange = advertisedRanges.any { (lower, upper) ->
            lower == preset.fps && upper == preset.fps
        }
        val maximumFramePeriodNs = ceil(1_000_000_000.0 / preset.fps.toDouble()).toLong()
        hasExactFixedRange && minFrameDurationNs <= maximumFramePeriodNs
    }
}
