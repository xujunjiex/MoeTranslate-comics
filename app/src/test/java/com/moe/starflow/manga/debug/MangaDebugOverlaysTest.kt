package com.moe.starflow.manga.debug

import android.graphics.Bitmap
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
}
