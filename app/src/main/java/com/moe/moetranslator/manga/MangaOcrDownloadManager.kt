package com.moe.moetranslator.manga

import android.content.Context
import com.moe.moetranslator.utils.LogCollector
import java.io.File
import java.security.MessageDigest

/**
 * Manga-Ocr 下载管理器
 *
 * 从 HuggingFace onnx-community/manga-ocr-base-ONNX 下载 ONNX 模型。
 * 支持多个版本：full (343MB+117MB)、fp16 (172MB+59MB)、quantized (87MB+30MB)
 *
 * 下载地址:
 * - Encoder: https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/encoder_model.onnx
 * - Decoder: https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/decoder_model.onnx
 *
 * 量化版本:
 * - encoder_model_fp16.onnx (172MB)
 * - decoder_model_fp16.onnx (59MB)
 * - encoder_model_quantized.onnx (87MB)
 * - decoder_model_int8.onnx (30MB)
 */
object MangaOcrDownloadManager {

    private const val TAG = "MangaOcrDownloadManager"

    // 模型版本枚举
    enum class ModelVersion(val encoderFile: String, val decoderFile: String, val description: String) {
        FULL("encoder_model.onnx", "decoder_model.onnx", "完整版 (343MB+117MB)"),
        FP16("encoder_model_fp16.onnx", "decoder_model_fp16.onnx", "半精度 (172MB+59MB)"),
        QUANTIZED("encoder_model_quantized.onnx", "decoder_model_int8.onnx", "量化版 (87MB+30MB)")
    }

    // HuggingFace 基础 URL
    private const val HF_BASE_URL = "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx"

    // 存储目录
    const val MODEL_DIR = "manga_ocr_download"
    const val ENCODER_FILE = "encoder_model.onnx"
    const val DECODER_FILE = "decoder_model.onnx"

    /**
     * 获取下载的模型目录
     */
    fun getModelDir(context: Context): File {
        return File(context.filesDir, MODEL_DIR)
    }

    /**
     * 获取指定版本的模型目录
     */
    fun getModelDir(context: Context, version: ModelVersion): File {
        return File(File(context.filesDir, MODEL_DIR), version.name)
    }

    /**
     * 获取 encoder 文件路径
     */
    fun getEncoderFile(context: Context): File {
        return File(getModelDir(context), ENCODER_FILE)
    }

    /**
     * 获取指定版本的 encoder 文件路径
     */
    fun getEncoderFile(context: Context, version: ModelVersion): File {
        return File(getModelDir(context, version), version.encoderFile)
    }

    /**
     * 获取 decoder 文件路径
     */
    fun getDecoderFile(context: Context): File {
        return File(getModelDir(context), DECODER_FILE)
    }

    /**
     * 获取指定版本的 decoder 文件路径
     */
    fun getDecoderFile(context: Context, version: ModelVersion): File {
        return File(getModelDir(context, version), version.decoderFile)
    }

    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(context: Context): Boolean {
        val encoder = getEncoderFile(context)
        val decoder = getDecoderFile(context)
        return encoder.exists() && decoder.exists() && encoder.length() > 1000
    }

    /**
     * 检查指定版本模型是否已下载
     */
    fun isVersionDownloaded(context: Context, version: ModelVersion): Boolean {
        val encoder = getEncoderFile(context, version)
        val decoder = getDecoderFile(context, version)
        return encoder.exists() && decoder.exists() && encoder.length() > 1000
    }

    /**
     * 获取已下载模型的版本
     */
    fun getDownloadedVersion(context: Context): ModelVersion? {
        // 优先检查版本目录
        for (version in ModelVersion.entries) {
            if (isVersionDownloaded(context, version)) {
                return version
            }
        }
        // 向后兼容：检查旧目录
        if (isModelDownloaded(context)) {
            val encoder = getEncoderFile(context)
            val size = encoder.length()
            return when {
                size < 100_000_000 -> ModelVersion.QUANTIZED
                size < 200_000_000 -> ModelVersion.FP16
                else -> ModelVersion.FULL
            }
        }
        return null
    }

    /**
     * 获取已下载的版本列表
     */
    fun getDownloadedVersions(context: Context): List<ModelVersion> {
        return ModelVersion.entries.filter { isVersionDownloaded(context, it) }
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
     * 删除指定版本的模型
     */
    fun deleteVersion(context: Context, version: ModelVersion): Result<Unit> {
        return try {
            val modelDir = getModelDir(context, version)
            if (modelDir.exists()) {
                val deleted = modelDir.deleteRecursively()
                if (!deleted) {
                    LogCollector.e(TAG, "删除模型失败: ${modelDir.absolutePath}")
                    return Result.failure(Exception("Failed to delete model directory"))
                }
            }
            LogCollector.d(TAG, "${version.name} 版本已删除")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除模型失败", e)
            Result.failure(e)
        }
    }

    /**
     * 下载模型
     *
     * @param context Context
     * @param version 模型版本（FULL/FP16/QUANTIZED）
     * @param onProgress 进度回调 (bytesRead, totalBytes, speed MB/s)
     */
    suspend fun downloadModel(
        context: Context,
        version: ModelVersion = ModelVersion.FULL,
        onProgress: ModelDownloadManager.ProgressCallback? = null
    ): Result<Unit> {
        val modelDir = getModelDir(context, version)
        if (!modelDir.exists()) {
            if (!modelDir.mkdirs()) {
                return Result.failure(Exception("Failed to create model directory: $modelDir"))
            }
        }

        LogCollector.d(TAG, "开始下载 manga-ocr ${version.name} 版本...")

        // 下载 encoder
        val encoderUrl = "$HF_BASE_URL/${version.encoderFile}"
        val encoderFile = File(modelDir, version.encoderFile)
        LogCollector.d(TAG, "下载 encoder: $encoderUrl")

        val result1 = ModelDownloadManager.downloadModel(
            context = context,
            url = encoderUrl,
            sha256Hash = "",  // HuggingFace 不提供 hash，我们跳过校验
            destFile = encoderFile,
            onProgress = object : ModelDownloadManager.ProgressCallback {
                override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                    onProgress?.onProgress(bytesRead, totalBytes, speed)
                }
            }
        )

        if (result1.isFailure) {
            LogCollector.e(TAG, "Encoder 下载失败", result1.exceptionOrNull())
            return result1
        }

        // 下载 decoder
        val decoderUrl = "$HF_BASE_URL/${version.decoderFile}"
        val decoderFile = File(modelDir, version.decoderFile)
        LogCollector.d(TAG, "下载 decoder: $decoderUrl")

        // 进度回调重置
        val decoderResult = ModelDownloadManager.downloadModel(
            context = context,
            url = decoderUrl,
            sha256Hash = "",
            destFile = decoderFile,
            onProgress = null  // decoder 下载不单独显示进度
        )

        if (decoderResult.isFailure) {
            LogCollector.e(TAG, "Decoder 下载失败", decoderResult.exceptionOrNull())
            return decoderResult
        }

        LogCollector.d(TAG, "下载完成!")
        LogCollector.d(TAG, "Encoder: ${encoderFile.absolutePath} (${encoderFile.length()} bytes)")
        LogCollector.d(TAG, "Decoder: ${decoderFile.absolutePath} (${decoderFile.length()} bytes)")

        return Result.success(Unit)
    }

    /**
     * 下载指定版本的完整模型包
     */
    suspend fun downloadModelVersion(
        context: Context,
        version: ModelVersion,
        onProgress: ModelDownloadManager.ProgressCallback? = null
    ): Result<Unit> {
        return downloadModel(context, version, onProgress)
    }
}