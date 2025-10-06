package com.override.battcaplsp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 简易日志查看对话框。
 */
@Composable
fun LogViewerDialog(
    logText: String,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    title: String = "日志"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp)) {
                val scroll = rememberScrollState()
                Text(
                    text = logText.ifBlank { "(无内容)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.verticalScroll(scroll)
                )
            }
        },
        confirmButton = {
            Row {
                if (onRefresh != null) {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}
