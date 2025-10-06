package com.override.battcaplsp.core

import android.content.Context
import kotlinx.coroutines.runBlocking

object ConfigSync {
    // 动态获取模块路径，优先使用动态模块，兼容旧模块
    private fun getModuleConfPath(context: Context): String {
        return runBlocking {
            try {
                val magiskMgr = MagiskModuleManager(context)
                
                // 检查动态模块是否存在
                val dynamicPath = "/data/adb/modules/batt-design-override-dynamic/common/params.conf"
                val checkDynamic = RootShell.exec("[ -d '/data/adb/modules/batt-design-override-dynamic' ]")
                if (checkDynamic.code == 0) {
                    return@runBlocking dynamicPath
                }
                
                // 检查旧模块是否存在
                val legacyPath = "/data/adb/modules/batt-design-override/common/params.conf"
                val checkLegacy = RootShell.exec("[ -d '/data/adb/modules/batt-design-override' ]")
                if (checkLegacy.code == 0) {
                    return@runBlocking legacyPath
                }
                
                // 默认使用动态模块路径（应用会创建这个路径）
                dynamicPath
            } catch (e: Exception) {
                // 出错时使用动态模块路径
                "/data/adb/modules/batt-design-override-dynamic/common/params.conf"
            }
        }
    }

    suspend fun syncBatt(
        context: Context,
        battName: String,
        designUah: Long,
        designUwh: Long,
        modelName: String,
        overrideAny: Boolean,
        verbose: Boolean
    ): RootShell.ExecResult {
        val confPath = getModuleConfPath(context)
        val bn = battName.trim()
        val mn = modelName
        val oa = if (overrideAny) 1 else 0
        val vb = if (verbose) 1 else 0
        val script = """
        CONF="${confPath}"
        mkdir -p "${'$'}(dirname "${'$'}CONF")"
        touch "${'$'}CONF"
        set_kv_str(){ k="${'$'}1"; v="${'$'}2"; if grep -q "^${'$'}k=" "${'$'}CONF"; then sed -i -E "s|^${'$'}k=.*|${'$'}k=\\\"${'$'}v\\\"|" "${'$'}CONF"; else echo "${'$'}k=\\\"${'$'}v\\\"" >> "${'$'}CONF"; fi; }
        set_kv_int(){ k="${'$'}1"; v="${'$'}2"; if grep -q "^${'$'}k=" "${'$'}CONF"; then sed -i -E "s|^${'$'}k=.*|${'$'}k=${'$'}v|" "${'$'}CONF"; else echo "${'$'}k=${'$'}v" >> "${'$'}CONF"; fi; }
        set_kv_str BATT_NAME "${bn}"
        set_kv_int DESIGN_UAH ${designUah}
        set_kv_int DESIGN_UWH ${designUwh}
        set_kv_str MODEL_NAME "${mn}"
        set_kv_int OVERRIDE_ANY ${oa}
        set_kv_int VERBOSE ${vb}
        """.trimIndent()
        return RootShell.exec(script)
    }

    suspend fun syncChg(
        context: Context,
        batt: String,
        usb: String,
        voltageMax: Long,
        ccc: Long,
        term: Long,
        icl: Long,
        chargeLimit: Int,
        verbose: Boolean,
        pdDesired: Int
    ): RootShell.ExecResult {
        val confPath = getModuleConfPath(context)
        val vb = if (verbose) 1 else 0
        val pd = if (pdDesired == 0) 0 else 1
        val script = """
        CONF="${confPath}"
        mkdir -p "${'$'}(dirname "${'$'}CONF")"
        touch "${'$'}CONF"
        set_kv_str(){ k="${'$'}1"; v="${'$'}2"; if grep -q "^${'$'}k=" "${'$'}CONF"; then sed -i -E "s|^${'$'}k=.*|${'$'}k=\\\"${'$'}v\\\"|" "${'$'}CONF"; else echo "${'$'}k=\\\"${'$'}v\\\"" >> "${'$'}CONF"; fi; }
        set_kv_int(){ k="${'$'}1"; v="${'$'}2"; if grep -q "^${'$'}k=" "${'$'}CONF"; then sed -i -E "s|^${'$'}k=.*|${'$'}k=${'$'}v|" "${'$'}CONF"; else echo "${'$'}k=${'$'}v" >> "${'$'}CONF"; fi; }
        # enable apply on boot and pd helper by default for persistence
        set_kv_int CHG_APPLY_ON_BOOT 1
        set_kv_str CHG_BATT_NAME "${batt}"
        set_kv_str CHG_USB_NAME "${usb}"
        set_kv_int CHG_VOLTAGE_MAX_UV ${voltageMax}
        set_kv_int CHG_CCC_UA ${ccc}
        set_kv_int CHG_TERM_UA ${term}
        set_kv_int CHG_ICL_UA ${icl}
        set_kv_int CHG_CHARGE_LIMIT ${chargeLimit}
        set_kv_int VERBOSE ${vb}
        set_kv_int PD_HELPER_ENABLE 1
        set_kv_int PD_DESIRED ${pd}
        """.trimIndent()
        return RootShell.exec(script)
    }
}


