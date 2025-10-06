package com.override.battcaplsp

import android.app.Application
import android.os.Build

class App: Application() {
    override fun onCreate() {
        super.onCreate()
        val traceEnabled = BuildConfig.DEBUG || kotlin.runCatching {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, "persist.batt.launch.trace", "0") as String) == "1"
        }.getOrDefault(false)
        LaunchTrace.init(traceEnabled)
        LaunchTrace.markAppOnCreate()
    }
}
