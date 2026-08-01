package com.moe.starflow.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import translationapi.hymt2translation.HyMt2Prompt

class HyMt2PromptTest {

    @Test
    fun replacesBothPlaceholders() {
        val p = HyMt2Prompt.build(
            "将以下文本翻译为 {target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{source_text}",
            "日语",
            "こんにちは"
        )
        assertTrue(p.startsWith("将以下文本翻译为 日语"))
        assertTrue(p.endsWith("\n\nこんにちは"))
        assertFalse(p.contains("{target_lang}"))
        assertFalse(p.contains("{source_text}"))
    }

    @Test
    fun customTemplate() {
        assertEquals("T:英语 S:hello", HyMt2Prompt.build("T:{target_lang} S:{source_text}", "英语", "hello"))
    }
}
