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

    /** 读取 params.conf 内容为键值 Map（仅大写 KEY=VALUE 结构，不解析注释）。 */
    fun readConf(context: Context): Map<String,String> = runBlocking {
        val path = getModuleConfPath(context)
        return@runBlocking try {
            val f = java.io.File(path)
            if (!f.exists()) emptyMap() else buildMap {
                f.readLines().forEach { line ->
                    val ln = line.trim()
                    if (ln.startsWith("#")) return@forEach
                    val eq = ln.indexOf('=')
                    if (eq > 0) {
                        val k = ln.substring(0, eq).trim()
                        if (k.matches(Regex("[A-Z0-9_]+"))) {
                            var v = ln.substring(eq + 1).trim()
                            if ((v.startsWith('"') && v.endsWith('"') && v.length >= 2)) {
                                v = v.substring(1, v.length - 1)
                            }
                            put(k, v)
                        }
                    }
                }
            }
        } catch (e: Exception) { emptyMap() }
    }

        /**
         * 同步电池模块配置到 params.conf
         * 关键修复点：
         * 1. 原子写入：使用临时文件 + mv，避免中途崩溃造成不完整行（之前出现未闭合引号）。
         * 2. 值清洗：去除换行、回车与首尾空白，转义双引号，防止多行注入将后续键变成同一变量值。
         * 3. 仅替换目标键：保留其它用户或模块写入的键，减少意外丢失。
         * 4. 范围校验：对 design 数值做基本限制（>0 且不超过 10^9 uAh/uWh，避免写入极端错误）。
         */
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

                fun sanitize(raw: String): String = raw.trim()
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .replace(Regex("\\s+"), " ")
                        .take(120) // 避免过长污染文件
                        .replace("\"", "\\\"")

                val bn = sanitize(battName.ifBlank { "battery" })
                val mn = sanitize(modelName)
                val oa = if (overrideAny) 1 else 0
                val vb = if (verbose) 1 else 0

                // 基础范围防护：允许 0 表示不覆盖；上限做一个宽松限制（1e9 uAh ≈ 1,000,000 mAh）
                val duah = designUah.coerceIn(0, 1_000_000_000L)
                val duwh = designUwh.coerceIn(0, 1_000_000_000L)

                // 需要更新的键集合（全部大写与 service.sh 约定一致）
                val keys = listOf("BATT_NAME","DESIGN_UAH","DESIGN_UWH","MODEL_NAME","OVERRIDE_ANY","VERBOSE")

                val script = """
                CONF="${confPath}"
                mkdir -p "${'$'}(dirname "${'$'}CONF")" || exit 1
                TMP="${'$'}CONF.tmp.$$"
                # 如果存在旧文件，先过滤掉将要更新的键，保留其它键，再写入临时文件
                if [ -f "${'$'}CONF" ]; then
                    grep -E '^[A-Z0-9_]+=' "${'$'}CONF" | grep -v -E '^(BATT_NAME|DESIGN_UAH|DESIGN_UWH|MODEL_NAME|OVERRIDE_ANY|VERBOSE)=' > "${'$'}TMP" || true
                else
                    : > "${'$'}TMP" || exit 1
                fi
                {
                    echo "BATT_NAME=\"${bn}\""
                    echo "DESIGN_UAH=${duah}"
                    echo "DESIGN_UWH=${duwh}"
                    echo "MODEL_NAME=\"${mn}\""
                    echo "OVERRIDE_ANY=${oa}"
                    echo "VERBOSE=${vb}"
                } >> "${'$'}TMP"
                # 原子替换
                mv -f "${'$'}TMP" "${'$'}CONF"
                chmod 0644 "${'$'}CONF" 2>/dev/null || true
                """.trimIndent()
                return RootShell.exec(script)
        }

        /**
         * 同步充电模块键值。采用与 syncBatt 相同的原子写 / 清洗策略。
         * 支持的键会在 service.sh 中被映射为 /proc 写入参数。
         */
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
                fun sanitize(raw: String): String = raw.trim()
                        .replace("\r", " ")
                        .replace("\n", " ")
                        .replace(Regex("\\s+"), " ")
                        .take(120)
                        .replace("\"", "\\\"")

                val b = sanitize(batt.ifBlank { "battery" })
                val u = sanitize(usb.ifBlank { "usb" })
                val vb = if (verbose) 1 else 0
                val pd = if (pdDesired == 0) 0 else 1

                // 数值基本范围裁剪，0 表示不启用该项
                fun clamp(v: Long): Long = v.coerceIn(0, 10_000_000_000L) // 1e10 µ(x) 作为上限防止写入异常
                val vmax = clamp(voltageMax)
                val cccV = clamp(ccc)
                val termV = clamp(term)
                val iclV = clamp(icl)
                val cl = chargeLimit.coerceIn(0, 100)

                val script = """
                CONF="${confPath}"
                mkdir -p "${'$'}(dirname "${'$'}CONF")" || exit 1
                TMP="${'$'}CONF.tmp.$$"
                if [ -f "${'$'}CONF" ]; then
                    grep -E '^[A-Z0-9_]+=' "${'$'}CONF" | grep -v -E '^(CHG_APPLY_ON_BOOT|CHG_BATT_NAME|CHG_USB_NAME|CHG_VOLTAGE_MAX_UV|CHG_CCC_UA|CHG_TERM_UA|CHG_ICL_UA|CHG_CHARGE_LIMIT|PD_HELPER_ENABLE|PD_DESIRED|VERBOSE)=' > "${'$'}TMP" || true
                else
                    : > "${'$'}TMP" || exit 1
                fi
                {
                    echo "CHG_APPLY_ON_BOOT=1"
                    echo "CHG_BATT_NAME=\"${b}\""
                    echo "CHG_USB_NAME=\"${u}\""
                    echo "CHG_VOLTAGE_MAX_UV=${vmax}"
                    echo "CHG_CCC_UA=${cccV}"
                    echo "CHG_TERM_UA=${termV}"
                    echo "CHG_ICL_UA=${iclV}"
                    echo "CHG_CHARGE_LIMIT=${cl}"
                    echo "VERBOSE=${vb}"
                    echo "PD_HELPER_ENABLE=1"
                    echo "PD_DESIRED=${pd}"
                } >> "${'$'}TMP"
                mv -f "${'$'}TMP" "${'$'}CONF"
                chmod 0644 "${'$'}CONF" 2>/dev/null || true
                """.trimIndent()
                return RootShell.exec(script)
        }
}


