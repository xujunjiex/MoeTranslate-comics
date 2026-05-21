package com.moe.moetranslator.manga

import com.moe.moetranslator.utils.LogCollector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OCR 前的 box 合并器。
 * 完整移植自 manga-image-translator 的：
 * - quadrilateral_can_merge_region()（generic.py:653-698）
 * - merge_bboxes_text_region()（textline_merge/__init__.py:110-182）
 * - split_text_region()（textline_merge/__init__.py:10-83）
 */
object BoxMerger {

    private const val TAG = "BoxMerger"

    // 默认参数（与 manga-image-translator textline_merge 调用参数一致）
    private const val RATIO = 1.9f
    private const val DISCARD_CONNECTION_GAP = 2f
    private const val CHAR_GAP_TOLERANCE = 0.6f
    private const val CHAR_GAP_TOLERANCE2 = 1.5f
    private const val FONT_SIZE_RATIO_TOL = 1.5f
    private const val ASPECT_RATIO_TOL = 2f

    /**
     * 合并 box 列表，返回合并后的分组。
     * 完整移植 merge_bboxes_text_region()。
     *
     * @return 每组是一个 List<QuadBox>，已按阅读顺序排序
     */
    fun merge(bboxes: List<QuadBox>): List<List<QuadBox>> {
        if (bboxes.isEmpty()) return emptyList()
        if (bboxes.size == 1) return listOf(bboxes)

        val n = bboxes.size

        // Step 1: 建图，满足 canMergeRegion 的 box 对连边
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
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (canMergeRegion(bboxes[i], bboxes[j])) {
                    union(i, j)
                }
            }
        }

        // 收集连通分量
        val components = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val root = find(i)
            components.getOrPut(root) { mutableListOf() }.add(i)
        }

        // Step 2: 对每个连通分量进一步拆分
        val regionIndices = mutableListOf<Set<Int>>()
        for ((_, nodeSet) in components) {
            regionIndices.addAll(splitTextRegion(bboxes, nodeSet.toSet()))
        }

        LogCollector.d(TAG, "box 合并: ${bboxes.size} → ${regionIndices.size} 个文本行")

        // Step 3: 构建结果，按阅读顺序排序
        val result = mutableListOf<List<QuadBox>>()
        for (nodeSet in regionIndices) {
            val nodes = nodeSet.toList()
            val txtlns = nodes.map { bboxes[it] }

            // majority vote 方向
            val majorityDir = determineDirection(txtlns)

            // 按方向排序
            val sorted = if (majorityDir == 'h') {
                txtlns.sortedBy { it.centroidY }
            } else {
                txtlns.sortedByDescending { it.centroidX }
            }

            result.add(sorted)
        }
        return result
    }

    /**
     * 判断两个 QuadBox 是否应合并。
     * 完整移植 quadrilateral_can_merge_region()（generic.py:653-698）。
     */
    private fun canMergeRegion(a: QuadBox, b: QuadBox): Boolean {
        val aabb1 = a.aabb
        val aabb2 = b.aabb
        val charSize = min(a.fontSize, b.fontSize)
        if (charSize <= 0) return false

        val x1 = aabb1.left.toFloat(); val y1 = aabb1.top.toFloat()
        val w1 = aabb1.width().toFloat(); val h1 = aabb1.height().toFloat()
        val x2 = aabb2.left.toFloat(); val y2 = aabb2.top.toFloat()
        val w2 = aabb2.width().toFloat(); val h2 = aabb2.height().toFloat()

        // 1. 距离粗筛（generic.py:663）
        val dist = a.distance(b)
        if (dist > DISCARD_CONNECTION_GAP * charSize) return false

        // 2. 字体大小比（generic.py:665）
        if (max(a.fontSize, b.fontSize) / min(a.fontSize, b.fontSize) > FONT_SIZE_RATIO_TOL) return false

        // 3. 宽高比交叉检查（generic.py:667-670）
        if (a.aspectRatio > ASPECT_RATIO_TOL && b.aspectRatio < 1f / ASPECT_RATIO_TOL) return false
        if (b.aspectRatio > ASPECT_RATIO_TOL && a.aspectRatio < 1f / ASPECT_RATIO_TOL) return false

        // 4. 轴对齐分支（generic.py:671-687）
        val a_aa = a.isApproximateAxisAligned
        val b_aa = b.isApproximateAxisAligned
        if (a_aa && b_aa) {
            if (dist < charSize * CHAR_GAP_TOLERANCE) {
                // 中心对齐检查（generic.py:675）
                if (abs(x1 + w1 / 2 - (x2 + w2 / 2)) < CHAR_GAP_TOLERANCE2) return true
                // 横竖交叉拒绝（generic.py:677-680）
                if (w1 > h1 * RATIO && h2 > w2 * RATIO) return false
                if (w2 > h2 * RATIO && h1 > w1 * RATIO) return false
                // 两个都偏横 → x 边对齐（generic.py:681-682）
                if (w1 > h1 * RATIO || w2 > h2 * RATIO) {
                    return abs(x1 - x2) < charSize * CHAR_GAP_TOLERANCE2 ||
                            abs(x1 + w1 - (x2 + w2)) < charSize * CHAR_GAP_TOLERANCE2
                }
                // 两个都偏竖 → y 边对齐（generic.py:683-684）
                if (h1 > w1 * RATIO || h2 > w2 * RATIO) {
                    return abs(y1 - y2) < charSize * CHAR_GAP_TOLERANCE2 ||
                            abs(y1 + h1 - (y2 + h2)) < charSize * CHAR_GAP_TOLERANCE2
                }
                return false
            } else {
                return false
            }
        }

        // 5. 非轴对齐分支（generic.py:688-697）
        if (abs(a.angle - b.angle) < 15 * PI / 180) {
            val fsA = a.fontSize
            val fsB = b.fontSize
            val fs = min(fsA, fsB)
            if (a.polyDistance(b) > fs * CHAR_GAP_TOLERANCE2) return false
            if (abs(fsA - fsB) / fs > 0.25f) return false
            return true
        }
        return false
    }

    /**
     * MST 分析拆分过大的文本区域。
     * 完整移植 split_text_region()（textline_merge/__init__.py:10-83）。
     */
    private fun splitTextRegion(
        bboxes: List<QuadBox>,
        connectedRegionIndices: Set<Int>
    ): List<Set<Int>> {
        val indices = connectedRegionIndices.toList()

        // case 1: 单个 box
        if (indices.size == 1) return listOf(connectedRegionIndices)

        // case 2: 两个 box
        if (indices.size == 2) {
            val fs1 = bboxes[indices[0]].fontSize
            val fs2 = bboxes[indices[1]].fontSize
            val fs = max(fs1, fs2)
            if (bboxes[indices[0]].distance(bboxes[indices[1]]) < (1 + 0.5f) * fs &&
                abs(bboxes[indices[0]].angle - bboxes[indices[1]].angle) < 0.2f * PI
            ) {
                return listOf(connectedRegionIndices)
            } else {
                return listOf(setOf(indices[0]), setOf(indices[1]))
            }
        }

        // case 3: 3+ 个 box → MST 分析
        // 构建完全图，边权 = Chebyshev 距离
        data class Edge(val u: Int, val v: Int, val weight: Float)

        val edges = mutableListOf<Edge>()
        for (i in indices) {
            for (j in indices) {
                if (i < j) {
                    edges.add(Edge(i, j, bboxes[i].distance(bboxes[j])))
                }
            }
        }

        // Kruskal MST
        val uf = UnionFind(bboxes.size)
        val sortedEdges = edges.sortedBy { it.weight }
        val mstEdges = mutableListOf<Edge>()
        for (edge in sortedEdges) {
            if (uf.find(edge.u) != uf.find(edge.v)) {
                uf.union(edge.u, edge.v)
                mstEdges.add(edge)
            }
        }

        // 按权重降序排列（从最大边开始检查是否需要拆分）
        val edgesSorted = mstEdges.sortedByDescending { it.weight }
        val distances = edgesSorted.map { it.weight.toDouble() }
        val distancesStd = std(distances)
        val distancesMean = mean(distances)
        val avgFontSize = indices.map { bboxes[it].fontSize }.average().toFloat()
        val stdThreshold = max(0.3f * avgFontSize + 5, 5f)

        // 最大边的两个端点
        val b1 = bboxes[edgesSorted[0].u]
        val b2 = bboxes[edgesSorted[0].v]
        val maxPolyDistance = b1.polyDistance(b2)
        val maxCentroidAlignment = min(
            abs(b1.centroidX - b2.centroidX),
            abs(b1.centroidY - b2.centroidY)
        )

        // 判断是否需要拆分（generic.py:66-69）
        if ((distances[0] <= distancesMean + distancesStd * 2 ||
                    distances[0] <= avgFontSize * (1 + 0.5f)) &&
            (distancesStd < stdThreshold ||
                    (maxPolyDistance == 0f && maxCentroidAlignment < 5))
        ) {
            return listOf(connectedRegionIndices)
        } else {
            // 拆分：移除最大边，递归处理
            val g = mutableMapOf<Int, MutableSet<Int>>()
            for (idx in indices) g[idx] = mutableSetOf()
            for (edge in edgesSorted.drop(1)) {
                g.getOrPut(edge.u) { mutableSetOf() }.add(edge.v)
                g.getOrPut(edge.v) { mutableSetOf() }.add(edge.u)
            }
            // 找连通分量
            val visited = mutableSetOf<Int>()
            val result = mutableListOf<Set<Int>>()
            for (idx in indices) {
                if (idx !in visited) {
                    val component = mutableSetOf<Int>()
                    val queue = ArrayDeque<Int>()
                    queue.add(idx)
                    while (queue.isNotEmpty()) {
                        val cur = queue.removeFirst()
                        if (cur in visited) continue
                        visited.add(cur)
                        component.add(cur)
                        g[cur]?.forEach { neighbor ->
                            if (neighbor !in visited) queue.add(neighbor)
                        }
                    }
                    result.addAll(splitTextRegion(bboxes, component))
                }
            }
            return result
        }
    }

    /**
     * Majority vote 确定方向。
     * 对应 merge_bboxes_text_region 中的 direction voting。
     */
    private fun determineDirection(textLines: List<QuadBox>): Char {
        if (textLines.isEmpty()) return 'v'
        if (textLines.size == 1) {
            val box = textLines[0]
            val w = box.aabb.width().toFloat()
            val h = box.aabb.height().toFloat()
            return if (h > w) 'v' else 'h'
        }

        val dirs = textLines.map { box ->
            val w = box.aabb.width().toFloat()
            val h = box.aabb.height().toFloat()
            if (h > w) 'v' else 'h'
        }
        val counts = dirs.groupingBy { it }.eachCount()
        val top2 = counts.entries.sortedByDescending { it.value }

        return if (top2.size == 1 || top2[0].value > top2[1].value) {
            top2[0].key
        } else {
            // 平票：取 aspectRatio 最大的
            textLines.maxByOrNull { max(it.aspectRatio, 1f / it.aspectRatio) }?.let { box ->
                val w = box.aabb.width().toFloat()
                val h = box.aabb.height().toFloat()
                if (h > w) 'v' else 'h'
            } ?: 'v'
        }
    }

    private fun mean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return values.sum() / values.size
    }

    private fun std(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

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
            val ra = find(a); val rb = find(b)
            if (ra == rb) return
            when {
                rank[ra] < rank[rb] -> parent[ra] = rb
                rank[ra] > rank[rb] -> parent[rb] = ra
                else -> { parent[rb] = ra; rank[ra]++ }
            }
        }
    }
}
