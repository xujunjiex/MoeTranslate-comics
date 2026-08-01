package com.moe.starflow.manga.debug

import android.graphics.Bitmap
import android.util.Size
import com.moe.starflow.manga.DebugRecResult
import com.moe.starflow.manga.MLKitDebugResult
import com.moe.starflow.manga.OcrResult
import com.moe.starflow.manga.RTDetrV2DebugResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MangaDebugOverlaysTest {

    @Test
    fun renderFunctionsProduceValidBitmap() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val out1 = MangaDebugOverlays.renderRTDetrV2DebugOverlay(
            bitmap,
            RTDetrV2DebugResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        )
        val out2 = MangaDebugOverlays.renderMLKitDebugOverlay(
            bitmap,
            MLKitDebugResult(emptyList(), 0, 0, null)
        )
        val ocr = OcrResult(emptyList(), emptyList(), emptyList(), emptyList())
        val out3 = MangaDebugOverlays.renderPPOcrV5DebugWithMerge(bitmap, ocr, emptyList(), null, 0.5f)
        val out4 = MangaDebugOverlays.renderPPOcrV6DebugWithMerge(bitmap, ocr, emptyList(), null, 0.5f)
        for (out in listOf(out1, out2, out3, out4)) {
            assertTrue("尺寸必须与输入一致", out.width == 100 && out.height == 100)
            assertFalse("bitmap 不能已回收", out.isRecycled)
        }
        bitmap.recycle()
    }

    /** 覆盖重构唯一的语义改动：recDebug 非空时 textScoreThresh 标签分支 */
    @Test
    fun renderWithDiscardedRecExercisesTextScoreLabel() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val recDebug = DebugRecResult(
            keptBoxes = emptyList(),
            keptTexts = emptyList(),
            keptScores = emptyList(),
            discardedBoxes = listOf(FloatArray(8) { it.toFloat() }),
            discardedTexts = listOf("x"),
            discardedScores = listOf(0.3f),
            discardedReasons = listOf("score")
        )
        val ocr = OcrResult(emptyList(), emptyList(), emptyList(), emptyList(), recDebug)
        val out5 = MangaDebugOverlays.renderPPOcrV5DebugWithMerge(bitmap, ocr, emptyList(), null, 0.5f)
        val out6 = MangaDebugOverlays.renderPPOcrV6DebugWithMerge(bitmap, ocr, emptyList(), null, 0.5f)
        for (out in listOf(out5, out6)) {
            assertTrue("尺寸必须与输入一致", out.width == 100 && out.height == 100)
            assertFalse("bitmap 不能已回收", out.isRecycled)
        }
        bitmap.recycle()
    }

    /**
     * 覆盖 applyCropDimming 的 cropRect==null 分支（原样返回）。
     * 非空分支无法在 Robolectric 下单测：android.util.Size 构造器在此环境不生效
     * （Size(100,100).width 实测返回 0），而该分支依赖 screenSize 读真实像素。
     * 该函数为逐字搬移，生产调用点传 cropRect + getScreenSize() 已核实。
     */
    @Test
    fun applyCropDimmingNullCropReturnsOriginal() {
        val debugBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val untouched = MangaDebugOverlays.applyCropDimming(debugBitmap, null, Size(0, 0))
        assertTrue("cropRect 为 null 应原样返回同一 bitmap", untouched === debugBitmap)
        debugBitmap.recycle()
    }
}
