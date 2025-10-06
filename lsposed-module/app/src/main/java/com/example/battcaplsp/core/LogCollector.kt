package com.override.battcaplsp.core

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 简单日志收集工具：抓取 logcat 末尾若干行 (默认 400)。
 * 生产环境可扩展筛选 tag / 级别 / 持久化。
 */
object LogCollector {
    data class LogResult(
        val lines: List<String>,
        val truncated: Boolean
    )

    /** 获取最近日志末尾 maxLines 行；失败返回空字符串。 */
    fun getRecentLogs(context: Context, maxLines: Int = 400): String {
        return try {
            // -d 只读取并退出；-v time 保留时间戳；不加过滤先全量读再截尾
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            proc.inputStream.bufferedReader().use(BufferedReader::readText)
                .lineSequence()
                .toList()
                .let { all ->
                    val truncated = all.size > maxLines
                    val tail = if (truncated) all.takeLast(maxLines) else all
                    buildString {
                        if (truncated) append("[... 截断: 仅显示末尾 ${maxLines} 行 ...]\n")
                        tail.forEach { append(it).append('\n') }
                    }
                }
        } catch (e: Exception) {
            "获取日志失败: ${e.message}".also { _ -> }
        }
    }
}
