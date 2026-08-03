package com.moe.starflow.utils

import android.content.SharedPreferences
import com.moe.starflow.manga.DetEngine
import com.moe.starflow.manga.OcrEngine
import com.moe.starflow.manga.OcrEngineGroup

/** 统一 OCR 引擎选择的共享 prefs 读写（游戏/漫画共用）。 */
object OcrEngineManager {
    const val PREF_KEY = "Ocr_Engine_Group"

    fun getOcrEngineGroup(prefs: SharedPreferences): OcrEngineGroup {
        val key = prefs.getString(PREF_KEY, null)
        if (key != null) return OcrEngineGroup.fromKey(key)
        // 迁移：优先漫画 prefs（4 组一一对应）；无漫画 legacy 键时用游戏 prefs 兜底。
        // ⚠️ 必须用 contains() 判断漫画 legacy 键是否存在——getInt 的默认值 5/5 恰好命中 PP_OCR_V6 组，
        // 若直接匹配会导致全新安装（无任何 legacy 键）被误迁移成 PP_OCR_V6 而非 MLKIT。
        val hasMangaLegacy = prefs.contains("Manga_Det_Model") || prefs.contains("Manga_Rec_Model")
        val migrated = if (hasMangaLegacy) {
            val det = prefs.getInt("Manga_Det_Model", DetEngine.PP_OCR_V6.value)
            val rec = prefs.getInt("Manga_Rec_Model", OcrEngine.PPOcrV6.value)
            OcrEngineGroup.entries.firstOrNull {
                it.mangaDet.value == det && it.mangaOcr.value == rec
            } ?: fromGameEngine(prefs.getInt("Game_OCR_Engine", 0))
        } else {
            fromGameEngine(prefs.getInt("Game_OCR_Engine", 0))
        }
        setOcrEngineGroup(prefs, migrated)
        return migrated
    }

    fun setOcrEngineGroup(prefs: SharedPreferences, group: OcrEngineGroup) {
        prefs.edit().putString(PREF_KEY, group.key).apply()
    }

    /** 游戏 OCR 引擎值 → 组。MLKIT=0/V5=1/MANGA=2/V6=3。 */
    private fun fromGameEngine(value: Int): OcrEngineGroup = when (value) {
        0 -> OcrEngineGroup.MLKIT
        1 -> OcrEngineGroup.PP_OCR_V5
        2 -> OcrEngineGroup.RT_MANGA
        3 -> OcrEngineGroup.PP_OCR_V6
        else -> OcrEngineGroup.MLKIT
    }
}
