package com.moe.moetranslator.manga

import android.graphics.Bitmap
import android.graphics.Rect
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.utils.LogCollector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CTD 调试模式结果
 */
data class CTDDebugResult(
    val rawBoxes: List<QuadBox>,           // CTD 检测的原始未合并 boxes（通过过滤）
    val mergedGroups: List<List<QuadBox>>,  // BoxMerger 合并后的组
    val discardedBoxes: List<QuadBox>       // CTD 检测中被过滤丢弃的 boxes
)

private const val DEBUG_TAG = "DetectionBridge"

/**
 * CTD 调试模式：只检测，不翻译，显示未合并/合并/丢弃选框位置。
 * 用于验证 CTD 检测和合并逻辑是否正确。
 *
 * @param bitmap 输入图片
 * @return CTDDebugResult 包含原始 boxes、合并组和丢弃的 boxes
 */
suspend fun detectWithCTDDebug(bitmap: Bitmap): CTDDebugResult {
    // Step 1: CTD 检测（获取包含丢弃框的完整结果）
    val detectResult = CTDDetector.detectQuadBoxesWithDiscarded(bitmap)
    val rawQuadBoxes = detectResult.quadBoxes
    val discardedBoxes = detectResult.discardedBoxes
    LogCollector.d(DEBUG_TAG, "detectWithCTDDebug: CTD 检测到 ${rawQuadBoxes.size} 个文字区域, ${discardedBoxes.size} 个被丢弃")

    // 记录丢弃的框（D=Discarded）
    for ((idx, box) in discardedBoxes.withIndex()) {
        LogCollector.d(DEBUG_TAG, "D[$idx]: ${box.aabb}, fontSize=${String.format("%.1f", box.fontSize)}")
    }

    // 记录原始框（R=Raw）
    for ((idx, box) in rawQuadBoxes.withIndex()) {
        LogCollector.d(DEBUG_TAG, "R[$idx]: ${box.aabb}, fontSize=${String.format("%.1f", box.fontSize)}")
    }

    // Step 2: BoxMerger 合并
    val mergedGroups = BoxMerger.merge(rawQuadBoxes)
    LogCollector.d(DEBUG_TAG, "detectWithCTDDebug: BoxMerger 合并: ${rawQuadBoxes.size} → ${mergedGroups.size} 个组")

    // 记录合并组（M=Merged）
    for ((idx, group) in mergedGroups.withIndex()) {
        val rawIndices = group.mapNotNull { qb -> rawQuadBoxes.indexOf(qb).takeIf { it >= 0 } }
        LogCollector.d(DEBUG_TAG, "M[$idx]:${group.size}boxes rects=${group.map { it.aabb }} rawIndices=$rawIndices")
    }

    return CTDDebugResult(rawQuadBoxes, mergedGroups, discardedBoxes)
}

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
            val rawQuadBoxes = CTDDetector.detectQuadBoxes(bitmap)
            if (rawQuadBoxes.isEmpty()) {
                LogCollector.d(TAG, "detectWithCTDHybrid: CTD 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "detectWithCTDHybrid: CTD 检测到 ${rawQuadBoxes.size} 个文字区域")

            // Step 2: BoxMerger 合并
            val groups = BoxMerger.merge(rawQuadBoxes)
            LogCollector.d(TAG, "detectWithCTDHybrid: BoxMerger 合并: ${rawQuadBoxes.size} → ${groups.size} 个组")

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
                        LogCollector.d(TAG, "detectWithCTDHybrid: manga-ocr crop=${unionRect.width()}x${unionRect.height()} at $unionRect")
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
                        LogCollector.d(TAG, "detectWithCTDHybrid: ML Kit crop=${paddedRect.width()}x${paddedRect.height()} at $paddedRect")
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

            // 使用 detectQuadBoxes 获取 QuadBox 列表
            val quadBoxes = CTDDetector.detectQuadBoxes(bitmap)
            if (quadBoxes.isEmpty()) {
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "CTD(${ocrEngine.name}) 检测到 ${quadBoxes.size} 个文字区域")

            // 使用新的 MST 合并流程
            val mergedGroups = mergeQuadBoxesTextRegion(quadBoxes)
            LogCollector.d(TAG, "CTD(${ocrEngine.name}) MST合并: ${quadBoxes.size} → ${mergedGroups.size} 个区域")

            // 对每个合并组计算 union AABB 并扩展
            val PADDING = 10
            val expandedRects = mergedGroups.map { group ->
                val groupBoxes = group.map { quadBoxes[it] }
                val unionRect = computeUnionAABB(groupBoxes)
                Rect(
                    (unionRect.left - PADDING).coerceAtLeast(0),
                    (unionRect.top - PADDING).coerceAtLeast(0),
                    (unionRect.right + PADDING).coerceAtMost(bitmap.width),
                    (unionRect.bottom + PADDING).coerceAtMost(bitmap.height)
                )
            }

            for ((idx, rect) in expandedRects.withIndex()) {
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) [$idx]: 最终区域[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}]")
            }

            // 裁剪图片
            val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }

            // OCR 识别
            val globalIsVertical = quadBoxes.count { it.aspectRatio > 1f } > quadBoxes.size / 2
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

    // ==================== MST Split & Merge (对齐官方 textline_merge) ====================

    /**
     * Kruskal 最小生成树边
     */
    private data class MSTEdge(val u: Int, val v: Int, val weight: Float)

    /**
     * 并查集（Union-Find）
     */
    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }
        private val rank = IntArray(n) { 0 }

        fun find(x: Int): Int {
            if (parent[x] != x) parent[x] = find(parent[x])
            return parent[x]
        }

        fun union(x: Int, y: Int): Boolean {
            val px = find(x); val py = find(y)
            if (px == py) return false
            when {
                rank[px] < rank[py] -> parent[px] = py
                rank[px] > rank[py] -> parent[py] = px
                else -> { parent[py] = px; rank[px]++ }
            }
            return true
        }
    }

    /**
     * MST 拆分：对齐官方 split_text_region
     *
     * @param quadBoxes 所有 QuadBox 列表
     * @param connectedIndices 连通分量中的节点索引集合
     * @param gamma 控制拆分阈值（官方默认 0.5）
     * @param sigma 控制标准差阈值（官方默认 2）
     * @return 拆分后的节点索引列表，每个元素是一组索引
     */
    private fun splitTextRegion(
        quadBoxes: List<QuadBox>,
        connectedIndices: Set<Int>,
        gamma: Float = 0.5f,
        sigma: Float = 2f
    ): List<Set<Int>> {
        val indices = connectedIndices.toList()

        // case 1: 只有一个节点
        if (indices.size == 1) {
            return listOf(setOf(indices[0]))
        }

        // case 2: 两个节点
        if (indices.size == 2) {
            val i1 = indices[0]; val i2 = indices[1]
            val fs = maxOf(quadBoxes[i1].fontSize, quadBoxes[i2].fontSize)
            val dist = quadBoxes[i1].polyDistance(quadBoxes[i2])
            val angleDiff = abs(quadBoxes[i1].angle - quadBoxes[i2].angle)
            return if (dist < (1 + gamma) * fs && angleDiff < 0.2 * PI.toFloat()) {
                listOf(setOf(i1, i2))
            } else {
                listOf(setOf(i1), setOf(i2))
            }
        }

        // case 3: 多个节点，构建 MST
        // 构建所有边
        val edges = mutableListOf<MSTEdge>()
        for (i in indices.indices) {
            for (j in i + 1 until indices.size) {
                val u = indices[i]; val v = indices[j]
                val w = quadBoxes[u].polyDistance(quadBoxes[v])
                edges.add(MSTEdge(u, v, w))
            }
        }

        // Kruskal MST
        val sortedEdges = edges.sortedByDescending { it.weight }
        val uf = UnionFind(quadBoxes.size)
        for (edge in sortedEdges) {
            uf.union(edge.u, edge.v)
        }

        // 获取按权重降序排列的边
        val mstEdges = sortedEdges.filter { uf.find(it.u) == uf.find(it.v) }.sortedByDescending { it.weight }

        // 计算统计量
        val weightList = edges.map { it.weight }
        val fontsize = edges.map { edge -> quadBoxes[edge.u].fontSize }.average().toFloat()
        val distancesMean = weightList.average().toFloat()
        val distancesStd = if (weightList.size > 1) {
            sqrt(weightList.map { (it - distancesMean) * (it - distancesMean) }.average().toDouble()).toFloat()
        } else 0f
        val stdThreshold = maxOf(0.3f * fontsize + 5, 5f)

        val maxEdge = mstEdges.firstOrNull() ?: return listOf(connectedIndices)
        val b1 = quadBoxes[maxEdge.u]; val b2 = quadBoxes[maxEdge.v]
        val maxPolyDistance = b1.polyDistance(b2)
        val maxCentroidAlignment = minOf(
            abs(b1.centroidX - b2.centroidX),
            abs(b1.centroidY - b2.centroidY)
        )

        // 判断是否需要拆分
        val shouldSplit = !(maxEdge.weight <= distancesMean + distancesStd * sigma ||
                maxEdge.weight <= fontsize * (1 + gamma)) &&
                (distancesStd >= stdThreshold && maxPolyDistance > 0 && maxCentroidAlignment >= 5)

        return if (shouldSplit) {
            // 去掉最大边，递归处理剩余连通分量
            val uf2 = UnionFind(quadBoxes.size)
            for (edge in mstEdges.drop(1)) {
                uf2.union(edge.u, edge.v)
            }
            val result = mutableListOf<Set<Int>>()
            val visited = mutableSetOf<Int>()
            for (idx in indices) {
                if (idx in visited) continue
                val component = mutableSetOf<Int>()
                val stack = ArrayDeque<Int>()
                stack.add(idx)
                while (stack.isNotEmpty()) {
                    val current = stack.removeLast()
                    if (current in visited) continue
                    visited.add(current)
                    component.add(current)
                    for (otherIdx in indices) {
                        if (otherIdx !in visited && uf2.find(current) == uf2.find(otherIdx)) {
                            stack.add(otherIdx)
                        }
                    }
                }
                if (component.isNotEmpty()) {
                    result.addAll(splitTextRegion(quadBoxes, component, gamma, sigma))
                }
            }
            result
        } else {
            listOf(connectedIndices)
        }
    }

    /**
     * 粗筛函数：对齐官方 quadrilateral_can_merge_region_coarse
     * 只检查方向是否一致 + angle差 + fontSize比例，不做详细几何判断
     */
    private fun quadrilateralCanMergeCoarse(
        a: QuadBox, b: QuadBox,
        discardConnectionGap: Float = 2f,
        fontSizeRatioTol: Float = 0.7f
    ): Boolean {
        // 方向必须一致
        if (a.assignedDirection != b.assignedDirection) return false

        // 角度差 < 15°
        val angleDiffThreshold = 15 * PI / 180f
        if (abs(a.angle - b.angle) > angleDiffThreshold) return false

        // fontSize 比例
        val fs = minOf(a.fontSize, b.fontSize)
        if (abs(a.fontSize - b.fontSize) / fs > fontSizeRatioTol) return false

        // polygon 距离
        val dist = a.polyDistance(b)
        if (dist > discardConnectionGap * maxOf(a.fontSize, b.fontSize)) return false

        return true
    }

    /**
     * 构建 QuadBox 连通图，返回可合并的节点对
     * 先粗筛，再精筛
     *
     * @param quadBoxes 所有 QuadBox 列表
     * @param aspectRatioTol 宽高比容忍度（默认 1.0，对齐官方 _generate_text_direction）
     * @param fontSizeRatioTol 字号比例容忍度（默认 2.0）
     * @param charGapTolerance 字符间隙容忍度（默认 1.0）
     * @param charGapTolerance2 字符间隙容忍度2（默认 3.0）
     * @return 可合并的边列表（节点索引对）
     */
    private fun buildMergeGraph(
        quadBoxes: List<QuadBox>,
        aspectRatioTol: Float = 1.0f,
        fontSizeRatioTol: Float = 2.0f,
        charGapTolerance: Float = 1.0f,
        charGapTolerance2: Float = 3.0f
    ): List<Pair<Int, Int>> {
        val edges = mutableListOf<Pair<Int, Int>>()
        for (i in quadBoxes.indices) {
            for (j in i + 1 until quadBoxes.size) {
                if (!quadrilateralCanMergeCoarse(quadBoxes[i], quadBoxes[j])) continue
                if (quadrilateralCanMerge(quadBoxes[i], quadBoxes[j], aspectRatioTol, fontSizeRatioTol, charGapTolerance, charGapTolerance2)) {
                    edges.add(i to j)
                }
            }
        }
        return edges
    }

    /**
     * 判断两个 QuadBox 是否可以合并：对齐官方 quadrilateral_can_merge_region
     */
    private fun quadrilateralCanMerge(
        a: QuadBox, b: QuadBox,
        aspectRatioTol: Float,
        fontSizeRatioTol: Float,
        charGapTolerance: Float,
        charGapTolerance2: Float
    ): Boolean {
        // 方向必须一致
        if (a.aspectRatio > b.aspectRatio) {
            if (a.aspectRatio > aspectRatioTol && b.aspectRatio < 1f / aspectRatioTol) return false
        } else {
            if (b.aspectRatio > aspectRatioTol && a.aspectRatio < 1f / aspectRatioTol) return false
        }

        // 字号比例
        val charSize = minOf(a.fontSize, b.fontSize)
        if (maxOf(a.fontSize, b.fontSize) / charSize > fontSizeRatioTol) return false

        // polygon 距离
        val dist = a.polyDistance(b)
        val discardConnectionGap = 2f
        if (dist > discardConnectionGap * charSize) return false

        // axis-aligned 判断
        val aIsAA = a.isApproximateAxisAligned
        val bIsAA = b.isApproximateAxisAligned

        if (aIsAA && bIsAA) {
            val gap = calculateAABBGap(a.aabb, b.aabb)
            if (gap < charSize * charGapTolerance) {
                val centerAX = a.centroidX; val centerBX = b.centroidX
                val ratio = 1.9f
                if (abs(centerAX - centerBX) < charGapTolerance2) return true
                if (a.aspectRatio > ratio && b.aspectRatio > ratio) return false
                // 边对齐
                return if (a.aspectRatio > 1f) {
                    abs(a.aabb.top - b.aabb.top) < charSize * charGapTolerance2 ||
                    abs(a.aabb.bottom - b.aabb.bottom) < charSize * charGapTolerance2
                } else {
                    abs(a.aabb.left - b.aabb.left) < charSize * charGapTolerance2 ||
                    abs(a.aabb.right - b.aabb.right) < charSize * charGapTolerance2
                }
            }
            return false
        }

        // 非 axis-aligned：检查角度差
        val angleDiffThreshold = 15 * PI / 180f
        if (abs(a.angle - b.angle) > angleDiffThreshold) return false

        // 字号差异
        if (abs(a.fontSize - b.fontSize) / charSize > 0.25f) return false

        return dist < charSize * charGapTolerance2
    }

    /**
     * 计算两个 AABB 之间的最小间隙
     */
    private fun calculateAABBGap(a: Rect, b: Rect): Float {
        return when {
            a.right <= b.left -> b.left - a.right.toFloat()
            b.right <= a.left -> a.left - b.right.toFloat()
            a.bottom <= b.top -> b.top - a.bottom.toFloat()
            b.bottom <= a.top -> a.top - b.bottom.toFloat()
            else -> 0f
        }
    }

    /**
     * 对齐官方 _generate_text_direction
     * 返回：(QuadBox, direction) 对，按方向分组投票后排序
     */
    private fun generateTextDirection(quadBoxes: List<QuadBox>): List<Pair<QuadBox, String>> {
        if (quadBoxes.isEmpty()) return emptyList()

        // 构建粗筛连通图
        val edges = mutableListOf<Pair<Int, Int>>()
        for (i in quadBoxes.indices) {
            for (j in i + 1 until quadBoxes.size) {
                if (quadrilateralCanMergeCoarse(quadBoxes[i], quadBoxes[j])) {
                    edges.add(i to j)
                }
            }
        }

        // 构建邻接表
        val adj = mutableMapOf<Int, MutableSet<Int>>()
        for (i in quadBoxes.indices) adj[i] = mutableSetOf()
        for ((u, v) in edges) {
            adj[u]!!.add(v); adj[v]!!.add(u)
        }

        val visited = mutableSetOf<Int>()
        val result = mutableListOf<Pair<QuadBox, String>>()

        fun dfs(node: Int, component: MutableSet<Int>) {
            if (node in visited) return
            visited.add(node)
            component.add(node)
            for (neighbor in adj[node] ?: emptySet()) {
                dfs(neighbor, component)
            }
        }

        for (i in quadBoxes.indices) {
            if (i in visited) continue
            val component = mutableSetOf<Int>()
            dfs(i, component)

            if (component.isEmpty()) continue

            // 方向投票
            val dirs = component.map { quadBoxes[it].assignedDirection }
            val majorityDir = dirs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "h"

            // 排序：对齐官方
            val sortedIndices = if (majorityDir == "h") {
                component.sortedBy { quadBoxes[it].aabb.top + quadBoxes[it].aabb.height() / 2 }
            } else {
                component.sortedBy { -(quadBoxes[it].aabb.left + quadBoxes[it].aabb.width()) }
            }

            for (idx in sortedIndices) {
                result.add(quadBoxes[idx] to majorityDir)
            }
        }

        return result
    }

    /**
     * 对齐官方 merge_bboxes_text_region
     *
     * @param quadBoxes CTD 检测输出的 QuadBox 列表
     * @return 分组后的索引列表，每组是一组 QuadBox 索引
     */
    private fun mergeQuadBoxesTextRegion(quadBoxes: List<QuadBox>): List<Set<Int>> {
        if (quadBoxes.isEmpty()) return emptyList()
        if (quadBoxes.size == 1) return listOf(setOf(0))

        // step 1: 构建连通图（粗筛 + 精筛）
        val edges = buildMergeGraph(quadBoxes)
        if (edges.isEmpty()) return quadBoxes.indices.map { setOf(it) }

        // 构建邻接表
        val adj = mutableMapOf<Int, MutableSet<Int>>()
        for (i in quadBoxes.indices) adj[i] = mutableSetOf()
        for ((u, v) in edges) {
            adj[u]!!.add(v); adj[v]!!.add(u)
        }

        // step 2: 找连通分量，然后 MST 拆分
        val visited = mutableSetOf<Int>()
        val result = mutableListOf<Set<Int>>()

        fun dfs(node: Int, component: MutableSet<Int>) {
            if (node in visited) return
            visited.add(node)
            component.add(node)
            for (neighbor in adj[node] ?: emptySet()) {
                dfs(neighbor, component)
            }
        }

        for (i in quadBoxes.indices) {
            if (i in visited) continue
            val component = mutableSetOf<Int>()
            dfs(i, component)
            if (component.isNotEmpty()) {
                result.addAll(splitTextRegion(quadBoxes, component))
            }
        }

        return result
    }

    /**
     * 按阅读顺序对合并组排序。
     * 对齐官方排序逻辑：
     * - 水平文字：从上到下，按 y + h/2 排序
     * - 竖排文字：从右到左，按 -(x + w) 排序
     */
    private fun sortByReadingOrder(groups: List<List<QuadBox>>): List<List<QuadBox>> {
        // 判断整体方向（多数组是竖排）
        val isVertical = groups.count { g -> g.first().let { qb ->
            qb.assignedDirection == "v"
        }} > groups.size / 2

        return if (isVertical) {
            groups.sortedWith(compareBy({ -(it.first().aabb.left + it.first().aabb.width()) }, { it.first().aabb.top }))
        } else {
            groups.sortedWith(compareBy({ it.first().aabb.top + it.first().aabb.height() / 2 }, { it.first().aabb.left }))
        }
    }
}
