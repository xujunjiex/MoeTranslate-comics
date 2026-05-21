package com.moe.moetranslator.manga

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 多条件气泡合并器。
 * 移植自 manga-image-translator 的 quadrilateral_can_merge_region，
 * 适配 ML Kit 的轴对齐矩形（Rect）。
 *
 * 参考项目调用参数（textline_merge/__init__.py:134）：
 *   quadrilateral_can_merge_region(ubox, vbox,
 *     aspect_ratio_tol=1.3, font_size_ratio_tol=2,
 *     char_gap_tolerance=1, char_gap_tolerance2=3)
 *
 * 默认参数（generic.py:653）：
 *   discard_connection_gap=2, ratio=1.9
 */
object BubbleMerger {

    private const val DISCARD_CONNECTION_GAP = 2f    // 距离容差（字符大小的倍数）
    private const val CHAR_GAP_TOLERANCE = 1.0f     // 距离门控阈值
    private const val CHAR_GAP_TOLERANCE2 = 3.0f    // 对齐容差
    private const val FONT_SIZE_RATIO_TOL = 2.0f    // 字体大小比容差
    private const val ASPECT_RATIO_TOL = 1.3f        // 宽高比容差
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

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (canMerge(textLines[i], textLines[j])) {
                    union(i, j)
                }
            }
        }

        val groups = mutableMapOf<Int, MutableList<TextLine>>()
        for (i in 0 until n) {
            val root = find(i)
            groups.getOrPut(root) { mutableListOf() }.add(textLines[i])
        }
        return groups.values.toList()
    }

    /**
     * 判断两个 TextLine 是否应该合并。
     * 精确匹配 quadrilateral_can_merge_region 的轴对齐分支（generic.py:653-698）。
     */
    private fun canMerge(a: TextLine, b: TextLine): Boolean {
        val charSize = min(a.fontSize, b.fontSize)
        if (charSize <= 0) return false

        val x1 = a.rect.left
        val y1 = a.rect.top
        val w1 = a.rect.width()
        val h1 = a.rect.height()
        val x2 = b.rect.left
        val y2 = b.rect.top
        val w2 = b.rect.width()
        val h2 = b.rect.height()

        // 1. 粗筛：距离（generic.py:663）
        val dist = rectDistance(a.rect, b.rect)
        if (dist > DISCARD_CONNECTION_GAP * charSize) return false

        // 2. 字体大小比（generic.py:665）
        if (max(a.fontSize, b.fontSize) / min(a.fontSize, b.fontSize) > FONT_SIZE_RATIO_TOL) return false

        // 3. 宽高比交叉检查（generic.py:667-670）
        if (a.aspectRatio > ASPECT_RATIO_TOL && b.aspectRatio < 1f / ASPECT_RATIO_TOL) return false
        if (b.aspectRatio > ASPECT_RATIO_TOL && a.aspectRatio < 1f / ASPECT_RATIO_TOL) return false

        // 4. 距离门控（generic.py:673-674）— 只有近处的才检查对齐
        if (dist >= charSize * CHAR_GAP_TOLERANCE) return false

        // 5. 对齐检查（在门控内执行，generic.py:675-685）

        // x-center 对齐（generic.py:675）
        if (abs(x1 + w1 / 2 - (x2 + w2 / 2)) < CHAR_GAP_TOLERANCE2) return true

        // 交叉方向拒绝（generic.py:677-680）
        if (w1 > h1 * RATIO && h2 > w2 * RATIO) return false
        if (w2 > h2 * RATIO && h1 > w1 * RATIO) return false

        // 两个都偏横 → 检查 x 边对齐（generic.py:681-682）
        if (w1 > h1 * RATIO || w2 > h2 * RATIO) {
            return abs(x1 - x2) < charSize * CHAR_GAP_TOLERANCE2 ||
                    abs(x1 + w1 - (x2 + w2)) < charSize * CHAR_GAP_TOLERANCE2
        }

        // 两个都偏竖 → 检查 y 边对齐（generic.py:683-684）
        if (h1 > w1 * RATIO || h2 > w2 * RATIO) {
            return abs(y1 - y2) < charSize * CHAR_GAP_TOLERANCE2 ||
                    abs(y1 + h1 - (y2 + h2)) < charSize * CHAR_GAP_TOLERANCE2
        }

        return false
    }
}
