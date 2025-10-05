package com.override.battcaplsp.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Magisk 模块管理器
 * 负责创建、安装和管理 Magisk 模块
 */
class MagiskModuleManager(private val context: Context) {
    
    companion object {
        private const val MAGISK_MODULES_PATH = "/data/adb/modules"
        // 以动态模块为主，兼容旧的静态模块 ID
        private const val MODULE_ID_PRIMARY = "batt-design-override-dynamic"
        private val MODULE_ID_ALTERNATIVES = listOf("batt-design-override")
        private const val MODULE_NAME = "Battery Design Override (Dynamic)"
        private const val MODULE_VERSION = "2.0.0"
        private const val MODULE_AUTHOR = "serein-213"
        private const val MODULE_DESCRIPTION = "内核级修改手机电池设计容量 (动态下载版本)"
    }
    
    data class ModuleInstallResult(
        val success: Boolean,
        val message: String,
        val modulePath: String? = null
    )
    
    /** 检查 Magisk/KernelSU 模块环境是否可用 */
    suspend fun isMagiskAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 使用 root shell 检查目录可见性，避免普通权限受限
            val checks = listOf(
                "[ -d /data/adb/modules ]",
                "[ -d /sbin/.magisk ]",
                "[ -d /data/adb/magisk ]",
                "[ -d /data/adb/ksu ]",
                "[ -d /data/adb/ksud ]"
            ).joinToString(" || ")
            val res = RootShell.exec("sh -c '\n$checks\n' || true")
            if (res.code == 0) return@withContext true

            // 兜底：which magisk
            val which = RootShell.exec("which magisk 2>/dev/null || true")
            which.code == 0 && which.out.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }
    
    /** 检查模块是否已安装（兼容主/备 ID） */
    suspend fun isModuleInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 使用 root shell，避免目录权限导致误判
            val ids = listOf(MODULE_ID_PRIMARY) + MODULE_ID_ALTERNATIVES
            
            // 更严格的检测：不仅检查目录存在，还要检查关键文件
            for (id in ids) {
                val modulePath = "$MAGISK_MODULES_PATH/$id"
                val checkCmd = """
                    if [ -d "$modulePath" ] && [ -f "$modulePath/module.prop" ]; then
                        echo "found:$id"
                        exit 0
                    fi
                """.trimIndent()
                
                val res = RootShell.exec("sh -c '$checkCmd' 2>/dev/null || true")
                android.util.Log.d("MagiskModuleManager", "Checking $id: code=${res.code}, out='${res.out.trim()}'")
                
                if (res.code == 0 && res.out.contains("found:")) {
                    android.util.Log.d("MagiskModuleManager", "Module $id is installed")
                    return@withContext true
                }
            }
            
            android.util.Log.d("MagiskModuleManager", "No modules found")
            return@withContext false
            
        } catch (e: Exception) {
            android.util.Log.e("MagiskModuleManager", "Error checking module installation", e)
            return@withContext false
        }
    }
    
    /** 获取模块路径 */
    fun getModulePath(): String = "$MAGISK_MODULES_PATH/$MODULE_ID_PRIMARY"
    
    /** 创建轻量级 Magisk 模块（不包含 .ko 文件，已安装应用时不包含 APK） */
    suspend fun createLightweightModule(): ModuleInstallResult = withContext(Dispatchers.IO) {
        try {
            val modulePath = getModulePath()
            
            android.util.Log.d("MagiskModuleManager", "Creating module at: $modulePath")
            
            // 使用 root shell 创建目录结构
            val createDirsResult = RootShell.exec("mkdir -p '$modulePath/common'")
            if (createDirsResult.code != 0) {
                android.util.Log.e("MagiskModuleManager", "Failed to create directories: ${createDirsResult.err}")
                return@withContext ModuleInstallResult(
                    success = false,
                    message = "创建目录失败: ${createDirsResult.err}"
                )
            }
            
            // 创建 module.prop（使用 root shell）
            val propResult = createModulePropWithRoot(modulePath)
            if (!propResult) {
                return@withContext ModuleInstallResult(
                    success = false,
                    message = "创建 module.prop 失败"
                )
            }
            
            // 创建 service.sh（使用 root shell）
            val serviceResult = createServiceScriptWithRoot(modulePath)
            if (!serviceResult) {
                return@withContext ModuleInstallResult(
                    success = false,
                    message = "创建 service.sh 失败"
                )
            }
            
            // 创建默认配置文件（使用 root shell）
            val configResult = createDefaultConfigWithRoot("$modulePath/common")
            if (!configResult) {
                return@withContext ModuleInstallResult(
                    success = false,
                    message = "创建配置文件失败"
                )
            }
            
            // 设置正确的权限
            val chmodResult = RootShell.exec("chmod -R 755 '$modulePath'")
            if (chmodResult.code != 0) {
                android.util.Log.w("MagiskModuleManager", "Failed to set permissions: ${chmodResult.err}")
            }
            
            android.util.Log.d("MagiskModuleManager", "Module created successfully")
            ModuleInstallResult(
                success = true,
                message = "轻量级模块创建成功（应用已安装）",
                modulePath = modulePath
            )
            
        } catch (e: Exception) {
            android.util.Log.e("MagiskModuleManager", "Exception creating module", e)
            ModuleInstallResult(
                success = false,
                message = "模块创建失败: ${e.message}"
            )
        }
    }
    
    /** 创建 module.prop 文件（使用 root shell） */
    private suspend fun createModulePropWithRoot(modulePath: String): Boolean {
        val content = """
            id=$MODULE_ID_PRIMARY
            name=$MODULE_NAME
            version=$MODULE_VERSION
            versionCode=200
            author=$MODULE_AUTHOR
            minMagisk=20.4
            description=$MODULE_DESCRIPTION
            
        """.trimIndent()
        
        val result = RootShell.exec("cat > '$modulePath/module.prop' << 'EOF'\n$content\nEOF")
        return result.code == 0
    }
    
    /** 创建 service.sh 脚本（使用 root shell） */
    private suspend fun createServiceScriptWithRoot(modulePath: String): Boolean {
        val content = """
            #!/system/bin/sh
            # 动态加载内核模块服务脚本（应用已安装版本）
            # 该脚本会从应用获取对应内核版本的 .ko 文件并加载
            
            MODDIR=${'$'}{0%/*}
            COMM_DIR="${'$'}MODDIR/common"
            CONF="${'$'}COMM_DIR/params.conf"
            FLAG_DISABLE="${'$'}MODDIR/disable_autoload"
            
            log() { echo "[batt-design-override][dynamic] ${'$'}*"; }
            logw() { echo "[batt-design-override][dynamic][warn] ${'$'}*"; }
            
            if [ -f "${'$'}FLAG_DISABLE" ]; then
                log "disable_autoload 存在，跳过加载"
                exit 0
            fi
            
            # 等待系统启动完成
            n=0; while [ ${'$'}n -lt 30 ]; do
                if [ "${'$'}(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then break; fi
                sleep 2; n=${'$'}((n+1))
            done
            
            # 检测内核版本
            KREL=${'$'}(uname -r 2>/dev/null)
            BASE_VER="${'$'}{KREL%%-*}"
            MAJOR_MINOR=${'$'}(echo "${'$'}BASE_VER" | cut -d. -f1,2)
            
            log "检测到内核版本: ${'$'}KREL (主版本: ${'$'}MAJOR_MINOR)"
            
            # 查找可用的 .ko 文件（按优先级排序）
            KO_CANDIDATES="batt_design_override-android*-${'$'}{MAJOR_MINOR}.ko batt_design_override-${'$'}{MAJOR_MINOR}.ko batt_design_override-${'$'}{MAJOR_MINOR%.*}.ko batt_design_override.ko"
            KO_SELECTED=""
            
            for pattern in ${'$'}KO_CANDIDATES; do
                for f in ${'$'}COMM_DIR/${'$'}pattern; do
                    if [ -f "${'$'}f" ]; then 
                        KO_SELECTED="${'$'}f"
                        break 2
                    fi
                done
            done
            
            if [ -z "${'$'}KO_SELECTED" ]; then
                log "未找到匹配的内核模块"
                # 创建内核版本信息文件供应用读取
                echo "${'$'}MAJOR_MINOR" > "${'$'}COMM_DIR/kernel_version"
                echo "${'$'}KREL" > "${'$'}COMM_DIR/kernel_release"
                
                log "请在应用中下载对应版本的内核模块"
                log "检测到的内核版本: ${'$'}MAJOR_MINOR"
                exit 1
            fi
            
            # 解析配置
            [ -f "${'$'}CONF" ] && . "${'$'}CONF"
            
            # 构建 insmod 参数
            ARGS=""
            [ -n "${'$'}MODEL_NAME" ] && ARGS="${'$'}ARGS model_name=${'$'}MODEL_NAME"
            [ -n "${'$'}DESIGN_UAH" ] && ARGS="${'$'}ARGS design_uah=${'$'}DESIGN_UAH"
            [ -n "${'$'}DESIGN_UWH" ] && ARGS="${'$'}ARGS design_uwh=${'$'}DESIGN_UWH"
            [ -n "${'$'}BATT_NAME" ] && ARGS="${'$'}ARGS batt_name=${'$'}BATT_NAME"
            [ -n "${'$'}OVERRIDE_ANY" ] && ARGS="${'$'}ARGS override_any=${'$'}OVERRIDE_ANY"
            [ -n "${'$'}VERBOSE" ] && ARGS="${'$'}ARGS verbose=${'$'}VERBOSE"
            
            ARGS=${'$'}(echo "${'$'}ARGS" | sed 's/^ *//')
            
            log "加载模块: ${'$'}KO_SELECTED 参数: ${'$'}ARGS"
            if ! insmod "${'$'}KO_SELECTED" ${'$'}ARGS 2>&1; then
                logw "insmod 失败"
                exit 1
            fi
            
            # 可选：加载充电参数模块
            CHG_PATTERNS="chg_param_override-android*-${'$'}{MAJOR_MINOR}.ko chg_param_override-${'$'}{MAJOR_MINOR}.ko chg_param_override.ko"
            CHG_KO=""
            
            for pattern in ${'$'}CHG_PATTERNS; do
                for f in ${'$'}COMM_DIR/${'$'}pattern; do
                    if [ -f "${'$'}f" ]; then 
                        CHG_KO="${'$'}f"
                        break 2
                    fi
                done
            done
            
            if [ -n "${'$'}CHG_KO" ]; then
                log "加载充电参数模块: ${'$'}CHG_KO"
                insmod "${'$'}CHG_KO" 2>/dev/null || logw "充电模块加载失败"
                
                # 应用充电参数
                PROC_PATH="/proc/chg_param_override"
                if [ -e "${'$'}PROC_PATH" ]; then
                    LINES=""
                    [ -n "${'$'}CHG_VMAX_UV" ] && LINES="${'$'}LINES\nvoltage_max=${'$'}CHG_VMAX_UV"
                    [ -n "${'$'}CHG_CCC_UA" ] && LINES="${'$'}LINES\nconstant_charge_current=${'$'}CHG_CCC_UA"
                    [ -n "${'$'}CHG_TERM_UA" ] && LINES="${'$'}LINES\ncharge_term_current=${'$'}CHG_TERM_UA"
                    [ -n "${'$'}CHG_ICL_UA" ] && LINES="${'$'}LINES\ninput_current_limit=${'$'}CHG_ICL_UA"
                    [ -n "${'$'}CHG_LIMIT_PERCENT" ] && LINES="${'$'}LINES\ncharge_control_limit=${'$'}CHG_LIMIT_PERCENT"
                    if [ "${'$'}{CHG_PD_VERIFED_ENABLED:-0}" = "1" ] && [ -n "${'$'}CHG_PD_VERIFED" ]; then
                        LINES="${'$'}LINES\npd_verifed=${'$'}CHG_PD_VERIFED"
                    fi
                    
                    LINES=${'$'}(echo "${'$'}LINES" | sed '/^${'$'}/d')
                    if [ -n "${'$'}LINES" ]; then
                        echo "${'$'}LINES" > "${'$'}PROC_PATH" || logw "写入充电参数失败"
                    fi
                fi
            fi
            
            log "模块加载完成"
            exit 0
            
        """.trimIndent()
        
        val result = RootShell.exec("cat > '$modulePath/service.sh' << 'EOF'\n$content\nEOF")
        if (result.code == 0) {
            // 设置执行权限
            val chmodResult = RootShell.exec("chmod 755 '$modulePath/service.sh'")
            return chmodResult.code == 0
        }
        return false
    }
    
    /** 创建默认配置文件（使用 root shell） */
    private suspend fun createDefaultConfigWithRoot(commonPath: String): Boolean {
        val content = """
            # 动态模块配置文件（应用已安装版本）
            # 此文件由应用自动管理，请勿手动编辑
            
            # 电池模块参数
            MODEL_NAME=DynamicBatt
            DESIGN_UAH=5000000
            OVERRIDE_ANY=1
            VERBOSE=1
            
            # 充电模块参数（可选）
            # CHG_VMAX_UV=4460000
            # CHG_CCC_UA=6000000
            # CHG_TERM_UA=200000
            # CHG_ICL_UA=1500000
            # CHG_LIMIT_PERCENT=85
            # CHG_PD_VERIFED_ENABLED=1
            # CHG_PD_VERIFED=1
            
            # 内核模块由应用动态管理，无需手动配置下载
            # 应用会根据检测到的内核版本自动下载对应的 .ko 文件
            
        """.trimIndent()
        
        val result = RootShell.exec("cat > '$commonPath/params.conf' << 'EOF'\n$content\nEOF")
        return result.code == 0
    }
    
    /** 安装内核模块文件到模块目录（智能文件名处理） */
    suspend fun installKernelModule(moduleName: String, koFilePath: String, version: String): Boolean = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("MagiskModuleManager", "Installing module: $moduleName from $koFilePath")
            
            val modulePath = getModulePath()
            val commonPath = "$modulePath/common"
            
            // 使用 root shell 检查目录是否存在
            val checkDirResult = RootShell.exec("[ -d '$modulePath' ] && [ -d '$commonPath' ]")
            if (checkDirResult.code != 0) {
                android.util.Log.e("MagiskModuleManager", "Module directories do not exist: $modulePath or $commonPath")
                return@withContext false
            }
            
            // 检查源文件是否存在
            val sourceFile = File(koFilePath)
            if (!sourceFile.exists()) {
                android.util.Log.e("MagiskModuleManager", "Source file does not exist: $koFilePath")
                return@withContext false
            }
            
            android.util.Log.d("MagiskModuleManager", "Source file size: ${sourceFile.length()} bytes")
            
            // 获取原始文件名
            val originalFileName = sourceFile.name
            
            // 创建多种文件名链接以支持不同的命名方式
            val fileNames = generateKernelModuleFileNames(moduleName, version, originalFileName)
            android.util.Log.d("MagiskModuleManager", "Generated file names: $fileNames")
            
            var success = false
            for (fileName in fileNames) {
                val targetPath = "$commonPath/$fileName"
                try {
                    android.util.Log.d("MagiskModuleManager", "Trying to install as: $fileName")
                    
                    // 使用 root shell 删除已存在的文件
                    val removeResult = RootShell.exec("rm -f '$targetPath'")
                    if (removeResult.code == 0) {
                        android.util.Log.d("MagiskModuleManager", "Removed existing file: $targetPath")
                    }
                    
                    // 使用 root shell 复制文件
                    val copyResult = RootShell.exec("cp '${sourceFile.absolutePath}' '$targetPath'")
                    if (copyResult.code != 0) {
                        android.util.Log.e("MagiskModuleManager", "Failed to copy file: ${copyResult.err}")
                        continue
                    }
                    
                    android.util.Log.d("MagiskModuleManager", "File copied successfully to: $targetPath")
                    
                    // 设置权限
                    val chmodResult = RootShell.exec("chmod 644 '$targetPath'")
                    android.util.Log.d("MagiskModuleManager", "Chmod result: code=${chmodResult.code}, out=${chmodResult.out}")
                    
                    if (chmodResult.code == 0) {
                        // 验证文件是否成功安装
                        val verifyResult = RootShell.exec("[ -f '$targetPath' ] && ls -la '$targetPath'")
                        if (verifyResult.code == 0) {
                            android.util.Log.d("MagiskModuleManager", "Successfully installed: $fileName")
                            android.util.Log.d("MagiskModuleManager", "File info: ${verifyResult.out}")
                            success = true
                            break
                        } else {
                            android.util.Log.w("MagiskModuleManager", "File verification failed for: $fileName")
                        }
                    } else {
                        android.util.Log.w("MagiskModuleManager", "Chmod failed for: $fileName - ${chmodResult.err}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MagiskModuleManager", "Failed to install as $fileName", e)
                    // 继续尝试其他文件名
                    continue
                }
            }
            
            android.util.Log.d("MagiskModuleManager", "Installation result: $success")
            success
        } catch (e: Exception) {
            android.util.Log.e("MagiskModuleManager", "Installation failed", e)
            false
        }
    }
    
    /** 生成内核模块的多种文件名 */
    private fun generateKernelModuleFileNames(moduleName: String, version: String, originalFileName: String): List<String> {
        val fileNames = mutableListOf<String>()
        
        // 1. 保持原始文件名（最高优先级）
        fileNames.add(originalFileName)
        
        // 2. 标准命名格式
        fileNames.add("$moduleName.ko")
        
        // 3. 带版本号的命名格式
        if (version.isNotEmpty()) {
            fileNames.add("$moduleName-$version.ko")
        }
        
        // 4. 从原始文件名中提取版本信息
        val extractedVersions = extractVersionFromFileName(originalFileName)
        for (extractedVersion in extractedVersions) {
            fileNames.add("$moduleName-$extractedVersion.ko")
        }
        
        return fileNames.distinct()
    }
    
    /** 从文件名中提取版本信息 */
    private fun extractVersionFromFileName(fileName: String): List<String> {
        val versions = mutableListOf<String>()
        val nameWithoutExtension = fileName.removeSuffix(".ko")
        
        // 匹配各种版本格式
        val versionPatterns = listOf(
            Regex("android(\\d+)-(\\d+\\.\\d+)"),  // android13-5.15
            Regex("(\\d+\\.\\d+\\.\\d+)"),         // 1.2.1
            Regex("(\\d+\\.\\d+)"),                // 5.15
            Regex("v(\\d+\\.\\d+\\.\\d+)"),        // v1.2.1
            Regex("v(\\d+\\.\\d+)")                // v1.2
        )
        
        for (pattern in versionPatterns) {
            val matches = pattern.findAll(nameWithoutExtension)
            for (match in matches) {
                if (match.groupValues.size > 1) {
                    versions.addAll(match.groupValues.drop(1))
                }
            }
        }
        
        return versions.distinct()
    }
    
    /** 卸载模块 */
    suspend fun uninstallModule(): ModuleInstallResult = withContext(Dispatchers.IO) {
        try {
            val modulePath = getModulePath()
            val result = RootShell.exec("rm -rf $modulePath")
            
            if (result.code == 0) {
                ModuleInstallResult(
                    success = true,
                    message = "模块卸载成功，重启后生效"
                )
            } else {
                ModuleInstallResult(
                    success = false,
                    message = "模块卸载失败: ${result.err}"
                )
            }
        } catch (e: Exception) {
            ModuleInstallResult(
                success = false,
                message = "模块卸载异常: ${e.message}"
            )
        }
    }
    
    /** 重启模块服务 */
    suspend fun restartModuleService(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modulePath = getModulePath()
            val serviceScript = File(modulePath, "service.sh")
            
            if (serviceScript.exists()) {
                val result = RootShell.exec("sh ${serviceScript.absolutePath}")
                return@withContext result.code == 0
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /** 获取模块状态信息（自动选择可用模块目录用于读取 common/） */
    suspend fun getModuleStatus(): Map<String, String> = withContext(Dispatchers.IO) {
        val status = mutableMapOf<String, String>()
        
        try {
            status["magisk_available"] = if (isMagiskAvailable()) "是" else "否"
            status["module_installed"] = if (isModuleInstalled()) "是" else "否"
            
            // 选择现存的模块目录（主 ID 优先，其次兼容 ID）
            val primary = File(getModulePath())
            val fallback = MODULE_ID_ALTERNATIVES.map { File("$MAGISK_MODULES_PATH/$it") }.firstOrNull { it.exists() }
            val moduleDir: File? = if (primary.exists()) primary else fallback
            if (moduleDir != null && moduleDir.exists()) {
                status["module_path"] = moduleDir.absolutePath

                val commonDir = File(moduleDir, "common")
                val koFiles = commonDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".ko")
                }

                status["available_modules"] = koFiles?.joinToString(", ") { it.name } ?: "无"

                // 检查内核版本信息
                val kernelVersionFile = File(commonDir, "kernel_version")
                if (kernelVersionFile.exists()) {
                    status["detected_kernel"] = kernelVersionFile.readText().trim()
                }
            } else {
                // 无法直接读取文件系统时，尝试通过 root shell 列出 common 目录中的 .ko 文件名
                try {
                    val ids = listOf(MODULE_ID_PRIMARY) + MODULE_ID_ALTERNATIVES
                    val script = ids.joinToString("; ") { id ->
                        "if [ -d $MAGISK_MODULES_PATH/$id/common ]; then ls -1 $MAGISK_MODULES_PATH/$id/common/*.ko 2>/dev/null; fi"
                    }
                    val r = RootShell.exec("sh -c \"$script\"")
                    if (r.code == 0 && r.out.isNotBlank()) {
                        val files = r.out.split('\n').filter { it.isNotBlank() }.map { File(it).name }
                        if (files.isNotEmpty()) {
                            status["available_modules"] = files.joinToString(", ")
                        }
                    }
                } catch (_: Throwable) { /* ignore */ }
            }
            
        } catch (e: Exception) {
            status["error"] = e.message ?: "未知错误"
        }
        
        status
    }
}