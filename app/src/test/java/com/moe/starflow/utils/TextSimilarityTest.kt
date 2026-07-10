package com.moe.starflow.utils

import org.junit.Assert.*
import org.junit.Test

class TextSimilarityTest {

    @Test
    fun normalizeFullwidthToHalfwidth() {
        assertEquals("hello", TextSimilarity.normalize("ｈｅｌｌｏ"))
        assertEquals("123", TextSimilarity.normalize("１２３"))
    }

    @Test
    fun normalizePunctuation() {
        assertEquals("hello,world!", TextSimilarity.normalize("hello，world！"))
    }

    @Test
    fun normalizeWhitespace() {
        assertEquals("hello world", TextSimilarity.normalize("  hello   world  "))
    }

    @Test
    fun normalizeLowercase() {
        assertEquals("hello", TextSimilarity.normalize("HELLO"))
    }

    @Test
    fun adaptiveThresholdForShortText() {
        assertEquals(0.3f, TextSimilarity.getAdaptiveThreshold(1))
        assertEquals(0.3f, TextSimilarity.getAdaptiveThreshold(2))
    }

    @Test
    fun adaptiveThresholdForMediumText() {
        assertEquals(0.6f, TextSimilarity.getAdaptiveThreshold(3))
        assertEquals(0.6f, TextSimilarity.getAdaptiveThreshold(5))
        assertEquals(1.0f, TextSimilarity.getAdaptiveThreshold(6))
        assertEquals(1.0f, TextSimilarity.getAdaptiveThreshold(10))
    }

    @Test
    fun adaptiveThresholdForLongText() {
        assertEquals(1.5f, TextSimilarity.getAdaptiveThreshold(11))
        assertEquals(1.5f, TextSimilarity.getAdaptiveThreshold(20))
        assertTrue(TextSimilarity.getAdaptiveThreshold(30) >= 2.0f)
    }

    @Test
    fun weightedLevenshteinIdenticalStrings() {
        assertEquals(0.0f, TextSimilarity.weightedLevenshtein("hello", "hello", 10.0f))
    }

    @Test
    fun weightedLevenshteinEmptyStrings() {
        assertEquals(0.0f, TextSimilarity.weightedLevenshtein("", "", 10.0f))
        assertEquals(5.0f, TextSimilarity.weightedLevenshtein("", "hello", 10.0f))
    }

    @Test
    fun weightedLevenshteinWithConfusableCharacters() {
        val distance = TextSimilarity.weightedLevenshtein("カタカナ", "力タ力ナ", 10.0f)
        assertTrue("Confusable chars should have low cost", distance < 1.0f)
        assertEquals(0.6f, distance, 0.01f)
    }

    @Test
    fun weightedLevenshteinWithDifferentCharacters() {
        val distance = TextSimilarity.weightedLevenshtein("hello", "world", 10.0f)
        assertTrue("Different chars should have high cost", distance >= 3.0f)
    }

    @Test
    fun weightedLevenshteinEarlyExit() {
        val distance = TextSimilarity.weightedLevenshtein("abcdefghij", "klmnopqrst", 2.0f)
        assertTrue("Should early exit", distance > 2.0f)
    }

    @Test
    fun japaneseKanaConfusable() {
        assertTrue(TextSimilarity.isOcrSimilar("カタカナ", "力タ力ナ"))
        assertTrue(TextSimilarity.isOcrSimilar("ロボット", "口ボット"))
        assertTrue(TextSimilarity.isOcrSimilar("ニホン", "二ホン"))
        assertTrue(TextSimilarity.isOcrSimilar("ハチ", "八チ"))
        assertTrue(TextSimilarity.isOcrSimilar("ホン", "木ン"))
    }

    @Test
    fun chineseSimilarCharacters() {
        assertTrue(TextSimilarity.isOcrSimilar("未来", "末来"))
        assertTrue(TextSimilarity.isOcrSimilar("自己", "自已"))
        assertTrue(TextSimilarity.isOcrSimilar("大家", "太家"))
    }

    @Test
    fun punctuationConfusable() {
        assertTrue(TextSimilarity.isOcrSimilar("你好！", "你好!"))
        assertTrue(TextSimilarity.isOcrSimilar("你好？", "你好?"))
    }

    @Test
    fun emptyStrings() {
        assertTrue(TextSimilarity.isOcrSimilar("", ""))
    }

    @Test
    fun oneEmptyOneNot() {
        assertFalse(TextSimilarity.isOcrSimilar("", "hello"))
        assertFalse(TextSimilarity.isOcrSimilar("hello", ""))
    }

    @Test
    fun singleCharacterDifferent() {
        assertFalse(TextSimilarity.isOcrSimilar("あ", "い"))
        assertTrue(TextSimilarity.isOcrSimilar("カ", "力"))
    }

    @Test
    fun twoCharactersDifferent() {
        assertFalse(TextSimilarity.isOcrSimilar("你好", "早上"))
        assertTrue(TextSimilarity.isOcrSimilar("ハチ", "八チ"))
    }

    @Test
    fun longTextOneError() {
        assertTrue(TextSimilarity.isOcrSimilar(
            "这是一个很长的漫画对话文本内容",
            "这是一个很长的漫画对语文本内容"
        ))
    }

    @Test
    fun completelyDifferentText() {
        assertFalse(TextSimilarity.isOcrSimilar("早上好", "晚上好"))
    }

    @Test
    fun findBestMatchExact() {
        val candidates = listOf("你好世界", "早上好", "晚上好")
        val result = TextSimilarity.findBestMatch("你好世界", candidates)
        assertNotNull(result)
        assertEquals("你好世界", result!!.first)
    }

    @Test
    fun findBestMatchSimilar() {
        val candidates = listOf("カタカナ", "おはよう", "こんばんは")
        val result = TextSimilarity.findBestMatch("力タカナ", candidates)
        assertNotNull(result)
        assertEquals("カタカナ", result!!.first)
    }

    @Test
    fun findBestMatchNoMatch() {
        val candidates = listOf("早上好", "晚上好", "中午好")
        val result = TextSimilarity.findBestMatch("再见", candidates)
        assertNull(result)
    }

    @Test
    fun weightedSimilarityIdentical() {
        assertEquals(1.0f, TextSimilarity.weightedSimilarity("hello", "hello"))
    }

    @Test
    fun weightedSimilarityConfusable() {
        val similarity = TextSimilarity.weightedSimilarity("カタカナ", "力タ力ナ")
        assertTrue(similarity > 0.8f)
    }

    @Test
    fun weightedSimilarityDifferent() {
        val similarity = TextSimilarity.weightedSimilarity("hello", "world")
        assertTrue(similarity < 0.5f)
    }

    @Test
    fun standardLevenshtein() {
        assertEquals(0, TextSimilarity.levenshtein("hello", "hello"))
        assertEquals(1, TextSimilarity.levenshtein("hello", "helo"))
        assertEquals(4, TextSimilarity.levenshtein("hello", "world"))
    }

    @Test
    fun standardSimilarity() {
        assertEquals(1.0f, TextSimilarity.similarity("hello", "hello"))
        assertTrue(TextSimilarity.similarity("hello", "helo") >= 0.8f)
        assertTrue(TextSimilarity.similarity("hello", "world") < 0.5f)
    }
}
