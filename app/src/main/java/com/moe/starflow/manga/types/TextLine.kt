package com.moe.starflow.manga.types
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.graphics.Rect
import kotlin.math.max

/**
 * 文本行数据类。
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
