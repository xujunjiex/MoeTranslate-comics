package com.moe.starflow.manga

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * TranslateUtils.buildTranslatorDisplayName 分支测试：
 * det/ocr 组合名、分批/自由文字开关、PP-OCRv5 参数显示。
 */
class TranslateUtilsDisplayNameTest {

    private fun prefs(incremental: Boolean = true, textFree: Boolean = true): SharedPreferences {
        val p = mock(SharedPreferences::class.java)
        `when`(p.getBoolean("Incremental_Render", true)).thenReturn(incremental)
        `when`(p.getBoolean("Manga_Keep_Text_Free", true)).thenReturn(textFree)
        `when`(p.getFloat(anyString(), anyFloat())).thenAnswer { it.getArgument(1) }
        return p
    }

    @Test
    fun unknownTranslator_mlkitCombo() {
        val name = TranslateUtils.buildTranslatorDisplayName(
            null, DetEngine.MLKIT, OcrEngine.MLKit, prefs()
        )
        assertTrue(name.startsWith("Unknown"))
        assertTrue(name.contains("MLKit+MLKit"))
    }

    @Test
    fun batchEnabled_rtDetrManga_showsBatchTag() {
        val name = TranslateUtils.buildTranslatorDisplayName(
            null, DetEngine.RT_DETR_V2, OcrEngine.MangaOcr, prefs(incremental = true)
        )
        assertTrue(name.contains("分批✓"))
    }

    @Test
    fun batchDisabled_noBatchTag() {
        val name = TranslateUtils.buildTranslatorDisplayName(
            null, DetEngine.RT_DETR_V2, OcrEngine.MangaOcr, prefs(incremental = false)
        )
        assertFalse(name.contains("分批"))
    }

    @Test
    fun textFreeEnabled_onlyRtDetrShowsTag() {
        // RT-DETR 检测器 → 显示自由文字✓
        val rtDetr = TranslateUtils.buildTranslatorDisplayName(
            null, DetEngine.RT_DETR_V2, OcrEngine.MangaOcr, prefs(textFree = true)
        )
        assertTrue(rtDetr.contains("自由文字✓"))
        // 非 RT-DETR → 显示自由文字✗（开关开但不支持）
        val p5 = TranslateUtils.buildTranslatorDisplayName(
            null, DetEngine.PP_OCR_V5, OcrEngine.PPOcrV5, prefs(textFree = true)
        )
        assertTrue(p5.contains("自由文字✗"))
    }

    @Test
    fun ppOcrV5Params_shownWhenPPOcrV5() {
        val name = TranslateUtils.buildTranslatorDisplayName(
            null, DetEngine.PP_OCR_V5, OcrEngine.PPOcrV5, prefs()
        )
        assertTrue(name.contains("box="))
    }

    @Test
    fun ppOcrV5Params_notShownForOtherEngines() {
        val name = TranslateUtils.buildTranslatorDisplayName(
            null, DetEngine.RT_DETR_V2, OcrEngine.MangaOcr, prefs()
        )
        assertFalse(name.contains("box="))
    }
}
