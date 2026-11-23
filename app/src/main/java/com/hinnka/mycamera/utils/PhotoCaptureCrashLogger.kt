package com.hinnka.mycamera.utils

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.util.Log
import com.hinnka.mycamera.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the active photo-capture trace so a fatal app/process exit does not erase
 * the diagnostics needed to investigate a shutter-time crash.
 */
object PhotoCaptureCrashLogger {
    private const val TAG = "PhotoCaptureCrash"
    private const val ACTIVE_FILE = "photo_capture_active.txt"
    private const val TRACE_FILE = "photo_capture_trace.txt"
    private const val REPORT_FILE = "photo_capture_crash_report.txt"
    private const val MAX_TRACE_BYTES = 512 * 1024
    private const val MAX_REPORT_BYTES = 600 * 1024

    private val lock = Any()
    private val activeCaptureIds = linkedSetOf<String>()
    private val _pendingCrashReport = MutableStateFlow<String?>(null)
    val pendingCrashReport: StateFlow<String?> = _pendingCrashReport.asStateFlow()

    @Volatile private var app: Application? = null
    private var installedHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Install before camera startup and call again after third-party crash SDK initialization
     * so this capture-aware handler remains the outermost uncaught-exception handler.
     */
    fun install(application: Application) {
        synchronized(lock) {
            if (app == null) {
                app = application
                recoverInterruptedCaptureLocked()
                if (_pendingCrashReport.value == null) {
                    _pendingCrashReport.value = reportFile()?.takeIf { it.isFile }
                        ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
                        ?.takeIf { it.isNotBlank() }
                }
            }
            installUncaughtExceptionHandlerLocked()
        }
    }

    private fun installUncaughtExceptionHandlerLocked() {
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (installedHandler != null && currentHandler === installedHandler) return

        val captureAwareHandler = Thread.UncaughtExceptionHandler { thread, error ->
            val hasActiveCapture = synchronized(lock) {
                activeCaptureIds.isNotEmpty() || activeMarkerFile()?.exists() == true
            }
            if (hasActiveCapture) {
                runCatching {
                    synchronized(lock) {
                        writeCrashReportLocked(
                            cause = "Uncaught ${error.javaClass.name}: ${error.message}",
                            threadName = thread.name,
                            throwable = error,
                            exitDetails = null,
                        )
                    }
                }.onFailure { loggingError ->
                    Log.e(TAG, "Could not persist photo crash report", loggingError)
                }
            }
            currentHandler?.uncaughtException(thread, error)
        }
        Thread.setDefaultUncaughtExceptionHandler(captureAwareHandler)
        installedHandler = captureAwareHandler
    }

    /** Begin only after Camera2 accepts and reserves the photo request. */
    fun beginPhotoCapture(captureId: String, details: Map<String, String>) {
        synchronized(lock) {
            if (app == null) return
            if (activeCaptureIds.isEmpty()) {
                traceFile()?.delete()
                reportFile()?.delete()
                _pendingCrashReport.value = null
            }
            activeCaptureIds.add(captureId)
            persistActiveMarkerLocked()
            appendTraceLocked(
                buildString {
                    appendLine("========== PHOTO CAPTURE BEGIN ==========")
                    appendLine("time=${now()}")
                    appendLine("photoId=$captureId")
                    appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    details.forEach { (key, value) -> appendLine("$key=$value") }
                }
            )
        }
    }

    fun recordEvent(event: String, details: String = "") {
        synchronized(lock) {
            if (activeCaptureIds.isEmpty()) return
            appendTraceLocked("${now()} I/PHOTO_CAPTURE [$event] ${details.take(12_000)}")
        }
    }

    /** Called by PLog; it is deliberately a no-op outside an active photo capture. */
    fun recordLog(
        timestamp: Long,
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        synchronized(lock) {
            if (activeCaptureIds.isEmpty()) return
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            val stack = throwable?.let { "\n${Log.getStackTraceString(it).take(24_000)}" }.orEmpty()
            appendTraceLocked("$time $level/$tag: ${message.take(8_000)}$stack")
        }
    }

    fun finishPhotoCapture(captureId: String, success: Boolean, failureReason: String? = null) {
        synchronized(lock) {
            if (!activeCaptureIds.contains(captureId)) return
            appendTraceLocked(
                "${now()} I/PHOTO_CAPTURE [FINISH] photoId=$captureId success=$success" +
                    (failureReason?.let { " reason=${it.take(4_000)}" } ?: "")
            )
            if (!success) {
                writeCrashReportLocked(
                    cause = failureReason ?: "Photo capture/processing failed",
                    threadName = Thread.currentThread().name,
                    throwable = null,
                    exitDetails = null,
                )
            }
            activeCaptureIds.remove(captureId)
            persistActiveMarkerLocked()
        }
    }

    fun dismissPendingReport() {
        synchronized(lock) {
            reportFile()?.delete()
            _pendingCrashReport.value = null
        }
    }

    private fun recoverInterruptedCaptureLocked() {
        val marker = activeMarkerFile() ?: return
        if (!marker.isFile) return
        val ids = runCatching { marker.readLines(Charsets.UTF_8).filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        if (ids.isEmpty()) {
            marker.delete()
            return
        }

        val existingReport = reportFile()?.takeIf { it.isFile }
            ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
        if (!existingReport.isNullOrBlank()) {
            _pendingCrashReport.value = existingReport
        } else {
            writeCrashReportLocked(
                cause = "App process ended before the photo capture finished. The previous session was interrupted.",
                threadName = "previous process",
                throwable = null,
                exitDetails = latestProcessExitDetails(),
                captureIds = ids,
            )
        }
        activeCaptureIds.clear()
        marker.delete()
    }

    private fun latestProcessExitDetails(): String? {
        val context = app ?: return null
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return@runCatching null
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                ?: return@runCatching "No historical process-exit record was returned."
            buildString {
                appendLine("reason=${exit.reason}")
                appendLine("status=${exit.status}")
                appendLine("importance=${exit.importance}")
                appendLine("timestamp=${Date(exit.timestamp)}")
                appendLine("description=${exit.description ?: "unavailable"}")
            }.trim()
        }.getOrElse {
            "Unable to read process-exit details: ${it.javaClass.simpleName}: ${it.message}"
        }
    }

    private fun writeCrashReportLocked(
        cause: String,
        threadName: String,
        throwable: Throwable?,
        exitDetails: String?,
        captureIds: List<String> = activeCaptureIds.toList(),
    ) {
        if (app == null) return
        val trace = traceFile()?.let {
            runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("Unable to read photo trace.")
        }.orEmpty()
        val report = buildString {
            appendLine("========== PHOTON PHOTO CAPTURE CRASH REPORT ==========")
            appendLine("time=${now()}")
            appendLine("cause=$cause")
            appendLine("captureIds=${captureIds.joinToString(",").ifBlank { "unknown" }}")
            appendLine("thread=$threadName")
            appendLine("app=${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} buildType=${BuildConfig.BUILD_TYPE}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} hardware=${Build.HARDWARE}")
            appendLine("android=${Build.VERSION.RELEASE} API=${Build.VERSION.SDK_INT}")
            if (!exitDetails.isNullOrBlank()) {
                appendLine()
                appendLine("--- Android process exit details ---")
                appendLine(exitDetails)
            }
            if (throwable != null) {
                appendLine()
                appendLine("--- Fatal exception stack trace ---")
                appendLine(Log.getStackTraceString(throwable))
            }
            appendLine()
            appendLine("--- Persisted photo capture log ---")
            appendLine(trace.ifBlank { "No photo capture events were written before the process exited." })
            appendLine()
            appendLine("========== END REPORT ==========")
        }.take(MAX_REPORT_BYTES)
        val destination = reportFile() ?: return
        try {
            writeDurableText(destination, report)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to persist photo capture crash report", error)
            return
        }
        _pendingCrashReport.value = report
        Log.e(TAG, "Photo capture failure report saved to ${destination.absolutePath}: $cause")
    }

    private fun persistActiveMarkerLocked() {
        val marker = activeMarkerFile() ?: return
        runCatching {
            if (activeCaptureIds.isEmpty()) marker.delete()
            else writeDurableText(marker, activeCaptureIds.joinToString("\n"))
        }.onFailure { Log.w(TAG, "Unable to persist active photo marker", it) }
    }

    private fun appendTraceLocked(text: String) {
        val file = traceFile() ?: return
        runCatching {
            FileOutputStream(file, true).use { output ->
                output.write((text.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
            if (file.length() > MAX_TRACE_BYTES) {
                val bytes = file.readBytes()
                val start = (bytes.size - MAX_TRACE_BYTES).coerceAtLeast(0)
                val nextLine = bytes.indexOf('\n'.code.toByte(), start)
                val tail = if (nextLine >= 0) bytes.copyOfRange(nextLine + 1, bytes.size)
                    else bytes.copyOfRange(start, bytes.size)
                file.writeBytes(String(tail, Charsets.UTF_8))
            }
        }.onFailure { Log.w(TAG, "Unable to append photo capture trace", it) }
    }

    private fun writeDurableText(file: File, text: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun activeMarkerFile() = app?.filesDir?.resolve(ACTIVE_FILE)
    private fun traceFile() = app?.filesDir?.resolve(TRACE_FILE)
    private fun reportFile() = app?.filesDir?.resolve(REPORT_FILE)
    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
