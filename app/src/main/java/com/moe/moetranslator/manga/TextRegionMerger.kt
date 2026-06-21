package com.moe.moetranslator.manga

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import com.moe.moetranslator.utils.LogCollector
import kotlin.math.PI
import kotlin.math.abs
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
     * 判断近似轴对齐。
     * 直接用 angle 直判：|angle| ≤ 3° 视为 AA。
     */
    private fun isApproxAxisAligned(quad: QuadBox): Boolean {
        val angleDeg = abs(quad.angle) * 180f / PI.toFloat()
        return angleDeg <= 3f
    }

    /**
     * 判断两个 TextRegion 是否应合并。
     * 完整对齐 manga generic.py:653-698 quadrilateral_can_merge_region。
     *
     * @return true 表示应合并
     */
    private fun canMergeRegion(a: TextRegion, b: TextRegion): Boolean {
        val charSize = min(a.quad.fontSize, b.quad.fontSize)
        if (charSize <= 0f) return false

        val tagA = "\"${(a.text ?: "").take(8)}\""
        val tagB = "\"${(b.text ?: "").take(8)}\""

        val aAA = isApproxAxisAligned(a.quad)
        val bAA = isApproxAxisAligned(b.quad)

        // 距离粗筛（AA + Tilted 共用）
        val dist = quadCenterDistance(a, b)
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
        if (a.quad.isVertical != b.quad.isVertical) {
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
            // 横排
            if (w1 > h1 * RATIO || w2 > h2 * RATIO) {
                val accept = abs(x1 - x2) < charSize * charGapTolerance2 ||
                             abs(x1 + w1 - (x2 + w2)) < charSize * charGapTolerance2
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA h-align=$accept")
                return accept
            }
            // 竖排
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
        val angleDiff = abs(a.quad.angle - b.quad.angle) * 180f / PI.toFloat()
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
}
