package com.moe.moetranslator.manga

import android.graphics.Bitmap
import android.graphics.Rect
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.utils.LogCollector

/**
 * 统一检测桥接层。
 *
 * 支持两种检测器：
 * - ML Kit: 检测 + 识别一体化
 * - DBNet: 仅检测（定位文字区域），识别需要单独调用 ML Kit 或 manga-ocr
 */
object DetectionBridge {

    private const val TAG = "DetectionBridge"

    private const val BATCH_SIZE = 16

    /**
     * 使用 DBNet 检测文字区域，然后用指定引擎识别文字。
     *
     * 流程对齐 manga-image-translator：
     * Detection → OCR（逐个 box，批量编码）→ Textline Merge（几何+文字合并）
     *
     * @param bitmap 输入图片
     * @param language 语言（用于 ML Kit 识别）
     * @param useMangaOcr 是否使用 manga-ocr 识别（否则用 ML Kit）
     * @return TextBlockInfo 列表（含位置和识别文字）
     */
    suspend fun detectWithDBNet(
        bitmap: Bitmap,
        language: String,
        useMangaOcr: Boolean
    ): List<TextBlockInfo> {
        try {
            // Step 1: DBNet 检测文字区域（QuadBox，保留旋转信息）
            LogCollector.d(TAG, "使用 DBNet 检测文字区域...")
            val rawQuadBoxes = DBNetDetector.detectQuadBoxes(bitmap)
            if (rawQuadBoxes.isEmpty()) {
                LogCollector.d(TAG, "DBNet 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "DBNet 检测到 ${rawQuadBoxes.size} 个文字区域")

            // Step 2: 逐个 box OCR（批量编码，对齐 manga-image-translator 的 OCR 在合并之前）
            val textQuadBoxes = recognizeQuadBoxes(bitmap, rawQuadBoxes, language, useMangaOcr)
            LogCollector.d(TAG, "OCR 完成: ${rawQuadBoxes.size} 个 box → ${textQuadBoxes.size} 个有文字的 box")

            if (textQuadBoxes.isEmpty()) {
                LogCollector.d(TAG, "OCR 未识别到任何文字")
                return emptyList()
            }

            // Step 3: 合并（有文字信息，能做更好的判断，对齐 merge_bboxes_text_region）
            val mergedGroups = BoxMerger.merge(textQuadBoxes)
            LogCollector.d(TAG, "box 合并: ${textQuadBoxes.size} → ${mergedGroups.size} 个文本行")

            // Step 4: 构建结果（合并后的文字拼接）
            val results = mutableListOf<TextBlockInfo>()
            for (group in mergedGroups) {
                val rect = computeBoundingRect(group)
                val combinedText = group.joinToString("") { it.text }
                if (combinedText.isNotBlank()) {
                    results.add(TextBlockInfo(
                        text = combinedText,
                        boundingBox = rect,
                        cornerPoints = null
                    ))
                }
                LogCollector.d(TAG, "合并结果: rect=$rect, text='$combinedText'")
            }

            LogCollector.d(TAG, "DBNet 检测完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "DBNet 检测失败", e)
            throw e
        }
    }

    /**
     * 对每个 QuadBox 裁剪并识别，使用批量编码优化。
     *
     * 对齐 manga-image-translator 的 OCR 阶段：
     * - 每个 box 独立识别（不做合并）
     * - 使用 batch 编码减少 Encoder 调用次数
     * - 跳过空白识别结果
     */
    private suspend fun recognizeQuadBoxes(
        bitmap: Bitmap,
        quadBoxes: List<QuadBox>,
        language: String,
        useMangaOcr: Boolean
    ): List<QuadBox> {
        // 裁剪所有 box
        val croppedBitmaps = quadBoxes.map { cropBitmap(bitmap, it.aabb) }

        try {
            val texts = if (useMangaOcr && MangaOcrBridge.isAvailable()) {
                // 批量识别：一次 Encoder 调用处理 BATCH_SIZE 个 box
                recognizeMangaOcrBatched(croppedBitmaps)
            } else {
                // ML Kit 逐个识别
                croppedBitmaps.map { cropped ->
                    try {
                        OCRBridge.recognizeText(language, cropped)
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "ML Kit 识别失败", e)
                        ""
                    }
                }
            }

            // 构建有文字的 QuadBox
            val result = mutableListOf<QuadBox>()
            for (i in quadBoxes.indices) {
                val text = texts[i].trim()
                if (text.isNotBlank()) {
                    result.add(QuadBox(quadBoxes[i].pts, text, quadBoxes[i].prob))
                }
            }
            return result

        } finally {
            // 释放裁剪的图片
            for (cropped in croppedBitmaps) {
                if (cropped !== bitmap) cropped.recycle()
            }
        }
    }

    /**
     * 使用 manga-ocr 批量识别。
     *
     * recognizeBatch 内部使用真正的 batch Encoder（一次处理 N 张图片），
     * 按 BATCH_SIZE 分块避免单次 batch 过大导致 OOM。
     */
    private suspend fun recognizeMangaOcrBatched(bitmaps: List<Bitmap>): List<String> {
        val results = mutableListOf<String>()
        for (chunk in bitmaps.chunked(BATCH_SIZE)) {
            results.addAll(MangaOcrRecognizer.recognizeBatch(chunk))
        }
        return results
    }

    private fun computeBoundingRect(group: List<QuadBox>): Rect {
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE; var bottom = Int.MIN_VALUE
        for (qb in group) {
            val aabb = qb.aabb
            left = minOf(left, aabb.left)
            top = minOf(top, aabb.top)
            right = maxOf(right, aabb.right)
            bottom = maxOf(bottom, aabb.bottom)
        }
        return Rect(left, top, right, bottom)
    }

    /**
     * 使用 CTD 检测文字区域，然后用 ML Kit 识别文字。
     *
     * 流程对齐 manga-image-translator：
     * CTD 检测 → pre-expand(1.5x) → merge → final-expand(2x) → ML Kit OCR
     */
    suspend fun detectWithCTDMLKit(
        bitmap: Bitmap,
        language: String
    ): List<TextBlockInfo> {
        try {
            // Step 1: CTD 检测文字区域
            LogCollector.d(TAG, "使用 CTD(MLKit) 检测文字区域...")
            val quadBoxes = CTDDetector.detectQuadBoxes(bitmap)
            if (quadBoxes.isEmpty()) {
                LogCollector.d(TAG, "CTD(MLKit) 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "CTD(MLKit) 检测到 ${quadBoxes.size} 个文字区域")
            for ((idx, qb) in quadBoxes.withIndex()) {
                val aabb = qb.aabb
                LogCollector.d(TAG, "CTD(MLKit) QuadBox[$idx]: AABB=[${aabb.left}, ${aabb.top}, ${aabb.right}, ${aabb.bottom}], area=${String.format("%.1f", qb.area)}, fontSize=${String.format("%.1f", qb.fontSize)}")
            }

            // Step 2: 提取 AABB 列表用于合并
            val rects = quadBoxes.map { DetectedRect(it.aabb, it.aspectRatio > 1) } // isVertical 用宽高比推断

            // Step 3: pre-expand (1.5x)
            val PRE_EXPAND = 1.5f
            val preExpandedRects = rects.map { detectedRect ->
                val rect = detectedRect.rect
                val cx = (rect.left + rect.right) / 2f
                val ew = rect.width() * PRE_EXPAND
                Rect(
                    (cx - ew / 2).toInt().coerceAtLeast(0),
                    rect.top,
                    (cx + ew / 2).toInt().coerceAtMost(bitmap.width),
                    rect.bottom
                )
            }

            // Step 4: mergeRectsByRowThenCol
            val mergedRects = mergeRectsByRowThenCol(preExpandedRects)
            LogCollector.d(TAG, "CTD(MLKit) 合并: ${rects.size} → ${mergedRects.size} 个区域")

            // Step 5: final-expand (2x)，确保最小宽度 >= 32px（ML Kit 要求）
            val FINAL_EXPAND = 2.0f
            val MIN_WIDTH = 32  // ML Kit 最小要求
            val expandedRects = mergedRects.map { rect ->
                val cx = (rect.left + rect.right) / 2f
                val ew = maxOf(rect.width() * FINAL_EXPAND, MIN_WIDTH.toFloat())
                Rect(
                    (cx - ew / 2).toInt().coerceAtLeast(0),
                    rect.top,
                    (cx + ew / 2).toInt().coerceAtMost(bitmap.width),
                    rect.bottom
                )
            }

            // 合并日志
            for ((idx, rect) in expandedRects.withIndex()) {
                val merged = mergedRects.getOrNull(idx)
                val mergedStr = if (merged != null) " <- [${merged.left}, ${merged.top}, ${merged.right}, ${merged.bottom}]" else ""
                LogCollector.d(TAG, "CTD(MLKit) [$idx]: 最终扩展[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}]$mergedStr")
            }

            // Step 6: 裁剪图片并用 ML Kit 识别
            val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }

            val results = mutableListOf<TextBlockInfo>()
            for (i in expandedRects.indices) {
                val cropped = croppedBitmaps[i]
                try {
                    val text = OCRBridge.recognizeText(language, cropped)
                    if (text.isNotBlank()) {
                        results.add(TextBlockInfo(
                            text = text,
                            boundingBox = expandedRects[i],
                            cornerPoints = null,
                            isVertical = null
                        ))
                        LogCollector.d(TAG, "CTD(MLKit) 识别结果[$i]: rect=[${expandedRects[i].left}, ${expandedRects[i].top}, ${expandedRects[i].right}, ${expandedRects[i].bottom}], text='$text'")
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "CTD(MLKit) ML Kit 识别失败[$i]", e)
                }
            }

            // 释放裁剪的图片
            for (cropped in croppedBitmaps) {
                if (cropped !== bitmap) cropped.recycle()
            }

            LogCollector.d(TAG, "CTD(MLKit) + ML Kit 完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "CTD(MLKit) + ML Kit 失败", e)
            throw e
        }
    }

    /**
     * 使用 ML Kit 检测+识别（或 manga-ocr 混合模式）。
     * 这是原有逻辑的封装，保持向后兼容。
     */
    suspend fun detectWithMLKit(
        bitmap: Bitmap,
        language: String,
        useMangaOcr: Boolean
    ): List<TextBlockInfo> {
        return if (useMangaOcr && MangaOcrBridge.isAvailable()) {
            LogCollector.d(TAG, "使用 ML Kit 检测 + manga-ocr 识别")
            MangaOcrBridge.recognizeWithLocation(bitmap, language)
        } else {
            LogCollector.d(TAG, "使用 ML Kit 检测 + 识别")
            OCRBridge.recognizeWithLocation(language, bitmap)
        }
    }

    private fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val padding = 10
        val left = maxOf(0, rect.left - padding)
        val top = maxOf(0, rect.top - padding)
        val right = minOf(bitmap.width, rect.right + padding)
        val bottom = minOf(bitmap.height, rect.bottom + padding)

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            LogCollector.w(TAG, "裁剪区域无效: left=$left, top=$top, right=$right, bottom=$bottom")
            return bitmap
        }

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /**
     * 使用 CTD(简化版) + manga-ocr 识别。
     *
     * 流程：CTD 简化检测 → 按 Y 分组合并 → 扩展宽度(2x) → manga-ocr 识别
     * 对齐 manga-image-translator 的 ctd + mocr 组合
     */
    suspend fun detectWithCTDManga(
        bitmap: Bitmap,
        language: String
    ): List<TextBlockInfo> {
        try {
            // Step 1: CTD 简化检测，只返回 AABB 列表
            LogCollector.d(TAG, "使用 CTD(简化) 检测文字区域...")
            val rects = CTDDetector.detectRectsSimple(bitmap)
            if (rects.isEmpty()) {
                LogCollector.d(TAG, "CTD(简化) 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "CTD(简化) 检测到 ${rects.size} 个文字区域")
            for ((idx, detectedRect) in rects.withIndex()) {
                LogCollector.d(TAG, "CTD(简化) 检测[$idx]: rect=[${detectedRect.rect.left}, ${detectedRect.rect.top}, ${detectedRect.rect.right}, ${detectedRect.rect.bottom}], isVertical=${detectedRect.isVertical}")
            }

            // Step 2: 扩展宽度（1.5x），让独立框有足够间隙后再合并
            val PRE_EXPAND = 1.5f
            val preExpandedRects = rects.map { detectedRect ->
                val rect = detectedRect.rect
                val cx = (rect.left + rect.right) / 2f
                val ew = rect.width() * PRE_EXPAND
                Rect(
                    (cx - ew / 2).toInt().coerceAtLeast(0),
                    rect.top,
                    (cx + ew / 2).toInt().coerceAtMost(bitmap.width),
                    rect.bottom
                )
            }
            LogCollector.d(TAG, "CTD(简化) 预扩展后: ${preExpandedRects.size} 个框")

            // Step 3: 按 Y 分组，组内按 X 合并相邻框
            val mergedRects = mergeRectsByRowThenCol(preExpandedRects)
            LogCollector.d(TAG, "CTD(简化) 合并: ${rects.size} → ${mergedRects.size} 个区域")

            // Step 4: 最终扩展宽度（2x），确保渲染文字有足够空间
            val FINAL_EXPAND = 2.0f
            val expandedRects = mergedRects.map { rect ->
                val cx = (rect.left + rect.right) / 2f
                val ew = rect.width() * FINAL_EXPAND
                Rect(
                    (cx - ew / 2).toInt().coerceAtLeast(0),
                    rect.top,
                    (cx + ew / 2).toInt().coerceAtMost(bitmap.width),
                    rect.bottom
                )
            }
            // 合并日志：合并后+最终扩展一起输出
            for ((idx, rect) in expandedRects.withIndex()) {
                val merged = mergedRects.getOrNull(idx)
                val mergedStr = if (merged != null) " → [${merged.left}, ${merged.top}, ${merged.right}, ${merged.bottom}]" else ""
                LogCollector.d(TAG, "CTD(简化) [$idx]: 合并后→最终扩展: [${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}]$mergedStr")
            }

            // Step 4: 裁剪图片并批量识别
            val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }

            // Step 5: manga-ocr 批量识别
            val texts = MangaOcrRecognizer.recognizeBatch(croppedBitmaps)

            // Step 6: 构建结果
            // 使用全部原始 rects 做 isVertical 多数投票
            val globalIsVertical = rects.count { it.isVertical } > rects.size / 2
            val results = mutableListOf<TextBlockInfo>()
            for (i in expandedRects.indices) {
                val text = texts[i].trim()
                // 过滤纯符号模式（". . ." 或类似）
                if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                    val rect = expandedRects[i]
                    results.add(TextBlockInfo(
                        text = text,
                        boundingBox = rect,
                        cornerPoints = null,
                        isVertical = globalIsVertical
                    ))
                    LogCollector.d(TAG, "CTD(简化) 识别结果[$i]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}], text='$text', isVertical=$globalIsVertical")
                } else if (isDotOnlyPattern(text)) {
                    LogCollector.d(TAG, "CTD(简化) 过滤纯符号[$i]: '$text'")
                }
            }

            // 释放裁剪的图片
            for (cropped in croppedBitmaps) {
                if (cropped !== bitmap) cropped.recycle()
            }

            LogCollector.d(TAG, "CTD(简化) + manga-ocr 完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "CTD(简化) + manga-ocr 失败", e)
            throw e
        }
    }

    /**
     * 判断是否是纯符号模式（如 ". . . " 或 "· · ·"）
     */
    private fun isDotOnlyPattern(text: String): Boolean {
        // 移除非日文字符，只保留点号和空格
        val normalized = text.filter { it == '.' || it == '·' || it == ' ' || it == '…' }
        // 如果剩余字符中点号占比超过 80%，认为是纯符号模式
        val dotCount = normalized.count { it == '.' || it == '·' || it == '…' }
        return dotCount > 0 && dotCount >= normalized.length * 0.8
    }

    /**
     * 按 Y 分组，组内按 X 合并相邻框
     * 1. 按 top 排序
     * 2. Y 范围重叠（或 gap ≤ 阈值）视为同一行
     * 3. 同行的框再按 X 方向合并相邻的
     */
    private fun mergeRectsByRowThenCol(rects: List<Rect>): List<Rect> {
        if (rects.isEmpty()) return emptyList()
        if (rects.size == 1) return rects

        val sorted = rects.sortedBy { it.top }
        val rows = mutableListOf<MutableList<Rect>>()
        var currentRow = mutableListOf<Rect>()
        var currentRowBottom = sorted[0].bottom

        val Y_GAP_THRESHOLD = 30 // Y 间隙超过 30px 视为不同行

        for (rect in sorted) {
            val gap = rect.top - currentRowBottom
            if (gap <= Y_GAP_THRESHOLD) {
                currentRow.add(rect)
                currentRowBottom = maxOf(currentRowBottom, rect.bottom)
            } else {
                if (currentRow.isNotEmpty()) rows.add(currentRow)
                currentRow = mutableListOf(rect)
                currentRowBottom = rect.bottom
            }
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        // 组内按 X 合并
        val result = mutableListOf<Rect>()
        for (row in rows) {
            val sortedRow = row.sortedBy { it.left }
            var merged: Rect? = null
            for (rect in sortedRow) {
                if (merged == null) {
                    merged = rect
                } else {
                    val gap = rect.left - merged.right
                    if (gap <= 15) { // X 间隙 ≤ 15px 合并
                        merged = Rect(
                            merged.left,
                            minOf(merged.top, rect.top),
                            maxOf(merged.right, rect.right),
                            maxOf(merged.bottom, rect.bottom)
                        )
                    } else {
                        result.add(merged)
                        merged = rect
                    }
                }
            }
            if (merged != null) result.add(merged)
        }
        return result
    }
}
