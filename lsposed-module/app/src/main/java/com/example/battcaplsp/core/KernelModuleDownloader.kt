package com.override.battcaplsp.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 内核模块下载管理器
 * 负责根据内核版本自动下载对应的 .ko 文件
 */
class KernelModuleDownloader(private val context: Context) {
    
    companion object {
        // GitHub Releases API 基础 URL
        private const val GITHUB_API_BASE = "https://api.github.com/repos/serein-213/batt-design-override-module"
        private const val GITHUB_RELEASES_BASE = "https://github.com/serein-213/batt-design-override-module/releases/download"
        
        // 支持的内核版本到 Android 版本的映射
        private val KERNEL_TO_ANDROID = mapOf(
            "5.4" to "android11",
            "5.10" to "android12", 
            "5.15" to "android13",
            "6.1" to "android14",
            "6.6" to "android15"
        )
        
        // 模块文件名模板（匹配实际的 .ko 文件）
        private val MODULE_TEMPLATES = mapOf(
            "batt_design_override" to "batt_design_override-{android}-{kernel}.ko",
            "chg_param_override" to "chg_param_override-{android}-{kernel}.ko"
        )
    }

    /**
     * 判断资产名是否匹配指定模块/Android版本/内核主次版本，允许补丁号（例如 5.15 或 5.15.192）
     */
    private fun matchesAssetName(
        assetName: String,
        moduleName: String,
        androidVersion: String,
        supportedKernelMajorMinor: String
    ): Boolean {
        val pattern = Regex(
            pattern = "^" +
                Regex.escape(moduleName) +
                "-" + Regex.escape(androidVersion) +
                "-" + Regex.escape(supportedKernelMajorMinor) +
                "(\\.\\d+)?" +
                "\\.ko$"
        )
        return pattern.matches(assetName)
    }

    /**
     * 从资产文件名中提取实际内核版本（最后一段减去后缀），例如
     * batt_design_override-android13-5.15.192.ko -> 5.15.192
     */
    private fun extractKernelFromAssetName(assetName: String): String? {
        if (!assetName.endsWith(".ko")) return null
        val base = assetName.removeSuffix(".ko")
        val lastDash = base.lastIndexOf('-')
        if (lastDash == -1 || lastDash == base.lastIndex) return null
        return base.substring(lastDash + 1)
    }
    
    data class DownloadResult(
        val success: Boolean,
        val message: String,
        val localPath: String? = null,
        val fileSize: Long = 0L
    )
    
    data class GitHubRelease(
        val tagName: String,
        val name: String,
        val assets: List<GitHubAsset>
    )
    
    data class GitHubAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
        val sha256: String? = null
    )
    
    /** 获取最新的 GitHub Release 信息（带 UA 与重试） */
    suspend fun getLatestRelease(): GitHubRelease? = withContext(Dispatchers.IO) {
        val ua = "battcaplsp-app/1.0 (+https://github.com/serein-213)"
        val maxRetries = 3
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                val url = URL("$GITHUB_API_BASE/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", ua)
                
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    // 可能被限流或网络错误；短暂重试
                    lastError = RuntimeException("HTTP $code")
                    android.util.Log.w("KernelModuleDownloader", "HTTP $code on attempt ${attempt + 1}")
                } else {
                    val response = connection.inputStream.bufferedReader().readText()
                    android.util.Log.d("KernelModuleDownloader", "Response length: ${response.length}")
                    
                    // 首先尝试正则解析；失败则宽松匹配 .ko 文件名
                    val parsed = parseGitHubRelease(response)
                    if (parsed != null) {
                        android.util.Log.d("KernelModuleDownloader", "Parsed release: ${parsed.tagName}, assets: ${parsed.assets.size}")
                        return@withContext parsed
                    } else {
                        // 宽松解析
                        val names = Regex("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                            .findAll(response).map { it.groupValues[1] }.toList()
                        val assets = names.map { GitHubAsset(it, "$GITHUB_RELEASES_BASE/unknown/$it", 0) }
                        val release = GitHubRelease(
                            tagName = Regex("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(response)?.groupValues?.getOrNull(1) ?: "unknown",
                            name = Regex("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(response)?.groupValues?.getOrNull(1) ?: "unknown",
                            assets = assets
                        )
                        android.util.Log.d("KernelModuleDownloader", "Fallback parsed: ${release.tagName}, assets: ${release.assets.size}")
                        return@withContext release
                    }
                }
            } catch (e: Exception) {
                lastError = e
                android.util.Log.e("KernelModuleDownloader", "Attempt ${attempt + 1} failed", e)
            }
            // 简单退避
            try { Thread.sleep((800L * (attempt + 1))) } catch (_: Throwable) {}
        }
        android.util.Log.e("KernelModuleDownloader", "All attempts failed", lastError)
        null
    }
    
    /** 解析 GitHub API 响应 */
    private fun parseGitHubRelease(jsonResponse: String): GitHubRelease? {
        try {
            // 简单的 JSON 解析（在实际项目中建议使用 JSON 库）
            val tagNameMatch = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(jsonResponse)
            val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(jsonResponse)
            
            if (tagNameMatch == null || nameMatch == null) return null
            
            val tagName = tagNameMatch.groupValues[1]
            val name = nameMatch.groupValues[1]
            
            // 更简单的 assets 解析：直接查找所有 "name" 和 "browser_download_url" 对
            val assets = mutableListOf<GitHubAsset>()
            val assetNames = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonResponse).map { it.groupValues[1] }.toList()
            val downloadUrls = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonResponse).map { it.groupValues[1] }.toList()
            val sizes = Regex("\"size\"\\s*:\\s*(\\d+)").findAll(jsonResponse).map { it.groupValues[1].toLongOrNull() ?: 0L }.toList()
            
            android.util.Log.d("KernelModuleDownloader", "Found ${assetNames.size} names, ${downloadUrls.size} urls, ${sizes.size} sizes")
            
            // 匹配 name 和 download_url（假设它们按顺序出现）
            val minCount = minOf(assetNames.size, downloadUrls.size)
            for (i in 0 until minCount) {
                val assetName = assetNames[i]
                val downloadUrl = downloadUrls[i]
                val size = if (i < sizes.size) sizes[i] else 0L
                
                android.util.Log.d("KernelModuleDownloader", "Asset: $assetName -> $downloadUrl")
                assets.add(GitHubAsset(assetName, downloadUrl, size))
            }
            
            android.util.Log.d("KernelModuleDownloader", "Parsed ${assets.size} assets")
            return GitHubRelease(tagName, name, assets)
        } catch (e: Exception) {
            android.util.Log.e("KernelModuleDownloader", "Error parsing GitHub release JSON: ${e.message}", e)
            return null
        }
    }
    
    /** 获取内核模块存储目录 */
    private fun getModuleStorageDir(): File {
        val dir = File(context.filesDir, "kernel_modules")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    data class ModuleInfo(
        val name: String,
        val version: String,
        val downloadUrl: String,
        val sha256: String? = null,
        val size: Long = 0L,
        val kernelVersion: String,
        val androidVersion: String
    )
    
    /** 根据内核版本获取可用的模块列表 */
    suspend fun getAvailableModules(kernelVersion: ModuleManager.KernelVersion): List<ModuleInfo> = withContext(Dispatchers.IO) {
        val modules = mutableListOf<ModuleInfo>()
        
        try {
            // 获取最新的 Release
            val release = getLatestRelease() ?: return@withContext emptyList()
            
            // 查找支持的内核版本
            val supportedVersion = findSupportedKernelVersion(kernelVersion.majorMinor)
            if (supportedVersion == null) {
                return@withContext emptyList()
            }
            
            val androidVersion = KERNEL_TO_ANDROID[supportedVersion] ?: return@withContext emptyList()
            
            // 遍历模块模板，查找匹配的 assets（现在匹配 .ko 文件）
            MODULE_TEMPLATES.forEach { (moduleName, template) ->
                val expectedPrefix = template
                    .replace("{android}", androidVersion)
                    .replace("{kernel}", supportedVersion)
                android.util.Log.d("KernelModuleDownloader", "Looking for file like: $expectedPrefix (+patch)")
                
                // 在 release assets 中查找匹配的文件（允许补丁版本号）
                val matchingAsset = release.assets.find { asset ->
                    android.util.Log.d("KernelModuleDownloader", "Checking asset: ${asset.name}")
                    matchesAssetName(asset.name, moduleName, androidVersion, supportedVersion)
                }
                
                if (matchingAsset != null) {
                    android.util.Log.d("KernelModuleDownloader", "Found matching asset: ${matchingAsset.name}")
                    val actualKernel = extractKernelFromAssetName(matchingAsset.name) ?: supportedVersion
                    modules.add(
                        ModuleInfo(
                            name = moduleName,
                            version = release.tagName,
                            downloadUrl = matchingAsset.downloadUrl,
                            sha256 = matchingAsset.sha256,
                            size = matchingAsset.size,
                            kernelVersion = actualKernel,
                            androidVersion = androidVersion
                        )
                    )
                } else {
                    android.util.Log.w("KernelModuleDownloader", "No matching asset found for: $expectedPrefix (allow patch)")
                }
            }
            
        } catch (e: Exception) {
            // 如果获取失败，返回空列表
        }
        
        modules
    }
    
    /** 查找支持的内核版本 */
    private fun findSupportedKernelVersion(majorMinor: String): String? {
        // 优先匹配完全相同的版本
        if (KERNEL_TO_ANDROID.containsKey(majorMinor)) {
            return majorMinor
        }
        
        // 尝试向下兼容匹配
        val targetMajor = majorMinor.split(".").firstOrNull()?.toIntOrNull() ?: return null
        val targetMinor = majorMinor.split(".").getOrNull(1)?.toIntOrNull() ?: return null
        
        return KERNEL_TO_ANDROID.keys
            .map { version ->
                val parts = version.split(".")
                val major = parts.firstOrNull()?.toIntOrNull() ?: 0
                val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
                Triple(version, major, minor)
            }
            .filter { (_, major, minor) -> 
                major <= targetMajor && (major < targetMajor || minor <= targetMinor)
            }
            .maxByOrNull { (_, major, minor) -> major * 1000 + minor }
            ?.first
    }
    
    /** 下载指定模块 */
    suspend fun downloadModule(moduleInfo: ModuleInfo, onProgress: ((Int) -> Unit)? = null): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(moduleInfo.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.setRequestProperty("User-Agent", "battcaplsp-app/1.0 (+https://github.com/serein-213)")
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext DownloadResult(
                    success = false,
                    message = "下载失败: HTTP ${connection.responseCode}"
                )
            }
            
            val contentLength = connection.contentLengthLong
            val storageDir = getModuleStorageDir()
            
            // 使用与 GitHub 文件名相同的命名格式
            val originalFileName = moduleInfo.downloadUrl.substringAfterLast("/")
            val localFile = File(storageDir, originalFileName)
            
            connection.inputStream.use { input ->
                FileOutputStream(localFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        if (contentLength > 0) {
                            val progress = (totalBytesRead * 100 / contentLength).toInt()
                            onProgress?.invoke(progress)
                        }
                    }
                }
            }
            
            // 验证文件完整性（如果提供了 SHA256）
            if (moduleInfo.sha256 != null) {
                val actualSha256 = calculateSHA256(localFile)
                if (actualSha256 != moduleInfo.sha256) {
                    localFile.delete()
                    return@withContext DownloadResult(
                        success = false,
                        message = "文件校验失败: SHA256 不匹配"
                    )
                }
            }
            
            DownloadResult(
                success = true,
                message = "下载成功",
                localPath = localFile.absolutePath,
                fileSize = localFile.length()
            )
            
        } catch (e: Exception) {
            DownloadResult(
                success = false,
                message = "下载异常: ${e.message}"
            )
        }
    }
    
    /** 计算文件 SHA256 */
    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /** 检查本地是否已有对应版本的模块 */
    fun getLocalModule(moduleName: String, version: String, kernelVersion: String): File? {
        val storageDir = getModuleStorageDir()
        
        // 尝试多种可能的文件名格式
        val possibleNames = listOf(
            "$moduleName-$version-$kernelVersion.ko",  // 新格式：batt_design_override-1.2.1-5.15.ko
            "$moduleName-$kernelVersion.ko",           // 简化格式：batt_design_override-5.15.ko
            "$moduleName.ko"                           // 通用格式：batt_design_override.ko
        )
        
        for (fileName in possibleNames) {
            val localFile = File(storageDir, fileName)
            if (localFile.exists() && localFile.length() > 0) {
                return localFile
            }
        }
        
        return null
    }
    
    /** 检查本地是否已有对应模块（重载方法保持兼容性） */
    fun getLocalModule(moduleName: String, version: String): File? {
        return getLocalModule(moduleName, version, "")
    }
    
    /** 获取所有本地模块 */
    fun getLocalModules(): List<Pair<String, File>> {
        val storageDir = getModuleStorageDir()
        return storageDir.listFiles { file ->
            file.isFile && file.name.endsWith(".ko")
        }?.map { file ->
            val name = file.nameWithoutExtension
            name to file
        } ?: emptyList()
    }
    
    /** 清理旧版本模块 */
    fun cleanupOldModules(keepLatest: Int = 3) {
        val storageDir = getModuleStorageDir()
        val moduleGroups = storageDir.listFiles { file ->
            file.isFile && file.name.endsWith(".ko")
        }?.groupBy { file ->
            // 按模块名分组
            file.name.substringBeforeLast("-")
        } ?: return
        
        moduleGroups.forEach { (_, files) ->
            if (files.size > keepLatest) {
                files.sortedByDescending { it.lastModified() }
                    .drop(keepLatest)
                    .forEach { it.delete() }
            }
        }
    }
}
