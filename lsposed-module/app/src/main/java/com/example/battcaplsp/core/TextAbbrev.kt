package com.override.battcaplsp.core

/**
 * 文本截断统一工具，集中管理策略，便于后续根据屏幕/密度动态调整。
 */
object TextAbbrev {
    /**
     * 居中截断：保留前后等长，插入省略号。（max < 5 时直接裁切）
     */
    fun middle(text: String, max: Int): String {
        if (max <= 0) return ""
        if (text.length <= max) return text
        if (max <= 5) return text.take(max)
        val keep = (max - 3) / 2
        val prefix = text.take(keep)
        val suffix = text.takeLast(keep)
        return "$prefix...$suffix"
    }

    /**
     * 右侧截断（用于路径等前缀更重要场景）
     */
    fun tail(text: String, max: Int): String {
        if (max <= 0) return ""
        if (text.length <= max) return text
        return text.take(max - 1) + "…"
    }

    /**
     * 左侧截断（保留尾部重要信息：文件名 / hash 结尾）
     */
    fun head(text: String, max: Int): String {
        if (max <= 0) return ""
        if (text.length <= max) return text
        return "…" + text.takeLast(max - 1)
    }
}
