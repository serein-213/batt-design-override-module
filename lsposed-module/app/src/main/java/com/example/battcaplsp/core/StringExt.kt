package com.override.battcaplsp.core

/** 截断字符串（居中），避免过长日志撑爆 UI */
fun String.truncateMiddle(max: Int): String {
    if (max <= 0) return ""
    if (length <= max) return this
    if (max <= 10) return take(max)
    val keep = (max - 3) / 2
    val prefix = take(keep)
    val suffix = takeLast(keep)
    return "$prefix...$suffix"
}
