package com.moe.starflow.manga.types
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.graphics.Point

/** PP-OCR det 检测框（4 个顶点），V5/V6 共用（从 PPOcrV5Engine.kt 提取） */
data class DetBox(val points: Array<Point>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetBox) return false
        return points.contentEquals(other.points)
    }

    override fun hashCode(): Int = points.contentHashCode()
}

/** PP-OCR rec 识别结果（从 PPOcrV5Engine.kt 提取） */
data class RecResult(val text: String, val score: Float)

/** PP-OCR OCR 完整结果，V5/V6 共用（从 PPOcrV5Engine.kt 提取） */
data class OcrResult(
    val boxes: List<FloatArray>,
    val texts: List<String>,
    val scores: List<Float>,
    val elapseList: List<Float>,
    /** 识别阶段被 text_score_thresh 丢弃的选区（调试用） */
    val recDebug: DebugRecResult? = null
)

/** 识别阶段调试结果：保留和被丢弃的选区（分数丢弃 + 内容丢弃） */
data class DebugRecResult(
    val keptBoxes: List<FloatArray>,
    val keptTexts: List<String>,
    val keptScores: List<Float>,
    val discardedBoxes: List<FloatArray>,
    val discardedTexts: List<String>,
    val discardedScores: List<Float>,
    val discardedReasons: List<String> = emptyList()  // "score" 或内容原因（"空白"/"单字符"/"纯符号"/"短数字"）
)
