package com.hinnka.mycamera.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.hinnka.mycamera.data.CaptureSoundRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Preloads each sound independently for low-latency capture, including looping burst audio. */
class ShutterSoundPlayer(private val context: Context) {
    companion object {
        private const val TAG = "ShutterSoundPlayer"

        private fun createPool(): SoundPool? = runCatching {
            SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
        }.onFailure { error ->
            PLog.w(TAG, "SoundPool unavailable", error)
        }.getOrNull()

        /** Verify that the device can actually decode the imported file before saving it. */
        suspend fun validate(file: File): Boolean {
            val pool = createPool() ?: return false
            try {
                val loaded = CompletableDeferred<Boolean>()
                pool.setOnLoadCompleteListener { _, _, status -> loaded.complete(status == 0) }
                if (pool.load(file.absolutePath, 1) == 0) return false
                return withTimeoutOrNull(10_000) { loaded.await() } == true
            } catch (e: Throwable) {
                PLog.w(TAG, "Sound validation failed", e)
                return false
            } finally {
                runCatching { pool.release() }
            }
        }
    }

    private class Sample(val fileName: String?, val id: Int) {
        val loaded = CompletableDeferred<Boolean>()
        var ready = false
    }

    private var single: Sample? = null
    private var burst: Sample? = null
    private var streamId = 0
    private var pool: SoundPool? = createPool()?.apply {
        runCatching {
            setOnLoadCompleteListener { _, sampleId, status ->
                synchronized(this@ShutterSoundPlayer) {
                    val sample = listOfNotNull(single, burst).firstOrNull { it.id == sampleId }
                        ?: return@setOnLoadCompleteListener
                    sample.ready = status == 0
                    sample.loaded.complete(sample.ready)
                    if (!sample.ready) PLog.e(TAG, "Sound load failed: id=$sampleId, status=$status")
                }
            }
        }
    }

    @Synchronized
    fun configure(singleFileName: String?, burstFileName: String?) {
        val activePool = pool ?: return
        if (single == null || single?.fileName != singleFileName) {
            single = replace(activePool, single, singleFileName, "shutter.mp3")
        }
        if (burst == null || burst?.fileName != burstFileName) {
            stopBurst()
            burst = replace(activePool, burst, burstFileName, "burst.mp3")
        }
    }

    private fun replace(activePool: SoundPool, previous: Sample?, fileName: String?, asset: String): Sample {
        previous?.let {
            it.loaded.complete(false)
            runCatching { activePool.unload(it.id) }
        }
        val id = try {
            val file = CaptureSoundRepository.resolve(context, fileName)
            if (file == null) {
                context.assets.openFd(asset).use { activePool.load(it, 1) }
            } else {
                activePool.load(file.absolutePath, 1)
            }
        } catch (e: Throwable) {
            PLog.e(TAG, "Cannot load sound: ${fileName ?: asset}", e)
            0
        }
        return Sample(fileName, id).also { if (id == 0) it.loaded.complete(false) }
    }

    @Synchronized
    fun play() {
        playSample(single, loop = false)
    }

    @Synchronized
    fun playBurst() {
        stopBurst()
        playSample(burst, loop = true)
    }

    private fun playSample(sample: Sample?, loop: Boolean): Boolean {
        val activePool = pool ?: return false
        if (sample?.ready != true) return false
        return runCatching {
            streamId = activePool.play(sample.id, 1f, 1f, 1, if (loop) -1 else 0, 1f)
            streamId != 0
        }.onFailure { error ->
            PLog.w(TAG, "Failed to play shutter sound", error)
        }.getOrDefault(false)
    }

    /** A preview plays exactly once, even for the burst sound. */
    suspend fun preview(isBurst: Boolean): Boolean {
        val sample = synchronized(this) { if (isBurst) burst else single } ?: return false
        if (withTimeoutOrNull(10_000) { sample.loaded.await() } != true) return false
        return synchronized(this) {
            if (sample !== (if (isBurst) burst else single)) false
            else playSample(sample, loop = false)
        }
    }

    @Synchronized
    fun stopBurst() {
        if (streamId != 0) runCatching { pool?.stop(streamId) }
        streamId = 0
    }

    @Synchronized
    fun release() {
        single?.loaded?.complete(false)
        burst?.loaded?.complete(false)
        runCatching { pool?.release() }
        pool = null
        single = null
        burst = null
        streamId = 0
    }
}
