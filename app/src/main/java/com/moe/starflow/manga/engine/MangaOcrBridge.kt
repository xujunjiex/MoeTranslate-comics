package com.moe.starflow.manga.engine
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.moe.starflow.utils.LogCollector

/**
 * manga-ocr 混合 OCR 桥接
 *
 * 实现"ML Kit 检测 + manga-ocr 识别"的混合模式：
 * 1. 用 ML Kit 检测文字位置（boundingBox、cornerPoints）
 * 2. 对每个检测到的文字区域，裁剪图片
 * 3. 用 manga-ocr 识别裁剪后的图片（支持并行批量识别）
 * 4. 返回 TextBlockInfo 列表（保留 ML Kit 的位置信息，使用 manga-ocr 的识别结果）
 */
object MangaOcrBridge {

    private const val TAG = "MangaOcrBridge"

    /**
     * 使用混合模式识别文字
     *
     * @param bitmap 输入图片
     * @param language 语言（用于 ML Kit 检测）
     * @return TextBlockInfo 列表，包含位置信息和 manga-ocr 识别的文字
     */
    suspend fun recognizeWithLocation(
        bitmap: Bitmap,
        language: String = "ja"
    ): List<TextBlockInfo> {
        try {
            // 1. 用 ML Kit 检测文字位置
            LogCollector.d(TAG, "使用 ML Kit 检测文字位置...")
            val mlKitBlocks = OCRBridge.recognizeWithLocation(language, bitmap)
            if (mlKitBlocks.isEmpty()) {
                LogCollector.d(TAG, "ML Kit 未检测到文字")
                return emptyList()
            }
            LogCollector.d(TAG, "ML Kit 检测到 ${mlKitBlocks.size} 个文字块")

            // 2. 分离：直接使用的块 vs 需要 manga-ocr 识别的块
            val directResults = mutableListOf<TextBlockInfo>()
            val blocksToRecognize = mutableListOf<Pair<TextBlockInfo, Bitmap>>()

            for (block in mlKitBlocks) {
                val rect = block.boundingBox
                if (rect == null) {
                    directResults.add(block)
                    continue
                }

                // 预过滤：如果 ML Kit 检测到的文字已经是纯符号，跳过 manga-ocr 识别
                if (isSymbolOnlyText(block.text)) {
                    LogCollector.d(TAG, "跳过纯符号文字块: '${block.text}'")
                    directResults.add(block)
                    continue
                }

                blocksToRecognize.add(block to cropBitmap(bitmap, rect))
            }

            // 3. 批量并行识别（根据活跃识别器选择 ONNX 或 TFLite）
            if (blocksToRecognize.isNotEmpty()) {
                LogCollector.d(TAG, "批量并行识别 ${blocksToRecognize.size} 个文字块...")
                val croppedBitmaps = blocksToRecognize.map { it.second }
                val recognizedTexts = MangaOcrRecognizer.recognizeBatch(croppedBitmaps)

                for (i in blocksToRecognize.indices) {
                    val (block, croppedBitmap) = blocksToRecognize[i]
                    val mangaOcrText = postProcessOcrText(recognizedTexts[i])

                    directResults.add(TextBlockInfo(
                        text = mangaOcrText,
                        boundingBox = block.boundingBox,
                        cornerPoints = block.cornerPoints
                    ))

                    LogCollector.d(TAG, "识别完成: ML Kit='${block.text}' -> manga-ocr='$mangaOcrText'")

                    if (croppedBitmap !== bitmap) {
                        croppedBitmap.recycle()
                    }
                }
            }

            return directResults

        } catch (e: Exception) {
            LogCollector.e(TAG, "混合 OCR 失败", e)
            throw e
        }
    }

    /**
     * 裁剪图片
     * 添加一些 padding 以确保文字完整
     */
    private fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val padding = 10
        val left = maxOf(0, rect.left - padding)
        val top = maxOf(0, rect.top - padding)
        val right = minOf(bitmap.width, rect.right + padding)
        val bottom = minOf(bitmap.height, rect.bottom + padding)

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            Log.w(TAG, "裁剪区域无效: left=$left, top=$top, right=$right, bottom=$bottom")
            return bitmap
        }

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /**
     * 检查 manga-ocr 是否可用
     */
    fun isAvailable(): Boolean {
        return MangaOcrRecognizer.isInitialized
    }

    /**
     * 批量识别
     */
    suspend fun recognizeBatch(bitmaps: List<Bitmap>): List<String> {
        return MangaOcrRecognizer.recognizeBatch(bitmaps)
    }

    /**
     * 初始化下载的 manga-ocr 模型
     */
    suspend fun initializeDownloaded(context: Context) {
        LogCollector.d(TAG, "initializeDownloaded: 加载已下载的 manga-ocr 模型")
        MangaOcrRecognizer.initialize(context, useAssets = false)
    }

    /**
     * 检查文本是否仅包含符号/标点（不含实际文字内容）。
     * 用于在 manga-ocr 识别前跳过纯符号文字块，节省 ONNX 推理时间。
     */
    private fun isSymbolOnlyText(text: String): Boolean {
        val stripped = text.replace(Regex("\\s+"), "")
        if (stripped.isEmpty()) return true
        return stripped.all { ch ->
            val type = Character.getType(ch).toByte()
            type == Character.START_PUNCTUATION ||
            type == Character.END_PUNCTUATION ||
            type == Character.DASH_PUNCTUATION ||
            type == Character.OTHER_PUNCTUATION ||
            type == Character.MATH_SYMBOL ||
            type == Character.CURRENCY_SYMBOL ||
            type == Character.MODIFIER_SYMBOL ||
            type == Character.OTHER_SYMBOL ||
            ch == '♡' || ch == '♥' || ch == '♪' || ch == '♫' ||
            ch == '〜' || ch == '～' || ch == '…' || ch == '─'
        }
    }

    /**
     * 后处理 manga-ocr 识别结果，对齐官方 Python 的 post_process。
     * 压缩连续符号、去除多余空格。
     */
    private fun postProcessOcrText(text: String): String {
        var result = text
        // 去除所有空格（manga-ocr 官方："".join(text.split())）
        result = result.replace(Regex("\\s+"), "")
        // 全角省略号 → 三个点
        result = result.replace("…", "...")
        // 压缩连续的点或中点为单个点（对齐 re.sub("[・.]{2,}", ...)）
        result = result.replace(Regex("[・.]{2,}"), ".")
        return result
    }
}
