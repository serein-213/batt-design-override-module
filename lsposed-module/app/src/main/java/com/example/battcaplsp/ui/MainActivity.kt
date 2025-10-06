package com.override.battcaplsp.ui

import android.app.Activity
import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import com.override.battcaplsp.LaunchTrace
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import android.widget.Toast
import com.override.battcaplsp.core.RootShell
import com.override.battcaplsp.core.ModuleManager
import com.override.battcaplsp.ui.theme.AppTheme
import com.override.battcaplsp.core.ParamRepository
import com.override.battcaplsp.core.truncateMiddle
import com.override.battcaplsp.ui.StatusBadge
import com.override.battcaplsp.ui.stripStatusPrefix
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.override.battcaplsp.core.OpEvents
import com.override.battcaplsp.BuildConfig

class MainActivity: ComponentActivity() {
    private val battMgr by lazy { ModuleManager() }
    private val battRepo by lazy { ParamRepository(this, battMgr) }
    private val chgMgr by lazy { com.override.battcaplsp.core.ChgModuleManager() }
    private val chgRepo by lazy { com.override.battcaplsp.core.ChgParamRepository(this, chgMgr) }
    private val hookRepo by lazy { com.override.battcaplsp.core.HookSettingsRepository(this) }
    private val downloader by lazy { com.override.battcaplsp.core.KernelModuleDownloader(this) }
    private val magiskManager by lazy { com.override.battcaplsp.core.MagiskModuleManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        LaunchTrace.markActivityCreateStart()
        super.onCreate(savedInstanceState)
        LaunchTrace.markSetContentStart()
        setContent {
            SideEffect { LaunchTrace.markFirstCompose() }
            AppTheme { AppScaffold() }
        }
        
        // 预热Shell连接并检查root权限
        lifecycleScope.launch {
            // 预热Shell连接，提前初始化
            try {
                RootShell.exec("echo 'init'")
            } catch (e: Exception) {
                // 忽略预热失败
            }
            
            // 延迟一点时间确保Shell完全初始化
            delay(500)
            checkAndShowRootStatus()
            // 启动交互完成（主界面主要模块加载刷新后触发）
            LaunchTrace.markUiInteractive()
        }
    }
    
    private suspend fun checkAndShowRootStatus() {
        // 首次启动时强制刷新root状态
        val rootStatus = RootShell.getRootStatus(forceRefresh = true)
            val message = if (rootStatus.available) {
            "SUCCESS:Root 权限已获取\n模块功能完全可用"
        } else {
            "WARN:Root 权限未获取\n${rootStatus.message}\n\n如果刚授予权限，请稍等片刻后重试"
        }
        
        // 在主线程显示 Toast
        runOnUiThread {
            Toast.makeText(this, message.stripStatusPrefix(), Toast.LENGTH_LONG).show()
        }
    }

    private fun isMiuiDevice(): Boolean {
        return try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            val v = get.invoke(null, "ro.miui.ui.version.name", "") as String
            v.isNotEmpty() || android.os.Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true)
        } catch (_: Throwable) {
            android.os.Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true)
        }
    }

    private fun formatModuleLoadResult(res: RootShell.ExecResult): String {
        return ResultFormatter.formatModuleLoadResult(res)
    }

    private fun formatModuleUnloadResult(res: RootShell.ExecResult): String {
        return ResultFormatter.formatModuleUnloadResult(res)
    }

    @Composable
    private fun getResultColor(result: String): androidx.compose.ui.graphics.Color {
        return ResultFormatter.getResultColor(result)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun AppScaffold() {
        var tab by remember { mutableStateOf(0) }
        // 基础 tabs
    val baseTabs = listOf("状态", "电池", "充电", "设置")
        // 如果开启内部 debug 面板，添加一个调试 tab
        val tabs = if (BuildConfig.ENABLE_INTERNAL_DEBUG_PANEL) baseTabs + "调试" else baseTabs
        Scaffold(topBar = {
            // 仅保留一层标题栏：移除重复标题，直接用 TabRow 作为顶栏内容
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab==i, onClick = { tab = i }, text = { Text(t) })
                }
            }
        }) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> StatusScreen(moduleManager = battMgr)
                    1 -> BatteryScreen()
                    2 -> ChargingScreen(repo = chgRepo, mgr = chgMgr)
                    3 -> HookSettingsScreen(repo = hookRepo)
                    4 -> if (BuildConfig.ENABLE_INTERNAL_DEBUG_PANEL) DebugPanel(moduleManager = battMgr) else StatusScreen(moduleManager = battMgr)
                    else -> StatusScreen(moduleManager = battMgr)
                }
            }
        }
    }

    @Composable private fun BatteryScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
    val uiState by battRepo.flow.collectAsState(initial = com.override.battcaplsp.core.UiState())
    LaunchedEffect(Unit) { battRepo.refresh() }
        var battName by remember { mutableStateOf(TextFieldValue(uiState.battName)) }
        var designUah by remember { mutableStateOf(TextFieldValue(if (uiState.designUah>0) (uiState.designUah/1000.0).toString() else "")) }
        var designUwh by remember { mutableStateOf(TextFieldValue(if (uiState.designUwh>0) (uiState.designUwh/1000000.0).toString() else "")) }
        var modelName by remember { mutableStateOf(TextFieldValue(uiState.modelName)) }
        var koPath by remember { mutableStateOf(TextFieldValue(uiState.koPath)) }
        var overrideAny by remember { mutableStateOf(uiState.overrideAny) }
        var verbose by remember { mutableStateOf(uiState.verbose) }
        var kernelMap by remember { mutableStateOf<Map<String,String?>>(emptyMap()) }
        var opResult by remember { mutableStateOf("") }
        var kernelLog by remember { mutableStateOf("") }

        LaunchedEffect(uiState.moduleLoaded) {
            if (uiState.moduleLoaded) {
                kernelMap = battMgr.readAll()
                // Prefill from kernel if present
                kernelMap["design_uah"]?.toLongOrNull()?.let { designUah = TextFieldValue((it/1000.0).toString()) }
                kernelMap["design_uwh"]?.toLongOrNull()?.let { designUwh = TextFieldValue((it/1000000.0).toString()) }
                kernelMap["batt_name"]?.let { battName = TextFieldValue(it) }
                kernelMap["model_name"]?.let { modelName = TextFieldValue(it) }
                kernelMap["override_any"]?.let { overrideAny = it == "Y" || it == "1" || it.equals("true", true) }
                kernelMap["verbose"]?.let { verbose = it == "Y" || it == "1" || it.equals("true", true) }
            }
        }

        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Text("电池模块: ${if (uiState.moduleLoaded) "已加载" else "未加载"}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(battName, { battName = it }, label={ Text("batt_name") }, supportingText={ Text("目标电池 power_supply 名称，默认 battery") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                // design_capacity 和 design_energy 并排放置
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = designUah, 
                        onValueChange = { designUah = it }, 
                        label = { Text("design_capacity (mAh)") }, 
                        supportingText = { Text("设计容量，单位毫安时 mAh") }, 
                        modifier = Modifier.weight(1.1f).padding(end = 4.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = designUwh, 
                        onValueChange = { designUwh = it }, 
                        label = { Text("design_energy (Wh)") }, 
                        supportingText = { Text("设计能量，单位瓦时 Wh") }, 
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        singleLine = true
                    )
                }
                OutlinedTextField(modelName, { modelName = it }, label={ Text("model_name") }, supportingText={ Text("型号字符串，仅用于展示") }, modifier=Modifier.fillMaxWidth(), singleLine = true)
                // 隐藏 .ko 路径的可视展示，固定路径由内部保存
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(overrideAny, { overrideAny = it })
                    Text("override_any")
                    Spacer(Modifier.width(16.dp))
                    Checkbox(verbose, { verbose = it })
                    Text("verbose")
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button({
                        scope.launch {
                            try {
                                val km = battMgr.readAll()
                                kernelMap = km
                                km["design_uah"]?.toLongOrNull()?.let { designUah = TextFieldValue((it/1000.0).toString()) }
                                km["design_uwh"]?.toLongOrNull()?.let { designUwh = TextFieldValue((it/1000000.0).toString()) }
                                km["batt_name"]?.let { battName = TextFieldValue(it) }
                                km["model_name"]?.let { modelName = TextFieldValue(it) }
                                km["override_any"]?.let { overrideAny = it == "Y" || it == "1" || it.equals("true", true) }
                                km["verbose"]?.let { verbose = it == "Y" || it == "1" || it.equals("true", true) }
                                opResult = "SUCCESS:内核参数读取成功"
                                OpEvents.success("刷新参数成功")
                            } catch (t: Throwable) {
                                opResult = "ERROR:读取失败 ${t.message}"; OpEvents.error("刷新参数失败: ${t.message}")
                            }
                        }
                    }, enabled = uiState.moduleLoaded) { Text("刷新参数") }
                    Spacer(Modifier.width(8.dp))
                    Button({
                        scope.launch {
                            try {
                                val mAhStr = designUah.text.trim()
                                val whStr = designUwh.text.trim()
                                val uahVal = ((mAhStr.ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                                val uwhVal = ((whStr.ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000000).toLong()
                                if (uahVal < 0 || uahVal > 20000000L) {
                                    snackbarHostState.showSnackbar("设计容量(mAh)超出范围或格式错误 (0~20000mAh)")
                                    OpEvents.warn("设计容量异常: $uahVal")
                                    return@launch
                                }
                                if (uwhVal < 0 || uwhVal > 100000000L) {
                                    snackbarHostState.showSnackbar("设计能量(Wh)超出范围或格式错误 (0~100Wh)")
                                    OpEvents.warn("设计能量异常: $uwhVal")
                                    return@launch
                                }
                                battRepo.update { it.copy(
                                    battName = battName.text.trim(),
                                    designUah = uahVal,
                                    designUwh = uwhVal,
                                    modelName = modelName.text.trim(),
                                    overrideAny = overrideAny,
                                    verbose = verbose,
                                    koPath = koPath.text.trim()
                                ) }
                                val tasks = listOf(
                                    Pair("batt_name", battName.text.trim()),
                                    Pair("design_uah", uahVal.toString()),
                                    Pair("design_uwh", uwhVal.toString()),
                                    Pair("model_name", modelName.text.trim()),
                                    Pair("override_any", if (overrideAny) "1" else "0"),
                                    Pair("verbose", if (verbose) "1" else "0")
                                )
                                var okCnt = 0
                                for ((k,v) in tasks) if (v.isNotEmpty()) if (battMgr.writeParam(k, v)) okCnt++
                                com.override.battcaplsp.core.ConfigSync.syncBatt(
                                    context,
                                    battName.text.trim(),
                                    uahVal,
                                    uwhVal,
                                    modelName.text.trim(),
                                    overrideAny,
                                    verbose
                                )
                                kernelMap = battMgr.readAll()
                                val msg = if (okCnt > 0) "SUCCESS:保存并应用完成 (成功 $okCnt 项)" else "WARN:保存完成，但应用失败"
                                opResult = msg
                                if (okCnt > 0) OpEvents.success("保存并应用成功 ($okCnt)") else OpEvents.warn("保存写入内核失败")
                            } catch (t: Throwable) {
                                opResult = "ERROR:保存失败 ${t.message}"; OpEvents.error("保存失败: ${t.message}")
                            }
                        }
                    }, enabled = uiState.moduleLoaded) { Text("保存并应用") }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    // 直接保留“传统加载模块”，去掉单独的“安全加载模块”按钮逻辑
                    Button({
                        scope.launch {
                            try {
                                val conf = com.override.battcaplsp.core.ConfigSync.readConf(context)
                                val battFromConf = conf["BATT_NAME"]?.ifBlank { null }
                                val duahFromConf = conf["DESIGN_UAH"]?.toLongOrNull()?.takeIf { it > 0 }
                                val duwhFromConf = conf["DESIGN_UWH"]?.toLongOrNull()?.takeIf { it > 0 }
                                val modelFromConf = conf["MODEL_NAME"]?.ifBlank { null }
                                val overrideFromConf = conf["OVERRIDE_ANY"]?.let { if (it == "1" || it.equals("true", true)) "1" else null }
                                val verboseFromConf = conf["VERBOSE"]?.let { if (it == "1" || it.equals("true", true) || it.equals("Y", true)) "1" else "0" }
                                val mAhStr2 = designUah.text.trim(); val whStr2 = designUwh.text.trim()
                                val designUahMicro = duahFromConf?.toString() ?: ((mAhStr2.ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString()
                                val designUwhMicro = duwhFromConf?.toString() ?: ((whStr2.ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000000).toLong().toString()
                                val res = battMgr.loadModuleWithSmartNaming("batt_design_override", mapOf(
                                    "design_uah" to designUahMicro.ifEmpty { null },
                                    "design_uwh" to designUwhMicro.ifEmpty { null },
                                    "model_name" to (modelFromConf ?: modelName.text.trim().ifEmpty { null }),
                                    "batt_name" to (battFromConf ?: battName.text.trim().ifEmpty { null }),
                                    "override_any" to (overrideFromConf ?: (if (overrideAny) "1" else null)),
                                    "verbose" to (verboseFromConf ?: (if (verbose) "1" else "0"))
                                ))
                                battRepo.refresh()
                                opResult = formatModuleLoadResult(res)
                                if (res.code == 0) OpEvents.success("加载模块成功") else OpEvents.error("加载模块失败: ${res.err.take(80)}")
                            } catch (t: Throwable) {
                                opResult = "ERROR:加载异常 ${t.message}"; OpEvents.error("加载异常: ${t.message}")
                            }
                        }
                    }, enabled = !uiState.moduleLoaded) { Text("加载模块") }
                    Spacer(Modifier.width(8.dp))
                    Button({
                        scope.launch {
                            try {
                                val res = battMgr.unload(); battRepo.refresh(); opResult = formatModuleUnloadResult(res)
                                if (res.code == 0) OpEvents.success("卸载模块成功") else OpEvents.error("卸载失败: ${res.err.take(60)}")
                            } catch (t: Throwable) {
                                opResult = "ERROR:卸载异常 ${t.message}"; OpEvents.error("卸载异常: ${t.message}")
                            }
                        }
                    }, enabled = uiState.moduleLoaded) { Text("卸载模块") }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button({
                        scope.launch {
                            try {
                                val cmd = "(dmesg | grep -E 'batt_design_override' || true)"
                                var res = RootShell.exec(cmd)
                                var lines = res.out.split('\n').filter { it.isNotBlank() }
                                if (lines.isEmpty()) {
                                    val fb = RootShell.exec("logcat -b kernel -d | grep -E 'batt_design_override' || true")
                                    if (fb.out.isNotBlank()) {
                                        res = fb
                                        lines = fb.out.split('\n').filter { it.isNotBlank() }
                                    }
                                }
                                if (lines.isNotEmpty()) {
                                    val tail = if (lines.size > 300) lines.takeLast(300) else lines
                                    kernelLog = tail.joinToString("\n")
                                    opResult = "SUCCESS:内核日志读取成功 (${tail.size} 行, 显示末尾)"; OpEvents.success("读取日志 ${tail.size} 行")
                                } else {
                                    kernelLog = ""
                                    opResult = if (res.err.isNotBlank()) {
                                        "WARN:未获取到匹配日志 (stderr: ${com.override.battcaplsp.core.TextAbbrev.middle(res.err,120)})".also { OpEvents.warn("日志为空(含stderr)") }
                                    } else {
                                        "INFO:没有匹配到包含 batt_design_override 的日志".also { OpEvents.info("日志无匹配") }
                                    }
                                }
                            } catch (t: Throwable) {
                                kernelLog = ""; opResult = "ERROR:日志读取异常 ${t.message}"; OpEvents.error("日志读取异常: ${t.message}")
                            }
                        }
                    }) { Text("查看内核日志") }
                }
                Spacer(Modifier.height(8.dp))
                if (kernelLog.isNotEmpty()) {
                    LogViewer(
                        title = "电池模块日志 (batt_design_override)",
                        logText = kernelLog,
                        onClear = { kernelLog = "" },
                        maxHeight = 320
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text("内核参数：", style = MaterialTheme.typography.titleSmall)
                for ((k,v) in kernelMap) Text("- $k = ${v ?: "<null>"}")
                Spacer(Modifier.height(12.dp))
                if (opResult.isNotBlank()) {
                    StatusBadge(opResult, showLabel = "结果:")
                }
        }
    }
}
