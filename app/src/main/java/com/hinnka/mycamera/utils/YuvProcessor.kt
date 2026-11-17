package com.hinnka.mycamera.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import androidx.core.graphics.createBitmap
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.model.SafeImage
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * YUV 图像处理器
 * 
 * 使用 libyuv 原生库进行 YUV 图像的旋转、裁切和转换；当原生库不可用时回退到 Android 平台解码。
 */
object YuvProcessor {

    private const val TAG = "YuvProcessor"

    init {
        NativeLibraryLoader.ensureLoaded()
    }

    /**
     * 处理 YUV Image 并返回用于预览的 8-bit Bitmap
     */
    fun processAndToBitmap(image: Image, aspectRatio: AspectRatio?, rotation: Int): Bitmap {
        val planes = image.planes

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val width = image.width
        val height = image.height
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        val format = image.format

        val dimensions = BitmapUtils.calculateProcessedRect(width, height, aspectRatio, null, rotation)
        val previewBitmap = createBitmap(dimensions.width(), dimensions.height())

        val tw = aspectRatio?.widthRatio ?: width
        val th = aspectRatio?.heightRatio ?: height

        if (NativeLibraryLoader.ensureLoaded()) {
            try {
                processToBitmap(
                    yBuffer, uBuffer, vBuffer,
                    width, height,
                    yRowStride, uvRowStride, uvPixelStride,
                    rotation, tw, th, format,
                    previewBitmap
                )
                return previewBitmap
            } catch (error: UnsatisfiedLinkError) {
                PLog.w(TAG, "Native processToBitmap unavailable, using Kotlin YUV fallback: ${error.message}")
            }
        }
        previewBitmap.recycle()
        return processToBitmapFallback(
            yBuffer = yBuffer,
            uBuffer = uBuffer,
            vBuffer = vBuffer,
            width = width,
            height = height,
            yRowStride = yRowStride,
            uvRowStride = uvRowStride,
            uvPixelStride = uvPixelStride,
            rotation = rotation,
            targetWidth = dimensions.width(),
            targetHeight = dimensions.height(),
        )
    }

    fun processAndSave(
        image: SafeImage,
        rotation: Int,
        outputPath: String,
    ): Boolean {
        val planes = image.planes

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val width = image.width
        val height = image.height
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        val format = image.format

        if (NativeLibraryLoader.ensureLoaded()) {
            try {
                return processToFile(
                    yBuffer, uBuffer, vBuffer,
                    width, height,
                    yRowStride, uvRowStride, uvPixelStride,
                    rotation, format,
                    outputPath
                )
            } catch (error: UnsatisfiedLinkError) {
                PLog.w(TAG, "Native processToFile unavailable, using Kotlin JPEG fallback: ${error.message}")
            }
        }

        return runCatching {
            val bitmap = processAndToBitmap(image.image, null, rotation)
            try {
                FileOutputStream(outputPath).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
            } finally {
                bitmap.recycle()
            }
        }.onFailure {
            PLog.e(TAG, "Fallback processAndSave failed for $outputPath", it)
        }.getOrDefault(false)
    }

    private fun processToBitmapFallback(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        rotation: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap {
        val safeTargetW = targetWidth.coerceAtLeast(1)
        val safeTargetH = targetHeight.coerceAtLeast(1)
        if (width <= 0 || height <= 0) {
            return createBitmap(safeTargetW, safeTargetH)
        }
        return runCatching {
            val nv21 = ByteArray(width * height + 2 * (width / 2) * (height / 2))
            val yDup = yBuffer.duplicate()
            for (row in 0 until height) {
                val srcOffset = row * yRowStride
                val copyLen = minOf(width, (yDup.capacity() - srcOffset).coerceAtLeast(0))
                if (copyLen > 0) {
                    yDup.position(srcOffset)
                    yDup.get(nv21, row * width, copyLen)
                }
            }
            val uDup = uBuffer.duplicate()
            val vDup = vBuffer.duplicate()
            var outIndex = width * height
            val uvHeight = height / 2
            val uvWidth = width / 2
            for (row in 0 until uvHeight) {
                val rowStart = row * uvRowStride
                for (col in 0 until uvWidth) {
                    val offset = rowStart + col * uvPixelStride
                    if (outIndex + 1 < nv21.size && offset < vDup.capacity() && offset < uDup.capacity()) {
                        nv21[outIndex++] = vDup.get(offset)
                        nv21[outIndex++] = uDup.get(offset)
                    }
                }
            }
            val jpegBytes = ByteArrayOutputStream().use { out ->
                YuvImage(nv21, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(Rect(0, 0, width, height), 92, out)
                out.toByteArray()
            }
            val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return@runCatching createBitmap(safeTargetW, safeTargetH)
            val rotated = if (rotation % 360 != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
            val targetAspect = safeTargetW.toFloat() / safeTargetH.toFloat()
            val srcAspect = rotated.width.toFloat() / rotated.height.coerceAtLeast(1).toFloat()
            val cropped = if (kotlin.math.abs(srcAspect - targetAspect) > 0.01f) {
                val cropW: Int
                val cropH: Int
                if (srcAspect > targetAspect) {
                    cropH = rotated.height
                    cropW = (cropH * targetAspect).roundToInt().coerceIn(1, rotated.width)
                } else {
                    cropW = rotated.width
                    cropH = (cropW / targetAspect).roundToInt().coerceIn(1, rotated.height)
                }
                val x = ((rotated.width - cropW) / 2).coerceAtLeast(0)
                val y = ((rotated.height - cropH) / 2).coerceAtLeast(0)
                Bitmap.createBitmap(rotated, x, y, cropW, cropH).also {
                    if (it !== rotated) rotated.recycle()
                }
            } else {
                rotated
            }
            if (cropped.width == safeTargetW && cropped.height == safeTargetH) {
                cropped
            } else {
                Bitmap.createScaledBitmap(cropped, safeTargetW, safeTargetH, true).also {
                    if (it !== cropped) cropped.recycle()
                }
            }
        }.onFailure {
            PLog.e(TAG, "Fallback YUV decode failed", it)
        }.getOrElse {
            createBitmap(safeTargetW, safeTargetH)
        }
    }

    /**
     * Native 预览处理方法
     */
    private external fun processToBitmap(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        rotation: Int,
        targetWR: Int,
        targetHR: Int,
        format: Int,
        previewBitmap: Bitmap
    )

    private external fun processToFile(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        rotation: Int,
        format: Int,
        outputPath: String
    ): Boolean

}
