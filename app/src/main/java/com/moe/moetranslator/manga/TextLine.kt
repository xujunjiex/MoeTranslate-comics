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

    val direction = if (h > w) {
        // 高 > 宽 → 竖排，根据用户配置选择 LR 或 RL
        if (config.textDirection == TextDirection.VERTICAL_LR) TextDirection.VERTICAL_LR
        else TextDirection.VERTICAL_RL
    } else {
        TextDirection.HORIZONTAL
    }

    return TextLine(
        rect = box,
        text = text,
        direction = direction,
        fontSize = min(w, h),
        centroidX = box.centerX().toFloat(),
        centroidY = box.centerY().toFloat(),
        aspectRatio = w / h
    )
}

/**
 * 计算两个轴对齐矩形之间的最短距离（像素）。
 * 如果重叠则返回 0。
 */
fun rectDistance(a: Rect, b: Rect): Float {
    val dx = max(0, max(b.left - a.right, a.left - b.right))
    val dy = max(0, max(b.top - a.bottom, a.top - b.bottom))
    return if (dx == 0 && dy == 0) 0f else kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
}
