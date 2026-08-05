package com.moe.starflow.manga.debug

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Size
import com.moe.starflow.manga.DebugRecResult
import com.moe.starflow.manga.MLKitDebugBlock
import com.moe.starflow.manga.MLKitDebugResult
import com.moe.starflow.manga.OcrResult
import com.moe.starflow.manga.RTDetrV2DebugResult
import com.moe.starflow.manga.TextDirection
import com.moe.starflow.manga.TextRegionGroup
import com.moe.starflow.utils.CustomPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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

    private fun sampleOcrResult(): OcrResult = OcrResult(
        boxes = listOf(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)),
        texts = listOf("こんにちは"),
        scores = listOf(0.9f),
        elapseList = listOf(0.1f, 0f, 0.2f, 0.5f, 0.8f),
        recDebug = DebugRecResult(
            keptBoxes = emptyList(),
            keptTexts = emptyList(),
            keptScores = emptyList(),
            discardedBoxes = listOf(floatArrayOf(20f, 20f, 30f, 20f, 30f, 30f, 20f, 30f)),
            discardedTexts = listOf("x"),
            discardedScores = listOf(0.2f),
            discardedReasons = listOf("score")
        )
    )

    private fun sampleMergedRegion(): TextRegionGroup = TextRegionGroup(
        rect = Rect(0, 0, 50, 100),
        quadPoints = arrayOf(
            PointF(0f, 0f), PointF(50f, 0f), PointF(50f, 100f), PointF(0f, 100f)
        ),
        texts = listOf("テスト", "文字"),
        direction = TextDirection.VERTICAL_RL,
        fontSize = 16f,
        angle = 0f,
        score = 0.9f,
        center = PointF(25f, 50f),
        members = emptyList()
    )

    @Test
    fun buildPPOcrInfoLines_包含合并结果和原始识别行() {
        val prefs = CustomPreference.getInstance(RuntimeEnvironment.getApplication())
        val lines = MangaDebugOverlays.buildPPOcrInfoLines(
            headerLines = listOf("PP-OCRv5 调试模式 | 检测: 1"),
            ocrResult = sampleOcrResult(),
            mergedRegions = listOf(sampleMergedRegion()),
            detDiscardBoxes = emptyList(),
            detDiscardScores = emptyList(),
            detDiscardReasons = emptyList(),
            prefs = prefs,
            detBoxKey = "ppocr_det_box_thresh", detBoxDefault = 0.3f,
            unclipKey = "ppocr_det_unclip_ratio",
            textKey = "ppocr_text_score_thresh", textDefault = 0.5f
        )
        assertTrue("合并结果块缺失", lines.any { it.contains("━━━ 合并结果 ━━━") })
        assertTrue("合并行格式错误: $lines", lines.any { it.contains("【0】竖排 ×2") })
        assertTrue("合并行缺坐标", lines.any { it.contains("[0,0,50,100]") })
        assertTrue("原始识别块缺失", lines.any { it.contains("━━━ 原始识别 ━━━") })
        assertTrue("识别行缺文本", lines.any { it.contains("\"こんにちは\"") })
        assertTrue("图例行缺失", lines.any { it.contains("图例:") })
        assertTrue("参数行缺失", lines.any { it.contains("box_thresh=0.30") })
    }

    @Test
    fun buildPPOcrInfoLines_丢弃选区行() {
        val prefs = CustomPreference.getInstance(RuntimeEnvironment.getApplication())
        val lines = MangaDebugOverlays.buildPPOcrInfoLines(
            headerLines = listOf("PP-OCRv6 调试模式 | det尺寸: 480x640", "检测: 1"),
            ocrResult = sampleOcrResult(),
            mergedRegions = emptyList(),
            detDiscardBoxes = listOf(floatArrayOf(5f, 5f, 15f, 5f, 15f, 15f, 5f, 15f)),
            detDiscardScores = listOf(0.1f),
            detDiscardReasons = listOf("box_thresh"),
            prefs = prefs,
            detBoxKey = "ppocrv6_det_box_thresh", detBoxDefault = 0.5f,
            unclipKey = "ppocrv6_det_unclip_ratio",
            textKey = "ppocrv6_text_score", textDefault = 0.5f
        )
        assertTrue("检测丢弃块缺失", lines.any { it.contains("━━━ 被检测丢弃选区 (1) ━━━") })
        assertTrue("检测丢弃行错误", lines.any { it.contains("✗[0] 0.10") && it.contains("box_thresh") })
        assertTrue("识别丢弃块缺失", lines.any { it.contains("━━━ 被识别/内容丢弃选区 (1) ━━━") })
        assertTrue("识别丢弃行错误", lines.any { it.contains("✗[0] 分数0.20") && it.contains("\"x\"") })
        assertTrue("headerLines 首行缺失", lines[0] == "PP-OCRv6 调试模式 | det尺寸: 480x640")
    }

    @Test
    fun buildPPOcrInfoLines_空结果只含基础行() {
        val prefs = CustomPreference.getInstance(RuntimeEnvironment.getApplication())
        val lines = MangaDebugOverlays.buildPPOcrInfoLines(
            headerLines = listOf("PP-OCRv5 调试模式 | 检测: 0"),
            ocrResult = OcrResult(emptyList(), emptyList(), emptyList(), emptyList(), null),
            mergedRegions = emptyList(),
            detDiscardBoxes = emptyList(),
            detDiscardScores = emptyList(),
            detDiscardReasons = emptyList(),
            prefs = prefs,
            detBoxKey = "ppocr_det_box_thresh", detBoxDefault = 0.3f,
            unclipKey = "ppocr_det_unclip_ratio",
            textKey = "ppocr_text_score_thresh", textDefault = 0.5f
        )
        assertTrue("耗时行缺失", lines.any { it.contains("耗时: det=0.00s") })
        assertTrue("合并参数行缺失", lines.any { it.contains("距离门控 = 1.5") })
        assertTrue("合并结果块不应出现在空输入", !lines.any { it.contains("【0】") })
        assertTrue("检测丢弃块不应出现在空输入", !lines.any { it.contains("被检测丢弃选区") })
    }

    @Test
    fun buildMLKitInfoLines_汇总头部和块文本() {
        val result = MLKitDebugResult(
            textBlocks = listOf(
                MLKitDebugBlock("こんにちは\n世界", null, null, emptyList(), "ja")
            ),
            totalLines = 2,
            totalElements = 3,
            detectedLanguage = "ja"
        )
        val lines = MangaDebugOverlays.buildMLKitInfoLines(result)
        assertTrue("块数缺失", lines[0].contains("块: 1"))
        assertTrue("行数缺失", lines[0].contains("行: 2"))
        assertTrue("元素数缺失", lines[0].contains("元素: 3"))
        assertTrue("语言缺失", lines[0].contains("语言: ja"))
        assertTrue("块文本行缺失: $lines", lines.any { it.contains("B0:") })
        assertTrue("换行未替换为空格", lines.any { it.contains("\"こんにちは 世界\"") })
    }

    @Test
    fun buildRTDetrInfoLines_keepTextFree开关() {
        val result = RTDetrV2DebugResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val keep = MangaDebugOverlays.buildRTDetrInfoLines(result, true)
        val drop = MangaDebugOverlays.buildRTDetrInfoLines(result, false)
        assertEquals(4, keep.size)
        assertTrue("保留分支错误: $keep", keep[1].contains("保留"))
        assertTrue("丢弃分支错误: $drop", drop[1].contains("丢弃"))
        assertTrue("text_bubble 行缺失", keep[0].contains("text_bubble"))
        assertTrue("bubble 行缺失", keep[2].contains("bubble"))
        assertTrue("最终OCR行缺失", keep[3].contains("最终提交OCR"))
    }
}
