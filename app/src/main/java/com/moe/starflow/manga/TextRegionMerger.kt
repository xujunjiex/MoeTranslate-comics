package com.moe.starflow.manga

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import com.moe.starflow.utils.LogCollector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PP-OCRv5 文字行/区域合并器。
 *
 * 对齐 manga-image-translator textline_merge 算法：
 * - quadrilateral_can_merge_region()（generic.py:653-698）
 * - merge_bboxes_text_region()（textline_merge/__init__.py:110-182）
 * - split_text_region()（textline_merge/__init__.py:10-83）
 *
 * **统一入口**：OCR 前/后都通过 merge() 入口；text 字段决定是否拼接文字。
 *
 * **调试日志**：受 enableDebugLogging() 控制，默认关闭，零开销。
 */
object TextRegionMerger {

    private const val TAG = "TextRegionMerger"

    // ========== 硬编码参数（对齐 manga 调用值） ==========
    private const val RATIO = 1.9f                   // 方向判断阈值
    private const val ASPECT_RATIO_TOL = 1.3f        // 长宽比交叉阈值（manga 调用 1.3）
    private const val CHAR_GAP_TOLERANCE = 1f        // AA 分支 char gap（manga 调用 1）
    private const val FONT_SIZE_RATIO_AA = 2.0f      // AA 分支字号比（manga 调用 2.0）
    private const val TILTED_ANGLE_DIFF_MAX = 15f    // 15° 倾斜角度差
    private const val TILTED_FS_DIFF_MAX = 0.25f     // 字号差比

    // ========== 可调参数 ==========
    @Volatile private var discardConnectionGap: Float = MergeParams.DISCARD_CONNECTION_GAP_DEFAULT
    @Volatile private var charGapTolerance2: Float = MergeParams.CHAR_GAP_TOLERANCE2_DEFAULT
    @Volatile private var debugEnabled: Boolean = false

    /**
     * 启用/禁用调试日志（默认关闭，零开销）。
     */
    fun enableDebugLogging(enabled: Boolean) {
        debugEnabled = enabled
    }

    /**
     * 从 SharedPreferences 刷新可调参数。
     */
    fun refreshParams(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        discardConnectionGap = prefs.getFloat(
            "merge_discard_gap",
            MergeParams.DISCARD_CONNECTION_GAP_DEFAULT
        ).coerceIn(MergeParams.MIN_DISCARD_GAP, MergeParams.MAX_DISCARD_GAP)
        charGapTolerance2 = prefs.getFloat(
            "merge_char_gap2",
            MergeParams.CHAR_GAP_TOLERANCE2_DEFAULT
        ).coerceIn(MergeParams.MIN_CHAR_GAP2, MergeParams.MAX_CHAR_GAP2)
    }

    /**
     * 重置参数为默认值。
     */
    fun resetParams(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putFloat("merge_discard_gap", MergeParams.DISCARD_CONNECTION_GAP_DEFAULT)
            .putFloat("merge_char_gap2", MergeParams.CHAR_GAP_TOLERANCE2_DEFAULT)
            .apply()
        refreshParams(context)
    }

    // ========== 工具类 ==========

    /**
     * 加权平均。
     */
    private fun weightedAverage(values: List<Float>, weights: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val totalWeight = weights.sum()
        if (totalWeight <= 0f) return values.average().toFloat()
        return values.zip(weights).sumOf { (v, w) -> (v * w).toDouble() }.toFloat() / totalWeight
    }

    /**
     * 并查集（Kruskal MST 用）。
     */
    internal class UnionFind(size: Int) {
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

        /**
         * @return true 表示合并成功；false 表示已在同一集合。
         */
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

    /**
     * MST 边。
     */
    internal data class MSTEdge(val u: Int, val v: Int, val weight: Float)

    /**
     * 计算 quad 中心点距离。
     */
    private fun quadCenterDistance(a: TextRegion, b: TextRegion): Float {
        val dx = b.quad.centroidX - a.quad.centroidX
        val dy = b.quad.centroidY - a.quad.centroidY
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * AABB 距离（Chebyshev 距离）：两个 AABB 之间的最大轴向间隙。
     * 对齐 Shapely Polygon.distance()，重叠时返回 0。
     */
    private fun aabbDistance(a: TextRegion, b: TextRegion): Float {
        val ra = a.quad.aabb
        val rb = b.quad.aabb
        val dx = max(0, max(rb.left - ra.right, ra.left - rb.right))
        val dy = max(0, max(rb.top - ra.bottom, ra.top - rb.bottom))
        return max(dx, dy).toFloat()
    }

    /**
     * 从 quad 顶边向量计算文字角度（弧度）。
     * 不使用 QuadBox.angle（结构线方向可能反向 180°）。
     * 对齐 ocrResultToTextLines 的 atan2(topDy, topDx) 算法。
     */
    private fun quadTopEdgeAngle(quad: QuadBox): Float {
        val topDx = quad.pts[1].x - quad.pts[0].x
        val topDy = quad.pts[1].y - quad.pts[0].y
        return atan2(topDy, topDx)
    }

    /**
     * 从 quad 顶边向量计算文字角度（度），±3° 内归零。
     */
    private fun quadTopEdgeAngleDeg(quad: QuadBox): Float {
        var angleDeg = quadTopEdgeAngle(quad) * 180f / PI.toFloat()
        if (abs(angleDeg) <= 3f) angleDeg = 0f
        return angleDeg
    }

    /**
     * 判断近似轴对齐。
     * 从顶边向量计算角度，归一化到 [0, 180°) 后判断。
     */
    private fun isApproxAxisAligned(quad: QuadBox): Boolean {
        val angleDeg = abs(quadTopEdgeAngle(quad)) * 180f / PI.toFloat()
        val normalized = angleDeg % 180f
        return normalized <= 3f || normalized >= 177f
    }

    /**
     * 判断两个 TextRegion 是否应合并。
     * 完整对齐 manga generic.py:653-698 quadrilateral_can_merge_region。
     *
     * @return true 表示应合并
     */
    private fun canMergeRegion(a: TextRegion, b: TextRegion, aIndex: Int, bIndex: Int): Boolean {
        val charSize = min(a.quad.fontSize, b.quad.fontSize)
        if (charSize <= 0f) return false

        // 编号 = 输入框索引（与调试面板「原始识别」的 [i] 对应）
        val tagA = "[$aIndex]\"${(a.text ?: "").take(8)}\""
        val tagB = "[$bIndex]\"${(b.text ?: "").take(8)}\""

        val aAA = isApproxAxisAligned(a.quad)
        val bAA = isApproxAxisAligned(b.quad)

        // 距离粗筛（AA + Tilted 共用，对齐 Shapely Polygon.distance）
        val dist = aabbDistance(a, b)
        val maxGap = discardConnectionGap * charSize
        if (dist > maxGap) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT dist=${String.format("%.1f", dist)} > $maxGap")
            return false
        }

        // 字号比（AA + Tilted 共用）
        val fsRatio = max(a.quad.fontSize, b.quad.fontSize) / charSize
        if (fsRatio > FONT_SIZE_RATIO_AA) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT fsRatio=${String.format("%.2f", fsRatio)} > $FONT_SIZE_RATIO_AA")
            return false
        }

        // 宽高比交叉检查（AA + Tilted 共用）
        if (a.quad.aspectRatio > ASPECT_RATIO_TOL && b.quad.aspectRatio < 1f / ASPECT_RATIO_TOL) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT aspectRatio cross")
            return false
        }
        if (b.quad.aspectRatio > ASPECT_RATIO_TOL && a.quad.aspectRatio < 1f / ASPECT_RATIO_TOL) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT aspectRatio cross")
            return false
        }

        // 方向一致性（AA + Tilted 共用）
        // 近似正方形的框方向不可靠（对角线结构向量无意义），跳过方向检查
        val aSquare = a.quad.aspectRatio < ASPECT_RATIO_TOL
        val bSquare = b.quad.aspectRatio < ASPECT_RATIO_TOL
        if (!aSquare && !bSquare && a.quad.isVertical != b.quad.isVertical) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT direction mismatch")
            return false
        }

        // ========== AA 分支（manga L671-687）==========
        if (aAA && bAA) {
            // char_gap_tolerance（manga 调用 1.0）
            if (dist >= charSize * CHAR_GAP_TOLERANCE) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT dist=${String.format("%.1f", dist)} >= ${charSize * CHAR_GAP_TOLERANCE}")
                return false
            }
            val x1 = a.quad.aabb.left.toFloat()
            val w1 = a.quad.aabb.width().toFloat()
            val h1 = a.quad.aabb.height().toFloat()
            val x2 = b.quad.aabb.left.toFloat()
            val w2 = b.quad.aabb.width().toFloat()
            val h2 = b.quad.aabb.height().toFloat()

            // 中心对齐
            if (abs(x1 + w1 / 2 - (x2 + w2 / 2)) < charGapTolerance2) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA ACCEPT center aligned")
                return true
            }
            // 方向互斥
            if (w1 > h1 * RATIO && h2 > w2 * RATIO) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT mixed orient")
                return false
            }
            if (w2 > h2 * RATIO && h1 > w1 * RATIO) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT mixed orient")
                return false
            }
            // 横排：边对齐（对齐参考：charSize * charGapTolerance2）
            if (w1 > h1 * RATIO || w2 > h2 * RATIO) {
                val accept = abs(x1 - x2) < charSize * charGapTolerance2 ||
                             abs(x1 + w1 - (x2 + w2)) < charSize * charGapTolerance2
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA h-align=$accept (Δleft=${String.format("%.0f", abs(x1-x2))}, Δright=${String.format("%.0f", abs(x1+w1-(x2+w2)))})")
                return accept
            }
            // 竖排：边对齐（对齐参考：charSize * charGapTolerance2）
            if (h1 > w1 * RATIO || h2 > w2 * RATIO) {
                val y1 = a.quad.aabb.top.toFloat()
                val y2 = b.quad.aabb.top.toFloat()
                val accept = abs(y1 - y2) < charSize * charGapTolerance2 ||
                             abs(y1 + h1 - (y2 + h2)) < charSize * charGapTolerance2
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA v-align=$accept")
                return accept
            }
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT no direction match")
            return false
        }

        // ========== Tilted 分支（manga L688-697）==========
        val angleDiff = abs(quadTopEdgeAngle(a.quad) - quadTopEdgeAngle(b.quad)) * 180f / PI.toFloat()
        if (angleDiff > TILTED_ANGLE_DIFF_MAX) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED REJECT angleDiff=${String.format("%.1f", angleDiff)} > $TILTED_ANGLE_DIFF_MAX")
            return false
        }
        val fsA = a.quad.fontSize
        val fsB = b.quad.fontSize
        val fsMin = min(fsA, fsB)
        val fsDiff = abs(fsA - fsB) / fsMin
        if (fsDiff > TILTED_FS_DIFF_MAX) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED REJECT fsDiff=${String.format("%.2f", fsDiff)} > $TILTED_FS_DIFF_MAX")
            return false
        }
        if (dist > fsMin * charGapTolerance2) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED REJECT dist=${String.format("%.1f", dist)} > ${fsMin * charGapTolerance2}")
            return false
        }
        if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED ACCEPT")
        return true
    }

    // ========== splitTextRegion（对齐 textline_merge/__init__.py L10-83） ==========

    /**
     * MST 分析拆分过大的文本区域。
     * 完整对齐 split_text_region()（textline_merge/__init__.py:10-83）。
     */
    private fun splitTextRegion(
        regions: List<TextRegion>,
        connectedIndices: Set<Int>,
        gamma: Float = 0.5f,
        sigma: Float = 2f
    ): List<Set<Int>> {
        val indices = connectedIndices.toList()
        if (indices.size == 1) return listOf(setOf(indices[0]))

        if (indices.size == 2) {
            // 2 元素组件无传递性：能组成连通分量说明 canMergeRegion 已认可这对
            // （AABB 间隙 < 1×字符宽 + 字号/方向/对齐校验）。阶段二在此没有"防过度合并"
            // 职责，直接保留——旧实现用中心距离 < 1.5×字号 重判，与 canMerge 的 AABB 间隙
            // 指标矛盾，导致竖排相邻行（如 [2][3]、[7][8]）被错误拆开。
            if (debugEnabled) LogCollector.d(TAG, "splitTextRegion[2]: keep（canMerge 已认可）")
            return listOf(setOf(indices[0], indices[1]))
        }

        // case 3+: MST
        val allEdges = mutableListOf<MSTEdge>()
        for (i in indices.indices) {
            for (j in i + 1 until indices.size) {
                val u = indices[i]
                val v = indices[j]
                allEdges.add(MSTEdge(u, v, quadCenterDistance(regions[u], regions[v])))
            }
        }
        allEdges.sortBy { it.weight }
        val uf = UnionFind(regions.size)
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
        val distancesMean = distances.average().toFloat()
        val distancesStd = if (distances.size > 1) {
            val mean = distancesMean
            sqrt(distances.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f
        val avgFontSize = indices.map { regions[it].quad.fontSize }.average().toFloat()
        val stdThreshold = max(0.3f * avgFontSize + 5f, 5f)

        val maxEdge = sortedEdges.first()
        val shouldKeep = (maxEdge.weight <= distancesMean + distancesStd * sigma ||
                maxEdge.weight <= avgFontSize * (1 + gamma)) &&
                distancesStd < stdThreshold

        if (debugEnabled) {
            LogCollector.d(TAG, "splitTextRegion[${indices.size}]: " +
                "maxEdge=${String.format("%.1f", maxEdge.weight)} " +
                "mean=${String.format("%.1f", distancesMean)} std=${String.format("%.1f", distancesStd)} " +
                "fontSize=${String.format("%.1f", avgFontSize)} keep=$shouldKeep")
        }

        if (shouldKeep) {
            return listOf(connectedIndices)
        }

        // 拆分：移除最大边，递归处理两个子图
        val remainingEdges = sortedEdges.drop(1)
        val uf2 = UnionFind(regions.size)
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
                result.addAll(splitTextRegion(regions, component, gamma, sigma))
            }
        }
        return result
    }

    // ========== merge 主入口 ==========

    /**
     * 主入口：合并 text regions 为文本组。
     *
     * @param regions 待合并的 text region 列表
     * @param params 可调参数（不传则使用当前 refreshParams 后的值）
     * @return 合并后的 text region groups（按阅读顺序：横排 top→bottom，竖排 right→left）
     */
    fun merge(
        regions: List<TextRegion>,
        params: MergeParams = MergeParams(discardConnectionGap, charGapTolerance2),
        verticalDirection: TextDirection = TextDirection.VERTICAL_RL
    ): List<TextRegionGroup> {
        if (regions.isEmpty()) return emptyList()

        // 临时覆盖可调参数
        val savedGap = discardConnectionGap
        val savedGap2 = charGapTolerance2
        discardConnectionGap = params.discardConnectionGap
        charGapTolerance2 = params.charGapTolerance2

        try {
            if (regions.size == 1) {
                val region = regions[0]
                val rect = region.quad.aabb
                val quadPoints = arrayOf(
                    PointF(rect.left.toFloat(), rect.top.toFloat()),
                    PointF(rect.right.toFloat(), rect.top.toFloat()),
                    PointF(rect.right.toFloat(), rect.bottom.toFloat()),
                    PointF(rect.left.toFloat(), rect.bottom.toFloat())
                )
                val direction = if (region.quad.isVertical) verticalDirection else TextDirection.HORIZONTAL
                return listOf(
                    TextRegionGroup(
                        rect = rect,
                        quadPoints = quadPoints,
                        texts = listOf(region.text ?: ""),
                        direction = direction,
                        fontSize = region.quad.fontSize,
                        angle = quadTopEdgeAngleDeg(region.quad),
                        score = region.score,
                        center = PointF(rect.exactCenterX(), rect.exactCenterY()),
                        members = listOf(region)
                    )
                )
            }

            if (debugEnabled) LogCollector.d(TAG, "merge: 输入 ${regions.size} 个 region")

            // Step 1: canMergeRegion 建图 → 连通分量
            val n = regions.size
            val adjacency = Array(n) { mutableSetOf<Int>() }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (canMergeRegion(regions[i], regions[j], i, j)) {
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
            if (debugEnabled) LogCollector.d(TAG, "merge: 连通分量 ${connectedComponents.size} 个")

            // Step 2: splitTextRegion MST 拆分
            val regionIndices = mutableListOf<Set<Int>>()
            for (component in connectedComponents) {
                regionIndices.addAll(splitTextRegion(regions, component))
            }
            if (debugEnabled) LogCollector.d(TAG, "merge: 拆分后 ${regionIndices.size} 个区域")

            // Step 3: 方向投票 + 排序 + 合并
            val result = mutableListOf<TextRegionGroup>()
            for (nodeSet in regionIndices) {
                val nodes = nodeSet.toList()
                val members = nodes.map { regions[it] }

                // 方向投票（只统计有明确方向的框，正方形不参与投票）
                val directionalMembers = members.filter { it.quad.aspectRatio >= ASPECT_RATIO_TOL }
                val voters = if (directionalMembers.isNotEmpty()) directionalMembers else members
                val directionCounts = voters.groupBy { it.quad.isVertical }.mapValues { it.value.size }
                val majorityVertical = (directionCounts[true] ?: 0) > (directionCounts[false] ?: 0)
                val direction = if (majorityVertical) verticalDirection else TextDirection.HORIZONTAL

                // 按方向排序（同行/列时用 x/y 坐标做二级排序，保证稳定性）
                val sortedNodes = if (direction == TextDirection.HORIZONTAL) {
                    nodes.sortedWith(Comparator { a, b ->
                        val ya = regions[a].quad.centroidY
                        val yb = regions[b].quad.centroidY
                        if (ya != yb) ya.compareTo(yb) else regions[a].quad.centroidX.compareTo(regions[b].quad.centroidX)
                    })
                } else {
                    nodes.sortedWith(Comparator { a, b ->
                        val xa = regions[a].quad.centroidX
                        val xb = regions[b].quad.centroidX
                        if (xa != xb) xb.compareTo(xa) else regions[a].quad.centroidY.compareTo(regions[b].quad.centroidY)
                    })
                }

                // AABB union
                val aabbs = sortedNodes.map { regions[it].quad.aabb }
                val unionRect = Rect(
                    aabbs.minOf { it.left },
                    aabbs.minOf { it.top },
                    aabbs.maxOf { it.right },
                    aabbs.maxOf { it.bottom }
                )

                val combinedTexts = sortedNodes.map { regions[it].text ?: "" }
                val minFontSize = members.minOf { it.quad.fontSize }
                val avgScore = members.map { it.score }.average().toFloat()
                val weightedAngle = weightedAverage(
                    members.map { quadTopEdgeAngleDeg(it.quad) },
                    members.map { it.quad.fontSize }
                )
                val mergedCenter = PointF(unionRect.exactCenterX(), unionRect.exactCenterY())

                // 中心加权 quad 角点（简化版：用 unionRect）
                val quadPoints = arrayOf(
                    PointF(unionRect.left.toFloat(), unionRect.top.toFloat()),
                    PointF(unionRect.right.toFloat(), unionRect.top.toFloat()),
                    PointF(unionRect.right.toFloat(), unionRect.bottom.toFloat()),
                    PointF(unionRect.left.toFloat(), unionRect.bottom.toFloat())
                )

                result.add(TextRegionGroup(
                    rect = unionRect,
                    quadPoints = quadPoints,
                    texts = combinedTexts,
                    direction = direction,
                    fontSize = minFontSize,
                    angle = weightedAngle,
                    score = avgScore,
                    center = mergedCenter,
                    members = members
                ))

                if (debugEnabled) {
                    LogCollector.d(TAG, "merge: 区域 ${members.size} 行, dir=$direction, " +
                            "fs=${String.format("%.1f", minFontSize)}, text='${combinedTexts.first().take(20)}'")
                }
            }

            if (debugEnabled) LogCollector.d(TAG, "merge: 输出 ${result.size} 个文本区域")
            return result
        } finally {
            // 恢复参数
            if (params.discardConnectionGap != savedGap ||
                params.charGapTolerance2 != savedGap2) {
                discardConnectionGap = savedGap
                charGapTolerance2 = savedGap2
            }
        }
    }
}
