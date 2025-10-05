package com.override.battcaplsp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.override.battcaplsp.core.ChgModuleManager
import com.override.battcaplsp.core.ChgParamRepository
import com.override.battcaplsp.core.ChgUiState
import kotlinx.coroutines.launch

@Composable
fun ChargingScreen(repo: ChgParamRepository, mgr: ChgModuleManager) {
    val scope = rememberCoroutineScope()
    val ui by repo.flow.collectAsState(initial = ChgUiState())
    var batt by remember { mutableStateOf(TextFieldValue(ui.batt)) }
    var usb by remember { mutableStateOf(TextFieldValue(ui.usb)) }
    // 用户友好的单位：伏特(V) 和 毫安(mA)
    var vMax by remember { mutableStateOf(TextFieldValue(if (ui.voltageMax>0) (ui.voltageMax/1000000.0).toString() else "")) }
    var ccc by remember { mutableStateOf(TextFieldValue(if (ui.ccc>0) (ui.ccc/1000).toString() else "")) }
    var term by remember { mutableStateOf(TextFieldValue(if (ui.term>0) (ui.term/1000).toString() else "")) }
    var icl by remember { mutableStateOf(TextFieldValue(if (ui.icl>0) (ui.icl/1000).toString() else "")) }
    var limit by remember { mutableStateOf(TextFieldValue(if (ui.chargeLimit>0) ui.chargeLimit.toString() else "")) }
    var koPath by remember { mutableStateOf(TextFieldValue(ui.koPath)) }
    var verbose by remember { mutableStateOf(ui.verbose) }
    var pdDesired by remember { mutableStateOf(TextFieldValue(ui.pdDesired.toString())) }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("充电模块: ${if (ui.loaded) "已加载" else "未加载"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        // 隐藏 .ko 固定路径
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = batt, 
                onValueChange = { batt = it }, 
                label = { Text("batt") }, 
                supportingText = { 
                    Text(
                        "目标电池节点名，如 battery", 
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                        softWrap = false
                    ) 
                }, 
                modifier = Modifier.weight(1f), 
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = usb, 
                onValueChange = { usb = it }, 
                label = { Text("usb") }, 
                supportingText = { 
                    Text(
                        "USB 电源名称，一般为 usb", 
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                        softWrap = false
                    ) 
                }, 
                modifier = Modifier.weight(1f), 
                singleLine = true
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(vMax, { vMax = it }, label={ Text("voltage_max (V)") }, supportingText={ Text("充电IC输出给电池的最大电压限制，单位伏特。例如：4.46V") }, modifier=Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(ccc, { ccc = it }, label={ Text("ccc (mA)") }, supportingText={ Text("恒流阶段电流，单位毫安。例如：6000mA") }, modifier=Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(term, { term = it }, label={ Text("term (mA)") }, supportingText={ Text("充电终止电流，单位毫安。例如：100mA") }, modifier=Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(icl, { icl = it }, label={ Text("icl (mA)") }, supportingText={ Text("USB输入电流限制，单位毫安。例如：1500mA") }, modifier=Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(limit, { limit = it }, label={ Text("charge_limit (0-100)") }, supportingText={ Text("系统充电上限百分比，0 表示不限") }, modifier=Modifier.fillMaxWidth(), singleLine = true)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(verbose, onCheckedChange = { verbose = it })
            Text("verbose")
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                scope.launch {
                    repo.update { it.copy(
                        koPath = koPath.text.trim(),
                        batt = batt.text.trim(),
                        usb = usb.text.trim(),
                        // 单位转换：V -> uV, mA -> uA
                        voltageMax = (vMax.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0 * 1000000).toLong(),
                        ccc = (ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0 * 1000).toLong(),
                        term = (term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0 * 1000).toLong(),
                        icl = (icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0 * 1000).toLong(),
                        chargeLimit = limit.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0,
                        verbose = verbose,
                        pdDesired = pdDesired.text.trim().toIntOrNull() ?: 1
                    ) }
                    msg = "✅ 配置保存成功"
                }
            }) { Text("保存配置") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    // 使用智能文件名匹配加载充电模块
                    val res = mgr.loadModuleWithSmartNaming(
                        targetBatt = batt.text.trim().ifEmpty { null },
                        targetUsb = usb.text.trim().ifEmpty { null },
                        verbose = verbose
                    )
                    // 加载/卸载后，触发 DataStore 状态刷新：保存一次以更新 loaded 字段
                    repo.update { it }
                    msg = ResultFormatter.formatModuleLoadResult(res)
                }
            }, enabled = !ui.loaded) { Text("加载模块") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { val r = mgr.unload(); repo.update { it }; msg = ResultFormatter.formatModuleUnloadResult(r) } }, enabled = ui.loaded) { Text("卸载模块") }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                scope.launch {
                    // 若未加载模块，直接提示并中止
                    if (!mgr.isLoaded()) {
                        msg = "❌ 模块未加载，无法应用参数"
                        return@launch
                    }
                    val res = mgr.applyBatch(
                        mapOf(
                            "batt" to batt.text.trim(),
                            "usb" to usb.text.trim(),
                            // 单位转换：V -> uV, mA -> uA
                            "voltage_max" to ((vMax.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000000).toLong().toString(),
                            "ccc" to ((ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString(),
                            "term" to ((term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString(),
                            "icl" to ((icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString(),
                            "charge_limit" to limit.text.trim()
                        )
                    )
                    if (res.code==0) {
                        com.override.battcaplsp.core.ConfigSync.syncChg(
                            batt.text.trim(),
                            usb.text.trim(),
                            // 单位转换：V -> uV, mA -> uA
                            (vMax.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0 * 1000000).toLong(),
                            (ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0 * 1000).toLong(),
                            (term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0 * 1000).toLong(),
                            (icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0 * 1000).toLong(),
                            limit.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0,
                            verbose,
                            pdDesired.text.trim().toIntOrNull() ?: 1
                        )
                        msg = "✅ 参数应用成功并已持久化到params.conf"
                    } else {
                        msg = ResultFormatter.formatApplyResult(res)
                    }
                }
            }) { Text("应用参数") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    // 若未加载模块，直接提示
                    if (!mgr.isLoaded()) {
                        msg = "❌ 模块未加载，无法读取参数"
                        return@launch
                    }
                    val m = mgr.readCurrent()
                    // Prefill UI with converted user-friendly units
                    m["batt"]?.let { batt = TextFieldValue(it) }
                    m["usb"]?.let { usb = TextFieldValue(it) }
                    m["voltage_max"]?.toLongOrNull()?.let { vMax = TextFieldValue((it/1000000.0).toString()) }
                    m["ccc"]?.toLongOrNull()?.let { ccc = TextFieldValue((it/1000).toString()) }
                    m["term"]?.toLongOrNull()?.let { term = TextFieldValue((it/1000).toString()) }
                    m["icl"]?.toLongOrNull()?.let { icl = TextFieldValue((it/1000).toString()) }
                    m["charge_limit"]?.toIntOrNull()?.let { limit = TextFieldValue(it.toString()) }
                    msg = "✅ 当前参数读取成功"
                }
            }) { Text("读取当前") }
        }
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(12.dp))
        val isMiui = try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            val v = get.invoke(null, "ro.miui.ui.version.name", "") as String
            v.isNotEmpty() || android.os.Build.MANUFACTURER.contains("Xiaomi", true)
        } catch (_: Throwable) { android.os.Build.MANUFACTURER.contains("Xiaomi", true) }
        if (isMiui) {
            Text("PD 守护", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(pdDesired, { pdDesired = it }, label={ Text("目标 PD (1=PPS,0=MIPPS)") }, modifier=Modifier.fillMaxWidth(), singleLine = true)
            
            // 添加状态检查
            var pdStatus by remember { mutableStateOf("检查中...") }
            
            LaunchedEffect(Unit) {
                pdStatus = mgr.checkPdHelperStatus()
            }
            
            Text("状态: $pdStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Row {
                Button(onClick = {
                    scope.launch {
                        try {
                            repo.update { it.copy(pdDesired = pdDesired.text.trim().toIntOrNull() ?: 1) }
                            val r1 = mgr.deployPdHelper(pdDesired.text.trim().toIntOrNull() ?: 1)
                            val r2 = mgr.startPdHelper()
                            msg = ResultFormatter.formatPdHelperResult(r1.code, r2.code)
                            
                            // 更新状态
                            pdStatus = mgr.checkPdHelperStatus()
                        } catch (e: Exception) {
                            msg = "❌ 操作失败: ${e.message}"
                        }
                    }
                }) { Text("部署并启动") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { 
                    scope.launch { 
                        try {
                            val r = mgr.stopPdHelper()
                            msg = ResultFormatter.formatPdStopResult(r)
                            
                            // 更新状态
                            pdStatus = mgr.checkPdHelperStatus()
                        } catch (e: Exception) {
                            msg = "❌ 停止失败: ${e.message}"
                        }
                    } 
                }) { Text("停止") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { 
                    scope.launch { 
                        try {
                            pdStatus = mgr.checkPdHelperStatus()
                            msg = "✅ 状态已刷新"
                        } catch (e: Exception) {
                            msg = "❌ 状态检查失败: ${e.message}"
                        }
                    } 
                }) { Text("刷新状态") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("结果: $msg", color = ResultFormatter.getResultColor(msg))
    }
}
