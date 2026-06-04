package com.moe.moetranslator.manga

import android.graphics.Rect
import com.moe.moetranslator.bridge.TextBlockInfo
import com.moe.moetranslator.utils.LogCollector
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

        // 2. 按距离和方向合并：同一气泡/段落的相邻文字行归为一组
        //    对齐参考项目 merge_bboxes_text_region 的连通分量逻辑
        val mergedGroups = groupNearbyLines(textLines)

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
     * 按距离和方向合并相邻文字行。
     * 对齐参考项目 merge_bboxes_text_region 的连通分量分组逻辑：
     * 1. 构建邻接图（方向一致 + 距离接近 + 字号相似 → 连边）
     * 2. DFS 找连通分量 = 一组
     *
     * 合并条件：
     * - 方向一致（同为竖排或同为横排）
     * - Chebyshev 距离 < fontSize * GAP_TOLERANCE
     * - 字号比例 < SIZE_RATIO_TOL
     */
    private fun groupNearbyLines(textLines: List<TextLine>): List<List<TextLine>> {
        if (textLines.size <= 1) return textLines.map { listOf(it) }

        // 构建邻接表
        val adj = MutableList(textLines.size) { mutableListOf<Int>() }

        for (i in 0 until textLines.size) {
            for (j in i + 1 until textLines.size) {
                if (canMergeLines(textLines[i], textLines[j])) {
                    adj[i].add(j)
                    adj[j].add(i)
                }
            }
        }

        // DFS 找连通分量
        val visited = BooleanArray(textLines.size)
        val groups = mutableListOf<List<TextLine>>()

        for (i in textLines.indices) {
            if (visited[i]) continue
            val group = mutableListOf<TextLine>()
            val stack = ArrayDeque<Int>()
            stack.add(i)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (visited[node]) continue
                visited[node] = true
                group.add(textLines[node])
                for (neighbor in adj[node]) {
                    if (!visited[neighbor]) stack.add(neighbor)
                }
            }
            groups.add(group)
        }

        LogCollector.d("BubbleDetector", "groupNearbyLines: ${textLines.size} 行 → ${groups.size} 组")
        return groups
    }

    /**
     * 判断两个 TextLine 是否可以合并到同一气泡。
     *
     * 竖排文字：同组文字行在同一列内纵向排列，需要 Y 轴重叠或接近 + X 轴对齐
     * 横排文字：同组文字行在同一行内横向排列，需要 X 轴重叠或接近 + Y 轴对齐
     *
     * 关键：不同气泡即使方向和字号相同，也因为空间分离而不应合并。
     */
    private fun canMergeLines(
        a: TextLine, b: TextLine
    ): Boolean {
        val SIZE_RATIO_TOL = 2.0f  // 字号比例 < 2 → 可能同组
        // 方向必须一致
        val aIsVertical = a.direction == TextDirection.VERTICAL_RL || a.direction == TextDirection.VERTICAL_LR
        val bIsVertical = b.direction == TextDirection.VERTICAL_RL || b.direction == TextDirection.VERTICAL_LR
        if (aIsVertical != bIsVertical) return false

        // 字号比例检查
        val maxFS = max(a.fontSize, b.fontSize)
        val minFS = min(a.fontSize, b.fontSize).coerceAtLeast(0.1f)
        if (maxFS / minFS > SIZE_RATIO_TOL) return false

        val rectA = a.rect
        val rectB = b.rect

        return if (aIsVertical) {
            // 竖排：文字列纵向排列，同组的行应该在同一列（X 接近）且纵向连续（Y 重叠或小间隙）
            val yOverlap = max(0, min(rectA.bottom, rectB.bottom) - max(rectA.top, rectB.top))
            val yGap = max(0, max(rectB.top - rectA.bottom, rectA.top - rectB.bottom))
            val xGap = max(0, max(rectB.left - rectA.right, rectA.left - rectB.right))

            // Y 轴重叠超过半个字号，或纵向间隙 < 0.8×字号 → 可能同列
            val yClose = yOverlap > maxFS * 0.5f || yGap < maxFS * 0.8f
            // X 轴间隙 < 0.8×字号 → 在同一列附近
            val xClose = xGap < maxFS * 0.8f

            yClose && xClose
        } else {
            // 横排：文字行横向排列，同组的行应该在同一行（Y 接近）且横向连续（X 重叠或小间隙）
            val xOverlap = max(0, min(rectA.right, rectB.right) - max(rectA.left, rectB.left))
            val xGap = max(0, max(rectB.left - rectA.right, rectA.left - rectB.right))
            val yGap = max(0, max(rectB.top - rectA.bottom, rectA.top - rectB.bottom))

            // X 轴重叠超过半个字号，或横向间隙 < 0.8×字号
            val xClose = xOverlap > maxFS * 0.5f || xGap < maxFS * 0.8f
            // Y 轴间隙 < 字号 * 1.5（行间距通常大于字间距）
            val yClose = yGap < maxFS * 1.5f

            xClose && yClose
        }
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
