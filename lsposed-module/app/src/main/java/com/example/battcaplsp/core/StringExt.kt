package com.override.battcaplsp.core

/** 截断字符串（居中），避免过长日志撑爆 UI */
@Deprecated("Use TextAbbrev.middle(text, max) instead for centralized management")
fun String.truncateMiddle(max: Int): String = TextAbbrev.middle(this, max)
