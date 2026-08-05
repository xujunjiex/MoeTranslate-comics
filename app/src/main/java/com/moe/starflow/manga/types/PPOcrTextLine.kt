package com.moe.starflow.manga.types
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 识别后的文字行数据类（对应参考项目的 Quadrilateral，AABB 特化版）。
 * text 必须非空（OCR 识别后的结果）。
 *
 * quadPoints 是 PP-OCRv5 检测的 4 顶点（顺时针：TL,TR,BR,BL）。
 * 有 quadPoints 时 fontSize/aspectRatio 用真实边长计算（倾斜时不失真），
 * 无 quadPoints 时降级为 AABB 估算。
 *
 * 合并逻辑已迁移到 TextRegionMerger。
 */
data class PPOcrTextLine(
    val rect: Rect,
    val text: String,
    val fontSize: Float,
    val isVertical: Boolean,
    val score: Float = 1f,
    val angle: Float = 0f,
    val quadPoints: Array<android.graphics.PointF> = emptyArray(),
    val center: android.graphics.PointF = android.graphics.PointF(0f, 0f)
) {
    /** 真实长边（顶边长），用 quad 顶点计算；无 quad 时回退 AABB 宽。 */
    val realTopLen: Float
        get() = if (quadPoints.size >= 2) {
            val dx = quadPoints[1].x - quadPoints[0].x
            val dy = quadPoints[1].y - quadPoints[0].y
            kotlin.math.sqrt(dx * dx + dy * dy)
        } else rect.width().toFloat()

    /** 真实短边（左边长），用 quad 顶点计算；无 quad 时回退 AABB 高。 */
    val realLeftLen: Float
        get() = if (quadPoints.size >= 4) {
            val dx = quadPoints[3].x - quadPoints[0].x
            val dy = quadPoints[3].y - quadPoints[0].y
            kotlin.math.sqrt(dx * dx + dy * dy)
        } else rect.height().toFloat()

    /**
     * 真实长宽比 = 真实长边 / 真实短边。倾斜时不受 AABB 膨胀影响。
     * 无 quad 时降级为 AABB 长宽比。
     */
    val aspectRatio: Float
        get() {
            val long = max(realTopLen, realLeftLen)
            val short = min(realTopLen, realLeftLen)
            return if (short > 0f) long / short else 1f
        }

    val centroidX: Float get() = rect.exactCenterX()
    val centroidY: Float get() = rect.exactCenterY()
    val direction: Char get() = if (isVertical) 'v' else 'h'

    /**
     * 是否近似轴对齐。
     * 直接用 quad 顶边 angle 判断：|angle| ≤ 3° 视为 AA。
     */
    val isApproxAxisAligned: Boolean
        get() = abs(angle) <= 3f
}
