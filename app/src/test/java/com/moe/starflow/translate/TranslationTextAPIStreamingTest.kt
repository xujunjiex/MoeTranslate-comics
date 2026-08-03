package com.moe.starflow.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * getTranslationStreaming 默认实现契约：
 * 不流式的引擎（所有 API + NLLB）也必须上报一次 onPhase("generate")，让文本翻译页/游戏/漫画
 * 在结果返回前显示"翻译中"状态（与 Hy-MT2 流式引擎行为一致）；结果经 getTranslation 转发，onPartial 永不触发。
 */
class TranslationTextAPIStreamingTest {

    private class StubApi : TranslationTextAPI {
        override fun getTranslation(
            text: String,
            sourceLanguage: String,
            targetLanguage: String,
            callback: (TranslationResult) -> Unit
        ) {
            callback(TranslationResult.Success(text))
        }

        override fun cancelTranslation() {}
        override fun release() {}
    }

    @Test
    fun defaultStreaming_emitsGenerateOnce_forwardResult_neverPartial() {
        val phases = mutableListOf<String>()
        val partials = mutableListOf<String>()
        var result: TranslationResult? = null
        StubApi().getTranslationStreaming(
            "hello", "ja", "zh",
            onPhase = { phases += it },
            onPartial = { partials += it },
            callback = { result = it }
        )
        assertEquals(listOf("generate"), phases)
        assertTrue("默认实现不应触发 onPartial", partials.isEmpty())
        assertEquals(TranslationResult.Success("hello"), result)
    }
}
