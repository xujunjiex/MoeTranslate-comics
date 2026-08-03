package com.moe.starflow.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * resolveTextLang 三条 fallback 分支：
 * 页面自有选择（own）优先 → 全局设置（global）→ 默认值。
 */
class TextTranslateFragmentLanguageTest {

    @Test
    fun ownLangWins() {
        assertEquals("ko", resolveTextLang("ko", "en", "ja"))
    }

    @Test
    fun globalLangFallsBackWhenOwnEmpty() {
        assertEquals("en", resolveTextLang("", "en", "ja"))
    }

    @Test
    fun defaultWhenBothEmpty() {
        assertEquals("ja", resolveTextLang("", "", "ja"))
    }
}
