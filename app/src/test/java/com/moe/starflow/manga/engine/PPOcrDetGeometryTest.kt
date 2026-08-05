package com.moe.starflow.manga.engine

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PPOcrDetGeometryTest {

    private fun pt(x: Float, y: Float) = PointF(x, y)

    @Test
    fun polygonArea_square() {
        val box = listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f), pt(0f, 10f))
        assertEquals(100.0, PPOcrDetGeometry.polygonArea(box), 0.001)
    }

    @Test
    fun polygonPerimeter_square() {
        val box = listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f), pt(0f, 10f))
        assertEquals(40.0, PPOcrDetGeometry.polygonPerimeter(box), 0.001)
    }

    @Test
    fun orderPointsClockwise_sortsToTL_TR_BR_BL() {
        // 乱序输入：BR, TL, BL, TR
        val pts = arrayOf(
            pt(10f, 10f),  // BR（最大和）
            pt(0f, 0f),    // TL（最小和）
            pt(0f, 10f),   // BL（最大差）
            pt(10f, 0f)    // TR（最小差）
        )
        val sorted = PPOcrDetGeometry.orderPointsClockwise(pts)
        assertEquals("TL", pt(0f, 0f), sorted[0])
        assertEquals("TR", pt(10f, 0f), sorted[1])
        assertEquals("BR", pt(10f, 10f), sorted[2])
        assertEquals("BL", pt(0f, 10f), sorted[3])
    }

    @Test
    fun unclip_expandsBox() {
        val box = listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f), pt(0f, 10f))
        val result = PPOcrDetGeometry.unclip(box, 1.0)
        assertTrue(result.isNotEmpty())
        // 扩张后坐标超出原 box（JTS BufferOp 正值扩张）
        assertTrue(result[0].any { it.x < 0 || it.y < 0 || it.x > 10 || it.y > 10 })
    }

    @Test
    fun getMiniBoxes_singleContour() {
        // 3 点三角形 → 返回外接矩形
        val contour = listOf(
            android.graphics.Point(0, 0),
            android.graphics.Point(10, 0),
            android.graphics.Point(5, 10)
        )
        val mini = PPOcrDetGeometry.getMiniBoxes(contour)
        assertTrue(mini.points != null)
        assertTrue(mini.width > 0f)
        assertTrue(mini.height > 0f)
    }
}
