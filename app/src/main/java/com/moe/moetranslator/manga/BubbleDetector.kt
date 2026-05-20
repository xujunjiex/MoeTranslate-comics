package com.moe.moetranslator.manga

import android.graphics.Rect
import com.moe.moetranslator.bridge.TextBlockInfo
import kotlin.math.max
import kotlin.math.min

data class BubbleRegion(
    val rect: Rect,
    val texts: List<String>,
    val fontSize: Float = 16f
)

/**
 * 气泡检测器。
 * 流程：TextBlockInfo → TextLine → 多条件合并 → MST 分割 → 阅读排序。
 */
object BubbleDetector {

    private const val BUBBLE_EXPAND_PX = 20

    /**
     * 检测气泡区域。
     * @param textBlocks ML Kit 识别的文字块
     * @param config 漫画模式配置（方向等）
     * @return 按阅读顺序排列的气泡列表
     */
    fun detectBubbles(textBlocks: List<TextBlockInfo>, config: MangaModeConfig): List<BubbleRegion> {
        if (textBlocks.isEmpty()) return emptyList()

        // 1. TextBlockInfo → TextLine
        val textLines = textBlocks.mapNotNull { it.toTextLine(config) }
        if (textLines.isEmpty()) return emptyList()

        // 2. 多条件合并（方向感知、字体大小、对齐、宽高比）
        val mergedGroups = BubbleMerger.merge(textLines, config)

        // 3. MST 分割（过大的区域拆分）
        val splitGroups = mergedGroups.flatMap { TextRegionSplitter.split(it) }

        // 4. 构建 BubbleRegion，文本按阅读顺序拼接
        val bubbles = splitGroups.map { group ->
            val sorted = ReadingOrderSorter.sortTextLines(group, config.textDirection)
            val boundingRect = computeBoundingRect(sorted)
            val expandedRect = expandRect(boundingRect, BUBBLE_EXPAND_PX)
            val avgFontSize = sorted.map { it.fontSize }.average().toFloat()
            BubbleRegion(
                rect = expandedRect,
                texts = sorted.map { it.text },
                fontSize = avgFontSize
            )
        }

        // 5. 阅读顺序排序
        return ReadingOrderSorter.sort(bubbles, config)
    }

    /**
     * 兼容旧接口：不传 config 时使用默认行为。
     */
    fun detectBubbles(textBlocks: List<TextBlockInfo>): List<BubbleRegion> {
        return detectBubbles(textBlocks, MangaModeConfig())
    }

    private fun computeBoundingRect(textLines: List<TextLine>): Rect {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (line in textLines) {
            left = min(left, line.rect.left)
            top = min(top, line.rect.top)
            right = max(right, line.rect.right)
            bottom = max(bottom, line.rect.bottom)
        }
        return Rect(left, top, right, bottom)
    }

    private fun expandRect(rect: Rect, px: Int): Rect {
        return Rect(
            maxOf(0, rect.left - px),
            maxOf(0, rect.top - px),
            rect.right + px,
            rect.bottom + px
        )
    }
}
