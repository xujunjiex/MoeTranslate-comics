package com.moe.starflow.manga.types
import com.moe.starflow.translate.screenshot.*

import android.graphics.PointF
import android.graphics.Rect

/**
 * 合并器的统一输出（对应参考项目的 TextBlock）。
 */
data class TextRegionGroup(
    val rect: Rect,
    val quadPoints: Array<PointF>,
    val texts: List<String>,
    val direction: TextDirection,
    val fontSize: Float,
    val angle: Float,
    val score: Float,
    val center: PointF,
    val members: List<TextRegion>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextRegionGroup) return false
        return rect == other.rect && texts == other.texts
    }

    override fun hashCode(): Int {
        var result = rect.hashCode()
        result = 31 * result + texts.hashCode()
        return result
    }
}
