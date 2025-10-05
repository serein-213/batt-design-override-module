package com.override.battcaplsp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.override.battcaplsp.core.Keys
import com.override.battcaplsp.core.HookSettingsKeys

class SettingsProvider : ContentProvider() {
    private val Context.dataStore by preferencesDataStore(name = "batt_override")
    private val Context.hookSettingsDataStore by preferencesDataStore(name = "hook_settings")

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        when (uri.path) {
            "/capacity" -> {
                val capMah = runBlocking {
                    val p = context!!.dataStore.data.first()
                    val uah = (p[Keys.designUah] ?: 0)
                    (uah / 1000).toString()
                }
                return SingleStringCursor(capMah)
            }
            "/hook_settings" -> {
                val settings = runBlocking {
                    val p = context!!.hookSettingsDataStore.data.first()
                    val isMiui = try {
                        val clz = Class.forName("android.os.SystemProperties")
                        val get = clz.getMethod("get", String::class.java, String::class.java)
                        val v = get.invoke(null, "ro.miui.ui.version.name", "") as String
                        v.isNotEmpty() || android.os.Build.MANUFACTURER.contains("Xiaomi", true)
                    } catch (_: Throwable) { android.os.Build.MANUFACTURER.contains("Xiaomi", true) }
                    HookSettingsCursor(
                        hookEnabled = if (isMiui) (p[HookSettingsKeys.hookEnabled] ?: true) else false,
                        useSystemProp = p[HookSettingsKeys.useSystemProp] ?: true,
                        customCapacity = p[HookSettingsKeys.customCapacity] ?: 0,
                        displayCapacity = p[HookSettingsKeys.displayCapacity] ?: 0,
                        hookTextView = p[HookSettingsKeys.hookTextView] ?: true,
                        hookSharedPrefs = p[HookSettingsKeys.hookSharedPrefs] ?: true,
                        hookJsonMethods = p[HookSettingsKeys.hookJsonMethods] ?: true
                    )
                }
                return settings
            }
        }
        return null
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/string"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}

private class SingleStringCursor(private val value: String) : android.database.MatrixCursor(arrayOf("value")) {
    init { addRow(arrayOf(value)) }
}

private class HookSettingsCursor(
    hookEnabled: Boolean,
    useSystemProp: Boolean,
    customCapacity: Int,
    displayCapacity: Int,
    hookTextView: Boolean,
    hookSharedPrefs: Boolean,
    hookJsonMethods: Boolean
) : android.database.MatrixCursor(arrayOf(
    "hook_enabled", "use_system_prop", "custom_capacity", "display_capacity",
    "hook_textview", "hook_sharedprefs", "hook_json_methods"
)) {
    init {
        addRow(arrayOf(
            if (hookEnabled) 1 else 0,
            if (useSystemProp) 1 else 0,
            customCapacity,
            displayCapacity,
            if (hookTextView) 1 else 0,
            if (hookSharedPrefs) 1 else 0,
            if (hookJsonMethods) 1 else 0
        ))
    }
}


