package com.override.battcaplsp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 统一的状态类型 */
enum class UnifiedStatusType { SUCCESS, WARN, ERROR, INFO, UNKNOWN }

data class ParsedStatus(val type: UnifiedStatusType, val message: String)

fun parseStatus(raw: String): ParsedStatus {
    val idx = raw.indexOf(":")
    if (idx in 1..20) {
        val prefix = raw.substring(0, idx).uppercase()
        val rest = raw.substring(idx + 1).trim().ifEmpty { raw }
        val t = when (prefix) {
            "SUCCESS" -> UnifiedStatusType.SUCCESS
            "WARN" -> UnifiedStatusType.WARN
            "ERROR" -> UnifiedStatusType.ERROR
            "INFO" -> UnifiedStatusType.INFO
            "UNKNOWN" -> UnifiedStatusType.UNKNOWN
            else -> return ParsedStatus(UnifiedStatusType.INFO, raw)
        }
        return ParsedStatus(t, rest)
    }
    return ParsedStatus(UnifiedStatusType.INFO, raw)
}

// 去除前缀（用于 Toast 或日志写入避免重复解析）
fun String.stripStatusPrefix(): String {
    val idx = indexOf(":")
    return if (idx in 1..20) substring(idx + 1).trim() else this
}

@Composable
fun StatusBadge(raw: String, modifier: Modifier = Modifier, showLabel: String? = null) {
    val (type, msg) = parseStatus(raw)
    val (icon, tint) = when (type) {
        UnifiedStatusType.SUCCESS -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
        UnifiedStatusType.WARN -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
        UnifiedStatusType.ERROR -> Icons.Default.Warning to MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
        UnifiedStatusType.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        UnifiedStatusType.UNKNOWN -> Icons.Default.Info to MaterialTheme.colorScheme.secondary.copy(alpha = 0.60f)
    }
    // TODO(icons): 若后续需要为 ERROR 与 WARN 使用不同形状的图标（而不是同一个 Warning），
    // 可在不增加 material-icons-extended 体积的前提下，内置一对本地矢量资源 (error_triangle.xml / warn_circle.xml)
    // 然后在这里根据 type 分流；当前保持最小依赖 footprint。
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        if (showLabel != null) {
            Text(showLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(msg, style = MaterialTheme.typography.bodySmall, color = when (type) {
            UnifiedStatusType.SUCCESS -> tint
            UnifiedStatusType.WARN -> tint
            UnifiedStatusType.ERROR -> tint
            UnifiedStatusType.INFO -> MaterialTheme.colorScheme.onSurface
            UnifiedStatusType.UNKNOWN -> Color.Gray
        })
    }
}
