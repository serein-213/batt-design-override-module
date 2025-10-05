package com.override.battcaplsp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** High level operations around batt_design_override kernel module. */
class ModuleManager(
    private val moduleName: String = "batt_design_override",
    private val sysModuleBase: String = "/sys/module" ,
    private val paramNames: List<String> = listOf("batt_name","override_any","verbose","design_uah","design_uwh","model_name")
) {

    /** 获取当前内核版本信息 */
    suspend fun getKernelVersion(): KernelVersion {
        return withContext(Dispatchers.IO) {
            try {
                // 1) 先尝试读取 /proc/sys/kernel/osrelease；若权限被拒绝，退化到 uname -r 与 /proc/version
                val releaseFromOs = runCatching { File("/proc/sys/kernel/osrelease").readText().trim() }.getOrNull().orEmpty()
                val releaseCandidate = if (releaseFromOs.isNotBlank()) releaseFromOs else runCatching {
                    RootShell.exec("uname -r").out.trim()
                }.getOrNull().orEmpty()

                val versionStr = runCatching { File("/proc/version").readText().trim() }.getOrNull().orEmpty()

                when {
                    releaseCandidate.isNotBlank() -> parseKernelVersion(versionStr, releaseCandidate)
                    versionStr.isNotBlank() -> {
                        val m = Regex("Linux version ([^ ]+)").find(versionStr)
                        val rel = m?.groupValues?.getOrNull(1).orEmpty()
                        if (rel.isNotBlank()) parseKernelVersion(versionStr, rel) else KernelVersion("unknown", "unknown", "unknown")
                    }
                    else -> KernelVersion("unknown", "unknown", "unknown")
                }
            } catch (e: Exception) {
                KernelVersion("unknown", "unknown", "unknown")
            }
        }
    }
    
    private fun parseKernelVersion(versionStr: String, releaseStr: String): KernelVersion {
        // 解析类似 "5.15.123-g1234567-ab123456" 的版本字符串
        val baseVersion = releaseStr.split('-').firstOrNull() ?: "unknown"
        val majorMinor = baseVersion.split('.').take(2).joinToString(".")
        
        return KernelVersion(
            full = releaseStr,
            base = baseVersion,
            majorMinor = majorMinor
        )
    }

    data class KernelVersion(
        val full: String,      // 完整版本号，如 "5.15.123-g1234567"
        val base: String,      // 基础版本号，如 "5.15.123"
        val majorMinor: String // 主要版本号，如 "5.15"
    )

    /** 智能查找可用的内核模块文件 */
    suspend fun findAvailableKernelModule(moduleName: String, searchPaths: List<String> = getDefaultSearchPaths()): String? = withContext(Dispatchers.IO) {
        val kernelVersion = getKernelVersion()
        
        // 构建可能的文件名列表（按优先级排序）
        val possibleFileNames = buildPossibleFileNames(moduleName, kernelVersion)
        
        android.util.Log.d("ModuleManager", "Searching for module: $moduleName")
        android.util.Log.d("ModuleManager", "Kernel version: ${kernelVersion.full}")
        android.util.Log.d("ModuleManager", "Possible file names: $possibleFileNames")
        android.util.Log.d("ModuleManager", "Search paths: $searchPaths")
        
        // 在所有搜索路径中查找文件
        for (searchPath in searchPaths) {
            android.util.Log.d("ModuleManager", "Searching in path: $searchPath")
            
            for (fileName in possibleFileNames) {
                val filePath = "$searchPath/$fileName"
                
                try {
                    // 使用 root shell 检查文件是否存在且不为空
                    val checkResult = RootShell.exec("[ -f '$filePath' ] && [ -s '$filePath' ] && echo 'found' || echo 'not_found'")
                    
                    android.util.Log.d("ModuleManager", "Checking $filePath: result=${checkResult.out.trim()}")
                    
                    if (checkResult.code == 0 && checkResult.out.trim() == "found") {
                        android.util.Log.d("ModuleManager", "Found module file: $filePath")
                        return@withContext filePath
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ModuleManager", "Error checking file $filePath", e)
                    continue
                }
            }
        }
        
        android.util.Log.w("ModuleManager", "No suitable module file found for $moduleName")
        return@withContext null
    }
    
    /** 构建可能的文件名列表 */
    private fun buildPossibleFileNames(moduleName: String, kernelVersion: KernelVersion): List<String> {
        val fileNames = mutableListOf<String>()
        
        // 1. 完整版本匹配（最高优先级）
        // 格式：modulename-android13-5.15.ko
        val androidVersion = getAndroidVersionFromKernel(kernelVersion.majorMinor)
        if (androidVersion != null) {
            fileNames.add("${moduleName}-${androidVersion}-${kernelVersion.majorMinor}.ko")
            fileNames.add("${moduleName}-${androidVersion}-${kernelVersion.base}.ko")
        }
        
        // 2. 内核版本匹配
        // 格式：modulename-5.15.ko, modulename-5.15.123.ko
        fileNames.add("${moduleName}-${kernelVersion.majorMinor}.ko")
        fileNames.add("${moduleName}-${kernelVersion.base}.ko")
        
        // 3. 主版本匹配
        // 格式：modulename-5.ko
        val majorVersion = kernelVersion.majorMinor.split(".").firstOrNull()
        if (majorVersion != null) {
            fileNames.add("${moduleName}-${majorVersion}.ko")
        }
        
        // 4. 带版本标识的文件
        // 格式：modulename-v1.2.1-5.15.ko, modulename-1.2.1-5.15.ko
        val commonVersions = listOf("v1.2.1", "1.2.1", "v1.2", "1.2", "latest")
        for (version in commonVersions) {
            fileNames.add("${moduleName}-${version}-${kernelVersion.majorMinor}.ko")
            if (androidVersion != null) {
                fileNames.add("${moduleName}-${version}-${androidVersion}-${kernelVersion.majorMinor}.ko")
            }
        }
        
        // 5. 通用文件名（最低优先级）
        // 格式：modulename.ko
        fileNames.add("${moduleName}.ko")
        
        return fileNames.distinct()
    }
    
    /** 根据内核版本获取对应的 Android 版本标识 */
    private fun getAndroidVersionFromKernel(kernelVersion: String): String? {
        return when (kernelVersion) {
            "5.4" -> "android11"
            "5.10" -> "android12"
            "5.15" -> "android13"
            "6.1" -> "android14"
            "6.6" -> "android15"
            else -> null
        }
    }
    
    /** 获取默认的搜索路径列表 */
    private fun getDefaultSearchPaths(): List<String> {
        return listOf(
            "/data/adb/modules/batt-design-override-dynamic/common",  // 主要动态模块路径
            "/data/adb/modules/batt-design-override/common",          // 备用静态模块路径
            "/data/local/tmp",                                        // 临时下载路径
            "/data/local/tmp/modules",                               // 临时模块路径
            "/system/lib/modules",                                   // 系统模块路径
            "/vendor/lib/modules"                                    // 厂商模块路径
        )
    }
    
    /** 加载内核模块（智能文件名匹配版本） */
    suspend fun loadModuleWithSmartNaming(moduleName: String, initial: Map<String,String?>, searchPaths: List<String> = getDefaultSearchPaths()): RootShell.ExecResult = withContext(Dispatchers.IO) {
        val koPath = findAvailableKernelModule(moduleName, searchPaths)
        if (koPath == null) {
            // 提供详细的调试信息
            val debugInfo = getModuleSearchDebugInfo(moduleName, searchPaths)
            return@withContext RootShell.ExecResult(1, "", "未找到可用的内核模块文件: $moduleName\n\n调试信息:\n$debugInfo")
        }
        
        android.util.Log.d("ModuleManager", "Loading module from: $koPath")
        return@withContext load(koPath, initial)
    }
    
    /** 获取模块搜索的调试信息 */
    suspend fun getModuleSearchDebugInfo(moduleName: String, searchPaths: List<String> = getDefaultSearchPaths()): String = withContext(Dispatchers.IO) {
        val debugInfo = StringBuilder()
        val kernelVersion = getKernelVersion()
        val possibleFileNames = buildPossibleFileNames(moduleName, kernelVersion)
        
        debugInfo.appendLine("内核版本: ${kernelVersion.full}")
        debugInfo.appendLine("主版本: ${kernelVersion.majorMinor}")
        debugInfo.appendLine("搜索的文件名: ${possibleFileNames.joinToString(", ")}")
        debugInfo.appendLine()
        
        for (searchPath in searchPaths) {
            debugInfo.appendLine("搜索路径: $searchPath")
            
            // 检查路径是否存在
            val pathCheckResult = RootShell.exec("[ -d '$searchPath' ] && echo 'exists' || echo 'not_exists'")
            debugInfo.appendLine("  路径状态: ${pathCheckResult.out.trim()}")
            
            if (pathCheckResult.code == 0 && pathCheckResult.out.trim() == "exists") {
                // 列出路径中的所有 .ko 文件
                val listResult = RootShell.exec("ls -la '$searchPath'/*.ko 2>/dev/null || echo 'no_ko_files'")
                if (listResult.out.trim() != "no_ko_files") {
                    debugInfo.appendLine("  可用的 .ko 文件:")
                    listResult.out.split('\n').forEach { line ->
                        if (line.trim().isNotEmpty() && line.contains(".ko")) {
                            debugInfo.appendLine("    $line")
                        }
                    }
                } else {
                    debugInfo.appendLine("  无 .ko 文件")
                }
            }
            debugInfo.appendLine()
        }
        
        return@withContext debugInfo.toString()
    }

    suspend fun isLoaded(): Boolean = withContext(Dispatchers.IO) {
        // 优先严格检查：/sys/module/<name>/initstate == live
        try {
            val initStatePath = "$sysModuleBase/$moduleName/initstate"
            val initState = runCatching { File(initStatePath).takeIf { it.exists() }?.readText()?.trim() }.getOrNull()
            if (initState == "live") return@withContext true
            // 使用 root 兜底读取（避免权限问题）
            val initStateRes = RootShell.exec("cat ${shellQuoteIfNeeded(initStatePath)} 2>/dev/null || true")
            if (initStateRes.code == 0 && initStateRes.out.trim() == "live") return@withContext true
        } catch (_: Throwable) {
            // ignore and fallback
        }

        // 次级检查：/proc/modules 存在条目
        try {
            val procRes = RootShell.exec("cat /proc/modules | grep -E '^${moduleName}\\s' | wc -l")
            val count = procRes.out.trim().toIntOrNull() ?: 0
            if (procRes.code == 0 && count > 0) return@withContext true
        } catch (_: Throwable) {
            // ignore and fallback
        }

        // 最后保守检查：/sys/module/<name> 目录是否存在 且 parameters 目录存在（减少误报）
        try {
            val res = RootShell.exec("[ -d '$sysModuleBase/$moduleName' ] && [ -d '$sysModuleBase/$moduleName/parameters' ] && echo live || echo not")
            if (res.code == 0 && res.out.trim() == "live") return@withContext true
        } catch (_: Throwable) {
            // ignore
        }

        false
    }

    fun paramPath(param: String): String = "$sysModuleBase/$moduleName/parameters/$param"

    suspend fun readParam(param: String): String? = withContext(Dispatchers.IO) {
        // 先普通读取，失败时尝试 root cat，避免某些设备权限导致返回 null
        try {
            File(paramPath(param)).takeIf { it.exists() }?.readText()?.trim()?.let { return@withContext it }
        } catch (_: Throwable) { /* fallthrough */ }
        val p = paramPath(param)
        val res = RootShell.exec("cat ${shellQuoteIfNeeded(p)} 2>/dev/null || true")
        return@withContext if (res.code == 0 && res.out.isNotBlank()) res.out.trim() else null
    }

    suspend fun readAll(): Map<String,String?> {
        val map = mutableMapOf<String,String?>()
        for (p in paramNames) map[p] = readParam(p)
        return map
    }

    suspend fun writeParam(param: String, value: String): Boolean {
        val path = paramPath(param)
        if (!File(path).exists()) return false
        // 先尝试常规重定向；失败则使用 tee 兜底（某些环境下重定向可能因上下文导致失败）
        val cmd = "printf %s ${shellQuote(value)} > ${shellQuoteIfNeeded(path)} 2>/dev/null || (echo ${shellQuote(value)} | tee ${shellQuoteIfNeeded(path)} >/dev/null)"
        val res = RootShell.exec(cmd)
        return res.code == 0
    }

    suspend fun load(koPath: String, initial: Map<String,String?>): RootShell.ExecResult {
        // 先检查模块是否已经加载
        if (isLoaded()) {
            return RootShell.ExecResult(1, "", "模块 $moduleName 已经加载，请先卸载")
        }
        
        // compose insmod with initial params that are not null/blank/zero depending semantics
        val args = buildString {
            for ((k,v) in initial) if (!v.isNullOrBlank()) {
                append(' ')
                append(k).append('=')
                append(shellQuoteIfNeeded(v))
            }
        }
        return RootShell.exec("insmod ${shellQuoteIfNeeded(koPath)}$args")
    }

    suspend fun unload(): RootShell.ExecResult = RootShell.exec("rmmod $moduleName")

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun shellQuoteIfNeeded(s: String): String {
        return if (s.matches(Regex("^[A-Za-z0-9._/:=-]+$"))) s else shellQuote(s)
    }
}
