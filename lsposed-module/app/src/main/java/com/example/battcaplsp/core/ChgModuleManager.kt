package com.override.battcaplsp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Operations for chg_param_override kernel module and PD helper. */
class ChgModuleManager(
    private val moduleName: String = "chg_param_override",
    private val procPath: String = "/proc/chg_param_override"
) {
    private val battMgr by lazy { ModuleManager() }
    
    suspend fun isLoaded(): Boolean = withContext(Dispatchers.IO) {
        // 先尝试直接文件访问
        if (File(procPath).exists()) {
            return@withContext true
        }
        
        // 如果直接访问失败，使用 root shell 检测
        return@withContext try {
            val result = RootShell.exec("[ -e '$procPath' ] && echo 'exists' || echo 'not_exists'")
            result.code == 0 && result.out.trim() == "exists"
        } catch (e: Exception) {
            // 最后尝试通过 lsmod 检测
            try {
                val lsmodResult = RootShell.exec("lsmod | grep '^$moduleName ' | wc -l")
                lsmodResult.code == 0 && lsmodResult.out.trim().toIntOrNull() ?: 0 > 0
            } catch (e2: Exception) {
                false
            }
        }
    }
    
    /** 智能查找并加载充电模块 */
    suspend fun loadModuleWithSmartNaming(
        targetBatt: String? = null, 
        targetUsb: String? = null, 
        verbose: Boolean = false,
        searchPaths: List<String> = getDefaultSearchPaths()
    ): RootShell.ExecResult = withContext(Dispatchers.IO) {
        val koPath = battMgr.findAvailableKernelModule(moduleName, searchPaths)
        if (koPath == null) {
            return@withContext RootShell.ExecResult(1, "", "未找到可用的充电模块文件: $moduleName")
        }
        
        return@withContext load(koPath, targetBatt, targetUsb, verbose)
    }
    
    /** 获取充电模块的默认搜索路径 */
    private fun getDefaultSearchPaths(): List<String> {
        return listOf(
            "/data/adb/modules/batt-design-override/common",
            "/data/adb/modules/batt-design-override-dynamic/common",
            "/data/adb/modules/chg-param-override/common",
            "/system/lib/modules",
            "/vendor/lib/modules",
            "/data/local/tmp/modules"
        )
    }

    /** Batch write k=v lines to /proc/chg_param_override. Empty values are skipped. */
    suspend fun applyBatch(params: Map<String, String?>): RootShell.ExecResult = withContext(Dispatchers.IO) {
        fun sanitize(raw: String): String = raw
            .replace("\r", " ")
            .replace("\n", " ")          // 真换行
            .replace("\\n", " ")         // 字面 \n
            .replace(Regex("\u0000"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)

        val cleaned = params.entries
            .filter { !it.value.isNullOrBlank() }
            .associate { it.key to sanitize(it.value!!) }
            .filter { it.value.isNotBlank() }

        if (cleaned.isEmpty()) return@withContext RootShell.exec(":")

        val builder = buildString {
            cleaned.forEach { (k,v) -> append(k).append('=').append(v).append('\n') }
        }
        // 确保末尾有换行，便于内核逐行解析
        val payload = if (builder.endsWith("\n")) builder else builder + "\n"
        RootShell.exec("printf %s "+RootShell.shellArg(payload)+" | tee "+procPath)
    }

    suspend fun load(koPath: String, targetBatt: String?, targetUsb: String?, verbose: Boolean): RootShell.ExecResult {
        // 先检查模块是否已经加载
        if (isLoaded()) {
            return RootShell.ExecResult(1, "", "模块 $moduleName 已经加载，请先卸载")
        }
        
        val args = buildString {
            if (!targetBatt.isNullOrBlank()) append(" target_batt=").append(shellQuoteIfNeeded(targetBatt))
            if (!targetUsb.isNullOrBlank()) append(" target_usb=").append(shellQuoteIfNeeded(targetUsb))
            if (verbose) append(" verbose=1")
        }
        return RootShell.exec("insmod "+shellQuoteIfNeeded(koPath)+args)
    }

    suspend fun unload(): RootShell.ExecResult = RootShell.exec("rmmod $moduleName")

    // -------- PD helper via userspace script --------
    private val pdScript = "/data/local/tmp/pd_service.sh"
    private val pdPid = "/data/local/tmp/pd_service.pid"

    suspend fun deployPdHelper(desired: Int): RootShell.ExecResult {
        android.util.Log.d("ChgModuleManager", "开始部署PD守护进程，目标值: $desired")
        
        val script = """
        #!/system/bin/sh
        DESIRED_PD=${desired}
        PD_NODE=/sys/class/qcom-battery/pd_verifed
        USB_ONLINE=/sys/class/power_supply/usb/online
        LOG_FILE=/data/local/tmp/pd_service.log
        
        # 记录启动日志
        echo "${'$'}(date): PD守护进程启动，目标值: ${'$'}DESIRED_PD" >> ${'$'}LOG_FILE
        
        set_pd(){ 
            if [ -e "${'$'}PD_NODE" ]; then 
                echo "${'$'}DESIRED_PD" > "${'$'}PD_NODE" 2>/dev/null
                echo "${'$'}(date): 设置PD为 ${'$'}DESIRED_PD" >> ${'$'}LOG_FILE
            else
                echo "${'$'}(date): PD节点不存在: ${'$'}PD_NODE" >> ${'$'}LOG_FILE
            fi
        }
        
        last=-1
        set_pd
        
        while true; do
            online=$(cat "${'$'}USB_ONLINE" 2>/dev/null)
            if [ -n "${'$'}online" ] && [ "${'$'}online" != "${'$'}last" ]; then
                echo "${'$'}(date): USB状态变化: ${'$'}last -> ${'$'}online" >> ${'$'}LOG_FILE
                set_pd
                last="${'$'}online"
            fi
            sleep 2
        done
        """.trimIndent()
        
        val cmds = listOf(
            "cat > $pdScript <<'EOF'\n$script\nEOF",
            "chmod 755 $pdScript"
        ).joinToString(" && ")
        
        android.util.Log.d("ChgModuleManager", "执行部署命令: $cmds")
        val result = RootShell.exec(cmds)
        android.util.Log.d("ChgModuleManager", "部署结果: code=${result.code}, out=${result.out}, err=${result.err}")
        
        return result
    }

    suspend fun startPdHelper(): RootShell.ExecResult {
        android.util.Log.d("ChgModuleManager", "开始启动PD守护进程")
        
        // 先检查脚本是否存在
        val checkScript = RootShell.exec("[ -f $pdScript ] && echo 'exists' || echo 'not_exists'")
        android.util.Log.d("ChgModuleManager", "脚本检查结果: ${checkScript.out}")
        
        if (checkScript.out.trim() != "exists") {
            android.util.Log.e("ChgModuleManager", "PD脚本不存在: $pdScript")
            return RootShell.ExecResult(1, "", "PD脚本不存在，请先部署")
        }
        
        // 停止可能正在运行的进程
        stopPdHelper()
        
        // 启动新进程
        val cmd = "nohup $pdScript >/dev/null 2>&1 & echo \$! > $pdPid"
        android.util.Log.d("ChgModuleManager", "执行启动命令: $cmd")
        
        val result = RootShell.exec(cmd)
        android.util.Log.d("ChgModuleManager", "启动结果: code=${result.code}, out=${result.out}, err=${result.err}")
        
        // 验证进程是否启动成功
        if (result.code == 0) {
            val pidCheck = RootShell.exec("[ -f $pdPid ] && cat $pdPid || echo 'no_pid'")
            android.util.Log.d("ChgModuleManager", "PID文件检查: ${pidCheck.out}")
            
            if (pidCheck.out.trim() != "no_pid") {
                val pid = pidCheck.out.trim()
                val processCheck = RootShell.exec("ps | grep '^$pid ' || echo 'not_running'")
                android.util.Log.d("ChgModuleManager", "进程检查: ${processCheck.out}")
            }
        }
        
        return result
    }

    suspend fun stopPdHelper(): RootShell.ExecResult {
        android.util.Log.d("ChgModuleManager", "停止PD守护进程")
        
        val cmd = "if [ -f $pdPid ]; then kill $(cat $pdPid) 2>/dev/null; rm -f $pdPid; echo 'stopped'; else echo 'no_pid_file'; fi"
        android.util.Log.d("ChgModuleManager", "执行停止命令: $cmd")
        
        val result = RootShell.exec(cmd)
        android.util.Log.d("ChgModuleManager", "停止结果: code=${result.code}, out=${result.out}, err=${result.err}")
        
        return result
    }
    
    /** 检查PD守护进程状态 */
    suspend fun checkPdHelperStatus(): String {
        return try {
            // 检查PID文件
            val pidCheck = RootShell.exec("[ -f $pdPid ] && cat $pdPid || echo 'no_pid'")
            if (pidCheck.out.trim() == "no_pid") {
                return "未运行"
            }
            
            val pid = pidCheck.out.trim()
            
            // 检查进程是否还在运行
            val processCheck = RootShell.exec("ps | grep '^$pid ' || echo 'not_running'")
            if (processCheck.out.trim() == "not_running") {
                return "PID文件存在但进程未运行"
            }
            
            // 检查日志文件
            val logCheck = RootShell.exec("[ -f /data/local/tmp/pd_service.log ] && tail -3 /data/local/tmp/pd_service.log || echo 'no_log'")
            val logInfo = if (logCheck.out.trim() != "no_log") {
                "\n最近日志:\n${logCheck.out}"
            } else {
                ""
            }
            
            "运行中 (PID: $pid)$logInfo"
        } catch (e: Exception) {
            "检查状态时出错: ${e.message}"
        }
    }

    /** Read current values from /proc/chg_param_override and return as a map. */
    suspend fun readCurrent(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!isLoaded()) return@withContext emptyMap()
        val r = RootShell.exec("cat "+procPath+" 2>/dev/null || true")
        if (r.code != 0 || r.out.isBlank()) return@withContext emptyMap()
        val map = mutableMapOf<String, String>()
        val lines = r.out.split('\n')
        lines.forEach { rawLine ->
            val ln = rawLine.trim()
            if (ln.isEmpty()) return@forEach
            if (ln.startsWith("batt=") && ln.contains(" usb=")) {
                ln.split(' ').forEach { token ->
                    val idx = token.indexOf('=')
                    if (idx > 0) map[token.substring(0, idx)] = token.substring(idx + 1)
                }
                return@forEach
            }
            val idx = ln.indexOf('=')
            if (idx > 0) {
                val k = ln.substring(0, idx)
                var v = ln.substring(idx + 1)
                // 如果值里仍然混入其它行（异常情况），截断到第一个换行或出现第二个 key 样式片段前
                val secondKeyMatch = Regex("\\b(voltage_max|ccc|term|icl|charge_limit|auto_reapply)=").find(v)
                if (secondKeyMatch != null) {
                    v = v.substring(0, secondKeyMatch.range.first).trim()
                }
                v = v.replace("\r", " ").replace('\u0000', ' ').replace(Regex("\\s+"), " ").trim()
                map[k] = v
            }
        }
        return@withContext map
    }

    private fun shellQuoteIfNeeded(s: String): String {
        return if (s.matches(Regex("^[A-Za-z0-9._/:=-]+$"))) s else RootShell.shellArg(s)
    }
}


