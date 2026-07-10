package com.moe.starflow.manga

import android.content.Context
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * 统一模型下载管理器
 * 支持断点续传、重试机制、进度回调
 */
object ModelDownloadManager {

    private const val TAG = "ModelDownloadManager"
    private const val SPEED_UPDATE_INTERVAL = 500L  // 每 500ms 更新一次速度
    private const val BUFFER_SIZE = 65536  // 64KB 缓冲区

    /**
     * 下载进度回调
     */
    interface ProgressCallback {
        fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float)
    }

    /**
     * 下载模型文件
     */
    suspend fun downloadModel(
        context: Context,
        url: String,
        sha256Hash: String,
        destFile: File,
        onProgress: ProgressCallback? = null,
        maxRetries: Int = 3
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tempFile = File(destFile.parent, destFile.name + ".part")
        var lastException: Exception? = null
        var connection: HttpURLConnection? = null

        for (attempt in 1..maxRetries) {
            try {
                LogCollector.d(TAG, "开始下载 (attempt $attempt/$maxRetries): $url")

                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept-Encoding", "identity")

                // 断点续传
                val startOffset = if (attempt > 1 && tempFile.exists() && tempFile.length() > 0) {
                    LogCollector.d(TAG, "断点续传：从 ${tempFile.length()} 字节继续")
                    connection.setRequestProperty("Range", "bytes=${tempFile.length()}-")
                    tempFile.length()
                } else {
                    if (tempFile.exists()) tempFile.delete()
                    0L
                }

                val responseCode = connection.responseCode
                val totalBytes = connection.contentLengthLong
                LogCollector.d(TAG, "HTTP $responseCode, Content-Length: $totalBytes")

                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    // 404/403 等客户端错误不需要重试
                    val permanent = responseCode == 404 || responseCode == 403
                    return@withContext Result.failure(
                        Exception("HTTP $responseCode${if (permanent) " (文件不存在)" else ""}")
                    )
                }

                // 实际总大小
                val actualTotalBytes = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    startOffset + totalBytes
                } else {
                    totalBytes
                }

                val appendMode = startOffset > 0
                val conn = connection
                FileOutputStream(tempFile, appendMode).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastBytesRead = startOffset
                    var speed = 0f

                    conn.inputStream.use { inputStream ->
                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read == -1) break

                            outputStream.write(buffer, 0, read)

                            val currentTime = System.currentTimeMillis()
                            val elapsed = currentTime - lastUpdateTime
                            if (elapsed >= SPEED_UPDATE_INTERVAL) {
                                val bytesDelta = tempFile.length() - lastBytesRead
                                speed = (bytesDelta.toFloat() / elapsed) * 1000f / (1024f * 1024f)
                                lastUpdateTime = currentTime
                                lastBytesRead = tempFile.length()
                                onProgress?.onProgress(lastBytesRead, actualTotalBytes, speed)
                            }
                        }
                    }
                    outputStream.flush()
                }

                // 验证完整性
                val downloadedBytes = tempFile.length()
                if (actualTotalBytes > 0 && downloadedBytes != actualTotalBytes) {
                    throw Exception("下载不完整：期望 $actualTotalBytes，实际 $downloadedBytes")
                }

                // 移动到目标
                if (!tempFile.renameTo(destFile)) {
                    Files.move(tempFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }

                // 校验
                if (sha256Hash.isNotEmpty()) {
                    val fileHash = calculateSHA256(destFile)
                    if (fileHash != sha256Hash.lowercase()) {
                        destFile.delete()
                        throw Exception("SHA-256 校验失败")
                    }
                }

                LogCollector.d(TAG, "下载完成: ${destFile.absolutePath}")
                return@withContext Result.success(Unit)

            } catch (e: Exception) {
                lastException = e
                LogCollector.e(TAG, "attempt $attempt/$maxRetries 失败: ${e.message}")
                connection?.disconnect()
                connection = null
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay((attempt * 2000L).coerceAtMost(10000L))
                }
            }
        }

        LogCollector.e(TAG, "下载失败，已重试 $maxRetries 次")
        if (tempFile.exists()) tempFile.delete()
        Result.failure(lastException ?: Exception("下载失败"))
    }

    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            DigestInputStream(fis, digest).use { dis ->
                val buffer = ByteArray(8192)
                while (dis.read(buffer) != -1) { /* auto-update digest */ }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}