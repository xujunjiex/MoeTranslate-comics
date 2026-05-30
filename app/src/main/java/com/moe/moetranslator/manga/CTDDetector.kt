package com.moe.moetranslator.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.moe.moetranslator.utils.LogCollector
import org.locationtech.jts.geom.Coordinate
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * CTD（ComicTextDetector）文字检测器（ONNX Runtime 推理）。
 *
 * 使用 YOLOv5 backbone + UnetHead + DBHead 检测图片中的文字区域。
 * 与 DBNet 的区别：CTD 输出文字行级别区域，不需要 BoxMerger 合并。
 *
 * 输入: [1, 3, 1024, 1024] float32, 归一化到 [0, 1]
 * 输出: blks + mask [1, 1, H, W] + lines_map [1, 2, H, W]
 * 后处理: SegDetectorRepresenter（与 DBNet 相同）
 */
data class DetectedRect(
    val rect: Rect,
    val isVertical: Boolean  // 原始检测框的方向
)

/**
 * 带字高的检测矩形（用于 merge 逻辑）
 */
data class DetectedRectWithFont(
    val rect: Rect,           // AABB
    val isVertical: Boolean,  // 文字方向
    val fontSize: Float,      // 字高（从 QuadBox 结构线计算）
    val angle: Float,         // 旋转角度（弧度）
    val aspectRatio: Float    // 宽高比
)

object CTDDetector {

    private const val TAG = "CTDDetector"
    private const val DETECT_SIZE = 1024  // CTD 输入尺寸
    private const val STRIDE = 64         // CTD stride 对齐
    private const val TEXT_THRESHOLD = 0.3f
    private const val MIN_AREA = 100       // 最小 AABB 面积阈值（100px²）
    private const val MAX_AREA_RATIO = 0.05f // 最大 AABB 面积占原图比例（过滤全图级别的误检，5%）

    // 预分配的缓冲区（避免每次调用重复分配）
    private val inputBuffer = FloatBuffer.allocate(1 * 3 * DETECT_SIZE * DETECT_SIZE)
    private val pixelBuffer = IntArray(DETECT_SIZE * DETECT_SIZE)

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile
    var isInitialized = false
        private set

    /**
     * 初始化模型
     */
    suspend fun initialize(context: Context) {
        if (isInitialized) return

        try {
            LogCollector.d(TAG, "开始初始化 CTD 模型...")

            // 检查模型是否存在
            if (!CTDModelManager.isModelInFilesDir(context)) {
                LogCollector.e(TAG, "CTD 模型未下载，请先下载")
                throw IllegalStateException("CTD model not downloaded")
            }

            val modelPath = CTDModelManager.getFilesDirModelFile(context).absolutePath
            LogCollector.d(TAG, "从 filesDir 加载 CTD 模型: $modelPath")

            ortEnv = OrtEnvironment.getEnvironment()

            // 直接使用 CPU+多线程（NNAPI 初始化太慢，跳过）
            val cpuOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setMemoryPatternOptimization(true)
                setCPUArenaAllocator(true)
                setIntraOpNumThreads(4)
            }
            session = ortEnv!!.createSession(modelPath, cpuOptions)
            LogCollector.d(TAG, "CTD 模型加载完成 (CPU)")
            LogCollector.d(TAG, "CTD 模型加载完成")

            isInitialized = true

        } catch (e: Exception) {
            LogCollector.e(TAG, "CTD 初始化失败", e)
            release()
            throw e
        }
    }

    /**
     * 检测图片中的文字区域，返回 QuadBox（保留旋转信息）。
     * CTD 输出文字行级别区域，不需要 BoxMerger 合并。
     */
    fun detectQuadBoxes(bitmap: Bitmap): List<QuadBox> {
        if (!isInitialized) {
            throw IllegalStateException("CTDDetector 未初始化")
        }

        try {
            val origWidth = bitmap.width
            val origHeight = bitmap.height

            val (linesMap, contentH, contentW) = runInference(bitmap)

            // 使用 CTDPostProcessor 的 SegDetectorRepresenter 逻辑（对齐 Python ctd.py）
            // 参数：textThreshold=0.3, boxThreshold=0.6, unclipRatio=1.5
            // 模型输出是 DETECT_SIZE×DETECT_SIZE，但内容只在 contentW×contentH 区域
            // 坐标映射用 contentW/contentH 做比例，不是 DETECT_SIZE
            val quadBoxes = CTDPostProcessor.extractQuadBoxes(
                probMap = linesMap,
                height = contentH,
                width = contentW,
                origWidth = origWidth,
                origHeight = origHeight,
                textThreshold = 0.3f,
                boxThreshold = 0.6f,
                unclipRatio = 1.5f
            )

            LogCollector.d(TAG, "检测完成: ${quadBoxes.size} 个文字行")
            return quadBoxes

        } catch (e: Exception) {
            LogCollector.e(TAG, "检测失败", e)
            throw e
        }
    }

    /**
     * CTD 检测结果（含被丢弃的框），用于调试模式
     */
    data class DetectResult(
        val quadBoxes: List<QuadBox>,       // 通过过滤的 QuadBox
        val discardedBoxes: List<QuadBox>    // 被过滤丢弃的 QuadBox
    )

    /**
     * 检测图片中的文字区域，返回包含被丢弃框的完整结果。
     * 用于 CTD 调试模式，显示哪些框被过滤了。
     */
    fun detectQuadBoxesWithDiscarded(bitmap: Bitmap): DetectResult {
        if (!isInitialized) {
            throw IllegalStateException("CTDDetector 未初始化")
        }

        try {
            val origWidth = bitmap.width
            val origHeight = bitmap.height

            val (linesMap, contentH, contentW) = runInference(bitmap)

            val extractResult = CTDPostProcessor.extractQuadBoxesWithDiscarded(
                probMap = linesMap,
                height = contentH,
                width = contentW,
                origWidth = origWidth,
                origHeight = origHeight,
                textThreshold = 0.3f,
                boxThreshold = 0.6f,
                unclipRatio = 1.5f
            )

            LogCollector.d(TAG, "检测完成: ${extractResult.quadBoxes.size} 个文字行, ${extractResult.discardedBoxes.size} 个被丢弃")
            return DetectResult(extractResult.quadBoxes, extractResult.discardedBoxes)

        } catch (e: Exception) {
            LogCollector.e(TAG, "检测失败", e)
            throw e
        }
    }

    /**
     * 从轮廓点判断是否竖排
     */
    private fun isVerticalFromContour(contour: List<Coordinate>): Boolean {
        if (contour.size < 4) {
            return false // 需要至少4个点才能形成四边形
        }
        // 简化为用AABB的宽高比判断
        val minX = contour.minOf { it.x }
        val maxX = contour.maxOf { it.x }
        val minY = contour.minOf { it.y }
        val maxY = contour.maxOf { it.y }

        val w = (maxX - minX).toFloat()
        val h = (maxY - minY).toFloat()
        if (w <= 0 || h <= 0) return false
        return h > w
    }

    /**
     * 简化版检测：阈值化 → BFS 连通域 → AABB → 缩放到原图坐标
     * 只返回 List<DetectedRect>，不做 unclip、旋转、四边形处理
     *
     * @return AABB 矩形列表（已缩放到原图坐标），包含方向信息
     */
    fun detectRectsSimple(bitmap: Bitmap): List<DetectedRect> {
        if (!isInitialized) {
            throw IllegalStateException("CTDDetector 未初始化")
        }

        try {
            val origWidth = bitmap.width
            val origHeight = bitmap.height

            val (linesMap, contentH, contentW) = runInference(bitmap)

            // 1. 阈值化 → 二值图
            val binary = BooleanArray(contentH * contentW)
            for (i in linesMap.indices) {
                binary[i] = linesMap[i] > TEXT_THRESHOLD
            }

            // 2. BFS 连通域提取
            val contours = findContoursSimple(binary, contentW, contentH)
            LogCollector.d(TAG, "detectRectsSimple: 阈值=$TEXT_THRESHOLD, 轮廓数=${contours.size}")

            // 3. 计算缩放比例和最大面积限制
            val scaleX = origWidth.toFloat() / contentW
            val scaleY = origHeight.toFloat() / contentH
            val maxArea = (origWidth * origHeight * MAX_AREA_RATIO).toInt()

            // 4. 对每个连通域计算 AABB 并缩放到原图坐标
            val rects = mutableListOf<DetectedRect>()
            for (contour in contours) {
                val minX = contour.minOf { it.x }
                val maxX = contour.maxOf { it.x }
                val minY = contour.minOf { it.y }
                val maxY = contour.maxOf { it.y }

                val aabb = Rect(
                    (minX * scaleX).toInt(),
                    (minY * scaleY).toInt(),
                    (maxX * scaleX).toInt(),
                    (maxY * scaleY).toInt()
                )

                // 过滤太小的 AABB
                val area = aabb.width() * aabb.height()
                // 过滤超大 AABB：宽度占原图 > 80% 或面积超限
                val widthRatio = aabb.width().toFloat() / origWidth
                val isTooWide = widthRatio > 0.8f
                val isVertical = isVerticalFromContour(contour)
                if (area >= MIN_AREA && area <= maxArea && !isTooWide) {
                    rects.add(DetectedRect(aabb, isVertical))
                    LogCollector.d(TAG, "detectRectsSimple: AABB=[${aabb.left}, ${aabb.top}, ${aabb.right}, ${aabb.bottom}](${aabb.width()}x${aabb.height()}), 面积=$area, isVertical=$isVertical")
                } else {
                    val reason = when {
                        area < MIN_AREA -> "面积太小"
                        area > maxArea -> "面积超限"
                        isTooWide -> "宽度占比太大(${String.format("%.1f", widthRatio * 100)}%)"
                        else -> "未知"
                    }
                    LogCollector.d(TAG, "detectRectsSimple: 过滤 AABB=[${aabb.left}, ${aabb.top}, ${aabb.right}, ${aabb.bottom}](${aabb.width()}x${aabb.height()}), 面积=$area, maxArea=$maxArea, isVertical=$isVertical, reason=$reason")
                }
            }

            LogCollector.d(TAG, "detectRectsSimple: 检测完成, ${rects.size} 个区域")
            return rects

        } catch (e: Exception) {
            LogCollector.e(TAG, "检测失败", e)
            throw e
        }
    }

    /**
     * BFS 连通域提取（简化版，用于 detectRectsSimple）
     */
    private fun findContoursSimple(
        binary: BooleanArray,
        width: Int,
        height: Int
    ): List<List<Coordinate>> {
        val visited = BooleanArray(binary.size)
        val contours = mutableListOf<List<Coordinate>>()

        val dx = intArrayOf(1, 0, -1, 0)
        val dy = intArrayOf(0, 1, 0, -1)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!binary[idx] || visited[idx]) continue

                val componentPixels = mutableListOf<Coordinate>()
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                visited[idx] = true

                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    componentPixels.add(Coordinate((cur % width).toDouble(), (cur / width).toDouble()))

                    val curX = cur % width
                    val curY = cur / width

                    for (d in 0..3) {
                        val nx = curX + dx[d]
                        val ny = curY + dy[d]
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                        val nidx = ny * width + nx
                        if (!binary[nidx] || visited[nidx]) continue
                        visited[nidx] = true
                        queue.add(nidx)
                    }
                }

                if (componentPixels.size >= 3) {
                    contours.add(componentPixels)
                }
            }
        }

        return contours
    }

    /**
     * 运行 CTD 推理，返回 lines_map 的概率图和内容尺寸。
     */
    private fun runInference(bitmap: Bitmap): Triple<FloatArray, Int, Int> {
        // 1. 预处理：letterbox 缩放到 DETECT_SIZE×DETECT_SIZE（stride=64 对齐）
        val (inputTensor, contentH, contentW) = preprocessImage(bitmap)

        // 2. ONNX 推理（3 个输出：blks, mask, lines_map）
        val results: OrtSession.Result = session!!.run(mapOf("images" to inputTensor))
        inputTensor.close()

        // 3. 获取 lines_map 输出（det: [1, 2, H, W]，2 通道概率图）
        //    注意：必须精确匹配 name="det"，不能依赖顺序！
        //    因为 onnxruntime 遍历 session.outputNames 的顺序不固定，
        //    seg([1,1,1024,1024]) 可能先于 det([1,2,1024,1024]) 被遍历到。
        var linesMapTensor: OnnxTensor? = null
        for (name in session!!.outputNames) {
            val value = results.get(name)
            if (value.isPresent && value.get() is OnnxTensor) {
                val tensor = value.get() as OnnxTensor
                val shape = tensor.info.shape
                LogCollector.d(TAG, "输出: name=$name, shape=${shape.contentToString()}")
                // 精确匹配 name="det"（2 通道概率图，形状 [1, 2, H, W]）
                if (name == "det" && shape.size == 4 && shape[1].toInt() == 2) {
                    linesMapTensor = tensor
                    break
                }
            }
        }
        // 如果精确匹配失败（兼容部分 onnxruntime 变体），fallback 到之前的启发式逻辑
        if (linesMapTensor == null) {
            LogCollector.d(TAG, "精确匹配 det 失败，尝试 fallback 遍历")
            for (name in session!!.outputNames) {
                val value = results.get(name)
                if (value.isPresent && value.get() is OnnxTensor) {
                    val tensor = value.get() as OnnxTensor
                    val shape = tensor.info.shape
                    if (shape.size == 4 && shape[1].toInt() == 2) {
                        linesMapTensor = tensor
                        LogCollector.d(TAG, "Fallback 选中: name=$name")
                        break
                    }
                }
            }
        }
        if (linesMapTensor == null) {
            results.close()
            throw IllegalStateException("找不到 2 通道 det 输出 tensor")
        }

        val linesData = extractFloatArray(linesMapTensor)
        val linesShape = linesMapTensor.info.shape
        val linesC = linesShape[1].toInt()  // 通道数（可能是 1 或 2）
        val linesW = linesShape[3].toInt()
        LogCollector.d(TAG, "使用输出: shape=${linesShape.contentToString()}, contentH=$contentH, contentW=$contentW")

        // 4. 后处理：取 channel 0，裁切掉 padding 区域
        //    对齐 manga-image-translator: lines_map = lines_map[..., :height-dh, :width-dw]
        //    channel 0 起始偏移 = 0（NCHW 布局，channel 0 在前 linesH*linesW 个元素）
        //    注意：CTD 模型的 det 输出已经过 sigmoid，不需要再应用
        val linesH = linesShape[2].toInt()
        val probMap = FloatArray(contentH * contentW)
        for (y in 0 until contentH) {
            val srcOffset = y * linesW              // 源行起始偏移（linesW=1024）
            val dstOffset = y * contentW           // 目标行起始偏移
            for (x in 0 until contentW) {
                probMap[dstOffset + x] = linesData[srcOffset + x]
            }
        }

        linesMapTensor.close()
        results.close()

        return Triple(probMap, contentH, contentW)
    }

    /**
     * 预处理图片：letterbox 缩放到 DETECT_SIZE×DETECT_SIZE（stride=64 对齐），归一化到 [0, 1]
     *
     * 对齐 manga-image-translator 的 preprocess_img：
     * - letterbox 保持宽高比，stride=64 对齐
     * - 归一化到 [0, 1]（不是 [-1, 1]）
     */
    private fun preprocessImage(bitmap: Bitmap): Triple<OnnxTensor, Int, Int> {
        val origW = bitmap.width
        val origH = bitmap.height

        // 保持宽高比缩放，使长边 = DETECT_SIZE，短边按比例缩放
        val scale = DETECT_SIZE.toFloat() / maxOf(origW, origH)
        // 实际内容尺寸（不是填充后的尺寸）
        // 不需要 maxOf(STRIDE, ...)，round 已经保证 > 0
        val contentW = (origW * scale).toInt()
        val contentH = (origH * scale).toInt()

        // 缩放图片
        val scaled = Bitmap.createScaledBitmap(bitmap, contentW, contentH, true)

        // 创建 DETECT_SIZE × DETECT_SIZE 画布，左上角放置缩放后的图片，其余填黑
        val padded = Bitmap.createBitmap(DETECT_SIZE, DETECT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(scaled, 0f, 0f, null)

        if (scaled != bitmap) scaled.recycle()

        // 提取像素（使用预分配缓冲区）
        padded.getPixels(pixelBuffer, 0, DETECT_SIZE, 0, 0, DETECT_SIZE, DETECT_SIZE)
        padded.recycle()

        // CHW 格式，RGB 通道，归一化到 [0, 1]（使用预分配缓冲区避免重复分配）
        inputBuffer.clear()
        for (c in 0 until 3) {
            for (i in pixelBuffer.indices) {
                val pixel = pixelBuffer[i]
                val value = when (c) {
                    0 -> (pixel shr 16 and 0xFF) / 255.0f  // R
                    1 -> (pixel shr 8 and 0xFF) / 255.0f   // G
                    2 -> (pixel and 0xFF) / 255.0f          // B
                    else -> 0f
                }
                inputBuffer.put(value)
            }
        }
        inputBuffer.rewind()

        val tensor = OnnxTensor.createTensor(
            ortEnv!!, inputBuffer,
            longArrayOf(1, 3, DETECT_SIZE.toLong(), DETECT_SIZE.toLong())
        )
        return Triple(tensor, contentH, contentW)
    }

    /**
     * sigmoid 函数
     */
    private fun sigmoid(x: Float): Float {
        return if (x >= 0) {
            1f / (1f + Math.exp(-x.toDouble())).toFloat()
        } else {
            val ex = Math.exp(x.toDouble()).toFloat()
            ex / (1f + ex)
        }
    }

    /**
     * 从 OnnxTensor 提取 float 数组
     */
    private fun extractFloatArray(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        val arr = FloatArray(buffer.remaining())
        buffer.get(arr)
        buffer.rewind()
        return arr
    }

    /**
     * 将 assets 文件复制到缓存目录
     */
    private fun copyAssetToCache(context: Context, assetPath: String): String {
        val fileName = assetPath.substringAfterLast("/")
        val cacheFile = context.cacheDir.resolve(fileName)
        LogCollector.d(TAG, "复制 assets 文件: $assetPath -> ${cacheFile.absolutePath}")
        context.assets.open(assetPath).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        LogCollector.d(TAG, "复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
        return cacheFile.absolutePath
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            session?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            LogCollector.e(TAG, "释放资源失败", e)
        } finally {
            session = null
            ortEnv = null
            isInitialized = false
        }
    }

    /**
     * 将 QuadBox 列表转换为 DetectedRectWithFont 列表
     */
    fun convertQuadBoxesToDetectedRects(quadBoxes: List<QuadBox>): List<DetectedRectWithFont> {
        return quadBoxes.map { qb ->
            DetectedRectWithFont(
                rect = qb.aabb,  // 使用 QuadBox 的 AABB
                isVertical = CTDPostProcessor.sortPnts(qb.pts),  // 从角点判断方向
                fontSize = qb.fontSize,  // 从结构线计算的真实字高
                angle = qb.angle,
                aspectRatio = qb.aspectRatio
            )
        }
    }
}
