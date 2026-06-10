package com.moe.moetranslator.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationHistoryDao {

    // ========== History ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: HistoryEntity): Long

    @Query("SELECT * FROM translation_history WHERE type = :type ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getHistoryByType(type: Int, limit: Int = 50): List<HistoryEntity>

    @Query("SELECT * FROM translation_history WHERE session_id = :sessionId ORDER BY createdAt DESC")
    suspend fun getHistoryBySessionId(sessionId: String): List<HistoryEntity>

    @Query("SELECT * FROM translation_history ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getAllHistory(limit: Int = 50): List<HistoryEntity>

    @Query("SELECT * FROM translation_history WHERE id = :id")
    suspend fun getHistoryById(id: Long): HistoryEntity?

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM translation_history WHERE type = :type")
    suspend fun deleteHistoryByType(type: Int)

    // ========== Page Cache ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(entry: PageCacheEntity): Long

    @Query("SELECT * FROM page_cache WHERE pHash = :pHash AND mode = :mode LIMIT 1")
    suspend fun findCacheByExactHash(pHash: Long, mode: Int): PageCacheEntity?

    /**
     * 按原文+语言对查找游戏翻译历史（精确匹配 sourceText）
     */
    @Query("SELECT * FROM translation_history WHERE sourceText = :sourceText COLLATE NOCASE AND type = 0 AND sourceLang = :sourceLang AND targetLang = :targetLang LIMIT 1")
    suspend fun findHistoryBySourceText(sourceText: String, sourceLang: String, targetLang: String): HistoryEntity?

    @Query("SELECT * FROM page_cache WHERE mode = :mode")
    suspend fun getAllCacheByMode(mode: Int): List<PageCacheEntity>

    @Query("UPDATE page_cache SET lastAccessedAt = :time WHERE id = :id")
    suspend fun updateLastAccessed(id: Long, time: Long)

    @Query("DELETE FROM page_cache WHERE id = :id")
    suspend fun deleteCacheById(id: Long)

    @Query("DELETE FROM page_cache WHERE historyId = :historyId")
    suspend fun deleteCacheByHistoryId(historyId: Long)

    @Query("SELECT COUNT(*) FROM page_cache WHERE mode = :mode")
    suspend fun getCacheCount(mode: Int): Int

    @Query("SELECT * FROM page_cache WHERE mode = :mode ORDER BY lastAccessedAt ASC LIMIT 1")
    suspend fun getOldestCache(mode: Int): PageCacheEntity?

    /**
     * 按 OCR 原文查找漫画历史（用于文本匹配缓存）。
     * 传入排序后的原文指纹（各气泡原文去编号后排序拼接）。
     */
    @Query("SELECT * FROM translation_history WHERE sourceText = :sourceTextFingerprint AND type = 1 AND sourceLang = :sourceLang AND targetLang = :targetLang LIMIT 1")
    suspend fun findMangaHistoryByText(sourceTextFingerprint: String, sourceLang: String, targetLang: String): HistoryEntity?

    @Query("SELECT * FROM page_cache WHERE pHash = :pHash AND mode = :mode LIMIT 1")
    suspend fun findCacheByPHash(pHash: Long, mode: Int): PageCacheEntity?
}
