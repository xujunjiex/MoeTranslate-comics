package com.moe.starflow.manga

import android.graphics.PointF
import android.graphics.Rect
import com.moe.starflow.utils.LogCollector
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// 类型别名已移至 MangaTypes.kt

/**
 * DBNet 后处理器（纯 Kotlin，不依赖 OpenCV）。
 *
 * 移植自 manga-image-translator 的 SegDetectorRepresenter.boxes_from_bitmap。
 * 流程：阈值化 → 连通域标记 → 轮廓提取 → 旋转最小外接矩形 → 概率过滤 →
 *       Vatti unclip → 旋转最小外接矩形 → 坐标映射。
 */
object DBNetPostProcessor {

    private const val TAG = "DBNetPostProcessor"

    fun extractBoxes(
        probMap: FloatArray,
        height: Int,
        width: Int,
        origWidth: Int,
        origHeight: Int,
        textThreshold: Float = 0.5f,
        boxThreshold: Float = 0.6f,
        minSize: Int = 3,
        minArea: Int = 16,
        unclipRatio: Float = 1.8f
    ): List<Rect> {
        // 1. 阈值化 → 二值图
        val binary = BooleanArray(height * width)
        for (i in probMap.indices) {
            binary[i] = probMap[i] > textThreshold
        }

        // 2. 连通域标记（Union-Find，8 连通对齐 cv2.findContours）
        val uf = UnionFind(height * width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!binary[idx]) continue
                if (x > 0 && binary[idx - 1]) uf.union(idx, idx - 1) // 左
                if (y > 0 && binary[idx - width]) uf.union(idx, idx - width) // 上
                if (x > 0 && y > 0 && binary[idx - width - 1]) uf.union(idx, idx - width - 1) // 左上
                if (x < width - 1 && y > 0 && binary[idx - width + 1]) uf.union(idx, idx - width + 1) // 右上
            }
        }

        // 3. 收集连通域
        val components = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until height * width) {
            if (!binary[i]) continue
            val root = uf.find(i)
            components.getOrPut(root) { mutableListOf() }.add(i)
        }

        LogCollector.d(TAG, "连通域数量: ${components.size}")

        // 4. 对每个连通域：轮廓提取 → minAreaRect → Vatti unclip → 概率过滤
        val boxes = mutableListOf<Rect>()
        var filteredBySize = 0

        for ((_, pixels) in components) {
            val pixelSet = HashSet(pixels)
            val pixelBounds = getPixelBounds(pixels, width)

            val contour = extractContour(binary, width, height, pixelSet, pixelBounds)
            if (contour.size < 4) continue

            val miniBox = minAreaRect(contour)
            val boxWidth = miniBox.width
            val boxHeight = miniBox.height

            if (boxWidth < minSize || boxHeight < minSize) { filteredBySize++; continue }

            val avgProb = GeometryUtils.boxScoreFast(probMap, width, height, contour)

            val area = polygonArea(contour)
            val perimeter = contourPerimeter(contour)
            val distance = area * unclipRatio / perimeter

            val expandedPaths = vattiUnclip(contour, distance)
            if (expandedPaths.isEmpty()) continue

            for (expandedPath in expandedPaths) {
                if (expandedPath.size < 3) continue
                val expandedBox = minAreaRect(expandedPath)
                val eWidth = expandedBox.width
                val eHeight = expandedBox.height

                if (eWidth < minSize + 2 || eHeight < minSize + 2) continue

                // unclip 后过滤 boxThreshold
                if (avgProb < boxThreshold) continue

                boxes.add(expandedBox.toRect())
            }
        }

        LogCollector.d(TAG, "过滤统计: size过小=$filteredBySize")
        LogCollector.d(TAG, "Vatti unclip 前 box 数量: ${boxes.size}")

        // 5. 坐标映射回原图尺寸
        val scaleX = origWidth.toFloat() / width
        val scaleY = origHeight.toFloat() / height

        val result = boxes.map { box ->
            Rect(
                (box.left * scaleX).toInt().coerceIn(0, origWidth),
                (box.top * scaleY).toInt().coerceIn(0, origHeight),
                (box.right * scaleX).toInt().coerceIn(0, origWidth),
                (box.bottom * scaleY).toInt().coerceIn(0, origHeight)
            )
        }.filter { it.width() > 0 && it.height() > 0 }

        LogCollector.d(TAG, "最终 box 数量: ${result.size}")
        return result
    }

    /**
     * 提取 QuadBox 列表（保留旋转信息），用于 OCR 前的 box 合并。
     * 与 extractBoxes() 相同的流程，但输出 QuadBox（4 角点 + 角度）而非轴对齐 Rect。
     * 过滤条件对齐 manga-image-translator：area > 16。
     */
    fun extractQuadBoxes(
        probMap: FloatArray,
        height: Int,
        width: Int,
        origWidth: Int,
        origHeight: Int,
        textThreshold: Float = 0.5f,
        boxThreshold: Float = 0.6f,
        minSize: Int = 2,
        unclipRatio: Float = 1.8f
    ): List<QuadBox> {
        // 1. 阈值化 → 二值图
        val binary = BooleanArray(height * width)
        for (i in probMap.indices) {
            binary[i] = probMap[i] > textThreshold
        }

        // 2. 连通域标记（Union-Find，8 连通对齐 cv2.findContours）
        val uf = UnionFind(height * width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!binary[idx]) continue
                if (x > 0 && binary[idx - 1]) uf.union(idx, idx - 1) // 左
                if (y > 0 && binary[idx - width]) uf.union(idx, idx - width) // 上
                if (x > 0 && y > 0 && binary[idx - width - 1]) uf.union(idx, idx - width - 1) // 左上
                if (x < width - 1 && y > 0 && binary[idx - width + 1]) uf.union(idx, idx - width + 1) // 右上
            }
        }

        // 3. 收集连通域
        val components = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until height * width) {
            if (!binary[i]) continue
            val root = uf.find(i)
            components.getOrPut(root) { mutableListOf() }.add(i)
        }

        LogCollector.d(TAG, "连通域数量: ${components.size}")

        // 4. 对每个连通域：轮廓提取 → minAreaRect → Vatti unclip → 概率过滤
        //    对齐 Python boxes_from_bitmap：不在循环内过滤 boxThreshold，先 unclip 再过滤
        val quadBoxes = mutableListOf<QuadBox>()
        var filteredBySize = 0

        for ((_, pixels) in components) {
            val pixelSet = HashSet(pixels)
            val pixelBounds = getPixelBounds(pixels, width)

            val contour = extractContour(binary, width, height, pixelSet, pixelBounds)
            if (contour.size < 4) continue

            val miniBox = minAreaRect(contour)
            val boxWidth = miniBox.width
            val boxHeight = miniBox.height

            // 对齐 Python: if sside < 2: continue
            if (boxWidth < minSize || boxHeight < minSize) { filteredBySize++; continue }

            // 计算概率（不在这里过滤，对齐 Python 注释掉的逻辑）
            val avgProb = GeometryUtils.boxScoreFast(probMap, width, height, contour)

            val area = polygonArea(contour)
            val perimeter = contourPerimeter(contour)
            val distance = area * unclipRatio / perimeter

            val expandedPaths = vattiUnclip(contour, distance)
            if (expandedPaths.isEmpty()) continue

            for (expandedPath in expandedPaths) {
                if (expandedPath.size < 3) continue
                val expandedBox = minAreaRect(expandedPath)
                val eWidth = expandedBox.width
                val eHeight = expandedBox.height

                if (eWidth < minSize + 2 || eHeight < minSize + 2) continue

                // 构建 QuadBox（保留旋转信息）
                val quadBox = QuadBox.fromRotatedRect(expandedBox)
                // 过滤 area > 16（对齐 manga-image-translator）
                if (quadBox.area <= 16f) continue

                // unclip 后过滤 boxThreshold（对齐 Python ctd.py: idx = np.where(scores[0] > box_thresh)）
                if (avgProb < boxThreshold) continue

                quadBoxes.add(quadBox)
            }
        }

        LogCollector.d(TAG, "过滤统计: size过小=$filteredBySize")
        LogCollector.d(TAG, "Vatti unclip 前 QuadBox 数量: ${quadBoxes.size}")

        // 5. 坐标映射回原图尺寸
        val scaleX = origWidth.toFloat() / width
        val scaleY = origHeight.toFloat() / height

        val result = quadBoxes.map { qb ->
            QuadBox(
                pts = Array(4) { i ->
                    PointF(
                        (qb.pts[i].x * scaleX).coerceIn(0f, origWidth.toFloat()),
                        (qb.pts[i].y * scaleY).coerceIn(0f, origHeight.toFloat())
                    )
                },
                text = qb.text,
                prob = qb.prob
            )
        }.filter { it.area > 0 }

        LogCollector.d(TAG, "最终 QuadBox 数量: ${result.size}")
        return result
    }

    // -----------------------------------------------------------------------
    // 轮廓提取：Moore 邻域追踪
    // -----------------------------------------------------------------------

    /**
     * 从连通域中提取外轮廓（Moore neighborhood tracing）。
     * 返回逆时针排列的轮廓点列表。
     */
    private fun extractContour(
        binary: BooleanArray,
        width: Int,
        height: Int,
        pixelSet: HashSet<Int>,
        bounds: IntArray // [minX, minY, maxX, maxY]
    ): Path64 {
        val minX = bounds[0]
        val minY = bounds[1]

        // 找到起始点（最左上角的前景像素）
        var startX = -1
        var startY = -1
        outer@ for (y in minY..bounds[3]) {
            for (x in minX..bounds[2]) {
                if (pixelSet.contains(y * width + x)) {
                    startX = x
                    startY = y
                    break@outer
                }
            }
        }
        if (startX < 0) return mutableListOf()

        // Moore 邻域追踪
        // 8 邻域方向：右、右下、下、左下、左、左上、上、右上
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        val contour = mutableListOf<Point64>()
        var cx = startX
        var cy = startY
        var dir = 6 // 从上方开始（上方向）

        val maxSteps = pixelSet.size * 8 + 100 // 防止无限循环
        var steps = 0

        do {
            contour.add(Point64(cx.toDouble(), cy.toDouble()))

            // 从 (dir + 6) % 8 开始顺时针搜索下一个边界像素
            var searchDir = (dir + 6) % 8
            var found = false
            for (i in 0 until 8) {
                val nd = (searchDir + i) % 8
                val nx = cx + dx[nd]
                val ny = cy + dy[nd]
                if (nx in 0 until width && ny in 0 until height && pixelSet.contains(ny * width + nx)) {
                    cx = nx
                    cy = ny
                    dir = nd
                    found = true
                    break
                }
            }
            if (!found) break
            steps++
        } while ((cx != startX || cy != startY || contour.size < 3) && steps < maxSteps)

        // 简化轮廓：移除共线点
        return simplifyContour(contour)
    }

    /**
     * 移除共线点（三点共线时移除中间点）。
     */
    private fun simplifyContour(contour: Path64): Path64 {
        if (contour.size < 3) return contour
        val result = mutableListOf<Point64>()
        val n = contour.size
        for (i in 0 until n) {
            val prev = contour[(i - 1 + n) % n]
            val curr = contour[i]
            val next = contour[(i + 1) % n]
            // 检查是否共线
            val cross = (curr.x - prev.x) * (next.y - curr.y) - (curr.y - prev.y) * (next.x - curr.x)
            if (cross != 0.0) {
                result.add(curr)
            }
        }
        return if (result.size >= 3) result else contour
    }

    // -----------------------------------------------------------------------
    // 旋转最小外接矩形（凸包 + 旋转卡壳）
    // -----------------------------------------------------------------------

    data class RotatedRect(
        val cx: Double, val cy: Double,
        val width: Double, val height: Double,
        val angle: Double // 弧度
    ) {
        fun toRect(): Rect {
            // 计算轴对齐外接矩形
            val cosA = cos(angle)
            val sinA = sin(angle)
            val hw = width / 2
            val hh = height / 2
            // 四个角点
            val corners = arrayOf(
                Pair(cx + cosA * (-hw) - sinA * (-hh), cy + sinA * (-hw) + cosA * (-hh)),
                Pair(cx + cosA * (hw) - sinA * (-hh), cy + sinA * (hw) + cosA * (-hh)),
                Pair(cx + cosA * (hw) - sinA * (hh), cy + sinA * (hw) + cosA * (hh)),
                Pair(cx + cosA * (-hw) - sinA * (hh), cy + sinA * (-hw) + cosA * (hh))
            )
            var l = corners[0].first; var r = l; var t = corners[0].second; var b = t
            for (c in corners) {
                if (c.first < l) l = c.first
                if (c.first > r) r = c.first
                if (c.second < t) t = c.second
                if (c.second > b) b = c.second
            }
            return Rect(l.roundToInt(), t.roundToInt(), r.roundToInt(), b.roundToInt())
        }
    }

    /**
     * 计算点集的旋转最小外接矩形。
     */
    private fun minAreaRect(points: Path64): RotatedRect {
        if (points.size < 3) {
            if (points.isEmpty()) return RotatedRect(0.0, 0.0, 0.0, 0.0, 0.0)
            var minX = points[0].x; var maxX = minX; var minY = points[0].y; var maxY = minY
            for (p in points) {
                if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
            }
            return RotatedRect(
                (minX + maxX) / 2.0, (minY + maxY) / 2.0,
                (maxX - minX).toDouble(), (maxY - minY).toDouble(), 0.0
            )
        }

        // 凸包
        val hull = GeometryUtils.convexHull(points)
        if (hull.size < 3) {
            var minX = hull[0].x; var maxX = minX; var minY = hull[0].y; var maxY = minY
            for (p in hull) {
                if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
            }
            return RotatedRect(
                (minX + maxX) / 2.0, (minY + maxY) / 2.0,
                (maxX - minX).toDouble(), (maxY - minY).toDouble(), 0.0
            )
        }

        // 旋转卡壳
        var minArea = Double.MAX_VALUE
        var bestCx = 0.0; var bestCy = 0.0
        var bestW = 0.0; var bestH = 0.0; var bestAngle = 0.0

        val n = hull.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val edgeX = (hull[j].x - hull[i].x).toDouble()
            val edgeY = (hull[j].y - hull[i].y).toDouble()
            val edgeLen = sqrt(edgeX * edgeX + edgeY * edgeY)
            if (edgeLen < 1e-10) continue

            // 归一化边方向
            val ux = edgeX / edgeLen
            val uy = edgeY / edgeLen
            // 垂直方向
            val vx = -uy
            val vy = ux

            // 找凸包在 u, v 方向上的极值
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (k in 0 until n) {
                val px = hull[k].x.toDouble() - hull[i].x.toDouble()
                val py = hull[k].y.toDouble() - hull[i].y.toDouble()
                val projU = px * ux + py * uy
                val projV = px * vx + py * vy
                if (projU < minU) minU = projU; if (projU > maxU) maxU = projU
                if (projV < minV) minV = projV; if (projV > maxV) maxV = projV
            }

            val area = (maxU - minU) * (maxV - minV)
            if (area < minArea) {
                minArea = area
                bestW = maxU - minU
                bestH = maxV - minV
                bestAngle = atan2(uy, ux)
                // 中心点
                val midU = (minU + maxU) / 2
                val midV = (minV + maxV) / 2
                bestCx = hull[i].x.toDouble() + midU * ux + midV * vx
                bestCy = hull[i].y.toDouble() + midU * uy + midV * vy
            }
        }

        return RotatedRect(bestCx, bestCy, bestW, bestH, bestAngle)
    }

    // -----------------------------------------------------------------------
    // Vatti unclip（ClipperOffset）
    // -----------------------------------------------------------------------

    /**
     * 使用 Vatti 算法扩展轮廓多边形。
     * 对应 manga-image-translator 中的 pyclipper.scale_to_clipper + ClipperOffset.Execute。
     */
    private fun vattiUnclip(contour: Path64, distance: Double): Paths64 {
        if (contour.size < 3 || distance <= 0) return mutableListOf()

        val factory = GeometryFactory()
        val coordinates = Array(contour.size) { i ->
            Coordinate(contour[i].x, contour[i].y)
        }
        val polygon = factory.createPolygon(coordinates)

        val params = BufferParameters()
        params.joinStyle = BufferParameters.JOIN_ROUND
        val buffered = BufferOp.bufferOp(polygon, distance, params)

        // 转换回 Paths64
        val result = mutableListOf<MutableList<Coordinate>>()
        val coords = buffered.coordinates
        val path = mutableListOf<Coordinate>()
        for (coord in coords) {
            path.add(Coordinate(coord.x, coord.y))
        }
        result.add(path)
        return result
    }

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    private fun getPixelBounds(pixels: List<Int>, width: Int): IntArray {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (idx in pixels) {
            val x = idx % width
            val y = idx / width
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    private fun contourPerimeter(contour: Path64): Double {
        var perimeter = 0.0
        val n = contour.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = (contour[j].x - contour[i].x).toDouble()
            val dy = (contour[j].y - contour[i].y).toDouble()
            perimeter += sqrt(dx * dx + dy * dy)
        }
        return perimeter
    }

    /**
     * 计算多边形面积（Shoelace 公式）。
     */
    private fun polygonArea(contour: Path64): Double {
        var area = 0.0
        val n = contour.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += contour[i].x * contour[j].y
            area -= contour[j].x * contour[i].y
        }
        return kotlin.math.abs(area) / 2.0
    }

    /**
     * Union-Find 数据结构
     */
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
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            when {
                rank[ra] < rank[rb] -> parent[ra] = rb
                rank[ra] > rank[rb] -> parent[rb] = ra
                else -> {
                    parent[rb] = ra
                    rank[ra]++
                }
            }
        }
    }
}
