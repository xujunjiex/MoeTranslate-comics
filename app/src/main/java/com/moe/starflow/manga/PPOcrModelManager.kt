package com.moe.starflow.manga

import android.content.Context
import com.moe.starflow.utils.LogCollector
import java.io.File

/**
 * PP-OCRv5 模型管理器
 *
 * v5 所有模型（det/rec/字典）均为下载使用，不再内置在 assets 中。
 * 模型存储在 filesDir/ppocrv5/ 目录。下载源：ModelScope (RapidAI/RapidOCR)
 *
 * 核心模型（需下载）：
 * - det_v5.onnx (~4.6MB) 文字区域检测
 * - rec_zh.onnx (~16MB) 中日英混合识别
 * - rec_zh_dict.txt (~75KB) 中日英字典
 *
 * 可选模型（需下载）：
 * - rec_en.onnx (~7.5MB) + 字典 — 英文识别
 * - rec_ko.onnx (~12.9MB) + 字典 — 韩文识别
 * - rec_ru.onnx (~7.7MB) + 字典 — 俄文/西里尔文字识别
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

    // v5 dict URLs（随 rec 模型一起下载）
    private const val V5_DICT_BASE = "https://modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.1/paddle/PP-OCRv5/rec"

    val REC_DICT_URLS = mapOf(
        "en" to "$V5_DICT_BASE/en_PP-OCRv5_rec_mobile/ppocrv5_en_dict.txt",
        "ko" to "$V5_DICT_BASE/korean_PP-OCRv5_rec_mobile/ppocrv5_korean_dict.txt",
        "ru" to "$V5_DICT_BASE/cyrillic_PP-OCRv5_rec_mobile/ppocrv5_cyrillic_dict.txt"
    )

    fun isRecDictDownloaded(context: Context, lang: String): Boolean {
        val f = File(getModelDir(context), "rec_${lang}_dict.txt")
        return f.exists() && f.length() > 0
    }

    // ========================================================================
    // v5 核心模型（det + rec_zh，原内置改为下载）
    // ========================================================================

    private const val V5_BASE_ONNX = "https://modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5"
    private const val V5_BASE_DICT2 = "https://modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.1/paddle/PP-OCRv5"

    val V5_DET_URL = "$V5_BASE_ONNX/det/ch_PP-OCRv5_det_mobile.onnx"
    val V5_REC_ZH_ONNX_URL = "$V5_BASE_ONNX/rec/ch_PP-OCRv5_rec_mobile.onnx"
    val V5_REC_ZH_DICT_URL = "$V5_BASE_DICT2/rec/ch_PP-OCRv5_rec_mobile/ppocrv5_dict.txt"

    fun isV5DetDownloaded(context: Context): Boolean {
        val f = File(getModelDir(context), "det_v5.onnx")
        return f.exists() && f.length() > 0
    }

    fun isV5RecZhDownloaded(context: Context): Boolean {
        val f = File(getModelDir(context), "rec_zh.onnx")
        return f.exists() && f.length() > 0
    }

    fun isV5RecZhDictDownloaded(context: Context): Boolean {
        val f = File(getModelDir(context), "rec_zh_dict.txt")
        return f.exists() && f.length() > 0
    }

    suspend fun downloadV5Det(
        context: Context,
        onProgress: ModelDownloadManager.ProgressCallback? = null
    ): Result<Unit> {
        val dir = getModelDir(context)
        if (!dir.exists()) dir.mkdirs()
        val destFile = File(dir, "det_v5.onnx")
        if (destFile.exists() && destFile.length() > 0) {
            LogCollector.d(TAG, "v5 det 已存在，跳过")
            return Result.success(Unit)
        }
        LogCollector.d(TAG, "开始下载 v5 det: $V5_DET_URL")
        return ModelDownloadManager.downloadModel(context, V5_DET_URL, "", destFile, onProgress)
    }

    suspend fun downloadV5RecZh(
        context: Context,
        onProgress: ModelDownloadManager.ProgressCallback? = null
    ): Result<Unit> {
        val dir = getModelDir(context)
        if (!dir.exists()) dir.mkdirs()

        // 1. 下载 ONNX
        val onnxFile = File(dir, "rec_zh.onnx")
        if (!onnxFile.exists() || onnxFile.length() == 0L) {
            LogCollector.d(TAG, "开始下载 v5 rec_zh ONNX: $V5_REC_ZH_ONNX_URL")
            val r1 = ModelDownloadManager.downloadModel(context, V5_REC_ZH_ONNX_URL, "", onnxFile, onProgress)
            if (r1.isFailure) return r1
        }

        // 2. 下载字典
        val dictFile = File(dir, "rec_zh_dict.txt")
        if (!dictFile.exists() || dictFile.length() == 0L) {
            LogCollector.d(TAG, "开始下载 v5 rec_zh 字典: $V5_REC_ZH_DICT_URL")
            val r2 = ModelDownloadManager.downloadModel(context, V5_REC_ZH_DICT_URL, "", dictFile, onProgress)
            if (r2.isFailure) return r2
        }

        LogCollector.d(TAG, "v5 rec_zh 下载完成")
        return Result.success(Unit)
    }

    fun deleteV5Det(context: Context): Result<Unit> {
        return try {
            File(getModelDir(context), "det_v5.onnx").let { if (it.exists()) it.delete() }
            LogCollector.d(TAG, "v5 det 已删除")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除 v5 det 失败", e)
            Result.failure(e)
        }
    }

    fun deleteV5RecZh(context: Context): Result<Unit> {
        return try {
            val dir = getModelDir(context)
            File(dir, "rec_zh.onnx").let { if (it.exists()) it.delete() }
            File(dir, "rec_zh_dict.txt").let { if (it.exists()) it.delete() }
            LogCollector.d(TAG, "v5 rec_zh 已删除（含字典）")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除 v5 rec_zh 失败", e)
            Result.failure(e)
        }
    }

    fun getV5DetSize(): String = "~4.6MB"
    fun getV5RecZhSize(): String = "~16MB"
    fun getV5DetSizeString(context: Context): String {
        val f = File(getModelDir(context), "det_v5.onnx")
        return if (f.exists()) formatSize(f.length()) else "0 B"
    }
    fun getV5RecZhSizeString(context: Context): String {
        val dir = getModelDir(context)
        var total = 0L
        File(dir, "rec_zh.onnx").let { if (it.exists()) total += it.length() }
        File(dir, "rec_zh_dict.txt").let { if (it.exists()) total += it.length() }
        return formatSize(total)
    }

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
     * 下载可选 rec 模型（ONNX + 字典）
     * @param lang "en"、"ko" 或 "ru"
     */
    suspend fun downloadRecModel(
        context: Context,
        lang: String,
        onProgress: ModelDownloadManager.ProgressCallback? = null
    ): Result<Unit> {
        val dir = getOrCreateDir(context)

        // 1. 下载 ONNX
        val recFileName = "rec_$lang.onnx"
        val recFile = File(dir, recFileName)
        if (!recFile.exists() || recFile.length() == 0L) {
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
        }

        // 2. 下载字典
        val dictFileName = "rec_${lang}_dict.txt"
        val dictFile = File(dir, dictFileName)
        if (!dictFile.exists() || dictFile.length() == 0L) {
            val dictUrl = REC_DICT_URLS[lang]
                ?: return Result.failure(IllegalArgumentException("Unknown dict for: $lang"))
            LogCollector.d(TAG, "开始下载 $dictFileName: $dictUrl")
            val dictResult = ModelDownloadManager.downloadModel(
                context = context,
                url = dictUrl,
                sha256Hash = "",
                destFile = dictFile,
                onProgress = onProgress
            )
            if (dictResult.isFailure) return dictResult
        }

        LogCollector.d(TAG, "可选模型 $lang 下载完成（含字典）")
        return Result.success(Unit)
    }

    /**
     * 删除可选 rec 模型（含字典）
     */
    fun deleteRecModel(context: Context, lang: String): Result<Unit> {
        return try {
            val dir = getModelDir(context)
            File(dir, "rec_$lang.onnx").let { if (it.exists()) it.delete() }
            File(dir, "rec_${lang}_dict.txt").let { if (it.exists()) it.delete() }
            LogCollector.d(TAG, "可选模型 $lang 已删除（含字典）")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除可选模型 $lang 失败", e)
            Result.failure(e)
        }
    }

    /**
     * 可选 rec 模型大小描述（含字典）
     */
    fun getRecModelSizeString(context: Context, lang: String): String {
        val dir = getModelDir(context)
        var total = 0L
        File(dir, "rec_$lang.onnx").let { if (it.exists()) total += it.length() }
        File(dir, "rec_${lang}_dict.txt").let { if (it.exists()) total += it.length() }
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

    fun isV6MediumDownloaded(context: Context, type: String): Boolean {
        val fileName = if (type == "det") "det_v6_medium.onnx" else "rec_v6_medium.onnx"
        val file = File(getV6ModelDir(context), fileName)
        return file.exists() && file.length() > 0
    }

    fun deleteV6Medium(context: Context, type: String): Result<Unit> {
        return try {
            val fileName = if (type == "det") "det_v6_medium.onnx" else "rec_v6_medium.onnx"
            val file = File(getV6ModelDir(context), fileName)
            if (file.exists()) file.delete()
            LogCollector.d(TAG, "v6 模型 $type 已删除")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除 v6 模型 $type 失败", e)
            Result.failure(e)
        }
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
