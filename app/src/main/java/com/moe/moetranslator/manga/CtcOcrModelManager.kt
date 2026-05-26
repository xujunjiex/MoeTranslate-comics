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
     * 检查模型是否在 filesDir（用户下载的，可删除）
     */
    fun isModelInFilesDir(context: Context): Boolean {
        val modelFile = getModelFile(context)
        val alphabetFile = getAlphabetFile(context)
        return modelFile.exists() && alphabetFile.exists()
    }

    /**
     * 检查 assets 中是否有模型文件
     */
    fun isModelInAssets(context: Context): Boolean {
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
            // 打印 zip 内文件列表
            ZipFile(zipFile).use { zip ->
                LogCollector.d(TAG, "zip 内文件列表:")
                zip.entries().asSequence().forEach { entry ->
                    LogCollector.d(TAG, "  ${entry.name} (${entry.size} bytes)")
                }
            }
            unzip(zipFile, modelDir)
            // 删除 zip 文件
            if (!zipFile.delete()) {
                LogCollector.e(TAG, "Failed to delete zip file: $zipFile")
            }
            // 重命名文件（如果需要）
            renameFiles(modelDir)
            LogCollector.d(TAG, "下载完成，模型目录内容:")
            modelDir.listFiles()?.forEach { file ->
                LogCollector.d(TAG, "  ${file.name} (${file.length()} bytes)")
            }
            // 验证 model.onnx 是否存在
            val modelOnnx = File(modelDir, MODEL_FILE)
            if (modelOnnx.exists()) {
                LogCollector.d(TAG, "model.onnx 大小: ${modelOnnx.length()} bytes")
            } else {
                LogCollector.e(TAG, "model.onnx 不存在！")
            }
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
        // ocr-ctc.zip 内可能直接包含 model.onnx（不需要重命名）
        // 也可能包含 ocr-ctc.ckpt 需要重命名为 model.onnx

        val onnxFile = File(modelDir, MODEL_FILE)
        if (onnxFile.exists()) {
            LogCollector.d(TAG, "model.onnx 已存在，无需重命名")
            return
        }

        // 查找 .ckpt 文件并重命名
        val ckptFile = modelDir.listFiles()?.find { it.name.contains("ocr-ctc") && it.extension == "ckpt" }
        if (ckptFile != null) {
            val renamed = ckptFile.renameTo(onnxFile)
            if (renamed) {
                LogCollector.d(TAG, "重命名模型文件: ${ckptFile.name} -> ${onnxFile.name}")
            } else {
                LogCollector.e(TAG, "重命名失败，尝试复制: ${ckptFile.name}")
                ckptFile.copyTo(onnxFile, overwrite = true)
                ckptFile.delete()
            }
        } else {
            // 检查是否有其他文件
            modelDir.listFiles()?.forEach { file ->
                LogCollector.d(TAG, "未找到 .ckpt 或 model.onnx，当前文件: ${file.name}")
            }
        }

        // 清理子目录
        modelDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            LogCollector.d(TAG, "清理子目录: ${dir.name}")
            dir.listFiles()?.forEach { file ->
                val destFile = File(modelDir, file.name)
                if (!destFile.exists()) {
                    file.copyTo(destFile)
                }
            }
            dir.deleteRecursively()
        }
    }
}