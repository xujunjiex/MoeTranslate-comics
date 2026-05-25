package com.moe.moetranslator.manga

import android.content.Context
import com.moe.moetranslator.utils.LogCollector
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * CTC OCR 模型管理器
 *
 * 模型文件：
 * - model.onnx (if converted from ocr-ctc.ckpt)
 * - alphabet-all-v5.txt (字符表)
 *
 * 下载地址: https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr-ctc.zip
 */
object CtcOcrModelManager {

    private const val TAG = "CtcOcrModelManager"
    const val MODEL_DIR = "ocr_ctc"
    const val MODEL_FILE = "model.onnx"
    const val ALPHABET_FILE = "alphabet-all-v5.txt"

    private const val DOWNLOAD_URL = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr-ctc.zip"
    private const val HASH = "fc61c52f7a811bc72c54f6be85df814c6b60f63585175db27cb94a08e0c30101"

    /**
     * 获取模型目录（应用内部存储）
     */
    fun getModelDir(context: Context): File {
        return File(context.filesDir, MODEL_DIR)
    }

    /**
     * 获取模型文件路径
     */
    fun getModelFile(context: Context): File {
        return File(getModelDir(context), MODEL_FILE)
    }

    /**
     * 获取字符表文件路径
     */
    fun getAlphabetFile(context: Context): File {
        return File(getModelDir(context), ALPHABET_FILE)
    }

    /**
     * 检查模型是否可用
     * 优先检查 filesDir（用户下载的），没有则检查 assets（内置的）
     */
    fun isModelDownloaded(context: Context): Boolean {
        // 先检查 filesDir（用户下载的）
        val modelFile = getModelFile(context)
        val alphabetFile = getAlphabetFile(context)
        if (modelFile.exists() && alphabetFile.exists()) {
            return true
        }
        // 再检查 assets（内置的）
        return isModelInAssets(context)
    }

    /**
     * 检查 assets 中是否有模型文件
     */
    private fun isModelInAssets(context: Context): Boolean {
        return try {
            context.assets.open("$MODEL_DIR/$MODEL_FILE").use { }
            context.assets.open("$MODEL_DIR/$ALPHABET_FILE").use { }
            true
        } catch (e: Exception) {
            false
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
                    return Result.failure(IOException("Failed to delete model directory"))
                }
            }
            LogCollector.d(TAG, "模型已删除: ${modelDir.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "删除模型失败", e)
            Result.failure(e)
        }
    }

    /**
     * 获取模型大小
     */
    fun getModelSize(context: Context): Long {
        val modelDir = getModelDir(context)
        if (!modelDir.exists()) return 0
        return modelDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    /**
     * 获取模型大小描述
     */
    fun getModelSizeString(context: Context): String {
        val size = getModelSize(context)
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
    }

    /**
     * 下载模型
     */
    suspend fun downloadModel(
        context: Context,
        onProgress: ModelDownloadManager.ProgressCallback? = null
    ): Result<Unit> {
        val modelDir = getModelDir(context)
        if (!modelDir.exists()) {
            if (!modelDir.mkdirs()) {
                return Result.failure(IllegalStateException("Failed to create model directory: $modelDir"))
            }
        }

        // 下载 zip 文件
        val zipFile = File(modelDir, "ocr-ctc.zip")
        val result = ModelDownloadManager.downloadModel(
            context = context,
            url = DOWNLOAD_URL,
            sha256Hash = HASH,
            destFile = zipFile,
            onProgress = onProgress
        )

        if (result.isFailure) {
            return result
        }

        // 解压
        return try {
            unzip(zipFile, modelDir)
            // 删除 zip 文件
            if (!zipFile.delete()) {
                LogCollector.e(TAG, "Failed to delete zip file: $zipFile")
            }
            // 重命名文件（如果需要）
            renameFiles(modelDir)
            Result.success(Unit)
        } catch (e: Exception) {
            LogCollector.e(TAG, "解压失败", e)
            Result.failure(e)
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val file = File(destDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun renameFiles(modelDir: File) {
        // ocr-ctc.zip 解压后可能是 ocr-ctc/ 目录下的文件
        // 或者直接包含 ocr-ctc.ckpt 和 alphabet-all-v5.txt
        // 需要根据实际情况处理
        val extractedDir = File(modelDir, "extracted")
        if (extractedDir.exists()) {
            // 从 extracted 目录移动文件到 modelDir
            extractedDir.listFiles()?.forEach { file ->
                file.copyTo(File(modelDir, file.name), overwrite = true)
            }
            extractedDir.deleteRecursively()
        }

        // 处理可能存在的 ocr-ctc 子目录（如 zip 解压到 ocr-ctc/ 下）
        val subDirs = modelDir.listFiles { f -> f.isDirectory && f.name != "extracted" }
        subDirs?.forEach { subDir ->
            subDir.listFiles()?.forEach { file ->
                file.copyTo(File(modelDir, file.name), overwrite = true)
            }
            subDir.deleteRecursively()
        }

        // 如果存在 ocr-ctc.ckpt，转换为 model.onnx（如果需要）
        // 目前 CtcOcrRecognizer 使用 model.onnx
    }
}