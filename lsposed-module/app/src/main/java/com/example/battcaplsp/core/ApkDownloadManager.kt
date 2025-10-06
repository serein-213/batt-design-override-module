package com.override.battcaplsp.core

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * APK下载和安装管理器
 */
class ApkDownloadManager(private val context: Context) {
    
    companion object {
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

    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val status: Int, // DownloadManager.COLUMN_STATUS
        val localUri: String?
    ) {
        val percent: Int = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
        val completed: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
        val failed: Boolean get() = status == DownloadManager.STATUS_FAILED
    }
    
    /**
     * 下载APK文件
     */
    suspend fun downloadApk(downloadUrl: String, versionName: String): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            // 直接使用公共 Download 根目录，不再创建子目录
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
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
     * 查询下载进度（非阻塞）。
     */
    suspend fun queryProgress(downloadId: Long): Progress? = withContext(Dispatchers.IO) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val c = dm.query(DownloadManager.Query().setFilterById(downloadId)) ?: return@withContext null
            c.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val localIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                val bytes = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L
                val total = if (totalIdx >= 0) cursor.getLong(totalIdx) else 0L
                val local = if (localIdx >= 0) cursor.getString(localIdx) else null
                return@withContext Progress(bytes, total, status, local)
            }
        } catch (e: Exception) {
            android.util.Log.e("ApkDownloadManager", "queryProgress error", e)
            null
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
        val file = File(filePath)
        if (!file.exists()) return@withContext InstallResult(false, "APK文件不存在")
        if (!file.canRead()) return@withContext InstallResult(false, "无法读取APK文件")
        if (file.length() < 1024) return@withContext InstallResult(false, "APK文件过小/不完整")
        // ZIP 完整性
        try { java.util.zip.ZipFile(file).close() } catch (_: Throwable) {
            return@withContext InstallResult(false, "APK文件损坏或未完全下载")
        }
        // 解析包信息（确认能被系统识别）
        runCatching {
            val pm = context.packageManager
            if (pm.getPackageArchiveInfo(file.absolutePath, 0) == null) {
                return@withContext InstallResult(false, "无法解析APK，可能未完成")
            }
        }.getOrElse { return@withContext InstallResult(false, "解析失败:${it.message}") }

        // 使用 FileProvider content:// 避免 FileUri 暴露
        val contentUri = try {
            FileProvider.getUriForFile(context, "com.override.battcaplsp.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            return@withContext InstallResult(false, "FileProvider路径不匹配:${e.message}")
        }

        // 优先尝试 root 静默安装（避免用户交互 & 未知来源授权流程）
        val rootSilentAttempt = try {
            // 简单检测 root：调用 id | grep uid=0
            val rootCheck = RootShell.exec("id | grep -q 'uid=0' && echo yes || echo no")
            if (rootCheck.out.trim() == "yes") {
                // 使用 pm install -r (保留数据)，支持降级需 -d（不默认加）
                val installCmd = "pm install -r ${RootShell.shellArg(file.absolutePath)} 2>&1"
                val res = RootShell.exec(installCmd)
                android.util.Log.d("ApkDownloadManager", "root silent install output: code=${res.code} out=${res.out} err=${res.err}")
                if (res.out.contains("Success", ignoreCase = true)) {
                    return@withContext InstallResult(true)
                }
                // 针对部分ROM输出在 err
                if (res.err.contains("Success", ignoreCase = true)) {
                    return@withContext InstallResult(true)
                }
                // 如果提示签名或版本问题，直接返回错误，不再弹出UI
                if (res.out.contains("INSTALL_FAILED", true) || res.err.contains("INSTALL_FAILED", true)) {
                    return@withContext InstallResult(false, "root静默安装失败: ${res.out.ifBlank { res.err }.take(160)}")
                }
                // 未明确失败则继续走普通安装回退
                false
            } else false
        } catch (e: Exception) {
            android.util.Log.w("ApkDownloadManager", "root silent install exception: ${e.message}")
            false
        }
        if (!rootSilentAttempt) {
            // 仅在非 root 成功静默安装时才需要用户权限检查
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val canInstall = context.packageManager.canRequestPackageInstalls()
                if (!canInstall) {
                    withContext(Dispatchers.Main) {
                        try {
                            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(settingsIntent)
                        } catch (_: Exception) { /* ignore */ }
                    }
                    return@withContext InstallResult(false, "需要授予“安装未知应用”权限，请返回后重试")
                }
            }
        } else {
            // root 静默已成功
            return@withContext InstallResult(true)
        }

        // 主线程启动 Activity
        try {
            withContext(Dispatchers.Main) {
                val pm = context.packageManager
                val flagBase = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                val intents = listOf(
                    Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        data = contentUri
                        type = "application/vnd.android.package-archive"
                        addFlags(flagBase)
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                        putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    },
                    // Fallback: 某些 ROM 不支持 ACTION_INSTALL_PACKAGE 解析时使用标准 VIEW
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "application/vnd.android.package-archive")
                        addFlags(flagBase)
                    }
                )
                val usable = intents.firstOrNull { it.resolveActivity(pm) != null }
                if (usable == null) {
                    throw ActivityNotFoundException("无可用安装Activity (ACTION_INSTALL_PACKAGE/VIEW 均未解析)")
                }
                context.startActivity(usable)
            }
        } catch (e: ActivityNotFoundException) {
            return@withContext InstallResult(false, "未找到安装界面: ${e.message}")
        } catch (e: SecurityException) {
            return@withContext InstallResult(false, "权限不足: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("ApkDownloadManager", "启动安装失败", e)
            return@withContext InstallResult(false, e.message ?: "未知错误")
        }
        InstallResult(true)
    }
    
}
