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
import kotlinx.coroutines.launch

@Composable
fun ChargingScreen(repo: ChgParamRepository, mgr: ChgModuleManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by repo.flow.collectAsState(initial = ChgUiState())
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
                    if (!ui.loaded) { msg = "❌ 模块未加载"; return@launch }
                    repo.update { it.copy(
                        koPath = koPath.text.trim(),
                        batt = batt.text.trim(),
                        usb = usb.text.trim(),
                        voltageMax = ((vMax.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong(),
                        ccc = ((ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                        term = ((term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                        icl = ((icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                        chargeLimit = limit.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0,
                        verbose = verbose,
                    ) }
                    val applyRes = mgr.applyBatch(mapOf(
                        "batt" to batt.text.trim(),
                        "usb" to usb.text.trim(),
                        "voltage_max" to ((vMax.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong().toString(),
                        "ccc" to ((ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString(),
                        "term" to ((term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString(),
                        "icl" to ((icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong().toString(),
                        "charge_limit" to limit.text.trim()
                    ))
                    if (applyRes.code == 0) {
                        ConfigSync.syncChg(
                            context,
                            batt.text.trim(), usb.text.trim(),
                            ((vMax.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong(),
                            ((ccc.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                            ((term.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                            ((icl.text.trim().ifEmpty { "0" }.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                            limit.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0,
                            verbose,
                            1
                        )
                        msg = "✅ 保存并应用完成"
                    } else {
                        val detail = ResultFormatter.formatApplyResult(applyRes)
                        msg = if (detail.contains("失败")) "⚠️ 保存完成，但应用失败: ${detail.truncateMiddle(160)}" else detail
                    }
                }
            }, enabled = ui.loaded) { Text("保存并应用") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { val res = mgr.loadModuleWithSmartNaming(batt.text.trim().ifEmpty { null }, usb.text.trim().ifEmpty { null }, verbose); repo.update { it }; msg = ResultFormatter.formatModuleLoadResult(res) } }, enabled = !ui.loaded) { Text("加载模块") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { val r = mgr.unload(); repo.update { it }; msg = ResultFormatter.formatModuleUnloadResult(r) } }, enabled = ui.loaded) { Text("卸载模块") }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { scope.launch { val res = RootShell.exec("dmesg | grep chg_param_override"); msg = if (res.code == 0) { val lines = res.out.split('\n').takeLast(80); if (lines.any { it.isNotBlank() }) "✅ 内核日志读取成功:\n" + lines.joinToString("\n") else "⚠️ 内核日志为空，可能模块未输出日志" } else { "❌ 读取内核日志失败: ${res.err.truncateMiddle(160)}" } } }) { Text("查看内核日志") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { if (!mgr.isLoaded()) { msg = "❌ 模块未加载，无法读取参数"; return@launch }; val m = mgr.readCurrent(); m["batt"]?.let { batt = TextFieldValue(it) }; m["usb"]?.let { usb = TextFieldValue(it) }; m["voltage_max"]?.toLongOrNull()?.let { vMax = TextFieldValue((it / 1_000_000.0).toString()) }; m["ccc"]?.toLongOrNull()?.let { ccc = TextFieldValue((it / 1000).toString()) }; m["term"]?.toLongOrNull()?.let { term = TextFieldValue((it / 1000).toString()) }; m["icl"]?.toLongOrNull()?.let { icl = TextFieldValue((it / 1000).toString()) }; m["charge_limit"]?.toIntOrNull()?.let { limit = TextFieldValue(it.toString()) }; msg = "✅ 当前参数读取成功" } }) { Text("读取当前参数") }
        }
        Spacer(Modifier.height(12.dp)); Divider(); Spacer(Modifier.height(12.dp))
        Text("结果: $msg", color = ResultFormatter.getResultColor(msg))
    }
}
