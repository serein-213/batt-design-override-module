package com.override.battcaplsp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.override.battcaplsp.core.ModuleManager
import com.override.battcaplsp.core.ParamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class BootCompletedReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return
        val mm = ModuleManager()
        val repo = ParamRepository(context, mm)
        scope.launch {
            // 读取 DataStore，若 koPath 存在则尝试加载并应用参数（只设置非空）
            val state = repo.flow.first()
            val initial = mapOf(
                "design_uah" to state.designUah.takeIf { it > 0 }?.toString(),
                "design_uwh" to state.designUwh.takeIf { it > 0 }?.toString(),
                "model_name" to state.modelName.ifBlank { null },
                "batt_name" to state.battName.ifBlank { null },
                "override_any" to if (state.overrideAny) "1" else null,
                "verbose" to if (state.verbose) "1" else null
            )
            mm.load(state.koPath, initial)
        }
    }
}


