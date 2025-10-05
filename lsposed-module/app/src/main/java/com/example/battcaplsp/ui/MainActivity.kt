package com.override.battcaplsp.ui

import android.app.Activity
import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity: ComponentActivity() {
    private val battMgr by lazy { ModuleManager() }
    private val battRepo by lazy { ParamRepository(this, battMgr) }
    private val chgMgr by lazy { com.override.battcaplsp.core.ChgModuleManager() }
    private val chgRepo by lazy { com.override.battcaplsp.core.ChgParamRepository(this, chgMgr) }
    private val hookRepo by lazy { com.override.battcaplsp.core.HookSettingsRepository(this) }
    private val downloader by lazy { com.override.battcaplsp.core.KernelModuleDownloader(this) }
    private val magiskManager by lazy { com.override.battcaplsp.core.MagiskModuleManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AppScaffold() } }
        
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
        }
    }
    
    private suspend fun checkAndShowRootStatus() {
        // 首次启动时强制刷新root状态
        val rootStatus = RootShell.getRootStatus(forceRefresh = true)
        val message = if (rootStatus.available) {
            "✅ Root 权限已获取\n模块功能完全可用"
        } else {
            "⚠️ Root 权限未获取\n${rootStatus.message}\n\n如果刚授予权限，请稍等片刻后重试"
        }
        
        // 在主线程显示 Toast
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
        val tabs = listOf("电池", "充电", "设置")
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
                    0 -> BatteryScreen()
                    1 -> ChargingScreen(repo = chgRepo, mgr = chgMgr)
                    2 -> HookSettingsScreen(repo = hookRepo)
                    else -> BatteryScreen()
                }
            }
        }
    }

    @Composable private fun BatteryScreen() {
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val uiState by battRepo.flow.collectAsState(initial = com.override.battcaplsp.core.UiState())
        var battName by remember { mutableStateOf(TextFieldValue(uiState.battName)) }
        var designUah by remember { mutableStateOf(TextFieldValue(if (uiState.designUah>0) (uiState.designUah/1000.0).toString() else "")) }
        var designUwh by remember { mutableStateOf(TextFieldValue(if (uiState.designUwh>0) (uiState.designUwh/1000000.0).toString() else "")) }
        var modelName by remember { mutableStateOf(TextFieldValue(uiState.modelName)) }
        var koPath by remember { mutableStateOf(TextFieldValue(uiState.koPath)) }
        var overrideAny by remember { mutableStateOf(uiState.overrideAny) }
        var verbose by remember { mutableStateOf(uiState.verbose) }
        var kernelMap by remember { mutableStateOf<Map<String,String?>>(emptyMap()) }
        var opResult by remember { mutableStateOf("") }

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
                            val km = battMgr.readAll()
                            kernelMap = km
                            km["design_uah"]?.toLongOrNull()?.let { designUah = TextFieldValue((it/1000.0).toString()) }
                            km["design_uwh"]?.toLongOrNull()?.let { designUwh = TextFieldValue((it/1000000.0).toString()) }
                            km["batt_name"]?.let { battName = TextFieldValue(it) }
                            km["model_name"]?.let { modelName = TextFieldValue(it) }
                            km["override_any"]?.let { overrideAny = it == "Y" || it == "1" || it.equals("true", true) }
                            km["verbose"]?.let { verbose = it == "Y" || it == "1" || it.equals("true", true) }
                            opResult = "✅ 内核参数读取成功"
                        }
                    }, enabled = uiState.moduleLoaded) { Text("刷新参数") }
                    Spacer(Modifier.width(8.dp))
                    Button({
                        scope.launch {
                            // 1. 先进行输入校验
                            val mAhStr = designUah.text.trim()
                            val whStr = designUwh.text.trim()
                            val uahVal = ((mAhStr.ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                            val uwhVal = ((whStr.ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000000).toLong()
                            if (uahVal < 0 || uahVal > 20000000L) {
                                snackbarHostState.showSnackbar("设计容量(mAh)超出范围或格式错误 (0~20000mAh)")
                                return@launch
                            }
                            if (uwhVal < 0 || uwhVal > 100000000L) {
                                snackbarHostState.showSnackbar("设计能量(Wh)超出范围或格式错误 (0~100Wh)")
                                return@launch
                            }
                            
                            // 2. 保存到 DataStore (应用内持久化)
                            battRepo.update { it.copy(
                                battName = battName.text.trim(),
                                designUah = uahVal,
                                designUwh = uwhVal,
                                modelName = modelName.text.trim(),
                                overrideAny = overrideAny,
                                verbose = verbose,
                                koPath = koPath.text.trim()
                            ) }
                            
                            // 3. 写入内核模块 (立即生效)
                            val tasks = listOf(
                                Pair("batt_name", battName.text.trim()),
                                Pair("design_uah", uahVal.toString()),
                                Pair("design_uwh", uwhVal.toString()),
                                Pair("model_name", modelName.text.trim()),
                                Pair("override_any", if (overrideAny) "1" else "0"),
                                Pair("verbose", if (verbose) "1" else "0")
                            )
                            var okCnt = 0
                            for ((k,v) in tasks) {
                                if (v.isNotEmpty()) if (battMgr.writeParam(k, v)) okCnt++
                            }
                            // 4. 总是同步到 params.conf (系统层持久化)，即使运行时写失败也保持持久化一致
                            com.override.battcaplsp.core.ConfigSync.syncBatt(
                                battName.text.trim(),
                                uahVal,
                                uwhVal,
                                modelName.text.trim(),
                                overrideAny,
                                verbose
                            )
                            
                            kernelMap = battMgr.readAll()
                            opResult = if (okCnt > 0) "✅ 保存并应用完成: 成功 $okCnt 项" else "⚠️ 保存完成，但应用失败"
                        }
                    }, enabled = uiState.moduleLoaded) { Text("保存并应用") }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button({
                        scope.launch {
                            val mAhStr2 = designUah.text.trim(); val whStr2 = designUwh.text.trim()
                            val designUahMicro = ((mAhStr2.ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString()
                            val designUwhMicro = ((whStr2.ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000000).toLong().toString()
                            
                            // 使用智能文件名匹配加载模块
                            val res = battMgr.loadModuleWithSmartNaming("batt_design_override", mapOf(
                                "design_uah" to designUahMicro.ifEmpty { null },
                                "design_uwh" to designUwhMicro.ifEmpty { null },
                                "model_name" to modelName.text.trim().ifEmpty { null },
                                "batt_name" to battName.text.trim().ifEmpty { null },
                                "override_any" to (if (overrideAny) "1" else null),
                                "verbose" to (if (verbose) "1" else null)
                            ))
                            
                            // 刷新仓库流，更新按钮状态
                            battRepo.update { it }
                            opResult = formatModuleLoadResult(res)
                        }
                    }, enabled = !uiState.moduleLoaded) { Text("加载模块") }
                    Spacer(Modifier.width(8.dp))
                    Button({
                        scope.launch { val res = battMgr.unload(); battRepo.update { it }; opResult = formatModuleUnloadResult(res) }
                    }, enabled = uiState.moduleLoaded) { Text("卸载模块") }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button({
                        scope.launch {
                            val res = RootShell.exec("dmesg | grep batt_design_override")
                            opResult = if (res.code == 0) {
                                val logLines = res.out.split('\n').takeLast(200)
                                if (logLines.any { it.isNotBlank() }) {
                                    "✅ 内核日志读取成功:\n" + logLines.joinToString("\n")
                                } else {
                                    "⚠️ 内核日志为空，可能模块未输出日志"
                                }
                            } else {
                                "❌ 读取内核日志失败: ${res.err}"
                            }
                        }
                    }) { Text("查看内核日志") }
                }
                
                Spacer(Modifier.height(12.dp))
                Text("内核参数：", style = MaterialTheme.typography.titleSmall)
                for ((k,v) in kernelMap) Text("- $k = ${v ?: "<null>"}")
                Spacer(Modifier.height(12.dp))
                Text("结果: $opResult", color = getResultColor(opResult))
        }
    }
}
