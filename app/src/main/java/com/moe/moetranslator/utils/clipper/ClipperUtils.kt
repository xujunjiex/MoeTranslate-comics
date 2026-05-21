package com.moe.moetranslator.utils.clipper

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Lightweight Clipper2 type aliases / data classes
// (Only what the utility functions below need.)
// ---------------------------------------------------------------------------

data class Point64(val x: Long, val y: Long) {
    companion object {
        /** Construct Point64 from PointD, rounding to nearest Long. */
        operator fun invoke(pt: PointD): Point64 =
            Point64(kotlin.math.round(pt.x).toLong(), kotlin.math.round(pt.y).toLong())
    }
}

data class PointD(val x: Double, val y: Double) {
    companion object {
        /** Construct PointD from Point64. */
        operator fun invoke(pt: Point64): PointD =
            PointD(pt.x.toDouble(), pt.y.toDouble())
    }
}

data class Rect64(
    var left: Long,
    var top: Long,
    var right: Long,
    var bottom: Long,
) {
    /** Convert this rectangle to a 4-vertex Path64. */
    fun asPath(): Path64 = mutableListOf(
        Point64(left, top),
        Point64(right, top),
        Point64(right, bottom),
        Point64(left, bottom)
    )
}

/** A single polygon (list of [Point64] vertices). */
typealias Path64 = MutableList<Point64>

/** A collection of polygons. */
typealias Paths64 = MutableList<Path64>

/** A single polygon with [Double] coordinates. */
typealias PathD = MutableList<PointD>

enum class ClipType { None, Union, Difference, Intersection, Xor }
enum class FillRule { EvenOdd, NonZero, Positive, Negative }

// ---------------------------------------------------------------------------
// Clipper utility object
// ---------------------------------------------------------------------------

object Clipper {

    /** An "invalid" rect whose left == Long.MAX_VALUE (un-initialised). */
    val InvalidRect64: Rect64 =
        Rect64(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)

    // -----------------------------------------------------------------------
    // Area (Path64) - shoelace formula
    // -----------------------------------------------------------------------
    fun Area(path: Path64): Double {
        val cnt = path.size
        if (cnt < 3) return 0.0
        var a = 0.0
        var prevPt = path[cnt - 1]
        for (pt in path) {
            a += (prevPt.y + pt.y).toDouble() * (prevPt.x - pt.x).toDouble()
            prevPt = pt
        }
        return a * 0.5
    }

    // -----------------------------------------------------------------------
    // Area (Paths64) - sum of areas
    // -----------------------------------------------------------------------
    @JvmName("AreaPaths")
    fun Area(paths: Paths64): Double {
        var a = 0.0
        for (path in paths) a += Area(path)
        return a
    }

    // -----------------------------------------------------------------------
    // IsPositive (Path64)
    // -----------------------------------------------------------------------
    fun IsPositive(poly: Path64): Boolean = Area(poly) >= 0

    // -----------------------------------------------------------------------
    // ReversePath (Path64) - returns a reversed copy
    // -----------------------------------------------------------------------
    fun ReversePath(path: Path64): Path64 {
        val result = path.toMutableList()
        result.reverse()
        return result
    }

    // -----------------------------------------------------------------------
    // ReversePaths (Paths64) - returns reversed copies
    // -----------------------------------------------------------------------
    fun ReversePaths(paths: Paths64): Paths64 {
        val result = mutableListOf<Path64>()
        for (t in paths) result.add(ReversePath(t))
        return result
    }

    // -----------------------------------------------------------------------
    // GetBounds (Path64) - returns Rect64
    // -----------------------------------------------------------------------
    fun GetBounds(path: Path64): Rect64 {
        var result = Rect64(
            InvalidRect64.left,
            InvalidRect64.top,
            InvalidRect64.right,
            InvalidRect64.bottom,
        )
        for (pt in path) {
            if (pt.x < result.left) result.left = pt.x
            if (pt.x > result.right) result.right = pt.x
            if (pt.y < result.top) result.top = pt.y
            if (pt.y > result.bottom) result.bottom = pt.y
        }
        return if (result.left == Long.MAX_VALUE) Rect64(0, 0, 0, 0) else result
    }

    // -----------------------------------------------------------------------
    // GetBounds (Paths64) - returns Rect64
    // -----------------------------------------------------------------------
    @JvmName("GetBoundsPaths")
    fun GetBounds(paths: Paths64): Rect64 {
        var result = Rect64(
            InvalidRect64.left,
            InvalidRect64.top,
            InvalidRect64.right,
            InvalidRect64.bottom,
        )
        for (path in paths) {
            for (pt in path) {
                if (pt.x < result.left) result.left = pt.x
                if (pt.x > result.right) result.right = pt.x
                if (pt.y < result.top) result.top = pt.y
                if (pt.y > result.bottom) result.bottom = pt.y
            }
        }
        return if (result.left == Long.MAX_VALUE) Rect64(0, 0, 0, 0) else result
    }

    // -----------------------------------------------------------------------
    // Sqr (Double) and Sqr (Long) - square
    // -----------------------------------------------------------------------
    fun Sqr(value: Double): Double = value * value

    fun Sqr(value: Long): Double = value.toDouble() * value.toDouble()

    // -----------------------------------------------------------------------
    // DistanceSqr (Point64, Point64) - squared distance
    // -----------------------------------------------------------------------
    fun DistanceSqr(pt1: Point64, pt2: Point64): Double =
        Sqr(pt1.x - pt2.x) + Sqr(pt1.y - pt2.y)

    // -----------------------------------------------------------------------
    // StripDuplicates (Path64, bool) - remove consecutive duplicates
    // -----------------------------------------------------------------------
    fun StripDuplicates(path: Path64, isClosedPath: Boolean): Path64 {
        val cnt = path.size
        val result = mutableListOf<Point64>()
        if (cnt == 0) return result
        var lastPt = path[0]
        result.add(lastPt)
        for (i in 1 until cnt) {
            if (lastPt != path[i]) {
                lastPt = path[i]
                result.add(lastPt)
            }
        }
        if (isClosedPath && lastPt == result[0]) {
            result.removeAt(result.size - 1)
        }
        return result
    }

    // -----------------------------------------------------------------------
    // Ellipse (Point64, double, double, int) - generate ellipse polygon
    // -----------------------------------------------------------------------
    fun Ellipse(
        center: Point64,
        radiusX: Double,
        radiusY: Double = 0.0,
        steps: Int = 0,
    ): Path64 {
        if (radiusX <= 0) return mutableListOf()
        val ry = if (radiusY <= 0) radiusX else radiusY
        val s = if (steps <= 2) {
            ceil(PI * sqrt((radiusX + ry) / 2.0)).toInt()
        } else {
            steps
        }
        val si = sin(2.0 * PI / s)
        val co = cos(2.0 * PI / s)
        var dx = co
        var dy = si
        val result = mutableListOf<Point64>()
        result.add(Point64(round(center.x + radiusX).toLong(), center.y))
        for (i in 1 until s) {
            result.add(
                Point64(
                    round(center.x + radiusX * dx).toLong(),
                    round(center.y + ry * dy).toLong(),
                ),
            )
            val x = dx * co - dy * si
            dy = dy * co + dx * si
            dx = x
        }
        return result
    }

    // -----------------------------------------------------------------------
    // PerpendicDistFromLineSqrd (Point64, Point64, Point64)
    // Perpendicular distance squared from point to line
    // -----------------------------------------------------------------------
    fun PerpendicDistFromLineSqrd(
        pt: Point64,
        line1: Point64,
        line2: Point64,
    ): Double {
        val a = pt.x.toDouble() - line1.x
        val b = pt.y.toDouble() - line1.y
        val c = line2.x.toDouble() - line1.x
        val d = line2.y.toDouble() - line1.y
        if (c == 0.0 && d == 0.0) return 0.0
        return Sqr(a * d - c * b) / (c * c + d * d)
    }
}

// ---------------------------------------------------------------------------
// InternalClipper - utility functions used by ClipperOffset and ClipperEngine
// ---------------------------------------------------------------------------

object InternalClipper {

    const val MaxCoord = Long.MAX_VALUE / 4
    const val floatingPointTolerance = 1E-12

    /** Cross product of two 2D vectors (PointD). */
    fun CrossProduct(vec1: PointD, vec2: PointD): Double =
        vec1.y * vec2.x - vec2.y * vec1.x

    /** Dot product of two 2D vectors (PointD). */
    fun DotProduct(vec1: PointD, vec2: PointD): Double =
        vec1.x * vec2.x + vec1.y * vec2.y

    /**
     * Get the intersection point of two lines defined by segments.
     * Returns the intersection point, or a default PointD if lines are parallel.
     * The result is clamped to the first segment's endpoints.
     */
    fun GetLineIntersectPt(
        ln1a: PointD, ln1b: PointD,
        ln2a: PointD, ln2b: PointD
    ): PointD {
        val dy1 = ln1b.y - ln1a.y
        val dx1 = ln1b.x - ln1a.x
        val dy2 = ln2b.y - ln2a.y
        val dx2 = ln2b.x - ln2a.x
        val det = dy1 * dx2 - dy2 * dx1
        if (det == 0.0) return PointD(0.0, 0.0)

        val t = ((ln1a.x - ln2a.x) * dy2 - (ln1a.y - ln2a.y) * dx2) / det
        return when {
            t <= 0.0 -> ln1a
            t >= 1.0 -> ln1b
            else -> PointD(ln1a.x + t * dx1, ln1a.y + t * dy1)
        }
    }

    /** Cross product of three Point64 points (2D). */
    fun CrossProduct(pt1: Point64, pt2: Point64, pt3: Point64): Double =
        (pt2.x - pt1.x).toDouble() * (pt3.y - pt2.y).toDouble() -
                (pt2.y - pt1.y).toDouble() * (pt3.x - pt2.x).toDouble()

    /** Dot product of three Point64 points (as vectors from pt1->pt2 and pt2->pt3). */
    fun DotProduct(pt1: Point64, pt2: Point64, pt3: Point64): Double =
        (pt2.x - pt1.x).toDouble() * (pt3.x - pt2.x).toDouble() +
                (pt2.y - pt1.y).toDouble() * (pt3.y - pt2.y).toDouble()
}
