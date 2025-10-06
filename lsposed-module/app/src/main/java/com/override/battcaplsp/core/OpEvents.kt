package com.override.battcaplsp.core

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简单的内存事件日志 (进程内) - 用于 UI 展示最近操作结果。
 * 不写入磁盘，避免权限 & 体积问题；如需持久化可扩展 DataStore。
 */
object OpEvents {
    data class Event(val time: String, val type: Type, val msg: String) {
        enum class Type { SUCCESS, WARN, ERROR, INFO }
    }

    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val events = mutableStateListOf<Event>()
    private const val MAX = 120

    @Synchronized
    fun log(type: Event.Type, msg: String) {
        val e = Event(formatter.format(Date()), type, msg.take(400))
        events.add(0, e)
        if (events.size > MAX) events.removeLast()
        // 可选：输出到 logcat 方便调试
        android.util.Log.d("OpEvents", "${type.name}: ${e.msg}")
    }

    fun success(msg: String) = log(Event.Type.SUCCESS, msg)
    fun warn(msg: String) = log(Event.Type.WARN, msg)
    fun error(msg: String) = log(Event.Type.ERROR, msg)
    fun info(msg: String) = log(Event.Type.INFO, msg)

    fun clear() { events.clear() }
}
