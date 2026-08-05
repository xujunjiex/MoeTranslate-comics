package com.moe.starflow.manga.engine
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.content.Context
import com.moe.starflow.utils.LogCollector
import java.io.File

/**
 * PP-OCRv5/v6 模型管理器（仅查询 API，下载走 ModelDownloadService）
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
object PPOcrModelFiles {

    private const val TAG = "PPOcrModelFiles"
    private const val MODEL_DIR = "ppocrv5"

    fun isRecDictDownloaded(context: Context, lang: String): Boolean {
        val f = File(getModelDir(context), "rec_${lang}_dict.txt")
        return f.exists() && f.length() > 0
    }

    // ========================================================================
    // v5 核心模型（det + rec_zh，原内置改为下载）
    // ========================================================================

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

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    // ========================================================================
    // PP-OCRv6 medium 模型查询（下载走 Service）
    // ========================================================================

    private const val V6_MODEL_DIR = "ppocrv6"

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
}