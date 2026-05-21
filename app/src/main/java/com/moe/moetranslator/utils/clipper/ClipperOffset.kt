/*******************************************************************************
 * Ported from Clipper2Lib/Clipper.Offset.cs
 * Author    :  AngusJohnson
 * Date      :  11 October 2025
 * Copyright :  Angus Johnson 2010-2025
 * Purpose   :  Path Offset (Inflate/Shrink)
 * License   :  https://www.boost.org/LICENSE_1_0.txt
 * Kotlin port by MoeTranslate project
 ******************************************************************************/

package com.moe.moetranslator.utils.clipper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class JoinType { Miter, Square, Bevel, Round }

enum class EndType { Polygon, Joined, Butt, Square, Round }

typealias DeltaCallback64 = (Path64, PathD, Int, Int) -> Double

class ClipperOffset(
    miterLimit: Double = 2.0,
    arcTolerance: Double = 0.0
) {

    private class Group(
        paths: Paths64,
        val joinType: JoinType,
        val endType: EndType = EndType.Polygon
    ) {
        val inPaths: Paths64 = mutableListOf()
        val pathsReversed: Boolean
        val lowestPathIdx: Int

        init {
            val isJoined = (endType == EndType.Polygon) || (endType == EndType.Joined)
            for (path in paths) {
                inPaths.add(Clipper.StripDuplicates(path, isJoined))
            }

            if (endType == EndType.Polygon) {
                val result = GetLowestPathInfo(inPaths)
                lowestPathIdx = result.first
                pathsReversed = (result.first >= 0) && result.second
            } else {
                lowestPathIdx = -1
                pathsReversed = false
            }
        }
    }

    companion object {
        private const val Tolerance = 1.0E-12

        // Clipper2 approximates arcs by using series of relatively short straight
        // line segments. And logically, shorter line segments will produce better arc
        // approximations. But very short segments can degrade performance, usually
        // with little or no discernable improvement in curve quality. Very short
        // segments can even detract from curve quality, due to the effects of integer
        // rounding. Since there isn't an optimal number of line segments for any given
        // arc radius (that perfectly balances curve approximation with performance),
        // arc tolerance is user defined. Nevertheless, when the user doesn't define
        // an arc tolerance (ie leaves alone the 0 default value), the calculated
        // default arc tolerance (offset_radius / 500) generally produces good (smooth)
        // arc approximations without producing excessively small segment lengths.
        // See also: https://www.angusj.com/clipper2/Docs/Trigonometry.htm
        private const val arc_const = 0.002 // <-- 1/500

        internal fun GetUnitNormal(pt1: Point64, pt2: Point64): PointD {
            val dx = (pt2.x - pt1.x).toDouble()
            val dy = (pt2.y - pt1.y).toDouble()
            if (dx == 0.0 && dy == 0.0) return PointD(0.0, 0.0)

            val f = 1.0 / sqrt(dx * dx + dy * dy)
            return PointD(dy * f, -dx * f)
        }

        internal fun GetLowestPathInfo(paths: Paths64): Pair<Int, Boolean> {
            var idx = -1
            var isNegArea = false
            var botPtX = Long.MAX_VALUE
            var botPtY = Long.MIN_VALUE
            for (i in paths.indices) {
                var a = Double.MAX_VALUE
                for (pt in paths[i]) {
                    if (pt.y < botPtY || (pt.y == botPtY && pt.x >= botPtX)) continue
                    if (a == Double.MAX_VALUE) {
                        a = Clipper.Area(paths[i])
                        if (a == 0.0) break // invalid closed path so break from inner loop
                        isNegArea = a < 0
                    }
                    idx = i
                    botPtX = pt.x
                    botPtY = pt.y
                }
            }
            return Pair(idx, isNegArea)
        }

        private fun TranslatePoint(pt: PointD, dx: Double, dy: Double): PointD {
            return PointD(pt.x + dx, pt.y + dy)
        }

        private fun ReflectPoint(pt: PointD, pivot: PointD): PointD {
            return PointD(pivot.x + (pivot.x - pt.x), pivot.y + (pivot.y - pt.y))
        }

        private fun AlmostZero(value: Double, epsilon: Double = 0.001): Boolean {
            return abs(value) < epsilon
        }

        private fun Hypotenuse(x: Double, y: Double): Double {
            return sqrt(x * x + y * y)
        }

        private fun NormalizeVector(vec: PointD): PointD {
            val h = Hypotenuse(vec.x, vec.y)
            if (AlmostZero(h)) return PointD(0.0, 0.0)
            val inverseHypot = 1.0 / h
            return PointD(vec.x * inverseHypot, vec.y * inverseHypot)
        }

        private fun GetAvgUnitVector(vec1: PointD, vec2: PointD): PointD {
            return NormalizeVector(PointD(vec1.x + vec2.x, vec1.y + vec2.y))
        }
    }

    private val _groupList: MutableList<Group> = mutableListOf()
    private var pathOut: Path64 = mutableListOf()
    private val _normals: PathD = mutableListOf()
    private var _solution: Paths64 = mutableListOf()

    private var _groupDelta: Double = 0.0 // *0.5 for open paths; *-1.0 for negative areas
    private var _delta: Double = 0.0
    private var _mitLimSqr: Double = 0.0
    private var _stepsPerRad: Double = 0.0
    private var _stepSin: Double = 0.0
    private var _stepCos: Double = 0.0
    private var _joinType: JoinType = JoinType.Miter
    private var _endType: EndType = EndType.Polygon

    var ArcTolerance: Double = arcTolerance
    var MergeGroups: Boolean = true
    var MiterLimit: Double = miterLimit
    var PreserveCollinear: Boolean = false
    var ReverseSolution: Boolean = false
    var DeltaCallback: DeltaCallback64? = null

    fun Clear() {
        _groupList.clear()
    }

    fun AddPath(path: Path64, joinType: JoinType, endType: EndType) {
        if (path.isEmpty()) return
        val pp = mutableListOf<Path64>()
        pp.add(path)
        AddPaths(pp, joinType, endType)
    }

    fun AddPaths(paths: Paths64, joinType: JoinType, endType: EndType) {
        if (paths.isEmpty()) return
        _groupList.add(Group(paths, joinType, endType))
    }

    private fun CalcSolutionCapacity(): Int {
        var result = 0
        for (g in _groupList) {
            result += if (g.endType == EndType.Joined) g.inPaths.size * 2 else g.inPaths.size
        }
        return result
    }

    internal fun CheckPathsReversed(): Boolean {
        var result = false
        for (g in _groupList) {
            if (g.endType == EndType.Polygon) {
                result = g.pathsReversed
                break
            }
        }
        return result
    }

    private fun ExecuteInternal(delta: Double) {
        if (_groupList.isEmpty()) return

        // make sure the offset delta is significant
        if (abs(delta) < 0.5) {
            for (group in _groupList) {
                for (path in group.inPaths) {
                    _solution.add(path)
                }
            }
            return
        }

        _delta = delta
        _mitLimSqr = if (MiterLimit <= 1.0) {
            2.0
        } else {
            2.0 / Clipper.Sqr(MiterLimit)
        }

        for (group in _groupList) {
            DoGroupOffset(group)
        }

        if (_groupList.isEmpty()) return

        val pathsReversed = CheckPathsReversed()
        val fillRule = if (pathsReversed) FillRule.Negative else FillRule.Positive

        // clean up self-intersections ...
        val c = Clipper64()
        c.preserveCollinear = PreserveCollinear
        c.reverseSolution = ReverseSolution != pathsReversed
        c.addSubject(_solution)
        c.execute(ClipType.Union, fillRule, _solution)
    }

    fun Execute(delta: Double, solution: Paths64) {
        solution.clear()
        _solution = solution
        ExecuteInternal(delta)
    }

    fun Execute(deltaCallback: DeltaCallback64, solution: Paths64) {
        DeltaCallback = deltaCallback
        Execute(1.0, solution)
    }

    private fun GetPerpendic(pt: Point64, norm: PointD): Point64 {
        return Point64(
            pt.x + (norm.x * _groupDelta).toLong(),
            pt.y + (norm.y * _groupDelta).toLong()
        )
    }

    private fun GetPerpendicD(pt: Point64, norm: PointD): PointD {
        return PointD(
            pt.x.toDouble() + norm.x * _groupDelta,
            pt.y.toDouble() + norm.y * _groupDelta
        )
    }

    private fun DoBevel(path: Path64, j: Int, k: Int) {
        val pt1: Point64
        val pt2: Point64
        if (j == k) {
            val absDelta = abs(_groupDelta)
            pt1 = Point64(
                path[j].x - (absDelta * _normals[j].x).toLong(),
                path[j].y - (absDelta * _normals[j].y).toLong()
            )
            pt2 = Point64(
                path[j].x + (absDelta * _normals[j].x).toLong(),
                path[j].y + (absDelta * _normals[j].y).toLong()
            )
        } else {
            pt1 = Point64(
                path[j].x + (_groupDelta * _normals[k].x).toLong(),
                path[j].y + (_groupDelta * _normals[k].y).toLong()
            )
            pt2 = Point64(
                path[j].x + (_groupDelta * _normals[j].x).toLong(),
                path[j].y + (_groupDelta * _normals[j].y).toLong()
            )
        }
        pathOut.add(pt1)
        pathOut.add(pt2)
    }

    private fun DoSquare(path: Path64, j: Int, k: Int) {
        val vec: PointD = if (j == k) {
            PointD(_normals[j].y, -_normals[j].x)
        } else {
            GetAvgUnitVector(
                PointD(-_normals[k].y, _normals[k].x),
                PointD(_normals[j].y, -_normals[j].x)
            )
        }

        val absDelta = abs(_groupDelta)
        // now offset the original vertex delta units along unit vector
        var ptQ = PointD(path[j])
        ptQ = TranslatePoint(ptQ, absDelta * vec.x, absDelta * vec.y)

        // get perpendicular vertices
        val pt1 = TranslatePoint(ptQ, _groupDelta * vec.y, _groupDelta * -vec.x)
        val pt2 = TranslatePoint(ptQ, _groupDelta * -vec.y, _groupDelta * vec.x)
        // get 2 vertices along one edge offset
        val pt3 = GetPerpendicD(path[k], _normals[k])

        if (j == k) {
            val pt4 = PointD(
                pt3.x + vec.x * _groupDelta,
                pt3.y + vec.y * _groupDelta
            )
            val pt = InternalClipper.GetLineIntersectPt(pt1, pt2, pt3, pt4)
            // get the second intersect point through reflection
            pathOut.add(Point64(ReflectPoint(pt, ptQ)))
            pathOut.add(Point64(pt))
        } else {
            val pt4 = GetPerpendicD(path[j], _normals[k])
            val pt = InternalClipper.GetLineIntersectPt(pt1, pt2, pt3, pt4)
            pathOut.add(Point64(pt))
            // get the second intersect point through reflection
            pathOut.add(Point64(ReflectPoint(pt, ptQ)))
        }
    }

    private fun DoMiter(path: Path64, j: Int, k: Int, cosA: Double) {
        val q = _groupDelta / (cosA + 1)
        pathOut.add(Point64(
            path[j].x + ((_normals[k].x + _normals[j].x) * q).toLong(),
            path[j].y + ((_normals[k].y + _normals[j].y) * q).toLong()
        ))
    }

    private fun DoRound(path: Path64, j: Int, k: Int, angle: Double) {
        if (DeltaCallback != null) {
            // when DeltaCallback is assigned, _groupDelta won't be constant,
            // so we'll need to do the following calculations for *every* vertex.
            val absDelta = abs(_groupDelta)
            val arcTol = if (ArcTolerance > 0.01) ArcTolerance else absDelta * arc_const
            val stepsPer360 = PI / acos(1 - arcTol / absDelta)
            _stepSin = sin((2 * PI) / stepsPer360)
            _stepCos = cos((2 * PI) / stepsPer360)
            if (_groupDelta < 0.0) _stepSin = -_stepSin
            _stepsPerRad = stepsPer360 / (2 * PI)
        }

        val pt = path[j]
        var offsetVec = PointD(_normals[k].x * _groupDelta, _normals[k].y * _groupDelta)
        if (j == k) offsetVec = PointD(-offsetVec.x, -offsetVec.y)
        pathOut.add(Point64(
            pt.x + offsetVec.x.toLong(),
            pt.y + offsetVec.y.toLong()
        ))
        val steps = ceil(_stepsPerRad * abs(angle)).toInt()
        for (i in 1 until steps) { // ie 1 less than steps
            offsetVec = PointD(
                offsetVec.x * _stepCos - _stepSin * offsetVec.y,
                offsetVec.x * _stepSin + offsetVec.y * _stepCos
            )
            pathOut.add(Point64(
                pt.x + offsetVec.x.toLong(),
                pt.y + offsetVec.y.toLong()
            ))
        }
        pathOut.add(GetPerpendic(pt, _normals[j]))
    }

    private fun BuildNormals(path: Path64) {
        val cnt = path.size
        _normals.clear()
        if (cnt == 0) return
        for (i in 0 until cnt - 1) {
            _normals.add(GetUnitNormal(path[i], path[i + 1]))
        }
        _normals.add(GetUnitNormal(path[cnt - 1], path[0]))
    }

    /**
     * Offsets a single vertex. Returns the updated k value.
     * Replaces the C# `ref int k` pattern.
     */
    private fun OffsetPoint(group: Group, path: Path64, j: Int, k: Int): Int {
        if (path[j] == path[k]) return j

        // Let A = change in angle where edges join
        // A == 0: ie no change in angle (flat join)
        // A == PI: edges 'spike'
        // sin(A) < 0: right turning
        // cos(A) < 0: change in angle is more than 90 degree
        var sinA = InternalClipper.CrossProduct(_normals[j], _normals[k])
        val cosA = InternalClipper.DotProduct(_normals[j], _normals[k])
        if (sinA > 1.0) sinA = 1.0
        else if (sinA < -1.0) sinA = -1.0

        if (DeltaCallback != null) {
            _groupDelta = DeltaCallback!!.invoke(path, _normals, j, k)
            if (group.pathsReversed) _groupDelta = -_groupDelta
        }
        if (abs(_groupDelta) < Tolerance) {
            pathOut.add(path[j])
            return j
        }

        if (cosA > -0.999 && (sinA * _groupDelta < 0)) { // test for concavity first (#593)
            // is concave
            // by far the simplest way to construct concave joins, especially those joining very
            // short segments, is to insert 3 points that produce negative regions. These regions
            // will be removed later by the finishing union operation. This is also the best way
            // to ensure that path reversals (ie over-shrunk paths) are removed.
            pathOut.add(GetPerpendic(path[j], _normals[k]))
            pathOut.add(path[j]) // (#405, #873, #916)
            pathOut.add(GetPerpendic(path[j], _normals[j]))
        } else if (cosA > 0.999 && _joinType != JoinType.Round) {
            // almost straight - less than 2.5 degree (#424, #482, #526 & #724)
            DoMiter(path, j, k, cosA)
        } else {
            when (_joinType) {
                // miter unless the angle is sufficiently acute to exceed ML
                JoinType.Miter -> {
                    if (cosA > _mitLimSqr - 1) {
                        DoMiter(path, j, k, cosA)
                    } else {
                        DoSquare(path, j, k)
                    }
                }
                JoinType.Round -> DoRound(path, j, k, atan2(sinA, cosA))
                JoinType.Bevel -> DoBevel(path, j, k)
                JoinType.Square -> DoSquare(path, j, k)
            }
        }

        return j
    }

    private fun OffsetPolygon(group: Group, path: Path64) {
        pathOut = mutableListOf()
        val cnt = path.size
        var prev = cnt - 1
        for (i in 0 until cnt) {
            prev = OffsetPoint(group, path, i, prev)
        }
        _solution.add(pathOut)
    }

    private fun OffsetOpenJoined(group: Group, path: Path64) {
        OffsetPolygon(group, path)
        val reversed = Clipper.ReversePath(path)
        BuildNormals(reversed)
        OffsetPolygon(group, reversed)
    }

    private fun OffsetOpenPath(group: Group, path: Path64) {
        pathOut = mutableListOf()
        val highI = path.size - 1

        if (DeltaCallback != null) {
            _groupDelta = DeltaCallback!!.invoke(path, _normals, 0, 0)
        }

        // do the line start cap
        if (abs(_groupDelta) < Tolerance) {
            pathOut.add(path[0])
        } else {
            when (_endType) {
                EndType.Butt -> DoBevel(path, 0, 0)
                EndType.Round -> DoRound(path, 0, 0, PI)
                else -> DoSquare(path, 0, 0)
            }
        }

        // offset the left side going forward
        run {
            var k = 0
            for (i in 1 until highI) {
                k = OffsetPoint(group, path, i, k)
            }
        }

        // reverse normals ...
        for (i in highI downTo 1) {
            _normals[i] = PointD(-_normals[i - 1].x, -_normals[i - 1].y)
        }
        _normals[0] = _normals[highI]

        if (DeltaCallback != null) {
            _groupDelta = DeltaCallback!!.invoke(path, _normals, highI, highI)
        }
        // do the line end cap
        if (abs(_groupDelta) < Tolerance) {
            pathOut.add(path[highI])
        } else {
            when (_endType) {
                EndType.Butt -> DoBevel(path, highI, highI)
                EndType.Round -> DoRound(path, highI, highI, PI)
                else -> DoSquare(path, highI, highI)
            }
        }

        // offset the left side going back
        run {
            var k = highI
            for (i in highI - 1 downTo 1) {
                k = OffsetPoint(group, path, i, k)
            }
        }

        _solution.add(pathOut)
    }

    private fun DoGroupOffset(group: Group) {
        if (group.endType == EndType.Polygon) {
            // a straight path (2 points) can now also be 'polygon' offset
            // where the ends will be treated as (180 deg.) joins
            if (group.lowestPathIdx < 0) _delta = abs(_delta)
            _groupDelta = if (group.pathsReversed) -_delta else _delta
        } else {
            _groupDelta = abs(_delta)
        }

        var absDelta = abs(_groupDelta)

        _joinType = group.joinType
        _endType = group.endType

        if (group.joinType == JoinType.Round || group.endType == EndType.Round) {
            val arcTol = if (ArcTolerance > 0.01) ArcTolerance else absDelta * arc_const
            val stepsPer360 = PI / acos(1 - arcTol / absDelta)
            _stepSin = sin((2 * PI) / stepsPer360)
            _stepCos = cos((2 * PI) / stepsPer360)
            if (_groupDelta < 0.0) _stepSin = -_stepSin
            _stepsPerRad = stepsPer360 / (2 * PI)
        }

        for (p in group.inPaths) {
            pathOut = mutableListOf()
            val cnt = p.size

            when (cnt) {
                1 -> {
                    val pt = p[0]

                    if (DeltaCallback != null) {
                        _groupDelta = DeltaCallback!!.invoke(p, _normals, 0, 0)
                        if (group.pathsReversed) _groupDelta = -_groupDelta
                        absDelta = abs(_groupDelta)
                    }

                    // single vertex so build a circle or square ...
                    if (group.endType == EndType.Round) {
                        val steps = ceil(_stepsPerRad * 2 * PI).toInt()
                        pathOut = Clipper.Ellipse(pt, absDelta, absDelta, steps)
                    } else {
                        val d = ceil(_groupDelta).toInt()
                        val r = Rect64(pt.x - d, pt.y - d, pt.x + d, pt.y + d)
                        pathOut = r.asPath()
                    }
                    _solution.add(pathOut)
                    continue // end of offsetting a single point
                }
                2 -> {
                    if (group.endType == EndType.Joined) {
                        _endType = if (group.joinType == JoinType.Round) EndType.Round
                        else EndType.Square
                    }
                }
            }

            BuildNormals(p)
            when (_endType) {
                EndType.Polygon -> OffsetPolygon(group, p)
                EndType.Joined -> OffsetOpenJoined(group, p)
                else -> OffsetOpenPath(group, p)
            }
        }
    }
}
