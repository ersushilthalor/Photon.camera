package com.hinnka.mycamera.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LargeDirectBuffer {
    private const val TAG = "LargeDirectBuffer"

    fun allocate(byteCount: Long, label: String): ByteBuffer? {
        if (byteCount <= 0L || byteCount > Int.MAX_VALUE) {
            PLog.e(TAG, "$label buffer size is invalid: $byteCount")
            return null
        }
        return try {
            if (NativeLibraryLoader.ensureLoaded()) {
                DirectBufferAllocator.allocateNative(byteCount)?.order(ByteOrder.nativeOrder()).also {
                    if (it == null) {
                        PLog.e(TAG, "Failed to allocate $label buffer: $byteCount bytes")
                    } else {
                        PLog.d(TAG, "Allocated $label buffer with native allocator: $byteCount bytes")
                    }
                }
            } else {
                ByteBuffer.allocateDirect(byteCount.toInt()).order(ByteOrder.nativeOrder()).also {
                    PLog.w(TAG, "Native allocator unavailable; allocated JVM direct buffer for $label ($byteCount bytes)")
                }
            }
        } catch (e: OutOfMemoryError) {
            PLog.e(TAG, "OOM allocating $label buffer: $byteCount bytes", e)
            null
        } catch (e: Throwable) {
            PLog.e(TAG, "Failed to allocate $label buffer: $byteCount bytes", e)
            null
        }
    }

    fun free(buffer: ByteBuffer?) {
        if (buffer == null || !NativeLibraryLoader.isAvailable) return
        try {
            DirectBufferAllocator.freeNative(buffer)
        } catch (e: Throwable) {
            PLog.e(TAG, "Failed to free native direct buffer", e)
        }
    }
}
