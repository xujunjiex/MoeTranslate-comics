package com.moe.starflow.manga

import android.content.Context
import com.moe.starflow.utils.LogCollector
import java.io.File

/**
 * Manga-Ocr 下载管理器
 *
 * 从 HuggingFace l0wgear/manga-ocr-2025-onnx 下载 ONNX 模型。
 *
 * 下载地址:
 * - Encoder: https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/encoder_model.onnx
 * - Decoder: https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/decoder_model.onnx
 * - Vocab:   https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/vocab.txt
 */
object MangaOcrDownloadManager {

    private const val TAG = "MangaOcrDownloadManager"

    /**
     * 聚合下载进度回调（跨多个文件的总进度）
     *
     * @param bytesRead 当前累计已下载字节（之前文件完成 + 当前文件已下载）
     * @param totalBytes 总字节数（所有文件之和）；HEAD 失败时为 -1
     * @param speed 当前文件下载速度（MB/s）
     * @param currentFileBytesRead 当前文件已下载字节
     * @param currentFileTotalBytes 当前文件总字节；HEAD 失败时为 -1
     * @param currentFileName 当前下载的文件名
     */
    interface AggregateProgressCallback {
        fun onAggregateProgress(
            bytesRead: Long,
            totalBytes: Long,
            speed: Float,
            currentFileBytesRead: Long,
            currentFileTotalBytes: Long,
            currentFileName: String
        )
    }

    // 模型配置
    private const val ENCODER_FILE = "encoder_model.onnx"
    private const val DECODER_FILE = "decoder_model.onnx"
    private const val VOCAB_FILE = "vocab.txt"
    private const val MODEL_DIR_NAME = "manga_ocr_download"
    private const val BASE_URL = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main"

    // 旧版本子目录名（用于兼容老用户已下载的模型）
    private const val LEGACY_V2025_DIR = "V2025"

    /**
     * 获取下载的模型目录（外部存储）
     */
    fun getModelDir(context: Context): File {
        return File(context.getExternalFilesDir(null), MODEL_DIR_NAME)
    }

    /**
     * 获取 encoder 文件路径
     */
    fun getEncoderFile(context: Context): File {
        return File(getModelDir(context), ENCODER_FILE)
    }

    /**
     * 获取 decoder 文件路径
     */
    fun getDecoderFile(context: Context): File {
        return File(getModelDir(context), DECODER_FILE)
    }

    /**
     * 获取 vocab 文件路径
     */
    fun getVocabFile(context: Context): File {
        return File(getModelDir(context), VOCAB_FILE)
    }

    /**
     * 检查模型是否已下载
     *
     * 如果根目录找不到，会尝试从旧版子目录（V2025/）迁移文件到根目录，
     * 避免老用户升级后被迫重新下载。
     */
    fun isModelDownloaded(context: Context): Boolean {
        val encoder = getEncoderFile(context)
        val decoder = getDecoderFile(context)
        val vocab = getVocabFile(context)
        if (encoder.exists() && decoder.exists() && vocab.exists() &&
                encoder.length() > 1000 && decoder.length() > 1000) {
            return true
        }

        // 兼容老版本：尝试从 V2025/ 子目录迁移
        if (migrateLegacyV2025(context)) {
            return encoder.exists() && decoder.exists() && vocab.exists() &&
                    encoder.length() > 1000 && decoder.length() > 1000
        }
        return false
    }

    /**
     * 从旧版 V2025/ 子目录迁移模型文件到根目录。
     * 成功迁移返回 true。
     */
    private fun migrateLegacyV2025(context: Context): Boolean {
        val legacyDir = File(getModelDir(context), LEGACY_V2025_DIR)
        if (!legacyDir.exists() || !legacyDir.isDirectory) return false

        val legacyEncoder = File(legacyDir, ENCODER_FILE)
        val legacyDecoder = File(legacyDir, DECODER_FILE)
        val legacyVocab = File(legacyDir, VOCAB_FILE)
        if (!legacyEncoder.exists() || !legacyDecoder.exists() || !legacyVocab.exists()) return false

        val modelDir = getModelDir(context)
        if (!modelDir.exists()) modelDir.mkdirs()

        LogCollector.d(TAG, "检测到旧版 V2025/ 子目录，迁移模型文件到根目录")
        val encoderOk = runCatching { legacyEncoder.copyTo(getEncoderFile(context), overwrite = true) }.isSuccess
        val decoderOk = runCatching { legacyDecoder.copyTo(getDecoderFile(context), overwrite = true) }.isSuccess
        val vocabOk = runCatching { legacyVocab.copyTo(getVocabFile(context), overwrite = true) }.isSuccess
        if (encoderOk && decoderOk && vocabOk) {
            legacyDir.deleteRecursively()
            LogCollector.d(TAG, "旧版模型文件迁移完成，已删除 V2025/ 子目录")
            return true
        } else {
            LogCollector.e(TAG, "旧版模型文件迁移失败 (encoder=$encoderOk, decoder=$decoderOk, vocab=$vocabOk)")
            return false
        }
    }

    /**
     * 获取模型大小描述
     */
    fun getModelSizeString(context: Context): String {
        val encoder = getEncoderFile(context)
        val decoder = getDecoderFile(context)
        if (!encoder.exists() || !decoder.exists()) return "未下载"

        val totalSize = encoder.length() + decoder.length()
        return formatSize(totalSize)
    }

    private fun formatSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * 删除已下载的模型
     */
    fun deleteModel(context: Context): Result<Unit> {
        return try {
            val modelDir = getModelDir(context)
            if (modelDir.exists()) {
                val deleted = modelDir.deleteRecursively()
                if (!deleted) {
                    LogCollector.e(TAG, "删除模型失败: ${modelDir.absolutePath}")
                    return Result.failure(Exception("Failed to delete model directory"))
                }
            }
            LogCollector.d(TAG, "模型已删除")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除模型失败", e)
            Result.failure(e)
        }
    }

    /**
     * 下载模型（聚合进度版）
     *
     * 跨 3 个文件（encoder/decoder/vocab）累计总进度，调用方无需切换文件时归零。
     */
    suspend fun downloadModel(
        context: Context,
        onAggregateProgress: AggregateProgressCallback? = null
    ): Result<Unit> {
        val modelDir = getModelDir(context)
        if (!modelDir.exists()) {
            if (!modelDir.mkdirs()) {
                return Result.failure(Exception("Failed to create model directory: $modelDir"))
            }
        }

        LogCollector.d(TAG, "开始下载 manga-ocr...")

        // 累计前序文件已写入字节和总大小
        var priorBytes = 0L
        var priorTotalBytes = 0L

        // 文件计划
        val filePlan = listOf(ENCODER_FILE, DECODER_FILE, VOCAB_FILE)

        for (fileName in filePlan) {
            val destFile = File(modelDir, fileName)
            if (destFile.exists() && destFile.length() > 0) {
                LogCollector.d(TAG, "$fileName 已存在，跳过: ${destFile.absolutePath}")
                continue
            }

            val fileUrl = "$BASE_URL/$fileName"
            val currentFileTotal = getContentLength(fileUrl)

            LogCollector.d(TAG, "下载 $fileName (size=$currentFileTotal): $fileUrl")
            val result = ModelDownloadManager.downloadModel(
                context = context, url = fileUrl, sha256Hash = "",
                destFile = destFile,
                onProgress = object : ModelDownloadManager.ProgressCallback {
                    override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                        onAggregateProgress?.onAggregateProgress(
                            bytesRead = priorBytes + bytesRead,
                            totalBytes = if (currentFileTotal > 0) priorTotalBytes + currentFileTotal else -1L,
                            speed = speed,
                            currentFileBytesRead = bytesRead,
                            currentFileTotalBytes = totalBytes,
                            currentFileName = fileName
                        )
                    }
                }
            )

            // vocab 404 不影响整体（保留旧版宽容行为）
            if (result.isFailure && fileName != VOCAB_FILE) {
                LogCollector.e(TAG, "$fileName 下载失败", result.exceptionOrNull())
                return result
            } else if (result.isFailure) {
                LogCollector.w(TAG, "$fileName 下载失败（可能不存在于 HuggingFace），跳过: ${result.exceptionOrNull()?.message}")
            }

            // 完成一个文件：累加到 prior*
            val actualSize = if (destFile.exists()) destFile.length() else 0L
            priorBytes += actualSize
            if (currentFileTotal > 0) priorTotalBytes += currentFileTotal
        }

        LogCollector.d(TAG, "下载完成!")
        LogCollector.d(TAG, "Encoder: ${getEncoderFile(context).absolutePath} (${getEncoderFile(context).length()} bytes)")
        LogCollector.d(TAG, "Decoder: ${getDecoderFile(context).absolutePath} (${getDecoderFile(context).length()} bytes)")

        return Result.success(Unit)
    }

    /**
     * HEAD 请求获取文件大小。失败返回 -1。
     */
    private fun getContentLength(url: String): Long {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            val len = conn.contentLengthLong
            conn.disconnect()
            if (code in 200..299) len else -1L
        } catch (e: Exception) {
            LogCollector.w(TAG, "HEAD 请求失败: $url - ${e.message}")
            -1L
        }
    }
}
