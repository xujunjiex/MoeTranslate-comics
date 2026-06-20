package com.moe.moetranslator.manga

import android.graphics.Rect
import com.moe.moetranslator.utils.LogCollector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 识别后文字行合并器 — 对齐 manga-image-translator 的 textline_merge 算法。
 *
 * 参考项目：`.reference/manga-image-translator/`
 * - quadrilateral_can_merge_region()（generic.py:653-698）
 * - merge_bboxes_text_region()（textline_merge/__init__.py:110-182）
 * - split_text_region()（textline_merge/__init__.py:10-83）
 *
 * **调用时机：OCR 识别之后**，将识别出的行级文字合并成文本区域。
 *
 * 供 PP-OCRv5 使用，后期可扩展到 CTD、MLKit。
 */
object TextLineMerger {

    private const val TAG = "TextLineMerger"

    /**
     * 识别后的文字行（对应参考项目的 Quadrilateral，AABB 特化版）。
     * text 必须非空（OCR 识别后的结果）。
     */
    data class TextLine(
        val rect: Rect,
        val text: String,
        val fontSize: Float,
        val isVertical: Boolean,
        val score: Float = 1f
    ) {
        val aspectRatio: Float
            get() = if (rect.height() > 0) rect.width().toFloat() / rect.height().toFloat() else 1f
        val centroidX: Float get() = rect.exactCenterX()
        val centroidY: Float get() = rect.exactCenterY()
        val direction: Char get() = if (isVertical) 'v' else 'h'
    }

    /**
     * 合并后的文本区域（对应参考项目的 TextBlock）。
     */
    data class MergedRegion(
        val rect: Rect,
        val texts: List<String>,
        val direction: TextDirection,
        val fontSize: Float,
        val score: Float
    )

    // ========== 参数（对齐参考项目 merge_bboxes_text_region 调用值） ==========

    private const val RATIO = 1.9f
    private const val DISCARD_CONNECTION_GAP = 2.0f
    private const val CHAR_GAP_TOLERANCE = 0.5f
    private const val CHAR_GAP_TOLERANCE2 = 3.0f
    private const val EDGE_ALIGN_TOL = 0.5f
    private const val FONT_SIZE_RATIO_TOL = 2.0f
    private const val ASPECT_RATIO_TOL = 1.3f

    // ========== 主入口 ==========

    /**
     * 合并识别后的文字行为文本区域。
     *
     * 流程（对齐 merge_bboxes_text_region）：
     * 1. canMergeRegion 构建图 → 连通分量
     * 2. splitTextRegion MST 拆分
     * 3. 方向投票 + 排序 + 合并 AABB + 拼接文字
     *
     * @return 合并后的文本区域列表，按阅读顺序排列
     */
    fun merge(textLines: List<TextLine>): List<MergedRegion> {
        if (textLines.isEmpty()) return emptyList()
        if (textLines.size == 1) {
            val line = textLines[0]
            return listOf(MergedRegion(
                rect = line.rect,
                texts = listOf(line.text),
                direction = if (line.isVertical) TextDirection.VERTICAL_RL else TextDirection.HORIZONTAL,
                fontSize = line.fontSize,
                score = line.score
            ))
        }

        LogCollector.d(TAG, "merge: 输入 ${textLines.size} 个文字行")

        // Step 1: canMergeRegion 构建图 → 连通分量
        val n = textLines.size
        val adjacency = Array(n) { mutableSetOf<Int>() }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (canMergeRegion(textLines[i], textLines[j])) {
                    adjacency[i].add(j)
                    adjacency[j].add(i)
                }
            }
        }

        val visited = BooleanArray(n)
        val connectedComponents = mutableListOf<Set<Int>>()
        for (i in 0 until n) {
            if (visited[i]) continue
            val component = mutableSetOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(i)
            visited[i] = true
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                component.add(node)
                for (neighbor in adjacency[node]) {
                    if (!visited[neighbor]) {
                        visited[neighbor] = true
                        queue.add(neighbor)
                    }
                }
            }
            connectedComponents.add(component)
        }
        LogCollector.d(TAG, "merge: 连通分量 ${connectedComponents.size} 个")

        // Step 2: splitTextRegion MST 拆分
        val regionIndices = mutableListOf<Set<Int>>()
        for (component in connectedComponents) {
            regionIndices.addAll(splitTextRegion(textLines, component))
        }
        LogCollector.d(TAG, "merge: 拆分后 ${regionIndices.size} 个区域")

        // Step 3: 方向投票 + 排序 + 合并 AABB + 拼接文字
        val result = mutableListOf<MergedRegion>()
        for (nodeSet in regionIndices) {
            val nodes = nodeSet.toList()
            val lines = nodes.map { textLines[it] }

            // majority vote 方向
            val directionCounts = lines.groupBy { it.direction }.mapValues { it.value.size }
            val majorityDir = directionCounts.maxByOrNull { it.value }?.key ?: 'h'

            // 按方向排序（对齐参考项目 L175-178）
            val sortedNodes = if (majorityDir == 'h') {
                nodes.sortedBy { textLines[it].centroidY }
            } else {
                nodes.sortedByDescending { textLines[it].centroidX }
            }

            // 合并 AABB
            val rects = sortedNodes.map { textLines[it].rect }
            val unionRect = Rect(
                rects.minOf { it.left },
                rects.minOf { it.top },
                rects.maxOf { it.right },
                rects.maxOf { it.bottom }
            )

            // 拼接文字（按阅读顺序）
            val combinedTexts = sortedNodes.map { textLines[it].text }
            val minFontSize = lines.minOf { it.fontSize }
            val avgScore = lines.map { it.score }.average().toFloat()
            val textDirection = if (majorityDir == 'v') TextDirection.VERTICAL_RL else TextDirection.HORIZONTAL

            result.add(MergedRegion(
                rect = unionRect,
                texts = combinedTexts,
                direction = textDirection,
                fontSize = minFontSize,
                score = avgScore
            ))

            LogCollector.d(TAG, "merge: 区域 ${lines.size} 行, dir=$textDirection, " +
                    "fs=${String.format("%.1f", minFontSize)}, text='${combinedTexts.first().take(20)}'")
        }

        LogCollector.d(TAG, "merge: 输出 ${result.size} 个文本区域")
        return result
    }

    // ========== canMergeRegion（对齐 generic.py L653-698） ==========

    private fun canMergeRegion(a: TextLine, b: TextLine): Boolean {
        val charSize = min(a.fontSize, b.fontSize)
        if (charSize <= 0f) return false

        val dist = aabbDistance(a, b)

        if (dist > DISCARD_CONNECTION_GAP * charSize) return false
        if (max(a.fontSize, b.fontSize) / charSize > FONT_SIZE_RATIO_TOL) return false
        if (a.aspectRatio > ASPECT_RATIO_TOL && b.aspectRatio < 1f / ASPECT_RATIO_TOL) return false
        if (b.aspectRatio > ASPECT_RATIO_TOL && a.aspectRatio < 1f / ASPECT_RATIO_TOL) return false

        if (dist < charSize * CHAR_GAP_TOLERANCE) {
            val x1 = a.rect.left.toFloat()
            val w1 = a.rect.width().toFloat()
            val h1 = a.rect.height().toFloat()
            val x2 = b.rect.left.toFloat()
            val w2 = b.rect.width().toFloat()
            val h2 = b.rect.height().toFloat()

            val centerDiff = abs(x1 + w1 / 2 - (x2 + w2 / 2))
            if (centerDiff < CHAR_GAP_TOLERANCE2) return true
            if (w1 > h1 * RATIO && h2 > w2 * RATIO) return false
            if (w2 > h2 * RATIO && h1 > w1 * RATIO) return false
            if (w1 > h1 * RATIO || w2 > h2 * RATIO) {
                return hEdgeAligned(a, b, charSize, EDGE_ALIGN_TOL)
            }
            if (h1 > w1 * RATIO || h2 > w2 * RATIO) {
                return vEdgeAligned(a, b, charSize, EDGE_ALIGN_TOL)
            }
            return false
        }
        return false
    }

    // ========== 边缘对齐检查 ==========

    private fun hEdgeAligned(a: TextLine, b: TextLine, charSize: Float, tol: Float): Boolean {
        // 横排文字：还需要 Y 范围重叠或间距 < charSize，否则是不同行
        val yOverlap = a.rect.top < b.rect.bottom && b.rect.top < a.rect.bottom
        val yGap = if (yOverlap) 0f else max(0f, max(a.rect.top - b.rect.bottom, b.rect.top - a.rect.bottom).toFloat())
        if (!yOverlap && yGap > charSize * 0.5f) return false
        return abs(a.rect.left - b.rect.left) < charSize * tol ||
               abs(a.rect.right - b.rect.right) < charSize * tol
    }

    private fun vEdgeAligned(a: TextLine, b: TextLine, charSize: Float, tol: Float): Boolean {
        // 竖排文字：还需要 X 范围重叠或间距 < charSize，否则是不同气泡
        val xOverlap = a.rect.left < b.rect.right && b.rect.left < a.rect.right
        val xGap = if (xOverlap) 0f else max(0f, max(a.rect.left - b.rect.right, b.rect.left - a.rect.right).toFloat())
        if (!xOverlap && xGap > charSize * 0.5f) return false
        return abs(a.rect.top - b.rect.top) < charSize * tol ||
               abs(a.rect.bottom - b.rect.bottom) < charSize * tol
    }

    // ========== AABB 距离 ==========

    private fun aabbDistance(a: TextLine, b: TextLine): Float {
        val dx = max(0f, max(a.rect.left.toFloat() - b.rect.right.toFloat(),
                             b.rect.left.toFloat() - a.rect.right.toFloat()))
        val dy = max(0f, max(a.rect.top.toFloat() - b.rect.bottom.toFloat(),
                             b.rect.top.toFloat() - a.rect.bottom.toFloat()))
        return sqrt(dx * dx + dy * dy)
    }

    // ========== splitTextRegion（对齐 textline_merge/__init__.py L10-83） ==========

    private fun splitTextRegion(
        textLines: List<TextLine>,
        connectedIndices: Set<Int>,
        gamma: Float = 0.5f,
        sigma: Float = 2f
    ): List<Set<Int>> {
        val indices = connectedIndices.toList()

        if (indices.size == 1) return listOf(setOf(indices[0]))

        if (indices.size == 2) {
            val a = textLines[indices[0]]
            val b = textLines[indices[1]]
            val fs = max(a.fontSize, b.fontSize)
            val dist = aabbDistance(a, b)
            return if (dist < (1 + gamma) * fs) {
                listOf(setOf(indices[0], indices[1]))
            } else {
                listOf(setOf(indices[0]), setOf(indices[1]))
            }
        }

        // case 3+: MST
        val allEdges = mutableListOf<MSTEdge>()
        for (i in indices.indices) {
            for (j in i + 1 until indices.size) {
                val u = indices[i]
                val v = indices[j]
                allEdges.add(MSTEdge(u, v, aabbDistance(textLines[u], textLines[v])))
            }
        }
        allEdges.sortBy { it.weight }
        val uf = UnionFind(textLines.size)
        val mstEdges = mutableListOf<MSTEdge>()
        for (edge in allEdges) {
            if (uf.union(edge.u, edge.v)) {
                mstEdges.add(edge)
                if (mstEdges.size == indices.size - 1) break
            }
        }
        if (mstEdges.isEmpty()) return listOf(connectedIndices)

        val sortedEdges = mstEdges.sortedByDescending { it.weight }
        val distances = sortedEdges.map { it.weight }

        val fontSize = indices.map { textLines[it].fontSize }.average().toFloat()
        val distancesStd = if (distances.size > 1) {
            val mean = distances.average().toFloat()
            sqrt(distances.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f
        val distancesMean = distances.average().toFloat()
        val stdThreshold = max(0.3f * fontSize + 5f, 5f)

        val maxEdge = sortedEdges.first()
        val b1 = textLines[maxEdge.u]
        val b2 = textLines[maxEdge.v]
        val maxPolyDist = aabbDistance(b1, b2)
        val maxCentroidAlignment = min(
            abs(b1.centroidX - b2.centroidX),
            abs(b1.centroidY - b2.centroidY)
        )

        val shouldKeep = (maxEdge.weight <= distancesMean + distancesStd * sigma
                || maxEdge.weight <= fontSize * (1 + gamma))
                && (distancesStd < stdThreshold
                || (maxPolyDist < 0.01f && maxCentroidAlignment < 5f))

        LogCollector.d(TAG, "splitTextRegion[${indices.size}]: " +
            "maxEdge=${String.format("%.1f", maxEdge.weight)} " +
            "mean=${String.format("%.1f", distancesMean)} std=${String.format("%.1f", distancesStd)} " +
            "fontSize=${String.format("%.1f", fontSize)} stdThreshold=${String.format("%.1f", stdThreshold)} " +
            "keep=$shouldKeep " +
            "(distCond=${maxEdge.weight <= distancesMean + distancesStd * sigma || maxEdge.weight <= fontSize * (1 + gamma)} " +
            "stdCond=${distancesStd < stdThreshold})")

        if (shouldKeep) {
            return listOf(connectedIndices)
        } else {
            val remainingEdges = sortedEdges.drop(1)
            val uf2 = UnionFind(textLines.size)
            for (edge in remainingEdges) {
                uf2.union(edge.u, edge.v)
            }

            val result = mutableListOf<Set<Int>>()
            val visited = mutableSetOf<Int>()
            for (idx in indices) {
                if (idx in visited) continue
                val component = mutableSetOf<Int>()
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    if (cur in visited) continue
                    visited.add(cur)
                    component.add(cur)
                    for (otherIdx in indices) {
                        if (otherIdx !in visited && uf2.find(cur) == uf2.find(otherIdx)) {
                            queue.add(otherIdx)
                        }
                    }
                }
                if (component.isNotEmpty()) {
                    result.addAll(splitTextRegion(textLines, component, gamma, sigma))
                }
            }
            return result
        }
    }

    // ========== MST 边和并查集 ==========

    private data class MSTEdge(val u: Int, val v: Int, val weight: Float)

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size) { 0 }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var node = x
            while (node != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }

        fun union(x: Int, y: Int): Boolean {
            val rx = find(x)
            val ry = find(y)
            if (rx == ry) return false
            when {
                rank[rx] < rank[ry] -> parent[rx] = ry
                rank[rx] > rank[ry] -> parent[ry] = rx
                else -> { parent[ry] = rx; rank[rx]++ }
            }
            return true
        }
    }
}
