package com.override.battcaplsp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * 终端风格日志查看组件
 * 功能:
 * - 固定等宽字体、深色背景、可滚动
 * - 支持复制、清空、自动滚动到底部
 * - 限制最大行数（由调用方处理截断逻辑）
 */
@Composable
fun LogViewer(
    title: String,
    logText: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    maxHeight: Int = 280,
    autoScroll: Boolean = true
) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    // 当日志变化且需要自动滚动时，滚动到底部
    LaunchedEffect(logText) {
        if (autoScroll) {
            // 延迟一帧等待布局完成
            kotlinx.coroutines.delay(16)
            vScroll.scrollTo(vScroll.maxValue)
        }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(logText.ifBlank { "(空)" }))
                    }) { Text("复制") }
                    if (onClear != null) {
                        TextButton(onClick = { onClear() }) { Text("清空") }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = maxHeight.dp)
                    .background(Color(0xFF111111), shape = MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                if (logText.isBlank()) {
                    Text(
                        "(暂无日志)",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll)
                    ) {
                        Text(
                            logText,
                            color = Color(0xFFEEEEEE),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }
        }
    }
}
