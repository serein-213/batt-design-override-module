package com.override.battcaplsp.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "batt_override")

object Keys {
    val battName = stringPreferencesKey("batt_name")
    val designUah = longPreferencesKey("design_uah")
    val designUwh = longPreferencesKey("design_uwh")
    val modelName = stringPreferencesKey("model_name")
    val overrideAny = longPreferencesKey("override_any") // 0|1 store as long
    val verbose = longPreferencesKey("verbose")
    val koPath = stringPreferencesKey("ko_path")
}

data class UiState(
    val battName: String = "battery",
    val designUah: Long = 0,
    val designUwh: Long = 0,
    val modelName: String = "",
    val overrideAny: Boolean = false,
    val verbose: Boolean = false,  // 默认为false（不勾选）
    val koPath: String = "/data/adb/modules/batt-design-override/common/batt_design_override.ko",
    val kernelValues: Map<String,String?> = emptyMap(),
    val moduleLoaded: Boolean = false,
    val lastLog: String = ""
)

class ParamRepository(private val ctx: Context, private val moduleManager: ModuleManager) {
    // 内部：DataStore 基础流
    private val prefsFlow: Flow<UiState> = ctx.dataStore.data.map { p ->
        runBlocking { toStateAsync(p) }
    }

    // 模块加载状态独立可刷新流
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> get() = _loaded

    // 合并为公开 UI 状态：DataStore 值 + 最新加载状态
    val flow: Flow<UiState> = combine(prefsFlow, _loaded) { base, liveLoaded ->
        base.copy(moduleLoaded = liveLoaded)
    }

    suspend fun refresh() {
        // 主动检测模块是否加载并推送
        _loaded.emit(moduleManager.isLoaded())
    }

    private suspend fun toStateAsync(p: Preferences): UiState = UiState(
        battName = p[Keys.battName] ?: "battery",
        designUah = p[Keys.designUah] ?: 0,
        designUwh = p[Keys.designUwh] ?: 0,
        modelName = p[Keys.modelName] ?: "",
        overrideAny = (p[Keys.overrideAny] ?: 0) == 1L,
        verbose = (p[Keys.verbose] ?: 0) == 1L,  // 默认为0（false，不勾选）
        koPath = p[Keys.koPath] ?: "/data/adb/modules/batt-design-override/common/batt_design_override.ko",
        moduleLoaded = moduleManager.isLoaded()
    )

    private fun toState(p: Preferences): UiState = UiState(
        battName = p[Keys.battName] ?: "battery",
        designUah = p[Keys.designUah] ?: 0,
        designUwh = p[Keys.designUwh] ?: 0,
        modelName = p[Keys.modelName] ?: "",
        overrideAny = (p[Keys.overrideAny] ?: 0) == 1L,
        verbose = (p[Keys.verbose] ?: 0) == 1L,  // 默认为0（false，不勾选）
        koPath = p[Keys.koPath] ?: "/data/adb/modules/batt-design-override/common/batt_design_override.ko",
        moduleLoaded = false  // 在非suspend上下文中默认为false
    )

    suspend fun update(transform: (UiState)->UiState) {
        ctx.dataStore.edit { p ->
            val cur = toState(p)
            val n = transform(cur)
            p[Keys.battName] = n.battName
            p[Keys.designUah] = n.designUah
            p[Keys.designUwh] = n.designUwh
            p[Keys.modelName] = n.modelName
            p[Keys.overrideAny] = if (n.overrideAny) 1 else 0
            p[Keys.verbose] = if (n.verbose) 1 else 0
            p[Keys.koPath] = n.koPath
        }
        // 更新持久化后尝试刷新加载状态（不会显著阻塞）
        runBlocking { refresh() }
    }
}
