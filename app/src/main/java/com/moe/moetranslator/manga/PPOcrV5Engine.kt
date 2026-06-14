package com.moe.moetranslator.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import com.moe.moetranslator.R
import com.moe.moetranslator.utils.LogCollector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.buffer.BufferOp
import java.nio.FloatBuffer
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============================================================================
// Data Classes
// ============================================================================

/**
 * 检测框（4 个顶点）
 */
data class DetBox(val points: Array<Point>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetBox) return false
        return points.contentEquals(other.points)
    }

    override fun hashCode(): Int = points.contentHashCode()
}

/**
 * 分类结果
 */
data class ClsResult(val label: String, val score: Float)

/**
 * 识别结果
 */
data class RecResult(val text: String, val score: Float)

/**
 * 单字符框
 */
data class CharBox(val text: String, val score: Float, val box: FloatArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharBox) return false
        return text == other.text && score == other.score && box.contentEquals(other.box)
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + box.contentHashCode()
        return result
    }
}

/**
 * 单词级结果（含字符框）
 */
data class WordResult(val text: String, val score: Float, val charBoxes: List<CharBox>)

/**
 * OCR 完整结果
 */
data class OcrResult(
    val boxes: List<FloatArray>,
    val texts: List<String>,
    val scores: List<Float>,
    val wordResults: List<WordResult>,
    val elapseList: List<Float>
)

/**
 * 可视化结果
 */
data class VisResult(
    val boxes: List<FloatArray>,
    val texts: List<String>,
    val scores: List<Float>
)

// ============================================================================
// PP-OCRv5 OCR Engine
// ============================================================================

/**
 * PP-OCRv5 完整 OCR 引擎（det + cls + rec）。
 *
 * 对齐 RapidOCR Python 实现：det → crop → cls → rec → CTCLabelDecode。
 * 所有模型输入名均为 `x`。
 */
object PPOcrV5Engine {

    private const val TAG = "PPOcrV5Engine"

    // -----------------------------------------------------------------------
    // Det 常量 (ch_ppocr_det/utils.py DetPreProcess + DBPostProcess)
    // -----------------------------------------------------------------------
    private const val DET_LIMIT_SIDE_LEN = 960
    private const val DET_LIMIT_TYPE = "max"
    private val DET_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val DET_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    private const val DET_THRESH = 0.3f
    private const val DET_BOX_THRESH = 0.5f
    private const val DET_UNCLIP_RATIO = 1.6
    private const val DET_MAX_CANDIDATES = 1000
    private const val DET_MIN_SIZE = 3

    // -----------------------------------------------------------------------
    // Cls 常量 (ch_ppocr_cls/main.py)
    // -----------------------------------------------------------------------
    private val CLS_IMAGE_SHAPE = intArrayOf(3, 80, 160) // [C, H, W]
    private const val CLS_THRESH = 0.9f

    // -----------------------------------------------------------------------
    // Rec 常量 (ch_ppocr_rec/main.py)
    // -----------------------------------------------------------------------
    private const val REC_IMG_HEIGHT = 48
    private const val REC_IMG_CHANNELS = 3
    private const val REC_BATCH_NUM = 16

    // -----------------------------------------------------------------------
    // 全局常量
    // -----------------------------------------------------------------------
    private const val TEXT_SCORE_THRESH = 0.5f

    // -----------------------------------------------------------------------
    // Rec 语言枚举
    // -----------------------------------------------------------------------
    /**
     * Rec 模型枚举（全部 PP-OCRv5 mobile）。
     * ZH: 中英日混合（~16MB，内置）
     * JA: 日文专用（复用 ZH 模型，日文由 ZH 模型覆盖）
     * EN: 英文专用（~7.5MB，可选下载）
     * KO: 韩文专用（~13MB，可选下载）
     * RU: 俄文/西里尔文字（~7.7MB，可选下载）
     */
    enum class RecLang(val code: String) {
        ZH("zh"),
        JA("zh"),   // 日文走 rec_zh（PP-OCRv5 中英日混合）
        EN("en"),
        KO("ko"),
        RU("ru");

        fun recModelFile(): String = "ppocrv5/rec_$code.onnx"
        fun dictFile(): String = "ppocrv5/rec_${code}_dict.txt"
    }

    // -----------------------------------------------------------------------
    // ONNX 会话
    // -----------------------------------------------------------------------
    @Volatile
    private var ortEnv: OrtEnvironment? = null
    @Volatile
    private var detSession: OrtSession? = null
    @Volatile
    private var clsSession: OrtSession? = null
    private val recSessions = EnumMap<RecLang, OrtSession>(RecLang::class.java)

    // 字典：blank(0) + dict_chars + space(end)
    private var dictionary: Map<RecLang, List<String>> = emptyMap()

    @Volatile
    var isInitialized = false
        private set

    // rec 会话加载锁
    private val recLocks = EnumMap<RecLang, Any>(RecLang::class.java)

    // 初始化/释放锁
    private val lock = Any()

    // ========================================================================
    // 初始化 / 释放
    // ========================================================================

    /**
     * 初始化引擎：加载 det + cls ONNX 会话 + 字典。
     * rec 会话按需懒加载（首次调用 [getRecSession] 时）。
     */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (isInitialized) return

            try {
                LogCollector.d(TAG, "开始初始化 PP-OCRv5 引擎...")

                ortEnv = OrtEnvironment.getEnvironment()

                val sessionOpts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setMemoryPatternOptimization(true)
                    setCPUArenaAllocator(true)
                    setIntraOpNumThreads(4)
                }

                // det（内置，从 assets 加载）
                LogCollector.d(TAG, "加载 det 模型...")
                val detBytes = context.assets.open("ppocrv5/det_v5.onnx").use { it.readBytes() }
                detSession = ortEnv!!.createSession(detBytes, sessionOpts)
                LogCollector.d(TAG, "det 模型加载完成")

                // cls（内置，可选，从 assets 加载）
                try {
                    LogCollector.d(TAG, "加载 cls 模型...")
                    val clsBytes = context.assets.open("ppocrv5/cls.onnx").use { it.readBytes() }
                    clsSession = ortEnv!!.createSession(clsBytes, sessionOpts)
                    LogCollector.d(TAG, "cls 模型加载完成")
                } catch (e: Exception) {
                    LogCollector.w(TAG, "cls 模型不可用，将跳过方向分类: ${e.message}")
                    clsSession = null
                }

                // 初始化 rec 锁
                for (lang in RecLang.entries) {
                    recLocks[lang] = Any()
                }

                // 字典
                loadDictionary(context)

                isInitialized = true
                LogCollector.d(TAG, "PP-OCRv5 引擎初始化完成")

            } catch (e: Exception) {
                LogCollector.e(TAG, "PP-OCRv5 初始化失败", e)
                release()
                throw e
            }
        }
    }

    /**
     * 懒加载 rec 会话（线程安全）
     * ZH/JA（code="zh"）从内置 assets 加载；EN/KO 从 filesDir 加载（需用户下载）。
     */
    private fun getRecSession(context: Context, lang: RecLang): OrtSession? {
        recSessions[lang]?.let { return it }

        synchronized(recLocks[lang]!!) {
            recSessions[lang]?.let { return it }

            return try {
                LogCollector.d(TAG, "懒加载 rec 模型: ${lang.code}...")
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setMemoryPatternOptimization(true)
                    setCPUArenaAllocator(true)
                    setIntraOpNumThreads(4)
                }

                val bytes = if (lang == RecLang.ZH || lang == RecLang.JA) {
                    // 内置模型，从 assets 加载
                    LogCollector.d(TAG, "从 assets 加载 rec 模型: ${lang.code}")
                    context.assets.open("ppocrv5/rec_zh.onnx").use { it.readBytes() }
                } else {
                    // 可选模型，从 filesDir 加载
                    val modelFile = PPOcrModelManager.getRecModelFile(context, lang.code)
                    if (modelFile == null) {
                        LogCollector.w(TAG, "rec 模型 ${lang.code} 未下载")
                        return null
                    }
                    LogCollector.d(TAG, "从 filesDir 加载 rec 模型: ${lang.code}")
                    modelFile.readBytes()
                }
                val session = ortEnv!!.createSession(bytes, opts)
                recSessions[lang] = session
                LogCollector.d(TAG, "rec 模型 ${lang.code} 加载完成")
                session
            } catch (e: Exception) {
                LogCollector.e(TAG, "rec 模型 ${lang.code} 加载失败", e)
                null
            }
        }
    }

    /**
     * 加载字典文件：blank(0) + dict_chars + space(end)
     * 所有字典内置在 assets 中。
     */
    private fun loadDictionary(context: Context) {
        val dicts = mutableMapOf<RecLang, List<String>>()

        for (lang in RecLang.entries) {
            try {
                // ZH 和 JA 共用同一个字典文件
                val dictFileName = if (lang == RecLang.JA) "ppocrv5/rec_zh_dict.txt"
                    else "ppocrv5/rec_${lang.code}_dict.txt"

                LogCollector.d(TAG, "从 assets 加载字典: ${lang.code} ($dictFileName)")
                val lines = context.assets.open(dictFileName)
                    .bufferedReader().readLines().filter { it.isNotEmpty() }

                val dict = mutableListOf<String>()
                dict.add("blank") // index 0
                dict.addAll(lines)
                dict.add(" ")     // end

                dicts[lang] = dict
                LogCollector.d(TAG, "字典 ${lang.code}: ${dict.size} 条 (含 blank+space)")
            } catch (e: Exception) {
                LogCollector.e(TAG, "字典 ${lang.code} 加载失败", e)
            }
        }

        dictionary = dicts
    }

    /**
     * 释放所有资源
     */
    fun release() {
        synchronized(lock) {
            try {
                detSession?.close()
                clsSession?.close()
                recSessions.values.forEach { try { it.close() } catch (_: Exception) {} }
                ortEnv?.close()
            } catch (e: Exception) {
                LogCollector.e(TAG, "释放资源失败", e)
            } finally {
                detSession = null
                clsSession = null
                recSessions.clear()
                ortEnv = null
                dictionary = emptyMap()
                isInitialized = false
            }
        }
    }

    /**
     * 将 source language 字符串映射到 RecLang。
     * 日文走 JA（= rec_zh，PP-OCRv5 中英日混合模型）。
     */
    fun getRecLang(language: String): RecLang? = when (language.lowercase()) {
        "zh", "chinese", "中文" -> RecLang.ZH
        "ja", "japanese", "日本語" -> RecLang.JA
        "en", "english", "英语" -> RecLang.EN
        "ko", "korean", "한국어" -> RecLang.KO
        "ru", "russian", "俄文" -> RecLang.RU
        else -> null
    }

    /**
     * 检查指定语言的 rec 模型是否可用（已下载到 filesDir）。
     * ZH/JA 内置（始终可用），EN/KO/RU 需用户下载。
     */
    fun isRecModelAvailable(context: Context, lang: RecLang): Boolean {
        return if (lang == RecLang.ZH || lang == RecLang.JA) {
            true // 内置模型
        } else {
            PPOcrModelManager.isRecModelDownloaded(context, lang.code)
        }
    }

    /**
     * 解析实际使用的 rec 语言（带 fallback）。
     * - ZH/JA：始终可用（内置）
     * - EN：已下载用 EN，否则 fallback 到 ZH（ch 模型也支持英文识别）
     * - KO：已下载用 KO，否则返回 null（需提示用户下载）
     * - RU：已下载用 RU，否则返回 null（需提示用户下载）
     *
     * @return Pair<实际RecLang, 提示消息?> 提示消息不为 null 时应展示给用户
     */
    fun resolveRecLang(context: Context, language: String): Pair<RecLang?, String?> {
        val lang = getRecLang(language) ?: return Pair(null, null)

        return when (lang) {
            RecLang.ZH, RecLang.JA -> Pair(lang, null)
            RecLang.EN -> {
                if (isRecModelAvailable(context, RecLang.EN)) {
                    Pair(RecLang.EN, null)
                } else {
                    Pair(RecLang.ZH, null) // fallback: ch 模型支持英文
                }
            }
            RecLang.KO -> {
                if (isRecModelAvailable(context, RecLang.KO)) {
                    Pair(RecLang.KO, null)
                } else {
                    Pair(null, context.getString(R.string.ko_need_download_model))
                }
            }
            RecLang.RU -> {
                if (isRecModelAvailable(context, RecLang.RU)) {
                    Pair(RecLang.RU, null)
                } else {
                    Pair(null, context.getString(R.string.ru_need_download_model))
                }
            }
        }
    }

    // ========================================================================
    // DetPreProcess (ch_ppocr_det/utils.py)
    // ========================================================================

    /**
     * 检测预处理。
     * 返回 (FloatArray CHW, resizedH, resizedW)。
     */
    private fun preprocessDet(bitmap: Bitmap): Triple<FloatArray, Int, Int> {
        val srcH = bitmap.height
        val srcW = bitmap.width

        // 1. 计算缩放
        var resizeH = srcH
        var resizeW = srcW
        val limitSideLen = DET_LIMIT_SIDE_LEN

        val ratio: Float
        if (DET_LIMIT_TYPE == "min") {
            val minSide = min(resizeH, resizeW).toFloat()
            if (minSide < limitSideLen) {
                ratio = limitSideLen / minSide
                resizeH = (resizeH * ratio).roundToInt()
                resizeW = (resizeW * ratio).roundToInt()
            } else {
                val maxSide = max(resizeH, resizeW).toFloat()
                if (maxSide > limitSideLen) {
                    ratio = limitSideLen / maxSide
                    resizeH = (resizeH * ratio).roundToInt()
                    resizeW = (resizeW * ratio).roundToInt()
                }
            }
        } else {
            val maxSide = max(resizeH, resizeW).toFloat()
            if (maxSide > limitSideLen) {
                ratio = limitSideLen / maxSide
                resizeH = (resizeH * ratio).roundToInt()
                resizeW = (resizeW * ratio).roundToInt()
            }
        }

        // 2. 对齐 32 的倍数
        resizeH = max(32, (resizeH / 32) * 32)
        resizeW = max(32, (resizeW / 32) * 32)

        // 3. Resize
        val resized = Bitmap.createScaledBitmap(bitmap, resizeW, resizeH, true)

        // 4. 提取像素
        val pixels = IntArray(resizeH * resizeW)
        resized.getPixels(pixels, 0, resizeW, 0, 0, resizeW, resizeH)
        if (resized !== bitmap) resized.recycle()

        // 5. HWC→CHW, normalize = (pixel/255 - mean) / std
        val floatArr = FloatArray(3 * resizeH * resizeW)
        for (c in 0 until 3) {
            for (i in 0 until (resizeH * resizeW)) {
                val pixel = pixels[i]
                val v = when (c) {
                    0 -> (pixel shr 16 and 0xFF) / 255.0f // R
                    1 -> (pixel shr 8 and 0xFF) / 255.0f  // G
                    2 -> (pixel and 0xFF) / 255.0f         // B
                    else -> 0f
                }
                floatArr[c * resizeH * resizeW + i] = (v - DET_MEAN[c]) / DET_STD[c]
            }
        }

        return Triple(floatArr, resizeH, resizeW)
    }

    // ========================================================================
    // DBPostProcess (ch_ppocr_det/utils.py)
    // ========================================================================

    /**
     * 检测后处理：pred → bounding boxes（原图坐标）。
     */
    private fun postprocessDet(
        pred: FloatArray,
        predH: Int,
        predW: Int,
        srcH: Int,
        srcW: Int
    ): BoxScoreResult {
        // 1. 阈值化
        val cBitmap = Bitmap.createBitmap(predW, predH, Bitmap.Config.ARGB_8888)
        for (i in 0 until predH * predW) {
            val v = if (pred[i] > DET_THRESH) Color.WHITE else Color.BLACK
            cBitmap.setPixel(i % predW, i / predW, v)
        }

        // 2. BFS 连通域
        val contours = findContours(cBitmap, predW, predH)
        cBitmap.recycle()

        if (contours.isEmpty()) return BoxScoreResult(emptyList(), emptyList())

        // 3. 限制候选数量
        val limitedContours = if (contours.size > DET_MAX_CANDIDATES) {
            contours.sortedByDescending { it.size }.take(DET_MAX_CANDIDATES)
        } else {
            contours
        }

        // 4. 处理每个连通域
        val boxes = mutableListOf<FloatArray>()
        val scores = mutableListOf<Float>()

        for (contour in limitedContours) {
            // getMiniBoxes
            val (boxPoints, _, _, w, h) = getMiniBoxes(contour)
            if (boxPoints == null) continue
            if (w < DET_MIN_SIZE || h < DET_MIN_SIZE) continue

            // 概率评分
            val boxCoords = boxPoints.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }
            val score = GeometryUtils.boxScoreFast(pred, predW, predH, boxCoords)

            // box_thresh 过滤：低于阈值的候选框直接跳过
            if (score < DET_BOX_THRESH) continue

            // unclip
            val unclipBoxes = unclip(boxPoints, DET_UNCLIP_RATIO)
            for (ub in unclipBoxes) {
                val (expPts, _, _, ew, eh) = getMiniBoxes(ub.map { Point(it.x.roundToInt(), it.y.roundToInt()) })
                if (expPts == null) continue
                if (ew < DET_MIN_SIZE + 2 || eh < DET_MIN_SIZE + 2) continue

                val pts = expPts.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
                val mapped = mapBoxToOriginal(pts, predH.toFloat(), predW.toFloat(), srcH, srcW)
                boxes.add(mapped)
                scores.add(score)
            }
        }

        // 5. 过滤
        return filterDetRes(boxes, scores, srcH, srcW)
    }

    private data class BoxScoreResult(
        val boxes: List<FloatArray>,
        val scores: List<Float>
    )

    /**
     * 过滤检测结果：裁剪到图像范围 + 最小尺寸检查
     */
    private fun filterDetRes(
        boxes: List<FloatArray>,
        scores: List<Float>,
        h: Int,
        w: Int
    ): BoxScoreResult {
        val resultBoxes = mutableListOf<FloatArray>()
        val resultScores = mutableListOf<Float>()
        for (i in boxes.indices) {
            val box = boxes[i]
            val clipped = FloatArray(8)
            for (j in 0 until 4) {
                clipped[j * 2] = box[j * 2].coerceIn(0f, (w - 1).toFloat())
                clipped[j * 2 + 1] = box[j * 2 + 1].coerceIn(0f, (h - 1).toFloat())
            }

            // 计算宽度和高度
            val widthA = sqrt(((clipped[4] - clipped[6]) * (clipped[4] - clipped[6]) +
                    (clipped[5] - clipped[7]) * (clipped[5] - clipped[7])).toDouble()).toFloat()
            val widthB = sqrt(((clipped[2] - clipped[0]) * (clipped[2] - clipped[0]) +
                    (clipped[3] - clipped[1]) * (clipped[3] - clipped[1])).toDouble()).toFloat()
            val boxWidth = max(widthA, widthB)

            val heightA = sqrt(((clipped[2] - clipped[4]) * (clipped[2] - clipped[4]) +
                    (clipped[3] - clipped[5]) * (clipped[3] - clipped[5])).toDouble()).toFloat()
            val heightB = sqrt(((clipped[0] - clipped[6]) * (clipped[0] - clipped[6]) +
                    (clipped[1] - clipped[7]) * (clipped[1] - clipped[7])).toDouble()).toFloat()
            val boxHeight = max(heightA, heightB)

            if (boxWidth >= DET_MIN_SIZE && boxHeight >= DET_MIN_SIZE) {
                resultBoxes.add(clipped)
                if (i < scores.size) resultScores.add(scores[i])
            }
        }
        return BoxScoreResult(resultBoxes, resultScores)
    }

    // -----------------------------------------------------------------------
    // findContours: BFS 连通域 (对应 cv2.findContours)
    // -----------------------------------------------------------------------

    private fun findContours(
        mask: Bitmap,
        w: Int,
        h: Int
    ): List<List<Point>> {
        val visited = BooleanArray(w * h)
        val contours = mutableListOf<List<Point>>()
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)

        val dx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val dy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (visited[idx] || pixels[idx] != Color.WHITE) continue

                val component = mutableListOf<Point>()
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                visited[idx] = true

                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val cy = cur / w
                    val cx = cur % w
                    component.add(Point(cx, cy))

                    for (d in 0 until 8) {
                        val nx = cx + dx[d]
                        val ny = cy + dy[d]
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                        val nIdx = ny * w + nx
                        if (!visited[nIdx] && pixels[nIdx] == Color.WHITE) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                }

                if (component.size >= DET_MIN_SIZE) {
                    contours.add(component)
                }
            }
        }

        return contours
    }

    // -----------------------------------------------------------------------
    // getMiniBoxes: 凸包 + 最小外接矩形
    // -----------------------------------------------------------------------

    private data class MiniBoxResult(
        val points: List<PointF>?,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float
    )

    private fun getMiniBoxes(contour: List<Point>): MiniBoxResult {
        if (contour.size < 3) {
            return MiniBoxResult(null, 0f, 0f, 0f, 0f)
        }

        val ptsD = contour.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }
        val hull = GeometryUtils.convexHull(ptsD)
        if (hull.size < 3) {
            return MiniBoxResult(null, 0f, 0f, 0f, 0f)
        }

        // 旋转卡壳：遍历凸包每条边作为候选方向
        var minArea = Float.MAX_VALUE
        var bestBox: MiniBoxResult? = null

        for (i in hull.indices) {
            val j = (i + 1) % hull.size
            val edgeX = (hull[j].x - hull[i].x).toFloat()
            val edgeY = (hull[j].y - hull[i].y).toFloat()
            val edgeLen = sqrt(edgeX * edgeX + edgeY * edgeY)
            if (edgeLen < 1e-6f) continue

            val ux = edgeX / edgeLen
            val uy = edgeY / edgeLen
            val vx = -uy
            val vy = ux

            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE

            for (k in hull.indices) {
                val dx = (hull[k].x - hull[i].x).toFloat()
                val dy = (hull[k].y - hull[i].y).toFloat()
                val projU = dx * ux + dy * uy
                val projV = dx * vx + dy * vy
                if (projU < minU) minU = projU
                if (projU > maxU) maxU = projU
                if (projV < minV) minV = projV
                if (projV > maxV) maxV = projV
            }

            val w = maxU - minU
            val h = maxV - minV
            val area = w * h

            if (area < minArea) {
                minArea = area
                val midU = (minU + maxU) / 2
                val midV = (minV + maxV) / 2
                val cx = hull[i].x.toFloat() + midU * ux + midV * vx
                val cy = hull[i].y.toFloat() + midU * uy + midV * vy

                // 构建四角点并排序：TL, TR, BR, BL
                val corners = arrayOf(
                    PointF(cx - w / 2 * ux - h / 2 * vx, cy - w / 2 * uy - h / 2 * vy),
                    PointF(cx + w / 2 * ux - h / 2 * vx, cy + w / 2 * uy - h / 2 * vy),
                    PointF(cx + w / 2 * ux + h / 2 * vx, cy + w / 2 * uy + h / 2 * vy),
                    PointF(cx - w / 2 * ux + h / 2 * vx, cy - w / 2 * uy + h / 2 * vy)
                )
                val sorted = orderPointsClockwise(corners)
                bestBox = MiniBoxResult(sorted.toList(), cx, cy, w, h)
            }
        }

        return bestBox ?: MiniBoxResult(null, 0f, 0f, 0f, 0f)
    }

    // -----------------------------------------------------------------------
    // orderPointsClockwise: 排序四角点 → TL, TR, BR, BL
    // -----------------------------------------------------------------------

    private fun orderPointsClockwise(pts: Array<PointF>): Array<PointF> {
        val rect = arrayOfNulls<PointF>(4)

        // TL: 最小和
        val s = FloatArray(4) { pts[it].x + pts[it].y }
        rect[0] = pts[s.indices.minByOrNull { s[it] }!!]
        // BR: 最大和
        rect[2] = pts[s.indices.maxByOrNull { s[it] }!!]

        // TR: 最小差 (y - x)
        val d = FloatArray(4) { pts[it].y - pts[it].x }
        rect[1] = pts[d.indices.minByOrNull { d[it] }!!]
        // BL: 最大差
        rect[3] = pts[d.indices.maxByOrNull { d[it] }!!]

        @Suppress("UNCHECKED_CAST")
        return rect as Array<PointF>
    }

    // -----------------------------------------------------------------------
    // unclip: Vatti unclip (JTS BufferOp)
    // -----------------------------------------------------------------------

    private fun unclip(box: List<PointF>, unclipRatio: Double): List<List<Coordinate>> {
        val area = polygonArea(box)
        val perimeter = polygonPerimeter(box)
        if (perimeter < 1e-6) return emptyList()

        val distance = area * unclipRatio / perimeter

        val factory = GeometryFactory()
        val coords = Array(box.size + 1) { i ->
            if (i < box.size) Coordinate(box[i].x.toDouble(), box[i].y.toDouble())
            else Coordinate(box[0].x.toDouble(), box[0].y.toDouble()) // close ring
        }
        val poly = try {
            factory.createPolygon(coords)
        } catch (e: Exception) {
            return emptyList()
        }

        val buffered = try {
            BufferOp.bufferOp(poly, distance)
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()

        if (buffered.isEmpty) return emptyList()

        val result = mutableListOf<List<Coordinate>>()
        for (i in 0 until buffered.numGeometries) {
            val coordsArr = buffered.getGeometryN(i).coordinates
            result.add(coordsArr.toList())
        }
        return result
    }

    private fun polygonArea(box: List<PointF>): Double {
        var area = 0.0
        val n = box.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += box[i].x * box[j].y - box[j].x * box[i].y
        }
        return abs(area) / 2.0
    }

    private fun polygonPerimeter(box: List<PointF>): Double {
        var peri = 0.0
        val n = box.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = (box[j].x - box[i].x).toDouble()
            val dy = (box[j].y - box[i].y).toDouble()
            peri += sqrt(dx * dx + dy * dy)
        }
        return peri
    }

    // ========================================================================
    // getRotateCropImage (utils/process_img.py)
    // ========================================================================

    /**
     * 透视裁剪 + 自动旋转竖排文字。
     *
     * @param bitmap 原图
     * @param points 4 个顶点 [TL, TR, BR, BL]（原图坐标）
     * @return 裁剪后的正向文字图片
     */
    fun getRotateCropImage(bitmap: Bitmap, points: Array<PointF>): Bitmap {
        // 1. 计算目标尺寸
        val tl = points[0]; val tr = points[1]
        val br = points[2]; val bl = points[3]

        val widthA = sqrt(((br.x - bl.x) * (br.x - bl.x) + (br.y - bl.y) * (br.y - bl.y)).toDouble()).toFloat()
        val widthB = sqrt(((tr.x - tl.x) * (tr.x - tl.x) + (tr.y - tl.y) * (tr.y - tl.y)).toDouble()).toFloat()
        val maxWidth = max(widthA, widthB).roundToInt().coerceIn(4, bitmap.width)
        val heightA = sqrt(((tr.x - br.x) * (tr.x - br.x) + (tr.y - br.y) * (tr.y - br.y)).toDouble()).toFloat()
        val heightB = sqrt(((tl.x - bl.x) * (tl.x - bl.x) + (tl.y - bl.y) * (tl.y - bl.y)).toDouble()).toFloat()
        val maxHeight = max(heightA, heightB).roundToInt().coerceIn(4, bitmap.height)

        // 2. 使用 Android Canvas + Matrix 做透视裁剪（硬件加速，替代纯 Java 像素循环）
        //    setPolyToPoly: src 点 → dst 点，drawBitmap 时反向映射
        val srcPts = floatArrayOf(
            tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y
        )
        val dstPts = floatArrayOf(
            0f, 0f, (maxWidth - 1).toFloat(), 0f,
            (maxWidth - 1).toFloat(), (maxHeight - 1).toFloat(), 0f, (maxHeight - 1).toFloat()
        )
        val matrix = android.graphics.Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

        val cropImg = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cropImg)
        canvas.drawBitmap(bitmap, matrix, null)

        // 3. 竖排文字自动旋转 90° CCW
        if (cropImg.height >= cropImg.width * 1.5f) {
            val rotMatrix = android.graphics.Matrix().apply { setRotate(-90f) }
            val rotated = Bitmap.createBitmap(cropImg, 0, 0, cropImg.width, cropImg.height, rotMatrix, true)
            if (rotated !== cropImg) cropImg.recycle()
            return rotated
        }

        return cropImg
    }

    // -----------------------------------------------------------------------
    // DLT 透视变换矩阵 (4 点对应)
    // -----------------------------------------------------------------------

    private fun computePerspectiveTransform(
        src: Array<FloatArray>,
        dst: Array<FloatArray>
    ): Array<FloatArray>? {
        // 构建 8×8 线性系统 Ah = b
        val A = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)

        for (i in 0 until 4) {
            val x = src[i][0].toDouble()
            val y = src[i][1].toDouble()
            val u = dst[i][0].toDouble()
            val v = dst[i][1].toDouble()

            A[i * 2][0] = x;     A[i * 2][1] = y;     A[i * 2][2] = 1.0
            A[i * 2][3] = 0.0;   A[i * 2][4] = 0.0;   A[i * 2][5] = 0.0
            A[i * 2][6] = -u * x; A[i * 2][7] = -u * y
            b[i * 2] = u

            A[i * 2 + 1][0] = 0.0;   A[i * 2 + 1][1] = 0.0;   A[i * 2 + 1][2] = 0.0
            A[i * 2 + 1][3] = x;     A[i * 2 + 1][4] = y;     A[i * 2 + 1][5] = 1.0
            A[i * 2 + 1][6] = -v * x; A[i * 2 + 1][7] = -v * y
            b[i * 2 + 1] = v
        }

        val h = solveLinearSystem(A, b) ?: return null

        return arrayOf(
            floatArrayOf(h[0].toFloat(), h[1].toFloat(), h[2].toFloat()),
            floatArrayOf(h[3].toFloat(), h[4].toFloat(), h[5].toFloat()),
            floatArrayOf(h[6].toFloat(), h[7].toFloat(), 1f)
        )
    }

    // -----------------------------------------------------------------------
    // 高斯消元（列主元）
    // -----------------------------------------------------------------------

    private fun solveLinearSystem(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        // 增广矩阵 [A|b]
        val aug = Array(n) { i ->
            DoubleArray(n + 1) { j -> if (j < n) A[i][j] else b[i] }
        }

        for (col in 0 until n) {
            // 列主元
            var maxVal = abs(aug[col][col])
            var maxRow = col
            for (row in col + 1 until n) {
                if (abs(aug[row][col]) > maxVal) {
                    maxVal = abs(aug[row][col])
                    maxRow = row
                }
            }
            if (maxVal < 1e-10) return null

            if (maxRow != col) {
                val tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp
            }

            // 消元
            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (j in col..n) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }

        // 回代
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            x[i] = aug[i][n]
            for (j in i + 1 until n) {
                x[i] -= aug[i][j] * x[j]
            }
            x[i] /= aug[i][i]
        }

        return x
    }

    // -----------------------------------------------------------------------
    // 逆透视映射 + 双线性插值
    // -----------------------------------------------------------------------

    private fun warpPerspective(
        src: Bitmap,
        H: Array<FloatArray>,
        outW: Int,
        outH: Int
    ): Bitmap {
        // 计算 H 的逆矩阵
        val Hinv = invertMatrix3x3(H) ?: return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

        val dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val srcW = src.width
        val srcH = src.height

        // 读取源图像素
        val srcPixels = IntArray(srcW * srcH)
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val dstPixels = IntArray(outW * outH)

        for (y in 0 until outH) {
            for (x in 0 until outW) {
                // 逆映射：dst(x,y) → src(sx, sy)
                val wCoord = Hinv[2][0] * x + Hinv[2][1] * y + Hinv[2][2]
                if (abs(wCoord) < 1e-10f) continue

                val sx = (Hinv[0][0] * x + Hinv[0][1] * y + Hinv[0][2]) / wCoord
                val sy = (Hinv[1][0] * x + Hinv[1][1] * y + Hinv[1][2]) / wCoord

                // 双线性插值
                val x0 = sx.toInt(); val y0 = sy.toInt()
                val x1 = x0 + 1; val y1 = y0 + 1
                val fx = sx - x0; val fy = sy - y0

                if (x0 < 0 || x1 >= srcW || y0 < 0 || y1 >= srcH) continue

                val p00 = srcPixels[y0 * srcW + x0]
                val p10 = srcPixels[y0 * srcW + x1]
                val p01 = srcPixels[y1 * srcW + x0]
                val p11 = srcPixels[y1 * srcW + x1]

                val r = ((1 - fx) * (1 - fy) * (p00 shr 16 and 0xFF) +
                        fx * (1 - fy) * (p10 shr 16 and 0xFF) +
                        (1 - fx) * fy * (p01 shr 16 and 0xFF) +
                        fx * fy * (p11 shr 16 and 0xFF)).roundToInt().coerceIn(0, 255)
                val g = ((1 - fx) * (1 - fy) * (p00 shr 8 and 0xFF) +
                        fx * (1 - fy) * (p10 shr 8 and 0xFF) +
                        (1 - fx) * fy * (p01 shr 8 and 0xFF) +
                        fx * fy * (p11 shr 8 and 0xFF)).roundToInt().coerceIn(0, 255)
                val b = ((1 - fx) * (1 - fy) * (p00 and 0xFF) +
                        fx * (1 - fy) * (p10 and 0xFF) +
                        (1 - fx) * fy * (p01 and 0xFF) +
                        fx * fy * (p11 and 0xFF)).roundToInt().coerceIn(0, 255)

                dstPixels[y * outW + x] = Color.rgb(r, g, b)
            }
        }

        dst.setPixels(dstPixels, 0, outW, 0, 0, outW, outH)
        return dst
    }

    // -----------------------------------------------------------------------
    // 3×3 矩阵求逆（余子式法）
    // -----------------------------------------------------------------------

    private fun invertMatrix3x3(m: Array<FloatArray>): Array<FloatArray>? {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
        val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
        val g = m[2][0]; val h = m[2][1]; val i = m[2][2]

        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-10f) return null

        val invDet = 1.0f / det
        return arrayOf(
            floatArrayOf((e * i - f * h) * invDet, (c * h - b * i) * invDet, (b * f - c * e) * invDet),
            floatArrayOf((f * g - d * i) * invDet, (a * i - c * g) * invDet, (c * d - a * f) * invDet),
            floatArrayOf((d * h - e * g) * invDet, (b * g - a * h) * invDet, (a * e - b * d) * invDet)
        )
    }

    // ========================================================================
    // Cls (ch_ppocr_cls/main.py)
    // ========================================================================

    /**
     * 方向分类 + 自动旋转。
     *
     * @param imgList 待分类图片列表
     * @return 分类结果列表（label="0" 或 "180"）
     */
    fun clsAndRotate(imgList: List<Bitmap>): List<ClsResult> {
        val session = clsSession ?: return imgList.map { ClsResult("0", 1.0f) }
        if (imgList.isEmpty()) return emptyList()

        val t0 = System.currentTimeMillis()

        // 按宽度降序排列（减少 batch padding）
        val indices = imgList.indices.sortedByDescending { imgList[it].width }
        val sortedImgs = indices.map { imgList[it] }

        // 预处理
        val preprocessed = sortedImgs.map { clsResizeNormImg(it) }
        val batchSize = preprocessed.size
        val channelSize = CLS_IMAGE_SHAPE[1] * CLS_IMAGE_SHAPE[2] // 80 * 160
        val totalSize = batchSize * 3 * channelSize

        val buffer = FloatBuffer.allocate(totalSize)
        for (arr in preprocessed) {
            buffer.put(arr)
        }
        buffer.rewind()

        val inputTensor = OnnxTensor.createTensor(
            ortEnv!!, buffer,
            longArrayOf(batchSize.toLong(), 3, CLS_IMAGE_SHAPE[1].toLong(), CLS_IMAGE_SHAPE[2].toLong())
        )

        // 推理
        val results = session.run(mapOf("x" to inputTensor))
        inputTensor.close()

        var outputData: FloatArray
        try {
            var outputTensor: OnnxTensor? = null
            for (name in session.outputNames) {
                val value = results.get(name)
                if (value.isPresent && value.get() is OnnxTensor) {
                    outputTensor = value.get() as OnnxTensor
                    break
                }
            }
            outputData = outputTensor!!.floatBuffer.array()
            outputTensor.close()
        } finally {
            results.close()
        }

        // 后处理：恢复原始顺序
        val sortedResults = (0 until batchSize).map { i ->
            val start = i * 2
            val end = (i + 1) * 2
            val probs = if (end <= outputData.size) {
                outputData.sliceArray(start until end)
            } else {
                LogCollector.w(TAG, "cls 输出不足: batchSize=$batchSize, outputSize=${outputData.size}, i=$i")
                floatArrayOf(1f, 0f) // 默认不旋转
            }
            clsPostProcess(probs)
        }

        val finalResults = MutableList(batchSize) { ClsResult("0", 0f) }
        for (i in indices.indices) {
            finalResults[indices[i]] = ClsResult(sortedResults[i].first, sortedResults[i].second)
        }

        // 旋转 180° 由 runOCR 处理（此处仅返回分类结果）

        LogCollector.d(TAG, "clsAndRotate: ${batchSize} 张, 耗时 ${System.currentTimeMillis() - t0}ms")
        return finalResults
    }

    /**
     * Cls 预处理：resize + pad + normalize [-1, 1]
     */
    private fun clsResizeNormImg(bitmap: Bitmap): FloatArray {
        val imgC = CLS_IMAGE_SHAPE[0] // 3
        val imgH = CLS_IMAGE_SHAPE[1] // 80
        val imgW = CLS_IMAGE_SHAPE[2] // 160

        val ratio = bitmap.width.toFloat() / bitmap.height
        var resizeW = ceil(imgH * ratio).toInt()
        if (resizeW > imgW) resizeW = imgW

        val resized = Bitmap.createScaledBitmap(bitmap, resizeW, imgH, true)

        // CHW, pad to (3, 80, 160), normalize [-1, 1]
        val floatArr = FloatArray(imgC * imgH * imgW)
        val pixels = IntArray(resizeW * imgH)
        resized.getPixels(pixels, 0, resizeW, 0, 0, resizeW, imgH)
        if (resized !== bitmap) resized.recycle()

        for (c in 0 until 3) {
            val cOffset = c * imgH * imgW
            for (y in 0 until imgH) {
                for (x in 0 until resizeW) {
                    val pixel = pixels[y * resizeW + x]
                    val v = when (c) {
                        0 -> (pixel shr 16 and 0xFF) / 255.0f
                        1 -> (pixel shr 8 and 0xFF) / 255.0f
                        2 -> (pixel and 0xFF) / 255.0f
                        else -> 0f
                    }
                    floatArr[cOffset + y * imgW + x] = (v - 0.5f) / 0.5f
                }
                // 右侧 padding 保持 0
            }
        }

        return floatArr
    }

    /**
     * Cls 后处理：argmax → ("0"/"180", score)
     */
    private fun clsPostProcess(probs: FloatArray): Pair<String, Float> {
        val label = if (probs[0] > probs[1]) "0" else "180"
        val score = max(probs[0], probs[1])
        return Pair(label, score)
    }

    // ========================================================================
    // Rec (ch_ppocr_rec/main.py + CTCLabelDecode)
    // ========================================================================

    /**
     * 批量识别（按 wh_ratio 分组，batch 推理）。
     */
    fun recognizeBatch(context: Context, imgList: List<Bitmap>, lang: RecLang): List<RecResult> {
        if (imgList.isEmpty()) return emptyList()

        val dict = dictionary[lang] ?: return imgList.map { RecResult("", 0f) }
        val session = getRecSession(context, lang) ?: return imgList.map { RecResult("", 0f) }

        val t0 = System.currentTimeMillis()
        val allResults = mutableListOf<RecResult>()

        var i = 0
        while (i < imgList.size) {
            val batchEnd = min(i + REC_BATCH_NUM, imgList.size)
            val batch = imgList.subList(i, batchEnd)

            // 找 batch 内最大 wh_ratio
            var maxWhRatio = 0f
            for (img in batch) {
                val r = img.width.toFloat() / img.height
                if (r > maxWhRatio) maxWhRatio = r
            }

            // 预处理
            val preprocessed = batch.map { recResizeNormImg(it, maxWhRatio) }
            val batchSize = preprocessed.size
            val recImgW = (REC_IMG_HEIGHT * maxWhRatio).roundToInt().coerceAtLeast(1)
            val channelSize = REC_IMG_HEIGHT * recImgW
            val totalSize = batchSize * REC_IMG_CHANNELS * channelSize

            val buffer = FloatBuffer.allocate(totalSize)
            for (arr in preprocessed) {
                buffer.put(arr)
            }
            buffer.rewind()

            val inputTensor = OnnxTensor.createTensor(
                ortEnv!!, buffer,
                longArrayOf(batchSize.toLong(), REC_IMG_CHANNELS.toLong(), REC_IMG_HEIGHT.toLong(), recImgW.toLong())
            )

            // 推理
            val results = session.run(mapOf("x" to inputTensor))
            inputTensor.close()

            var outputData: FloatArray
            var seqLen: Int
            var numClasses: Int
            try {
                var batchOutputTensor: OnnxTensor? = null
                for (name in session.outputNames) {
                    val value = results.get(name)
                    if (value.isPresent && value.get() is OnnxTensor) {
                        batchOutputTensor = value.get() as OnnxTensor
                        break
                    }
                }
                outputData = batchOutputTensor!!.floatBuffer.array()
                val outputShape = batchOutputTensor.info.shape // [batch, seq_len, num_classes]
                batchOutputTensor.close()
                seqLen = outputShape[1].toInt()
                numClasses = outputShape[2].toInt()
            } finally {
                results.close()
            }

            // CTCLabelDecode per sample
            for (j in 0 until batchSize) {
                val start = j * seqLen * numClasses
                val preds = outputData.sliceArray(start until start + seqLen * numClasses)
                val (text, score) = ctcLabelDecode(preds, seqLen, numClasses, dict)
                allResults.add(RecResult(text, score))
            }

            i = batchEnd
        }

        LogCollector.d(TAG, "recognizeBatch: ${imgList.size} 张, lang=${lang.code}, 耗时 ${System.currentTimeMillis() - t0}ms")
        return allResults
    }

    /**
     * Rec 预处理：resize + pad + normalize [-1, 1]
     */
    private fun recResizeNormImg(bitmap: Bitmap, maxWhRatio: Float): FloatArray {
        val imgC = REC_IMG_CHANNELS // 3
        val imgH = REC_IMG_HEIGHT   // 48
        val imgW = (imgH * maxWhRatio).roundToInt().coerceAtLeast(1)

        val ratio = bitmap.width.toFloat() / bitmap.height
        var resizeW = ceil(imgH * ratio).toInt()
        if (resizeW > imgW) resizeW = imgW

        val resized = Bitmap.createScaledBitmap(bitmap, resizeW, imgH, true)

        // CHW, pad to (3, 48, imgW), normalize [-1, 1]
        val floatArr = FloatArray(imgC * imgH * imgW)
        val pixels = IntArray(resizeW * imgH)
        resized.getPixels(pixels, 0, resizeW, 0, 0, resizeW, imgH)
        if (resized !== bitmap) resized.recycle()

        for (c in 0 until 3) {
            val cOffset = c * imgH * imgW
            for (y in 0 until imgH) {
                for (x in 0 until resizeW) {
                    val pixel = pixels[y * resizeW + x]
                    val v = when (c) {
                        0 -> (pixel shr 16 and 0xFF) / 255.0f
                        1 -> (pixel shr 8 and 0xFF) / 255.0f
                        2 -> (pixel and 0xFF) / 255.0f
                        else -> 0f
                    }
                    floatArr[cOffset + y * imgW + x] = (v - 0.5f) / 0.5f
                }
            }
        }

        return floatArr
    }

    /**
     * CTCLabelDecode：argmax → remove_duplicate → filter blank(0) → join
     *
     * @param preds 扁平化预测 [seq_len * num_classes]
     * @param seqLen 序列长度
     * @param numClasses 类别数（= 字典大小）
     * @param dict 字典 [blank, char1, char2, ..., space]
     */
    private fun ctcLabelDecode(
        preds: FloatArray,
        seqLen: Int,
        numClasses: Int,
        dict: List<String>
    ): Pair<String, Float> {
        // 1. argmax
        val charIndices = IntArray(seqLen)
        val confidences = FloatArray(seqLen)
        for (t in 0 until seqLen) {
            val offset = t * numClasses
            var bestIdx = 0
            var bestProb = preds[offset]
            for (c in 1 until numClasses) {
                if (preds[offset + c] > bestProb) {
                    bestProb = preds[offset + c]
                    bestIdx = c
                }
            }
            charIndices[t] = bestIdx
            confidences[t] = bestProb
        }

        // 2. remove_duplicate + filter blank(0)
        val text = StringBuilder()
        var totalScore = 0f
        var count = 0
        var prevIdx = -1

        for (t in 0 until seqLen) {
            val idx = charIndices[t]
            if (idx > 0 && idx != prevIdx) {
                if (idx < dict.size) {
                    text.append(dict[idx])
                    totalScore += confidences[t]
                    count++
                }
            }
            prevIdx = idx
        }

        // 3. 过滤纯空格
        val resultText = text.toString()
        if (resultText.isBlank()) return Pair("", 0f)

        val avgScore = if (count > 0) totalScore / count else 0f
        return Pair(resultText, avgScore)
    }

    // ========================================================================
    // 主 pipeline
    // ========================================================================

    /**
     * 运行完整 OCR 流水线。
     *
     * @param bitmap 输入图片
     * @param recLang 识别语言
     * @param useDet 是否使用检测（false 则全图识别）
     * @param useCls 是否使用方向分类
     * @param returnWordBox 是否返回字符级框
     * @return OCR 结果
     */
    fun runOCR(
        context: Context,
        bitmap: Bitmap,
        recLang: RecLang = RecLang.ZH,
        useDet: Boolean = true,
        useCls: Boolean = true,
        returnWordBox: Boolean = false
    ): OcrResult {
        if (!isInitialized) throw IllegalStateException("PPOcrV5Engine 未初始化")
        if (bitmap.isRecycled) throw IllegalArgumentException("Bitmap 已回收")

        val t0 = System.currentTimeMillis()

        // 1. Det
        val (boxes, detTime) = if (useDet && detSession != null) {
            val dt = System.currentTimeMillis()
            val det = runDet(bitmap)
            Pair(det, System.currentTimeMillis() - dt)
        } else {
            // 无检测：全图作为一个 box
            Pair(listOf(floatArrayOf(
                0f, 0f,
                (bitmap.width - 1).toFloat(), 0f,
                (bitmap.width - 1).toFloat(), (bitmap.height - 1).toFloat(),
                0f, (bitmap.height - 1).toFloat()
            )), 0L)
        }

        // 2. Crop + Cls + Rec
        val clsTime: Long
        val recTime: Long
        val allTexts = mutableListOf<String>()
        val allScores = mutableListOf<Float>()
        val allWordResults = mutableListOf<WordResult>()

        // 2a. 裁剪
        val cropT0 = System.currentTimeMillis()
        val cropResults = mutableListOf<Triple<Bitmap, FloatArray, Int>>() // crop, box, originalIndex

        for ((idx, box) in boxes.withIndex()) {
            val pts = boxToQuadPoints(box)
            try {
                val crop = getRotateCropImage(bitmap, pts)
                cropResults.add(Triple(crop, box, idx))
            } catch (e: Exception) {
                LogCollector.w(TAG, "裁剪失败 box[$idx]: ${e.message}")
            }
        }
        val cropTime = System.currentTimeMillis() - cropT0

        // 2b. Cls
        val clsT0 = System.currentTimeMillis()
        val clsResults = if (useCls && clsSession != null && cropResults.isNotEmpty()) {
            clsAndRotate(cropResults.map { it.first })
        } else {
            cropResults.map { ClsResult("0", 1.0f) }
        }
        clsTime = System.currentTimeMillis() - clsT0

        // 应用 cls 旋转
        for (i in cropResults.indices) {
            if (i < clsResults.size && clsResults[i].label == "180" && clsResults[i].score > CLS_THRESH) {
                val (crop, box, idx) = cropResults[i]
                val matrix = android.graphics.Matrix().apply { setRotate(180f) }
                val rotated = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, matrix, true)
                if (rotated !== crop) crop.recycle()
                cropResults[i] = Triple(rotated, box, idx)
            }
        }

        // 2c. Rec
        val recT0 = System.currentTimeMillis()
        if (cropResults.isNotEmpty()) {
            val recResults = recognizeBatch(context, cropResults.map { it.first }, recLang)
            for (i in cropResults.indices) {
                if (i < recResults.size) {
                    allTexts.add(recResults[i].text)
                    allScores.add(recResults[i].score)

                    if (returnWordBox) {
                        allWordResults.add(calcWordBoxes(cropResults[i].first, recResults[i], recLang))
                    }
                } else {
                    allTexts.add("")
                    allScores.add(0f)
                    if (returnWordBox) allWordResults.add(WordResult("", 0f, emptyList()))
                }
            }
        }
        recTime = System.currentTimeMillis() - recT0

        // 3. 过滤低置信度
        val filtered = filterByTextScore(boxes, allTexts, allScores, allWordResults)

        // 4. 释放裁剪图片
        for ((crop, _, _) in cropResults) {
            if (!crop.isRecycled) crop.recycle()
        }

        val totalTime = System.currentTimeMillis() - t0
        val elapseList = listOf(
            detTime / 1000f,
            cropTime / 1000f,
            clsTime / 1000f,
            recTime / 1000f,
            totalTime / 1000f
        )

        LogCollector.d(TAG, "runOCR: det=${boxes.size}(${detTime}ms), crop=${cropTime}ms, " +
                "cls=${clsTime}ms, rec=${allTexts.size}(${recTime}ms), total=${totalTime}ms")

        return OcrResult(
            boxes = filtered.boxes,
            texts = filtered.texts,
            scores = filtered.scores,
            wordResults = filtered.wordResults,
            elapseList = elapseList
        )
    }

    /**
     * 批量识别（含 cls 方向分类）。
     * 用于增量渲染场景：对已裁剪图片执行 cls + rec。
     *
     * @param context Context
     * @param imgList 已裁剪图片列表
     * @param recLang 识别语言
     * @return 识别结果列表
     */
    fun recognizeBatchWithCls(context: Context, imgList: List<Bitmap>, lang: RecLang): List<RecResult> {
        if (imgList.isEmpty()) return emptyList()

        val dict = dictionary[lang] ?: return imgList.map { RecResult("", 0f) }
        val session = getRecSession(context, lang) ?: return imgList.map { RecResult("", 0f) }

        val t0 = System.currentTimeMillis()

        // 1. Cls 方向分类
        val clsResults = clsAndRotate(imgList)
        val processedImgs = imgList.mapIndexed { i, bmp ->
            if (i < clsResults.size && clsResults[i].label == "180" && clsResults[i].score > CLS_THRESH) {
                val matrix = android.graphics.Matrix().apply { setRotate(180f) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                rotated
            } else {
                bmp
            }
        }

        // 2. Rec 批量识别
        val allResults = mutableListOf<RecResult>()
        var i = 0
        while (i < processedImgs.size) {
            val batchEnd = min(i + REC_BATCH_NUM, processedImgs.size)
            val batch = processedImgs.subList(i, batchEnd)

            var maxWhRatio = 0f
            for (img in batch) {
                val r = img.width.toFloat() / img.height
                if (r > maxWhRatio) maxWhRatio = r
            }

            val preprocessed = batch.map { recResizeNormImg(it, maxWhRatio) }
            val batchSize = preprocessed.size
            val recImgW = (REC_IMG_HEIGHT * maxWhRatio).roundToInt().coerceAtLeast(1)
            val channelSize = REC_IMG_HEIGHT * recImgW
            val totalSize = batchSize * REC_IMG_CHANNELS * channelSize

            val buffer = FloatBuffer.allocate(totalSize)
            for (arr in preprocessed) { buffer.put(arr) }
            buffer.rewind()

            val inputTensor = OnnxTensor.createTensor(
                ortEnv!!, buffer,
                longArrayOf(batchSize.toLong(), REC_IMG_CHANNELS.toLong(), REC_IMG_HEIGHT.toLong(), recImgW.toLong())
            )

            val results = session.run(mapOf("x" to inputTensor))
            inputTensor.close()

            var outputData: FloatArray
            var seqLen: Int
            var numClasses: Int
            try {
                var batchOutputTensor: OnnxTensor? = null
                for (name in session.outputNames) {
                    val value = results.get(name)
                    if (value.isPresent && value.get() is OnnxTensor) {
                        batchOutputTensor = value.get() as OnnxTensor
                        break
                    }
                }
                outputData = batchOutputTensor!!.floatBuffer.array()
                val outputShape = batchOutputTensor.info.shape
                batchOutputTensor.close()
                seqLen = outputShape[1].toInt()
                numClasses = outputShape[2].toInt()
            } finally {
                results.close()
            }

            for (j in 0 until batchSize) {
                val start = j * seqLen * numClasses
                val preds = outputData.sliceArray(start until start + seqLen * numClasses)
                val (text, score) = ctcLabelDecode(preds, seqLen, numClasses, dict)
                allResults.add(RecResult(text, score))
            }

            i = batchEnd
        }

        // 释放旋转产生的额外 Bitmap（非原始输入的）
        for ((idx, img) in processedImgs.withIndex()) {
            if (img !== imgList[idx]) img.recycle()
        }

        LogCollector.d(TAG, "recognizeBatchWithCls: ${imgList.size} 张, lang=${lang.code}, 耗时 ${System.currentTimeMillis() - t0}ms")
        return allResults
    }

    /**
     * 公开检测方法：返回原始坐标系的 box 数组。
     * 用于增量渲染场景的 det 阶段。
     */
    fun runDetForBoxes(bitmap: Bitmap): List<FloatArray> {
        if (!isInitialized) throw IllegalStateException("PPOcrV5Engine 未初始化")
        val boxes = runDet(bitmap)
        LogCollector.d(TAG, "runDetForBoxes: ${boxes.size} 个文字行")
        return boxes
    }

    /**
     * 公开 boxToQuadPoints：将 8 元素 box 数组转为 4 点。
     */
    fun boxToQuadPointsPublic(box: FloatArray): Array<PointF> = boxToQuadPoints(box)

    /**
     * 运行检测，返回原始坐标系的 box 数组。
     */
    private fun runDet(bitmap: Bitmap): List<FloatArray> {
        val (input, detH, detW) = preprocessDet(bitmap)
        LogCollector.d(TAG, "det input: ${bitmap.width}x${bitmap.height} → ${detW}x${detH}")

        val buffer = FloatBuffer.wrap(input)
        val inputTensor = OnnxTensor.createTensor(
            ortEnv!!, buffer,
            longArrayOf(1, 3, detH.toLong(), detW.toLong())
        )

        val results = detSession!!.run(mapOf("x" to inputTensor))
        inputTensor.close()

        try {
            var detOutputTensor: OnnxTensor? = null
            for (name in detSession!!.outputNames) {
                val value = results.get(name)
                if (value.isPresent && value.get() is OnnxTensor) {
                    detOutputTensor = value.get() as OnnxTensor
                    break
                }
            }
            val outputData = detOutputTensor!!.floatBuffer.array()
            val outputShape = detOutputTensor.info.shape // [1, 1, H, W]
            detOutputTensor.close()

            val predH = outputShape[2].toInt()
            val predW = outputShape[3].toInt()
            val pred = outputData.sliceArray(0 until predH * predW)

            return postprocessDet(pred, predH, predW, bitmap.height, bitmap.width).boxes
        } finally {
            results.close()
        }
    }

    /**
     * 将 8 元素 box 数组转为 4 点 [TL, TR, BR, BL]
     */
    private fun boxToQuadPoints(box: FloatArray): Array<PointF> {
        return arrayOf(
            PointF(box[0], box[1]),
            PointF(box[2], box[3]),
            PointF(box[4], box[5]),
            PointF(box[6], box[7])
        )
    }

    /**
     * 将 4 点映射回原图坐标系（pred 空间 → 原图空间）
     */
    private fun mapBoxToOriginal(
        pts: Array<PointF>,
        predH: Float,
        predW: Float,
        srcH: Int,
        srcW: Int
    ): FloatArray {
        val ratioH = srcH.toFloat() / predH
        val ratioW = srcW.toFloat() / predW

        return floatArrayOf(
            (pts[0].x * ratioW).coerceIn(0f, (srcW - 1).toFloat()),
            (pts[0].y * ratioH).coerceIn(0f, (srcH - 1).toFloat()),
            (pts[1].x * ratioW).coerceIn(0f, (srcW - 1).toFloat()),
            (pts[1].y * ratioH).coerceIn(0f, (srcH - 1).toFloat()),
            (pts[2].x * ratioW).coerceIn(0f, (srcW - 1).toFloat()),
            (pts[2].y * ratioH).coerceIn(0f, (srcH - 1).toFloat()),
            (pts[3].x * ratioW).coerceIn(0f, (srcW - 1).toFloat()),
            (pts[3].y * ratioH).coerceIn(0f, (srcH - 1).toFloat())
        )
    }

    // -----------------------------------------------------------------------
    // filterByTextScore
    // -----------------------------------------------------------------------

    private fun filterByTextScore(
        boxes: List<FloatArray>,
        texts: List<String>,
        scores: List<Float>,
        wordResults: List<WordResult>
    ): FilteredResult {
        val fBoxes = mutableListOf<FloatArray>()
        val fTexts = mutableListOf<String>()
        val fScores = mutableListOf<Float>()
        val fWordResults = mutableListOf<WordResult>()

        val n = min(boxes.size, min(texts.size, scores.size))
        for (i in 0 until n) {
            if (scores[i] > TEXT_SCORE_THRESH) {
                fBoxes.add(boxes[i])
                fTexts.add(texts[i])
                fScores.add(scores[i])
                if (i < wordResults.size) fWordResults.add(wordResults[i])
            }
        }

        return FilteredResult(fBoxes, fTexts, fScores, fWordResults)
    }

    private data class FilteredResult(
        val boxes: List<FloatArray>,
        val texts: List<String>,
        val scores: List<Float>,
        val wordResults: List<WordResult>
    )

    // ========================================================================
    // visRes: 可视化
    // ========================================================================

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 24f
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /**
     * 在图片上绘制检测框和文字标签。
     */
    fun visRes(bitmap: Bitmap, result: OcrResult): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        for (i in result.boxes.indices) {
            val box = result.boxes[i]

            // 绘制四边形
            canvas.drawLine(box[0], box[1], box[2], box[3], boxPaint)
            canvas.drawLine(box[2], box[3], box[4], box[5], boxPaint)
            canvas.drawLine(box[4], box[5], box[6], box[7], boxPaint)
            canvas.drawLine(box[6], box[7], box[0], box[1], boxPaint)

            // 文字标签
            if (i < result.texts.size) {
                val text = result.texts[i]
                val score = if (i < result.scores.size) result.scores[i] else 0f
                val label = "$text ${String.format("%.2f", score)}"

                val textX = box[0]
                val textY = box[1] - 5f

                // 背景
                val textWidth = textPaint.measureText(label)
                canvas.drawRect(
                    textX, textY - textPaint.textSize,
                    textX + textWidth, textY + 5f,
                    bgPaint
                )
                canvas.drawText(label, textX, textY, textPaint)
            }
        }

        return output
    }

    // ========================================================================
    // calcWordBoxes: 字符级框
    // ========================================================================

    /**
     * 基于字符宽度平均分割的单词框计算。
     */
    @Suppress("UNUSED_PARAMETER")
    fun calcWordBoxes(
        img: Bitmap,
        recResult: RecResult,
        lang: RecLang
    ): WordResult {
        val text = recResult.text
        if (text.isEmpty()) return WordResult(text, recResult.score, emptyList())

        val charBoxes = mutableListOf<CharBox>()
        val w = img.width.toFloat()
        val h = img.height.toFloat()
        val charWidth = w / text.length

        for (i in text.indices) {
            val x0 = i * charWidth
            val x1 = (i + 1) * charWidth
            charBoxes.add(CharBox(
                text[i].toString(),
                recResult.score,
                floatArrayOf(x0, 0f, x1, 0f, x1, h, x0, h)
            ))
        }

        return WordResult(text, recResult.score, charBoxes)
    }

    // ========================================================================
    // applyVerticalPadding: 长图 padding
    // ========================================================================

    /**
     * 对宽高比过大的图片添加上下 padding（改善检测效果）。
     *
     * @param bitmap 原图
     * @param widthHeightRatio 宽高比阈值（W/H > ratio 时添加 padding）
     * @param minHeight 最小高度（像素）
     * @return Triple(paddedBitmap, paddingTop, paddingLeft)
     */
    fun applyVerticalPadding(
        bitmap: Bitmap,
        widthHeightRatio: Float = 3.0f,
        minHeight: Int = 30
    ): Triple<Bitmap, Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val ratio = w.toFloat() / h

        if (ratio <= widthHeightRatio && h >= minHeight) {
            return Triple(bitmap, 0, 0)
        }

        // 计算 padding 使宽高比降至阈值
        val targetH = (w / widthHeightRatio).roundToInt().coerceAtLeast(minHeight)
        val paddingTop = ((targetH - h) / 2).coerceAtLeast(0)
        val paddedH = h + paddingTop * 2

        val padded = Bitmap.createBitmap(w, paddedH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, 0f, paddingTop.toFloat(), null)

        return Triple(padded, paddingTop, 0)
    }

    // ========================================================================
    // resizeImageWithinBounds: 缩放约束
    // ========================================================================

    /**
     * 将图片缩放到指定范围内。
     *
     * @param bitmap 原图
     * @param minSideLen 最小边长（不足则放大）
     * @param maxSideLen 最大边长（超出则缩小）
     * @return Triple(newBitmap, ratioH, ratioW)
     */
    fun resizeImageWithinBounds(
        bitmap: Bitmap,
        minSideLen: Int = 30,
        maxSideLen: Int = 960
    ): Triple<Bitmap, Float, Float> {
        var result: Bitmap
        var ratioH = 1f
        var ratioW = 1f

        // 缩小：最大边超出
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide > maxSideLen) {
            val ratio = maxSideLen.toFloat() / maxSide
            val newW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
            val newH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
            result = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            ratioH *= ratio
            ratioW *= ratio
        } else {
            result = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        }

        // 放大：最小边不足
        val minSide = min(result.width, result.height)
        if (minSide < minSideLen) {
            val ratio = minSideLen.toFloat() / minSide
            val newW = (result.width * ratio).roundToInt().coerceAtLeast(1)
            val newH = (result.height * ratio).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(result, newW, newH, true)
            if (scaled !== result) result.recycle()
            result = scaled
            ratioH *= ratio
            ratioW *= ratio
        }

        return Triple(result, ratioH, ratioW)
    }

    // ========================================================================
    // mapBoxesToOriginal: 坐标映射
    // ========================================================================

    /**
     * 将检测框从缩放/填充后的坐标系映射回原图坐标系。
     */
    fun mapBoxesToOriginal(
        boxes: List<FloatArray>,
        ratioH: Float,
        ratioW: Float,
        paddingTop: Int,
        paddingLeft: Int,
        oriH: Int,
        oriW: Int
    ): List<FloatArray> {
        return boxes.map { box ->
            floatArrayOf(
                ((box[0] - paddingLeft) / ratioW).coerceIn(0f, (oriW - 1).toFloat()),
                ((box[1] - paddingTop) / ratioH).coerceIn(0f, (oriH - 1).toFloat()),
                ((box[2] - paddingLeft) / ratioW).coerceIn(0f, (oriW - 1).toFloat()),
                ((box[3] - paddingTop) / ratioH).coerceIn(0f, (oriH - 1).toFloat()),
                ((box[4] - paddingLeft) / ratioW).coerceIn(0f, (oriW - 1).toFloat()),
                ((box[5] - paddingTop) / ratioH).coerceIn(0f, (oriH - 1).toFloat()),
                ((box[6] - paddingLeft) / ratioW).coerceIn(0f, (oriW - 1).toFloat()),
                ((box[7] - paddingTop) / ratioH).coerceIn(0f, (oriH - 1).toFloat())
            )
        }
    }
}
