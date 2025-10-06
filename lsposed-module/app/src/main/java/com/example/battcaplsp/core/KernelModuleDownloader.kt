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
        
        // 支持的内核版本到 Android 版本的映射（仅用于显示，可为空）
        private val KERNEL_TO_ANDROID = mapOf(
            "5.4" to "android11",
            "5.10" to "android12",
            "5.15" to "android13",
            "6.1" to "android14",
            "6.6" to "android15"
        )

        // 需要匹配的模块名
        private val MODULE_NAMES = listOf(
            "batt_design_override",
            "chg_param_override"
        )
    }

    /**
     * 判断资产名是否匹配指定模块/内核主次版本，允许：
     * - 带或不带 android 段（如 android13- 可选）
     * - 补丁号（例如 5.15 或 5.15.192）
     */
    private fun matchesAssetName(
        assetName: String,
        moduleName: String,
        supportedKernelMajorMinor: String
    ): Boolean {
        val pattern = Regex(
            pattern = "^" +
                Regex.escape(moduleName) +
                "-(?:android\\d{2}-)?" +
                Regex.escape(supportedKernelMajorMinor) +
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
        return@withContext getAllReleases().firstOrNull()
    }
    
    /** 获取所有 GitHub Releases 信息（最新优先，支持回退查找） */
    suspend fun getAllReleases(): List<GitHubRelease> = withContext(Dispatchers.IO) {
        val ua = "battcaplsp-app/1.0 (+https://github.com/serein-213)"
        val maxRetries = 3
        var lastError: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val url = URL("$GITHUB_API_BASE/releases")  // 获取所有releases而不仅仅是latest
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", ua)
                
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    lastError = RuntimeException("HTTP $code")
                    android.util.Log.w("KernelModuleDownloader", "HTTP $code on attempt ${attempt + 1}")
                } else {
                    val response = connection.inputStream.bufferedReader().readText()
                    android.util.Log.d("KernelModuleDownloader", "Response length: ${response.length}")
                    
                    // 解析所有releases
                    val releases = parseAllGitHubReleases(response)
                    if (releases.isNotEmpty()) {
                        android.util.Log.d("KernelModuleDownloader", "Found ${releases.size} releases")
                        return@withContext releases
                    } else {
                        // 如果解析失败，回退到latest
                        android.util.Log.w("KernelModuleDownloader", "Failed to parse releases, falling back to latest")
                        val latestRelease = getLatestReleaseOnly()
                        return@withContext if (latestRelease != null) listOf(latestRelease) else emptyList()
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
        return@withContext emptyList()
    }
    
    /** 仅获取最新release（原有逻辑保留作为回退） */
    private suspend fun getLatestReleaseOnly(): GitHubRelease? = withContext(Dispatchers.IO) {
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
    
    /** 解析所有 GitHub Releases 的 API 响应 */
    private fun parseAllGitHubReleases(jsonResponse: String): List<GitHubRelease> {
        try {
            val releases = mutableListOf<GitHubRelease>()
            
            // 更简单的方法：查找所有 tag_name 和对应的 assets
            val tagNamePattern = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
            val namePattern = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            
            // 分割成release段落，通过查找 "tag_name" 开始的位置
            val tagMatches = tagNamePattern.findAll(jsonResponse).toList()
            val nameMatches = namePattern.findAll(jsonResponse).toList()
            
            android.util.Log.d("KernelModuleDownloader", "Found ${tagMatches.size} tag_name matches")
            
            for (i in tagMatches.indices) {
                val tagName = tagMatches[i].groupValues[1]
                
                // 找到对应的release name（通常紧跟在tag_name后面）
                val releaseStartPos = tagMatches[i].range.first
                val releaseEndPos = if (i < tagMatches.size - 1) tagMatches[i + 1].range.first else jsonResponse.length
                val releaseSection = jsonResponse.substring(releaseStartPos, releaseEndPos)
                
                val releaseName = namePattern.find(releaseSection)?.groupValues?.getOrNull(1) ?: tagName
                
                // 在这个release段落中查找assets
                val assets = mutableListOf<GitHubAsset>()
                val assetNamePattern = Regex("\"name\"\\s*:\\s*\"([^\"]+\\.ko)\"")
                val downloadUrlPattern = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")
                val sizePattern = Regex("\"size\"\\s*:\\s*(\\d+)")
                
                val assetNames = assetNamePattern.findAll(releaseSection).map { it.groupValues[1] }.toList()
                val downloadUrls = downloadUrlPattern.findAll(releaseSection).map { it.groupValues[1] }.toList()
                val sizes = sizePattern.findAll(releaseSection).map { it.groupValues[1].toLongOrNull() ?: 0L }.toList()
                
                // 匹配 .ko 文件的 assets
                for (j in assetNames.indices) {
                    val assetName = assetNames[j]
                    if (assetName.endsWith(".ko") && j < downloadUrls.size) {
                        val downloadUrl = downloadUrls.find { url -> url.contains(assetName) } ?: continue
                        val size = if (j < sizes.size) sizes[j] else 0L
                        assets.add(GitHubAsset(assetName, downloadUrl, size))
                    }
                }
                
                if (assets.isNotEmpty()) {
                    releases.add(GitHubRelease(tagName, releaseName, assets))
                    android.util.Log.d("KernelModuleDownloader", "Parsed release $tagName with ${assets.size} .ko assets")
                }
            }
            
            android.util.Log.d("KernelModuleDownloader", "Successfully parsed ${releases.size} releases")
            return releases
            
        } catch (e: Exception) {
            android.util.Log.e("KernelModuleDownloader", "Error parsing all GitHub releases JSON: ${e.message}", e)
            return emptyList()
        }
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
    
    /** 根据内核版本获取可用的模块列表（支持多个release回退查找） */
    suspend fun getAvailableModules(kernelVersion: ModuleManager.KernelVersion): List<ModuleInfo> = withContext(Dispatchers.IO) {
        val modules = mutableListOf<ModuleInfo>()
        
        try {
            // 获取所有 Releases（按新到旧排序）
            val releases = getAllReleases()
            if (releases.isEmpty()) {
                android.util.Log.w("KernelModuleDownloader", "No releases found")
                return@withContext emptyList()
            }
            
            // 查找支持的内核版本
            val supportedVersion = findSupportedKernelVersion(kernelVersion.majorMinor)
            if (supportedVersion == null) {
                android.util.Log.w("KernelModuleDownloader", "No supported kernel version found for ${kernelVersion.majorMinor}")
                return@withContext emptyList()
            }
            
            val androidVersion = KERNEL_TO_ANDROID[supportedVersion] // 仅用于信息展示

            // 遍历已知模块名
            MODULE_NAMES.forEach { moduleName ->
                android.util.Log.d("KernelModuleDownloader", "Looking for file of $moduleName with kernel $supportedVersion")

                // 定义查找优先级（不包含通用匹配）
                val searchPatterns = listOf(
                    // 1. 完全匹配：包含android版本和完整内核版本
                    "$moduleName-${androidVersion ?: "android\\d+"}-${kernelVersion.full}\\.ko",
                    // 2. 完全匹配：包含android版本和主次版本
                    "$moduleName-${androidVersion ?: "android\\d+"}-${supportedVersion}\\.ko",
                    // 3. 简化匹配：完整内核版本
                    "$moduleName-${kernelVersion.full}\\.ko",
                    // 4. 简化匹配：主次版本
                    "$moduleName-${supportedVersion}\\.ko"
                )

                var matchingAsset: GitHubAsset? = null
                var matchedPattern = ""
                var foundInRelease = ""

                // 在所有releases中按优先级顺序查找
                releaseLoop@ for (release in releases) {
                    android.util.Log.d("KernelModuleDownloader", "Checking release: ${release.tagName}")
                    
                    for (pattern in searchPatterns) {
                        val regex = Regex("^$pattern$")
                        matchingAsset = release.assets.find { asset ->
                            val matches = regex.matches(asset.name)
                            if (matches) {
                                android.util.Log.d("KernelModuleDownloader", "Asset ${asset.name} matches pattern: $pattern in release ${release.tagName}")
                            }
                            matches
                        }
                        if (matchingAsset != null) {
                            matchedPattern = pattern
                            foundInRelease = release.tagName
                            break@releaseLoop
                        }
                    }
                }

                if (matchingAsset != null) {
                    android.util.Log.d("KernelModuleDownloader", "Found matching asset: ${matchingAsset.name} (pattern: $matchedPattern, release: $foundInRelease)")
                    val actualKernel = extractKernelFromAssetName(matchingAsset.name) ?: supportedVersion
                    modules.add(
                        ModuleInfo(
                            name = moduleName,
                            version = foundInRelease,
                            downloadUrl = matchingAsset.downloadUrl,
                            sha256 = matchingAsset.sha256,
                            size = matchingAsset.size,
                            kernelVersion = actualKernel,
                            androidVersion = androidVersion ?: ""
                        )
                    )
                } else {
                    android.util.Log.w("KernelModuleDownloader", "No matching asset found for: $moduleName kernel $supportedVersion across ${releases.size} releases")
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("KernelModuleDownloader", "Error in getAvailableModules", e)
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
            // 覆盖策略: 若已存在同名文件先删除，确保重新下载（符合“不要使用本地缓存”需求）
            if (localFile.exists()) {
                runCatching { localFile.delete() }
            }
            
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

    /** 强制下载（语义化包装，便于调用方表达 intent），内部直接调用 downloadModule */
    suspend fun forceRedownload(moduleInfo: ModuleInfo, onProgress: ((Int) -> Unit)? = null): DownloadResult =
        downloadModule(moduleInfo, onProgress)
    
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
    
    /** 检查本地是否已有对应版本的模块（按精确匹配优先级排序） */
    fun getLocalModule(moduleName: String, version: String, kernelVersion: String): File? {
        val storageDir = getModuleStorageDir()
        
        // 尝试获取当前内核信息以进行精确匹配
        val currentKernel = try {
            java.io.File("/proc/sys/kernel/osrelease").readText().trim()
        } catch (e: Exception) {
            kernelVersion
        }
        
        val majorMinor = currentKernel.split('.').take(2).joinToString(".")
        val androidVersion = KERNEL_TO_ANDROID[majorMinor]
        
        // 按优先级排序的可能文件名（与下载逻辑保持一致）
        val possibleNames = listOf(
            // 1. 完全匹配：包含android版本和完整内核版本
            "$moduleName-$androidVersion-$currentKernel.ko",
            // 2. 完全匹配：包含android版本和主次版本  
            "$moduleName-$androidVersion-$majorMinor.ko",
            // 3. 简化匹配：完整内核版本
            "$moduleName-$currentKernel.ko",
            // 4. 简化匹配：主次版本
            "$moduleName-$majorMinor.ko",
            // 5. 通用匹配
            "$moduleName.ko",
            // 6. 兼容旧格式：带版本号的格式
            "$moduleName-$version-$kernelVersion.ko",
            "$moduleName-$version-$majorMinor.ko"
        ).distinct() // 去重
        
        for (fileName in possibleNames) {
            val localFile = File(storageDir, fileName)
            if (localFile.exists() && localFile.length() > 0) {
                android.util.Log.d("KernelModuleDownloader", "Found local module: $fileName")
                return localFile
            }
        }
        
        android.util.Log.d("KernelModuleDownloader", "No local module found for $moduleName")
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
