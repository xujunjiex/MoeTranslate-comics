package com.moe.moetranslator.manga

import android.graphics.Bitmap
import android.graphics.Rect
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.utils.LogCollector
import kotlin.math.PI
import kotlin.math.abs

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
     * CTD 检测后使用的 OCR 引擎类型
     */
    enum class CTDOCREngine {
        MLKit,
        MangaOcr,
        CTCOcr
    }

    /**
     * CTD + 混合 OCR 路径。
     *
     * 流程：
     * 1. CTD 检测文字区域 → List<QuadBox>
     * 2. BoxMerger 合并 → List<List<QuadBox>>
     * 3. 分流：
     *    - size > 1（合并组）：manga-ocr 识别
     *    - size == 1（单个框）：ML Kit 识别
     *
     * @param bitmap 输入图片
     * @param language 语言（用于 ML Kit 识别）
     * @return TextBlockInfo 列表
     */
    suspend fun detectWithCTDHybrid(
        bitmap: Bitmap,
        language: String
    ): List<TextBlockInfo> {
        try {
            LogCollector.d(TAG, "detectWithCTDHybrid: 开始")

            // Step 1: CTD 检测
            val quadBoxes = CTDDetector.detectQuadBoxes(bitmap)
            if (quadBoxes.isEmpty()) {
                LogCollector.d(TAG, "detectWithCTDHybrid: CTD 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "detectWithCTDHybrid: CTD 检测到 ${quadBoxes.size} 个文字区域")

            // Step 2: BoxMerger 合并
            val groups = BoxMerger.merge(quadBoxes)
            LogCollector.d(TAG, "detectWithCTDHybrid: BoxMerger 合并: ${quadBoxes.size} → ${groups.size} 个组")

            for ((idx, group) in groups.withIndex()) {
                LogCollector.d(TAG, "detectWithCTDHybrid: 组[$idx]: size=${group.size}, rects=${group.map { it.aabb }}")
            }

            // Step 3: 分流识别
            val results = mutableListOf<TextBlockInfo>()

            for ((idx, group) in groups.withIndex()) {
                if (group.size > 1) {
                    // 合并组 → manga-ocr
                    LogCollector.d(TAG, "detectWithCTDHybrid: 组[$idx] 使用 manga-ocr (size=${group.size})")
                    try {
                        val unionRect = computeUnionAABB(group)
                        val crop = Bitmap.createBitmap(
                            bitmap,
                            unionRect.left.coerceAtLeast(0),
                            unionRect.top.coerceAtLeast(0),
                            unionRect.width().coerceAtLeast(1),
                            unionRect.height().coerceAtLeast(1)
                        )
                        val resized = Bitmap.createScaledBitmap(crop, 224, 224, true)
                        crop.recycle()
                        try {
                            val text = MangaOcrRecognizer.recognize(resized)
                            resized.recycle()
                            if (text.isNotBlank()) {
                                results.add(TextBlockInfo(
                                    text = text,
                                    boundingBox = unionRect,
                                    cornerPoints = null,
                                    isVertical = null
                                ))
                                LogCollector.d(TAG, "detectWithCTDHybrid: manga-ocr 结果[$idx]: '$text'")
                            }
                        } catch (e: Exception) {
                            resized.recycle()
                            throw e
                        }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "detectWithCTDHybrid: manga-ocr 识别失败[$idx]", e)
                    }
                } else {
                    // 单个框 → ML Kit
                    val qb = group.first()
                    LogCollector.d(TAG, "detectWithCTDHybrid: 组[$idx] 使用 ML Kit (single box)")
                    try {
                        val rect = qb.aabb
                        val paddedRect = Rect(
                            (rect.left - 10).coerceAtLeast(0),
                            (rect.top - 10).coerceAtLeast(0),
                            (rect.right + 10).coerceAtMost(bitmap.width),
                            (rect.bottom + 10).coerceAtMost(bitmap.height)
                        )
                        val crop = Bitmap.createBitmap(
                            bitmap,
                            paddedRect.left,
                            paddedRect.top,
                            paddedRect.width().coerceAtLeast(1),
                            paddedRect.height().coerceAtLeast(1)
                        )
                        val text = OCRBridge.recognizeText(language, crop)
                        crop.recycle()
                        if (text.isNotBlank()) {
                            results.add(TextBlockInfo(
                                text = text,
                                boundingBox = paddedRect,
                                cornerPoints = null,
                                isVertical = null
                            ))
                            LogCollector.d(TAG, "detectWithCTDHybrid: ML Kit 结果[$idx]: '$text'")
                        }
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "detectWithCTDHybrid: ML Kit 识别失败[$idx]", e)
                    }
                }
            }

            // Step 4: 按阅读顺序排序
            val sortedResults = sortResultsByReadingOrder(results)
            LogCollector.d(TAG, "detectWithCTDHybrid: 完成，共 ${sortedResults.size} 个结果")

            return sortedResults

        } catch (e: Exception) {
            LogCollector.e(TAG, "detectWithCTDHybrid: 失败", e)
            throw e
        }
    }

    /**
     * 按阅读顺序对结果排序。
     * 与 sortByReadingOrder 类似，但作用于 TextBlockInfo 结果。
     */
    private fun sortResultsByReadingOrder(results: List<TextBlockInfo>): List<TextBlockInfo> {
        if (results.isEmpty()) return results

        val isVertical = results.count { r ->
            r.boundingBox?.let { it.height() > it.width() } ?: false
        } > results.size / 2

        return if (isVertical) {
            results.sortedWith(compareBy({ r -> -((r.boundingBox?.centerX() ?: 0)) }, { r -> r.boundingBox?.centerY() ?: 0 }))
        } else {
            results.sortedWith(compareBy({ r -> r.boundingBox?.centerY() ?: 0 }, { r -> r.boundingBox?.centerX() ?: 0 }))
        }
    }

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
     * 使用 CTD 检测文字区域，然后用指定 OCR 引擎识别文字。
     *
     * 流程：CTD 检测 → pre-expand(1.5x) → merge → final-expand(2.5x width, 3x height) → OCR
     *
     * @param bitmap 输入图片
     * @param language 语言（用于 ML Kit 识别）
     * @param ocrEngine OCR 引擎类型（MLKit 或 MangaOcr）
     * @return TextBlockInfo 列表（含位置和识别文字）
     */
    suspend fun detectWithCTD(
        bitmap: Bitmap,
        language: String,
        ocrEngine: CTDOCREngine
    ): List<TextBlockInfo> {
        try {
            LogCollector.d(TAG, "使用 CTD(${ocrEngine.name}) 检测文字区域...")

            // 使用 detectQuadBoxes 获取真实 font_size
            val quadBoxes = CTDDetector.detectQuadBoxes(bitmap)
            if (quadBoxes.isEmpty()) {
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 检测到 ${quadBoxes.size} 个文字区域")

            // 转换为带 font_size 的 DetectedRectWithFont
            val rects = CTDDetector.convertQuadBoxesToDetectedRects(quadBoxes)
            for ((idx, detectedRect) in rects.withIndex()) {
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) 检测[$idx]: rect=[${detectedRect.rect.left}, ${detectedRect.rect.top}, ${detectedRect.rect.right}, ${detectedRect.rect.bottom}], isVertical=${detectedRect.isVertical}, fontSize=${detectedRect.fontSize}")
            }

            // mergeRectsByRowThenCol（过滤器模式）
            val mergedRects = mergeRectsByRowThenCol(rects)
            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 合并: ${rects.size} → ${mergedRects.size} 个区域")

            // final-expand: simple 10px padding only
            val PADDING = 10
            val expandedRects = mergedRects.map { rect ->
                Rect(
                    (rect.left - PADDING).coerceAtLeast(0),
                    (rect.top - PADDING).coerceAtLeast(0),
                    (rect.right + PADDING).coerceAtMost(bitmap.width),
                    (rect.bottom + PADDING).coerceAtMost(bitmap.height)
                )
            }

            for ((idx, rect) in expandedRects.withIndex()) {
                val merged = mergedRects.getOrNull(idx)
                val mergedStr = if (merged != null) " <- [${merged.left}, ${merged.top}, ${merged.right}, ${merged.bottom}]" else ""
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) [$idx]: 最终扩展[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}]$mergedStr")
            }

            // 裁剪图片
            val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }

            // OCR 识别
            val globalIsVertical = rects.count { it.isVertical } > rects.size / 2
            val results = mutableListOf<TextBlockInfo>()

            when (ocrEngine) {
                CTDOCREngine.MLKit -> {
                    for (i in expandedRects.indices) {
                        val cropped = croppedBitmaps[i]
                        try {
                            val text = OCRBridge.recognizeText(language, cropped)
                            if (text.isNotBlank()) {
                                results.add(TextBlockInfo(
                                    text = text,
                                    boundingBox = expandedRects[i],
                                    cornerPoints = null,
                                    isVertical = globalIsVertical
                                ))
                                LogCollector.d(TAG, "CTD(MLKit) 识别结果[$i]: rect=[${expandedRects[i].left}, ${expandedRects[i].top}, ${expandedRects[i].right}, ${expandedRects[i].bottom}], text='$text', isVertical=$globalIsVertical")
                            }
                        } catch (e: Exception) {
                            LogCollector.e(TAG, "CTD(MLKit) ML Kit 识别失败[$i]", e)
                        }
                    }
                }
                CTDOCREngine.MangaOcr -> {
                    val texts = MangaOcrRecognizer.recognizeBatch(croppedBitmaps)
                    for (i in expandedRects.indices) {
                        val text = texts[i].trim()
                        if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                            val rect = expandedRects[i]
                            results.add(TextBlockInfo(
                                text = text,
                                boundingBox = rect,
                                cornerPoints = null,
                                isVertical = globalIsVertical
                            ))
                            LogCollector.d(TAG, "CTD(MangaOcr) 识别结果[$i]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}], text='$text', isVertical=$globalIsVertical")
                        } else if (isDotOnlyPattern(text)) {
                            LogCollector.d(TAG, "CTD(MangaOcr) 过滤纯符号[$i]: '$text'")
                        }
                    }
                }
                CTDOCREngine.CTCOcr -> {
                    val texts = CtcOcrRecognizer.recognizeBatch(croppedBitmaps)
                    for (i in expandedRects.indices) {
                        val text = texts[i].trim()
                        if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                            val rect = expandedRects[i]
                            results.add(TextBlockInfo(
                                text = text,
                                boundingBox = rect,
                                cornerPoints = null,
                                isVertical = globalIsVertical
                            ))
                            LogCollector.d(TAG, "CTD(CTCOcr) 识别结果[$i]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}], text='$text', isVertical=$globalIsVertical")
                        } else if (isDotOnlyPattern(text)) {
                            LogCollector.d(TAG, "CTD(CTCOcr) 过滤纯符号[$i]: '$text'")
                        }
                    }
                }
            }

            // 释放裁剪的图片
            for (cropped in croppedBitmaps) {
                if (cropped !== bitmap) cropped.recycle()
            }

            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "CTD(${ocrEngine.name}) 失败", e)
            throw e
        }
    }

    /**
     * 使用 CTD 检测文字区域，然后用 ML Kit 识别文字。
     *
     * 流程对齐 manga-image-translator：
     * CTD 检测 → pre-expand(1.5x) → merge → final-expand(2x) → ML Kit OCR
     *
     * @deprecated 使用 [detectWithCTD] 替代，传入 [CTDOCREngine.MLKit]
     */
    @Deprecated("使用 detectWithCTD(bitmap, language, CTDOCREngine.MLKit) 替代", ReplaceWith("detectWithCTD(bitmap, language, CTDOCREngine.MLKit)"))
    suspend fun detectWithCTDMLKit(
        bitmap: Bitmap,
        language: String
    ): List<TextBlockInfo> {
        try {
            // Step 1: 使用 detectQuadBoxes 获取真实 font_size
            LogCollector.d(TAG, "使用 CTD(MLKit) 检测文字区域...")
            val quadBoxes = CTDDetector.detectQuadBoxes(bitmap)
            if (quadBoxes.isEmpty()) {
                LogCollector.d(TAG, "CTD(MLKit) 未检测到文字区域")
                return emptyList()
            }
            val rects = CTDDetector.convertQuadBoxesToDetectedRects(quadBoxes)
            LogCollector.d(TAG, "CTD(MLKit) 检测到 ${rects.size} 个文字区域")
            for ((idx, detectedRect) in rects.withIndex()) {
                LogCollector.d(TAG, "CTD(MLKit) 检测[$idx]: rect=[${detectedRect.rect.left}, ${detectedRect.rect.top}, ${detectedRect.rect.right}, ${detectedRect.rect.bottom}], isVertical=${detectedRect.isVertical}, fontSize=${detectedRect.fontSize}")
            }

            // mergeRectsByRowThenCol
            val mergedRects = mergeRectsByRowThenCol(rects)
            LogCollector.d(TAG, "CTD(MLKit) 合并: ${rects.size} → ${mergedRects.size} 个区域")

            // final-expand: simple 10px padding only
            val PADDING = 10
            val expandedRects = mergedRects.map { rect ->
                Rect(
                    (rect.left - PADDING).coerceAtLeast(0),
                    (rect.top - PADDING).coerceAtLeast(0),
                    (rect.right + PADDING).coerceAtMost(bitmap.width),
                    (rect.bottom + PADDING).coerceAtMost(bitmap.height)
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
     *
     * @deprecated 使用 [detectWithCTD] 替代，传入 [CTDOCREngine.MangaOcr]
     */
    @Deprecated("使用 detectWithCTD(bitmap, language, CTDOCREngine.MangaOcr) 替代", ReplaceWith("detectWithCTD(bitmap, language, CTDOCREngine.MangaOcr)"))
    suspend fun detectWithCTDManga(
        bitmap: Bitmap,
        language: String
    ): List<TextBlockInfo> {
        try {
            // Step 1: 使用 detectQuadBoxes 获取真实 font_size
            LogCollector.d(TAG, "使用 CTD(简化) 检测文字区域...")
            val quadBoxes = CTDDetector.detectQuadBoxes(bitmap)
            if (quadBoxes.isEmpty()) {
                LogCollector.d(TAG, "CTD(简化) 未检测到文字区域")
                return emptyList()
            }
            val rects = CTDDetector.convertQuadBoxesToDetectedRects(quadBoxes)
            LogCollector.d(TAG, "CTD(简化) 检测到 ${rects.size} 个文字区域")
            for ((idx, detectedRect) in rects.withIndex()) {
                LogCollector.d(TAG, "CTD(简化) 检测[$idx]: rect=[${detectedRect.rect.left}, ${detectedRect.rect.top}, ${detectedRect.rect.right}, ${detectedRect.rect.bottom}], isVertical=${detectedRect.isVertical}, fontSize=${detectedRect.fontSize}")
            }

            // mergeRectsByRowThenCol
            val mergedRects = mergeRectsByRowThenCol(rects)
            LogCollector.d(TAG, "CTD(简化) 合并: ${rects.size} → ${mergedRects.size} 个区域")

            // final-expand: simple 10px padding only
            val PADDING = 10
            val expandedRects = mergedRects.map { rect ->
                Rect(
                    (rect.left - PADDING).coerceAtLeast(0),
                    (rect.top - PADDING).coerceAtLeast(0),
                    (rect.right + PADDING).coerceAtMost(bitmap.width),
                    (rect.bottom + PADDING).coerceAtMost(bitmap.height)
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
     * 合并后的矩形及其属性
     */
    private data class MergedBox(
        val rect: Rect,
        val isVertical: Boolean,
        val fontSize: Float,
        val angle: Float,
        val aspectRatio: Float
    )

    /**
     * 对齐 manga-image-translator quadrilateral_can_merge_region
     *
     * 参数（对齐官方调用）：
     * - discard_connection_gap = 0（调用时传0）
     * - char_gap_tolerance = 0.6
     * - char_gap_tolerance2 = 3（官方调用）
     * - font_size_ratio_tol = 2
     * - aspect_ratio_tol = 1.3
     */
    private fun canMergeWithDynamicThreshold(
        a: DetectedRectWithFont, b: DetectedRectWithFont
    ): Boolean {
        // 1. 文字方向必须一致
        if (a.isVertical != b.isVertical) return false

        // 2. font_size
        val fontSizeA = a.fontSize
        val fontSizeB = b.fontSize
        val charSize = minOf(fontSizeA, fontSizeB)

        // 3. font_size 比例检查
        val fontSizeRatioTol = 2f  // 官方调用用 2
        if (maxOf(fontSizeA, fontSizeB) / charSize > fontSizeRatioTol) {
            return false
        }

        // 4. aspect_ratio 检查（拒绝一横一竖）
        val aspectRatioTol = 1.3f
        val ratio = 1.9f  // w > h * ratio 表示横排
        if (a.aspectRatio > aspectRatioTol && b.aspectRatio < 1f / aspectRatioTol) return false
        if (b.aspectRatio > aspectRatioTol && a.aspectRatio < 1f / aspectRatioTol) return false

        // 5. axis-aligned 检查（近似轴对齐）
        val aIsAxisAligned = isApproximateAxisAligned(a)
        val bIsAxisAligned = isApproximateAxisAligned(b)

        // 6. 计算 AABB gap
        val gap = calculateGap(a.rect, b.rect)

        val discardConnectionGap = 0f  // 调用时传 0
        val charGapTolerance = 1.0f  // 官方 merge 实际调用传 1
        val charGapTolerance2 = 3.0f  // 官方 merge 实际调用传 3

        if (aIsAxisAligned && bIsAxisAligned) {
            if (gap < charSize * charGapTolerance) {
                // 检查中心对齐
                val centerAX = a.rect.left + a.rect.width() / 2f
                val centerBX = b.rect.left + b.rect.width() / 2f
                if (abs(centerAX - centerBX) < charGapTolerance2) {
                    return true
                }
                // aspect ratio 判断
                val w1 = a.rect.width().toFloat()
                val h1 = a.rect.height().toFloat()
                val w2 = b.rect.width().toFloat()
                val h2 = b.rect.height().toFloat()
                if (w1 > h1 * ratio && h2 > w2 * ratio) return false
                if (w2 > h2 * ratio && h1 > w1 * ratio) return false
                // 边对齐检查
                return if (a.isVertical) {  // v
                    abs(a.rect.top - b.rect.top) < charSize * charGapTolerance2 ||
                    abs(a.rect.bottom - b.rect.bottom) < charSize * charGapTolerance2
                } else {  // h
                    abs(a.rect.left - b.rect.left) < charSize * charGapTolerance2 ||
                    abs(a.rect.right - b.rect.right) < charSize * charGapTolerance2
                }
            }
            return false
        }

        // 7. 非 axis-aligned：检查 angle 差异
        val angleDiffThreshold = 15 * PI / 180f
        if (abs(a.angle - b.angle) > angleDiffThreshold) return false

        // 8. font_size 差异检查
        if (abs(fontSizeA - fontSizeB) / charSize > 0.25f) return false

        return gap < charSize * charGapTolerance2
    }

    /**
     * 判断是否近似轴对齐
     */
    private fun isApproximateAxisAligned(detected: DetectedRectWithFont): Boolean {
        return if (detected.isVertical) {
            detected.aspectRatio > 1.5f  // 竖排：宽高比大
        } else {
            detected.aspectRatio < 0.67f  // 横排：宽高比小
        }
    }

    /**
     * 计算两个 AABB 之间的最小间隙
     */
    private fun calculateGap(a: Rect, b: Rect): Float {
        return when {
            a.right <= b.left -> b.left - a.right.toFloat()
            b.right <= a.left -> a.left - b.right.toFloat()
            a.bottom <= b.top -> b.top - a.bottom.toFloat()
            b.bottom <= a.top -> a.top - b.bottom.toFloat()
            else -> 0f  // 重叠
        }
    }

    /**
     * 过滤器模式合并：对齐 manga-image-translator
     *
     * @param rects DetectedRectWithFont 列表（包含真实 font_size）
     */
    private fun mergeRectsByRowThenCol(rects: List<DetectedRectWithFont>): List<Rect> {
        if (rects.isEmpty()) return emptyList()
        if (rects.size == 1) return listOf(rects[0].rect)

        // 按 Y 排序（从上到下）
        val sortedRects = rects.sortedBy { it.rect.top }
        val result = mutableListOf<MergedBox>()

        for (current in sortedRects) {
            var mergedRect: Rect = current.rect
            var merged = false

            // 检查是否能和之前已合并的结果合并
            for (i in result.indices) {
                val existing = result[i]
                if (canMergeWithDynamicThreshold(current, DetectedRectWithFont(existing.rect, existing.isVertical, existing.fontSize, existing.angle, existing.aspectRatio))) {
                    // 合并到 existing
                    mergedRect = Rect(
                        minOf(mergedRect.left, existing.rect.left),
                        minOf(mergedRect.top, existing.rect.top),
                        maxOf(mergedRect.right, existing.rect.right),
                        maxOf(mergedRect.bottom, existing.rect.bottom)
                    )
                    result[i] = MergedBox(mergedRect, existing.isVertical, existing.fontSize, existing.angle, existing.aspectRatio)
                    merged = true
                    break
                }
            }

            if (!merged) {
                result.add(MergedBox(mergedRect, current.isVertical, current.fontSize, current.angle, current.aspectRatio))
            }
        }

        return result.map { it.rect }
    }

    /**
     * 计算 QuadBox 列表的 union AABB（包含所有 QuadBox 的最小矩形）
     */
    private fun computeUnionAABB(group: List<QuadBox>): Rect {
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
     * 按阅读顺序对合并组排序。
     * 竖排为主：从右到左，从上到下
     * 横排为主：从左到右，从上到下
     */
    private fun sortByReadingOrder(groups: List<List<QuadBox>>): List<List<QuadBox>> {
        // 判断整体方向
        val isVertical = groups.count { g -> g.first().let { qb ->
            qb.aabb.height() > qb.aabb.width()
        }} > groups.size / 2

        return if (isVertical) {
            groups.sortedWith(compareBy({ -it.first().centroidX }, { it.first().centroidY }))
        } else {
            groups.sortedWith(compareBy({ it.first().centroidY }, { it.first().centroidX }))
        }
    }
}
