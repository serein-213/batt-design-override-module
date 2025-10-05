package com.override.battcaplsp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.override.battcaplsp.core.RootShell

object ResultFormatter {
    
    fun formatModuleLoadResult(res: RootShell.ExecResult): String {
        return when {
            res.code == 0 && res.err.isEmpty() -> "✅ 模块加载成功"
            res.code == 0 && res.err.isNotEmpty() -> "⚠️ 模块加载成功，但有警告: ${res.err}"
            res.code == 1 && res.err.isEmpty() -> "❌ 模块加载失败: 权限不足或模块已存在"
            res.code == 1 && res.err.contains("exists") -> "❌ 模块加载失败: 模块已经存在，请先卸载"
            res.code == 1 && res.err.contains("permission") -> "❌ 模块加载失败: 权限不足，请检查ROOT权限"
            res.code == 1 && res.err.contains("not found") -> "❌ 模块加载失败: 找不到模块文件"
            res.code == 2 -> "❌ 模块加载失败: 文件格式错误或依赖缺失"
            res.code != 0 && res.err.isNotEmpty() -> "❌ 模块加载失败 (错误码: ${res.code}): ${res.err}"
            res.code != 0 -> "❌ 模块加载失败 (错误码: ${res.code}): 未知错误"
            else -> "❓ 模块加载状态未知"
        }
    }

    fun formatModuleUnloadResult(res: RootShell.ExecResult): String {
        return when {
            res.code == 0 -> "✅ 模块卸载成功"
            res.code == 1 && res.err.contains("not found") -> "⚠️ 模块已经卸载或不存在"
            res.code == 1 && res.err.contains("busy") -> "❌ 模块卸载失败: 模块正在使用中"
            res.code == 1 && res.err.contains("permission") -> "❌ 模块卸载失败: 权限不足"
            res.code != 0 && res.err.isNotEmpty() -> "❌ 模块卸载失败 (错误码: ${res.code}): ${res.err}"
            res.code != 0 -> "❌ 模块卸载失败 (错误码: ${res.code}): 未知错误"
            else -> "❓ 模块卸载状态未知"
        }
    }

    fun formatApplyResult(res: RootShell.ExecResult): String {
        return when {
            res.code == 0 -> "✅ 参数应用成功"
            res.code != 0 && res.err.isNotEmpty() -> "❌ 参数应用失败: ${res.err}"
            res.code != 0 -> "❌ 参数应用失败 (错误码: ${res.code})"
            else -> "❓ 参数应用状态未知"
        }
    }

    fun formatPdHelperResult(deployCode: Int, startCode: Int): String {
        return when {
            deployCode == 0 && startCode == 0 -> "✅ PD守护部署并启动成功"
            deployCode == 0 && startCode != 0 -> "⚠️ PD守护部署成功，但启动失败 (启动码: $startCode)"
            deployCode != 0 && startCode == 0 -> "⚠️ PD守护部署失败，但启动成功 (部署码: $deployCode)"
            deployCode != 0 && startCode != 0 -> "❌ PD守护部署和启动都失败 (部署码: $deployCode, 启动码: $startCode)"
            else -> "❓ PD守护状态未知"
        }
    }

    fun formatPdStopResult(res: RootShell.ExecResult): String {
        return when {
            res.code == 0 -> "✅ PD守护停止成功"
            res.code != 0 && res.err.isNotEmpty() -> "❌ PD守护停止失败: ${res.err}"
            res.code != 0 -> "❌ PD守护停止失败 (错误码: ${res.code})"
            else -> "❓ PD守护停止状态未知"
        }
    }

    fun formatReadParamsResult(success: Boolean): String {
        return if (success) "✅ 参数读取成功" else "❌ 参数读取失败"
    }

    fun formatSaveConfigResult(success: Boolean): String {
        return if (success) "✅ 配置保存成功" else "❌ 配置保存失败"
    }

    fun formatKernelLogResult(res: RootShell.ExecResult): String {
        return when {
            res.code == 0 && res.out.isNotBlank() -> "✅ 内核日志读取成功"
            res.code == 0 && res.out.isBlank() -> "⚠️ 内核日志为空，可能模块未输出日志"
            res.code != 0 && res.err.isNotEmpty() -> "❌ 读取内核日志失败: ${res.err}"
            res.code != 0 -> "❌ 读取内核日志失败 (错误码: ${res.code})"
            else -> "❓ 内核日志读取状态未知"
        }
    }

    @Composable
    fun getResultColor(result: String): androidx.compose.ui.graphics.Color {
        return when {
            result.startsWith("✅") -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // 绿色 - 成功
            result.startsWith("⚠️") -> androidx.compose.ui.graphics.Color(0xFFFF9800) // 橙色 - 警告
            result.startsWith("❌") -> androidx.compose.ui.graphics.Color(0xFFF44336) // 红色 - 错误
            result.startsWith("❓") -> MaterialTheme.colorScheme.secondary // 默认颜色 - 未知
            result.contains("成功") -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // 绿色
            result.contains("失败") || result.contains("错误") -> androidx.compose.ui.graphics.Color(0xFFF44336) // 红色
            else -> MaterialTheme.colorScheme.secondary // 默认颜色
        }
    }
}
