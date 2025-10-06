package com.override.battcaplsp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.override.battcaplsp.core.GitHubReleaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.override.battcaplsp.core.RootShell
import com.override.battcaplsp.core.ModuleManager
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.override.battcaplsp.core.TempModuleTester

/**
 * 内部调试面板（仅 Debug 构建可用）
 * 功能：
 * 1. Root 状态刷新
 * 2. 模块搜索调试信息 (ModuleManager#getModuleSearchDebugInfo)
 * 3. 执行简单 Shell 命令 (限制长度 & 只读建议)
 * 4. Dump 当前内核参数
 * 5. 快速切换 verbose 参数
 */
@Composable
fun DebugPanel(moduleManager: ModuleManager) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var rootStatus by remember { mutableStateOf("(未查询)") }
    var paramsDump by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    // 临时模块测试器 (仅 Debug 使用) - 先拿到 context 再 remember，避免在 remember 计算块中直接访问 Composable API
    val context = LocalContext.current
    val tester = remember(context) { TempModuleTester(context = context) }
    var tempUrl by remember { mutableStateOf(TextFieldValue("")) }
    var tempModuleName by remember { mutableStateOf(TextFieldValue("")) }
    var tempParams by remember { mutableStateOf(TextFieldValue("")) } // 形如: key1=val1 key2=val2
    var tempDownloadMsg by remember { mutableStateOf("") }
    var tempLoadMsg by remember { mutableStateOf("") }
    var tempDmesg by remember { mutableStateOf("") }
    var tempListRefreshToggle by remember { mutableStateOf(false) }
    val downloaded = remember(tempListRefreshToggle) { tester.listDownloaded() }

    // 动态 GitHub Release .ko 资产列表
    val ghClient = remember { GitHubReleaseClient() }
    var koAssets by remember { mutableStateOf<List<GitHubReleaseClient.KoAsset>>(emptyList()) }
    var loadingKoList by remember { mutableStateOf(false) }
    var showPreset by remember { mutableStateOf(false) }
    var koListError by remember { mutableStateOf<String?>(null) }

    // 内核/设备兼容信息采集
    var compatCollecting by remember { mutableStateOf(false) }
    var compatInfo by remember { mutableStateOf("") }
    var compatMsg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Top) {
        Text("Debug 内部调试面板", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        SnackbarHost(hostState = snackbarHostState)

        // Root 状态
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Root 状态:")
            Spacer(Modifier.width(8.dp))
            Text(rootStatus, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Button(enabled = !loading, onClick = {
                scope.launch {
                    loading = true
                    val status = RootShell.getRootStatus(forceRefresh = true)
                    rootStatus = status.message
                    loading = false
                }
            }) { Text("刷新Root") }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        // Dump 内核参数
        Text("内核模块参数 Dump", style = MaterialTheme.typography.titleMedium)
        Row {
            Button(enabled = !loading, onClick = {
                scope.launch {
                    loading = true
                    try {
                        val map = moduleManager.readAll()
                        paramsDump = buildString {
                            appendLine("参数 (存在=值 / 不存在=null)")
                            for ((k,v) in map) appendLine("$k = ${v ?: "<null>"}")
                        }
                    } finally { loading = false }
                }
            }) { Text("读取") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { paramsDump = "" }) { Text("清空") }
        }
        if (paramsDump.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            LogViewer(title = "参数", logText = paramsDump, onClear = { paramsDump = "" }, maxHeight = 200)
        }

        Spacer(Modifier.height(16.dp))
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(40.dp))

        // 分隔线
    HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("临时内核模块测试 (仅调试)", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("1. 从给定 URL 下载 .ko 到应用内部目录  2. 手动 insmod  3. 抓取最近 dmesg", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(tempUrl, { tempUrl = it }, label = { Text(".ko 下载 URL") }, supportingText = { Text("http(s):// 开头, <=5MB") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(tempModuleName, { tempModuleName = it }, label = { Text("模块名 (不含.ko)") }, supportingText = { Text("用于 rmmod 与日志过滤") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(tempParams, { tempParams = it }, label = { Text("加载参数 可选") }, supportingText = { Text("格式: key1=val1 key2=val2") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !loading, onClick = {
                val url = tempUrl.text.trim()
                if (url.isEmpty()) { scope.launch { snackbarHostState.showSnackbar("URL 不能为空") }; return@Button }
                scope.launch {
                    // root 不是必须（下载阶段允许无 root），仅提示
                    loading = true; tempDownloadMsg = "正在下载..."; tempLoadMsg = ""; tempDmesg = ""
                    val res = tester.download(url)
                    loading = false
                    tempDownloadMsg = if (res.success) {
                        tempListRefreshToggle = !tempListRefreshToggle
                        "SUCCESS: 下载完成 -> ${res.path} (${res.size}B)"
                    } else {
                        "ERROR: ${res.error}"
                    }
                }
            }) { Text("下载 .ko") }
            Box {
                Button(onClick = {
                    showPreset = true
                    if (koAssets.isEmpty() && !loadingKoList) {
                        scope.launch {
                            loadingKoList = true
                            koListError = null
                            val list = try { ghClient.listLatestKoAssets() } catch (e: Throwable) { emptyList() }
                            loadingKoList = false
                            if (list.isEmpty()) koListError = "未获取到 .ko Release 资产" else koAssets = list
                        }
                    }
                }) { Text(if (loadingKoList) "加载中" else "Release 预设") }
                DropdownMenu(expanded = showPreset, onDismissRequest = { showPreset = false }) {
                    if (loadingKoList) {
                        DropdownMenuItem(text = { Text("正在加载...") }, onClick = { })
                    } else if (koListError != null) {
                        DropdownMenuItem(text = { Text(koListError ?: "错误", maxLines = 2) }, onClick = { showPreset = false })
                    } else if (koAssets.isEmpty()) {
                        DropdownMenuItem(text = { Text("无 .ko 资产") }, onClick = { showPreset = false })
                    } else {
                        koAssets.forEach { asset ->
                            DropdownMenuItem(text = { Text("${asset.name} (${asset.tag})", maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                                tempUrl = TextFieldValue(asset.downloadUrl)
                                showPreset = false
                                scope.launch { snackbarHostState.showSnackbar("选择: ${asset.name}") }
                            })
                        }
                    }
                }
            }
            Button(enabled = downloaded.isNotEmpty() && !loading, onClick = {
                tempListRefreshToggle = !tempListRefreshToggle
            }) { Text("刷新列表") }
            Button(enabled = downloaded.isNotEmpty() && !loading, onClick = {
                val count = tester.deleteAll(); tempListRefreshToggle = !tempListRefreshToggle; tempDownloadMsg = "INFO: 已删除 $count 个"; tempLoadMsg = ""; tempDmesg = ""
            }) { Text("清理全部") }
        }
        if (tempDownloadMsg.isNotBlank()) {
            Spacer(Modifier.height(6.dp)); Text(tempDownloadMsg, style = MaterialTheme.typography.bodySmall, color = if (tempDownloadMsg.startsWith("SUCCESS")) MaterialTheme.colorScheme.primary else if (tempDownloadMsg.startsWith("ERROR")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(10.dp))
        if (downloaded.isNotEmpty()) {
            Text("已下载模块:", style = MaterialTheme.typography.titleSmall)
            for (f in downloaded) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(f.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${f.length()}B", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val modName = if (tempModuleName.text.trim().isNotEmpty()) tempModuleName.text.trim() else f.name.removeSuffix(".ko")
                        scope.launch {
                            if (modName.isBlank()) { snackbarHostState.showSnackbar("请填写模块名") ;return@launch }
                            val root = RootShell.getRootStatus(forceRefresh = false)
                            if (!root.available) { snackbarHostState.showSnackbar("缺少 Root 权限，无法 insmod") ;return@launch }
                            loading = true; tempLoadMsg = "正在加载 ${f.name}..."; tempDmesg = ""
                            val paramsMap = tempParams.text.trim().split(Regex("\\s+")).mapNotNull { p ->
                                if (p.isBlank()) null else p.split('=', limit = 2).let { kv -> if (kv.size == 2) kv[0] to kv[1] else null }
                            }.toMap()
                            val load = tester.insmod(f.absolutePath, paramsMap)
                            loading = false
                            tempLoadMsg = if (load.success) "SUCCESS: 加载成功" else "ERROR: ${load.error}"
                            tempDmesg = load.dmesg
                        }
                    }) { Text("加载") }
                }
            }
        }
        if (tempLoadMsg.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(tempLoadMsg, color = if (tempLoadMsg.startsWith("SUCCESS")) MaterialTheme.colorScheme.primary else if (tempLoadMsg.startsWith("ERROR")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val name = tempModuleName.text.trim().ifBlank { downloaded.firstOrNull()?.name?.removeSuffix(".ko") ?: "" }
                    if (name.isBlank()) { snackbarHostState.showSnackbar("无模块名可卸载") ;return@launch }
                    val root = RootShell.getRootStatus(forceRefresh = false)
                    if (!root.available) { snackbarHostState.showSnackbar("缺少 Root 权限，无法卸载") ;return@launch }
                    loading = true
                    val r = tester.rmmod(name)
                    loading = false
                    tempLoadMsg = if (r.success) "SUCCESS: 卸载 $name" else "ERROR: 卸载失败 ${r.error}"
                }
            }, enabled = !loading) { Text("卸载模块") }
            Button(onClick = {
                scope.launch {
                    val name = tempModuleName.text.trim().ifBlank { downloaded.firstOrNull()?.name?.removeSuffix(".ko") ?: "" }
                    if (name.isBlank()) { snackbarHostState.showSnackbar("缺少关键字") ;return@launch }
                    loading = true
                    tempDmesg = tester.collectModuleLogs(name)
                    loading = false
                }
            }) { Text("抓取日志") }
        }
        if (tempDmesg.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            LogViewer(title = "内核日志 (tail)", logText = tempDmesg, onClear = { tempDmesg = "" }, maxHeight = 260)
        }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
        Spacer(Modifier.height(16.dp))
    Text("设备内核兼容信息采集", style = MaterialTheme.typography.titleLarge)
    Text("用于分析 .ko 不兼容：一次脚本采集内核版本/vermagic/模块/配置/prop/安全状态等。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !compatCollecting, onClick = {
                scope.launch {
                    compatCollecting = true; compatMsg = "正在采集..."; compatInfo = ""
                    val script = """
                        echo '==== uname -a ===='; uname -a
                        echo '==== /proc/version ===='; cat /proc/version 2>/dev/null || echo 'N/A'
                        echo '==== arch ===='; uname -m
                        echo '==== randomize_va_space ===='; cat /proc/sys/kernel/randomize_va_space 2>/dev/null || echo 'N/A'
                        echo '==== kernel tainted ===='; cat /proc/sys/kernel/tainted 2>/dev/null || echo 'N/A'
                        echo '==== /proc/modules (head 60) ===='; head -n 60 /proc/modules 2>/dev/null || echo 'N/A'
                        echo '==== getprop (key subset) ===='
                        for K in ro.product.device ro.product.model ro.product.board ro.hardware ro.boot.hardware.platform \
                                 ro.bootloader ro.build.version.release ro.build.version.sdk ro.build.id ro.build.fingerprint \
                                 ro.build.flavor ro.miui.ui.version.name ro.boot.verifiedbootstate ro.boot.vbmeta.device_state; do
                          VAL="$(getprop ${'$'}K)"; echo ${'$'}K="${'$'}VAL";
                        done
                        echo '==== /proc/config.gz (filtered) ===='
                        if [ -f /proc/config.gz ]; then
                          zcat /proc/config.gz 2>/dev/null | egrep 'CONFIG_(MODULES|MODVERSIONS|LOCALVERSION|PREEMPT|ANDROID|KALLSYMS|KERNEL_LZ4|ARM64_PTR_AUTH|ARM64_MTE|CFI_CLANG|LTO|BPF|BPF_SYSCALL|STACKPROTECTOR|KASLR)' | head -n 120
                        else
                          echo '不可用'
                        fi
                        echo '==== vermagic probe ===='
                        grep -Rhs 'vermagic=' /sys/module/*/sections 2>/dev/null | head -n 2 || echo '未找到'
                        echo '==== cpuinfo (Features line) ===='
                        grep -m1 '^Features' /proc/cpuinfo 2>/dev/null || echo 'N/A'
                        echo '==== vendor modules dir listing ===='
                        if [ -d /vendor/lib/modules ]; then ls -1 /vendor/lib/modules | head -n 80; else echo '目录不存在'; fi
                        echo '==== dmesg (module tail 80) ===='
                        dmesg | grep -i module | tail -n 80 2>/dev/null || echo 'N/A'
                    """.trimIndent()
                    val res = RootShell.exec(script)
                    compatInfo = (res.out + if (res.err.isNotBlank()) "\n[STDERR]\n${res.err}" else "").trim()
                    compatCollecting = false
                    compatMsg = "完成 (单次脚本)"
                }
            }) { Text("采集信息") }
            Button(enabled = compatInfo.isNotBlank(), onClick = {
                scope.launch {
                    val clip = android.content.ClipboardManager::class.java
                    try {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("compat", compatInfo))
                        snackbarHostState.showSnackbar("已复制")
                    } catch (e: Throwable) { snackbarHostState.showSnackbar("复制失败: ${e.message}") }
                }
            }) { Text("复制") }
            Button(enabled = compatInfo.isNotBlank(), onClick = {
                scope.launch {
                    try {
                        val file = java.io.File(context.filesDir, "compat_info.txt")
                        file.writeText(compatInfo)
                        snackbarHostState.showSnackbar("已保存: ${file.absolutePath}")
                    } catch (e: Throwable) { snackbarHostState.showSnackbar("保存失败: ${e.message}") }
                }
            }) { Text("保存文件") }
        }
        if (compatMsg.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(compatMsg, style = MaterialTheme.typography.bodySmall) }
        if (compatInfo.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            LogViewer(title = "兼容信息", logText = compatInfo, onClear = { compatInfo = "" }, maxHeight = 360)
        }
    }
}
