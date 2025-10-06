package com.override.battcaplsp.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 临时内核模块下载 & 加载工具 (仅 Debug 使用) */
class TempModuleTester(private val context: Context) {
    private val workDir: File by lazy {
        val d = File(context.filesDir, "temp_ko")
        if (!d.exists()) d.mkdirs()
        d
    }

    data class DownloadResult(val success: Boolean, val path: String?, val size: Long, val error: String?)
    data class LoadResult(val success: Boolean, val error: String?, val dmesg: String)
    data class UnloadResult(val success: Boolean, val error: String?)

    /** 下载远程 .ko 文件到内部存储 (限制大小 5MB) */
    suspend fun download(url: String, fileName: String? = null, maxSize: Long = 5 * 1024 * 1024): DownloadResult = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http")) return@withContext DownloadResult(false, null, 0, "URL必须以http开头")
        try {
            val conn = URL(clean).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.connect()
            val len = conn.contentLengthLong
            if (len > 0 && len > maxSize) {
                conn.disconnect(); return@withContext DownloadResult(false, null, len, "文件过大(${len}B) > ${maxSize}B")
            }
            val guessName = fileName ?: clean.substringAfterLast('/')
            if (!guessName.endsWith(".ko")) {
                conn.disconnect(); return@withContext DownloadResult(false, null, 0, "文件必须为 .ko")
            }
            val outFile = File(workDir, guessName)
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        total += r
                        if (total > maxSize) {
                            output.flush(); conn.disconnect(); outFile.delete()
                            return@withContext DownloadResult(false, null, total, "下载过程中大小超过限制 ${maxSize}B")
                        }
                        output.write(buf, 0, r)
                    }
                }
            }
            conn.disconnect()
            DownloadResult(true, outFile.absolutePath, outFile.length(), null)
        } catch (t: Throwable) {
            DownloadResult(false, null, 0, t.message ?: "下载异常")
        }
    }

    /** insmod 加载，附带可选参数 map；返回截取的 dmesg 片段 */
    suspend fun insmod(path: String, params: Map<String,String?> = emptyMap(), dmesgLines: Int = 120): LoadResult = withContext(Dispatchers.IO) {
        if (!File(path).exists()) return@withContext LoadResult(false, "文件不存在", "")
        // 记录加载前后特征日志时间戳(使用 dmesg -T 不一定所有内核支持；采用序号)
        val before = RootShell.exec("dmesg | wc -l").out.trim().toIntOrNull() ?: -1
        val args = buildString {
            params.forEach { (k,v) -> if (!v.isNullOrBlank()) append(" ").append(k).append("=").append(quoteIfNeeded(v)) }
        }
        val res = RootShell.exec("insmod ${quoteIfNeeded(path)}$args")
        val after = RootShell.exec("dmesg | wc -l").out.trim().toIntOrNull() ?: -1
        val delta = if (before >=0 && after >= before) after - before else dmesgLines
        val tailCmd = if (delta in 1..dmesgLines) "dmesg | tail -n $delta" else "dmesg | tail -n $dmesgLines"
        val log = RootShell.exec(tailCmd).out
        return@withContext if (res.code == 0) LoadResult(true, null, log) else LoadResult(false, res.err.ifBlank { "加载失败(未知错误)" }, log)
    }

    suspend fun rmmod(moduleName: String): UnloadResult = withContext(Dispatchers.IO) {
        val res = RootShell.exec("rmmod ${moduleName}")
        if (res.code == 0) UnloadResult(true, null) else UnloadResult(false, res.err.ifBlank { "卸载失败" })
    }

    suspend fun collectModuleLogs(keyword: String, maxLines: Int = 200): String = withContext(Dispatchers.IO) {
        val cmd = "(dmesg | grep -E '${keyword.replace("'",".")}' || true)"
        val res = RootShell.exec(cmd)
        val lines = res.out.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return@withContext "(无匹配日志)" else lines.takeLast(maxLines).joinToString("\n")
    }

    fun listDownloaded(): List<File> = workDir.listFiles()?.filter { it.isFile && it.name.endsWith(".ko") }?.sortedBy { it.name.lowercase() } ?: emptyList()
    fun deleteAll(): Int { val all = listDownloaded(); all.forEach { it.delete() }; return all.size }
    private fun quoteIfNeeded(s: String): String = if (s.matches(Regex("[A-Za-z0-9._/:=-]+"))) s else "'" + s.replace("'", "'\\''") + "'"
}
