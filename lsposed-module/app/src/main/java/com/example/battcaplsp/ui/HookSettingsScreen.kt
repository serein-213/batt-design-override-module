package com.override.battcaplsp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import com.override.battcaplsp.core.HookSettingsRepository
import com.override.battcaplsp.core.HookSettingsState
import com.override.battcaplsp.core.RootShell
import com.override.battcaplsp.core.ModuleManager
import com.override.battcaplsp.core.ChgModuleManager
import kotlinx.coroutines.launch
import java.io.File

// 提取vermagic中的内核版本号（如从"5.15.192-g12345678 SMP preempt mod_unload aarch64"提取"5.15.192"）
private fun extractKernelVersionFromVermagic(vermagic: String): String {
    if (vermagic.isBlank()) return ""
    
    // 匹配内核版本号模式：数字.数字.数字
    val versionRegex = Regex("""(\d+\.\d+\.\d+)""")
    val match = versionRegex.find(vermagic)
    return match?.value ?: ""
}

@Composable
fun HookSettingsScreen(repo: HookSettingsRepository) {
    val scope = rememberCoroutineScope()
    val ui by repo.flow.collectAsState(initial = HookSettingsState())
    val context = LocalContext.current
    
    // 模块状态检测
    val battMgr = remember { ModuleManager() }
    val chgMgr = remember { ChgModuleManager() }
    val downloader = remember { com.override.battcaplsp.core.KernelModuleDownloader(context) }
    val magiskManager = remember { com.override.battcaplsp.core.MagiskModuleManager(context) }
    val githubClient = remember { com.override.battcaplsp.core.GitHubReleaseClient() }
    val safeInstaller = remember { com.override.battcaplsp.core.SafeModuleInstaller(context) }
    
    var rootStatus by remember { mutableStateOf<RootShell.RootStatus?>(null) }
    var battModuleLoaded by remember { mutableStateOf<Boolean?>(null) }
    var chgModuleLoaded by remember { mutableStateOf<Boolean?>(null) }
    var kernelVersion by remember { mutableStateOf("") }
    var kernelVersionDetail by remember { mutableStateOf("") }
    var battModuleVersion by remember { mutableStateOf("") }
    // 删除充电模块版本号展示需求 -> 不再保留版本号状态
    var chgModuleVersion by remember { mutableStateOf("") }
    var battModuleVermagic by remember { mutableStateOf("") }
    var chgModuleVermagic by remember { mutableStateOf("") }
    var showRootDialog by remember { mutableStateOf(false) }
    var magiskAvailable by remember { mutableStateOf<Boolean?>(null) }
    var magiskModuleInstalled by remember { mutableStateOf<Boolean?>(null) }
    var detectedKernelVersion by remember { mutableStateOf<ModuleManager.KernelVersion?>(null) }
    var availableModules by remember { mutableStateOf<List<com.override.battcaplsp.core.KernelModuleDownloader.ModuleInfo>>(emptyList()) }
    var downloadingModule by remember { mutableStateOf<String?>(null) }
    var moduleDownloadProgress by remember { mutableStateOf(0) }
    var moduleManagementMessage by remember { mutableStateOf("") }
    var showModuleDownloadDialog by remember { mutableStateOf(false) }
    var isInstallingModule by remember { mutableStateOf(false) }
    var initialLoading by remember { mutableStateOf(true) }
    
    // 版本检查相关状态
    var versionCheckResult by remember { mutableStateOf<com.override.battcaplsp.core.GitHubReleaseClient.VersionCheckResult?>(null) }
    var isCheckingVersion by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var downloadingApk by remember { mutableStateOf(false) }
    var apkDownloadProgress by remember { mutableStateOf(0) }
    val apkDownloadManager = remember { com.override.battcaplsp.core.ApkDownloadManager(context) }
    
    // 检测是否为小米设备
    val isMiuiDevice = remember {
        try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            val v = get.invoke(null, "ro.miui.ui.version.name", "") as String
            v.isNotEmpty() || android.os.Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true)
        } catch (_: Throwable) {
            android.os.Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true)
        }
    }
    
    var lastStatusLoadTime by remember { mutableStateOf(0L) }
    val statusTtlMs = 5000L
    suspend fun loadStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && !initialLoading && (now - lastStatusLoadTime) < statusTtlMs) {
            return
        }
        // 聚合加载，局部变量暂存，最后一次性赋值，减少 Compose 多次重组引发的闪烁
        val newRoot = RootShell.getRootStatus(forceRefresh = true)
        val newBattLoaded = battMgr.isLoaded()
        val newChgLoaded = chgMgr.isLoaded()
        val newMagiskAvail = magiskManager.isMagiskAvailable()
        val newMagiskInstalled = magiskManager.isModuleInstalled()
        var newKernelVersion: ModuleManager.KernelVersion? = null
        var newKernelVersionStr = "未知"
        var newKernelVersionDetailStr = ""
        var newAvailableModules: List<com.override.battcaplsp.core.KernelModuleDownloader.ModuleInfo> = emptyList()
        var newBattVersion = ""
        var newChgVersion = ""
        var newBattVermagic = ""
        var newChgVermagic = ""

        try {
            newKernelVersion = battMgr.getKernelVersion()
            newKernelVersionStr = newKernelVersion?.majorMinor ?: "未知"
            newKernelVersionDetailStr = newKernelVersion?.full?.split("-")?.take(2)?.joinToString("-") ?: ""
            newKernelVersion?.let { kv ->
                newAvailableModules = downloader.getAvailableModules(kv)
            }
            val kernelVersionFile = File("/proc/version")
            if (kernelVersionFile.exists()) {
                val versionText = kernelVersionFile.readText()
                val versionMatch = Regex("Linux version ([0-9]+\\.[0-9]+\\.[0-9]+)").find(versionText)
                val fullKernelVersion = versionMatch?.groupValues?.get(1) ?: "未知"
                if (newKernelVersionStr == "未知") newKernelVersionStr = fullKernelVersion
                if (newKernelVersionDetailStr.isBlank()) {
                    val detailMatch = Regex("Linux version ([0-9]+\\.[0-9]+\\.[0-9]+-android[0-9]+)").find(versionText)
                    val detail = detailMatch?.groupValues?.get(1)
                    if (!detail.isNullOrBlank()) newKernelVersionDetailStr = detail
                }
            }
        } catch (_: Throwable) { if (newKernelVersionStr == "未知") newKernelVersionStr = "获取失败" }

    suspend fun readVermagic(module: String, loaded: Boolean): Pair<String,String> {
            var version = ""
            var vermagic = ""
            if (loaded) {
                try {
                    val versionFile = File("/sys/module/${module}/version")
                    version = if (versionFile.exists()) versionFile.readText().trim() else {
                        val res = RootShell.exec("modinfo ${module} | grep '^version:' | cut -d: -f2 | tr -d ' '")
                        if (res.code == 0 && res.out.isNotBlank()) res.out.trim() else "v1.0"
                    }
                    val sysFile = File("/sys/module/${module}/vermagic")
                    vermagic = if (sysFile.exists()) sysFile.readText().trim() else {
                        val vres = RootShell.exec("modinfo -F vermagic ${module} | head -1")
                        if (vres.code == 0 && vres.out.isNotBlank()) vres.out.trim() else {
                            val ko = battMgr.findAvailableKernelModule(module)
                            if (ko != null) {
                                val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                                if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) byModinfo.out.trim() else {
                                    val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                                    if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else "未知"
                                }
                            } else "未知"
                        }
                    }
                } catch (_: Throwable) { version = "v1.0"; vermagic = "获取失败" }
            } else {
                // 未加载时尝试从文件获取 vermagic
                try {
                    val ko = battMgr.findAvailableKernelModule(module)
                    if (ko != null) {
                        val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                        vermagic = if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) {
                            byModinfo.out.trim()
                        } else {
                            val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                            if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else ""
                        }
                    }
                } catch (_: Throwable) { }
            }
            return version to vermagic
        }

    val (bVersion, bVermagic) = readVermagic("batt_design_override", newBattLoaded)
    val (cVersion, cVermagic) = readVermagic("chg_param_override", newChgLoaded)

        // 一次性赋值
        rootStatus = newRoot
        battModuleLoaded = newBattLoaded
        chgModuleLoaded = newChgLoaded
        magiskAvailable = newMagiskAvail
        magiskModuleInstalled = newMagiskInstalled
        detectedKernelVersion = newKernelVersion
        kernelVersion = newKernelVersionStr
        kernelVersionDetail = newKernelVersionDetailStr
        availableModules = newAvailableModules
        battModuleVersion = if (newBattLoaded) bVersion else ""
        battModuleVermagic = if (newBattLoaded) bVermagic else bVermagic // bVermagic 可能为空/信息
    // 不再展示充电模块版本号，仍计算 cVersion 但不赋值可选: chgModuleVersion = ""
    chgModuleVersion = ""
        chgModuleVermagic = if (newChgLoaded) cVermagic else cVermagic
        initialLoading = false
        lastStatusLoadTime = now
    }

    LaunchedEffect(Unit) { loadStatus() }
    
    var hookEnabled by remember { mutableStateOf(ui.hookEnabled) }
    var displayCapacity by remember { mutableStateOf(TextFieldValue(if (ui.displayCapacity > 0) ui.displayCapacity.toString() else "")) }
    var useSystemProp by remember { mutableStateOf(ui.useSystemProp) }
    var customCapacity by remember { mutableStateOf(TextFieldValue(if (ui.customCapacity > 0) ui.customCapacity.toString() else "")) }
    var hookTextView by remember { mutableStateOf(ui.hookTextView) }
    var hookSharedPrefs by remember { mutableStateOf(ui.hookSharedPrefs) }
    var hookJsonMethods by remember { mutableStateOf(ui.hookJsonMethods) }
    var launcherIconEnabled by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }

    // 当 ui 状态变化时，更新本地状态变量
    LaunchedEffect(ui) {
        hookEnabled = ui.hookEnabled
        displayCapacity = TextFieldValue(if (ui.displayCapacity > 0) ui.displayCapacity.toString() else "")
        useSystemProp = ui.useSystemProp
        customCapacity = TextFieldValue(if (ui.customCapacity > 0) ui.customCapacity.toString() else "")
        hookTextView = ui.hookTextView
        hookSharedPrefs = ui.hookSharedPrefs
        hookJsonMethods = ui.hookJsonMethods
        launcherIconEnabled = ui.launcherIconEnabled
    }

    // Root 权限对话框
    if (showRootDialog && rootStatus != null) {
        AlertDialog(
            onDismissRequest = { showRootDialog = false },
            title = { Text("Root 权限状态") },
            text = { Text(rootStatus!!.message) },
            confirmButton = {
                TextButton(onClick = { showRootDialog = false }) {
                    Text("确定")
                }
            },
            icon = {
                Icon(
                    if (rootStatus!!.available) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (rootStatus!!.available) Color.Green else Color.Red
                )
            }
        )
    }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
        // 应用版本检查卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("应用更新", style = MaterialTheme.typography.titleSmall)
                    Button(
                        onClick = {
                            isCheckingVersion = true
                            scope.launch {
                                try {
                                    versionCheckResult = githubClient.checkForUpdates(context)
                                } catch (e: Exception) {
                                    versionCheckResult = com.override.battcaplsp.core.GitHubReleaseClient.VersionCheckResult(
                                        hasUpdate = false,
                                        currentVersion = "未知",
                                        latestVersion = null,
                                        releaseInfo = null,
                                        error = "检查更新失败: ${e.message}"
                                    )
                                }
                                isCheckingVersion = false
                                if (versionCheckResult?.hasUpdate == true) {
                                    showUpdateDialog = true
                                }
                            }
                        },
                        enabled = !isCheckingVersion
                    ) {
                        if (isCheckingVersion) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("检查更新")
                        }
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                // 显示当前版本和检查结果
                versionCheckResult?.let { result ->
                    if (result.error != null) {
                        Text(
                            text = "❌ ${result.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = "当前版本: ${result.currentVersion}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (result.latestVersion != null) {
                            Text(
                                text = "最新版本: ${result.latestVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (result.hasUpdate) {
                                Text(
                                    text = "🆕 发现新版本！",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "✅ 已是最新版本",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                } ?: run {
                    Text(
                        text = "点击「检查更新」查看是否有新版本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        // 合并后的 模块状态与管理 卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("模块状态与管理", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                
                // 内核版本（与标签同一行显示）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("内核版本:")
                    Text(
                        kernelVersionDetail.ifBlank { kernelVersion.ifEmpty { "获取中..." } },
                        color = if ((kernelVersionDetail.ifBlank { kernelVersion }).isNotEmpty() && kernelVersion != "获取失败")
                            MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
                
                Spacer(Modifier.height(1.dp))
                
                // 电池模块
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("电池模块:")
                    
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // vermagic 显示（仅显示内核版本号部分，如5.15.192）
                        run {
                            val source = when {
                                battModuleVermagic.isNotEmpty() && battModuleVermagic != "未知" && battModuleVermagic != "获取失败" -> {
                                    android.util.Log.d("HookSettings", "Using battModuleVermagic: $battModuleVermagic")
                                    extractKernelVersionFromVermagic(battModuleVermagic)
                                }
                                else -> {
                                    android.util.Log.d("HookSettings", "No vermagic available")
                                    ""
                                }
                            }
                            val displayVermagic = source
                            if (displayVermagic.isNotBlank()) {
                                Text(
                                    displayVermagic,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        // 移除版本号显示
                        
                        // 状态图标和文字
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            when (battModuleLoaded) {
                                null -> {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                }
                                true -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.Green.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                                false -> Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color.Red.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(1.dp))
                
                // 充电模块
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("充电模块:")
                    
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // vermagic 显示（仅显示内核版本号部分，如5.15.192）
                        run {
                            val source = when {
                                chgModuleVermagic.isNotEmpty() && chgModuleVermagic != "未知" && chgModuleVermagic != "获取失败" -> {
                                    android.util.Log.d("HookSettings", "Using chgModuleVermagic: $chgModuleVermagic")
                                    extractKernelVersionFromVermagic(chgModuleVermagic)
                                }
                                else -> {
                                    android.util.Log.d("HookSettings", "No chg vermagic source available")
                                    ""
                                }
                            }
                            val displayVermagic = source
                            if (displayVermagic.isNotBlank()) {
                                Text(
                                    displayVermagic,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        // 已移除充电模块版本号显示
                        
                        // 状态图标和文字
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            when (chgModuleLoaded) {
                                null -> {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                }
                                true -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.Green.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                                false -> Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color.Red.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(2.dp))
                
                // Root权限
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Root权限:")
                    TextButton(
                        onClick = { 
                            scope.launch {
                                // 清除缓存并强制重新检测
                                RootShell.clearCache()
                                rootStatus = RootShell.getRootStatus(forceRefresh = true)
                                showRootDialog = true
                            }
                        }
                    ) {
                        when (rootStatus?.available) {
                            null -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            true -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(16.dp))
                            false -> Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when (rootStatus?.available) {
                                null -> "检测中..."
                                true -> "已获取"
                                false -> "未获取"
                            },
                            color = when (rootStatus?.available) {
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                                true -> Color.Green
                                false -> Color.Red
                            }
                        )
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                // Magisk / 动态模块状态区块（原管理卡片内容合并）
                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text("环境与动态模块", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                // Magisk
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Magisk 环境:")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        when (magiskAvailable) {
                            null -> { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(4.dp)); Text("检测中...", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            true -> { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green.copy(alpha = 0.6f), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("可用") }
                            false -> { Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("不可用") }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 动态模块
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("动态模块:")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        when (magiskModuleInstalled) {
                            null -> { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(4.dp)); Text("检测中...", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            true -> { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green.copy(alpha = 0.6f), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("已安装") }
                            false -> { Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("未安装") }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 可用内核模块数量（点击打开下载）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (availableModules.isNotEmpty()) showModuleDownloadDialog = true }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("可用内核模块:")
                    val totalCount = availableModules.size
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${totalCount} 个", color = if (totalCount > 0) MaterialTheme.colorScheme.primary else Color.Gray)
                        if (availableModules.isNotEmpty()) {
                            Icon(Icons.Default.Add, contentDescription = "下载模块", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 操作按钮行：安装/卸载 + 刷新
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                isInstallingModule = true; moduleManagementMessage = "正在创建动态模块..."
                                try {
                                    val result = magiskManager.createLightweightModule()
                                    if (result.success) { magiskModuleInstalled = magiskManager.isModuleInstalled(); moduleManagementMessage = "✅ ${result.message}" } else { moduleManagementMessage = "❌ ${result.message}" }
                                } finally { isInstallingModule = false }
                            }
                        },
                        enabled = (magiskAvailable == true) && (magiskModuleInstalled == false) && !isInstallingModule,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isInstallingModule) { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(4.dp)) }
                        Text(if (isInstallingModule) "处理中..." else "安装动态模块", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                isInstallingModule = true; moduleManagementMessage = "正在卸载模块..."
                                try {
                                    val result = magiskManager.uninstallModule()
                                    if (result.success) { magiskModuleInstalled = magiskManager.isModuleInstalled(); moduleManagementMessage = "✅ ${result.message}" } else { moduleManagementMessage = "❌ ${result.message}" }
                                } finally { isInstallingModule = false }
                            }
                        },
                        enabled = (magiskModuleInstalled == true) && !isInstallingModule,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isInstallingModule) { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(4.dp)) }
                        Text(if (isInstallingModule) "处理中..." else "卸载模块", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { scope.launch { loadStatus(force = true); moduleManagementMessage = "✅ 状态已刷新" } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("刷新") }
                if (moduleManagementMessage.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("状态: $moduleManagementMessage", color = ResultFormatter.getResultColor(moduleManagementMessage), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        
        // 小米设备专用的 Hook 设置卡片
        if (isMiuiDevice) {
            var expanded by remember { mutableStateOf(false) }  // 默认为折叠状态
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { 
                            Text("小米", style = MaterialTheme.typography.titleMedium)
                            Text("我的设备页面-电池容量显示", style = MaterialTheme.typography.titleSmall)
                        }
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "收起" else "展开")
                        }
                    }

                    if (expanded) {
                        Spacer(Modifier.height(8.dp))

                        // Hook总开关
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Hook总开关", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(hookEnabled, onCheckedChange = { hookEnabled = it })
                                    Text("启用Hook功能")
                                }
                                Text("控制是否对设置页面进行电量显示Hook", 
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        
                        // 数据源设置
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("数据源设置", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(useSystemProp, onCheckedChange = { useSystemProp = it })
                                    Text("使用系统属性")
                                }
                                Text("优先从 persist.sys.batt.capacity_mah 读取容量", 
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                                
                                Spacer(Modifier.height(8.dp))
                                
                                OutlinedTextField(
                                    value = customCapacity,
                                    onValueChange = { customCapacity = it },
                                    label = { Text("自定义容量 (mAh)") },
                                    supportingText = { Text("当不使用系统属性时，使用此值") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !useSystemProp
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        
                        // Hook方法设置
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Hook方法设置", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(hookTextView, onCheckedChange = { hookTextView = it })
                                    Text("Hook TextView.setText")
                                }
                                Text("拦截TextView文本设置，替换其中的mAh数值", 
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                                
                                Spacer(Modifier.height(8.dp))
                                
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(hookSharedPrefs, onCheckedChange = { hookSharedPrefs = it })
                                    Text("Hook SharedPreferences")
                                }
                                Text("拦截SharedPreferences读写，修改basic_info_key", 
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                                
                                Spacer(Modifier.height(8.dp))
                                
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(hookJsonMethods, onCheckedChange = { hookJsonMethods = it })
                                    Text("Hook JSON方法")
                                }
                                Text("拦截JSON相关方法，修改设备信息JSON", 
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        
                        // 显示容量设置
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("显示容量设置", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                
                                OutlinedTextField(
                                    value = displayCapacity,
                                    onValueChange = { displayCapacity = it },
                                    label = { Text("显示容量 (mAh)") },
                                    supportingText = { Text("在设置页面显示的电池容量，0表示使用系统默认") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))

                        // 操作按钮
                        Row {
                            Button(onClick = {
                                scope.launch {
                                    repo.update { it.copy(
                                        hookEnabled = hookEnabled,
                                        displayCapacity = displayCapacity.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0,
                                        useSystemProp = useSystemProp,
                                        customCapacity = customCapacity.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0,
                                        hookTextView = hookTextView,
                                        hookSharedPrefs = hookSharedPrefs,
                                        hookJsonMethods = hookJsonMethods,
                                        launcherIconEnabled = launcherIconEnabled
                                    ) }
                                    // 应用桌面图标显隐
                                    val pm = context.packageManager
                                    val comp = android.content.ComponentName("com.override.battcaplsp", "com.override.battcaplsp.Launcher")
                                    pm.setComponentEnabledSetting(
                                        comp,
                                        if (launcherIconEnabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                        android.content.pm.PackageManager.DONT_KILL_APP
                                    )
                                    msg = "✅ 设置保存成功"
                                }
                            }) { Text("保存设置") }
                            
                            Spacer(Modifier.width(8.dp))
                            
                            Button(onClick = {
                                scope.launch {
                                    // 重置为默认值
                                    hookEnabled = true
                                    displayCapacity = TextFieldValue("")
                                    useSystemProp = true
                                    customCapacity = TextFieldValue("")
                                    hookTextView = true
                                    hookSharedPrefs = true
                                    hookJsonMethods = true
                                    
                                    repo.update { HookSettingsState() }
                                    msg = "✅ 已重置为默认设置"
                                }
                            }) { Text("重置默认") }
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        
                        // 说明信息（折叠内）
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("使用说明", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                Text("""
                                    1. Hook总开关：控制是否启用所有Hook功能
                                    2. 数据源设置：选择容量数据的来源
                                    3. Hook方法设置：选择要使用的Hook方法
                                    4. 显示容量：设置要在设置页面显示的容量值
                                    
                                    修改设置后需要重启设置应用才能生效
                                """.trimIndent(), 
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        Text("结果: $msg", color = ResultFormatter.getResultColor(msg))
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
        }
        
        // 原独立模块管理卡片删除
        
        // 桌面入口卡片 - 始终显示在外面
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("桌面入口", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(launcherIconEnabled, onCheckedChange = { enabled ->
                        launcherIconEnabled = enabled
                        scope.launch {
                            repo.update { it.copy(launcherIconEnabled = enabled) }
                            val pm = context.packageManager
                            val comp = android.content.ComponentName("com.override.battcaplsp", "com.override.battcaplsp.Launcher")
                            pm.setComponentEnabledSetting(
                                comp,
                                if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                android.content.pm.PackageManager.DONT_KILL_APP
                            )
                        }
                    })
                    Text("显示本应用桌面图标")
                }
                Text("关闭后将从桌面隐藏应用图标，可通过 LSPosed 管理器的「模块设置」进入此应用。", 
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    
    // 抽取：隐式测试 + 安装到 Magisk (复用下载与本地已有两处逻辑)
    suspend fun implicitTestAndInstall(
        moduleName: String,
        localPath: String,
        version: String,
        setMsg: (String) -> Unit
    ) {
        isInstallingModule = true
        setMsg("🔍 正在测试 $moduleName...")
        val test = safeInstaller.quickTestModule(moduleName, localPath, emptyMap())
        if (!test.passed) {
            setMsg("❌ 测试失败: ${test.message}")
            try { RootShell.exec("rmmod ${moduleName}") } catch (_: Throwable) {}
            isInstallingModule = false
            return
        }
        setMsg("📦 测试通过，正在安装...")
        val ok = magiskManager.installKernelModule(moduleName, localPath, version)
        setMsg(if (ok) "✅ $moduleName 安装成功 (已测试)" else "❌ $moduleName 安装失败")
        try { RootShell.exec("rmmod ${moduleName}") } catch (_: Throwable) {}
        isInstallingModule = false
    }

    // 模块下载对话框
    if (showModuleDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showModuleDownloadDialog = false },
            title = { Text("下载内核模块") },
            text = {
                Column {
                    Text(
                        "检测到 ${availableModules.size} 个可用的内核模块：",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    availableModules.forEach { moduleInfo ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showModuleDownloadDialog = false
                                    scope.launch {
                                        val localModule = downloader.getLocalModule(moduleInfo.name, moduleInfo.version, moduleInfo.kernelVersion)
                                        if (localModule != null) {
                                            scope.launch {
                                                implicitTestAndInstall(moduleInfo.name, localModule.absolutePath, moduleInfo.version) { moduleManagementMessage = it }
                                            }
                                        } else {
                                            // 需要下载
                                            downloadingModule = moduleInfo.name
                                            moduleDownloadProgress = 0
                                            moduleManagementMessage = "正在下载 ${moduleInfo.name}..."
                                            
                                            val result = downloader.downloadModule(moduleInfo) { progress ->
                                                moduleDownloadProgress = progress
                                            }
                                            
                                            downloadingModule = null
                                            
                                            if (result.success && result.localPath != null) {
                                                scope.launch {
                                                    implicitTestAndInstall(moduleInfo.name, result.localPath, moduleInfo.version) { moduleManagementMessage = it }
                                                }
                                            } else {
                                                moduleManagementMessage = "❌ ${result.message}"
                                            }
                                        }
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Text(
                                        moduleInfo.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "下载",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                
                                Spacer(Modifier.height(4.dp))
                                
                                Text(
                                    "版本: ${moduleInfo.version}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Text(
                                    "内核: ${moduleInfo.kernelVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Text(
                                    "大小: ${String.format("%.1f", moduleInfo.size / 1024.0)} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModuleDownloadDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
    
    // 应用更新对话框
    if (showUpdateDialog && versionCheckResult?.releaseInfo != null) {
        val releaseInfo = versionCheckResult!!.releaseInfo!!
        val currentVersion = versionCheckResult!!.currentVersion
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("发现新版本") },
            text = {
                Column {
                    Text(
                        "发现新版本 ${releaseInfo.versionName}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        "当前版本: ${currentVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (releaseInfo.releaseNotes.isNotEmpty()) {
                        Text(
                            "更新内容:",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            releaseInfo.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (downloadingApk) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "正在下载... ${apkDownloadProgress}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!downloadingApk) {
                            downloadingApk = true
                            apkDownloadProgress = 0
                            scope.launch {
                                try {
                                    val downloadResult = apkDownloadManager.downloadApk(
                                        releaseInfo.downloadUrl,
                                        releaseInfo.versionName
                                    )
                                    
                                    if (downloadResult.success && downloadResult.filePath != null) {
                                        // 下载成功，尝试安装
                                        val installResult = apkDownloadManager.installApk(downloadResult.filePath)
                                        if (installResult.success) {
                                            showUpdateDialog = false
                                        } else {
                                            // 安装失败，显示错误
                                            android.widget.Toast.makeText(
                                                context,
                                                "安装失败: ${installResult.error}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } else {
                                        // 下载失败
                                        android.widget.Toast.makeText(
                                            context,
                                            "下载失败: ${downloadResult.error}",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "更新失败: ${e.message}",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    downloadingApk = false
                                }
                            }
                        }
                    },
                    enabled = !downloadingApk
                ) {
                    Text(if (downloadingApk) "下载中..." else "立即更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("稍后更新")
                }
            }
        )
    }
}
