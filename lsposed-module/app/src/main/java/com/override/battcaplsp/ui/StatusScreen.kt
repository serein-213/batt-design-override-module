package com.override.battcaplsp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.override.battcaplsp.core.ModuleManager
import com.override.battcaplsp.core.OpEvents
import com.override.battcaplsp.core.RootShell
import kotlinx.coroutines.launch

@Composable
fun StatusScreen(moduleManager: ModuleManager) {
    val scope = rememberCoroutineScope()
    var kernelVersion by remember { mutableStateOf("(查询中)") }
    var rootStatus by remember { mutableStateOf("(查询中)") }
    var loaded by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        try {
            val rs = RootShell.getRootStatus(forceRefresh = true)
            rootStatus = rs.message
            loaded = moduleManager.isLoaded()
            val kv = moduleManager.getKernelVersion()
            kernelVersion = kv.full
            candidates = moduleManager.listCandidateModuleNames()
            OpEvents.info("状态刷新完成")
        } catch (t: Throwable) {
            OpEvents.error("状态初始化失败: ${t.message}")
        } finally { loading = false }
    }

    val events = OpEvents.events

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("系统与模块状态", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        StatusCard(title = "Root", content = rootStatus)
        Spacer(Modifier.height(8.dp))
        StatusCard(title = "内核版本", content = kernelVersion)
        Spacer(Modifier.height(8.dp))
        StatusCard(title = "模块加载", content = if (loaded) "已加载" else "未加载")
        Spacer(Modifier.height(8.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("候选 .ko 文件名 (按优先级)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (candidates.isEmpty()) Text("(无)") else {
                    for (c in candidates) Text("- $c", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.padding(top = 6.dp)) {
                    Button(enabled = !loading, onClick = {
                        scope.launch {
                            loading = true
                            try {
                                candidates = moduleManager.listCandidateModuleNames()
                                OpEvents.success("候选名刷新成功")
                            } catch (t: Throwable) {
                                OpEvents.error("获取候选失败: ${t.message}")
                            } finally { loading = false }
                        }
                    }) { Text("刷新候选") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Button(enabled = !loading, onClick = {
                scope.launch {
                    loading = true
                    try {
                        val rs = RootShell.getRootStatus(forceRefresh = true)
                        rootStatus = rs.message
                        loaded = moduleManager.isLoaded()
                        val kv = moduleManager.getKernelVersion()
                        kernelVersion = kv.full
                        OpEvents.success("状态刷新成功")
                    } catch (t: Throwable) {
                        OpEvents.error("刷新失败: ${t.message}")
                    } finally { loading = false }
                }
            }) { Text("重新检测") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { OpEvents.clear() }) { Text("清空事件") }
        }
        Spacer(Modifier.height(16.dp))
        Text("最近事件", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (events.isEmpty()) Text("(暂无事件)") else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(events) { e ->
                    val color = when(e.type) {
                        OpEvents.Event.Type.SUCCESS -> MaterialTheme.colorScheme.primary
                        OpEvents.Event.Type.WARN -> MaterialTheme.colorScheme.tertiary
                        OpEvents.Event.Type.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondary
                    }
                    Text("${e.time} [${e.type}] ${e.msg}", color = color, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (loading) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun StatusCard(title: String, content: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(content, style = MaterialTheme.typography.bodySmall)
        }
    }
}
