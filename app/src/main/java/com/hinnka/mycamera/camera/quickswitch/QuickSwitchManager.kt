package com.hinnka.mycamera.camera.quickswitch

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import com.hinnka.mycamera.utils.PLog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrates VCamera's instant lens switching system inside PhotonCamera's Camera2Controller:
 * - Dual streaming mode detection (Concurrent Devices / Logical Multi-Cam Physical / Persistent Dual Session)
 * - Standby background camera prewarming & 3-attempt reconnect
 * - True 0ms active <-> standby pointer & session swapping without stopping preview or recording
 * - Concurrent CameraDevice opening keeping old preview alive until replacement frames arrive, with
 *   sequential fallback on hardware conflict / configure failure
 * - 16ms zoom preview coalescing & synchronized standby 3A + FOV-matched crop updates
 */
class QuickSwitchManager(
    private val cameraManager: CameraManager,
    private val getCameraHandler: () -> Handler?,
    private val getCharacteristics: (String) -> CameraCharacteristics?,
    private val bridge: ControllerBridge,
    val zoomTransitionController: ZoomTransitionController
) {

    interface ControllerBridge {
        fun isQuickLensSwitchAllowedNow(): Boolean
        fun isCameraClosingOrUninitialized(): Boolean
        fun isVideoMode(): Boolean
        fun isRecordingVideo(): Boolean
        fun getActiveCameraDevice(): CameraDevice?
        fun setActiveCameraDevice(device: CameraDevice?)
        fun getActiveCaptureSession(): CameraCaptureSession?
        fun setActiveCaptureSession(session: CameraCaptureSession?)
        fun getActivePreviewRequestBuilder(): CaptureRequest.Builder?
        fun setActivePreviewRequestBuilder(builder: CaptureRequest.Builder?)
        fun getActiveImageReader(): ImageReader?
        fun setActiveImageReader(reader: ImageReader?)
        fun getActiveOpenCameraId(): String
        fun setActiveOpenCameraId(cameraId: String)
        fun getActiveOutputPhysicalCameraId(): String?
        fun setActiveOutputPhysicalCameraId(physicalId: String?)
        fun getAvailableDescriptors(): List<QuickLensDescriptor>
        fun getCurrentDescriptor(): QuickLensDescriptor?
        fun getCurrentUiZoom(): Float
        fun updateCurrentUiZoom(uiZoom: Float)
        fun updateSelectedLensDescriptor(descriptor: QuickLensDescriptor)
        fun onActiveStreamSwapped(
            descriptor: QuickLensDescriptor,
            useUltraWideSurface: Boolean,
            source: PreviewStreamSource
        )
        fun onBackgroundStatusChanged(status: BackgroundCameraStatus)
        fun getOrCreateSurfaceForSource(source: PreviewStreamSource, size: Size): Surface?
        fun getSurfaceTextureForSource(source: PreviewStreamSource): SurfaceTexture?
        fun getOptimalPreviewSizeForDescriptor(descriptor: QuickLensDescriptor): Size
        fun applyBaseSettingsToBuilder(builder: CaptureRequest.Builder, descriptor: QuickLensDescriptor)
        fun getTargetFpsRangeForDescriptor(descriptor: QuickLensDescriptor, isVideo: Boolean): Range<Int>?
        fun getActiveCaptureCallback(): CameraCaptureSession.CaptureCallback?
        fun setupPhotoImageReaderForActiveLens(descriptor: QuickLensDescriptor, onReady: () -> Unit)
        fun getSecondaryRecorderSurface(descriptor: QuickLensDescriptor, previewSize: Size): Surface?
        fun notifyRecorderActiveSource(
            source: PreviewStreamSource,
            descriptor: QuickLensDescriptor,
            inputSize: Size
        )
        fun isDualRecorderSurfaceSupported(): Boolean
        fun recreateActiveSessionOnSameDevice()
        fun fallbackToPhotonCameraOpen(descriptor: QuickLensDescriptor, targetZoom: Float)
        fun onActiveLensCapabilitiesChanged(descriptor: QuickLensDescriptor, targetZoom: Float)
    }

    companion object {
        private const val TAG = "QuickSwitchManager"
        private const val MAX_STANDBY_RECONNECT = 3
        private const val ZOOM_UPDATE_INTERVAL_MS = 16L
    }

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var isContinuousZoomTransitionActive: Boolean = false

    @Volatile
    var activeLogicalMultiCamUltraWideConfigured: Boolean = false

    @Volatile
    var activeSessionDescriptor: QuickLensDescriptor? = null

    @Volatile
    private var activeSwitchingTargetDescriptor: QuickLensDescriptor? = null

    @Volatile
    private var standbyCameraDevice: CameraDevice? = null

    @Volatile
    private var standbyCaptureSession: CameraCaptureSession? = null

    @Volatile
    private var standbyRequestBuilder: CaptureRequest.Builder? = null

    @Volatile
    private var standbyImageReader: ImageReader? = null

    @Volatile
    private var standbyLensDescriptor: QuickLensDescriptor? = null

    @Volatile
    private var standbyConfiguredPreviewSize: Size? = null

    @Volatile
    private var standbyConfiguredIsVideoMode: Boolean = false

    @Volatile
    private var standbyConfiguredForRecording: Boolean = false

    @Volatile
    private var standbyRecordingSessionFailed: Boolean = false

    private val isPreparingStandby = AtomicBoolean(false)
    private val isStandbyStreaming = AtomicBoolean(false)
    private val standbyCameraDeviceGeneration = AtomicInteger(0)
    private val standbySessionGeneration = AtomicInteger(0)
    private val lensSwitchGeneration = AtomicInteger(0)
    private val isSwitchingLens = AtomicBoolean(false)
    private val lifecycleLock = Any()

    @Volatile
    private var pendingLensWhileSwitching: QuickLensDescriptor? = null

    @Volatile
    private var pendingZoomWhileSwitching: Float? = null

    @Volatile
    private var pendingZoomPresetTapWhileSwitching: Boolean = false

    @Volatile
    private var pendingContinuousTransitionWhileSwitching: Boolean = false

    private var standbyReconnectCount = 0
    private var standbyReconnectRunnable: Runnable? = null

    private val standbyCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (standbyCaptureSession === session && !isStandbyStreaming.getAndSet(true)) {
                bridge.onBackgroundStatusChanged(BackgroundCameraStatus.READY_QUIET)
            }
        }
    }

    private var lastZoomPreviewUpdateTimeMs = 0L
    private var pendingZoomRunnable: Runnable? = null

    @Volatile
    private var pendingZoomUpdateTarget: Float? = null
    private val isZoomUpdateScheduled = AtomicBoolean(false)

    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        standbyReconnectCount = 0
        if (!enabled) {
            zoomTransitionController.cancelTransition()
            isContinuousZoomTransitionActive = false
            activeLogicalMultiCamUltraWideConfigured = false
            pendingZoomRunnable?.let { getCameraHandler()?.removeCallbacks(it) }
            pendingZoomRunnable = null
            pendingZoomUpdateTarget = null
            isZoomUpdateScheduled.set(false)
            isSwitchingLens.set(false)
            activeSwitchingTargetDescriptor = null
            pendingLensWhileSwitching = null
            pendingZoomWhileSwitching = null
            bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
            postOrRun { releaseStandby() }
        } else {
            bridge.onBackgroundStatusChanged(
                if (bridge.isQuickLensSwitchAllowedNow()) BackgroundCameraStatus.PREPARING
                else BackgroundCameraStatus.OFF
            )
            postOrRun { ensureStandbyReady() }
        }
    }

    fun detectSimultaneousStreamingMode(
        mainLens: QuickLensDescriptor?,
        ultraWideLens: QuickLensDescriptor?
    ): DualStreamingMode {
        if (mainLens == null || ultraWideLens == null) return DualStreamingMode.NONE
        if (mainLens.cameraId == ultraWideLens.cameraId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val chars = getCharacteristics(mainLens.cameraId)
                val physIds = chars?.physicalCameraIds ?: emptySet()
                val targetPhysId = ultraWideLens.physicalCameraId
                if (targetPhysId != null && physIds.contains(targetPhysId) && ultraWideLens.supportsPhysicalStream) {
                    return DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
                }
            }
            return DualStreamingMode.PERSISTENT_DUAL_SESSION
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val concurrentSets = cameraManager.concurrentCameraIds
                    if (concurrentSets.isNotEmpty()) {
                        val matches = concurrentSets.any { combo ->
                            combo.contains(mainLens.cameraId) && combo.contains(ultraWideLens.cameraId)
                        }
                        if (matches) return DualStreamingMode.CONCURRENT_DEVICES
                    }
                } catch (_: Throwable) {
                }
            }
            return DualStreamingMode.PERSISTENT_DUAL_SESSION
        }
    }

    fun getPrimaryAndUltraWideDescriptors(): Pair<QuickLensDescriptor?, QuickLensDescriptor?> {
        val backLenses = bridge.getAvailableDescriptors().filter {
            it.facing == CameraCharacteristics.LENS_FACING_BACK
        }
        val mainLens = backLenses.firstOrNull { it.isPrimaryMain }
            ?: backLenses.firstOrNull { it.role == QuickLensRole.WIDE && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.role == QuickLensRole.WIDE }
        val uwLens = backLenses.firstOrNull { it.role == QuickLensRole.ULTRAWIDE && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.role == QuickLensRole.ULTRAWIDE }
        return mainLens to uwLens
    }

    fun isStandbySessionReadyFor(cameraId: String): Boolean {
        val cam = standbyCameraDevice ?: return false
        val sess = standbyCaptureSession ?: return false
        return cam.id == cameraId && sess != null
    }

    fun getStandbyCameraDevice(): CameraDevice? = standbyCameraDevice

    fun getStandbyCaptureSession(): CameraCaptureSession? = standbyCaptureSession

    @SuppressLint("MissingPermission")
    fun ensureStandbyReady() {
        if (!isEnabled && !isContinuousZoomTransitionActive) {
            releaseStandby()
            return
        }
        if (!bridge.isQuickLensSwitchAllowedNow() || bridge.isCameraClosingOrUninitialized()) {
            releaseStandby()
            return
        }
        val activeCam = bridge.getActiveCameraDevice() ?: return
        val currentDesc = bridge.getCurrentDescriptor() ?: return
        if (currentDesc.facing != CameraCharacteristics.LENS_FACING_BACK) {
            releaseStandby()
            return
        }
        val (mainLens, uwLens) = getPrimaryAndUltraWideDescriptors()
        if (mainLens == null || uwLens == null) {
            bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
            return
        }

        val standbyLens = if (currentDesc.role == QuickLensRole.ULTRAWIDE) mainLens else uwLens
        val dualMode = detectSimultaneousStreamingMode(mainLens, uwLens)

        // 1. Same logical multi-camera physical stream
        if (dualMode == DualStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL && mainLens.cameraId == uwLens.cameraId) {
            val uwSize = bridge.getOptimalPreviewSizeForDescriptor(uwLens)
            val mainSize = bridge.getOptimalPreviewSizeForDescriptor(mainLens)
            bridge.getOrCreateSurfaceForSource(PreviewStreamSource.ULTRAWIDE, uwSize)
            bridge.getOrCreateSurfaceForSource(PreviewStreamSource.MAIN, mainSize)
            if (!activeLogicalMultiCamUltraWideConfigured &&
                bridge.getActiveCaptureSession() != null &&
                !bridge.isRecordingVideo() &&
                !isContinuousZoomTransitionActive
            ) {
                bridge.recreateActiveSessionOnSameDevice()
                return
            }
            if (bridge.getActiveCaptureSession() != null && activeLogicalMultiCamUltraWideConfigured) {
                isStandbyStreaming.set(true)
                bridge.onBackgroundStatusChanged(BackgroundCameraStatus.READY_QUIET)
            }
            return
        }

        // 2. Separate CameraDevices (Concurrent or Persistent Dual-Session)
        val isRecordingNow = bridge.isRecordingVideo()
        val existingStandbyCam = standbyCameraDevice
        if (existingStandbyCam != null) {
            if (existingStandbyCam.id == standbyLens.cameraId) {
                val optimalSize = bridge.getOptimalPreviewSizeForDescriptor(standbyLens)
                val sizeMatches = standbyConfiguredPreviewSize?.let {
                    max(it.width, it.height) == max(optimalSize.width, optimalSize.height) &&
                        min(it.width, it.height) == min(optimalSize.width, optimalSize.height)
                } == true
                if (standbyCaptureSession != null &&
                    sizeMatches &&
                    standbyConfiguredForRecording == isRecordingNow
                ) {
                    isStandbyStreaming.set(true)
                    bridge.onBackgroundStatusChanged(BackgroundCameraStatus.READY_QUIET)
                    syncStandbyStreamSettings()
                    return
                }
                configureStandbySessionOnDevice(existingStandbyCam, standbyLens)
                return
            } else {
                releaseStandbyCameraOnly()
            }
        }

        if (!isPreparingStandby.compareAndSet(false, true)) {
            return
        }
        bridge.onBackgroundStatusChanged(BackgroundCameraStatus.PREPARING)
        try {
            val standbySize = bridge.getOptimalPreviewSizeForDescriptor(standbyLens)
            val standbySource = if (standbyLens.role == QuickLensRole.ULTRAWIDE) {
                PreviewStreamSource.ULTRAWIDE
            } else {
                PreviewStreamSource.MAIN
            }
            bridge.getOrCreateSurfaceForSource(standbySource, standbySize)

            val openToken = standbyCameraDeviceGeneration.incrementAndGet()
            cameraManager.openCamera(
                standbyLens.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (openToken != standbyCameraDeviceGeneration.get() ||
                            (!isEnabled && !isContinuousZoomTransitionActive) ||
                            !bridge.isQuickLensSwitchAllowedNow() ||
                            bridge.isCameraClosingOrUninitialized() ||
                            bridge.getActiveCameraDevice() == null
                        ) {
                            PLog.i(TAG, "Stale standby camera opened (${camera.id}); closing")
                            isPreparingStandby.set(false)
                            try {
                                camera.close()
                            } catch (_: Throwable) {
                            }
                            return
                        }
                        if (standbyCameraDevice != null && standbyCameraDevice !== camera) {
                            try {
                                standbyCameraDevice?.close()
                            } catch (_: Throwable) {
                            }
                        }
                        standbyCameraDevice = camera
                        standbyLensDescriptor = standbyLens
                        configureStandbySessionOnDevice(camera, standbyLens)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        if (openToken != standbyCameraDeviceGeneration.get()) {
                            try {
                                camera.close()
                            } catch (_: Throwable) {
                            }
                            return
                        }
                        standbyCameraDeviceGeneration.incrementAndGet()
                        standbySessionGeneration.incrementAndGet()
                        try {
                            camera.close()
                        } catch (_: Throwable) {
                        }
                        if (standbyCameraDevice === camera) {
                            standbyCameraDevice = null
                            standbyCaptureSession = null
                        }
                        isPreparingStandby.set(false)
                        isStandbyStreaming.set(false)
                        bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
                        if (isEnabled && bridge.isQuickLensSwitchAllowedNow() && !bridge.isCameraClosingOrUninitialized()) {
                            scheduleStandbyReconnect()
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (openToken != standbyCameraDeviceGeneration.get()) {
                            try {
                                camera.close()
                            } catch (_: Throwable) {
                            }
                            return
                        }
                        standbyCameraDeviceGeneration.incrementAndGet()
                        standbySessionGeneration.incrementAndGet()
                        try {
                            camera.close()
                        } catch (_: Throwable) {
                        }
                        if (standbyCameraDevice === camera) {
                            standbyCameraDevice = null
                            standbyCaptureSession = null
                        }
                        isPreparingStandby.set(false)
                        isStandbyStreaming.set(false)
                        bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
                        PLog.w(TAG, "Standby camera open error ($error) on ${camera.id}")
                        if (isEnabled && bridge.isQuickLensSwitchAllowedNow() && !bridge.isCameraClosingOrUninitialized()) {
                            scheduleStandbyReconnect()
                        }
                    }
                },
                getCameraHandler()
            )
        } catch (t: Throwable) {
            standbyCameraDeviceGeneration.incrementAndGet()
            standbySessionGeneration.incrementAndGet()
            isPreparingStandby.set(false)
            bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
            PLog.w(TAG, "Could not open standby camera concurrently: ${t.message}")
            if (isEnabled && bridge.isQuickLensSwitchAllowedNow() && !bridge.isCameraClosingOrUninitialized()) {
                scheduleStandbyReconnect()
            }
        }
    }

    private fun configureStandbySessionOnDevice(
        camera: CameraDevice,
        standbyLens: QuickLensDescriptor
    ) {
        val handler = getCameraHandler()
        try {
            val standbySource = if (standbyLens.role == QuickLensRole.ULTRAWIDE) {
                PreviewStreamSource.ULTRAWIDE
            } else {
                PreviewStreamSource.MAIN
            }
            val expectedPreviewSize = bridge.getOptimalPreviewSizeForDescriptor(standbyLens)
            val standbyPreviewSurf = bridge.getOrCreateSurfaceForSource(standbySource, expectedPreviewSize)
            if (standbyPreviewSurf == null || !standbyPreviewSurf.isValid) {
                isPreparingStandby.set(false)
                return
            }

            try {
                standbyCaptureSession?.stopRepeating()
            } catch (_: Throwable) {
            }
            try {
                standbyCaptureSession?.close()
            } catch (_: Throwable) {
            }
            standbyCaptureSession = null

            val isRecordingActive = bridge.isRecordingVideo()
            val isVideo = bridge.isVideoMode() || isRecordingActive
            standbyConfiguredPreviewSize = expectedPreviewSize
            standbyConfiguredIsVideoMode = isVideo
            val configToken = standbySessionGeneration.incrementAndGet()
            val targetSurfaceTexture = bridge.getSurfaceTextureForSource(standbySource)

            val standbyRecorderSurf = if (isRecordingActive && bridge.isDualRecorderSurfaceSupported()) {
                bridge.getSecondaryRecorderSurface(standbyLens, expectedPreviewSize)
            } else {
                null
            }

            if (isRecordingActive && standbyRecorderSurf != null && standbyRecorderSurf.isValid) {
                val recordBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(standbyPreviewSurf)
                    addTarget(standbyRecorderSurf)
                    applySynchronizedStandbySettings(this, standbyLens)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                }
                standbyRequestBuilder = recordBuilder
                val targetPhysId = resolvePhysicalStreamId(standbyLens)
                val surfaces = listOf(standbyPreviewSurf, standbyRecorderSurf)
                var retriedWithoutPhys = false

                val recCallback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val expectedTex = bridge.getSurfaceTextureForSource(standbySource)
                        val isTexValid = targetSurfaceTexture != null &&
                            targetSurfaceTexture === expectedTex &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !targetSurfaceTexture.isReleased)
                        if (configToken != standbySessionGeneration.get() ||
                            (!isEnabled && !isContinuousZoomTransitionActive) ||
                            standbyCameraDevice !== camera ||
                            !isTexValid
                        ) {
                            isPreparingStandby.set(false)
                            try {
                                session.close()
                            } catch (_: Throwable) {
                            }
                            return
                        }
                        standbyCaptureSession = session
                        standbyLensDescriptor = standbyLens
                        standbyConfiguredForRecording = true
                        standbyRecordingSessionFailed = false
                        isPreparingStandby.set(false)
                        isStandbyStreaming.set(true)
                        bridge.onBackgroundStatusChanged(BackgroundCameraStatus.READY_QUIET)
                        try {
                            applySynchronizedStandbySettings(recordBuilder, standbyLens)
                            recordBuilder.set(
                                CaptureRequest.CONTROL_CAPTURE_INTENT,
                                CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                            )
                            session.setRepeatingRequest(recordBuilder.build(), standbyCaptureCallback, handler)
                            PLog.i(TAG, "Prewarmed recording standby session active on ID ${camera.id} (${standbyLens.role})")
                        } catch (e: Exception) {
                            PLog.e(TAG, "Failed repeating recording request on standby ${camera.id}", e)
                            try {
                                session.close()
                            } catch (_: Throwable) {
                            }
                            if (standbyCaptureSession === session) standbyCaptureSession = null
                            standbyConfiguredForRecording = false
                            standbyRecordingSessionFailed = true
                            isPreparingStandby.set(false)
                            isStandbyStreaming.set(false)
                            bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
                            if (isEnabled && !bridge.isCameraClosingOrUninitialized()) {
                                scheduleStandbyReconnect()
                            }
                        }
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        if (standbyCaptureSession === session) {
                            standbyCaptureSession = null
                            standbyConfiguredForRecording = false
                            isStandbyStreaming.set(false)
                            bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
                            if (isEnabled && !bridge.isCameraClosingOrUninitialized()) {
                                scheduleStandbyReconnect()
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        try {
                            session.close()
                        } catch (_: Throwable) {
                        }
                        if (configToken != standbySessionGeneration.get()) {
                            isPreparingStandby.set(false)
                            return
                        }
                        if (!retriedWithoutPhys && targetPhysId != null) {
                            retriedWithoutPhys = true
                            createSessionInternal(camera, surfaces, null, this, handler)
                            return
                        }
                        PLog.w(TAG, "Hardware rejected concurrent recording standby session on ${camera.id}")
                        standbyConfiguredForRecording = false
                        standbyRecordingSessionFailed = true
                        isPreparingStandby.set(false)
                        if (standbyCaptureSession === session) standbyCaptureSession = null
                    }
                }
                createSessionInternal(camera, surfaces, targetPhysId, recCallback, handler)
                return
            }

            // Standard standby session (preview only — photo capture reader is provisioned safely upon active swap)
            standbyConfiguredForRecording = false
            val template = if (isVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = camera.createCaptureRequest(template).apply {
                addTarget(standbyPreviewSurf)
                applySynchronizedStandbySettings(this, standbyLens)
            }
            standbyRequestBuilder = builder
            val sessionSurfaces = listOf(standbyPreviewSurf)
            val targetPhysId = resolvePhysicalStreamId(standbyLens)
            var retriedWithoutPhys = false

            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    val expectedTex = bridge.getSurfaceTextureForSource(standbySource)
                    val isTexValid = targetSurfaceTexture != null &&
                        targetSurfaceTexture === expectedTex &&
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !targetSurfaceTexture.isReleased)
                    if (configToken != standbySessionGeneration.get() ||
                        (!isEnabled && !isContinuousZoomTransitionActive) ||
                        standbyCameraDevice !== camera ||
                        !isTexValid
                    ) {
                        isPreparingStandby.set(false)
                        try {
                            session.close()
                        } catch (_: Throwable) {
                        }
                        return
                    }
                    standbyCaptureSession = session
                    standbyLensDescriptor = standbyLens
                    isPreparingStandby.set(false)
                    try {
                        session.setRepeatingRequest(builder.build(), standbyCaptureCallback, handler)
                        PLog.i(TAG, "Persistent standby session active on ID ${camera.id} (${standbyLens.role})")
                    } catch (e: Exception) {
                        PLog.e(TAG, "Failed repeating request on standby ${camera.id}", e)
                        try {
                            session.close()
                        } catch (_: Throwable) {
                        }
                        if (standbyCaptureSession === session) standbyCaptureSession = null
                        isPreparingStandby.set(false)
                        isStandbyStreaming.set(false)
                        bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
                        if (isEnabled && !bridge.isCameraClosingOrUninitialized()) {
                            scheduleStandbyReconnect()
                        }
                        return
                    }

                    val pendingLens = pendingLensWhileSwitching
                    if (pendingLens != null && (pendingLens.cameraId == camera.id || pendingLens.id == standbyLens.id)) {
                        pendingLensWhileSwitching = null
                        val pendingContinuous = pendingContinuousTransitionWhileSwitching
                        pendingContinuousTransitionWhileSwitching = false
                        switchUsingPrewarmedStandby(
                            pendingLens,
                            lensSwitchGeneration.get(),
                            isContinuousTransition = pendingContinuous
                        )
                    }
                }

                override fun onClosed(session: CameraCaptureSession) {
                    if (standbyCaptureSession === session) {
                        standbyCaptureSession = null
                        isStandbyStreaming.set(false)
                        bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
                        if (isEnabled && !bridge.isCameraClosingOrUninitialized()) {
                            scheduleStandbyReconnect()
                        }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    try {
                        session.close()
                    } catch (_: Throwable) {
                    }
                    if (configToken != standbySessionGeneration.get()) {
                        isPreparingStandby.set(false)
                        return
                    }
                    if (!retriedWithoutPhys && targetPhysId != null) {
                        retriedWithoutPhys = true
                        createSessionInternal(camera, sessionSurfaces, null, this, handler)
                        return
                    }
                    isPreparingStandby.set(false)
                    isStandbyStreaming.set(false)
                    bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
                    if (standbyCaptureSession === session) standbyCaptureSession = null
                    if (isEnabled && !bridge.isCameraClosingOrUninitialized()) {
                        scheduleStandbyReconnect()
                    }
                }
            }
            createSessionInternal(camera, sessionSurfaces, targetPhysId, stateCallback, handler)
        } catch (e: Exception) {
            isPreparingStandby.set(false)
            PLog.e(TAG, "Failed configuring standby session on ${camera.id}", e)
            if (isEnabled && !bridge.isCameraClosingOrUninitialized()) {
                scheduleStandbyReconnect()
            }
        }
    }

    private fun resolvePhysicalStreamId(lens: QuickLensDescriptor): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            lens.physicalCameraId != null &&
            lens.supportsPhysicalStream &&
            lens.physicalCameraId != lens.cameraId
        ) {
            lens.physicalCameraId
        } else {
            null
        }
    }

    private fun createSessionInternal(
        camera: CameraDevice,
        surfaces: List<Surface>,
        physicalCameraId: String?,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val outputConfigs = surfaces.map { surf ->
                    OutputConfiguration(surf).apply {
                        if (physicalCameraId != null) {
                            setPhysicalCameraId(physicalCameraId)
                        }
                    }
                }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    Executor { cmd -> handler?.post(cmd) ?: cmd.run() },
                    callback
                )
                camera.createCaptureSession(sessionConfig)
                return
            } catch (_: Throwable) {
            }
        }
        @Suppress("DEPRECATION")
        camera.createCaptureSession(surfaces, callback, handler)
    }

    private fun scheduleStandbyReconnect(delayMs: Long = 1000L) {
        if (bridge.isCameraClosingOrUninitialized() || !isEnabled || !bridge.isQuickLensSwitchAllowedNow()) return
        if (standbyReconnectCount >= MAX_STANDBY_RECONNECT) return
        val handler = getCameraHandler() ?: return
        standbyReconnectRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (!bridge.isCameraClosingOrUninitialized() &&
                isEnabled &&
                bridge.isQuickLensSwitchAllowedNow() &&
                bridge.getCurrentDescriptor()?.facing == CameraCharacteristics.LENS_FACING_BACK
            ) {
                if (standbyCameraDevice == null && !isPreparingStandby.get()) {
                    standbyReconnectCount++
                    PLog.i(TAG, "Reopening standby camera (attempt $standbyReconnectCount)")
                    ensureStandbyReady()
                }
            }
        }
        standbyReconnectRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun releaseStandbyCameraOnly() {
        standbyCameraDeviceGeneration.incrementAndGet()
        standbySessionGeneration.incrementAndGet()
        try {
            standbyCaptureSession?.stopRepeating()
        } catch (_: Throwable) {
        }
        try {
            standbyCaptureSession?.close()
        } catch (_: Throwable) {
        }
        standbyCaptureSession = null
        try {
            standbyCameraDevice?.close()
        } catch (_: Throwable) {
        }
        standbyCameraDevice = null
        standbyLensDescriptor = null
        standbyRequestBuilder = null
        standbyConfiguredPreviewSize = null
        standbyConfiguredForRecording = false
    }

    fun releaseStandby() {
        standbyCameraDeviceGeneration.incrementAndGet()
        standbySessionGeneration.incrementAndGet()
        standbyReconnectRunnable?.let { getCameraHandler()?.removeCallbacks(it) }
        standbyReconnectRunnable = null
        isPreparingStandby.set(false)
        isStandbyStreaming.set(false)
        try {
            standbyCaptureSession?.stopRepeating()
        } catch (_: Throwable) {
        }
        try {
            standbyCaptureSession?.close()
        } catch (_: Throwable) {
        }
        standbyCaptureSession = null
        try {
            standbyCameraDevice?.close()
        } catch (_: Throwable) {
        }
        standbyCameraDevice = null
        try {
            standbyImageReader?.close()
        } catch (_: Throwable) {
        }
        standbyImageReader = null
        standbyLensDescriptor = null
        standbyRequestBuilder = null
        standbyConfiguredPreviewSize = null
        standbyConfiguredIsVideoMode = false
        standbyConfiguredForRecording = false
        standbyRecordingSessionFailed = false
        activeLogicalMultiCamUltraWideConfigured = false
        bridge.onBackgroundStatusChanged(BackgroundCameraStatus.OFF)
    }

    internal fun calculateStandbyCropRatio(
        standbyLens: QuickLensDescriptor,
        uiZoomOverride: Float? = null
    ): Float {
        val sameFacingLenses = bridge.getAvailableDescriptors().filter {
            it.facing == standbyLens.facing
        }
        val mainLens = sameFacingLenses.firstOrNull { it.isPrimaryMain }
            ?: sameFacingLenses.firstOrNull { it.role == QuickLensRole.WIDE && !it.isZoomPreset }
            ?: sameFacingLenses.firstOrNull { it.role == QuickLensRole.WIDE }
        val mainEqFocalMm = mainLens?.equivalent35mmFocalMm
            ?.takeIf { it.isFinite() && it > 0f }
            ?: CameraOpticalCalibration.DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
        val uwEqFocalMm = sameFacingLenses.firstOrNull { it.role == QuickLensRole.ULTRAWIDE }
            ?.equivalent35mmFocalMm
            ?.takeIf { it.isFinite() && it > 0f }
            ?: CameraOpticalCalibration.DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM

        if (uiZoomOverride != null && uiZoomOverride.isFinite()) {
            return CameraOpticalCalibration.calculateRequiredDigitalCrop(
                uiZoom = uiZoomOverride.coerceAtLeast(0.5f),
                lensBaseRatio = standbyLens.baseZoomRatio,
                lensType = standbyLens.role,
                uwEquivalentFocalMm = uwEqFocalMm,
                mainEquivalentFocalMm = mainEqFocalMm,
                switchPointMm = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM
            )
        }
        return if (standbyLens.role == QuickLensRole.ULTRAWIDE) {
            CameraOpticalCalibration.calculateFovMatchedUltraWideCrop(
                switchPointMm = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM,
                mainEquivalentFocalMm = mainEqFocalMm,
                uwEquivalentFocalMm = uwEqFocalMm
            )
        } else {
            val matchedTargetFocalMm = maxOf(CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM, mainEqFocalMm)
            CameraOpticalCalibration.calculateMainCropForSwitchPoint(matchedTargetFocalMm, mainEqFocalMm)
        }
    }

    private fun applySynchronizedStandbySettings(
        builder: CaptureRequest.Builder,
        standbyLens: QuickLensDescriptor
    ) {
        try {
            bridge.applyBaseSettingsToBuilder(builder, standbyLens)
            val isRecordingActive = bridge.isRecordingVideo()
            val isVideo = bridge.isVideoMode() || isRecordingActive
            if (isRecordingActive) {
                builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
            }
            val chars = getCharacteristics(standbyLens.cameraId)
            val fpsRange = bridge.getTargetFpsRangeForDescriptor(standbyLens, isVideo)
            if (fpsRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            }
            val sensorRect = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: Rect(0, 0, 4000, 3000)
            val handoffState = zoomTransitionController.previewOverlapState.value
            val handoffZoom = if (handoffState.isOverlapping) {
                zoomTransitionController.currentInterpolatedZoom.value
            } else {
                null
            }
            val targetStandbyRatio = calculateStandbyCropRatio(standbyLens, handoffZoom)
            applyCalibratedCropOrZoomRatio(
                builder = builder,
                chars = chars,
                desiredZoom = targetStandbyRatio,
                sensorRect = sensorRect
            )
        } catch (e: Exception) {
            PLog.w(TAG, "Error applying synchronized standby settings", e)
        }
    }

    fun syncStandbyStreamSettings(forceDuringFrameHandoff: Boolean = false) {
        val handoffActive = zoomTransitionController.previewOverlapState.value.isOverlapping
        if ((!isEnabled && !(forceDuringFrameHandoff && handoffActive)) ||
            bridge.isCameraClosingOrUninitialized()
        ) {
            return
        }
        val session = standbyCaptureSession ?: return
        val builder = standbyRequestBuilder ?: return
        val standbyCam = standbyCameraDevice ?: return
        val backLenses = bridge.getAvailableDescriptors().filter {
            it.facing == CameraCharacteristics.LENS_FACING_BACK
        }
        val standbyLens = standbyLensDescriptor
            ?: backLenses.firstOrNull { it.cameraId == standbyCam.id && !it.isZoomPreset }
            ?: backLenses.firstOrNull { it.cameraId == standbyCam.id }
            ?: return
        try {
            applySynchronizedStandbySettings(builder, standbyLens)
            session.setRepeatingRequest(builder.build(), standbyCaptureCallback, getCameraHandler())
        } catch (_: Exception) {
        }
    }

    private fun syncStandbyIfFrameHandoffActive() {
        if (zoomTransitionController.previewOverlapState.value.isOverlapping) {
            syncStandbyStreamSettings(forceDuringFrameHandoff = true)
        }
    }

    fun applyZoomToBuilder(
        builder: CaptureRequest.Builder,
        lens: QuickLensDescriptor,
        explicitUiZoom: Float? = null
    ) {
        try {
            val chars = getCharacteristics(lens.cameraId) ?: return
            val allLenses = bridge.getAvailableDescriptors().filter { it.facing == lens.facing }
            val uwLens = allLenses.firstOrNull { it.role == QuickLensRole.ULTRAWIDE }
            val mainLens = allLenses.firstOrNull { it.isPrimaryMain }
                ?: allLenses.firstOrNull { it.role == QuickLensRole.WIDE && !it.isZoomPreset }
                ?: allLenses.firstOrNull { it.role == QuickLensRole.WIDE }
            val effectiveUiZoom = explicitUiZoom ?: bridge.getCurrentUiZoom()
            val uwEqFocal = uwLens?.equivalent35mmFocalMm?.takeIf { it > 0f }
                ?: CameraOpticalCalibration.DEFAULT_ULTRAWIDE_EQUIVALENT_FOCAL_MM
            val mainEqFocal = mainLens?.equivalent35mmFocalMm?.takeIf { it > 0f }
                ?: CameraOpticalCalibration.DEFAULT_MAIN_EQUIVALENT_FOCAL_MM
            val digitalCrop = CameraOpticalCalibration.calculateRequiredDigitalCrop(
                uiZoom = effectiveUiZoom,
                lensBaseRatio = lens.baseZoomRatio,
                lensType = lens.role,
                uwEquivalentFocalMm = uwEqFocal,
                mainEquivalentFocalMm = mainEqFocal,
                switchPointMm = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM
            )
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val isLogicalMulti = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                chars.physicalCameraIds.size > 1
            val activePhysId = bridge.getActiveOutputPhysicalCameraId()
            val isRunningOnPhysicalStream = (activePhysId != null && activePhysId == lens.physicalCameraId) ||
                (!isLogicalMulti && lens.physicalCameraId != null && lens.supportsPhysicalStream)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRange != null) {
                    val desiredZoom = when {
                        lens.role == QuickLensRole.FRONT -> digitalCrop
                        isLogicalMulti && !isRunningOnPhysicalStream -> effectiveUiZoom
                        else -> digitalCrop
                    }
                    val maxAllowedZoom = if (lens.role == QuickLensRole.ULTRAWIDE &&
                        (!isLogicalMulti || isRunningOnPhysicalStream)
                    ) {
                        val baseUwCrop = CameraOpticalCalibration.calculateUltraWideCropForSwitchPoint(
                            CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM,
                            uwEqFocal
                        )
                        val switchZ = CameraOpticalCalibration.switchPointToZoom(
                            CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM
                        )
                        baseUwCrop * (effectiveUiZoom / switchZ).coerceAtLeast(1.0f)
                    } else {
                        zoomRange.upper
                    }
                    val targetZoomRatio = desiredZoom.coerceIn(
                        zoomRange.lower,
                        minOf(zoomRange.upper, maxAllowedZoom.coerceAtLeast(zoomRange.lower))
                    )
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, targetZoomRatio)
                    if (desiredZoom > zoomRange.upper) {
                        val extraScale = (desiredZoom / zoomRange.upper).coerceAtLeast(1.0f)
                        val cropW = (sensorRect.width() / extraScale).toInt().coerceIn(1, sensorRect.width())
                        val cropH = (sensorRect.height() / extraScale).toInt().coerceIn(1, sensorRect.height())
                        val cropX = sensorRect.left + (sensorRect.width() - cropW) / 2
                        val cropY = sensorRect.top + (sensorRect.height() - cropH) / 2
                        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                    } else {
                        builder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect)
                    }
                    return
                }
            }
            applyCalibratedCropOrZoomRatio(builder, chars, digitalCrop, sensorRect)
        } catch (e: Exception) {
            PLog.w(TAG, "Error applying zoom to builder", e)
        }
    }

    private fun applyCalibratedCropOrZoomRatio(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics?,
        desiredZoom: Float,
        sensorRect: Rect
    ) {
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && chars != null) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        if (zoomRange != null) {
            builder.set(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                desiredZoom.coerceIn(zoomRange.lower, zoomRange.upper)
            )
            builder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect)
            return
        }
        val maxDigitalZoom = chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        val factor = desiredZoom.coerceIn(1.0f, maxOf(maxDigitalZoom, desiredZoom, 1.0f))
        val cropW = (sensorRect.width() / factor).toInt().coerceIn(1, sensorRect.width())
        val cropH = (sensorRect.height() / factor).toInt().coerceIn(1, sensorRect.height())
        val cropX = sensorRect.left + (sensorRect.width() - cropW) / 2
        val cropY = sensorRect.top + (sensorRect.height() - cropH) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
    }

    fun scheduleZoomPreviewUpdate(immediate: Boolean = false, explicitZoom: Float? = null) {
        val handler = getCameraHandler()
        val now = SystemClock.uptimeMillis()
        val isContinuous = isContinuousZoomTransitionActive
        val zoomToApply = explicitZoom ?: bridge.getCurrentUiZoom()
        if (handler == null || Looper.myLooper() == handler.looper) {
            if (!immediate && !isContinuous && (now - lastZoomPreviewUpdateTimeMs < ZOOM_UPDATE_INTERVAL_MS)) {
                if (pendingZoomRunnable == null) {
                    val delayMs = (ZOOM_UPDATE_INTERVAL_MS - (now - lastZoomPreviewUpdateTimeMs))
                        .coerceIn(4L, ZOOM_UPDATE_INTERVAL_MS)
                    val runnable = Runnable {
                        pendingZoomRunnable = null
                        lastZoomPreviewUpdateTimeMs = SystemClock.uptimeMillis()
                        updateActivePreviewZoom(pendingZoomUpdateTarget ?: zoomToApply)
                        syncStandbyIfFrameHandoffActive()
                    }
                    pendingZoomRunnable = runnable
                    pendingZoomUpdateTarget = zoomToApply
                    handler?.postDelayed(runnable, delayMs)
                } else {
                    pendingZoomUpdateTarget = zoomToApply
                }
                return
            }
            pendingZoomRunnable?.let { handler?.removeCallbacks(it) }
            pendingZoomRunnable = null
            pendingZoomUpdateTarget = zoomToApply
            lastZoomPreviewUpdateTimeMs = now
            updateActivePreviewZoom(zoomToApply)
            syncStandbyIfFrameHandoffActive()
            return
        }

        pendingZoomUpdateTarget = zoomToApply
        if (isZoomUpdateScheduled.compareAndSet(false, true) || immediate) {
            handler.post {
                isZoomUpdateScheduled.set(false)
                val target = pendingZoomUpdateTarget ?: zoomToApply
                pendingZoomUpdateTarget = null
                lastZoomPreviewUpdateTimeMs = SystemClock.uptimeMillis()
                updateActivePreviewZoom(target)
                syncStandbyIfFrameHandoffActive()
            }
        }
    }

    private fun updateActivePreviewZoom(uiZoom: Float) {
        val session = bridge.getActiveCaptureSession() ?: return
        val builder = bridge.getActivePreviewRequestBuilder() ?: return
        val lens = activeSessionDescriptor ?: bridge.getCurrentDescriptor() ?: return
        try {
            applyZoomToBuilder(builder, lens, uiZoom)
            session.setRepeatingRequest(
                builder.build(),
                bridge.getActiveCaptureCallback(),
                getCameraHandler()
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Port of VCamera Camera2Engine.setZoom: resolves optimal lens with hysteresis, triggers
     * instant standby swap or concurrent open when switching lenses, and updates calibrated zoom.
     */
    fun setZoom(zoom: Float, isPresetTap: Boolean = false, isContinuousTransition: Boolean = false) {
        val inFlightLens = activeSwitchingTargetDescriptor ?: bridge.getCurrentDescriptor() ?: return
        isContinuousZoomTransitionActive = isContinuousTransition || zoomTransitionController.isTransitionActive.value
        val isFront = inFlightLens.facing == CameraCharacteristics.LENS_FACING_FRONT
        val sameFacingLenses = bridge.getAvailableDescriptors().filter { it.facing == inFlightLens.facing }
        val uwLens = sameFacingLenses.firstOrNull { it.role == QuickLensRole.ULTRAWIDE && !it.isZoomPreset }
            ?: sameFacingLenses.firstOrNull { it.role == QuickLensRole.ULTRAWIDE }
        val mainWideLens = sameFacingLenses.firstOrNull { it.isPrimaryMain }
            ?: sameFacingLenses.firstOrNull { it.role == QuickLensRole.WIDE && !it.isZoomPreset }
            ?: sameFacingLenses.firstOrNull { it.role == QuickLensRole.WIDE }
            ?: sameFacingLenses.firstOrNull()

        val minAllowedZoom = if (isFront) 1.0f else if (uwLens != null) 0.5f else 1.0f
        val maxCapabilityZoom = if (isFront) {
            inFlightLens.maxZoomRatio.coerceAtLeast(1.0f)
        } else {
            maxOf(sameFacingLenses.maxOfOrNull { it.maxZoomRatio } ?: 20.0f, 20.0f)
        }
        val clampedZoom = zoom.coerceIn(minAllowedZoom, maxCapabilityZoom)
        bridge.updateCurrentUiZoom(clampedZoom)

        if (isFront) {
            if (isSwitchingLens.get()) {
                pendingZoomWhileSwitching = clampedZoom
                pendingZoomPresetTapWhileSwitching = isPresetTap
                pendingContinuousTransitionWhileSwitching = isContinuousTransition
            }
            scheduleZoomPreviewUpdate(immediate = isPresetTap || isContinuousTransition, explicitZoom = clampedZoom)
            return
        }

        val hasUw = uwLens != null
        val hasTele2x = sameFacingLenses.any { it.role == QuickLensRole.TELEPHOTO }
        val hasTele3x = sameFacingLenses.any { it.role == QuickLensRole.TELEPHOTO_3X }
        val switchPoint = CameraOpticalCalibration.DEFAULT_SWITCH_POINT_MM
        val referenceRole = (activeSessionDescriptor ?: inFlightLens).role
        val targetRole = CameraOpticalCalibration.resolveTargetLensType(
            currentLensType = referenceRole,
            targetZoom = clampedZoom,
            hasUltraWide = hasUw,
            hasTelephoto2x = hasTele2x,
            hasTelephoto3x = hasTele3x,
            isPresetTap = isPresetTap,
            switchPointMm = switchPoint,
            isContinuousTransition = isContinuousTransition
        )
        val targetLens = when (targetRole) {
            QuickLensRole.ULTRAWIDE -> uwLens ?: mainWideLens ?: inFlightLens
            QuickLensRole.TELEPHOTO -> sameFacingLenses.firstOrNull { it.role == QuickLensRole.TELEPHOTO }
                ?: mainWideLens ?: inFlightLens
            QuickLensRole.TELEPHOTO_3X -> sameFacingLenses.firstOrNull { it.role == QuickLensRole.TELEPHOTO_3X }
                ?: mainWideLens ?: inFlightLens
            else -> mainWideLens ?: inFlightLens
        }

        if (isSwitchingLens.get()) {
            pendingZoomWhileSwitching = clampedZoom
            pendingZoomPresetTapWhileSwitching = isPresetTap
            val inFlightTarget = activeSwitchingTargetDescriptor
            pendingLensWhileSwitching = if (inFlightTarget == null ||
                (targetLens.id != inFlightTarget.id && targetLens.role != inFlightTarget.role)
            ) {
                targetLens
            } else {
                null
            }
            pendingContinuousTransitionWhileSwitching = isContinuousTransition
            scheduleZoomPreviewUpdate(immediate = isPresetTap || isContinuousTransition, explicitZoom = clampedZoom)
            return
        }

        val activeLens = activeSessionDescriptor ?: inFlightLens
        val isContinuous = isContinuousTransition || isContinuousZoomTransitionActive
        val keepUwUntilSwitchPoint = CameraOpticalCalibration.shouldKeepUltraWideDuringContinuousZoom(
            currentLensType = activeLens.role,
            targetZoom = clampedZoom,
            switchPointMm = switchPoint,
            isContinuousTransition = isContinuous
        )
        val effectiveTargetLens = if (keepUwUntilSwitchPoint) {
            uwLens ?: targetLens
        } else {
            targetLens
        }

        val activeDevice = bridge.getActiveCameraDevice()
        val isDifferentDevice = activeDevice != null && effectiveTargetLens.cameraId != activeDevice.id
        val isDifferentPhysicalStream = activeDevice != null &&
            effectiveTargetLens.cameraId == activeDevice.id &&
            effectiveTargetLens.physicalCameraId != null &&
            effectiveTargetLens.physicalCameraId != bridge.getActiveOutputPhysicalCameraId()
        val isInFlightTarget = activeSwitchingTargetDescriptor?.let {
            effectiveTargetLens.id == it.id || effectiveTargetLens.role == it.role
        } == true

        val shouldSwitchPhysicalLens = (isDifferentDevice || isDifferentPhysicalStream ||
            (effectiveTargetLens.role != activeLens.role &&
                (activeLogicalMultiCamUltraWideConfigured || standbyCaptureSession != null))) && !isInFlightTarget

        if (shouldSwitchPhysicalLens) {
            selectLens(
                targetLens = effectiveTargetLens,
                targetZoom = clampedZoom,
                isContinuousTransition = isContinuous
            )
        } else {
            if (effectiveTargetLens.id == activeLens.id || activeDevice == null ||
                effectiveTargetLens.cameraId == activeDevice.id
            ) {
                activeSessionDescriptor = effectiveTargetLens
                bridge.updateSelectedLensDescriptor(effectiveTargetLens)
            }
            val useUw = effectiveTargetLens.role == QuickLensRole.ULTRAWIDE
            val targetSource = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
            if (useUw && clampedZoom > 0.5f && !isContinuous && isEnabled) {
                postOrRun { ensureStandbyReady() }
            }
            if (isEnabled && (activeLogicalMultiCamUltraWideConfigured || standbyCaptureSession != null)) {
                val inputSize = bridge.getOptimalPreviewSizeForDescriptor(effectiveTargetLens)
                bridge.notifyRecorderActiveSource(targetSource, effectiveTargetLens, inputSize)
                bridge.onActiveStreamSwapped(effectiveTargetLens, useUw, targetSource)
            }
            scheduleZoomPreviewUpdate(immediate = isPresetTap || isContinuous, explicitZoom = clampedZoom)
        }
    }

    fun selectLens(
        targetLens: QuickLensDescriptor,
        targetZoom: Float = targetLens.baseZoomRatio,
        isContinuousTransition: Boolean = false
    ) {
        if (!isEnabled && !isContinuousZoomTransitionActive) {
            bridge.fallbackToPhotonCameraOpen(targetLens, targetZoom)
            return
        }
        val activeDevice = bridge.getActiveCameraDevice()
        val currentRunningLens = activeSessionDescriptor ?: bridge.getCurrentDescriptor()
        val switchGen = lensSwitchGeneration.incrementAndGet()
        isSwitchingLens.set(true)
        activeSwitchingTargetDescriptor = targetLens
        bridge.updateCurrentUiZoom(targetZoom)

        val isSwitchBetweenMainAndUw =
            (currentRunningLens?.role == QuickLensRole.WIDE && targetLens.role == QuickLensRole.ULTRAWIDE) ||
                (currentRunningLens?.role == QuickLensRole.ULTRAWIDE && targetLens.role == QuickLensRole.WIDE) ||
                (targetLens.role == QuickLensRole.WIDE && activeDevice != null && targetLens.cameraId != activeDevice.id)

        val isSameCameraDevice = activeDevice != null && targetLens.cameraId == activeDevice.id

        // Case A: Logical multi-camera with simultaneous Main + Physical Ultra-Wide streams
        if (isSameCameraDevice &&
            bridge.getActiveCaptureSession() != null &&
            activeLogicalMultiCamUltraWideConfigured &&
            isSwitchBetweenMainAndUw
        ) {
            val useUw = targetLens.role == QuickLensRole.ULTRAWIDE
            val targetSource = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
            val inputSize = bridge.getOptimalPreviewSizeForDescriptor(targetLens)
            bridge.notifyRecorderActiveSource(targetSource, targetLens, inputSize)
            activeSessionDescriptor = targetLens
            bridge.setActiveOutputPhysicalCameraId(targetLens.physicalCameraId)
            bridge.updateSelectedLensDescriptor(targetLens)
            bridge.onActiveStreamSwapped(targetLens, useUw, targetSource)
            bridge.onActiveLensCapabilitiesChanged(targetLens, targetZoom)
            scheduleZoomPreviewUpdate(immediate = true, explicitZoom = targetZoom)
            completeLensSwitch(targetLens, isContinuousTransition)
            PLog.i(TAG, "0ms logical-physical surface switch -> ${targetLens.role} (source=$targetSource)")
            return
        }

        // Case B: Persistent dual-session swap using already-warmed standby CameraDevice
        val prewarmedCam = standbyCameraDevice
        if (prewarmedCam != null && prewarmedCam.id == targetLens.cameraId && standbyCaptureSession != null) {
            switchUsingPrewarmedStandby(targetLens, switchGen, isContinuousTransition)
            return
        }

        // Case C: Concurrent switch (keep active stream visible while opening target camera) when not recording
        if (activeDevice != null && !bridge.isRecordingVideo() && targetLens.cameraId != activeDevice.id) {
            switchCameraDevice(targetLens, targetZoom, switchGen, isContinuousTransition)
            return
        }

        // Case D: Same camera or recording switch or sequential fallback
        completeLensSwitch(targetLens, isContinuousTransition)
        bridge.fallbackToPhotonCameraOpen(targetLens, targetZoom)
    }

    private fun switchUsingPrewarmedStandby(
        targetLens: QuickLensDescriptor,
        switchGen: Int,
        isContinuousTransition: Boolean
    ) {
        val targetCam = standbyCameraDevice ?: run {
            completeLensSwitch(targetLens, isContinuousTransition)
            bridge.fallbackToPhotonCameraOpen(targetLens, bridge.getCurrentUiZoom())
            return
        }
        val standbySession = standbyCaptureSession ?: run {
            completeLensSwitch(targetLens, isContinuousTransition)
            bridge.fallbackToPhotonCameraOpen(targetLens, bridge.getCurrentUiZoom())
            return
        }
        synchronized(lifecycleLock) {
            if (switchGen != lensSwitchGeneration.get()) {
                completeLensSwitch(targetLens, isContinuousTransition)
                return
            }
            val oldCam = bridge.getActiveCameraDevice()
            val oldSession = bridge.getActiveCaptureSession()
            val oldBuilder = bridge.getActivePreviewRequestBuilder()
            val oldReader = bridge.getActiveImageReader()
            val oldDescriptor = activeSessionDescriptor ?: bridge.getCurrentDescriptor()

            val targetBuilder = standbyRequestBuilder
            val targetReader = standbyImageReader

            // 0ms pointer swap!
            bridge.setActiveCameraDevice(targetCam)
            bridge.setActiveCaptureSession(standbySession)
            bridge.setActivePreviewRequestBuilder(targetBuilder)
            if (targetReader != null) {
                bridge.setActiveImageReader(targetReader)
            }
            bridge.setActiveOpenCameraId(targetCam.id)
            bridge.setActiveOutputPhysicalCameraId(targetLens.physicalCameraId)

            standbyCameraDevice = oldCam
            standbyCaptureSession = oldSession
            standbyRequestBuilder = oldBuilder
            if (targetReader != null) {
                standbyImageReader = oldReader
            }
            standbyLensDescriptor = oldDescriptor

            val useUw = targetLens.role == QuickLensRole.ULTRAWIDE
            val targetSource = if (useUw) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
            val inputSize = bridge.getOptimalPreviewSizeForDescriptor(targetLens)
            if (bridge.isRecordingVideo()) {
                bridge.notifyRecorderActiveSource(targetSource, targetLens, inputSize)
            }

            activeSessionDescriptor = targetLens
            bridge.updateSelectedLensDescriptor(targetLens)
            bridge.onActiveStreamSwapped(targetLens, useUw, targetSource)
            bridge.onActiveLensCapabilitiesChanged(targetLens, bridge.getCurrentUiZoom())
            scheduleZoomPreviewUpdate(immediate = true, explicitZoom = bridge.getCurrentUiZoom())

            isStandbyStreaming.set(oldSession != null)
            bridge.onBackgroundStatusChanged(
                if (oldSession != null) BackgroundCameraStatus.READY_QUIET else BackgroundCameraStatus.OFF
            )
            postOrRun { syncStandbyStreamSettings() }
            completeLensSwitch(targetLens, isContinuousTransition)
            PLog.i(TAG, "0ms prewarmed standby swap completed -> ${targetLens.role} (id=${targetCam.id})")
        }
    }

    @SuppressLint("MissingPermission")
    private fun switchCameraDevice(
        targetLens: QuickLensDescriptor,
        targetZoom: Float,
        switchGen: Int,
        isContinuousTransition: Boolean
    ) {
        val handler = getCameraHandler()
        val targetSource = if (targetLens.role == QuickLensRole.ULTRAWIDE) {
            PreviewStreamSource.ULTRAWIDE
        } else {
            PreviewStreamSource.MAIN
        }
        val optimalSize = bridge.getOptimalPreviewSizeForDescriptor(targetLens)
        val targetSurf = bridge.getOrCreateSurfaceForSource(targetSource, optimalSize)
        if (targetSurf == null || !targetSurf.isValid) {
            fallbackToSequentialSwitch(targetLens, targetZoom, switchGen, isContinuousTransition)
            return
        }
        try {
            cameraManager.openCamera(
                targetLens.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(newCamera: CameraDevice) {
                        synchronized(lifecycleLock) {
                            if (switchGen != lensSwitchGeneration.get() || bridge.isCameraClosingOrUninitialized()) {
                                try {
                                    newCamera.close()
                                } catch (_: Throwable) {
                                }
                                return
                            }
                            val sessionCb = object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    synchronized(lifecycleLock) {
                                        if (switchGen != lensSwitchGeneration.get() ||
                                            bridge.isCameraClosingOrUninitialized()
                                        ) {
                                            try {
                                                session.close()
                                            } catch (_: Throwable) {
                                            }
                                            try {
                                                newCamera.close()
                                            } catch (_: Throwable) {
                                            }
                                            return
                                        }
                                        val builder = try {
                                            val tpl = if (bridge.isVideoMode()) {
                                                CameraDevice.TEMPLATE_RECORD
                                            } else {
                                                CameraDevice.TEMPLATE_PREVIEW
                                            }
                                            newCamera.createCaptureRequest(tpl).apply {
                                                addTarget(targetSurf)
                                                bridge.applyBaseSettingsToBuilder(this, targetLens)
                                                applyZoomToBuilder(this, targetLens, targetZoom)
                                            }
                                        } catch (_: Exception) {
                                            null
                                        }

                                        val oldDevice = bridge.getActiveCameraDevice()
                                        val oldSession = bridge.getActiveCaptureSession()
                                        val oldBuilder = bridge.getActivePreviewRequestBuilder()
                                        val oldReader = bridge.getActiveImageReader()
                                        val oldDescriptor = activeSessionDescriptor ?: bridge.getCurrentDescriptor()

                                        bridge.setActiveCameraDevice(newCamera)
                                        bridge.setActiveCaptureSession(session)
                                        bridge.setActivePreviewRequestBuilder(builder)
                                        bridge.setActiveOpenCameraId(newCamera.id)
                                        bridge.setActiveOutputPhysicalCameraId(targetLens.physicalCameraId)

                                        if (builder != null) {
                                            try {
                                                session.setRepeatingRequest(
                                                    builder.build(),
                                                    bridge.getActiveCaptureCallback(),
                                                    handler
                                                )
                                            } catch (e: Exception) {
                                                PLog.w(TAG, "Failed repeating on new camera ${newCamera.id}", e)
                                            }
                                        }

                                        val useUw = targetLens.role == QuickLensRole.ULTRAWIDE
                                        activeSessionDescriptor = targetLens
                                        bridge.updateSelectedLensDescriptor(targetLens)
                                        bridge.onActiveStreamSwapped(targetLens, useUw, targetSource)
                                        bridge.onActiveLensCapabilitiesChanged(targetLens, targetZoom)

                                        if (isEnabled && bridge.isQuickLensSwitchAllowedNow() &&
                                            oldDevice != null && oldSession != null &&
                                            oldDevice !== newCamera
                                        ) {
                                            if (standbyCameraDevice != null && standbyCameraDevice !== oldDevice) {
                                                try {
                                                    standbyCaptureSession?.close()
                                                } catch (_: Throwable) {
                                                }
                                                try {
                                                    standbyCameraDevice?.close()
                                                } catch (_: Throwable) {
                                                }
                                            }
                                            standbyCameraDevice = oldDevice
                                            standbyCaptureSession = oldSession
                                            standbyRequestBuilder = oldBuilder
                                            standbyImageReader = oldReader
                                            standbyLensDescriptor = oldDescriptor
                                            isStandbyStreaming.set(true)
                                            bridge.onBackgroundStatusChanged(BackgroundCameraStatus.READY_QUIET)
                                            postOrRun { syncStandbyStreamSettings() }
                                        } else {
                                            try {
                                                oldSession?.stopRepeating()
                                            } catch (_: Throwable) {
                                            }
                                            try {
                                                oldSession?.close()
                                            } catch (_: Throwable) {
                                            }
                                            if (oldDevice !== newCamera) {
                                                try {
                                                    oldDevice?.close()
                                                } catch (_: Throwable) {
                                                }
                                            }
                                            if (standbyCameraDevice === oldDevice) {
                                                standbyCameraDevice = null
                                                standbyCaptureSession = null
                                            }
                                        }

                                        bridge.setupPhotoImageReaderForActiveLens(targetLens) {}
                                        scheduleZoomPreviewUpdate(immediate = true, explicitZoom = targetZoom)
                                        completeLensSwitch(targetLens, isContinuousTransition)
                                        PLog.i(TAG, "Concurrent camera switch completed -> ${targetLens.role} (${newCamera.id})")
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    PLog.w(TAG, "Concurrent configureFailed on ${newCamera.id}; sequential fallback")
                                    try {
                                        session.close()
                                    } catch (_: Throwable) {
                                    }
                                    try {
                                        newCamera.close()
                                    } catch (_: Throwable) {
                                    }
                                    fallbackToSequentialSwitch(targetLens, targetZoom, switchGen, isContinuousTransition)
                                }
                            }
                            createSessionInternal(
                                newCamera,
                                listOf(targetSurf),
                                resolvePhysicalStreamId(targetLens),
                                sessionCb,
                                handler
                            )
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        try {
                            camera.close()
                        } catch (_: Throwable) {
                        }
                        if (switchGen == lensSwitchGeneration.get()) {
                            fallbackToSequentialSwitch(targetLens, targetZoom, switchGen, isContinuousTransition)
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        PLog.w(TAG, "Concurrent open error $error on ${targetLens.cameraId}; sequential fallback")
                        try {
                            camera.close()
                        } catch (_: Throwable) {
                        }
                        if (switchGen == lensSwitchGeneration.get()) {
                            fallbackToSequentialSwitch(targetLens, targetZoom, switchGen, isContinuousTransition)
                        }
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            PLog.w(TAG, "Concurrent open exception on ${targetLens.cameraId}: ${t.message}")
            fallbackToSequentialSwitch(targetLens, targetZoom, switchGen, isContinuousTransition)
        }
    }

    private fun fallbackToSequentialSwitch(
        targetLens: QuickLensDescriptor,
        targetZoom: Float,
        switchGen: Int,
        isContinuousTransition: Boolean
    ) {
        if (switchGen != lensSwitchGeneration.get()) return
        completeLensSwitch(targetLens, isContinuousTransition)
        bridge.fallbackToPhotonCameraOpen(targetLens, targetZoom)
    }

    private fun completeLensSwitch(
        completedLens: QuickLensDescriptor,
        isContinuousTransition: Boolean = false
    ) {
        isSwitchingLens.set(false)
        activeSwitchingTargetDescriptor = null

        val queuedLens = pendingLensWhileSwitching
        val queuedZoom = pendingZoomWhileSwitching
        val queuedPresetTap = pendingZoomPresetTapWhileSwitching
        val queuedContinuous = pendingContinuousTransitionWhileSwitching
        pendingLensWhileSwitching = null
        pendingZoomWhileSwitching = null
        pendingZoomPresetTapWhileSwitching = false
        pendingContinuousTransitionWhileSwitching = false

        if (queuedLens != null && queuedLens.id != completedLens.id) {
            val z = queuedZoom ?: bridge.getCurrentUiZoom()
            setZoom(z, isPresetTap = queuedPresetTap, isContinuousTransition = queuedContinuous || isContinuousTransition)
            return
        }
        if (queuedZoom != null) {
            setZoom(queuedZoom, isPresetTap = queuedPresetTap, isContinuousTransition = queuedContinuous || isContinuousTransition)
        }
    }

    fun ownsCameraDevice(device: CameraDevice): Boolean {
        return standbyCameraDevice === device
    }

    fun ownsCaptureSession(session: CameraCaptureSession): Boolean {
        return standbyCaptureSession === session
    }

    private fun postOrRun(action: () -> Unit) {
        val handler = getCameraHandler()
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post(action)
        } else {
            action()
        }
    }
}
