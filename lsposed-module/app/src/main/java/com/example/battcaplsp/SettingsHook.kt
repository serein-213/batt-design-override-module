package com.override.battcaplsp

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import android.widget.TextView

class SettingsHook : IXposedHookLoadPackage {
    private val TAG = "BattCapLSP"
    private val KEY_BASIC = "basic_info_key"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.settings") return
        // Gate on MIUI devices only
        if (!isMiui()) return
        try {
            // 钩住具体实现类 EditorImpl.putString(String, String)
            val editorImpl = XposedHelpers.findClass("android.app.SharedPreferencesImpl\$EditorImpl", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(editorImpl, "putString", String::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        var value = param.args[1] as? String ?: return
                        if (key != KEY_BASIC) return

                        val cap = getTargetCap()
                        if (cap <= 0) return

                        val newVal = rewriteBasicInfoValue(value, cap)
                        if (newVal != null && newVal != value) {
                            param.args[1] = newVal
                            Log.i(TAG, "patched basic_info_key to ${cap}mAh")
                        }
                    }
                }
            )

            // 读取侧兜底：钩住 SharedPreferencesImpl.getString(String, String)
            val spImpl = XposedHelpers.findClass("android.app.SharedPreferencesImpl", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(spImpl, "getString", String::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key != KEY_BASIC) return
                        val original = param.result as? String ?: return
                        val cap = getTargetCap()
                        if (cap <= 0) return
                        val newVal = rewriteBasicInfoValue(original, cap)
                        if (newVal != null && newVal != original) {
                            param.result = newVal
                            Log.i(TAG, "patched getString(basic_info_key) to ${cap}mAh")
                        }
                    }
                }
            )

            // 扩展：尝试钩住 ParseMiShopDataUtils 与 MiuiSharedPreferencesUtils 相关方法，对 JSON 字符串参数/返回值做重写
            hookJsonRewriters(lpparam, "com.android.settings.device.ParseMiShopDataUtils")
            hookJsonRewriters(lpparam, "com.android.settings.utils.MiuiSharedPreferencesUtils")

            // 最后兜底：TextView.setText 重写展示字符串中的 mAh 数值（仅限 Settings 包）
            XposedHelpers.findAndHookMethod(TextView::class.java, "setText", CharSequence::class.java, TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cap = getTargetCap()
                        if (cap <= 0) return
                        val cs = param.args[0] as? CharSequence ?: return
                        val s = cs.toString()
                        if (!s.contains("mAh")) return
                        // 只替换常见的 “4500mAh(typ)” / “4500mAh” 数字部分
                        val replaced = s.replace(Regex("(?<![0-9])[0-9]{3,5}(?=mAh(\\(typ\\))?)"), cap.toString())
                        if (replaced != s) {
                            param.args[0] = replaced
                            Log.i(TAG, "patched TextView text to ${cap}mAh")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hook error: ${t.message}")
        }
    }

    private fun isMiui(): Boolean {
        return try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            val v = get.invoke(null, "ro.miui.ui.version.name", "").toString()
            v.isNotEmpty() || android.os.Build.MANUFACTURER.contains("Xiaomi", true)
        } catch (_: Throwable) {
            android.os.Build.MANUFACTURER.contains("Xiaomi", true)
        }
    }

    private fun hookJsonRewriters(lpparam: XC_LoadPackage.LoadPackageParam, className: String) {
        val clz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: return
        for (m in clz.declaredMethods) {
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cap = getTargetCap()
                        if (cap <= 0) return
                        for (i in param.args.indices) {
                            val arg = param.args[i]
                            if (arg is String && looksLikeBasicInfoJson(arg)) {
                                val newVal = rewriteBasicInfoValue(arg, cap)
                                if (newVal != null && newVal != arg) {
                                    param.args[i] = newVal
                                    Log.i(TAG, "rewrote JSON arg in ${className}.${m.name}")
                                }
                            }
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cap = getTargetCap()
                        if (cap <= 0) return
                        val res = param.result
                        if (res is String && looksLikeBasicInfoJson(res)) {
                            val newRes = rewriteBasicInfoValue(res, cap)
                            if (newRes != null && newRes != res) {
                                param.result = newRes
                                Log.i(TAG, "rewrote JSON result in ${className}.${m.name}")
                            }
                        }
                    }
                })
            } catch (_: Throwable) {
            }
        }
    }

    private fun rewriteBasicInfoValue(value: String, cap: Int): String? {
        // 先尝试按 JSON 解析；如失败再尝试把 &quot; 还原成引号再解析
        fun tryParseAndRewrite(src: String): String? {
            return try {
                val obj = JSONObject(src)
                if (obj.has("BasicItems")) {
                    val arr: JSONArray = obj.getJSONArray("BasicItems")
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val title = item.optString("Title")
                        if (title == "电池容量") {
                            val old = item.optString("Summary")
                            val suffix = if (old.contains("(typ)")) "mAh(typ)" else "mAh"
                            item.put("Summary", "${cap}${suffix}")
                            return obj.toString()
                        }
                    }
                }
                null
            } catch (_: Throwable) { null }
        }
        return tryParseAndRewrite(value) ?: tryParseAndRewrite(value.replace("&quot;", '"'.toString()))
    }

    private fun looksLikeBasicInfoJson(s: String): Boolean {
        // 简单判断，避免误伤其他 JSON
        return s.contains("BasicItems") && s.contains("电池容量")
    }

    private fun getTargetCap(): Int {
        // 优先通过 Provider 从 HookSettings DataStore 读取
        try {
            val clzCtx = Class.forName("android.app.ActivityThread")
            val current = clzCtx.getMethod("currentApplication").invoke(null)
            val ctx = current as android.content.Context
            
            // 尝试读取Hook设置
            val hookUri = android.net.Uri.parse("content://com.override.battcaplsp.provider/hook_settings")
            ctx.contentResolver.query(hookUri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val hookEnabled = c.getInt(c.getColumnIndexOrThrow("hook_enabled")) != 0
                    if (!hookEnabled) return -1  // Hook被禁用
                    
                    val useSystemProp = c.getInt(c.getColumnIndexOrThrow("use_system_prop")) != 0
                    if (useSystemProp) {
                        // 使用系统属性
                        val capUri = android.net.Uri.parse("content://com.override.battcaplsp.provider/capacity")
                        ctx.contentResolver.query(capUri, null, null, null, null)?.use { capC ->
                            if (capC.moveToFirst()) {
                                val v = capC.getString(0)
                                val n = v.toIntOrNull()
                                if (n != null && n > 0) return n
                            }
                        }
                    } else {
                        // 使用自定义容量
                        val customCap = c.getInt(c.getColumnIndexOrThrow("custom_capacity"))
                        if (customCap > 0) return customCap
                    }
                }
            }
        } catch (_: Throwable) { }
        
        // 兜底：读取系统属性（兼容旧版本）
        return try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            val cap = get.invoke(null, "persist.sys.batt.capacity_mah", "").toString()
            cap.toIntOrNull() ?: -1
        } catch (_: Throwable) { -1 }
    }
}
