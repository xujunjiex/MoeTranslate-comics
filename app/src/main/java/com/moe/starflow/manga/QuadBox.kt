package com.moe.starflow.manga

import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 旋转矩形四角点表示，对应 manga-image-translator 的 Quadrilateral。
 *
 * 存储 4 个角点 + 派生几何属性，用于 OCR 前的 box 合并。
 */
class QuadBox(
    val pts: Array<PointF>,  // 4 个角点
    val text: String = "",
    val prob: Float = 1f
) {
    /** 文字方向：基于对角线向量的结构方向 */
    val isVertical: Boolean by lazy {
        // 直接取两条对角线：pt0↔pt2, pt1↔pt3
        val vec1x = pts[2].x - pts[0].x; val vec1y = pts[2].y - pts[0].y
        val vec2x = pts[3].x - pts[1].x; val vec2y = pts[3].y - pts[1].y
        // 点积，如果 < 0 说明方向相反，翻转 v1
        val innerProd = vec1x * vec2x + vec1y * vec2y
        val correctedVec1x = if (innerProd < 0) -vec1x else vec1x
        val correctedVec1y = if (innerProd < 0) -vec1y else vec1y
        // 结构向量 = |v1 + v2| 的两个分量
        val strucVecX = abs(correctedVec1x + vec2x)
        val strucVecY = abs(correctedVec1y + vec2y)
        // x <= y → 竖排
        strucVecX <= strucVecY
    }

    /** 方向标识符（h = 横排，v = 竖排） */
    val direction: String get() = if (isVertical) "v" else "h"

    /** 文字方向（兼容属性名） */
    val assignedDirection: String get() = direction
    // 结构线：对边中点连线
    // p1 = (pts[0]+pts[1])/2, p2 = (pts[2]+pts[3])/2 → 边1中点到边3中点
    // p3 = (pts[1]+pts[2])/2, p4 = (pts[3]+pts[0])/2 → 边2中点到边4中点
    private val structure: Array<PointF> by lazy {
        arrayOf(
            PointF((pts[0].x + pts[1].x) / 2, (pts[0].y + pts[1].y) / 2),
            PointF((pts[2].x + pts[3].x) / 2, (pts[2].y + pts[3].y) / 2),
            PointF((pts[1].x + pts[2].x) / 2, (pts[1].y + pts[2].y) / 2),
            PointF((pts[3].x + pts[0].x) / 2, (pts[3].y + pts[0].y) / 2)
        )
    }

    private fun structureLen(idx1: Int, idx2: Int): Float {
        val dx = structure[idx2].x - structure[idx1].x
        val dy = structure[idx2].y - structure[idx1].y
        return sqrt(dx * dx + dy * dy)
    }

    /** 字体大小：结构线较短边的长度 */
    val fontSize: Float by lazy {
        val len01 = structureLen(0, 1)
        val len23 = structureLen(2, 3)
        min(len01, len23)
    }

    /** 宽高比：结构线较长边 / 较短边 */
    val aspectRatio: Float by lazy {
        val len01 = structureLen(0, 1)
        val len23 = structureLen(2, 3)
        if (len01 > len23) len01 / max(len23, 0.001f) else len23 / max(len01, 0.001f)
    }

    /** 结构线比例：||v_vec|| / ||h_vec||，对齐参考项目 get_transformed_region 的 ratio 计算 */
    val structRatio: Float by lazy {
        val len01 = structureLen(0, 1)
        val len23 = structureLen(2, 3)
        len01 / max(len23, 0.001f)
    }

    /** 主轴角度（弧度）：较长结构线的方向 */
    val angle: Float by lazy {
        val len01 = structureLen(0, 1)
        val len23 = structureLen(2, 3)
        if (len01 >= len23) {
            atan2(structure[1].y - structure[0].y, structure[1].x - structure[0].x)
        } else {
            atan2(structure[3].y - structure[2].y, structure[3].x - structure[2].x)
        }
    }

    /** 中心 x */
    val centroidX: Float by lazy {
        (pts[0].x + pts[1].x + pts[2].x + pts[3].x) / 4
    }

    /** 中心 y */
    val centroidY: Float by lazy {
        (pts[0].y + pts[1].y + pts[2].y + pts[3].y) / 4
    }

    /** 是否近似轴对齐（边方向与水平/垂直轴的 dot product < 0.05） */
    val isApproximateAxisAligned: Boolean by lazy {
        val v1x = structure[1].x - structure[0].x
        val v1y = structure[1].y - structure[0].y
        val v2x = structure[3].x - structure[2].x
        val v2y = structure[3].y - structure[2].y
        val len1 = sqrt(v1x * v1x + v1y * v1y)
        val len2 = sqrt(v2x * v2x + v2y * v2y)
        if (len1 < 1e-6f || len2 < 1e-6f) return@lazy true
        val ux1 = v1x / len1; val uy1 = v1y / len1
        val ux2 = v2x / len2; val uy2 = v2y / len2
        // 检查是否接近水平或垂直
        abs(uy1) < 0.05f || abs(ux1) < 0.05f || abs(uy2) < 0.05f || abs(ux2) < 0.05f
    }

    /** 轴对齐外接矩形 */
    val aabb: Rect by lazy {
        var l = pts[0].x; var r = l; var t = pts[0].y; var b = t
        for (p in pts) {
            if (p.x < l) l = p.x; if (p.x > r) r = p.x
            if (p.y < t) t = p.y; if (p.y > b) b = p.y
        }
        Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    /** 多边形面积（Shoelace formula） */
    val area: Float by lazy {
        var sum = 0f
        val n = pts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += pts[i].x * pts[j].y - pts[j].x * pts[i].y
        }
        abs(sum) / 2f
    }

    /**
     * Chebyshev AABB 距离。
     * 对应 Quadrilateral.distance()，使用轴对齐外接矩形的水平/垂直间隙最大值。
     * 重叠时返回 0。
     */
    fun distance(other: QuadBox): Float {
        val a = this.aabb
        val b = other.aabb
        val dx = max(0, max(b.left - a.right, a.left - b.right))
        val dy = max(0, max(b.top - a.bottom, a.top - b.bottom))
        return max(dx, dy).toFloat()
    }

    /**
     * 凸多边形最小距离。
     * 对应 Quadrilateral.polyDistance()（Shapely Polygon.distance）。
     * 计算两个凸四边形所有边对之间的最小距离。
     * 如果两多边形相交或一个包含另一个，返回 0。
     */
    fun polyDistance(other: QuadBox): Float {
        if (polygonsIntersectOrContain(other)) return 0f

        var minDist = Float.MAX_VALUE
        for (i in 0 until 4) {
            val a1 = pts[i]; val a2 = pts[(i + 1) % 4]
            for (j in 0 until 4) {
                val b1 = other.pts[j]; val b2 = other.pts[(j + 1) % 4]
                val d = segmentToSegmentDistance(a1, a2, b1, b2)
                if (d < minDist) minDist = d
            }
        }
        return minDist
    }

    /**
     * 检查两个凸多边形是否相交或一个包含另一个。
     * 使用分离轴定理（SAT），要求分离轴归一化为单位向量。
     */
    private fun polygonsIntersectOrContain(other: QuadBox): Boolean {
        val polygons = arrayOf(this, other)
        val allPts = arrayOf(this.pts, other.pts)

        for (box in polygons) {
            for (i in 0 until 4) {
                val edge = PointF(
                    box.pts[(i + 1) % 4].x - box.pts[i].x,
                    box.pts[(i + 1) % 4].y - box.pts[i].y
                )
                // 归一化为单位向量
                var len = sqrt(edge.x * edge.x + edge.y * edge.y)
                if (len < 1e-10f) continue
                val nx = -edge.y / len
                val ny = edge.x / len

                // 投影所有点
                var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
                var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
                for (p in allPts[0]) {
                    val proj = p.x * nx + p.y * ny
                    if (proj < minA) minA = proj; if (proj > maxA) maxA = proj
                }
                for (p in allPts[1]) {
                    val proj = p.x * nx + p.y * ny
                    if (proj < minB) minB = proj; if (proj > maxB) maxB = proj
                }
                if (maxA < minB || maxB < minA) return false
            }
        }
        return true
    }

    companion object {
        private fun segmentToSegmentDistance(a1: PointF, a2: PointF, b1: PointF, b2: PointF): Float {
            if (segmentsIntersect(a1, a2, b1, b2)) return 0f
            var minDist = pointToSegmentDistance(a1, b1, b2)
            minDist = min(minDist, pointToSegmentDistance(a2, b1, b2))
            minDist = min(minDist, pointToSegmentDistance(b1, a1, a2))
            minDist = min(minDist, pointToSegmentDistance(b2, a1, a2))
            return minDist
        }

        private fun segmentsIntersect(a1: PointF, a2: PointF, b1: PointF, b2: PointF): Boolean {
            val d1 = cross(a1, a2, b1)
            val d2 = cross(a1, a2, b2)
            val d3 = cross(b1, b2, a1)
            val d4 = cross(b1, b2, a2)
            if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
                ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
            ) return true
            return false
        }

        private fun cross(o: PointF, a: PointF, b: PointF): Float {
            return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        }

        private fun pointToSegmentDistance(p: PointF, a: PointF, b: PointF): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1e-10f) {
                val ex = p.x - a.x; val ey = p.y - a.y
                return sqrt(ex * ex + ey * ey)
            }
            var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
            t = t.coerceIn(0f, 1f)
            val projX = a.x + t * dx
            val projY = a.y + t * dy
            val ex = p.x - projX; val ey = p.y - projY
            return sqrt(ex * ex + ey * ey)
        }
    }
}
