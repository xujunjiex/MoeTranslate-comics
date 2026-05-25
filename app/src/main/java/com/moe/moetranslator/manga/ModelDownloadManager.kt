package com.moe.moetranslator.manga

import android.content.Context
import com.moe.moetranslator.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * 统一模型下载管理器
 * 支持从 GitHub releases 下载模型，带 SHA-256 校验和进度回调
 */
object ModelDownloadManager {

    private const val TAG = "ModelDownloadManager"
    private const val SPEED_UPDATE_INTERVAL = 500L  // 每 500ms 更新一次速度

    /**
     * 下载进度回调
     * @param bytesRead 已下载字节数
     * @param totalBytes 总字节数，-1 表示未知
     * @param speed 下载速度 MB/s
     */
    interface ProgressCallback {
        fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float)
    }

    /**
     * 下载模型文件
     * @param context Context
     * @param url 下载地址
     * @param sha256Hash SHA-256 校验码（为空时不校验）
     * @param destFile 目标文件
     * @param onProgress 进度回调
     * @return true 下载成功
     */
    suspend fun downloadModel(
        context: Context,
        url: String,
        sha256Hash: String,
        destFile: File,
        onProgress: ProgressCallback? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            LogCollector.d(TAG, "开始下载: $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"

            // 支持断点续传
            val existingSize = if (destFile.exists()) destFile.length() else 0L
            if (existingSize > 0) {
                connection.setRequestProperty("Range", "bytes=$existingSize-")
            }

            val responseCode = connection.responseCode
            val totalBytes = connection.contentLengthLong

            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                return@withContext Result.failure(Exception("HTTP $responseCode"))
            }

            // 创建临时文件
            val tempFile = File(destFile.parent, destFile.name + ".part")

            connection.inputStream.use { inputStream ->
                tempFile.outputStream().buffered().use { outputStream ->
                    var bytesRead = existingSize
                    val buffer = ByteArray(8192)
                    var read: Int
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastBytesRead = existingSize
                    var speed = 0f

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        bytesRead += read

                        val currentTime = System.currentTimeMillis()
                        val elapsed = currentTime - lastUpdateTime
                        if (elapsed >= SPEED_UPDATE_INTERVAL) {
                            val bytesDelta = bytesRead - lastBytesRead
                            speed = (bytesDelta.toFloat() / elapsed) * 1000f / (1024f * 1024f)  // MB/s
                            lastUpdateTime = currentTime
                            lastBytesRead = bytesRead
                        }
                        onProgress?.onProgress(bytesRead, totalBytes, speed)
                    }
                    outputStream.flush()
                }
            }

            // 移动到目标位置（使用原子移动，跨文件系统边界也能工作）
            Files.move(tempFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)

            connection.disconnect()

            // 校验 hash
            if (sha256Hash.isNotEmpty()) {
                val fileHash = calculateSHA256(destFile)
                if (fileHash != sha256Hash.lowercase()) {
                    destFile.delete()
                    return@withContext Result.failure(Exception("SHA-256 校验失败"))
                }
            }

            LogCollector.d(TAG, "下载完成: ${destFile.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "下载失败", e)
            Result.failure(e)
        }
    }

    /**
     * 计算文件的 SHA-256（使用流式处理，避免大文件 OOM）
     */
    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            DigestInputStream(fis, digest).use { dis ->
                val buffer = ByteArray(8192)
                while (dis.read(buffer) != -1) {
                    // DigestInputStream 在 read 时自动更新 digest
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}