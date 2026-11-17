package com.hinnka.mycamera.tracking

import android.content.Context
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** COCO EfficientDet-Lite0 wrapper. It trusts only the model's real "person" class. */
internal class PersonObjectDetector(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var detector: ObjectDetector? = null
    private var usingGpu = false

    fun initialize() {
        val model = DetectorModelCache.obtain(appContext)
        detector = try {
            createDetector(model, Delegate.GPU).also { usingGpu = true }
        } catch (gpuError: Exception) {
            Log.w(TAG, "GPU delegate unavailable; using CPU", gpuError)
            usingGpu = false
            createDetector(model, Delegate.CPU)
        }
    }

    fun detect(
        rgbBuffer: ByteBuffer,
        timestampNs: Long,
        contentLeft: Int,
        contentTop: Int,
        contentWidth: Int,
        contentHeight: Int,
    ): List<TrackingDetection> {
        val activeDetector = detector ?: return emptyList()
        val imageBuffer = rgbBuffer.duplicate().apply { position(0) }
        val mpImage = ByteBufferImageBuilder(
            imageBuffer, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, MPImage.IMAGE_FORMAT_RGB
        ).build()
        return try {
            activeDetector.detect(mpImage).detections().mapNotNull { detection ->
                // This name is emitted by the COCO class head; no heuristic human inference.
                val person = detection.categories().firstOrNull {
                    it.categoryName().equals(COCO_PERSON_LABEL, ignoreCase = true)
                } ?: return@mapNotNull null
                val bounds: RectF = detection.boundingBox()
                val left = (bounds.left - contentLeft) / contentWidth.toFloat()
                val right = (bounds.right - contentLeft) / contentWidth.toFloat()
                val top = (bounds.top - contentTop) / contentHeight.toFloat()
                val bottom = (bounds.bottom - contentTop) / contentHeight.toFloat()
                if (right <= 0f || bottom <= 0f || left >= 1f || top >= 1f) {
                    return@mapNotNull null
                }
                val rect = TrackingRect.fromLTRB(
                    left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
                    right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)
                )
                val appearance = HsvAppearanceModel.fromRgb(
                    rgb = rgbBuffer,
                    imageWidth = MODEL_INPUT_SIZE,
                    imageHeight = MODEL_INPUT_SIZE,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentWidth = contentWidth,
                    contentHeight = contentHeight,
                    rect = rect,
                )
                TrackingDetection(rect, person.score().coerceIn(0f, 1f), appearance, timestampNs)
            }
        } finally {
            mpImage.close()
        }
    }

    fun isUsingGpu(): Boolean = usingGpu

    private fun createDetector(model: File, delegate: Delegate): ObjectDetector {
        val base = BaseOptions.builder()
            .setModelAssetPath(model.absolutePath)
            .setDelegate(delegate)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.35f)
            .setMaxResults(20)
            .build()
        return ObjectDetector.createFromOptions(appContext, options)
    }

    override fun close() {
        try {
            detector?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Detector close failed", error)
        } finally {
            detector = null
        }
    }

    private object DetectorModelCache {
        private const val FILE_NAME = "efficientdet_lite0_coco_person.tflite"
        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite"

        @Synchronized
        fun obtain(context: Context): File {
            val model = File(context.filesDir, FILE_NAME)
            if (model.isFile && model.length() > MINIMUM_BYTES) return model
            val temporary = File(context.filesDir, "$FILE_NAME.download")
            if (temporary.exists()) temporary.delete()
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "PhotonCamera-AISubjectTracking")
                connection.connect()
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "Model download returned HTTP ${connection.responseCode}"
                }
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val bytes = input.copyTo(output)
                        output.fd.sync()
                        check(bytes > MINIMUM_BYTES) { "Downloaded model is incomplete" }
                    }
                }
                if (model.exists()) model.delete()
                check(temporary.renameTo(model)) { "Could not install model file" }
                return model
            } finally {
                connection.disconnect()
                if (temporary.exists()) temporary.delete()
            }
        }

        private const val MINIMUM_BYTES = 1_000_000L
    }

    companion object {
        const val MODEL_INPUT_SIZE = 320
        private const val COCO_PERSON_LABEL = "person"
        private const val TAG = "PersonObjectDetector"
    }
}

/** 3+ direct buffers. A lease remains exclusive until inference completes. */
internal class RgbInputBufferPool(
    bufferCount: Int = 3,
    sizeBytes: Int = PersonObjectDetector.MODEL_INPUT_SIZE *
        PersonObjectDetector.MODEL_INPUT_SIZE * 3,
) {
    class Lease internal constructor(
        val buffer: ByteBuffer,
        private val releaseAction: () -> Unit,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) releaseAction()
        }
    }

    private class Slot(val buffer: ByteBuffer) {
        val inUse = AtomicBoolean(false)
    }

    private val slots = List(bufferCount.coerceAtLeast(3)) { Slot(ByteBuffer.allocateDirect(sizeBytes)) }

    fun acquire(): Lease? {
        for (slot in slots) {
            if (slot.inUse.compareAndSet(false, true)) {
                slot.buffer.clear()
                return Lease(slot.buffer) {
                    slot.buffer.clear()
                    slot.inUse.set(false)
                }
            }
        }
        return null
    }
}
