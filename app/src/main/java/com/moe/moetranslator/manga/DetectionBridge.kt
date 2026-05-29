package com.moe.moetranslator.manga

import android.graphics.Bitmap
import android.graphics.Rect
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.utils.LogCollector
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * 支持：
 * - ML Kit: 检测 + 识别一体化
 * - CTD: ComicTextDetector 文字行级检测
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
     * CTD + 混合 OCR 路径（manga-ocr + CTC 并行）。
     *
     * 流程：
     * 1. CTD 检测文字区域 → List<QuadBox>
     * 2. BoxMerger 合并 → List<List<QuadBox>>
     * 3. 分流（并行执行）：
     *    - size > 1（合并组）：cropBitmap(+10px) → resize 224x224 → manga-ocr.recognizeBatch
     *    - size == 1（单个框）：cropBitmap(+10px) → prepareCtcInputs → CTC.recognizeBatch
     *
     * @param bitmap 输入图片
     * @param language 未使用（保留兼容性）
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

            // Step 3: 分流
            val mangaGroups = mutableListOf<Pair<Int, List<QuadBox>>>()
            val ctcSingles = mutableListOf<Pair<Int, QuadBox>>()
            for ((idx, group) in groups.withIndex()) {
                if (group.size > 1) {
                    mangaGroups.add(idx to group)
                } else {
                    ctcSingles.add(idx to group.first())
                }
            }
            val globalIsVertical = rawQuadBoxes.count { it.aspectRatio > 1f } > rawQuadBoxes.size / 2

            LogCollector.d(TAG, "detectWithCTDHybrid: manga-ocr 组=${mangaGroups.size}, CTC 单框=${ctcSingles.size}, globalIsVertical=$globalIsVertical")

            // 并行执行
            return coroutineScope {
                val mangaDeferred = async {
                    val mangaResults = mutableListOf<Pair<Int, TextBlockInfo>>()
                    if (mangaGroups.isNotEmpty()) {
                        // 对齐 CTD+MangaOcr 标准流程
                        val expandedRects = mangaGroups.map { (_, group) ->
                            val unionRect = computeUnionAABB(group)
                            Rect(
                                (unionRect.left - 10).coerceAtLeast(0),
                                (unionRect.top - 10).coerceAtLeast(0),
                                (unionRect.right + 10).coerceAtMost(bitmap.width),
                                (unionRect.bottom + 10).coerceAtMost(bitmap.height)
                            )
                        }
                        val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }
                        val resizedBitmaps = croppedBitmaps.map { crop ->
                            val r = Bitmap.createScaledBitmap(crop, 224, 224, true)
                            crop.recycle(); r
                        }
                        val texts = MangaOcrBridge.recognizeBatch(resizedBitmaps)
                        resizedBitmaps.forEach { it.recycle() }
                        for (i in mangaGroups.indices) {
                            val text = texts[i].trim()
                            if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                                mangaResults.add(mangaGroups[i].first to TextBlockInfo(
                                    text = text,
                                    boundingBox = expandedRects[i],
                                    cornerPoints = null,
                                    isVertical = globalIsVertical
                                ))
                                LogCollector.d(TAG, "detectWithCTDHybrid: manga-ocr 结果[$i]: '$text'")
                            } else if (isDotOnlyPattern(text)) {
                                LogCollector.d(TAG, "detectWithCTDHybrid: manga-ocr 过滤纯符号[$i]: '$text'")
                            }
                        }
                    }
                    mangaResults
                }
                val ctcDeferred = async {
                    val ctcResults = mutableListOf<Pair<Int, TextBlockInfo>>()
                    if (ctcSingles.isNotEmpty()) {
                        // 对齐 CTD+CTC 标准流程
                        val singleGroups = ctcSingles.map { it.second }
                        val expandedRects = singleGroups.map { qb ->
                            val aabb = qb.aabb
                            Rect(
                                aabb.left.coerceAtLeast(0),
                                aabb.top.coerceAtLeast(0),
                                aabb.right.coerceAtMost(bitmap.width),
                                aabb.bottom.coerceAtMost(bitmap.height)
                            )
                        }
                        val croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }
                        val mergedGroupsForCtc = singleGroups.map { listOf(it) }
                        val ctcInputs = prepareCtcInputs(croppedBitmaps, mergedGroupsForCtc, expandedRects, globalIsVertical)
                        croppedBitmaps.forEach { if (it !== bitmap) it.recycle() }
                        val texts = CtcOcrRecognizer.recognizeBatch(ctcInputs)
                        ctcInputs.forEach { it.recycle() }
                        for (i in ctcSingles.indices) {
                            val text = texts[i].trim()
                            val isVertical = singleGroups[i].assignedDirection == "v"
                            if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                                ctcResults.add(ctcSingles[i].first to TextBlockInfo(
                                    text = text,
                                    boundingBox = expandedRects[i],
                                    cornerPoints = null,
                                    isVertical = isVertical
                                ))
                                LogCollector.d(TAG, "detectWithCTDHybrid: CTC 结果[$i]: '$text', isVertical=$isVertical")
                            } else if (isDotOnlyPattern(text)) {
                                LogCollector.d(TAG, "detectWithCTDHybrid: CTC 过滤纯符号[$i]: '$text'")
                            }
                        }
                    }
                    ctcResults
                }

                val allResults = (mangaDeferred.await() + ctcDeferred.await())
                    .sortedBy { it.first }
                    .map { it.second }
                LogCollector.d(TAG, "detectWithCTDHybrid: 完成，共 ${allResults.size} 个结果")
                allResults
            }

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

            // CTCOcr 跳过合并：CTC 模型逐行识别，合并会导致多行混在一起
            val mergedGroups: List<List<QuadBox>>
            val expandedRects: List<Rect>
            val PADDING = 10

            if (ocrEngine == CTDOCREngine.CTCOcr) {
                // 不合并，每个 QuadBox 独立识别
                // CTC 不需要额外 padding：unclip 已经扩展了足够的文字区域
                // cropBitmap 内部还有 10px padding，合计已经足够
                mergedGroups = quadBoxes.map { listOf(it) }
                expandedRects = quadBoxes.map { box ->
                    val aabb = box.aabb
                    Rect(
                        aabb.left.coerceAtLeast(0),
                        aabb.top.coerceAtLeast(0),
                        aabb.right.coerceAtMost(bitmap.width),
                        aabb.bottom.coerceAtMost(bitmap.height)
                    )
                }
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) 跳过合并+跳过扩展padding，直接使用 ${quadBoxes.size} 个检测框")
            } else {
                val groups = BoxMerger.merge(quadBoxes)
                mergedGroups = groups
                expandedRects = groups.map { group ->
                    val unionRect = computeUnionAABB(group)
                    Rect(
                        (unionRect.left - PADDING).coerceAtLeast(0),
                        (unionRect.top - PADDING).coerceAtLeast(0),
                        (unionRect.right + PADDING).coerceAtMost(bitmap.width),
                        (unionRect.bottom + PADDING).coerceAtMost(bitmap.height)
                    )
                }
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) MST合并: ${quadBoxes.size} → ${mergedGroups.size} 个区域")
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
                    val texts = MangaOcrBridge.recognizeBatch(croppedBitmaps)
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
                    // CTC 需要特殊预处理：对齐参考项目 get_transformed_region
                    // 竖排文字需要 90° 旋转让宽度变为主轴(48px)
                    // 横排文字 height 缩放到 48px
                    val ctcInputs = prepareCtcInputs(croppedBitmaps, mergedGroups, expandedRects, globalIsVertical)
                    val texts = CtcOcrRecognizer.recognizeBatch(ctcInputs)
                    for (i in expandedRects.indices) {
                        val text = texts[i].trim()
                        // 使用每个框自己的方向，而非全局方向
                        val isVertical = mergedGroups[i].firstOrNull()?.assignedDirection == "v"
                        if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                            val rect = expandedRects[i]
                            results.add(TextBlockInfo(
                                text = text,
                                boundingBox = rect,
                                cornerPoints = null,
                                isVertical = isVertical
                            ))
                            LogCollector.d(TAG, "CTD(CTCOcr) 识别结果[$i]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}], text='$text', isVertical=$isVertical")
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
     * 为 CTC 准备输入图片：对齐参考项目 get_transformed_region 的透视校正流程。
     *
     * 对齐 generic.py get_transformed_region 的逻辑：
     * 1. 对 QuadBox 做 AABB 裁剪（不加额外 padding）
     * 2. 用 DLT 算法计算从 QuadBox 角点到目标矩形的单应矩阵
     * 3. 透视变换校正文字区域
     * 4. 竖排：w=48, h=48*ratio → rotate CCW 90°
     * 5. 横排：h=48, w=48/ratio
     */
    internal fun prepareCtcInputs(
        croppedBitmaps: List<Bitmap>,
        mergedGroups: List<List<QuadBox>>,
        expandedRects: List<Rect>,
        globalIsVertical: Boolean
    ): List<Bitmap> {
        val result = mutableListOf<Bitmap>()
        for (i in croppedBitmaps.indices) {
            val crop = croppedBitmaps[i]
            val group = mergedGroups.getOrElse(i) { emptyList() }

            // 判断该组的方向：优先用 QuadBox.assignedDirection
            val isVertical = group.firstOrNull()?.let {
                it.assignedDirection == "v"
            } ?: globalIsVertical

            // 参考项目 ratio = ||v_vec|| / ||h_vec||（结构线比例）
            val ratio = group.firstOrNull()?.structRatio
                ?: (crop.height.toFloat() / crop.width.toFloat().coerceAtLeast(1f))

            val textHeight = 48f
            val cropW = crop.width.toFloat().coerceAtLeast(1f)
            val cropH = crop.height.toFloat().coerceAtLeast(1f)

            val targetW: Int
            val targetH: Int
            if (isVertical) {
                targetW = textHeight.toInt()
                targetH = (textHeight * ratio).toInt().coerceAtLeast(1)
            } else {
                targetH = textHeight.toInt()
                targetW = (textHeight / ratio).toInt().coerceAtLeast(1)
            }

            // 简单缩放（createBitmap + setScale 保证输出尺寸 = target）
            val matrix = android.graphics.Matrix()
            val scaleX = targetW.toFloat() / cropW
            val scaleY = targetH.toFloat() / cropH
            matrix.setScale(scaleX, scaleY)

            if (isVertical) {
                matrix.postRotate(-90f)
            }

            val transformed = Bitmap.createBitmap(
                crop, 0, 0, crop.width, crop.height,
                matrix, true
            )
            LogCollector.d(TAG, "CTC预处理[$i]: isVertical=$isVertical, crop=${crop.width}x${crop.height}, ratio=${String.format("%.3f", ratio)}, target=${targetW}x${targetH}, result=${transformed.width}x${transformed.height}")
            result.add(transformed)
        }
        return result
    }

    /**
     * DLT 算法计算单应矩阵（findHomography）。
     * 求解 4 组点对应的 8 参数透视变换 H（3x3 矩阵，H33=1）。
     * 对齐 cv2.findHomography(src_pts, dst_pts)。
     *
     * @return 9 元素数组表示行优先的 3x3 矩阵，或 null（退化情况）
     */
    private fun computeHomography(
        src: Array<android.graphics.PointF>,
        dst: Array<android.graphics.PointF>
    ): DoubleArray? {
        // 构建 8x8 线性系统 Ah = b
        // 对应方程：
        //   x' = (h1*x + h2*y + h3) / (h7*x + h8*y + 1)
        //   y' = (h4*x + h5*y + h6) / (h7*x + h8*y + 1)
        val A = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)

        for (i in 0 until 4) {
            val x = src[i].x.toDouble()
            val y = src[i].y.toDouble()
            val xp = dst[i].x.toDouble()
            val yp = dst[i].y.toDouble()

            // x' 方程: x'*h7*x + x'*h8*y - h1*x - h2*y = -h3 + x'  →  h3 系数 = 1
            A[i][0] = -x;     A[i][1] = -y;     A[i][2] = 1.0
            A[i][3] = 0.0;    A[i][4] = 0.0;    A[i][5] = 0.0
            A[i][6] = xp * x; A[i][7] = xp * y
            b[i] = xp

            // y' 方程
            A[i + 4][0] = 0.0;    A[i + 4][1] = 0.0;    A[i + 4][2] = 0.0
            A[i + 4][3] = -x;     A[i + 4][4] = -y;     A[i + 4][5] = 1.0
            A[i + 4][6] = yp * x; A[i + 4][7] = yp * y
            b[i + 4] = yp
        }

        val h = solveLinearSystem(A, b) ?: return null
        return doubleArrayOf(
            h[0], h[1], h[2],
            h[3], h[4], h[5],
            h[6], h[7], 1.0
        )
    }

    /**
     * 高斯消元法求解线性方程组 Ax = b（部分主元选取）。
     */
    private fun solveLinearSystem(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        // 构建增广矩阵 [A|b]
        val aug = Array(n) { i ->
            DoubleArray(n + 1) { j -> if (j < n) A[i][j] else b[i] }
        }

        // 前向消元 + 部分主元
        for (col in 0 until n) {
            var maxRow = col
            var maxVal = kotlin.math.abs(aug[col][col])
            for (row in col + 1 until n) {
                val v = kotlin.math.abs(aug[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-10) return null
            if (maxRow != col) {
                val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp
            }
            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (j in col..n) aug[row][j] -= factor * aug[col][j]
            }
        }

        // 回代
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            x[i] = aug[i][n]
            for (j in i + 1 until n) x[i] -= aug[i][j] * x[j]
            x[i] /= aug[i][i]
        }
        return x
    }

    /**
     * 透视变换（warpPerspective）。
     * 对齐 cv2.warpPerspective(src, H, (w, h))。
     * 使用逆映射 + 双线性插值，对源图做单次 getPixels 批量读取。
     *
     * @param src 源图片
     * @param H 9 元素行优先 3x3 单应矩阵（dst → src 映射）
     * @param outW 输出宽度
     * @param outH 输出高度
     * @return 透视校正后的图片
     */
    private fun warpPerspective(src: Bitmap, H: DoubleArray, outW: Int, outH: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

        // 批量读取源像素（OOB 区域视为白色 0xFFFFFFFF）
        val srcPixels = IntArray(srcW * srcH)
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(outW * outH)

        // 计算 H 的逆矩阵（dst→src → src→dst 用于回代验证，实际用 dst→src 做逆映射）
        val inv = invertMatrix3x3(H) ?: run {
            // 逆矩阵不存在（退化），返回白色图
            java.util.Arrays.fill(dstPixels, -1)
            dst.setPixels(dstPixels, 0, outW, 0, 0, outW, outH)
            return dst
        }

        for (dy in 0 until outH) {
            for (dx in 0 until outW) {
                // 逆映射：dst (dx,dy) → src (sx,sy)
                val w = inv[6] * dx + inv[7] * dy + inv[8]
                if (w < 1e-8 && w > -1e-8) { dstPixels[dy * outW + dx] = -1; continue }
                val sx = (inv[0] * dx + inv[1] * dy + inv[2]) / w
                val sy = (inv[3] * dx + inv[4] * dy + inv[5]) / w

                // 双线性插值
                val x0 = sx.toInt() - if (sx < 0) 1 else 0
                val y0 = sy.toInt() - if (sy < 0) 1 else 0
                val x1 = x0 + 1
                val y1 = y0 + 1
                val fx = (sx - x0).toFloat()
                val fy = (sy - y0).toFloat()

                // getPixels 数组索引（OOB → -1 → 映射到白色）
                val i00 = if (x0 in 0 until srcW && y0 in 0 until srcH) y0 * srcW + x0 else -1
                val i10 = if (x1 in 0 until srcW && y0 in 0 until srcH) y0 * srcW + x1 else -1
                val i01 = if (x0 in 0 until srcW && y1 in 0 until srcH) y1 * srcW + x0 else -1
                val i11 = if (x1 in 0 until srcW && y1 in 0 until srcH) y1 * srcW + x1 else -1

                val c00 = if (i00 >= 0) srcPixels[i00] else -1
                val c10 = if (i10 >= 0) srcPixels[i10] else -1
                val c01 = if (i01 >= 0) srcPixels[i01] else -1
                val c11 = if (i11 >= 0) srcPixels[i11] else -1

                // 每通道双线性插值
                val r = bilinearChannel(c00 ushr 16 and 0xFF, c10 ushr 16 and 0xFF,
                    c01 ushr 16 and 0xFF, c11 ushr 16 and 0xFF, fx, fy)
                val g = bilinearChannel(c00 ushr 8 and 0xFF, c10 ushr 8 and 0xFF,
                    c01 ushr 8 and 0xFF, c11 ushr 8 and 0xFF, fx, fy)
                val b = bilinearChannel(c00 and 0xFF, c10 and 0xFF,
                    c01 and 0xFF, c11 and 0xFF, fx, fy)

                dstPixels[dy * outW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        dst.setPixels(dstPixels, 0, outW, 0, 0, outW, outH)
        return dst
    }

    private fun bilinearChannel(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
        val v = c00 * (1 - fx) * (1 - fy) + c10 * fx * (1 - fy) +
                c01 * (1 - fx) * fy + c11 * fx * fy
        return v.toInt().coerceIn(0, 255)
    }

    /**
     * 3x3 矩阵求逆（行优先 9 元素数组）。
     */
    private fun invertMatrix3x3(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
                  m[1] * (m[3] * m[8] - m[5] * m[6]) +
                  m[2] * (m[3] * m[7] - m[4] * m[6])
        if (kotlin.math.abs(det) < 1e-12) return null
        val invDet = 1.0 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * invDet,
            (m[2] * m[7] - m[1] * m[8]) * invDet,
            (m[1] * m[5] - m[2] * m[4]) * invDet,
            (m[5] * m[6] - m[3] * m[8]) * invDet,
            (m[0] * m[8] - m[2] * m[6]) * invDet,
            (m[2] * m[3] - m[0] * m[5]) * invDet,
            (m[3] * m[7] - m[4] * m[6]) * invDet,
            (m[1] * m[6] - m[0] * m[7]) * invDet,
            (m[0] * m[4] - m[1] * m[3]) * invDet
        )
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
        if (text.isBlank()) return true
        // 统计原始文本中点号类字符的占比
        val dotChars = text.count { it == '.' || it == '·' || it == '…' }
        // 点号占比超过 80% 才认为是纯符号
        return dotChars > 0 && dotChars >= text.length * 0.8
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
