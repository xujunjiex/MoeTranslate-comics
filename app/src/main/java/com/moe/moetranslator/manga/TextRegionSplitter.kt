package com.moe.moetranslator.manga

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 文本区域分割器。
 * 移植自 manga-image-translator 的 split_text_region，
 * 使用 Kruskal 最小生成树 + 距离标准差判断是否需要分割。
 */
object TextRegionSplitter {

    private const val GAMMA = 0.5f   // 距离容差系数
    private const val SIGMA = 2f     // 标准差倍数

    /**
     * 对一组 TextLine 进行分割，返回分割后的多个组。
     * 如果不需要分割，返回包含单个组的列表。
     */
    fun split(textLines: List<TextLine>): List<List<TextLine>> {
        if (textLines.size <= 1) return listOf(textLines)

        // Case 2: 两个节点
        if (textLines.size == 2) {
            val fs = max(textLines[0].fontSize, textLines[1].fontSize)
            val dist = rectDistance(textLines[0].rect, textLines[1].rect)
            val sameDirection = textLines[0].direction == textLines[1].direction

            if (dist < (1 + GAMMA) * fs && sameDirection) {
                return listOf(textLines)
            }
            return listOf(listOf(textLines[0]), listOf(textLines[1]))
        }

        // Case 3: 3+ 节点，用 MST 分割
        return splitByMST(textLines)
    }

    /**
     * Kruskal MST 分割算法。
     * 1. 构建完全图（边权 = 矩形距离）
     * 2. Kruskal 最小生成树
     * 3. 按边权降序排列，检查是否需要切断最大边
     */
    private fun splitByMST(textLines: List<TextLine>): List<List<TextLine>> {
        val n = textLines.size

        // 构建所有边
        data class Edge(val u: Int, val v: Int, val weight: Float)

        val edges = mutableListOf<Edge>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                edges.add(Edge(i, j, rectDistance(textLines[i].rect, textLines[j].rect)))
            }
        }

        // Kruskal MST
        edges.sortBy { it.weight }
        val parent = IntArray(n) { it }
        val rank = IntArray(n) { 0 }

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

        fun union(a: Int, b: Int): Boolean {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return false
            if (rank[ra] < rank[rb]) parent[ra] = rb
            else if (rank[ra] > rank[rb]) parent[rb] = ra
            else {
                parent[rb] = ra
                rank[ra]++
            }
            return true
        }

        val mstEdges = mutableListOf<Edge>()
        for (edge in edges) {
            if (union(edge.u, edge.v)) {
                mstEdges.add(edge)
            }
        }

        // 按边权降序排列
        mstEdges.sortByDescending { it.weight }

        val distances = mstEdges.map { it.weight.toDouble() }
        val fontSize = textLines.map { it.fontSize.toDouble() }.average().toFloat()
        val distancesStd = stdDev(distances)
        val distancesMean = distances.average()
        val stdThreshold = max(0.3 * fontSize + 5, 5.0)

        // 检查最大边是否需要切断
        val maxDist = distances.firstOrNull() ?: 0.0

        // 检查最大边两端的对齐情况
        val maxEdge = mstEdges.firstOrNull()
        val maxCentroidAlignment = if (maxEdge != null) {
            min(
                abs(textLines[maxEdge.u].centroidX - textLines[maxEdge.v].centroidX),
                abs(textLines[maxEdge.u].centroidY - textLines[maxEdge.v].centroidY)
            )
        } else 0f

        val shouldKeep = (maxDist <= distancesMean + distancesStd * SIGMA
                || maxDist <= fontSize * (1 + GAMMA))
                && (distancesStd < stdThreshold
                || maxCentroidAlignment < 5)

        if (shouldKeep) {
            return listOf(textLines)
        }

        // 切断最大边，递归分割
        val remainingEdges = mstEdges.drop(1)
        val uf = UnionFind(n)
        for (edge in remainingEdges) {
            uf.union(edge.u, edge.v)
        }

        val groups = mutableMapOf<Int, MutableList<TextLine>>()
        for (i in 0 until n) {
            val root = uf.find(i)
            groups.getOrPut(root) { mutableListOf() }.add(textLines[i])
        }

        // 递归分割每个子组
        val result = mutableListOf<List<TextLine>>()
        for (group in groups.values) {
            result.addAll(split(group))
        }
        return result
    }

    private fun stdDev(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }
}

/**
 * Union-Find 数据结构。
 */
private class UnionFind(val size: Int) {
    private val parent = IntArray(size) { it }
    private val rank = IntArray(size) { 0 }

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

    fun union(a: Int, b: Int): Boolean {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return false
        if (rank[ra] < rank[rb]) parent[ra] = rb
        else if (rank[ra] > rank[rb]) parent[rb] = ra
        else {
            parent[rb] = ra
            rank[ra]++
        }
        return true
    }
}
