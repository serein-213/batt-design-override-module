package com.override.battcaplsp.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标管理器 - 用于隐藏/显示应用的桌面图标
 */
object LauncherIconManager {
    
    /**
     * 检查桌面图标是否可见
     */
    fun isLauncherIconVisible(context: Context): Boolean {
        val componentName = ComponentName(context, "com.override.battcaplsp.Launcher")
        val state = context.packageManager.getComponentEnabledSetting(componentName)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || 
               state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }
    
    /**
     * 设置桌面图标的可见性
     */
    fun setLauncherIconVisible(context: Context, visible: Boolean) {
        val componentName = ComponentName(context, "com.override.battcaplsp.Launcher")
        val newState = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        
        context.packageManager.setComponentEnabledSetting(
            componentName,
            newState,
            PackageManager.DONT_KILL_APP
        )
    }
    
    /**
     * 切换桌面图标的可见性
     */
    fun toggleLauncherIcon(context: Context): Boolean {
        val currentlyVisible = isLauncherIconVisible(context)
        setLauncherIconVisible(context, !currentlyVisible)
        return !currentlyVisible
    }
}
