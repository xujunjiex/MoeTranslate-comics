package com.moe.moetranslator.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.moe.moetranslator.bridge.OCRBridge
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.utils.LogCollector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

/**
 * ML Kit 调试模式结果
 */
data class MLKitDebugResult(
    val textBlocks: List<MLKitDebugBlock>,  // 所有文字块
    val totalLines: Int,                     // 总行数
    val totalElements: Int,                  // 总元素数
    val detectedLanguage: String?            // 检测到的语言
)

data class MLKitDebugBlock(
    val blockText: String,                   // 块文字
    val blockRect: android.graphics.Rect?,   // 块边界框
    val blockCorners: Array<android.graphics.Point>?, // 块四角点
    val lines: List<MLKitDebugLine>,         // 行列表
    val language: String?                    // 识别语言
)

data class MLKitDebugLine(
    val lineText: String,                    // 行文字
    val lineRect: android.graphics.Rect?,    // 行边界框
    val lineCorners: Array<android.graphics.Point>?, // 行四角点
    val angle: Float,                        // 行倾斜角度
    val elements: List<MLKitDebugElement>    // 元素列表
)

data class MLKitDebugElement(
    val elementText: String,                 // 元素文字
    val elementRect: android.graphics.Rect?, // 元素边界框
    val elementCorners: Array<android.graphics.Point>? // 元素四角点
)

/**
 * RT-DETR-V2 调试模式结果
 */
data class RTDetrV2DebugResult(
    val allBubbles: List<ComicBubbleDetector.DetectedBubble>,  // 所有检测结果（含 classId=0）
    val textBubbles: List<ComicBubbleDetector.DetectedBubble>,  // classId=1
    val textFree: List<ComicBubbleDetector.DetectedBubble>,     // classId=2
    val emptyBubbles: List<ComicBubbleDetector.DetectedBubble>, // classId=0
    val finalRegions: List<Rect>                                // 最终提交给OCR的区域（压缩+去重后）
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
        PPOcrV5
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
        ocrEngine: CTDOCREngine,
        context: Context
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

            val PADDING = 10
            // PP-OCRv5 rec 是单行模型，跳过合并 + 使用透视校正裁剪；其他引擎需要合并 + AABB 裁剪
            val (groups, expandedRects) = if (ocrEngine == CTDOCREngine.PPOcrV5) {
                // 跳过合并：每个 QuadBox 独立，使用 QuadBox AABB 作为位置参考
                val rects = quadBoxes.map { qb ->
                    val aabb = qb.aabb
                    Rect(
                        (aabb.left - PADDING).coerceAtLeast(0),
                        (aabb.top - PADDING).coerceAtLeast(0),
                        (aabb.right + PADDING).coerceAtMost(bitmap.width),
                        (aabb.bottom + PADDING).coerceAtMost(bitmap.height)
                    )
                }
                Pair(quadBoxes.map { listOf(it) }, rects)
            } else {
                val merged = BoxMerger.merge(quadBoxes)
                val rects = merged.map { group ->
                    val unionRect = computeUnionAABB(group)
                    Rect(
                        (unionRect.left - PADDING).coerceAtLeast(0),
                        (unionRect.top - PADDING).coerceAtLeast(0),
                        (unionRect.right + PADDING).coerceAtMost(bitmap.width),
                        (unionRect.bottom + PADDING).coerceAtMost(bitmap.height)
                    )
                }
                Pair(merged, rects)
            }
            if (ocrEngine == CTDOCREngine.PPOcrV5) {
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) 跳过合并: ${quadBoxes.size} 个独立区域")
            } else {
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) MST合并: ${quadBoxes.size} → ${groups.size} 个区域")
            }

            for ((idx, rect) in expandedRects.withIndex()) {
                LogCollector.d(TAG, "CTD(${ocrEngine.name}) [$idx]: 最终区域[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}]")
            }

            // 裁剪图片：PPOcrV5 使用透视校正裁剪（利用 QuadBox 角点），其他使用 AABB 裁剪
            val croppedBitmaps: List<Bitmap>
            if (ocrEngine == CTDOCREngine.PPOcrV5) {
                // 透视校正裁剪：DLT 单应矩阵 + warpPerspective + 竖排自动旋转 90°
                croppedBitmaps = quadBoxes.map { qb ->
                    PPOcrV5Engine.getRotateCropImage(bitmap, qb.pts)
                }
            } else {
                croppedBitmaps = expandedRects.map { rect -> cropBitmap(bitmap, rect) }
            }

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
                CTDOCREngine.PPOcrV5 -> {
                    val recLang = PPOcrV5Engine.getRecLang(language)
                    if (recLang != null) {
                        val texts = PPOcrV5Engine.recognizeBatch(context, croppedBitmaps, recLang)
                        for (i in expandedRects.indices) {
                            val result = texts.getOrElse(i) { RecResult("", 0f) }
                            if (result.text.isNotBlank() && result.score >= 0.5f) {
                                val rect = expandedRects[i]
                                val isVertical = quadBoxes.getOrNull(i)?.isVertical ?: globalIsVertical
                                results.add(TextBlockInfo(
                                    text = result.text,
                                    boundingBox = rect,
                                    cornerPoints = null,
                                    isVertical = isVertical
                                ))
                                LogCollector.d(TAG, "CTD(PPOcrV5) 识别结果[$i]: rect=[${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}], text='${result.text}', score=${String.format("%.3f", result.score)}, isVertical=$isVertical")
                            } else {
                                LogCollector.d(TAG, "CTD(PPOcrV5) 未识别[$i]: text='${result.text}', score=${String.format("%.3f", result.score)}, crop=${croppedBitmaps[i].width}x${croppedBitmaps[i].height}")
                            }
                        }
                    } else {
                        LogCollector.w(TAG, "CTD(PPOcrV5) 不支持的语言: $language")
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
     * 计算两个矩形的 IoU (Intersection over Union)。
     */
    private fun calcIoU(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interArea = maxOf(0, interRight - interLeft) * maxOf(0, interBottom - interTop).toFloat()
        val unionArea = (a.right - a.left) * (a.bottom - a.top).toFloat() +
                (b.right - b.left) * (b.bottom - b.top).toFloat() - interArea
        return if (unionArea > 0) interArea / unionArea else 0f
    }

    /**
     * 计算 containment：inner 有多少比例被 outer 包含。
     * 返回值 1.0 表示 inner 完全在 outer 内部。
     */
    private fun calcContainment(outer: Rect, inner: Rect): Float {
        val interLeft = maxOf(outer.left, inner.left)
        val interTop = maxOf(outer.top, inner.top)
        val interRight = minOf(outer.right, inner.right)
        val interBottom = minOf(outer.bottom, inner.bottom)
        val interArea = maxOf(0, interRight - interLeft) * maxOf(0, interBottom - interTop).toFloat()
        val innerArea = (inner.right - inner.left) * (inner.bottom - inner.top).toFloat()
        return if (innerArea > 0) interArea / innerArea else 0f
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

    /**
     * RT-DETR-V2 气泡检测 + 指定 OCR 引擎识别。
     *
     * 流程：
     * 1. ComicBubbleDetector 检测气泡/文字区域 → List<DetectedBubble>
     * 2. 过滤 classId==0（无文字气泡），只保留 text_bubble(1) + text_free(2)
     * 3. 逐个裁剪区域 → OCR 引擎识别
     * 4. 返回 TextBlockInfo 列表
     *
     * @param bitmap 输入图片
     * @param language OCR 语言代码
     * @param ocrEngine OCR 引擎类型（复用 CTDOCREngine 枚举）
     * @return TextBlockInfo 列表
     */
    suspend fun detectWithRTDetrV2(
        bitmap: Bitmap,
        language: String,
        ocrEngine: CTDOCREngine,
        context: Context
    ): List<TextBlockInfo> {
        try {
            LogCollector.d(TAG, "使用 RT-DETR-V2 + ${ocrEngine.name} 检测文字区域...")

            // Step 1: RT-DETR-V2 检测（返回所有类别）
            val allBubbles = ComicBubbleDetector.detectBubbles(bitmap)
            if (allBubbles.isEmpty()) {
                LogCollector.d(TAG, "RT-DETR-V2 未检测到文字区域")
                return emptyList()
            }
            LogCollector.d(TAG, "RT-DETR-V2 原始检测到 ${allBubbles.size} 个区域")

            // Step 2: 分类处理
            // - text_bubble(绿色, classId=1): 直接保留
            // - text_free(蓝色, classId=2): 丢弃
            // - bubble(红色, classId=0): 压缩50%后保留（减轻OCR压力）
            val greenBubbles = allBubbles.filter { it.classId == 1 }
            val redBubbles = allBubbles.filter { it.classId == 0 }.map { bubble ->
                // 压缩15%，去除气泡外框多余的边距
                val cx = (bubble.rect.left + bubble.rect.right) / 2
                val cy = (bubble.rect.top + bubble.rect.bottom) / 2
                val halfW = (bubble.rect.right - bubble.rect.left) / 2 * 0.85f
                val halfH = (bubble.rect.bottom - bubble.rect.top) / 2 * 0.85f
                bubble.copy(rect = Rect(
                    (cx - halfW).toInt().coerceAtLeast(0),
                    (cy - halfH).toInt().coerceAtLeast(0),
                    (cx + halfW).toInt(),
                    (cy + halfH).toInt()
                ))
            }

            // Step 3: 去重 — 红色区域与绿色区域有重叠时，丢弃红色（优先用更紧凑的绿色）
            val dedupedBubbles = greenBubbles.toMutableList()
            for (red in redBubbles) {
                // 红色完全包裹绿色时丢弃红色（绿色面积90%以上在红色内部）
                val fullyContained = greenBubbles.any { green -> calcContainment(red.rect, green.rect) > 0.9f }
                if (!fullyContained) {
                    dedupedBubbles.add(red)
                }
            }

            // Step 4: 按 confidence 降序排序
            val sortedBubbles = dedupedBubbles.sortedByDescending { it.confidence }

            LogCollector.d(TAG, "RT-DETR-V2 过滤后 ${sortedBubbles.size} 个区域 (绿色=${greenBubbles.size}, 红色保留=${dedupedBubbles.size - greenBubbles.size})")

            // Step 5: 裁剪图片（10px padding）
            val croppedBitmaps = sortedBubbles.map { bubble -> cropBitmap(bitmap, bubble.rect) }

            // Step 4: OCR 识别
            val results = mutableListOf<TextBlockInfo>()
            when (ocrEngine) {
                CTDOCREngine.MLKit -> {
                    for (i in sortedBubbles.indices) {
                        try {
                            val text = OCRBridge.recognizeText(language, croppedBitmaps[i])
                            if (text.isNotBlank()) {
                                results.add(TextBlockInfo(
                                    text = text,
                                    boundingBox = sortedBubbles[i].rect,
                                    cornerPoints = null,
                                    isVertical = false
                                ))
                                LogCollector.d(TAG, "RT-DETR-V2(MLKit) [$i]: rect=${sortedBubbles[i].rect}, class=${sortedBubbles[i].classId}, text='$text'")
                            }
                        } catch (e: Exception) {
                            LogCollector.e(TAG, "RT-DETR-V2(MLKit) 识别失败[$i]", e)
                        }
                    }
                }
                CTDOCREngine.MangaOcr -> {
                    val texts = MangaOcrRecognizer.recognizeBatch(croppedBitmaps)
                    for (i in sortedBubbles.indices) {
                        val text = texts[i].trim()
                        if (text.isNotBlank() && !isDotOnlyPattern(text)) {
                            results.add(TextBlockInfo(
                                text = text,
                                boundingBox = sortedBubbles[i].rect,
                                cornerPoints = null,
                                isVertical = false
                            ))
                            LogCollector.d(TAG, "RT-DETR-V2(MangaOcr) [$i]: rect=${sortedBubbles[i].rect}, class=${sortedBubbles[i].classId}, text='$text'")
                        }
                    }
                }
                CTDOCREngine.PPOcrV5 -> {
                    val recLang = PPOcrV5Engine.getRecLang(language)
                    if (recLang != null) {
                        val texts = PPOcrV5Engine.recognizeBatch(context, croppedBitmaps, recLang)
                        for (i in sortedBubbles.indices) {
                            val result = texts.getOrElse(i) { RecResult("", 0f) }
                            if (result.text.isNotBlank() && result.score >= 0.5f) {
                                results.add(TextBlockInfo(
                                    text = result.text,
                                    boundingBox = sortedBubbles[i].rect,
                                    cornerPoints = null,
                                    isVertical = false
                                ))
                                LogCollector.d(TAG, "RT-DETR-V2(PPOcrV5) [$i]: rect=${sortedBubbles[i].rect}, class=${sortedBubbles[i].classId}, text='${result.text}', score=${String.format("%.3f", result.score)}")
                            }
                        }
                    } else {
                        LogCollector.w(TAG, "RT-DETR-V2(PPOcrV5) 不支持的语言: $language")
                    }
                }
            }

            // 释放裁剪的图片
            for (cropped in croppedBitmaps) {
                if (cropped !== bitmap) cropped.recycle()
            }

            LogCollector.d(TAG, "RT-DETR-V2 + ${ocrEngine.name} 完成，共 ${results.size} 个文字块")
            return results

        } catch (e: Exception) {
            LogCollector.e(TAG, "RT-DETR-V2 检测失败", e)
            throw e
        }
    }

    /**
     * RT-DETR-V2 调试模式：只检测，不翻译，显示所有类别的检测框。
     */
    fun detectWithRTDetrV2Debug(bitmap: Bitmap): RTDetrV2DebugResult {
        val allBubbles = ComicBubbleDetector.detectBubblesAllClasses(bitmap)
        LogCollector.d(TAG, "RT-DETR-V2 Debug: 检测到 ${allBubbles.size} 个区域")

        for ((idx, b) in allBubbles.withIndex()) {
            val className = when (b.classId) {
                0 -> "bubble"
                1 -> "text_bubble"
                2 -> "text_free"
                else -> "unknown"
            }
            LogCollector.d(TAG, "  [$idx] rect=[${b.rect.left},${b.rect.top},${b.rect.right},${b.rect.bottom}] class=$className(${b.classId}) conf=${String.format("%.3f", b.confidence)}")
        }

        // 计算最终提交区域（与 detectWithRTDetrV2 一致的逻辑）
        val greenBubbles = allBubbles.filter { it.classId == 1 }
        val redBubbles = allBubbles.filter { it.classId == 0 }.map { bubble ->
            // 压缩15%，去除气泡外框多余的边距
            val cx = (bubble.rect.left + bubble.rect.right) / 2
            val cy = (bubble.rect.top + bubble.rect.bottom) / 2
            val halfW = (bubble.rect.right - bubble.rect.left) / 2 * 0.85f
            val halfH = (bubble.rect.bottom - bubble.rect.top) / 2 * 0.85f
            bubble.copy(rect = Rect(
                (cx - halfW).toInt().coerceAtLeast(0),
                (cy - halfH).toInt().coerceAtLeast(0),
                (cx + halfW).toInt(),
                (cy + halfH).toInt()
            ))
        }
        val finalRegions = greenBubbles.map { it.rect }.toMutableList()
        for (red in redBubbles) {
            val fullyContained = greenBubbles.any { green -> calcContainment(red.rect, green.rect) > 0.9f }
            if (!fullyContained) {
                finalRegions.add(red.rect)
            }
        }

        return RTDetrV2DebugResult(
            allBubbles = allBubbles,
            textBubbles = allBubbles.filter { it.classId == 1 },
            textFree = allBubbles.filter { it.classId == 2 },
            emptyBubbles = allBubbles.filter { it.classId == 0 },
            finalRegions = finalRegions
        )
    }

    /**
     * 使用 PP-OCRv5 独立检测 + 识别（det + cls + rec 完整流水线）。
     *
     * @param bitmap 输入图片
     * @param language 语言代码
     * @return TextBlockInfo 列表
     */
    suspend fun detectWithPPOcrV5(
        bitmap: Bitmap,
        language: String,
        context: Context
    ): List<TextBlockInfo> {
        try {
            LogCollector.d(TAG, "使用 PP-OCRv5 独立检测+识别, language=$language")

            val recLang = PPOcrV5Engine.getRecLang(language)
            if (recLang == null) {
                LogCollector.w(TAG, "PP-OCRv5 不支持的语言: $language")
                return emptyList()
            }

            val result = withContext(Dispatchers.IO) {
                PPOcrV5Engine.runOCR(context, bitmap, recLang, useDet = true, useCls = true)
            }

            val textBlocks = result.texts.indices.mapNotNull { i ->
                val text = result.texts[i]
                if (text.isBlank() || result.scores[i] < 0.5f) return@mapNotNull null

                // 从 boxes 获取检测框 (FloatArray: x0,y0,x1,y1,x2,y2,x3,y3)
                val box = result.boxes.getOrNull(i) ?: return@mapNotNull null
                var xMin = Float.MAX_VALUE
                var yMin = Float.MAX_VALUE
                var xMax = Float.MIN_VALUE
                var yMax = Float.MIN_VALUE
                for (k in box.indices step 2) {
                    if (box[k] < xMin) xMin = box[k]
                    if (box[k] > xMax) xMax = box[k]
                }
                for (k in 1 until box.size step 2) {
                    if (box[k] < yMin) yMin = box[k]
                    if (box[k] > yMax) yMax = box[k]
                }
                val rect = Rect(
                    xMin.toInt().coerceAtLeast(0),
                    yMin.toInt().coerceAtLeast(0),
                    xMax.toInt().coerceAtMost(bitmap.width - 1),
                    yMax.toInt().coerceAtMost(bitmap.height - 1)
                )
                val isVertical = rect.height() > rect.width()

                TextBlockInfo(text = text, boundingBox = rect, cornerPoints = null, isVertical = isVertical)
            }

            LogCollector.d(TAG, "PP-OCRv5 独立完成，共 ${textBlocks.size} 个文字块")
            return textBlocks

        } catch (e: Exception) {
            LogCollector.e(TAG, "PP-OCRv5 检测失败", e)
            throw e
        }
    }

    /**
     * ML Kit 调试模式：返回所有 ML Kit 识别数据（block/line/element 全部层级）
     */
    suspend fun detectWithMLKitDebug(
        bitmap: Bitmap,
        language: String
    ): MLKitDebugResult = suspendCancellableCoroutine { continuation ->
        val recognizer = when (language) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "zh", "zh-CN", "zh-TW" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        LogCollector.d(TAG, "ML Kit Debug: bitmap=${bitmap.width}x${bitmap.height}, lang=$language")
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                var totalLines = 0
                var totalElements = 0

                val debugBlocks = result.textBlocks.map { block ->
                    val lines = block.lines.map { line ->
                        totalLines++
                        val elements = line.elements.map { element ->
                            totalElements++
                            MLKitDebugElement(
                                elementText = element.text,
                                elementRect = element.boundingBox,
                                elementCorners = element.cornerPoints?.let { pts ->
                                    Array(pts.size) { i -> pts[i] }
                                }
                            )
                        }
                        MLKitDebugLine(
                            lineText = line.text,
                            lineRect = line.boundingBox,
                            lineCorners = line.cornerPoints?.let { pts ->
                                Array(pts.size) { i -> pts[i] }
                            },
                            angle = line.angle,
                            elements = elements
                        )
                    }

                    MLKitDebugBlock(
                        blockText = block.text,
                        blockRect = block.boundingBox,
                        blockCorners = block.cornerPoints?.let { pts ->
                            Array(pts.size) { i -> pts[i] }
                        },
                        lines = lines,
                        language = block.recognizedLanguage
                    )
                }

                val result_obj = MLKitDebugResult(
                    textBlocks = debugBlocks,
                    totalLines = totalLines,
                    totalElements = totalElements,
                    detectedLanguage = result.textBlocks.firstOrNull()?.recognizedLanguage
                )

                LogCollector.d(TAG, "ML Kit Debug: 完成, blocks=${debugBlocks.size}, lines=$totalLines, elements=$totalElements")
                continuation.resume(result_obj)
            }
            .addOnFailureListener { e ->
                LogCollector.e(TAG, "ML Kit Debug: 失败", e)
                continuation.resumeWithException(e)
            }
            .addOnCompleteListener {
                recognizer.close()
            }
    }
}
