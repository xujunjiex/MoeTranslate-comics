package com.moe.starflow.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RegionCacheManagerTest {

    private fun region(text: String, age: Long = 0L) = RegionCacheManager.TranslatedRegion(
        ocrText = text,
        ocrTextHash = text.hashCode(),
        translation = "译:$text",
        translatedAt = System.currentTimeMillis() - age
    )

    @Test
    fun findFuzzyMatch_exactHit() {
        val cache = RegionCacheManager()
        cache.add(region("こんにちは"))
        assertNotNull(cache.findFuzzyMatch("こんにちは"))
    }

    @Test
    fun findFuzzyMatch_similarHit() {
        val cache = RegionCacheManager()
        cache.add(region("テスト"))
        assertNotNull(cache.findFuzzyMatch("テスト"))
    }

    @Test
    fun findFuzzyMatch_noMatch_returnsNull() {
        val cache = RegionCacheManager()
        cache.add(region("こんにちは"))
        assertNull(cache.findFuzzyMatch("世界"))
    }

    @Test
    fun findFuzzyMatch_emptyInput_returnsNull() {
        val cache = RegionCacheManager()
        cache.add(region("こんにちは"))
        assertNull(cache.findFuzzyMatch(""))
    }

    @Test
    fun add_evictsOldestBeyondLimit() {
        val cache = RegionCacheManager()
        repeat(55) { cache.add(region("text$it")) }
        assertEquals(RegionCacheManager.MAX_CACHED_REGIONS, cache.size())
        // 最旧的被淘汰
        assertNull(cache.findFuzzyMatch("text0"))
    }

    @Test
    fun evictExpiredRegions_removesExpired() {
        val cache = RegionCacheManager()
        cache.add(region("old", age = RegionCacheManager.REGION_TTL_MS + 1))
        cache.add(region("new", age = 0L))
        cache.evictExpiredRegions(System.currentTimeMillis())
        assertEquals(1, cache.size())
        assertNotNull(cache.findFuzzyMatch("new"))
    }

    @Test
    fun findExact_matchesHashAndText() {
        val cache = RegionCacheManager()
        cache.add(region("こんにちは"))
        assertNotNull(cache.findExact("こんにちは", "こんにちは".hashCode()))
        assertNull(cache.findExact("こんにちは", 0))
    }
}
