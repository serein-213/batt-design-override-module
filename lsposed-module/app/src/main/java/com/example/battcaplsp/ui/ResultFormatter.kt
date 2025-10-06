package com.override.battcaplsp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.override.battcaplsp.core.RootShell

object ResultFormatter {
    
    fun formatModuleLoadResult(res: RootShell.ExecResult): String = when {
        res.code == 0 && res.err.isEmpty() -> "SUCCESS:模块加载成功"
        res.code == 0 && res.err.isNotEmpty() -> "WARN:模块加载成功，但有警告: ${res.err}"
        res.code == 1 && res.err.isEmpty() -> "ERROR:模块加载失败: 权限不足或模块已存在"
        res.code == 1 && res.err.contains("exists") -> "ERROR:模块加载失败: 模块已经存在，请先卸载"
        res.code == 1 && res.err.contains("permission") -> "ERROR:模块加载失败: 权限不足，请检查ROOT权限"
        res.code == 1 && res.err.contains("not found") -> "ERROR:模块加载失败: 找不到模块文件"
        res.code == 2 -> "ERROR:模块加载失败: 文件格式错误或依赖缺失"
        res.code != 0 && res.err.isNotEmpty() -> "ERROR:模块加载失败 (错误码: ${res.code}): ${res.err}"
        res.code != 0 -> "ERROR:模块加载失败 (错误码: ${res.code}): 未知错误"
        else -> "UNKNOWN:模块加载状态未知"
    }

    fun formatModuleUnloadResult(res: RootShell.ExecResult): String = when {
        res.code == 0 -> "SUCCESS:模块卸载成功"
        res.code == 1 && res.err.contains("not found") -> "WARN:模块已经卸载或不存在"
        res.code == 1 && res.err.contains("busy") -> "ERROR:模块卸载失败: 模块正在使用中"
        res.code == 1 && res.err.contains("permission") -> "ERROR:模块卸载失败: 权限不足"
        res.code != 0 && res.err.isNotEmpty() -> "ERROR:模块卸载失败 (错误码: ${res.code}): ${res.err}"
        res.code != 0 -> "ERROR:模块卸载失败 (错误码: ${res.code}): 未知错误"
        else -> "UNKNOWN:模块卸载状态未知"
    }

    fun formatApplyResult(res: RootShell.ExecResult): String = when {
        res.code == 0 -> "SUCCESS:参数应用成功"
        res.code != 0 && res.err.isNotEmpty() -> "ERROR:参数应用失败: ${res.err}"
        res.code != 0 -> "ERROR:参数应用失败 (错误码: ${res.code})"
        else -> "UNKNOWN:参数应用状态未知"
    }

    fun formatPdHelperResult(deployCode: Int, startCode: Int): String = when {
        deployCode == 0 && startCode == 0 -> "SUCCESS:PD守护部署并启动成功"
        deployCode == 0 && startCode != 0 -> "WARN:PD守护部署成功，但启动失败 (启动码: $startCode)"
        deployCode != 0 && startCode == 0 -> "WARN:PD守护部署失败，但启动成功 (部署码: $deployCode)"
        deployCode != 0 && startCode != 0 -> "ERROR:PD守护部署和启动都失败 (部署码: $deployCode, 启动码: $startCode)"
        else -> "UNKNOWN:PD守护状态未知"
    }

    fun formatPdStopResult(res: RootShell.ExecResult): String = when {
        res.code == 0 -> "SUCCESS:PD守护停止成功"
        res.code != 0 && res.err.isNotEmpty() -> "ERROR:PD守护停止失败: ${res.err}"
        res.code != 0 -> "ERROR:PD守护停止失败 (错误码: ${res.code})"
        else -> "UNKNOWN:PD守护停止状态未知"
    }

    fun formatReadParamsResult(success: Boolean): String = if (success) "SUCCESS:参数读取成功" else "ERROR:参数读取失败"

    fun formatSaveConfigResult(success: Boolean): String = if (success) "SUCCESS:配置保存成功" else "ERROR:配置保存失败"

    fun formatKernelLogResult(res: RootShell.ExecResult): String = when {
        res.code == 0 && res.out.isNotBlank() -> "SUCCESS:内核日志读取成功"
        res.code == 0 && res.out.isBlank() -> "WARN:内核日志为空，可能模块未输出日志"
        res.code != 0 && res.err.isNotEmpty() -> "ERROR:读取内核日志失败: ${res.err}"
        res.code != 0 -> "ERROR:读取内核日志失败 (错误码: ${res.code})"
        else -> "UNKNOWN:内核日志读取状态未知"
    }

    @Composable
    fun getResultColor(result: String): androidx.compose.ui.graphics.Color = when {
        result.startsWith("SUCCESS:") -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        result.startsWith("WARN:") -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        result.startsWith("ERROR:") -> androidx.compose.ui.graphics.Color(0xFFF44336)
        result.startsWith("INFO:") -> MaterialTheme.colorScheme.primary
        result.startsWith("UNKNOWN:") -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.secondary
    }
}
