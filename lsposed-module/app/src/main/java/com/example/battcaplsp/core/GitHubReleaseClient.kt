package com.override.battcaplsp.core

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release API 客户端
 * 用于检查应用新版本和下载APK
 */
class GitHubReleaseClient {
    
    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val REPO_OWNER = "serein-213" // 需要替换为实际的仓库所有者
        private const val REPO_NAME = "batt-design-override-module" // 需要替换为实际的仓库名
        private const val RELEASE_TAG_PREFIX = "app-v" // APK release的tag前缀
    }
    
    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String,
        val publishedAt: String
    )
    
    data class VersionCheckResult(
        val hasUpdate: Boolean,
        val currentVersion: String,
        val latestVersion: String?,
        val releaseInfo: ReleaseInfo?,
        val error: String? = null
    )
    
    /**
     * 检查是否有新版本
     */
    suspend fun checkForUpdates(context: Context): VersionCheckResult = withContext(Dispatchers.IO) {
        try {
            val currentVersion = getCurrentVersion(context)
            val latestRelease = getLatestRelease()
            
            if (latestRelease == null) {
                return@withContext VersionCheckResult(
                    hasUpdate = false,
                    currentVersion = currentVersion,
                    latestVersion = null,
                    releaseInfo = null,
                    error = "无法获取最新版本信息"
                )
            }
            
            val hasUpdate = compareVersions(currentVersion, latestRelease.versionName) < 0
            
            VersionCheckResult(
                hasUpdate = hasUpdate,
                currentVersion = currentVersion,
                latestVersion = latestRelease.versionName,
                releaseInfo = latestRelease
            )
            
        } catch (e: Exception) {
            android.util.Log.e("GitHubReleaseClient", "检查更新失败", e)
            VersionCheckResult(
                hasUpdate = false,
                currentVersion = getCurrentVersion(context),
                latestVersion = null,
                releaseInfo = null,
                error = "检查更新失败: ${e.message}"
            )
        }
    }
    
    /**
     * 获取当前应用版本
     */
    private fun getCurrentVersion(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val pi = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            (pi.versionName ?: run {
                val vc = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toString() else ""; if (vc.isNotBlank()) vc else null
            }) ?: "未知版本"
        } catch (e: Exception) {
            android.util.Log.w("GitHubReleaseClient", "getCurrentVersion failed: ${e.javaClass.simpleName} ${e.message}")
            "未知版本"
        }
    }
    
    /**
     * 获取最新的Release信息
     */
    private suspend fun getLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$GITHUB_API_BASE/repos/$REPO_OWNER/$REPO_NAME/releases")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "BatteryOverrideManager/1.0")
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                android.util.Log.e("GitHubReleaseClient", "API请求失败: $responseCode")
                return@withContext null
            }
            
            val response = connection.inputStream.bufferedReader().readText()
            val releases = org.json.JSONArray(response)
            
            // 查找最新的APK release
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                val tagName = release.getString("tag_name")
                
                // 检查是否是APK release
                if (tagName.startsWith(RELEASE_TAG_PREFIX)) {
                    val assets = release.getJSONArray("assets")
                    var downloadUrl: String? = null
                    
                    // 查找APK文件
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val fileName = asset.getString("name")
                        if (fileName.endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }
                    
                    if (downloadUrl != null) {
                        return@withContext ReleaseInfo(
                            tagName = tagName,
                            versionName = tagName.removePrefix(RELEASE_TAG_PREFIX),
                            versionCode = extractVersionCode(release),
                            downloadUrl = downloadUrl,
                            releaseNotes = release.optString("body", ""),
                            publishedAt = release.getString("published_at")
                        )
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            android.util.Log.e("GitHubReleaseClient", "获取Release信息失败", e)
            null
        }
    }
    
    /**
     * 从Release信息中提取版本号
     */
    private fun extractVersionCode(release: JSONObject): Int {
        return try {
            // 尝试从tag_name中提取版本号
            val tagName = release.getString("tag_name")
            val versionPart = tagName.removePrefix(RELEASE_TAG_PREFIX)
            
            // 简单的版本号解析，如 "1.2.3" -> 123
            val parts = versionPart.split(".")
            var versionCode = 0
            for (i in parts.indices) {
                val part = parts[i].toIntOrNull() ?: 0
                versionCode = versionCode * 100 + part
            }
            versionCode
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * 比较版本号
     * 返回值: -1 (当前版本 < 目标版本), 0 (相等), 1 (当前版本 > 目标版本)
     */
    private fun compareVersions(current: String, target: String): Int {
        return try {
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val targetParts = target.split(".").map { it.toIntOrNull() ?: 0 }
            
            val maxLength = maxOf(currentParts.size, targetParts.size)
            
            for (i in 0 until maxLength) {
                val currentPart = currentParts.getOrElse(i) { 0 }
                val targetPart = targetParts.getOrElse(i) { 0 }
                
                when {
                    currentPart < targetPart -> return -1
                    currentPart > targetPart -> return 1
                }
            }
            0
        } catch (e: Exception) {
            0
        }
    }
}
