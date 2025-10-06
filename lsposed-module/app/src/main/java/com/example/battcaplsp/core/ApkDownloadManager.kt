package com.override.battcaplsp.core

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * APK下载和安装管理器
 */
class ApkDownloadManager(private val context: Context) {
    
    companion object {
        private const val DOWNLOAD_FOLDER = "BatteryOverrideManager"
        private const val APK_FILE_PREFIX = "battery_override_manager"
    }
    
    data class DownloadResult(
        val success: Boolean,
        val filePath: String? = null,
        val downloadId: Long? = null,
        val error: String? = null
    )
    
    data class InstallResult(
        val success: Boolean,
        val error: String? = null
    )
    
    /**
     * 下载APK文件
     */
    suspend fun downloadApk(downloadUrl: String, versionName: String): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            // 创建下载目录
            val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FOLDER)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            // 生成文件名
            val fileName = "${APK_FILE_PREFIX}_${versionName}.apk"
            val filePath = File(downloadDir, fileName)
            
            // 删除已存在的文件
            if (filePath.exists()) {
                filePath.delete()
            }
            
            // 创建下载请求
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("Battery Override Manager 更新")
                .setDescription("正在下载版本 $versionName")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(filePath))
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                .setAllowedOverRoaming(false)
            
            // 开始下载
            val downloadId = downloadManager.enqueue(request)
            
            DownloadResult(
                success = true,
                filePath = filePath.absolutePath,
                downloadId = downloadId
            )
            
        } catch (e: Exception) {
            android.util.Log.e("ApkDownloadManager", "下载APK失败", e)
            DownloadResult(
                success = false,
                error = "下载失败: ${e.message}"
            )
        }
    }
    
    /**
     * 检查下载状态
     */
    suspend fun checkDownloadStatus(downloadId: Long): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                val localUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        DownloadResult(
                            success = true,
                            filePath = localUri
                        )
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                        DownloadResult(
                            success = false,
                            error = "下载失败，错误代码: $reason"
                        )
                    }
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED -> {
                        DownloadResult(
                            success = false,
                            error = "下载进行中..."
                        )
                    }
                    else -> {
                        DownloadResult(
                            success = false,
                            error = "未知下载状态: $status"
                        )
                    }
                }
            } else {
                DownloadResult(
                    success = false,
                    error = "无法查询下载状态"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ApkDownloadManager", "检查下载状态失败", e)
            DownloadResult(
                success = false,
                error = "检查下载状态失败: ${e.message}"
            )
        }
    }
    
    /**
     * 安装APK
     */
    suspend fun installApk(filePath: String): InstallResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext InstallResult(
                    success = false,
                    error = "APK文件不存在"
                )
            }
            
            // 检查文件权限
            if (!file.canRead()) {
                return@withContext InstallResult(
                    success = false,
                    error = "无法读取APK文件"
                )
            }
            
            // 创建安装Intent
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            // 启动安装
            context.startActivity(intent)
            
            InstallResult(success = true)
            
        } catch (e: Exception) {
            android.util.Log.e("ApkDownloadManager", "安装APK失败", e)
            InstallResult(
                success = false,
                error = "安装失败: ${e.message}"
            )
        }
    }
    
}
