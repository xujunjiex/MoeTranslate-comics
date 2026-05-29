package com.moe.moetranslator.manga

import android.graphics.Rect
import com.moe.moetranslator.bridge.TextBlockInfo
import kotlin.math.max
import kotlin.math.min

data class BubbleRegion(
    val rect: Rect,
    val texts: List<String>,
    val fontSize: Float = 16f,
    val direction: TextDirection = TextDirection.VERTICAL_RL
)

/**
 * 气泡检测器。
 * 流程：TextBlockInfo → TextLine → 多条件合并 → MST 分割 → majority vote 方向 → 阅读排序。
 */
object BubbleDetector {

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

        // 2. 不再做合并（BoxMerger 已处理），每个 TextLine 独立处理
        val mergedGroups = textLines.map { listOf(it) }

        // 3. MST 分割（过大的区域拆分）
        val splitGroups = mergedGroups.flatMap { TextRegionSplitter.split(it) }

        // 4. 构建 BubbleRegion，majority vote 确定方向（参考项目 merge_bboxes_text_region 第 158-172 行）
        val bubbles = splitGroups.map { group ->
            val sorted = ReadingOrderSorter.sortTextLines(group, config.textDirection)
            val boundingRect = computeBoundingRect(sorted)
            val avgFontSize = sorted.maxOf { it.fontSize }

            // majority vote 方向
            val majorityDir = determineDirection(sorted, config.textDirection)

            BubbleRegion(
                rect = boundingRect,
                texts = sorted.map { it.text },
                fontSize = avgFontSize,
                direction = majorityDir
            )
        }

        // 5. 按 per-bubble 方向排序
        return ReadingOrderSorter.sort(bubbles)
    }

    /**
     * 兼容旧接口：不传 config 时使用默认行为。
     */
    fun detectBubbles(textBlocks: List<TextBlockInfo>): List<BubbleRegion> {
        return detectBubbles(textBlocks, MangaModeConfig())
    }

    /**
     * Majority vote 确定方向（参考项目 merge_bboxes_text_region 第 158-172 行）。
     * 平票时取 aspectRatio 最大的 textline 的方向。
     */
    private fun determineDirection(textLines: List<TextLine>, fallback: TextDirection): TextDirection {
        if (textLines.isEmpty()) return fallback
        if (textLines.size == 1) return textLines[0].direction

        val dirCounts = textLines.groupBy { it.direction }
        if (dirCounts.size == 1) return dirCounts.keys.first()

        val top2 = dirCounts.entries.sortedByDescending { it.value.size }
        return if (top2[0].value.size == top2[1].value.size) {
            // 平票：取 aspectRatio 最大的
            textLines.maxByOrNull { max(it.aspectRatio, 1f / it.aspectRatio) }?.direction ?: fallback
        } else {
            top2[0].key
        }
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
}
