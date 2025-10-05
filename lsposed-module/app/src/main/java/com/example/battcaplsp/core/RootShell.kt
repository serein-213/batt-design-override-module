package com.override.battcaplsp.core

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 优化的 Root Shell 助手
 * 包含重试机制和更可靠的权限检测逻辑
 */
object RootShell {
    private var lastCheckTime = 0L
    private var cachedRootStatus: Boolean? = null
    private const val CACHE_DURATION = 5000L // 5秒缓存
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY = 1000L // 1秒重试间隔
    private const val SHELL_INIT_TIMEOUT = 10000L // 10秒Shell初始化超时
    
    /**
     * 检查 root 权限是否可用 - 带重试和缓存机制
     */
    suspend fun checkRootAccess(forceRefresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        
        // 如果不强制刷新且缓存有效，返回缓存结果
        if (!forceRefresh && cachedRootStatus != null && 
            (currentTime - lastCheckTime) < CACHE_DURATION) {
            return@withContext cachedRootStatus!!
        }
        
        var lastException: Throwable? = null
        
        // 重试机制
        repeat(MAX_RETRIES) { attempt ->
            try {
                val hasRoot = performRootCheck()
                if (hasRoot) {
                    // 成功获取root权限，更新缓存
                    cachedRootStatus = true
                    lastCheckTime = currentTime
                    return@withContext true
                }
                
                // 如果不是最后一次尝试，等待后重试
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY)
                }
            } catch (t: Throwable) {
                lastException = t
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY)
                }
            }
        }
        
        // 所有重试都失败，更新缓存为false
        cachedRootStatus = false
        lastCheckTime = currentTime
        false
    }
    
    /**
     * 执行实际的root权限检测
     */
    private suspend fun performRootCheck(): Boolean = withContext(Dispatchers.IO) {
        // 1. 首先检查Shell是否被授予root权限
        val shellGranted = withTimeoutOrNull(SHELL_INIT_TIMEOUT) {
            // 等待Shell初始化
            var attempts = 0
            while (attempts < 5) {
                try {
                    val granted = Shell.isAppGrantedRoot()
                    if (granted == true) return@withTimeoutOrNull true
                    if (granted == false) return@withTimeoutOrNull false
                    // granted == null 表示还在初始化，继续等待
                    delay(500)
                    attempts++
                } catch (e: Exception) {
                    delay(500)
                    attempts++
                }
            }
            Shell.isAppGrantedRoot()
        }
        
        if (shellGranted != true) {
            return@withContext false
        }
        
        // 2. 执行多个命令验证root权限
        val commands = listOf(
            "id",
            "whoami", 
            "ls /data/data/ | head -1"  // 需要root权限才能访问
        )
        
        for (cmd in commands) {
            try {
                val result = withTimeoutOrNull(3000) {
                    Shell.cmd(cmd).exec()
                }
                
                if (result == null || !result.isSuccess) {
                    continue
                }
                
                when (cmd) {
                    "id" -> {
                        if (result.out.any { it.contains("uid=0") }) {
                            return@withContext true
                        }
                    }
                    "whoami" -> {
                        if (result.out.any { it.trim() == "root" }) {
                            return@withContext true
                        }
                    }
                    else -> {
                        // ls /data/data/ 命令成功执行说明有root权限
                        if (result.out.isNotEmpty()) {
                            return@withContext true
                        }
                    }
                }
            } catch (e: Exception) {
                // 继续尝试下一个命令
                continue
            }
        }
        
        false
    }
    
    /**
     * 获取 root 状态信息 - 支持强制刷新
     */
    suspend fun getRootStatus(forceRefresh: Boolean = false): RootStatus = withContext(Dispatchers.IO) {
        return@withContext try {
            val hasRoot = checkRootAccess(forceRefresh)
            if (!hasRoot) {
                return@withContext RootStatus(false, "Root 权限不可用\n请确保设备已root并授予应用权限")
            }
            
            // 获取详细的root信息
            val infoCommands = listOf("id", "whoami", "getenforce 2>/dev/null || echo 'SELinux: Unknown'")
            val infoResults = mutableListOf<String>()
            
            for (cmd in infoCommands) {
                try {
                    val result = withTimeoutOrNull(2000) {
                        Shell.cmd(cmd).exec()
                    }
                    if (result?.isSuccess == true && result.out.isNotEmpty()) {
                        infoResults.addAll(result.out)
                    }
                } catch (e: Exception) {
                    // 忽略单个命令的错误
                }
            }
            
            val info = if (infoResults.isNotEmpty()) {
                infoResults.joinToString("\n")
            } else {
                "Root权限验证成功"
            }
            
            RootStatus(true, "✅ Root 权限已获取\n$info")
        } catch (t: Throwable) {
            RootStatus(false, "Root 权限检查异常: ${t.message}")
        }
    }
    
    /**
     * 清除缓存，强制下次检查时重新验证
     */
    fun clearCache() {
        cachedRootStatus = null
        lastCheckTime = 0L
    }

    suspend fun exec(cmd: String): ExecResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val res = Shell.cmd(cmd).exec()
            ExecResult(res.code, res.out.joinToString("\n"), res.err.joinToString("\n"))
        } catch (t: Throwable) {
            ExecResult(-1, "", t.message ?: "error")
        }
    }

    fun shellArg(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    data class ExecResult(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0 && err.isEmpty()
    }
    
    data class RootStatus(val available: Boolean, val message: String)
}
