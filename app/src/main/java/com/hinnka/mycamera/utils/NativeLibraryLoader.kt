package com.hinnka.mycamera.utils

/**
 * Thread-safe, crash-proof loader for the optional native library `libmy-native-lib.so`.
 * Devices or builds where the native library is unavailable for an ABI will cleanly fall back
 * instead of crashing with [UnsatisfiedLinkError] or [ExceptionInInitializerError].
 */
object NativeLibraryLoader {
    private const val TAG = "NativeLibraryLoader"
    private const val LIB_NAME = "my-native-lib"

    private val lock = Any()

    @Volatile
    private var attempted = false

    @Volatile
    private var loaded = false

    val isAvailable: Boolean
        get() = ensureLoaded()

    fun ensureLoaded(): Boolean {
        if (attempted) return loaded
        synchronized(lock) {
            if (attempted) return loaded
            loaded = try {
                System.loadLibrary(LIB_NAME)
                true
            } catch (error: Throwable) {
                PLog.w(TAG, "Native library $LIB_NAME unavailable on this device/build: ${error.message}")
                false
            } finally {
                attempted = true
            }
            return loaded
        }
    }
}
