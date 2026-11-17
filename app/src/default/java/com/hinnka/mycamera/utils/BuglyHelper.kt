package com.hinnka.mycamera.utils

import android.content.Context
import android.provider.Settings
import com.tencent.bugly.crashreport.BuglyLog
import com.tencent.bugly.crashreport.CrashReport

object BuglyHelper {
    private const val TAG = "BuglyHelper"

    fun init(context: Context) {
        runCatching {
            val strategy = CrashReport.UserStrategy(context).apply {
                deviceID = runCatching {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                }.getOrNull() ?: "unknown"
                deviceModel = DeviceUtil.model
            }
            CrashReport.initCrashReport(context.applicationContext, "9966e9cd35", false, strategy)
        }.onFailure { error ->
            PLog.w(TAG, "Bugly init unavailable: ${error.message}")
        }
    }

    fun setUserScene(context: Context, scene: Int) {
        runCatching {
            CrashReport.setUserSceneTag(context, scene)
        }
    }

    fun putUserData(context: Context, key: String, value: String) {
        runCatching {
            CrashReport.putUserData(context, key, value)
        }
    }

    fun log(tag: String, msg: String, throwable: Throwable? = null) {
        runCatching {
            BuglyLog.e(tag, msg, throwable)
        }
    }

    fun error(throwable: Throwable) {
        runCatching {
            CrashReport.postCatchedException(throwable)
        }
    }
}
