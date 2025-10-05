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
    
    var rootStatus by remember { mutableStateOf<RootShell.RootStatus?>(null) }
    var battModuleLoaded by remember { mutableStateOf(false) }
    var chgModuleLoaded by remember { mutableStateOf(false) }
    var kernelVersion by remember { mutableStateOf("") }
    var kernelVersionDetail by remember { mutableStateOf("") }
    var battModuleVersion by remember { mutableStateOf("") }
    var chgModuleVersion by remember { mutableStateOf("") }
    var battModuleVermagic by remember { mutableStateOf("") }
    var chgModuleVermagic by remember { mutableStateOf("") }
    var showRootDialog by remember { mutableStateOf(false) }
    var magiskAvailable by remember { mutableStateOf(false) }
    var magiskModuleInstalled by remember { mutableStateOf(false) }
    var detectedKernelVersion by remember { mutableStateOf<ModuleManager.KernelVersion?>(null) }
    var availableModules by remember { mutableStateOf<List<com.override.battcaplsp.core.KernelModuleDownloader.ModuleInfo>>(emptyList()) }
    var downloadingModule by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0) }
    var moduleManagementMessage by remember { mutableStateOf("") }
    var showModuleDownloadDialog by remember { mutableStateOf(false) }
    
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
    
    // 检查模块状态
    LaunchedEffect(Unit) {
        // 启动时检查root状态，使用强制刷新确保准确性
        rootStatus = RootShell.getRootStatus(forceRefresh = true)
        battModuleLoaded = battMgr.isLoaded()
        chgModuleLoaded = chgMgr.isLoaded()
        magiskAvailable = magiskManager.isMagiskAvailable()
        magiskModuleInstalled = magiskManager.isModuleInstalled()
        
        // 获取内核版本信息
        detectedKernelVersion = battMgr.getKernelVersion()
        kernelVersion = detectedKernelVersion?.majorMinor ?: "未知"
        kernelVersionDetail = detectedKernelVersion?.full?.split("-")?.take(2)?.joinToString("-") ?: ""
        
        // 获取可用的模块列表（远程）
        detectedKernelVersion?.let { kv ->
            availableModules = downloader.getAvailableModules(kv)
        }
        
        // 获取内核版本（保持原有的获取方式用于显示）
        try {
            val kernelVersionFile = File("/proc/version")
            if (kernelVersionFile.exists()) {
                val versionText = kernelVersionFile.readText()
                // 提取版本号，例如 "Linux version 5.15.78-android13-8-00205-g4f5025129fe8-ab9850788"
                val versionMatch = Regex("Linux version ([0-9]+\\.[0-9]+\\.[0-9]+)").find(versionText)
                val fullKernelVersion = versionMatch?.groupValues?.get(1) ?: "未知"
                if (kernelVersion == "未知") kernelVersion = fullKernelVersion
                // 尝试提取包含 android 标签的简短详细版本，例如 5.15.178-android13
                if (kernelVersionDetail.isBlank()) {
                    val detailMatch = Regex("Linux version ([0-9]+\\.[0-9]+\\.[0-9]+-android[0-9]+)").find(versionText)
                    val detail = detailMatch?.groupValues?.get(1)
                    if (!detail.isNullOrBlank()) kernelVersionDetail = detail
                }
            }
        } catch (e: Exception) {
            if (kernelVersion == "未知") kernelVersion = "获取失败"
        }
        
        // 获取电池模块版本和vermagic
        if (battModuleLoaded) {
            try {
                val versionFile = File("/sys/module/batt_design_override/version")
                battModuleVersion = if (versionFile.exists()) {
                    versionFile.readText().trim()
                } else {
                    // 尝试从模块信息获取版本
                    val res = RootShell.exec("modinfo batt_design_override | grep '^version:' | cut -d: -f2 | tr -d ' '")
                    if (res.code == 0 && res.out.isNotBlank()) {
                        res.out.trim()
                    } else {
                        "v1.0"
                    }
                }
                
                // 获取vermagic信息（优先 /sys，其次 modinfo -F，再尝试 .ko/strings）
                val sysVermagicFile = File("/sys/module/batt_design_override/vermagic")
                battModuleVermagic = if (sysVermagicFile.exists()) {
                    sysVermagicFile.readText().trim()
                } else {
                    val vermagicRes = RootShell.exec("modinfo -F vermagic batt_design_override | head -1")
                    if (vermagicRes.code == 0 && vermagicRes.out.isNotBlank()) {
                        vermagicRes.out.trim()
                    } else {
                        val ko = battMgr.findAvailableKernelModule("batt_design_override")
                        if (ko != null) {
                            val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                            if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) byModinfo.out.trim() else {
                                val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                                if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else "未知"
                            }
                        } else "未知"
                    }
                }
            } catch (e: Exception) {
                battModuleVersion = "v1.0"
                battModuleVermagic = "获取失败"
            }
        } else {
            battModuleVersion = ""
            battModuleVermagic = ""
            // 模块未加载时尝试从 .ko 文件读取 vermagic（支持无 modinfo 环境）
            try {
                val ko = battMgr.findAvailableKernelModule("batt_design_override")
                if (ko != null) {
                    val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                    battModuleVermagic = if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) {
                        byModinfo.out.trim()
                    } else {
                        val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                        if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else ""
                    }
                }
            } catch (_: Throwable) { /* ignore */ }
        }
        
        // 获取充电模块版本和vermagic
        if (chgModuleLoaded) {
            try {
                val versionFile = File("/sys/module/chg_param_override/version")
                chgModuleVersion = if (versionFile.exists()) {
                    versionFile.readText().trim()
                } else {
                    // 尝试从模块信息获取版本
                    val res = RootShell.exec("modinfo chg_param_override | grep '^version:' | cut -d: -f2 | tr -d ' '")
                    if (res.code == 0 && res.out.isNotBlank()) {
                        res.out.trim()
                    } else {
                        "v1.0"
                    }
                }
                
                // 获取vermagic信息（优先 /sys，其次 modinfo -F，再尝试 .ko/strings）
                val sysChgVermagicFile = File("/sys/module/chg_param_override/vermagic")
                chgModuleVermagic = if (sysChgVermagicFile.exists()) {
                    sysChgVermagicFile.readText().trim()
                } else {
                    val vermagicRes = RootShell.exec("modinfo -F vermagic chg_param_override | head -1")
                    if (vermagicRes.code == 0 && vermagicRes.out.isNotBlank()) {
                        vermagicRes.out.trim()
                    } else {
                        val ko = battMgr.findAvailableKernelModule("chg_param_override")
                        if (ko != null) {
                            val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                            if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) byModinfo.out.trim() else {
                                val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                                if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else "未知"
                            }
                        } else "未知"
                    }
                }
            } catch (e: Exception) {
                chgModuleVersion = "v1.0"
                chgModuleVermagic = "获取失败"
            }
        } else {
            chgModuleVersion = ""
            chgModuleVermagic = ""
            // 模块未加载时尝试从 .ko 文件读取 vermagic
            try {
                val ko = battMgr.findAvailableKernelModule("chg_param_override")
                if (ko != null) {
                    val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                    chgModuleVermagic = if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) {
                        byModinfo.out.trim()
                    } else {
                        val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                        if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else ""
                    }
                }
            } catch (_: Throwable) { /* ignore */ }
        }
    }
    
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
        // 模块状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("模块状态", style = MaterialTheme.typography.titleMedium)
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
                        
                        // 版本号
                        if (battModuleLoaded && battModuleVersion.isNotEmpty()) {
                            Text(
                                battModuleVersion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        // 状态图标和文字
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (battModuleLoaded) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = (if (battModuleLoaded) Color.Green else Color.Red).copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
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
                        
                        // 版本号
                        if (chgModuleLoaded && chgModuleVersion.isNotEmpty()) {
                            Text(
                                chgModuleVersion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        // 状态图标和文字
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (chgModuleLoaded) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = (if (chgModuleLoaded) Color.Green else Color.Red).copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
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
                        Icon(
                            if (rootStatus?.available == true) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (rootStatus?.available == true) Color.Green else Color.Red,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (rootStatus?.available == true) "已获取" else "未获取",
                            color = if (rootStatus?.available == true) Color.Green else Color.Red
                        )
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            // 强制刷新所有状态
                            RootShell.clearCache()
                            rootStatus = RootShell.getRootStatus(forceRefresh = true)
                            battModuleLoaded = battMgr.isLoaded()
                            chgModuleLoaded = chgMgr.isLoaded()
                            
                            // 重新获取版本信息
                            if (battModuleLoaded) {
                                try {
                                    val versionFile = File("/sys/module/batt_design_override/version")
                                    battModuleVersion = if (versionFile.exists()) {
                                        versionFile.readText().trim()
                                    } else {
                                        val res = RootShell.exec("modinfo batt_design_override | grep '^version:' | cut -d: -f2 | tr -d ' '")
                                        if (res.code == 0 && res.out.isNotBlank()) {
                                            res.out.trim()
                                        } else {
                                            "v1.0"
                                        }
                                    }
                                    
                                    // 重新获取vermagic信息（优先 /sys，其次 modinfo -F，再尝试 .ko/strings）
                                    val sysVermagicFile = File("/sys/module/batt_design_override/vermagic")
                                    battModuleVermagic = if (sysVermagicFile.exists()) {
                                        sysVermagicFile.readText().trim()
                                    } else {
                                        val vermagicRes = RootShell.exec("modinfo -F vermagic batt_design_override | head -1")
                                        if (vermagicRes.code == 0 && vermagicRes.out.isNotBlank()) {
                                            vermagicRes.out.trim()
                                        } else {
                                            // 未获取到时，尝试从 .ko 文件读取
                                            val ko = battMgr.findAvailableKernelModule("batt_design_override")
                                            if (ko != null) {
                                                val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                                                if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) byModinfo.out.trim() else {
                                                    val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                                                    if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else "未知"
                                                }
                                            } else "未知"
                                        }
                                    }
                                } catch (e: Exception) {
                                    battModuleVersion = "v1.0"
                                    battModuleVermagic = "获取失败"
                                }
                            } else {
                                battModuleVersion = ""
                                battModuleVermagic = ""
                                // 未加载时也尝试从文件获取
                                val ko = try { battMgr.findAvailableKernelModule("batt_design_override") } catch (_: Throwable) { null }
                                if (ko != null) {
                                    val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                                    battModuleVermagic = if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) {
                                        byModinfo.out.trim()
                                    } else {
                                        val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                                        if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else ""
                                    }
                                }
                            }
                            
                            if (chgModuleLoaded) {
                                try {
                                    val versionFile = File("/sys/module/chg_param_override/version")
                                    chgModuleVersion = if (versionFile.exists()) {
                                        versionFile.readText().trim()
                                    } else {
                                        val res = RootShell.exec("modinfo chg_param_override | grep '^version:' | cut -d: -f2 | tr -d ' '")
                                        if (res.code == 0 && res.out.isNotBlank()) {
                                            res.out.trim()
                                        } else {
                                            "v1.0"
                                        }
                                    }
                                    
                                    // 重新获取vermagic信息（优先 /sys，其次 modinfo -F，再尝试 .ko/strings）
                                    val sysChgVermagicFile = File("/sys/module/chg_param_override/vermagic")
                                    chgModuleVermagic = if (sysChgVermagicFile.exists()) {
                                        sysChgVermagicFile.readText().trim()
                                    } else {
                                        val vermagicRes = RootShell.exec("modinfo -F vermagic chg_param_override | head -1")
                                        if (vermagicRes.code == 0 && vermagicRes.out.isNotBlank()) {
                                            vermagicRes.out.trim()
                                        } else {
                                            val ko = battMgr.findAvailableKernelModule("chg_param_override")
                                            if (ko != null) {
                                                val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                                                if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) byModinfo.out.trim() else {
                                                    val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                                                    if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else "未知"
                                                }
                                            } else "未知"
                                        }
                                    }
                                } catch (e: Exception) {
                                    chgModuleVersion = "v1.0"
                                    chgModuleVermagic = "获取失败"
                                }
                            } else {
                                chgModuleVersion = ""
                                chgModuleVermagic = ""
                                val ko = try { battMgr.findAvailableKernelModule("chg_param_override") } catch (_: Throwable) { null }
                                if (ko != null) {
                                    val byModinfo = RootShell.exec("modinfo -F vermagic ${RootShell.shellArg(ko)} | head -1")
                                    chgModuleVermagic = if (byModinfo.code == 0 && byModinfo.out.isNotBlank()) {
                                        byModinfo.out.trim()
                                    } else {
                                        val byStrings = RootShell.exec("strings ${RootShell.shellArg(ko)} | grep -m1 -o 'vermagic=[^\\n]*' | head -1 | sed 's/^vermagic=//'")
                                        if (byStrings.code == 0 && byStrings.out.isNotBlank()) byStrings.out.trim() else ""
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("刷新状态")
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
        
        // 模块管理卡片 - 所有设备都显示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("模块管理", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                
                // Magisk 状态
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Magisk 环境:")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(
                            if (magiskAvailable) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = (if (magiskAvailable) Color.Green else Color.Red).copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (magiskAvailable) "可用" else "不可用")
                    }
                }
                
                Spacer(Modifier.height(4.dp))
                
                // 动态模块状态
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("动态模块:")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(
                            if (magiskModuleInstalled) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = (if (magiskModuleInstalled) Color.Green else Color.Red).copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (magiskModuleInstalled) "已安装" else "未安装")
                    }
                }
                
                // 可用内核模块数量（可点击打开下载对话框）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            if (availableModules.isNotEmpty()) {
                                showModuleDownloadDialog = true
                            }
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("可用内核模块:")
                    val totalCount = availableModules.size  // 只统计远程可用的模块
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "${totalCount} 个",
                            color = if (totalCount > 0) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                        if (availableModules.isNotEmpty()) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "下载模块",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 安装动态模块
                    Button(
                        onClick = {
                            scope.launch {
                                moduleManagementMessage = "正在创建动态模块..."
                                val result = magiskManager.createLightweightModule()
                                if (result.success) {
                                    magiskModuleInstalled = magiskManager.isModuleInstalled()
                                    moduleManagementMessage = "✅ ${result.message}"
                                } else {
                                    moduleManagementMessage = "❌ ${result.message}"
                                }
                            }
                        },
                        enabled = magiskAvailable && !magiskModuleInstalled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("安装动态模块", style = MaterialTheme.typography.bodySmall)
                    }
                    
                    // 卸载模块
                    Button(
                        onClick = {
                            scope.launch {
                                moduleManagementMessage = "正在卸载模块..."
                                val result = magiskManager.uninstallModule()
                                if (result.success) {
                                    magiskModuleInstalled = magiskManager.isModuleInstalled()
                                    moduleManagementMessage = "✅ ${result.message}"
                                } else {
                                    moduleManagementMessage = "❌ ${result.message}"
                                }
                            }
                        },
                        enabled = magiskModuleInstalled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("卸载模块", style = MaterialTheme.typography.bodySmall)
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                
                
                Spacer(Modifier.height(8.dp))
                
                // 刷新状态按钮
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            // 强制刷新所有状态
                            RootShell.clearCache()
                            rootStatus = RootShell.getRootStatus(forceRefresh = true)
                            battModuleLoaded = battMgr.isLoaded()
                            chgModuleLoaded = chgMgr.isLoaded()
                            magiskAvailable = magiskManager.isMagiskAvailable()
                            magiskModuleInstalled = magiskManager.isModuleInstalled()
                            
                            detectedKernelVersion = battMgr.getKernelVersion()
                            kernelVersion = detectedKernelVersion?.majorMinor ?: "未知"
                            
                            detectedKernelVersion?.let { kv ->
                                availableModules = downloader.getAvailableModules(kv)
                            }
                            
                            moduleManagementMessage = "✅ 状态已刷新"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("刷新模块管理状态")
                }
                
                if (moduleManagementMessage.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "状态: $moduleManagementMessage",
                        color = ResultFormatter.getResultColor(moduleManagementMessage),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
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
                                            // 本地已有，直接安装到 Magisk 模块
                                            moduleManagementMessage = "正在安装 ${moduleInfo.name}..."
                                            val success = magiskManager.installKernelModule(
                                                moduleInfo.name,
                                                localModule.absolutePath,
                                                moduleInfo.version
                                            )
                                            if (success) {
                                                moduleManagementMessage = "✅ ${moduleInfo.name} 安装成功"
                                            } else {
                                                moduleManagementMessage = "❌ ${moduleInfo.name} 安装失败"
                                            }
                                        } else {
                                            // 需要下载
                                            downloadingModule = moduleInfo.name
                                            downloadProgress = 0
                                            moduleManagementMessage = "正在下载 ${moduleInfo.name}..."
                                            
                                            val result = downloader.downloadModule(moduleInfo) { progress ->
                                                downloadProgress = progress
                                            }
                                            
                                            downloadingModule = null
                                            
                                            if (result.success && result.localPath != null) {
                                                // 下载成功，安装到 Magisk 模块
                                                val success = magiskManager.installKernelModule(
                                                    moduleInfo.name,
                                                    result.localPath,
                                                    moduleInfo.version
                                                )
                                                if (success) {
                                                    moduleManagementMessage = "✅ ${moduleInfo.name} 下载并安装成功"
                                                } else {
                                                    moduleManagementMessage = "✅ 下载成功，但安装失败"
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
}
