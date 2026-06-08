package com.moe.moetranslator.manga

import android.graphics.PointF
import com.moe.moetranslator.utils.LogCollector
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
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
 * CTD (Comic Text Detector) 后处理器。
 *
 * 移植自 manga-image-translator 的 boxes_from_bitmap 逻辑（SegDetectorRepresenter）。
 * 关键差异于 DBNetPostProcessor：
 * - sside < 2 过滤（不是 minSize=3）
 * - 无 post-unclip 尺寸检查（Python 代码中已注释掉）
 * - 使用 box_score_fast 计算轮廓内平均概率
 * - 使用 unclipPolygon 做多边形扩张
 *
 * 参数：textThreshold=0.3, boxThreshold=0.6, unclipRatio=1.5, maxCandidates=1000
 */
object CTDPostProcessor {

    private const val TAG = "CTDPostProcessor"

    // CTD 参数（对齐 manga-image-translator）
    private const val TEXT_THRESHOLD = 0.3f
    private const val BOX_THRESHOLD = 0.6f
    private const val UNCLIP_RATIO = 1.2f
    private const val MAX_CANDIDATES = 1000
    // 模型参考尺寸（原始训练尺寸 1024），像素阈值基于此尺寸
    private const val REFERENCE_SIZE = 1024
    // 过滤不合理框的参数（对齐 Python 原装 + CTD 调试显示）
    // 注意：这些是 REFERENCE_SIZE 空间的阈值，实际使用时按比例缩放
    private const val REF_MIN_SSHORT_SIDE = 2f        // 原始轮廓短边
    private const val REF_MIN_UNCLIPPED_SSHORT_SIDE = 5f  // unclip 后短边
    private const val REF_MIN_FONT_SIZE = 8f           // fontSize 阈值
    private const val REF_MIN_WIDTH = 6f               // AABB 宽度阈值
    private const val REF_MIN_RAW_AREA = 2f            // 原始轮廓 AABB 面积

    /**
     * CTD 提取结果，包含通过过滤的框和被丢弃的框
     */
    data class ExtractResult(
        val quadBoxes: List<QuadBox>,       // 通过过滤的 QuadBox
        val discardedBoxes: List<QuadBox>  // 被丢弃的 QuadBox（调试用）
    )

    /**
     * 从概率图提取 QuadBox 列表。
     * 完全对齐 Python boxes_from_bitmap 流程：
     * 1. 阈值化 → 二值图
     * 2. findContours（BFS 连通域）→ 收集 contours
     * 3. 对每个 contour：get_mini_boxes → sside < 2 过滤 → box_score_fast → unclip → get_mini_boxes → scale → area 过滤
     */
    fun extractQuadBoxes(
        probMap: FloatArray,
        height: Int,
        width: Int,
        origWidth: Int,
        origHeight: Int,
        textThreshold: Float = TEXT_THRESHOLD,
        boxThreshold: Float = BOX_THRESHOLD,
        unclipRatio: Float = UNCLIP_RATIO,
        maxCandidates: Int = MAX_CANDIDATES,
        trackDiscarded: Boolean = false
    ): List<QuadBox> {
        // 计算缩放比例（当前模型输出尺寸 / 参考尺寸 1024）
        // 所有像素阈值按此比例缩放
        val scale = height.toFloat() / REFERENCE_SIZE

        // 1. 阈值化 → 二值图
        val binary = BooleanArray(height * width)
        for (i in probMap.indices) {
            binary[i] = probMap[i] > textThreshold
        }

        // 2. findContours - BFS 连通域提取（等价于 cv2.findContours）
        val contours = findContours(binary, width, height, maxCandidates)

        LogCollector.d(TAG, "轮廓数量: ${contours.size}, scale=$scale")

        // 缩放后的阈值
        val minSshortSide = REF_MIN_SSHORT_SIDE * scale
        val minUnclippedSside = REF_MIN_UNCLIPPED_SSHORT_SIDE * scale
        val minFontSize = REF_MIN_FONT_SIZE * scale
        val minWidth = REF_MIN_WIDTH * scale
        val minRawArea = REF_MIN_RAW_AREA * scale

        val quadBoxes = mutableListOf<QuadBox>()
        val discardedBoxes = mutableListOf<QuadBox>()
        var filteredByShortSide = 0
        var filteredByUnclippedSside = 0
        var filteredByBoxScore = 0

        for (contour in contours) {
            // 3. get_mini_boxes: 计算 minAreaRect 和 short side
            val (_, sside) = getMiniBoxes(contour)

            // 4. sside < 阈值过滤（只过滤原始轮廓，不过滤 unclip 后）
            if (sside < minSshortSide) {
                filteredByShortSide++
                continue
            }

            // 4.5 原始坐标过滤（unclip 前，基于几何特征过滤误检）
            val contourW = (contour.maxOf { it.x } - contour.minOf { it.x }).toFloat()
            val contourH = (contour.maxOf { it.y } - contour.minOf { it.y }).toFloat()
            val rawArea = contourW * contourH
            val rawAspect = if (contourW > contourH) {
                contourW / maxOf(contourH, 1f)
            } else {
                contourH / maxOf(contourW, 1f)
            }
            // 狭长轮廓不过滤（漫画文字可能是狭长的）
            // 面积太小过滤
            if (rawArea < minRawArea) {
                LogCollector.d(TAG, "原始过滤(面积小): area=${String.format("%.1f", rawArea)}, aspect=${String.format("%.1f", rawAspect)}")
                continue
            }

            // 5. box_score_fast: 计算轮廓内平均概率
            val score = GeometryUtils.boxScoreFast(probMap, width, height, contour)

            // 6. 动态 unclip_ratio：窄框用更大的 ratio，补偿 area/perimeter 公式对窄框扩展不足
            val aspect = maxOf(contourW, contourH) / maxOf(minOf(contourW, contourH), 1f)
            val dynamicRatio = maxOf(unclipRatio, unclipRatio * sqrt(aspect / 3f))

            // 7. unclipPolygon: 多边形扩张
            val unclipped = unclipPolygon(contour, dynamicRatio)

            // 8. 扩张后重新计算 minAreaRect
            val (unclippedPoints, unclippedSside) = getMiniBoxes(unclipped)

            // 9. unclip 后 sside < 5 过滤（对齐 polygons_from_bitmap）
            if (unclippedSside < minUnclippedSside) {
                filteredByUnclippedSside++
                continue
            }

            // 8. 构建 QuadBox（尚未缩放）
            // 注意：官方 boxes_from_bitmap 在 unclip 后没有 area 过滤！
            // 这里不做过滤，让 unclip 后的框完整保留，坐标缩放到原图后再过滤
            val quadBox = QuadBox(
                Array(4) { i ->
                    PointF(unclippedPoints[i].x.toFloat(), unclippedPoints[i].y.toFloat())
                },
                text = "",
                prob = score
            )

            // 9. 这里不再做 area 过滤（与官方一致，area 过滤在 unclip 前通过 sside < 2 已完成）
            quadBoxes.add(quadBox)
        }

        LogCollector.d(TAG, "过滤统计: sside<2=$filteredByShortSide, unclippedSside<5=$filteredByUnclippedSside, boxScore<$boxThreshold=$filteredByBoxScore")
        LogCollector.d(TAG, "unclip 后 QuadBox 数量: ${quadBoxes.size}")

        // 10. 坐标缩放到原图尺寸
        val scaleX = origWidth.toFloat() / width
        val scaleY = origHeight.toFloat() / height

        val result = quadBoxes.map { qb ->
            QuadBox(
                Array(4) { i ->
                    PointF(
                        (qb.pts[i].x * scaleX).coerceIn(0f, origWidth.toFloat()),
                        (qb.pts[i].y * scaleY).coerceIn(0f, origHeight.toFloat())
                    )
                },
                text = qb.text,
                prob = qb.prob
            )
        }.filter { it.area > 0f }
         .partition { qb ->
            val w = qb.aabb.width()
            qb.fontSize >= minFontSize && w >= minWidth
         }

        LogCollector.d(TAG, "最终 QuadBox 数量: ${result.first.size}, 丢弃(fontSize/w): ${result.second.size}")
        return result.first
    }

    /**
     * 从概率图提取 QuadBox 列表，同时跟踪被丢弃的框。
     * 用于 CTD 调试模式。
     */
    fun extractQuadBoxesWithDiscarded(
        probMap: FloatArray,
        height: Int,
        width: Int,
        origWidth: Int,
        origHeight: Int,
        textThreshold: Float = TEXT_THRESHOLD,
        boxThreshold: Float = BOX_THRESHOLD,
        unclipRatio: Float = UNCLIP_RATIO,
        maxCandidates: Int = MAX_CANDIDATES
    ): ExtractResult {
        // 计算缩放比例
        val scale = height.toFloat() / REFERENCE_SIZE

        // 1. 阈值化 → 二值图
        val binary = BooleanArray(height * width)
        for (i in probMap.indices) {
            binary[i] = probMap[i] > textThreshold
        }

        // 2. findContours - BFS 连通域提取（等价于 cv2.findContours）
        val contours = findContours(binary, width, height, maxCandidates)

        LogCollector.d(TAG, "轮廓数量: ${contours.size}, scale=$scale")

        // 缩放后的阈值
        val minSshortSide = REF_MIN_SSHORT_SIDE * scale
        val minUnclippedSside = REF_MIN_UNCLIPPED_SSHORT_SIDE * scale
        val minFontSize = REF_MIN_FONT_SIZE * scale
        val minWidth = REF_MIN_WIDTH * scale
        val minRawArea = REF_MIN_RAW_AREA * scale

        val quadBoxes = mutableListOf<QuadBox>()
        var filteredByShortSide = 0
        var filteredByUnclippedSside = 0
        var filteredByBoxScore = 0

        for (contour in contours) {
            // 3. get_mini_boxes: 计算 minAreaRect 和 short side
            val (_, sside) = getMiniBoxes(contour)

            // 4. sside < 阈值过滤（只过滤原始轮廓，不过滤 unclip 后）
            if (sside < minSshortSide) {
                filteredByShortSide++
                continue
            }

            // 4.5 原始坐标过滤（unclip 前，基于几何特征过滤误检）
            val contourW = (contour.maxOf { it.x } - contour.minOf { it.x }).toFloat()
            val contourH = (contour.maxOf { it.y } - contour.minOf { it.y }).toFloat()
            val rawArea = contourW * contourH
            val rawAspect = if (contourW > contourH) {
                contourW / maxOf(contourH, 1f)
            } else {
                contourH / maxOf(contourW, 1f)
            }
            // 狭长轮廓不过滤（漫画文字可能是狭长的）
            // 面积太小过滤
            if (rawArea < minRawArea) {
                LogCollector.d(TAG, "原始过滤(面积小): area=${String.format("%.1f", rawArea)}, aspect=${String.format("%.1f", rawAspect)}")
                continue
            }

            // 5. box_score_fast: 计算轮廓内平均概率
            val score = GeometryUtils.boxScoreFast(probMap, width, height, contour)

            // 6. 动态 unclip_ratio：窄框用更大的 ratio，补偿 area/perimeter 公式对窄框扩展不足
            val aspect = maxOf(contourW, contourH) / maxOf(minOf(contourW, contourH), 1f)
            val dynamicRatio = maxOf(unclipRatio, unclipRatio * sqrt(aspect / 3f))

            // 7. unclipPolygon: 多边形扩张
            val unclipped = unclipPolygon(contour, dynamicRatio)

            // 8. 扩张后重新计算 minAreaRect
            val (unclippedPoints, unclippedSside) = getMiniBoxes(unclipped)

            // 9. unclip 后 sside < 5 过滤（对齐 polygons_from_bitmap）
            if (unclippedSside < minUnclippedSside) {
                filteredByUnclippedSside++
                continue
            }

            // 8. 构建 QuadBox（尚未缩放）
            val quadBox = QuadBox(
                Array(4) { i ->
                    PointF(unclippedPoints[i].x.toFloat(), unclippedPoints[i].y.toFloat())
                },
                text = "",
                prob = score
            )

            quadBoxes.add(quadBox)
        }

        LogCollector.d(TAG, "过滤统计: sside<2=$filteredByShortSide, unclippedSside<5=$filteredByUnclippedSside, boxScore<$boxThreshold=$filteredByBoxScore")
        LogCollector.d(TAG, "unclip 后 QuadBox 数量: ${quadBoxes.size}")

        // 10. 坐标缩放到原图尺寸
        val scaleX = origWidth.toFloat() / width
        val scaleY = origHeight.toFloat() / height

        val (passed, discarded) = quadBoxes.map { qb ->
            QuadBox(
                Array(4) { i ->
                    PointF(
                        (qb.pts[i].x * scaleX).coerceIn(0f, origWidth.toFloat()),
                        (qb.pts[i].y * scaleY).coerceIn(0f, origHeight.toFloat())
                    )
                },
                text = qb.text,
                prob = qb.prob
            )
        }.filter { it.area > 0f }
         .partition { qb ->
            // 过滤不合理的框：fontSize 太小或 AABB 宽度太小（CTD 调试显示用）
            val w = qb.aabb.width()
            val pass = qb.fontSize >= minFontSize && w >= minWidth
            pass
         }

        LogCollector.d(TAG, "最终 QuadBox 数量: ${passed.size}, 丢弃: ${discarded.size}")
        return ExtractResult(passed, discarded)
    }

    // -----------------------------------------------------------------------
    // findContours: BFS 连通域提取（等价于 cv2.findContours + RETR_LIST）
    // -----------------------------------------------------------------------

    /**
     * BFS 连通域提取，返回轮廓列表（每个轮廓是一个 Path64，点按顺序排列）。
     * 等价于 cv2.findContours(bitmap.astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
     */
    private fun findContours(
        binary: BooleanArray,
        width: Int,
        height: Int,
        maxCandidates: Int
    ): List<Path64> {
        val visited = BooleanArray(binary.size)
        val contours = mutableListOf<Path64>()

        // 4 邻域方向：右、下、左、上
        val dx = intArrayOf(1, 0, -1, 0)
        val dy = intArrayOf(0, 1, 0, -1)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!binary[idx] || visited[idx]) continue

                // BFS 收集连通域的所有像素
                val componentPixels = mutableListOf<Point64>()
                val queue = ArrayDeque<Int>()
                queue.add(idx)
                visited[idx] = true

                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    componentPixels.add(Coordinate((cur % width).toDouble(), (cur / width).toDouble()))

                    val curX = cur % width
                    val curY = cur / width

                    for (d in 0..3) {
                        val nx = curX + dx[d]
                        val ny = curY + dy[d]
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                        val nidx = ny * width + nx
                        if (!binary[nidx] || visited[nidx]) continue
                        visited[nidx] = true
                        queue.add(nidx)
                    }
                }

                if (componentPixels.size >= 3) {
                    // 计算凸包作为轮廓（等价于 cv2.CHAIN_APPROX_SIMPLE 的效果）
                    val hull = GeometryUtils.convexHull(componentPixels)
                    if (hull.size >= 3) {
                        contours.add(hull.toMutableList())
                    }
                }

                // 控制轮廓数量
                if (contours.size >= maxCandidates) break
            }
            if (contours.size >= maxCandidates) break
        }

        return contours
    }

    // -----------------------------------------------------------------------
    // getMiniBoxes: minAreaRect via rotating calipers on convex hull
    // -----------------------------------------------------------------------

    /**
     * 计算点集的旋转最小外接矩形。
     * 返回：Pair(4 个角点列表, short side长度)
     *
     * 等价于 cv2.minAreaRect(points) + cv2.boxPoints。
     * sside = min(bounding_rect[1])，即宽高中较小的那个。
     */
    private fun getMiniBoxes(points: Path64): Pair<Path64, Float> {
        if (points.size < 3) {
            // 退化情况：不足 3 个点，退化为轴对齐矩形
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            val w = (maxX - minX).toFloat()
            val h = (maxY - minY).toFloat()
            return Pair(
                mutableListOf(
                    Coordinate(minX, minY),
                    Coordinate(maxX, minY),
                    Coordinate(maxX, maxY),
                    Coordinate(minX, maxY)
                ),
                min(w, h)
            )
        }

        // 凸包
        val hull = GeometryUtils.convexHull(points)
        if (hull.size < 3) {
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (p in hull) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            val w = (maxX - minX).toFloat()
            val h = (maxY - minY).toFloat()
            return Pair(
                mutableListOf(
                    Coordinate(minX, minY),
                    Coordinate(maxX, minY),
                    Coordinate(maxX, maxY),
                    Coordinate(minX, maxY)
                ),
                min(w, h)
            )
        }

        // 旋转卡壳找最小面积矩形
        var minArea = Double.MAX_VALUE
        var bestRect = mutableListOf<Coordinate>()
        var bestSside = 0f

        val n = hull.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val edgeX = hull[j].x - hull[i].x
            val edgeY = hull[j].y - hull[i].y
            val edgeLen = sqrt(edgeX * edgeX + edgeY * edgeY)
            if (edgeLen < 1e-10) continue

            // 归一化边方向（u 轴）
            val ux = edgeX / edgeLen
            val uy = edgeY / edgeLen
            // 垂直方向（v 轴）
            val vx = -uy
            val vy = ux

            // 投影到 u, v 方向
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (k in 0 until n) {
                val px = hull[k].x - hull[i].x
                val py = hull[k].y - hull[i].y
                val projU = px * ux + py * uy
                val projV = px * vx + py * vy
                if (projU < minU) minU = projU
                if (projU > maxU) maxU = projU
                if (projV < minV) minV = projV
                if (projV > maxV) maxV = projV
            }

            val area = (maxU - minU) * (maxV - minV)
            if (area < minArea) {
                minArea = area
                val w = maxU - minU
                val h = maxV - minV
                val sside = min(w.toFloat(), h.toFloat())

                // 计算中心点
                val midU = (minU + maxU) / 2
                val midV = (minV + maxV) / 2
                val cx = hull[i].x + midU * ux + midV * vx
                val cy = hull[i].y + midU * uy + midV * vy

                // 计算 4 个角点
                val angle = atan2(uy, ux)
                val cosA = cos(angle)
                val sinA = sin(angle)
                val hw = w / 2
                val hh = h / 2

                bestRect = mutableListOf(
                    Coordinate(cx + cosA * (-hw) - sinA * (-hh),
                            cy + sinA * (-hw) + cosA * (-hh)),
                    Coordinate(cx + cosA * (hw) - sinA * (-hh),
                            cy + sinA * (hw) + cosA * (-hh)),
                    Coordinate(cx + cosA * (hw) - sinA * (hh),
                            cy + sinA * (hw) + cosA * (hh)),
                    Coordinate(cx + cosA * (-hw) - sinA * (hh),
                            cy + sinA * (-hw) + cosA * (hh))
                )
                bestSside = sside
            }
        }

        return Pair(bestRect, bestSside)
    }

    // -----------------------------------------------------------------------
    // unclipPolygon: 多边形扩张（使用 JTS BufferOp，匹配 Python Pyclipper）
    // -----------------------------------------------------------------------

    /**
     * 多边形扩张。等价于 Python 的 unclip（基于 Vatti clipping 的偏移）。
     * distance = area * unclip_ratio / perimeter
     * 使用 JTS BufferOp 实现，与 pyclipper.PyclipperOffset 等价。
     */
    private fun unclipPolygon(contour: Path64, unclipRatio: Float): Path64 {
        if (contour.size < 3) return contour

        // 计算面积和周长
        var area = 0.0
        var perimeter = 0.0
        val n = contour.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += contour[i].x * contour[j].y
            area -= contour[j].x * contour[i].y
            val dx = contour[j].x - contour[i].x
            val dy = contour[j].y - contour[i].y
            perimeter += sqrt(dx * dx + dy * dy)
        }
        area = abs(area) / 2.0

        if (perimeter < 1e-10 || area < 1e-10) return contour

        // 计算偏移距离（与 Python 一致）
        val distance = area * unclipRatio / perimeter

        // 使用 JTS BufferOp（Vatti clipping 算法）进行多边形扩张
        try {
            val factory = GeometryFactory()
            // 确保多边形闭合：首尾点相同
            val coords = if (contour.size >= 3) {
                val first = contour[0]
                val last = contour[contour.size - 1]
                val needsClose = first.x != last.x || first.y != last.y
                if (needsClose) {
                    contour + Coordinate(first.x, first.y)
                } else {
                    contour
                }
            } else {
                contour
            }
            val ring = factory.createLinearRing(
                Array(coords.size) { i ->
                    Coordinate(coords[i].x, coords[i].y)
                }
            )
            val polygon = factory.createPolygon(ring)
            val params = BufferParameters()
            params.joinStyle = BufferParameters.JOIN_ROUND
            val buffered = BufferOp.bufferOp(polygon, distance, params)

            // 转换回 Path64
            val resultCoords = buffered.coordinates
            val result = mutableListOf<Coordinate>()
            for (i in 0 until resultCoords.size) {
                result.add(Coordinate(resultCoords[i].x, resultCoords[i].y))
            }

            return result
        } catch (e: Exception) {
            LogCollector.e(TAG, "unclip failed", e)
            return contour
        }
    }

    // -----------------------------------------------------------------------
    // sortPnts: 文字方向检测（竖排/横排）
    // -----------------------------------------------------------------------

    /**
     * 根据四边形结构向量判断文字方向。
     * 对齐 manga-image-translator 的 sort_pnts 逻辑。
     *
     * @param pts 4个角点数组
     * @return isVertical: x方向分量小(y方向分量大) → 竖排
     */
    fun sortPnts(pts: Array<PointF>): Boolean {
        // 1. 计算所有点对向量
        val pairwiseVec = mutableListOf<Double>()
        for (i in pts.indices) {
            for (j in pts.indices) {
                pairwiseVec.add((pts[j].x - pts[i].x).toDouble())
                pairwiseVec.add((pts[j].y - pts[i].y).toDouble())
            }
        }
        // pairwiseVec 现在是 16 对 (dx, dy)

        // 2. 计算每对向量的长度
        val norms = DoubleArray(16) { i ->
            val dx = pairwiseVec[i * 2]
            val dy = pairwiseVec[i * 2 + 1]
            sqrt(dx * dx + dy * dy)
        }

        // 3. 找到第二长的两条边（indices 8 和 10）
        // argsort 升序排列，indices 8 和 10 是第二、第三长的
        val sortedIndices = norms.indices.sortedBy { norms[it] }
        val longSideIds = listOf(sortedIndices[8], sortedIndices[10])

        // 4. 获取这两条边对应的向量
        val longSideVecs = longSideIds.map { idx ->
            doubleArrayOf(pairwiseVec[idx * 2], pairwiseVec[idx * 2 + 1])
        }

        // 5. 如果两向量方向相反，翻转第一个
        val innerProd = longSideVecs[0][0] * longSideVecs[1][0] + longSideVecs[0][1] * longSideVecs[1][1]
        if (innerProd < 0) {
            longSideVecs[0][0] = -longSideVecs[0][0]
            longSideVecs[0][1] = -longSideVecs[0][1]
        }

        // 6. 计算平均结构向量
        val strucVecX = abs((longSideVecs[0][0] + longSideVecs[1][0]) / 2)
        val strucVecY = abs((longSideVecs[0][1] + longSideVecs[1][1]) / 2)

        // 7. x分量小 → 竖排
        return strucVecX <= strucVecY
    }
}