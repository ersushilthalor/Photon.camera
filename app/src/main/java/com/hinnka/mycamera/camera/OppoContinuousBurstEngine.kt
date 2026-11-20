package com.hinnka.mycamera.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import com.hinnka.mycamera.utils.PLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

data class OppoBurstJpegFrame(
    val frameIndex: Int,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val jpegBytes: ByteArray,
)

/**
 * OppoCam-style continuous burst pipeline:
 * repeating Camera2 YUV frames -> FPS gate -> pooled NV21 buffers -> bounded JPEG workers.
 *
 * The input Image is always closed on the camera callback thread immediately after copying.
 * JPEG encoding and downstream saving never block the camera thread.
 */
class OppoContinuousBurstEngine(
    private val onFrameReady: (OppoBurstJpegFrame) -> Unit,
) {
    companion object {
        private const val TAG = "OppoContinuousBurst"
        private const val MAX_OUTPUT_PIXELS = 3_000_000L
        private const val JPEG_QUALITY = 95
        private const val WORKER_COUNT = 2
        private const val QUEUE_CAPACITY = 6
        private const val MAX_BUFFERS_PER_SIZE = WORKER_COUNT + QUEUE_CAPACITY
        private const val MIN_FREE_MEMORY_BYTES = 64L * 1024L * 1024L
    }

    private class ByteArrayPool(
        private val byteCount: Int,
        private val capacity: Int,
    ) {
        private val available = ConcurrentLinkedQueue<ByteArray>()
        private val allocated = AtomicInteger(0)

        fun acquire(): ByteArray? {
            available.poll()?.let { return it }
            while (true) {
                val current = allocated.get()
                if (current >= capacity || !hasMemoryHeadroom()) return null
                if (allocated.compareAndSet(current, current + 1)) {
                    return try {
                        ByteArray(byteCount)
                    } catch (error: OutOfMemoryError) {
                        allocated.decrementAndGet()
                        PLog.e(TAG, "Unable to allocate burst frame buffer", error)
                        null
                    }
                }
            }
        }

        fun recycle(buffer: ByteArray) {
            if (buffer.size == byteCount) available.offer(buffer)
        }

        private fun hasMemoryHeadroom(): Boolean {
            val runtime = Runtime.getRuntime()
            val availableBytes = runtime.maxMemory() -
                (runtime.totalMemory() - runtime.freeMemory())
            return availableBytes > MIN_FREE_MEMORY_BYTES
        }
    }

    private data class OutputSize(val width: Int, val height: Int)

    private data class QueuedFrame(
        val index: Int,
        val timestampMs: Long,
        val width: Int,
        val height: Int,
        val buffer: ByteArray,
        val pool: ByteArrayPool,
    )

    private val active = AtomicBoolean(false)
    private val frameCounter = AtomicInteger(0)
    private val pendingTasks = AtomicInteger(0)
    private val lastAcceptedTimestampNs = AtomicLong(0L)
    private val completion = AtomicReference<(() -> Unit)?>(null)
    private val completionDelivered = AtomicBoolean(false)
    private val currentPool = AtomicReference<ByteArrayPool?>(null)
    private val currentPoolSize = AtomicInteger(0)

    @Volatile
    private var targetFps = 15

    @Volatile
    private var released = false

    private val workers = ThreadPoolExecutor(
        WORKER_COUNT,
        WORKER_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
        ThreadFactory { task ->
            Thread(task, "OppoBurstJpegWorker").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun start(fps: Int, onComplete: () -> Unit): Boolean {
        if (released || !active.compareAndSet(false, true)) return false
        targetFps = fps.coerceIn(5, 20)
        frameCounter.set(0)
        pendingTasks.set(0)
        lastAcceptedTimestampNs.set(0L)
        completionDelivered.set(false)
        completion.set(onComplete)
        PLog.i(TAG, "Continuous burst started: targetFps=$targetFps")
        return true
    }

    fun isActive(): Boolean = active.get()

    /**
     * Called only by the ImageReader's camera-handler listener.
     * Acquires the newest YUV frame, samples it down to at most 3MP, copies to NV21,
     * and closes the camera-owned Image before submitting the worker task.
     */
    fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (error: Exception) {
            PLog.w(TAG, "Unable to acquire burst frame: ${error.message}")
            null
        } ?: return

        if (!active.get()) {
            image.close()
            return
        }

        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) {
            image.close()
            return
        }

        val timestampNs = image.timestamp
        val minIntervalNs = 1_000_000_000L / targetFps
        val previousTimestamp = lastAcceptedTimestampNs.get()
        if (previousTimestamp > 0L &&
            timestampNs > previousTimestamp &&
            timestampNs - previousTimestamp < (minIntervalNs * 0.90).toLong()
        ) {
            image.close()
            return
        }

        val outputSize = resolveOutputSize(image.width, image.height)
        val bufferSize = outputSize.width * outputSize.height * 3 / 2
        val pool = obtainPool(bufferSize)
        val buffer = pool?.acquire()
        if (pool == null || buffer == null) {
            image.close()
            PLog.w(TAG, "Burst frame skipped: bounded buffer pool exhausted")
            return
        }

        val copied = try {
            copyToNv21(image, buffer, outputSize)
        } catch (error: Exception) {
            PLog.e(TAG, "YUV to NV21 copy failed", error)
            false
        } finally {
            image.close()
        }

        if (!copied || !active.get()) {
            pool.recycle(buffer)
            return
        }

        lastAcceptedTimestampNs.set(timestampNs)
        val frameIndex = frameCounter.get() + 1
        val frame = QueuedFrame(
            index = frameIndex,
            timestampMs = System.currentTimeMillis(),
            width = outputSize.width,
            height = outputSize.height,
            buffer = buffer,
            pool = pool,
        )

        pendingTasks.incrementAndGet()
        try {
            workers.execute {
                try {
                    val jpegBytes = encodeJpeg(frame)
                    if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                        onFrameReady(
                            OppoBurstJpegFrame(
                                frameIndex = frame.index,
                                timestampMs = frame.timestampMs,
                                width = frame.width,
                                height = frame.height,
                                jpegBytes = jpegBytes,
                            ),
                        )
                    } else {
                        PLog.w(TAG, "Burst JPEG encoder returned an empty frame: index=${frame.index}")
                    }
                } catch (error: Exception) {
                    PLog.e(TAG, "Burst JPEG worker failed: index=${frame.index}", error)
                } finally {
                    frame.pool.recycle(frame.buffer)
                    pendingTasks.decrementAndGet()
                    completeWhenDrained()
                }
            }
            frameCounter.incrementAndGet()
        } catch (error: RejectedExecutionException) {
            pendingTasks.decrementAndGet()
            pool.recycle(buffer)
            PLog.w(TAG, "Burst worker queue full; dropping frame")
            completeWhenDrained()
        }
    }

    /**
     * Stops accepting frames. Already accepted work is drained before onComplete is invoked.
     */
    fun stop() {
        if (!active.getAndSet(false)) {
            completeWhenDrained()
            return
        }
        PLog.i(TAG, "Burst stopped: acceptedFrames=${frameCounter.get()}, pending=${pendingTasks.get()}")
        completeWhenDrained()
    }

    fun release() {
        released = true
        stop()
        workers.shutdown()
    }

    private fun completeWhenDrained() {
        if (active.get() || pendingTasks.get() != 0) return
        if (!completionDelivered.compareAndSet(false, true)) return
        val callback = completion.getAndSet(null)
        try {
            callback?.invoke()
        } catch (error: Exception) {
            PLog.e(TAG, "Burst completion callback failed", error)
        }
    }

    private fun obtainPool(byteCount: Int): ByteArrayPool? {
        val existing = currentPool.get()
        if (existing != null && currentPoolSize.get() == byteCount) return existing
        synchronized(currentPool) {
            val latest = currentPool.get()
            if (latest != null && currentPoolSize.get() == byteCount) return latest
            val created = ByteArrayPool(byteCount, MAX_BUFFERS_PER_SIZE)
            currentPoolSize.set(byteCount)
            currentPool.set(created)
            return created
        }
    }

    private fun resolveOutputSize(sourceWidth: Int, sourceHeight: Int): OutputSize {
        val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
        val scale = if (sourcePixels <= MAX_OUTPUT_PIXELS) {
            1.0
        } else {
            sqrt(sourcePixels.toDouble() / MAX_OUTPUT_PIXELS.toDouble())
        }
        var outputWidth = ((sourceWidth / scale).toInt() / 2 * 2).coerceAtLeast(2)
        var outputHeight = ((sourceHeight / scale).toInt() / 2 * 2).coerceAtLeast(2)
        while (outputWidth.toLong() * outputHeight.toLong() > MAX_OUTPUT_PIXELS) {
            if (outputWidth >= outputHeight) outputWidth -= 2 else outputHeight -= 2
        }
        return OutputSize(outputWidth, outputHeight)
    }

    private fun copyToNv21(image: Image, output: ByteArray, size: OutputSize): Boolean {
        val planes = image.planes
        if (planes.size < 3) return false
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        var destination = 0
        for (row in 0 until size.height) {
            val sourceRow = row * image.height / size.height
            val rowBase = sourceRow * yRowStride
            for (column in 0 until size.width) {
                val sourceColumn = column * image.width / size.width
                val sourceIndex = rowBase + sourceColumn * yPixelStride
                if (sourceIndex !in 0 until yBuffer.limit()) return false
                output[destination++] = yBuffer.get(sourceIndex)
            }
        }

        val chromaOffset = size.width * size.height
        destination = chromaOffset
        val outputChromaHeight = size.height / 2
        val outputChromaWidth = size.width / 2
        val sourceChromaHeight = image.height / 2
        val sourceChromaWidth = image.width / 2
        for (row in 0 until outputChromaHeight) {
            val sourceRow = row * sourceChromaHeight / outputChromaHeight
            for (column in 0 until outputChromaWidth) {
                val sourceColumn = column * sourceChromaWidth / outputChromaWidth
                val vIndex = sourceRow * vRowStride + sourceColumn * vPixelStride
                val uIndex = sourceRow * uRowStride + sourceColumn * uPixelStride
                if (vIndex !in 0 until vBuffer.limit() || uIndex !in 0 until uBuffer.limit()) return false
                output[destination++] = vBuffer.get(vIndex)
                output[destination++] = uBuffer.get(uIndex)
            }
        }
        return true
    }

    private fun encodeJpeg(frame: QueuedFrame): ByteArray? {
        val yuv = YuvImage(frame.buffer, ImageFormat.NV21, frame.width, frame.height, null)
        val stream = ByteArrayOutputStream(maxOf(64 * 1024, frame.width * frame.height / 3))
        val success = yuv.compressToJpeg(
            Rect(0, 0, frame.width, frame.height),
            JPEG_QUALITY,
            stream,
        )
        return if (success) stream.toByteArray() else null
    }
}
