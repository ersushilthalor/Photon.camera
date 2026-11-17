package com.hinnka.mycamera

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import com.hinnka.mycamera.data.ContentRepository
import com.hinnka.mycamera.gallery.GalleryManager
import com.hinnka.mycamera.phantom.PhantomService
import com.hinnka.mycamera.phantom.PhantomShortcutActivity
import com.hinnka.mycamera.raw.RawSceneExposureEstimator
import com.hinnka.mycamera.screencapture.PhantomPipPreviewCoordinator
import com.hinnka.mycamera.update.AppUpdateManager
import com.hinnka.mycamera.utils.BuglyHelper
import com.hinnka.mycamera.utils.DeviceUtil
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.utils.StartupTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MyCameraApplication : Application() {
    private val applicationScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark("Application.onCreate start")
        instance = this
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                removeLegacyDetectorFiles()
            }.onFailure { error ->
                PLog.w(TAG, "Removing legacy detector files failed", error)
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            runCatching {
                RawSceneExposureEstimator.warmUp(this@MyCameraApplication)
            }.onFailure { error ->
                PLog.w(TAG, "RawSceneExposureEstimator warmUp failed", error)
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                StartupTrace.measure("BuglyHelper.init") {
                    BuglyHelper.init(this@MyCameraApplication)
                }
            }.onFailure { error ->
                PLog.e(TAG, "Bugly initialization failed", error)
            }
        }
        val contentRepository = ContentRepository.getInstance(this)
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                StartupTrace.measure("ContentRepository.initialize") {
                    contentRepository.initialize()
                }
            }.onFailure { error ->
                PLog.e(TAG, "Content repository initialization failed", error)
            }
        }
        phantomService = StartupTrace.measure("PhantomService()") {
            PhantomService(this)
        }
        recoverPrivateGalleryIndex()
//        AppUpdateManager.startSilentUpdate(this)

        val userPreferencesRepository = contentRepository.userPreferencesRepository
        applicationScope.launch {
            StartupTrace.measure("Application.first userPreferences load") {
                userPreferencesRepository.userPreferences.first()
            }
            userPreferencesRepository.userPreferences.map { it.phantomMode }.distinctUntilChanged()
                .collect { phantomMode ->
                    StartupTrace.mark("Application.phantomMode collected", "phantomMode=$phantomMode")
                    if (phantomMode) {
                        phantomService.start()
                    } else {
                        PhantomPipPreviewCoordinator.requestStop(this@MyCameraApplication)
                        phantomService.stop()
                    }
                    if (DeviceUtil.canShowPhantom) {
                        updateShortcuts(phantomMode)
                    }
                    updateWidgets(this@MyCameraApplication)
                }
        }
        StartupTrace.mark("Application.onCreate end")
    }

    private fun removeLegacyDetectorFiles() {
        listOf(
            "efficientdet_lite0_coco_person.tflite",
            "efficientdet_lite0_coco_person.tflite.download",
        ).forEach { fileName ->
            val staleFile = java.io.File(filesDir, fileName)
            if (staleFile.exists() && !staleFile.delete()) {
                PLog.w(TAG, "Unable to remove obsolete detector file: $fileName")
            }
        }
    }

    private fun recoverPrivateGalleryIndex() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val result = GalleryManager.recoverPrivatePhotoDirectoryToDatabase(this@MyCameraApplication)
                PLog.d(
                    TAG,
                    "Private gallery recovery: scanned=${result.scannedCount}, " +
                        "restored=${result.restoredCount}, existing=${result.skippedExistingCount}, " +
                        "unsupported=${result.skippedUnsupportedCount}, failed=${result.failedCount}"
                )
            } catch (e: Throwable) {
                PLog.e(TAG, "Private gallery recovery failed", e)
            }
        }
    }

    private fun updateShortcuts(isActive: Boolean) {
        runCatching {
            val shortcutManager = getSystemService(android.content.pm.ShortcutManager::class.java)
                ?: return@runCatching
            val phantomShortcut = android.content.pm.ShortcutInfo.Builder(this, "phantom_toggle")
                .setShortLabel(getString(if (isActive) R.string.close_ghost_mode else R.string.ghost_mode))
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_camera))
                .setIntent(Intent(this, PhantomShortcutActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                })
                .build()
            val lutShortcut = android.content.pm.ShortcutInfo.Builder(this, "lut_manage")
                .setShortLabel(getString(R.string.filter_management_title))
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.auto_awesome_color))
                .setIntent(Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra("route", Routes.filterManagement())
                })
                .build()
            shortcutManager.dynamicShortcuts = listOf(phantomShortcut, lutShortcut)
        }.onFailure { error ->
            PLog.w(TAG, "Failed to update dynamic shortcuts", error)
        }
    }

    companion object {
        private const val TAG = "MyCameraApplication"

        lateinit var instance: MyCameraApplication

        @SuppressLint("StaticFieldLeak")
        lateinit var phantomService: PhantomService

        fun updateWidgets(context: Context) {
            runCatching {
                val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
                    ?: return@runCatching
                val componentName =
                    android.content.ComponentName(context, com.hinnka.mycamera.phantom.PhantomWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                if (appWidgetIds.isNotEmpty()) {
                    val intent = Intent(context, com.hinnka.mycamera.phantom.PhantomWidgetProvider::class.java).apply {
                        action = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                    }
                    context.sendBroadcast(intent)
                }
            }.onFailure { error ->
                PLog.w(TAG, "Failed to update widgets", error)
            }
        }
    }
}
