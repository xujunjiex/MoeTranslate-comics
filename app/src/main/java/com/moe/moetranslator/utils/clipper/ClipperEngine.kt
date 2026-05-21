package com.moe.moetranslator.utils.clipper

import kotlin.math.abs

/**
 * Minimal Clipper64 engine for polygon boolean operations.
 * Simplified from Clipper2 C# (Clipper.Engine.cs).
 * Supports Union operation needed by ClipperOffset for self-intersection cleanup.
 */
class Clipper64 {

    var preserveCollinear: Boolean = false
    var reverseSolution: Boolean = false

    private val subjectPaths = mutableListOf<Path64>()
    private val clipPaths = mutableListOf<Path64>()

    @JvmName("addSubjectPaths")
    fun addSubject(paths: Paths64) {
        for (path in paths) addSubject(path)
    }

    fun addSubject(path: Path64) {
        if (path.size >= 2) subjectPaths.add(path.toMutableList())
    }

    @JvmName("addClipPaths")
    fun addClip(paths: Paths64) {
        for (path in paths) addClip(path)
    }

    fun addClip(path: Path64) {
        if (path.size >= 2) clipPaths.add(path.toMutableList())
    }

    fun execute(clipType: ClipType, fillRule: FillRule, solution: Paths64): Boolean {
        solution.clear()
        return try {
            val allPaths = mutableListOf<Path64>()
            for (p in subjectPaths) allPaths.add(p)
            for (p in clipPaths) allPaths.add(p)
            if (allPaths.isEmpty()) return true

            val result = booleanOp(clipType, fillRule, allPaths)
            for (p in result) solution.add(p)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clear() {
        subjectPaths.clear()
        clipPaths.clear()
    }

    companion object {

        private const val HORIZONTAL = -3.4E+38

        fun booleanOp(clipType: ClipType, fillRule: FillRule, paths: Paths64): Paths64 {
            if (clipType != ClipType.Union) {
                return paths.map { it.toMutableList() }.toMutableList()
            }

            // Step 1: Collect all segments
            val allSegments = mutableListOf<Pair<Point64, Point64>>()
            for (path in paths) {
                for (i in path.indices) {
                    val j = if (i + 1 < path.size) i + 1 else 0
                    allSegments.add(Pair(path[i], path[j]))
                }
            }

            // Step 2: Find intersection points and split segments
            val splitPoints = mutableMapOf<Int, MutableList<Point64>>()
            for (i in allSegments.indices) {
                splitPoints[i] = mutableListOf(allSegments[i].first)
            }

            for (i in allSegments.indices) {
                for (j in i + 1 until allSegments.size) {
                    val ip = getSegIntersectPt(
                        allSegments[i].first, allSegments[i].second,
                        allSegments[j].first, allSegments[j].second
                    )
                    if (ip != null) {
                        splitPoints[i]!!.add(ip)
                        splitPoints[j]!!.add(ip)
                    }
                }
            }

            // Step 3: Build split segments
            val splitSegs = mutableListOf<Pair<Point64, Point64>>()
            for (i in allSegments.indices) {
                val pts = splitPoints[i]!!
                val sorted = sortPointsAlongSegment(pts, allSegments[i].first, allSegments[i].second)
                for (k in 0 until sorted.size - 1) {
                    if (sorted[k] != sorted[k + 1]) {
                        splitSegs.add(Pair(sorted[k], sorted[k + 1]))
                    }
                }
            }

            // Step 4: Filter by winding number
            val selectedSegs = mutableListOf<Pair<Point64, Point64>>()
            for (seg in splitSegs) {
                val mid = Point64(
                    (seg.first.x + seg.second.x) / 2,
                    (seg.first.y + seg.second.y) / 2
                )
                val winding = windingNumber(mid, paths)
                val inside = when (fillRule) {
                    FillRule.EvenOdd -> winding % 2 != 0
                    FillRule.NonZero -> winding != 0
                    FillRule.Positive -> winding > 0
                    FillRule.Negative -> winding < 0
                }
                if (inside) selectedSegs.add(seg)
            }

            // Step 5: Reconstruct polygon paths
            return reconstructPaths(selectedSegs)
        }

        private fun windingNumber(pt: Point64, paths: Paths64): Int {
            var wn = 0
            for (path in paths) {
                wn += windingNumberSingle(pt, path)
            }
            return wn
        }

        private fun windingNumberSingle(pt: Point64, path: Path64): Int {
            var wn = 0
            val n = path.size
            if (n < 3) return 0
            for (i in 0 until n) {
                val j = if (i + 1 < n) i + 1 else 0
                if (path[i].y <= pt.y) {
                    if (path[j].y > pt.y) {
                        val cross = crossProduct(path[i], path[j], pt)
                        if (cross > 0) wn++
                    }
                } else {
                    if (path[j].y <= pt.y) {
                        val cross = crossProduct(path[i], path[j], pt)
                        if (cross < 0) wn--
                    }
                }
            }
            return wn
        }

        private fun crossProduct(p1: Point64, p2: Point64, p3: Point64): Double {
            return (p2.x - p1.x).toDouble() * (p3.y - p2.y).toDouble() -
                    (p2.y - p1.y).toDouble() * (p3.x - p2.x).toDouble()
        }

        private fun sortPointsAlongSegment(
            points: List<Point64>, start: Point64, end: Point64
        ): List<Point64> {
            val dx = end.x - start.x
            val dy = end.y - start.y
            val lenSq = dx.toDouble() * dx.toDouble() + dy.toDouble() * dy.toDouble()
            if (lenSq == 0.0) return points.sortedWith(compareBy({ it.x }, { it.y }))
            return points.sortedBy { pt ->
                ((pt.x - start.x).toDouble() * dx.toDouble() + (pt.y - start.y).toDouble() * dy.toDouble()) / lenSq
            }
        }

        private fun getSegIntersectPt(
            a1: Point64, a2: Point64, b1: Point64, b2: Point64
        ): Point64? {
            val dax = (a2.x - a1.x).toDouble()
            val day = (a2.y - a1.y).toDouble()
            val dbx = (b2.x - b1.x).toDouble()
            val dby = (b2.y - b1.y).toDouble()
            val det = day * dbx - dby * dax
            if (abs(det) < 1e-12) return null

            val t = ((a1.x - b1.x).toDouble() * dby - (a1.y - b1.y).toDouble() * dbx) / det
            if (t < 1e-10 || t > 1.0 - 1e-10) return null

            return Point64(
                (a1.x + t * dax).toLong(),
                (a1.y + t * day).toLong()
            )
        }

        private fun reconstructPaths(segments: List<Pair<Point64, Point64>>): Paths64 {
            if (segments.isEmpty()) return mutableListOf()

            val adj = mutableMapOf<Point64, MutableList<Point64>>()
            for (seg in segments) {
                adj.getOrPut(seg.first) { mutableListOf() }.add(seg.second)
                adj.getOrPut(seg.second) { mutableListOf() }.add(seg.first)
            }

            val used = mutableSetOf<Pair<Point64, Point64>>()
            val result = mutableListOf<Path64>()

            for (start in adj.keys) {
                for (next in adj[start]!!) {
                    val edge = if (start.x < next.x || (start.x == next.x && start.y < next.y))
                        Pair(start, next) else Pair(next, start)
                    if (edge in used) continue

                    val path = mutableListOf<Point64>()
                    path.add(start)
                    var curr = start
                    var prev = start

                    while (true) {
                        var found = false
                        for (neighbor in adj[curr]!!) {
                            if (neighbor == prev && path.size > 2) continue
                            val e = if (curr.x < neighbor.x || (curr.x == neighbor.x && curr.y < neighbor.y))
                                Pair(curr, neighbor) else Pair(neighbor, curr)
                            if (e in used) continue
                            used.add(e)
                            path.add(neighbor)
                            prev = curr
                            curr = neighbor
                            found = true
                            break
                        }
                        if (!found || curr == start) break
                    }

                    if (path.size >= 3) {
                        if (path.first() == path.last()) path.removeAt(path.size - 1)
                        if (path.size >= 3) result.add(path)
                    }
                }
            }

            return result
        }
    }
}
