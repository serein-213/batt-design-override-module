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
import androidx.compose.material.icons.filled.Info
// Icons.Filled.Terminal 需要 material-icons-extended，这里不引入扩展包以减小体积，改用 Warning 图标占位
// import androidx.compose.material.icons.filled.Terminal
import com.override.battcaplsp.core.HookSettingsRepository
import com.override.battcaplsp.core.HookSettingsState
import com.override.battcaplsp.core.RootShell
import com.override.battcaplsp.core.ModuleManager
import com.override.battcaplsp.core.ChgModuleManager
import kotlinx.coroutines.launch
import com.override.battcaplsp.core.OpEvents
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

// 提取vermagic中的内核版本号（如从"5.15.192-g12345678 SMP preempt mod_unload aarch64"提取"5.15.192"）
private fun extractKernelVersionFromVermagic(vermagic: String): String {
    if (vermagic.isBlank()) return ""
    
    // 匹配内核版本号模式：数字.数字.数字
    val versionRegex = Regex("""(\d+\.\d+\.\d+)""")
    val match = versionRegex.find(vermagic)
    return match?.value ?: ""
}

// 统一状态展示组件（代替 emoji）顶层定义，避免本地 enum 编译限制
private enum class StatusType { SUCCESS, ERROR, WARN, INFO, UPDATE }

@Composable
private fun StatusLine(type: StatusType, text: String) {
    val (icon, tint) = when (type) {
        StatusType.SUCCESS -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
        // 没有 material.icons.filled.Error 基础图标，使用 Warning 图标代替错误态
        StatusType.ERROR -> Icons.Default.Warning to MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
        StatusType.WARN -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
        StatusType.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        StatusType.UPDATE -> Icons.Default.Info to MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
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
    var apkDownloadId by remember { mutableStateOf<Long?>(null) }
    var apkLocalPath by remember { mutableStateOf<String?>(null) }
    var apkPhase by remember { mutableStateOf("idle") } // idle | downloading | ready | installing | done | error
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
    
    // 状态刷新缓存与并行优化
    var lastStatusLoadTime by remember { mutableStateOf(0L) }
    val statusTtlMs = 5000L
    // availableModules 缓存更长一点（因为很少变化）
    var lastModulesFetchTime by remember { mutableStateOf(0L) }
    val modulesTtlMs = 60000L
    var cachedModules by remember { mutableStateOf<List<com.override.battcaplsp.core.KernelModuleDownloader.ModuleInfo>>(emptyList()) }

    suspend fun loadStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && !initialLoading && (now - lastStatusLoadTime) < statusTtlMs) return

        // 并行获取基础状态
        kotlinx.coroutines.coroutineScope {
            val rootDeferred = async { RootShell.getRootStatus(forceRefresh = true) }
            val battLoadedDeferred = async { battMgr.isLoaded() }
            val chgLoadedDeferred = async { chgMgr.isLoaded() }
            val magiskAvailDeferred = async { magiskManager.isMagiskAvailable() }
            val magiskInstalledDeferred = async { magiskManager.isModuleInstalled() }
            val kernelVersionDeferred = async { runCatching { battMgr.getKernelVersion() }.getOrNull() }

            val newRoot = rootDeferred.await()
            val newBattLoaded = battLoadedDeferred.await()
            val newChgLoaded = chgLoadedDeferred.await()
            val newMagiskAvail = magiskAvailDeferred.await()
            val newMagiskInstalled = magiskInstalledDeferred.await()
            val newKernelVersion = kernelVersionDeferred.await()

            var newKernelVersionStr = newKernelVersion?.majorMinor ?: "未知"
            var newKernelVersionDetailStr = newKernelVersion?.full?.split('-')?.take(2)?.joinToString("-") ?: ""

            // 只在需要且缓存过期时获取 availableModules（依赖 kernelVersion）
            val needFetchModules = (cachedModules.isEmpty() || (now - lastModulesFetchTime) > modulesTtlMs) && newKernelVersion != null
            if (needFetchModules && newKernelVersion != null) {
                val fetched = runCatching { downloader.getAvailableModules(newKernelVersion) }.getOrElse { emptyList() }
                cachedModules = fetched
                lastModulesFetchTime = now
            }

            // 仅当模块已加载时再去读 vermagic，避免大量无谓 root 调用
            suspend fun readLoadedVermagic(module: String): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val sysFile = File("/sys/module/${'$'}module/vermagic")
                    if (sysFile.exists()) return@withContext sysFile.readText().trim()
                    val vres = RootShell.exec("modinfo -F vermagic ${'$'}module | head -1")
                    if (vres.code == 0 && vres.out.isNotBlank()) return@withContext vres.out.trim() else ""
                } catch (_: Throwable) { }
                return@withContext ""
            }

            val battVermagicDeferred = if (newBattLoaded == true) async { readLoadedVermagic("batt_design_override") } else null
            val chgVermagicDeferred = if (newChgLoaded == true) async { readLoadedVermagic("chg_param_override") } else null

            val newBattVermagic = battVermagicDeferred?.await().orEmpty()
            val newChgVermagic = chgVermagicDeferred?.await().orEmpty()

            // 一次性赋值到 Compose 状态
            rootStatus = newRoot
            battModuleLoaded = newBattLoaded
            chgModuleLoaded = newChgLoaded
            magiskAvailable = newMagiskAvail
            magiskModuleInstalled = newMagiskInstalled
            detectedKernelVersion = newKernelVersion
            kernelVersion = newKernelVersionStr
            kernelVersionDetail = newKernelVersionDetailStr
            availableModules = cachedModules
            // 版本号已不展示
            battModuleVersion = ""
            battModuleVermagic = newBattVermagic
            chgModuleVersion = ""
            chgModuleVermagic = newChgVermagic
            initialLoading = false
            lastStatusLoadTime = now
        }
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
    // 日志查看相关状态
    var showLogDialog by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf<String?>(null) }
    var loadingLog by remember { mutableStateOf(false) }

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
                val successTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                val warnTint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                Icon(
                    if (rootStatus!!.available) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (rootStatus!!.available) successTint else warnTint
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
                // 查看日志按钮
                OutlinedButton(
                    onClick = {
                        showLogDialog = true
                        loadingLog = true
                        logContent = null
                        scope.launch {
                            logContent = com.override.battcaplsp.core.LogCollector.getRecentLogs(context, maxLines = 400)
                            loadingLog = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("查看日志")
                }
                
                Spacer(Modifier.height(8.dp))
                
                // 显示当前版本和检查结果
                versionCheckResult?.let { result ->
                    if (result.error != null) {
                        StatusLine(type = StatusType.ERROR, text = result.error ?: "未知错误")
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
                                StatusLine(type = StatusType.UPDATE, text = "发现新版本")
                            } else {
                                StatusLine(type = StatusType.SUCCESS, text = "已是最新版本")
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

        // 统一使用底部 LogViewerDialog，不再单独展示“日志工具”卡片
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
                
                // 统一行间距：与内核版本 / 电池模块保持一致 (1.dp)
                Spacer(Modifier.height(1.dp))

                // 电池模块行（仅图标与 vermagic，去除 root 权限误嵌内容）
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
                        // vermagic 显示（仅显示内核版本号部分，如 5.15.192）
                        run {
                            val source = when {
                                battModuleVermagic.isNotEmpty() && battModuleVermagic != "未知" && battModuleVermagic != "获取失败" -> {
                                    android.util.Log.d("HookSettings", "Using battModuleVermagic: $battModuleVermagic")
                                    extractKernelVersionFromVermagic(battModuleVermagic)
                                }
                                else -> {
                                    android.util.Log.d("HookSettings", "No batt vermagic source available")
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
                        // 状态图标
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            when (battModuleLoaded) {
                                null -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                true -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    modifier = Modifier.size(16.dp)
                                )
                                false -> Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.70f),
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
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    modifier = Modifier.size(16.dp)
                                )
                                false -> Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.70f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(2.dp))
                
                // Root权限（仅展示图标/进度，去除文字）
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
                            true -> Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                modifier = Modifier.size(16.dp)
                            )
                            false -> Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.70f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
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
                            true -> { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("可用") }
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
                            true -> { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("已安装") }
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
                                    if (result.success) { magiskModuleInstalled = magiskManager.isModuleInstalled(); moduleManagementMessage = "SUCCESS:${result.message}" } else { moduleManagementMessage = "ERROR:${result.message}" }
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
                                    if (result.success) { magiskModuleInstalled = magiskManager.isModuleInstalled(); moduleManagementMessage = "SUCCESS:${result.message}" } else { moduleManagementMessage = "ERROR:${result.message}" }
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
                    onClick = { scope.launch { loadStatus(force = true); moduleManagementMessage = "INFO:状态已刷新" } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("刷新") }
                if (moduleManagementMessage.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    val (stype, body) = remember(moduleManagementMessage) {
                        val parts = moduleManagementMessage.split(":", limit = 2)
                        if (parts.size == 2) {
                            val t = when(parts[0]) {
                                "SUCCESS" -> StatusType.SUCCESS
                                "ERROR" -> StatusType.ERROR
                                "INFO" -> StatusType.INFO
                                else -> StatusType.INFO
                            }
                            t to parts[1]
                        } else StatusType.INFO to moduleManagementMessage
                    }
                    StatusLine(type = stype, text = body)
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
                                    try {
                                        val displayCap = displayCapacity.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0
                                        if (displayCap < 0 || displayCap > 200000) {
                                            msg = "ERROR:显示容量超出范围"; OpEvents.error("Hook:显示容量非法 $displayCap"); return@launch
                                        }
                                        repo.update { it.copy(
                                            hookEnabled = hookEnabled,
                                            displayCapacity = displayCap,
                                            useSystemProp = useSystemProp,
                                            customCapacity = customCapacity.text.trim().ifEmpty { "0" }.toIntOrNull() ?: 0,
                                            hookTextView = hookTextView,
                                            hookSharedPrefs = hookSharedPrefs,
                                            hookJsonMethods = hookJsonMethods,
                                            launcherIconEnabled = launcherIconEnabled
                                        ) }
                                        val pm = context.packageManager
                                        val comp = android.content.ComponentName("com.override.battcaplsp", "com.override.battcaplsp.Launcher")
                                        pm.setComponentEnabledSetting(
                                            comp,
                                            if (launcherIconEnabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                            android.content.pm.PackageManager.DONT_KILL_APP
                                        )
                                        msg = "SUCCESS:设置保存成功"; OpEvents.success("Hook:设置保存")
                                    } catch (t: Throwable) {
                                        msg = "ERROR:保存异常 ${t.message}"; OpEvents.error("Hook:保存异常 ${t.message}")
                                    }
                                }
                            }) { Text("保存设置") }
                            
                            Spacer(Modifier.width(8.dp))
                            
                            Button(onClick = {
                                scope.launch {
                                    try {
                                        hookEnabled = true
                                        displayCapacity = TextFieldValue("")
                                        useSystemProp = true
                                        customCapacity = TextFieldValue("")
                                        hookTextView = true
                                        hookSharedPrefs = true
                                        hookJsonMethods = true
                                        repo.update { HookSettingsState() }
                                        msg = "INFO:已重置为默认设置"; OpEvents.info("Hook:重置默认")
                                    } catch (t: Throwable) {
                                        msg = "ERROR:重置异常 ${t.message}"; OpEvents.error("Hook:重置异常 ${t.message}")
                                    }
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
    setMsg("INFO:正在测试 $moduleName...")
        val test = safeInstaller.quickTestModule(moduleName, localPath, emptyMap())
        if (!test.passed) {
            setMsg("ERROR:测试失败: ${test.message}")
            try { RootShell.exec("rmmod ${moduleName}") } catch (_: Throwable) {}
            isInstallingModule = false
            return
        }
    setMsg("INFO:测试通过，正在安装...")
        // 针对 chg_param_override 支持安装后立即试加载 (autoLoad)，其它模块仍只复制
        val autoLoad = moduleName == "chg_param_override"
        val detailed = magiskManager.installKernelModuleDetailed(
            moduleName = moduleName,
            koFilePath = localPath,
            version = version,
            autoLoad = autoLoad,
            loadParams = if (autoLoad) "verbose=1" else ""
        )
        if (detailed.success) {
            val loadPart = if (autoLoad) {
                if (detailed.autoLoaded) "已尝试加载 (code=0)" else "已复制(加载失败, 可稍后重试)"
            } else "已复制"
            val errTail = if (detailed.errorMessages.isNotEmpty()) " | warn:${detailed.errorMessages.first()}" else ""
            setMsg("SUCCESS:$moduleName 安装成功 ($loadPart)$errTail")
        } else {
            val reason = detailed.errorMessages.firstOrNull()?.take(120) ?: "未知原因"
            setMsg("ERROR:$moduleName 安装失败: $reason")
        }
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
                                        // 改动: 强制忽略本地缓存，每次都重新下载并覆盖 (用户需求)
                                        downloadingModule = moduleInfo.name
                                        moduleDownloadProgress = 0
                                        moduleManagementMessage = "正在重新下载 ${moduleInfo.name} (忽略本地缓存)..."

                                        val result = downloader.downloadModule(moduleInfo) { progress ->
                                            moduleDownloadProgress = progress
                                        }

                                        downloadingModule = null

                                        if (result.success && result.localPath != null) {
                                            // 下载完成后继续测试 + 安装（保持原行为）
                                            scope.launch {
                                                implicitTestAndInstall(moduleInfo.name, result.localPath, moduleInfo.version) { moduleManagementMessage = it }
                                            }
                                        } else {
                                            moduleManagementMessage = "ERROR:${result.message}"
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
                    
                    if (apkPhase == "downloading") {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在下载... ${apkDownloadProgress}%", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (apkPhase == "ready") {
                        Spacer(Modifier.height(8.dp))
                        StatusLine(StatusType.SUCCESS, "下载完成，准备安装")
                    } else if (apkPhase == "installing") {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在启动安装...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (apkPhase == "error") {
                        Spacer(Modifier.height(8.dp))
                        StatusLine(StatusType.ERROR, "更新失败，请重试")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            when (apkPhase) {
                                "idle", "error" -> {
                                    // 启动下载
                                    downloadingApk = true
                                    apkPhase = "downloading"
                                    apkDownloadProgress = 0
                                    val result = apkDownloadManager.downloadApk(releaseInfo.downloadUrl, releaseInfo.versionName)
                                    if (result.success && result.downloadId != null && result.filePath != null) {
                                        apkDownloadId = result.downloadId
                                        apkLocalPath = result.filePath
                                        // 轮询进度
                                        while (true) {
                                            kotlinx.coroutines.delay(600)
                                            val pg = apkDownloadId?.let { apkDownloadManager.queryProgress(it) }
                                            if (pg != null) {
                                                apkDownloadProgress = pg.percent
                                                if (pg.completed) {
                                                    // localUri 可能是 file:// 开头
                                                    val local = pg.localUri?.removePrefix("file://") ?: apkLocalPath
                                                    apkLocalPath = local
                                                    apkPhase = "ready"
                                                    downloadingApk = false
                                                    break
                                                }
                                                if (pg.failed) {
                                                    apkPhase = "error"
                                                    downloadingApk = false
                                                    android.widget.Toast.makeText(context, "下载失败", android.widget.Toast.LENGTH_LONG).show()
                                                    break
                                                }
                                            }
                                        }
                                    } else {
                                        apkPhase = "error"
                                        downloadingApk = false
                                        android.widget.Toast.makeText(context, "下载启动失败: ${result.error}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                                "ready" -> {
                                    // 安装
                                    apkPhase = "installing"
                                    val p = apkLocalPath
                                    if (p != null) {
                                        val res = apkDownloadManager.installApk(p)
                                        if (res.success) {
                                            apkPhase = "done"
                                            showUpdateDialog = false
                                        } else {
                                            apkPhase = "error"
                                            val err = res.error ?: "未知错误"
                                            android.widget.Toast.makeText(context, "安装失败：$err", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        apkPhase = "error"
                                        android.widget.Toast.makeText(context, "文件路径缺失", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                                "downloading", "installing" -> { /* ignore */ }
                                "done" -> { showUpdateDialog = false }
                            }
                        }
                    },
                    enabled = apkPhase !in setOf("downloading", "installing")
                ) {
                    val label = when (apkPhase) {
                        "idle", "error" -> "下载更新"
                        "downloading" -> "下载中..."
                        "ready" -> "安装更新"
                        "installing" -> "安装中..."
                        "done" -> "已完成"
                        else -> "下载更新"
                    }
                    Text(label)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("稍后更新")
                }
            }
        )
    }

    // 日志查看对话框 (增强: 使用终端风格 LogViewer)
    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("最近日志 (末尾400行)") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (loadingLog) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在加载...")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    com.override.battcaplsp.ui.LogViewer(
                        title = "日志输出",
                        logText = (logContent ?: if (loadingLog) "" else "(无日志)"),
                        onClear = { logContent = "" },
                        maxHeight = 280,
                        autoScroll = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        loadingLog = true
                        scope.launch {
                            logContent = com.override.battcaplsp.core.LogCollector.getRecentLogs(context, maxLines = 400)
                            loadingLog = false
                        }
                    }) { Text("刷新") }
                    TextButton(onClick = { showLogDialog = false }) { Text("关闭") }
                }
            }
        )
    }
}
