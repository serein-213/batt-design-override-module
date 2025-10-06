package com.override.battcaplsp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.override.battcaplsp.core.*
import com.override.battcaplsp.core.OpEvents
import com.override.battcaplsp.core.truncateMiddle
import com.override.battcaplsp.ui.StatusBadge
import kotlinx.coroutines.launch

@Composable
fun ChargingScreen(repo: ChgParamRepository, mgr: ChgModuleManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by repo.flow.collectAsState(initial = ChgUiState())
    LaunchedEffect(Unit) { repo.refresh() }
    var batt by remember { mutableStateOf(TextFieldValue(ui.batt)) }
    var usb by remember { mutableStateOf(TextFieldValue(ui.usb)) }
    var vMax by remember { mutableStateOf(TextFieldValue(if (ui.voltageMax > 0) (ui.voltageMax / 1_000_000.0).toString() else "")) }
    var ccc by remember { mutableStateOf(TextFieldValue(if (ui.ccc > 0) (ui.ccc / 1000).toString() else "")) }
    var term by remember { mutableStateOf(TextFieldValue(if (ui.term > 0) (ui.term / 1000).toString() else "")) }
    var icl by remember { mutableStateOf(TextFieldValue(if (ui.icl > 0) (ui.icl / 1000).toString() else "")) }
    var limit by remember { mutableStateOf(TextFieldValue(if (ui.chargeLimit > 0) ui.chargeLimit.toString() else "")) }
    var koPath by remember { mutableStateOf(TextFieldValue(ui.koPath)) }
    var verbose by remember { mutableStateOf(ui.verbose) }
    var msg by remember { mutableStateOf("") }
    var kernelLog by remember { mutableStateOf("") }

    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("充电模块: ${if (ui.loaded) "已加载" else "未加载"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(batt, { batt = it }, label = { Text("batt") }, supportingText = { Text("目标电池节点名") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(usb, { usb = it }, label = { Text("usb") }, supportingText = { Text("USB 电源名称") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(vMax, { vMax = it }, label = { Text("voltage_max (V)") }, supportingText = { Text("例:4.46") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(ccc, { ccc = it }, label = { Text("ccc (mA)") }, supportingText = { Text("恒流阶段") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(term, { term = it }, label = { Text("term (mA)") }, supportingText = { Text("终止电流") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(icl, { icl = it }, label = { Text("icl (mA)") }, supportingText = { Text("输入限制") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(limit, { limit = it }, label = { Text("charge_limit (0-100)") }, supportingText = { Text("上限 0 不限") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(verbose, { verbose = it }); Text("verbose") }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                scope.launch {
                    try {
                        if (!ui.loaded) { msg = "ERROR:模块未加载"; OpEvents.error("充电:模块未加载保存失败"); return@launch }
                        val vMaxVal = vMax.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0
                        if (vMaxVal !in 3.0..5.5) { msg = "WARN:voltage_max 可疑(${vMaxVal}V)"; OpEvents.warn("voltage_max 异常 $vMaxVal"); }
                        val limitVal = limit.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0
                        if (limitVal !in 0..100) { msg = "ERROR:charge_limit 必须 0-100"; OpEvents.error("charge_limit 越界 $limitVal"); return@launch }
                        repo.update { it.copy(
                            koPath = koPath.text.trim(),
                            batt = batt.text.trim(),
                            usb = usb.text.trim(),
                            voltageMax = (vMaxVal * 1_000_000).toLong(),
                            ccc = ((ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                            term = ((term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                            icl = ((icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                            chargeLimit = limitVal,
                            verbose = verbose,
                        ) }
                        val applyRes = mgr.applyBatch(mapOf(
                            "batt" to batt.text.trim(),
                            "usb" to usb.text.trim(),
                            "voltage_max" to ((vMaxVal) * 1_000_000).toLong().toString(),
                            "ccc" to ((ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString(),
                            "term" to ((term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString(),
                            "icl" to ((icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString(),
                            "charge_limit" to limit.text.trim()
                        ))
                        if (applyRes.code == 0) {
                            ConfigSync.syncChg(
                                context,
                                batt.text.trim(), usb.text.trim(),
                                (vMaxVal * 1_000_000).toLong(),
                                ((ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                                ((term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                                ((icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                                limitVal,
                                verbose,
                                1
                            )
                            msg = "SUCCESS:保存并应用完成"; OpEvents.success("充电:保存并应用成功")
                        } else {
                            val detail = ResultFormatter.formatApplyResult(applyRes)
                            msg = if (detail.contains("失败")) {
                                OpEvents.error("充电:写内核失败"); "WARN:保存完成，但应用失败: ${com.override.battcaplsp.core.TextAbbrev.middle(detail,160)}" } else detail
                        }
                    } catch (t: Throwable) {
                        msg = "ERROR:保存异常 ${t.message}"; OpEvents.error("充电:保存异常 ${t.message}")
                    }
                }
            }, enabled = ui.loaded) { Text("保存并应用") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch {
                try {
                    val conf = com.override.battcaplsp.core.ConfigSync.readConf(context)
                    val battConf = conf["CHG_BATT_NAME"]?.ifBlank { null }
                    val usbConf = conf["CHG_USB_NAME"]?.ifBlank { null }
                    val verboseConf = conf["VERBOSE"]?.let { it == "1" || it.equals("true", true) || it.equals("Y", true) }
                    val finalVerbose = verboseConf ?: verbose
                    val res = mgr.loadModuleWithSmartNaming(
                        targetBatt = battConf ?: batt.text.trim().ifEmpty { null },
                        targetUsb = usbConf ?: usb.text.trim().ifEmpty { null },
                        verbose = finalVerbose
                    )
                    repo.refresh()
                    msg = ResultFormatter.formatModuleLoadResult(res)
                    if (res.code == 0) OpEvents.success("充电:加载模块成功") else OpEvents.error("充电:加载失败 ${res.err.take(60)}")
                } catch (t: Throwable) {
                    msg = "ERROR:加载异常 ${t.message}"; OpEvents.error("充电:加载异常 ${t.message}")
                }
            } }, enabled = !ui.loaded) { Text("加载模块") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch {
                try {
                    val r = mgr.unload(); repo.refresh(); msg = ResultFormatter.formatModuleUnloadResult(r)
                    if (r.code == 0) OpEvents.success("充电:卸载成功") else OpEvents.error("充电:卸载失败 ${r.err.take(40)}")
                } catch (t: Throwable) {
                    msg = "ERROR:卸载异常 ${t.message}"; OpEvents.error("充电:卸载异常 ${t.message}")
                }
            } }, enabled = ui.loaded) { Text("卸载模块") }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { scope.launch {
                try {
                    val cmd = "(dmesg | grep -E 'chg_param_override' || true)"
                    var res = RootShell.exec(cmd)
                    var lines = res.out.split('\n').filter { it.isNotBlank() }
                    if (lines.isEmpty()) {
                        val fallback = RootShell.exec("logcat -b kernel -d | grep -E 'chg_param_override' || true")
                        if (fallback.out.isNotBlank()) { res = fallback; lines = fallback.out.split('\n').filter { it.isNotBlank() } }
                    }
                    if (lines.isNotEmpty()) {
                        val tail = if (lines.size > 300) lines.takeLast(300) else lines
                        kernelLog = tail.joinToString("\n")
                        msg = "SUCCESS:内核日志读取成功 (${tail.size} 行, 显示末尾)"; OpEvents.success("充电:读取日志 ${tail.size}")
                    } else {
                        kernelLog = ""
                        msg = if (res.err.isNotBlank()) {
                            OpEvents.warn("充电:日志stderr有输出"); "WARN:未获取到匹配日志 (stderr: ${com.override.battcaplsp.core.TextAbbrev.middle(res.err,120)})"
                        } else {
                            OpEvents.info("充电:日志无匹配"); "INFO:没有匹配到包含 chg_param_override 的日志"
                        }
                    }
                } catch (t: Throwable) {
                    kernelLog = ""; msg = "ERROR:日志读取异常 ${t.message}"; OpEvents.error("充电:日志异常 ${t.message}")
                }
            } }) { Text("查看内核日志") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch {
                try {
                    if (!mgr.isLoaded()) { msg = "ERROR:模块未加载，无法读取参数"; OpEvents.error("充电:读取参数失败未加载"); return@launch }
                    val m = mgr.readCurrent();
                    m["batt"]?.let { batt = TextFieldValue(it) }
                    m["usb"]?.let { usb = TextFieldValue(it) }
                    m["voltage_max"]?.toLongOrNull()?.let { vMax = TextFieldValue((it / 1_000_000.0).toString()) }
                    m["ccc"]?.toLongOrNull()?.let { ccc = TextFieldValue((it / 1000).toString()) }
                    m["term"]?.toLongOrNull()?.let { term = TextFieldValue((it / 1000).toString()) }
                    m["icl"]?.toLongOrNull()?.let { icl = TextFieldValue((it / 1000).toString()) }
                    m["charge_limit"]?.toIntOrNull()?.let { limit = TextFieldValue(it.toString()) }
                    msg = "SUCCESS:当前参数读取成功"; OpEvents.success("充电:读取当前参数成功")
                } catch (t: Throwable) {
                    msg = "ERROR:读取参数异常 ${t.message}"; OpEvents.error("充电:读取参数异常 ${t.message}")
                }
            } }) { Text("读取当前参数") }
        }
        Spacer(Modifier.height(8.dp))
        if (kernelLog.isNotEmpty()) {
            LogViewer(
                title = "充电模块日志 (chg_param_override)",
                logText = kernelLog,
                onClear = { kernelLog = "" },
                maxHeight = 320
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(12.dp)); Divider(); Spacer(Modifier.height(12.dp))
        if (msg.isNotBlank()) {
            StatusBadge(msg, showLabel = "结果:")
        }
    }
}
