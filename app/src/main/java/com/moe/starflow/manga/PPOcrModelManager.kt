package com.moe.starflow.manga

import android.content.Context
import com.moe.starflow.utils.LogCollector
import java.io.File

/**
 * PP-OCRv5 可选模型管理器
 *
 * 核心模型（det + cls + rec_zh）和所有字典文件内置在 assets 中，无需下载。
 * 本类仅管理可选的 rec ONNX 模型（en/ko/ru），存储在 filesDir/ppocrv5/ 目录。
 * 下载源：ModelScope (RapidAI/RapidOCR)
 *
 * 可选模型（用户按需下载）：
 * - rec_en.onnx (~7.5MB) 英文识别
 * - rec_ko.onnx (~12.9MB) 韩文识别
 * - rec_ru.onnx (~7.7MB) 俄文/西里尔文字识别
 */
object PPOcrModelManager {

    private const val TAG = "PPOcrModelManager"
    private const val MODEL_DIR = "ppocrv5"

    // ModelScope 下载 URL
    private const val BASE_URL = "https://modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/rec"

    val DOWNLOAD_URLS = mapOf(
        "rec_en.onnx" to "$BASE_URL/en_PP-OCRv5_rec_mobile.onnx",
        "rec_ko.onnx" to "$BASE_URL/korean_PP-OCRv5_rec_mobile.onnx",
        "rec_ru.onnx" to "$BASE_URL/cyrillic_PP-OCRv5_rec_mobile.onnx"
    )

    /**
     * 获取外部存储中的 ppocrv5 目录
     */
    fun getModelDir(context: Context): File = File(context.getExternalFilesDir(null), MODEL_DIR)

    // ========================================================================
    // 可选模型（rec_en / rec_ko / rec_ru）
    // ========================================================================

    /**
     * 检查可选 rec 模型是否已下载
     */
    fun isRecModelDownloaded(context: Context, lang: String): Boolean {
        val f = File(getModelDir(context), "rec_$lang.onnx")
        return f.exists() && f.length() > 0
    }

    /**
     * 获取 rec 模型文件（从 filesDir）
     */
    fun getRecModelFile(context: Context, lang: String): File? {
        val f = File(getModelDir(context), "rec_$lang.onnx")
        return if (f.exists() && f.length() > 0) f else null
    }

    /**
     * 下载可选 rec 模型
     * @param lang "en"、"ko" 或 "ru"
     */
    suspend fun downloadRecModel(
        context: Context,
        lang: String,
        onProgress: ModelDownloadManager.ProgressCallback? = null
    ): Result<Unit> {
        val dir = getOrCreateDir(context)

        val recFileName = "rec_$lang.onnx"
        val recFile = File(dir, recFileName)
        if (recFile.exists() && recFile.length() > 0) {
            LogCollector.d(TAG, "$recFileName 已存在，跳过")
            return Result.success(Unit)
        }

        val url = DOWNLOAD_URLS[recFileName]
            ?: return Result.failure(IllegalArgumentException("Unknown model: $recFileName"))
        LogCollector.d(TAG, "开始下载 $recFileName: $url")
        val result = ModelDownloadManager.downloadModel(
            context = context,
            url = url,
            sha256Hash = "",
            destFile = recFile,
            onProgress = onProgress
        )
        if (result.isFailure) return result

        LogCollector.d(TAG, "可选模型 $lang 下载完成")
        return Result.success(Unit)
    }

    /**
     * 删除可选 rec 模型
     */
    fun deleteRecModel(context: Context, lang: String): Result<Unit> {
        return try {
            val dir = getModelDir(context)
            File(dir, "rec_$lang.onnx").let { if (it.exists()) it.delete() }
            LogCollector.d(TAG, "可选模型 $lang 已删除")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除可选模型 $lang 失败", e)
            Result.failure(e)
        }
    }

    /**
     * 可选 rec 模型大小描述
     */
    fun getRecModelSizeString(context: Context, lang: String): String {
        val dir = getModelDir(context)
        var total = 0L
        File(dir, "rec_$lang.onnx").let { if (it.exists()) total += it.length() }
        return formatSize(total)
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private fun getOrCreateDir(context: Context): File {
        val dir = getModelDir(context)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    // ========================================================================
    // PP-OCRv6 medium 模型下载管理
    // ========================================================================

    private const val V6_MODEL_DIR = "ppocrv6"

    private const val V6_BASE_URL = "https://modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv6"

    val V6_DOWNLOAD_URLS = mapOf(
        "det" to "$V6_BASE_URL/det/PP-OCRv6_det_medium.onnx",
        "rec" to "$V6_BASE_URL/rec/PP-OCRv6_rec_medium.onnx"
    )

    fun getV6ModelDir(context: Context): File = File(context.getExternalFilesDir(null), V6_MODEL_DIR)

    fun isV6MediumDownloaded(context: Context): Boolean {
        val detFile = File(getV6ModelDir(context), "det_v6_medium.onnx")
        val recFile = File(getV6ModelDir(context), "rec_v6_medium.onnx")
        return detFile.exists() && detFile.length() > 0 && recFile.exists() && recFile.length() > 0
    }

    suspend fun downloadV6Medium(
        context: Context,
        type: String,
        onProgress: ModelDownloadManager.ProgressCallback? = null
    ): Result<Unit> {
        val v6Dir = getV6ModelDir(context)
        if (!v6Dir.exists()) v6Dir.mkdirs()

        val fileName = if (type == "det") "det_v6_medium.onnx" else "rec_v6_medium.onnx"
        val destFile = File(v6Dir, fileName)
        if (destFile.exists() && destFile.length() > 0) {
            LogCollector.d(TAG, "v6 $fileName 已存在，跳过")
            return Result.success(Unit)
        }

        val url = V6_DOWNLOAD_URLS[type]
            ?: return Result.failure(IllegalArgumentException("Unknown v6 model type: $type"))
        LogCollector.d(TAG, "开始下载 v6 $fileName: $url")
        val result = ModelDownloadManager.downloadModel(
            context = context,
            url = url,
            sha256Hash = "",
            destFile = destFile,
            onProgress = onProgress
        )
        if (result.isFailure) return result

        LogCollector.d(TAG, "v6 模型 $type 下载完成")
        return Result.success(Unit)
    }

    fun deleteV6Medium(context: Context): Result<Unit> {
        return try {
            val dir = getV6ModelDir(context)
            File(dir, "det_v6_medium.onnx").let { if (it.exists()) it.delete() }
            File(dir, "rec_v6_medium.onnx").let { if (it.exists()) it.delete() }
            LogCollector.d(TAG, "v6 模型已删除")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除 v6 模型失败", e)
            Result.failure(e)
        }
    }

    fun getV6MediumSize(type: String): String = when (type) {
        "det" -> "~60MB"
        "rec" -> "~74MB"
        else -> "?"
    }
}
