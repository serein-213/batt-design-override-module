package com.override.battcaplsp.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.hookSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "hook_settings")

data class HookSettingsState(
    val hookEnabled: Boolean = true,
    val displayCapacity: Int = 0,  // 显示的电池容量(mAh)，0表示使用系统默认
    val useSystemProp: Boolean = true,  // 是否使用系统属性作为数据源
    val customCapacity: Int = 0,  // 自定义容量(mAh)
    val hookTextView: Boolean = true,  // 是否Hook TextView显示
    val hookSharedPrefs: Boolean = true,  // 是否Hook SharedPreferences
    val hookJsonMethods: Boolean = true,  // 是否Hook JSON方法
    val launcherIconEnabled: Boolean = true, // 是否显示桌面入口
)

object HookSettingsKeys {
    val hookEnabled = booleanPreferencesKey("hook_enabled")
    val displayCapacity = intPreferencesKey("display_capacity")
    val useSystemProp = booleanPreferencesKey("use_system_prop")
    val customCapacity = intPreferencesKey("custom_capacity")
    val hookTextView = booleanPreferencesKey("hook_textview")
    val hookSharedPrefs = booleanPreferencesKey("hook_sharedprefs")
    val hookJsonMethods = booleanPreferencesKey("hook_json_methods")
    val launcherIconEnabled = booleanPreferencesKey("launcher_icon_enabled")
}

class HookSettingsRepository(private val context: Context) {
    private val dataStore = context.hookSettingsDataStore

    val flow: Flow<HookSettingsState> = dataStore.data.map { preferences ->
        HookSettingsState(
            hookEnabled = preferences[HookSettingsKeys.hookEnabled] ?: true,
            displayCapacity = preferences[HookSettingsKeys.displayCapacity] ?: 0,
            useSystemProp = preferences[HookSettingsKeys.useSystemProp] ?: true,
            customCapacity = preferences[HookSettingsKeys.customCapacity] ?: 0,
            hookTextView = preferences[HookSettingsKeys.hookTextView] ?: true,
            hookSharedPrefs = preferences[HookSettingsKeys.hookSharedPrefs] ?: true,
            hookJsonMethods = preferences[HookSettingsKeys.hookJsonMethods] ?: true,
            launcherIconEnabled = preferences[HookSettingsKeys.launcherIconEnabled] ?: true,
        )
    }

    suspend fun update(transform: (HookSettingsState) -> HookSettingsState) {
        dataStore.edit { preferences ->
            val current = HookSettingsState(
                hookEnabled = preferences[HookSettingsKeys.hookEnabled] ?: true,
                displayCapacity = preferences[HookSettingsKeys.displayCapacity] ?: 0,
                useSystemProp = preferences[HookSettingsKeys.useSystemProp] ?: true,
                customCapacity = preferences[HookSettingsKeys.customCapacity] ?: 0,
                hookTextView = preferences[HookSettingsKeys.hookTextView] ?: true,
                hookSharedPrefs = preferences[HookSettingsKeys.hookSharedPrefs] ?: true,
                hookJsonMethods = preferences[HookSettingsKeys.hookJsonMethods] ?: true,
                launcherIconEnabled = preferences[HookSettingsKeys.launcherIconEnabled] ?: true,
            )
            val updated = transform(current)
            preferences[HookSettingsKeys.hookEnabled] = updated.hookEnabled
            preferences[HookSettingsKeys.displayCapacity] = updated.displayCapacity
            preferences[HookSettingsKeys.useSystemProp] = updated.useSystemProp
            preferences[HookSettingsKeys.customCapacity] = updated.customCapacity
            preferences[HookSettingsKeys.hookTextView] = updated.hookTextView
            preferences[HookSettingsKeys.hookSharedPrefs] = updated.hookSharedPrefs
            preferences[HookSettingsKeys.hookJsonMethods] = updated.hookJsonMethods
            preferences[HookSettingsKeys.launcherIconEnabled] = updated.launcherIconEnabled
        }
    }
}
