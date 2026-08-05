package com.moe.starflow.manga.engine
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*
import com.moe.starflow.manga.*

import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import com.moe.starflow.utils.LogCollector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.buffer.BufferOp

/**
 * PP-OCR det 后处理共享几何/图像逻辑（PPOcrV5Engine/PPOcrV6Engine 提取，完全一致部分）。
 * 纯函数：无引擎状态，det 阈值参数由调用方传入。
 */
object PPOcrDetGeometry {

    data class BoxScoreResult(
        val boxes: List<FloatArray>,
        val scores: List<Float>
    )

    data class MiniBoxResult(
        val points: List<PointF>?,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float
    )

    /**
     * 过滤检测结果：裁剪到图像范围 + 最小尺寸检查 + 大框过滤。
     */
    fun filterDetRes(
        boxes: List<FloatArray>,
        scores: List<Float>,
        h: Int,
        w: Int,
        detMinSize: Int,
        largeBoxEnabled: Boolean,
        largeBoxRatio: Float
    ): BoxScoreResult {
        val resultBoxes = mutableListOf<FloatArray>()
        val resultScores = mutableListOf<Float>()
        for (i in boxes.indices) {
            val box = boxes[i]
            val clipped = FloatArray(8)
            for (j in 0 until 4) {
                clipped[j * 2] = box[j * 2].coerceIn(0f, (w - 1).toFloat())
                clipped[j * 2 + 1] = box[j * 2 + 1].coerceIn(0f, (h - 1).toFloat())
            }

            // 计算宽度和高度
            val widthA = sqrt(((clipped[4] - clipped[6]) * (clipped[4] - clipped[6]) +
                    (clipped[5] - clipped[7]) * (clipped[5] - clipped[7])).toDouble()).toFloat()
            val widthB = sqrt(((clipped[2] - clipped[0]) * (clipped[2] - clipped[0]) +
                    (clipped[3] - clipped[1]) * (clipped[3] - clipped[1])).toDouble()).toFloat()
            val boxWidth = max(widthA, widthB)

            val heightA = sqrt(((clipped[2] - clipped[4]) * (clipped[2] - clipped[4]) +
                    (clipped[3] - clipped[5]) * (clipped[3] - clipped[5])).toDouble()).toFloat()
            val heightB = sqrt(((clipped[0] - clipped[6]) * (clipped[0] - clipped[6]) +
                    (clipped[1] - clipped[7]) * (clipped[1] - clipped[7])).toDouble()).toFloat()
            val boxHeight = max(heightA, heightB)

            if (boxWidth < detMinSize || boxHeight < detMinSize) continue

            // 大框过滤（可选）：宽/高/面积超过图片比例阈值时丢弃
            if (largeBoxEnabled) {
                val ratio = largeBoxRatio
                val imgArea = w.toFloat() * h.toFloat()
                val boxArea = boxWidth * boxHeight
                if (boxWidth > w * ratio || boxHeight > h * ratio || boxArea > imgArea * ratio) {
                    continue
                }
            }

            resultBoxes.add(clipped)
            if (i < scores.size) resultScores.add(scores[i])
        }
        return BoxScoreResult(resultBoxes, resultScores)
    }

    /**
     * findContours: BFS 连通域 (对应 cv2.findContours)
     */
    fun findContours(mask: Bitmap, w: Int, h: Int, detMinSize: Int): List<List<Point>> {
        val visited = BooleanArray(w * h)
        val contours = mutableListOf<List<Point>>()
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)

        val dx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val dy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (visited[idx] || pixels[idx] != Color.WHITE) continue

                val component = mutableListOf<Point>()
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                visited[idx] = true

                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val cy = cur / w
                    val cx = cur % w
                    component.add(Point(cx, cy))

                    for (d in 0 until 8) {
                        val nx = cx + dx[d]
                        val ny = cy + dy[d]
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                        val nIdx = ny * w + nx
                        if (!visited[nIdx] && pixels[nIdx] == Color.WHITE) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                }

                if (component.size >= detMinSize) {
                    contours.add(component)
                }
            }
        }

        return contours
    }

    /**
     * getMiniBoxes: 凸包 + 最小外接矩形
     */
    fun getMiniBoxes(contour: List<Point>): MiniBoxResult {
        if (contour.size < 3) {
            return MiniBoxResult(null, 0f, 0f, 0f, 0f)
        }

        val ptsD = contour.map { Coordinate(it.x.toDouble(), it.y.toDouble()) }
        val hull = GeometryUtils.convexHull(ptsD)
        if (hull.size < 3) {
            return MiniBoxResult(null, 0f, 0f, 0f, 0f)
        }

        // 旋转卡壳：遍历凸包每条边作为候选方向
        var minArea = Float.MAX_VALUE
        var bestBox: MiniBoxResult? = null

        for (i in hull.indices) {
            val j = (i + 1) % hull.size
            val edgeX = (hull[j].x - hull[i].x).toFloat()
            val edgeY = (hull[j].y - hull[i].y).toFloat()
            val edgeLen = sqrt(edgeX * edgeX + edgeY * edgeY)
            if (edgeLen < 1e-6f) continue

            val ux = edgeX / edgeLen
            val uy = edgeY / edgeLen
            val vx = -uy
            val vy = ux

            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE

            for (k in hull.indices) {
                val dx = (hull[k].x - hull[i].x).toFloat()
                val dy = (hull[k].y - hull[i].y).toFloat()
                val projU = dx * ux + dy * uy
                val projV = dx * vx + dy * vy
                if (projU < minU) minU = projU
                if (projU > maxU) maxU = projU
                if (projV < minV) minV = projV
                if (projV > maxV) maxV = projV
            }

            val w = maxU - minU
            val h = maxV - minV
            val area = w * h

            if (area < minArea) {
                minArea = area
                val midU = (minU + maxU) / 2
                val midV = (minV + maxV) / 2
                val cx = hull[i].x.toFloat() + midU * ux + midV * vx
                val cy = hull[i].y.toFloat() + midU * uy + midV * vy

                // 构建四角点并排序：TL, TR, BR, BL
                val corners = arrayOf(
                    PointF(cx - w / 2 * ux - h / 2 * vx, cy - w / 2 * uy - h / 2 * vy),
                    PointF(cx + w / 2 * ux - h / 2 * vx, cy + w / 2 * uy - h / 2 * vy),
                    PointF(cx + w / 2 * ux + h / 2 * vx, cy + w / 2 * uy + h / 2 * vy),
                    PointF(cx - w / 2 * ux + h / 2 * vx, cy - w / 2 * uy + h / 2 * vy)
                )
                val sorted = orderPointsClockwise(corners)
                bestBox = MiniBoxResult(sorted.toList(), cx, cy, w, h)
            }
        }

        return bestBox ?: MiniBoxResult(null, 0f, 0f, 0f, 0f)
    }

    /**
     * orderPointsClockwise: 排序四角点 → TL, TR, BR, BL
     */
    fun orderPointsClockwise(pts: Array<PointF>): Array<PointF> {
        val rect = arrayOfNulls<PointF>(4)

        // TL: 最小和
        val s = FloatArray(4) { pts[it].x + pts[it].y }
        rect[0] = pts[s.indices.minByOrNull { s[it] }!!]
        // BR: 最大和
        rect[2] = pts[s.indices.maxByOrNull { s[it] }!!]

        // TR: 最小差 (y - x)
        val d = FloatArray(4) { pts[it].y - pts[it].x }
        rect[1] = pts[d.indices.minByOrNull { d[it] }!!]
        // BL: 最大差
        rect[3] = pts[d.indices.maxByOrNull { d[it] }!!]

        @Suppress("UNCHECKED_CAST")
        return rect as Array<PointF>
    }

    /**
     * unclip: Vatti unclip (JTS BufferOp)
     */
    fun unclip(box: List<PointF>, unclipRatio: Double): List<List<Coordinate>> {
        val area = polygonArea(box)
        val perimeter = polygonPerimeter(box)
        if (perimeter < 1e-6) return emptyList()

        val distance = area * unclipRatio / perimeter

        val factory = GeometryFactory()
        val coords = Array(box.size + 1) { i ->
            if (i < box.size) Coordinate(box[i].x.toDouble(), box[i].y.toDouble())
            else Coordinate(box[0].x.toDouble(), box[0].y.toDouble()) // close ring
        }
        val poly = try {
            factory.createPolygon(coords)
        } catch (e: Exception) {
            return emptyList()
        }

        val buffered = try {
            BufferOp.bufferOp(poly, distance)
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()

        if (buffered.isEmpty) return emptyList()

        val result = mutableListOf<List<Coordinate>>()
        for (i in 0 until buffered.numGeometries) {
            val coordsArr = buffered.getGeometryN(i).coordinates
            result.add(coordsArr.toList())
        }
        return result
    }

    fun polygonArea(box: List<PointF>): Double {
        var area = 0.0
        val n = box.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += box[i].x * box[j].y - box[j].x * box[i].y
        }
        return abs(area) / 2.0
    }

    fun polygonPerimeter(box: List<PointF>): Double {
        var peri = 0.0
        val n = box.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = (box[j].x - box[i].x).toDouble()
            val dy = (box[j].y - box[i].y).toDouble()
            peri += sqrt(dx * dx + dy * dy)
        }
        return peri
    }

    /**
     * 透视裁剪 + 自动旋转竖排文字。
     *
     * @param bitmap 原图
     * @param points 4 个顶点 [TL, TR, BR, BL]（原图坐标）
     * @return 裁剪后的正向文字图片
     */
    fun getRotateCropImage(bitmap: Bitmap, points: Array<PointF>): Bitmap {
        // 1. 计算目标尺寸
        val tl = points[0]; val tr = points[1]
        val br = points[2]; val bl = points[3]

        val widthA = sqrt(((br.x - bl.x) * (br.x - bl.x) + (br.y - bl.y) * (br.y - bl.y)).toDouble()).toFloat()
        val widthB = sqrt(((tr.x - tl.x) * (tr.x - tl.x) + (tr.y - tl.y) * (tr.y - tl.y)).toDouble()).toFloat()
        val maxWidth = max(widthA, widthB).roundToInt().coerceIn(4, bitmap.width)
        val heightA = sqrt(((tr.x - br.x) * (tr.x - br.x) + (tr.y - br.y) * (tr.y - br.y)).toDouble()).toFloat()
        val heightB = sqrt(((tl.x - bl.x) * (tl.x - bl.x) + (tl.y - bl.y) * (tl.y - bl.y)).toDouble()).toFloat()
        val maxHeight = max(heightA, heightB).roundToInt().coerceIn(4, bitmap.height)

        // 2. 使用 Android Canvas + Matrix 做透视裁剪（硬件加速，替代纯 Java 像素循环）
        val srcPts = floatArrayOf(
            tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y
        )
        val dstPts = floatArrayOf(
            0f, 0f, (maxWidth - 1).toFloat(), 0f,
            (maxWidth - 1).toFloat(), (maxHeight - 1).toFloat(), 0f, (maxHeight - 1).toFloat()
        )
        val matrix = android.graphics.Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

        val cropImg = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cropImg)
        canvas.drawBitmap(bitmap, matrix, null)

        // 3. 竖排文字自动旋转 90° CCW
        if (cropImg.height >= cropImg.width * 1.5f) {
            val rotMatrix = android.graphics.Matrix().apply { setRotate(-90f) }
            val rotated = Bitmap.createBitmap(cropImg, 0, 0, cropImg.width, cropImg.height, rotMatrix, true)
            if (rotated !== cropImg) cropImg.recycle()
            return rotated
        }

        return cropImg
    }
}
