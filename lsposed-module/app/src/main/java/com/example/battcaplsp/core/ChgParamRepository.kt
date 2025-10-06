package com.override.battcaplsp.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.chgStore by preferencesDataStore(name = "chg_override")

object ChgKeys {
    val koPath = stringPreferencesKey("chg_ko_path")
    val batt = stringPreferencesKey("target_batt")
    val usb = stringPreferencesKey("target_usb")
    val voltageMax = longPreferencesKey("voltage_max")
    val ccc = longPreferencesKey("ccc")
    val term = longPreferencesKey("term")
    val icl = longPreferencesKey("icl")
    val chargeLimit = intPreferencesKey("charge_limit")
    val verbose = intPreferencesKey("verbose")
    val pdDesired = intPreferencesKey("pd_desired")
}

data class ChgUiState(
    val koPath: String = "/data/adb/modules/batt-design-override/common/chg_param_override.ko",
    val batt: String = "battery",
    val usb: String = "usb",
    val voltageMax: Long = 0,
    val ccc: Long = 0,
    val term: Long = 0,
    val icl: Long = 0,
    val chargeLimit: Int = 0,
    val verbose: Boolean = false,
    val pdDesired: Int = 1,
    val loaded: Boolean = false,
    val lastMsg: String = ""
)

class ChgParamRepository(private val ctx: Context, private val mgr: ChgModuleManager) {
    private val prefsFlow: Flow<ChgUiState> = ctx.chgStore.data.map {
        runBlocking { toStateAsync(it) }
    }
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> get() = _loaded
    val flow: Flow<ChgUiState> = combine(prefsFlow, _loaded) { base, live -> base.copy(loaded = live) }

    suspend fun refresh() { _loaded.emit(mgr.isLoaded()) }

    private suspend fun toStateAsync(p: Preferences): ChgUiState = ChgUiState(
        koPath = p[ChgKeys.koPath] ?: "/data/adb/modules/batt-design-override/common/chg_param_override.ko",
        batt = p[ChgKeys.batt] ?: "battery",
        usb = p[ChgKeys.usb] ?: "usb",
        voltageMax = p[ChgKeys.voltageMax] ?: 0,
        ccc = p[ChgKeys.ccc] ?: 0,
        term = p[ChgKeys.term] ?: 0,
        icl = p[ChgKeys.icl] ?: 0,
        chargeLimit = p[ChgKeys.chargeLimit] ?: 0,
    // 默认值改为 0（之前为 1 导致未设置时 UI 误认为开启并写回1）
    verbose = (p[ChgKeys.verbose] ?: 0) == 1,
        pdDesired = p[ChgKeys.pdDesired] ?: 1,
        loaded = mgr.isLoaded()
    )

    private fun toState(p: Preferences): ChgUiState = ChgUiState(
        koPath = p[ChgKeys.koPath] ?: "/data/adb/modules/batt-design-override/common/chg_param_override.ko",
        batt = p[ChgKeys.batt] ?: "battery",
        usb = p[ChgKeys.usb] ?: "usb",
        voltageMax = p[ChgKeys.voltageMax] ?: 0,
        ccc = p[ChgKeys.ccc] ?: 0,
        term = p[ChgKeys.term] ?: 0,
        icl = p[ChgKeys.icl] ?: 0,
        chargeLimit = p[ChgKeys.chargeLimit] ?: 0,
    // 同上：保持与 toStateAsync 一致
    verbose = (p[ChgKeys.verbose] ?: 0) == 1,
        pdDesired = p[ChgKeys.pdDesired] ?: 1,
        loaded = false  // 在非suspend上下文中默认为false
    )

    suspend fun update(f: (ChgUiState)->ChgUiState) {
        ctx.chgStore.edit { p ->
            val n = f(toState(p))
            p[ChgKeys.koPath] = n.koPath
            p[ChgKeys.batt] = n.batt
            p[ChgKeys.usb] = n.usb
            p[ChgKeys.voltageMax] = n.voltageMax
            p[ChgKeys.ccc] = n.ccc
            p[ChgKeys.term] = n.term
            p[ChgKeys.icl] = n.icl
            p[ChgKeys.chargeLimit] = n.chargeLimit
            p[ChgKeys.verbose] = if (n.verbose) 1 else 0
            p[ChgKeys.pdDesired] = n.pdDesired
        }
        runBlocking { refresh() }
    }
}


