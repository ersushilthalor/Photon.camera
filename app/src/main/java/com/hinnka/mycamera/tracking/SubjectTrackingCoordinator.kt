package com.hinnka.mycamera.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Camera2 analysis bridge. The camera Image is read and closed on its owner callback; inference owns
 * one of three independent RGB buffers until MediaPipe returns. No JPEG or per-frame Bitmap path.
 */
internal class SubjectTrackingCoordinator(
    context: Context,
    private val onUiState: (SubjectTrackingUiState) -> Unit,
    private val onCrop: (TrackingCrop) -> TrackingCrop,
    private val onResolutionFallback: (SubjectTrackingInputResolution) -> Unit,
) : AutoCloseable, SensorEventListener {
    private val appContext = context.applicationContext
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SubjectTracking-Inference").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val tracker = SubjectTracker()
    private val trackerLock = Any()
    private val trackingEpoch = AtomicLong(0L)
    private val cropController = CropController()
    private val performance = TrackingPerformanceMonitor()
    private val bufferPool = RgbInputBufferPool(3)
    private val inferenceBusy = AtomicBoolean(false)
    private val enabled = AtomicBoolean(false)
    private val lock = Any()
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile private var detector: PersonObjectDetector? = null
    @Volatile private var settings = SubjectTrackingSettings()
    @Volatile private var isLoading = false
    @Volatile private var modelError: String? = null
    private var lastInferenceTimestampNs = 0L
    private var lastUiPublishTimestampNs = 0L
    @Volatile private var currentSnapshot = SubjectTrackingSnapshot()
    @Volatile private var lastCameraTimestampNs = 0L
    @Volatile private var outputCrop = TrackingCrop.WIDE
    private var lastPerformanceLogNs = 0L
    @Volatile private var isClosed = false

    fun setEnabled(value: Boolean) {
        if (isClosed || enabled.getAndSet(value) == value) return
        trackingEpoch.incrementAndGet()
        if (!value) {
            synchronized(trackerLock) { tracker.unlock() }
            cropController.reset()
            outputCrop = onCrop(TrackingCrop.WIDE)
            val previousDetector = detector
            detector = null
            inferenceExecutor.execute { previousDetector?.close() }
            isLoading = false
            modelError = null
            currentSnapshot = SubjectTrackingSnapshot()
            publish(force = true)
            sensorManager?.unregisterListener(this)
            return
        }

        isLoading = true
        modelError = null
        sensorManager?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        publish(force = true)
        inferenceExecutor.execute {
            if (!enabled.get() || isClosed) return@execute
            val created = PersonObjectDetector(appContext)
            try {
                created.initialize()
                if (!enabled.get() || isClosed) {
                    created.close()
                    return@execute
                }
                detector = created
                isLoading = false
                modelError = null
                publish(force = true)
                Log.i(TAG, "COCO person detector ready; GPU delegate enabled=${created.isUsingGpu()}")
            } catch (error: Exception) {
                created.close()
                Log.e(TAG, "Unable to initialize AI Subject Tracking", error)
                isLoading = false
                modelError = error.localizedMessage ?: "AI model could not be loaded"
                publish(force = true)
            }
        }
    }

    fun updateSettings(next: SubjectTrackingSettings) {
        val previousResolution = settings.inputResolution
        settings = next.sanitized()
        if (!settings.humanEyeAssist) {
            synchronized(trackerLock) { tracker.clearHumanFaceRect() }
        }
        if (settings.inputResolution != previousResolution) {
            performance.resetFallbackForExplicitResolutionChange()
        }
        publish(force = true)
    }

    fun updateHumanFaceRect(rect: TrackingRect) {
        if (!enabled.get() || isClosed || !settings.humanEyeAssist) return
        val frameTimestampNs = lastCameraTimestampNs
        if (frameTimestampNs <= 0L) return
        synchronized(trackerLock) {
            tracker.updateHumanFaceRect(rect, frameTimestampNs)
        }
    }

    fun selectSubject(normalizedX: Float, normalizedY: Float) {
        if (!enabled.get()) return
        val x = normalizedX.coerceIn(0f, 1f)
        val y = normalizedY.coerceIn(0f, 1f)
        val lockedRect = currentSnapshot.rect
        val isAlreadyLocked = currentSnapshot.state == SubjectTrackingState.LOCKED ||
            currentSnapshot.state == SubjectTrackingState.OCCLUDED
        if (isAlreadyLocked && lockedRect != null &&
            x >= lockedRect.left - lockedRect.width * 0.10f &&
            x <= lockedRect.right + lockedRect.width * 0.10f &&
            y >= lockedRect.top - lockedRect.height * 0.10f &&
            y <= lockedRect.bottom + lockedRect.height * 0.10f
        ) {
            unlockSubject()
            return
        }
        synchronized(trackerLock) { tracker.requestTap(x, y) }
    }

    fun unlockSubject() {
        synchronized(trackerLock) { tracker.unlock() }
        cropController.reset()
        outputCrop = onCrop(TrackingCrop.WIDE)
        currentSnapshot = SubjectTrackingSnapshot()
        publish(force = true)
    }

    /** Called once per ImageReader frame; ownership of [image] transfers to this method. */
    fun onCameraImage(image: Image) {
        try {
            if (!enabled.get() || isClosed) return
            val timestampNs = image.timestamp
            if (timestampNs <= 0L) return
            lastCameraTimestampNs = timestampNs

            val grayFrame = GrayFrame.fromImage(image)
            currentSnapshot = synchronized(trackerLock) {
                tracker.onCameraFrame(timestampNs, grayFrame)
            }
            val frameStats = performance.onFrame(timestampNs)
            val activeSettings = settings

            if (performance.shouldFallbackTo720p(timestampNs, frameStats.fps, activeSettings)) {
                settings = activeSettings.copy(inputResolution = SubjectTrackingInputResolution.P720)
                onResolutionFallback(SubjectTrackingInputResolution.P720)
                Log.w(TAG, "Frame rate remained below 26 FPS; analysis stream downgraded to 720p")
                publish(force = true)
            }

            val activeDetector = detector
            if (activeDetector != null &&
                !isLoading &&
                timestampNs - lastInferenceTimestampNs >= DETECTOR_INTERVAL_NS &&
                inferenceBusy.compareAndSet(false, true)
            ) {
                val lease = bufferPool.acquire()
                if (lease != null) {
                    try {
                        val content = YuvRgbPreprocessor.copyToModelInput(image, lease.buffer)
                        val inferenceTimestampNs = timestampNs
                        val inferenceEpoch = trackingEpoch.get()
                        lastInferenceTimestampNs = timestampNs
                        inferenceExecutor.execute {
                            val startedNs = SystemClock.elapsedRealtimeNanos()
                            try {
                                val results = activeDetector.detect(
                                    rgbBuffer = lease.buffer,
                                    timestampNs = inferenceTimestampNs,
                                    contentLeft = content.left,
                                    contentTop = content.top,
                                    contentWidth = content.width,
                                    contentHeight = content.height,
                                )
                                if (enabled.get() && inferenceEpoch == trackingEpoch.get()) {
                                    synchronized(trackerLock) {
                                        tracker.submitDetections(inferenceTimestampNs, results)
                                    }
                                }
                            } catch (error: Exception) {
                                Log.w(TAG, "Person detector frame failed", error)
                            } finally {
                                performance.onDetectorLatency(
                                    (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L
                                )
                                lease.close()
                                inferenceBusy.set(false)
                            }
                        }
                    } catch (error: Exception) {
                        lease.close()
                        inferenceBusy.set(false)
                        Log.w(TAG, "Could not prepare detector frame", error)
                    }
                } else {
                    inferenceBusy.set(false)
                }
            }

            // Analysis frames already include the previous Camera2 hardware crop. Map the
            // tracked box back to the base analysis frame before calculating the next crop.
            // This closes the feedback loop and prevents zoom/pan oscillation.
            val cropInputSnapshot = mapSnapshotToBaseStream(currentSnapshot, outputCrop)
            val requestedCrop = cropController.step(cropInputSnapshot, timestampNs, activeSettings)
            outputCrop = onCrop(requestedCrop)
            if (lastPerformanceLogNs == 0L ||
                timestampNs - lastPerformanceLogNs >= PERFORMANCE_LOG_INTERVAL_NS
            ) {
                lastPerformanceLogNs = timestampNs
                val stats = performance.stats()
                Log.i(
                    TAG,
                    "AI tracking performance: avgFps=${"%.1f".format(stats.fps)}, " +
                        "avgDetectorLatencyMs=${"%.1f".format(stats.detectorLatencyMs)}",
                )
            }
            if (timestampNs - lastUiPublishTimestampNs >= UI_PUBLISH_INTERVAL_NS) {
                lastUiPublishTimestampNs = timestampNs
                publish(frameStats = performance.stats())
            }
        } catch (error: Exception) {
            Log.e(TAG, "AI tracking frame failed safely", error)
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
                // Camera switch/teardown may retire the reader while this callback is unwinding.
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (!enabled.get() || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        cropController.onGyroscopeSample(event.timestamp, event.values[0], event.values[1])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publish(
        force: Boolean = false,
        frameStats: TrackingPerformanceMonitor.Stats = performance.stats(),
    ) {
        synchronized(lock) {
            if (!force && !enabled.get()) return
            onUiState(
                SubjectTrackingUiState(
                    enabled = enabled.get(),
                    isLoadingModel = isLoading,
                    modelError = modelError,
                    settings = settings,
                    snapshot = currentSnapshot,
                    averageFps = frameStats.fps,
                    detectorLatencyMs = frameStats.detectorLatencyMs,
                )
            )
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        enabled.set(false)
        sensorManager?.unregisterListener(this)
        trackingEpoch.incrementAndGet()
        synchronized(trackerLock) { tracker.unlock() }
        cropController.reset()
        outputCrop = TrackingCrop.WIDE
        val previousDetector = detector
        detector = null
        inferenceExecutor.execute { previousDetector?.close() }
        inferenceExecutor.shutdown()
    }

    private fun mapSnapshotToBaseStream(
        snapshot: SubjectTrackingSnapshot,
        previousCrop: TrackingCrop,
    ): SubjectTrackingSnapshot {
        val rect = snapshot.rect ?: return snapshot
        val zoom = previousCrop.zoom.coerceIn(1f, 8f)
        val mappedRect = TrackingRect(
            centerX = previousCrop.centerX + (rect.centerX - 0.5f) / zoom,
            centerY = previousCrop.centerY + (rect.centerY - 0.5f) / zoom,
            width = rect.width / zoom,
            height = rect.height / zoom,
        ).clamped()
        return snapshot.copy(
            rect = mappedRect,
            velocityX = snapshot.velocityX / zoom,
            velocityY = snapshot.velocityY / zoom,
        )
    }

    private data class ContentRect(val left: Int, val top: Int, val width: Int, val height: Int)

    private object YuvRgbPreprocessor {
        private const val OUTPUT_SIZE = PersonObjectDetector.MODEL_INPUT_SIZE

        fun copyToModelInput(image: Image, output: ByteBuffer): ContentRect {
            val crop = image.cropRect
            val sourceWidth = crop.width().coerceAtLeast(1)
            val sourceHeight = crop.height().coerceAtLeast(1)
            val scale = min(OUTPUT_SIZE.toFloat() / sourceWidth, OUTPUT_SIZE.toFloat() / sourceHeight)
            val contentWidth = (sourceWidth * scale).roundToInt().coerceIn(1, OUTPUT_SIZE)
            val contentHeight = (sourceHeight * scale).roundToInt().coerceIn(1, OUTPUT_SIZE)
            val left = (OUTPUT_SIZE - contentWidth) / 2
            val top = (OUTPUT_SIZE - contentHeight) / 2
            output.clear()
            for (i in 0 until OUTPUT_SIZE * OUTPUT_SIZE * 3) output.put(i, 0.toByte())

            val planes = image.planes
            require(planes.size >= 3) { "Camera did not provide YUV_420_888 planes" }
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            for (outY in 0 until contentHeight) {
                val srcY = crop.top + (outY * sourceHeight / contentHeight)
                val yRow = srcY * yPlane.rowStride
                for (outX in 0 until contentWidth) {
                    val srcX = crop.left + (outX * sourceWidth / contentWidth)
                    val yValue = yBuffer.get(yRow + srcX * yPlane.pixelStride).toInt() and 0xff
                    val uIndex = (srcY / 2) * uPlane.rowStride + (srcX / 2) * uPlane.pixelStride
                    val vIndex = (srcY / 2) * vPlane.rowStride + (srcX / 2) * vPlane.pixelStride
                    val uValue = (uBuffer.get(uIndex).toInt() and 0xff) - 128
                    val vValue = (vBuffer.get(vIndex).toInt() and 0xff) - 128
                    val y = max(0, yValue - 16) * 1.164f
                    val red = (y + 1.596f * vValue).toInt().coerceIn(0, 255)
                    val green = (y - 0.392f * uValue - 0.813f * vValue).toInt().coerceIn(0, 255)
                    val blue = (y + 2.017f * uValue).toInt().coerceIn(0, 255)
                    val offset = ((top + outY) * OUTPUT_SIZE + left + outX) * 3
                    output.put(offset, red.toByte())
                    output.put(offset + 1, green.toByte())
                    output.put(offset + 2, blue.toByte())
                }
            }
            output.position(0)
            return ContentRect(left, top, contentWidth, contentHeight)
        }
    }

    companion object {
        private const val TAG = "SubjectTracking"
        private const val DETECTOR_INTERVAL_NS = 100_000_000L
        private const val UI_PUBLISH_INTERVAL_NS = 100_000_000L
        private const val PERFORMANCE_LOG_INTERVAL_NS = 60_000_000_000L
    }
}
