package com.moe.moetranslator.manga

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 多条件气泡合并器。
 * 移植自 manga-image-translator 的 quadrilateral_can_merge_region，
 * 适配 ML Kit 的轴对齐矩形（Rect）。
 */
object BubbleMerger {

    private const val DISCARD_CONNECTION_GAP = 2f    // 距离容差（字符大小的倍数）
    private const val CHAR_GAP_TOLERANCE = 0.6f      // 紧邻容差
    private const val CHAR_GAP_TOLERANCE2 = 1.5f     // 对齐容差
    private const val FONT_SIZE_RATIO_TOL = 1.5f     // 字体大小比容差
    private const val ASPECT_RATIO_TOL = 1.9f        // 宽高比容差
    private const val RATIO = 1.9f                    // 横竖判断阈值

    /**
     * 合并文本行列表，返回合并后的分组。
     * 使用图的连通分量：满足合并条件的文本对之间连边，同一连通分量归为一个气泡。
     */
    fun merge(textLines: List<TextLine>, config: MangaModeConfig): List<List<TextLine>> {
        if (textLines.isEmpty()) return emptyList()
        if (textLines.size == 1) return listOf(textLines)

        val n = textLines.size
        val parent = IntArray(n) { it }

        // Union-Find
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (cur != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        // 检查所有文本对
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (canMerge(textLines[i], textLines[j], config)) {
                    union(i, j)
                }
            }
        }

        // 收集连通分量
        val groups = mutableMapOf<Int, MutableList<TextLine>>()
        for (i in 0 until n) {
            val root = find(i)
            groups.getOrPut(root) { mutableListOf() }.add(textLines[i])
        }
        return groups.values.toList()
    }

    /**
     * 判断两个 TextLine 是否应该合并。
     * 参考 quadrilateral_can_merge_region 的轴对齐分支。
     */
    private fun canMerge(a: TextLine, b: TextLine, @Suppress("UNUSED_PARAMETER") config: MangaModeConfig): Boolean {
        val charSize = min(a.fontSize, b.fontSize)
        if (charSize <= 0) return false

        // 1. 距离检查：矩形间隙 < 字符大小 × 2
        val dist = rectDistance(a.rect, b.rect)
        if (dist > DISCARD_CONNECTION_GAP * charSize) return false

        // 2. 字体大小比检查
        val fontSizeRatio = max(a.fontSize, b.fontSize) / min(a.fontSize, b.fontSize)
        if (fontSizeRatio > FONT_SIZE_RATIO_TOL) return false

        // 3. 宽高比检查：一个很竖一个很横的不合并
        if (a.aspectRatio > ASPECT_RATIO_TOL && b.aspectRatio < 1f / ASPECT_RATIO_TOL) return false
        if (b.aspectRatio > ASPECT_RATIO_TOL && a.aspectRatio < 1f / ASPECT_RATIO_TOL) return false

        // 4. 轴对齐矩形的对齐检查
        if (dist < charSize * CHAR_GAP_TOLERANCE) {
            val x1 = a.rect.left
            val y1 = a.rect.top
            val w1 = a.rect.width()
            val h1 = a.rect.height()
            val x2 = b.rect.left
            val y2 = b.rect.top
            val w2 = b.rect.width()
            val h2 = b.rect.height()

            // 中心 x 对齐检查
            if (abs(x1 + w1 / 2 - (x2 + w2 / 2)) < CHAR_GAP_TOLERANCE2) return true

            // 一个很横一个很竖 → 不合并
            if (w1 > h1 * RATIO && h2 > w2 * RATIO) return false
            if (w2 > h2 * RATIO && h1 > w1 * RATIO) return false

            // 两个都偏横 → 检查 x 对齐
            if (w1 > h1 * RATIO || w2 > h2 * RATIO) {
                return abs(x1 - x2) < charSize * CHAR_GAP_TOLERANCE2 ||
                        abs(x1 + w1 - (x2 + w2)) < charSize * CHAR_GAP_TOLERANCE2
            }

            // 两个都偏竖 → 检查 y 对齐
            if (h1 > w1 * RATIO || h2 > w2 * RATIO) {
                return abs(y1 - y2) < charSize * CHAR_GAP_TOLERANCE2 ||
                        abs(y1 + h1 - (y2 + h2)) < charSize * CHAR_GAP_TOLERANCE2
            }

            return false
        }

        return false
    }
}
