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
}
