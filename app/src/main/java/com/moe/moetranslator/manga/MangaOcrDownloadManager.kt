package com.moe.moetranslator.manga

import android.content.Context
import com.moe.moetranslator.utils.LogCollector
import java.io.File
import java.security.MessageDigest

/**
 * Manga-Ocr 下载管理器
 *
 * 从 HuggingFace onnx-community/manga-ocr-base-ONNX 下载 ONNX 模型。
 * 仅支持完整版 FULL (343MB+117MB)，FP16/量化版在 Android ONNX Runtime 上无法运行。
 *
 * 下载地址:
 * - Encoder: https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/encoder_model.onnx
 * - Decoder: https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/decoder_model.onnx
 */
object MangaOcrDownloadManager {

    private const val TAG = "MangaOcrDownloadManager"

    // 模型版本枚举
    enum class ModelVersion(
        val encoderFile: String,
        val decoderFile: String,
        val vocabFile: String,
        val description: String,
        val baseUrl: String
    ) {
        FULL(
            "encoder_model.onnx", "decoder_model.onnx", "vocab.txt",
            "原版 (343MB+117MB)",
            "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx"
        ),
        V2025(
            "encoder_model.onnx", "decoder_model.onnx", "vocab.txt",
            "2025版 (22MB+113MB)",
            "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main"
        )
    }

    // 存储目录
    const val MODEL_DIR = "manga_ocr_download"
    const val ENCODER_FILE = "encoder_model.onnx"
    const val DECODER_FILE = "decoder_model.onnx"
    private const val PREFS_NAME = "manga_ocr_prefs"

    /**
     * 获取下载的模型目录（外部存储）
     */
    fun getModelDir(context: Context): File {
        return File(context.getExternalFilesDir(null), MODEL_DIR)
    }

    /**
     * 获取指定版本的模型目录（外部存储）
     */
    fun getModelDir(context: Context, version: ModelVersion): File {
        return File(File(context.getExternalFilesDir(null), MODEL_DIR), version.name)
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
     * 获取指定版本的 vocab 文件路径
     */
    fun getVocabFile(context: Context, version: ModelVersion): File {
        return File(getModelDir(context, version), version.vocabFile)
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
        val vocab = getVocabFile(context, version)
        return encoder.exists() && decoder.exists() && vocab.exists() && encoder.length() > 1000
    }

    /**
     * 获取已下载模型的版本
     */
    fun getDownloadedVersion(context: Context): ModelVersion? {
        // 检查版本目录
        for (version in ModelVersion.entries) {
            if (isVersionDownloaded(context, version)) {
                return version
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
            // 如果删除的是当前使用版本，清除 activeVersion
            if (getActiveVersion(context) == version) {
                clearActiveVersion(context)
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

        val encoderFile = File(modelDir, version.encoderFile)
        val decoderFile = File(modelDir, version.decoderFile)
        val vocabFile = File(modelDir, version.vocabFile)

        // 下载 encoder（跳过已存在的文件）
        if (encoderFile.exists() && encoderFile.length() > 0) {
            LogCollector.d(TAG, "Encoder 已存在，跳过: ${encoderFile.absolutePath}")
        } else {
            val encoderUrl = "${version.baseUrl}/${version.encoderFile}"
            LogCollector.d(TAG, "下载 encoder: $encoderUrl")
            val result = ModelDownloadManager.downloadModel(
                context = context, url = encoderUrl, sha256Hash = "",
                destFile = encoderFile,
                onProgress = object : ModelDownloadManager.ProgressCallback {
                    override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                        onProgress?.onProgress(bytesRead, totalBytes, speed)
                    }
                }
            )
            if (result.isFailure) {
                LogCollector.e(TAG, "Encoder 下载失败", result.exceptionOrNull())
                return result
            }
        }

        // 下载 decoder（跳过已存在的文件）
        if (decoderFile.exists() && decoderFile.length() > 0) {
            LogCollector.d(TAG, "Decoder 已存在，跳过: ${decoderFile.absolutePath}")
        } else {
            val decoderUrl = "${version.baseUrl}/${version.decoderFile}"
            LogCollector.d(TAG, "下载 decoder: $decoderUrl")
            val result = ModelDownloadManager.downloadModel(
                context = context, url = decoderUrl, sha256Hash = "",
                destFile = decoderFile,
                onProgress = object : ModelDownloadManager.ProgressCallback {
                    override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                        onProgress?.onProgress(bytesRead, totalBytes, speed)
                    }
                }
            )
            if (result.isFailure) {
                LogCollector.e(TAG, "Decoder 下载失败", result.exceptionOrNull())
                return result
            }
        }

        // 下载 vocab（跳过已存在的文件，404 时跳过不报错）
        if (vocabFile.exists() && vocabFile.length() > 0) {
            LogCollector.d(TAG, "Vocab 已存在，跳过: ${vocabFile.absolutePath}")
        } else {
            val vocabUrl = "${version.baseUrl}/${version.vocabFile}"
            LogCollector.d(TAG, "下载 vocab: $vocabUrl")
            val result = ModelDownloadManager.downloadModel(
                context = context, url = vocabUrl, sha256Hash = "",
                destFile = vocabFile,
                onProgress = object : ModelDownloadManager.ProgressCallback {
                    override fun onProgress(bytesRead: Long, totalBytes: Long, speed: Float) {
                        onProgress?.onProgress(bytesRead, totalBytes, speed)
                    }
                }
            )
            if (result.isFailure) {
                // vocab 404 不影响整体，只记录警告
                LogCollector.w(TAG, "Vocab 下载失败（可能不存在于 HuggingFace），跳过: ${result.exceptionOrNull()?.message}")
            }
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

    /**
     * 获取当前使用的版本配置
     */
    fun getActiveVersion(context: Context): ModelVersion? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val versionName = prefs.getString("active_version", null) ?: return null
        return try {
            ModelVersion.valueOf(versionName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 设置当前使用的版本
     */
    fun setActiveVersion(context: Context, version: ModelVersion) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("active_version", version.name).apply()
    }

    /**
     * 清除当前使用的版本
     */
    fun clearActiveVersion(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("active_version").apply()
    }
}