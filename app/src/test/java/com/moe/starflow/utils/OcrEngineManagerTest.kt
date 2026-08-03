package com.moe.starflow.utils

import androidx.preference.PreferenceManager
import com.moe.starflow.manga.OcrEngineGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OcrEngineManagerTest {
    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())

    @Test fun default_noLegacyPrefs_returnsMlkitAndMigrates() {
        val p = prefs()
        p.edit().clear().commit()
        assertEquals(OcrEngineGroup.MLKIT, OcrEngineManager.getOcrEngineGroup(p))
        // 迁移已写共享值
        assertEquals("mlkit", p.getString(OcrEngineManager.PREF_KEY, ""))
    }
    @Test fun legacy_mangaV6_migratesToV6() {
        val p = prefs()
        p.edit().clear().commit()
        // 漫画 v6：DetEngine.PP_OCR_V6=5, OcrEngine.PPOcrV6=5
        p.edit().putInt("Manga_Det_Model", 5).putInt("Manga_Rec_Model", 5).commit()
        assertEquals(OcrEngineGroup.PP_OCR_V6, OcrEngineManager.getOcrEngineGroup(p))
    }
    @Test fun setAndRead() {
        val p = prefs()
        p.edit().clear().commit()
        OcrEngineManager.setOcrEngineGroup(p, OcrEngineGroup.RT_MANGA)
        assertEquals(OcrEngineGroup.RT_MANGA, OcrEngineManager.getOcrEngineGroup(p))
    }
}
