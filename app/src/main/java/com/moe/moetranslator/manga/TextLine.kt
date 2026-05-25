package com.moe.moetranslator.manga

import android.graphics.Rect
import com.moe.moetranslator.bridge.TextBlockInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 文本行数据类，从 ML Kit 的 TextBlockInfo 转换而来。
 * 补充方向、字体大小、宽高比等属性，用于多条件合并和排序。
 */
data class TextLine(
    val rect: Rect,
    val text: String,
    val direction: TextDirection,
    val fontSize: Float,
    val centroidX: Float,
    val centroidY: Float,
    val aspectRatio: Float
)

/**
 * 将 ML Kit 的 TextBlockInfo 转换为 TextLine。
 * 方向判断：从 boundingBox 宽高比推断（高 > 宽 → 竖排，宽 > 高 → 横排）。
 */
fun TextBlockInfo.toTextLine(config: MangaModeConfig): TextLine? {
    val box = boundingBox ?: return null
    val w = box.width().toFloat()
    val h = box.height().toFloat()
    if (w <= 0 || h <= 0) return null

    val direction = if (isVertical != null) {
        // Use the passed isVertical from CTD detection
        if (isVertical) {
            if (config.textDirection == TextDirection.VERTICAL_LR) TextDirection.VERTICAL_LR
            else TextDirection.VERTICAL_RL
        } else {
            TextDirection.HORIZONTAL
        }
    } else {
        // Fallback: use AABB aspect ratio
        if (h > w) {
            if (config.textDirection == TextDirection.VERTICAL_LR) TextDirection.VERTICAL_LR
            else TextDirection.VERTICAL_RL
        } else {
            TextDirection.HORIZONTAL
        }
    }

    // fontSize = 垂直于文字排列方向的维度（即字符大小）
    // 竖排：字符宽度固定，高度随分组数量变化 → 用 w
    // 横排：字符高度固定，宽度随分组数量变化 → 用 h
    val fontSize = if (direction == TextDirection.HORIZONTAL) h else w

    return TextLine(
        rect = box,
        text = text,
        direction = direction,
        fontSize = fontSize,
        centroidX = box.centerX().toFloat(),
        centroidY = box.centerY().toFloat(),
        aspectRatio = w / h
    )
}

/**
 * 计算两个轴对齐矩形之间的距离（像素）。
 * 使用 Chebyshev 距离（max of horizontal/vertical gaps），比欧氏距离更适合文字块合并判断。
 * 参考项目 manga-image-translator 使用 polygon distance，Chebyshev 更接近其行为。
 * 如果重叠则返回 0。
 */
fun rectDistance(a: Rect, b: Rect): Float {
    val dx = max(0, max(b.left - a.right, a.left - b.right))
    val dy = max(0, max(b.top - a.bottom, a.top - b.bottom))
    return max(dx, dy).toFloat()
}
