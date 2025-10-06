package com.override.battcaplsp

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻量启动阶段打点工具：避免引入依赖，默认仅 Debug / 系统属性开启时输出。
 * 打点阶段（按顺序）:
 *  1. procStart (App.onCreate 之前 - 无法直接记录，使用 appOnCreate 作为最早点)
 *  2. appOnCreate
 *  3. activityCreateStart
 *  4. setContentStart
 *  5. firstCompose (Composition 完成进入初帧等待)
 *  6. firstFrame (第一帧提交时间 - frameNanos)
 *  7. uiInteractive (主界面重要状态可交互，自定义触发)
 */
object LaunchTrace {
    private const val TAG = "LaunchTrace"
    private val appOnCreate = AtomicLong(0L)
    private val activityCreateStart = AtomicLong(0L)
    private val setContentStart = AtomicLong(0L)
    private val firstCompose = AtomicLong(0L)
    private val firstFrame = AtomicLong(0L)
    private val uiInteractive = AtomicLong(0L)

    // 开关：Debug 构建或设置属性 batt.launch.trace=1
    @Volatile private var enabled = false

    fun init(enabledFlag: Boolean) { enabled = enabledFlag }

    private fun now() = SystemClock.elapsedRealtime()

    fun markAppOnCreate() = mark(appOnCreate, "appOnCreate")
    fun markActivityCreateStart() = mark(activityCreateStart, "activityCreateStart")
    fun markSetContentStart() = mark(setContentStart, "setContentStart")
    fun markFirstCompose() = mark(firstCompose, "firstCompose")
    fun markFirstFrame() = mark(firstFrame, "firstFrame")
    fun markUiInteractive() = mark(uiInteractive, "uiInteractive", final = true)

    private fun mark(slot: AtomicLong, label: String, final: Boolean = false) {
        if (!enabled) return
        if (slot.get() != 0L) return // 避免重复
        slot.set(now())
        Log.i(TAG, "MARK $label=${slot.get()}ms")
        if (final) dumpSummaryIfComplete()
    }

    private fun dumpSummaryIfComplete() {
        if (listOf(appOnCreate, activityCreateStart, setContentStart, firstCompose, firstFrame, uiInteractive).all { it.get() > 0 }) {
            val a = appOnCreate.get()
            val ac = activityCreateStart.get()
            val sc = setContentStart.get()
            val fc = firstCompose.get()
            val ff = firstFrame.get()
            val ui = uiInteractive.get()
            Log.i(TAG, buildString {
                append("ColdStart Summary (ms since boot):\n")
                append(" appOnCreate=$a\n")
                append(" activityCreateStart=$ac (+${ac - a})\n")
                append(" setContentStart=$sc (+${sc - ac})\n")
                append(" firstCompose=$fc (+${fc - sc})\n")
                append(" firstFrame=$ff (+${ff - fc})\n")
                append(" uiInteractive=$ui (+${ui - ff})\n")
                append(" TOTAL from appOnCreate -> uiInteractive = ${ui - a} ms")
            })
        }
    }
}
