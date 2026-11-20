package com.hinnka.mycamera.processor

import com.hinnka.mycamera.model.SafeImage
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

data class CaptureProcessingState(
    val pendingCount: Int = 0,
    val completedCount: Long = 0,
)

/** Owns each shot until its entire merge, render and save operation has finished. */
object CaptureProcessingQueue {
    private class Request(val images: List<SafeImage>, val process: suspend () -> Unit)

    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CaptureProcessingQueue")
    }
    private val workerDispatcher = workerExecutor.asCoroutineDispatcher()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        PLog.e("CaptureProcessingQueue", "Uncaught error in capture processing worker", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher + exceptionHandler)
    // Memory is bounded at camera admission by the 50 reserved/acquired ImageReader slots.
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(CaptureProcessingState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            for (request in requests) {
                try {
                    request.process()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    PLog.e("CaptureProcessingQueue", "Capture processing failed", e)
                } finally {
                    request.images.forEach { image ->
                        runCatching { image.close() }.onFailure {
                            PLog.w("CaptureProcessingQueue", "Failed to close captured image", it)
                        }
                    }
                    mutableState.update {
                        it.copy(
                            pendingCount = (it.pendingCount - 1).coerceAtLeast(0),
                            completedCount = it.completedCount + 1,
                        )
                    }
                }
            }
        }
    }

    fun enqueue(images: List<SafeImage>, process: suspend () -> Unit) {
        mutableState.update { it.copy(pendingCount = it.pendingCount + 1) }
        val result = requests.trySend(Request(images, process))
        if (!result.isSuccess) {
            images.forEach { runCatching { it.close() } }
            mutableState.update {
                it.copy(pendingCount = (it.pendingCount - 1).coerceAtLeast(0))
            }
            check(result.isSuccess) { "Failed to enqueue capture processing request" }
        }
    }
}
