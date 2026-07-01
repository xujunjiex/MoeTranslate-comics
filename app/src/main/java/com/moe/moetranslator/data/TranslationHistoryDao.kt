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

    @Query("SELECT * FROM translation_history WHERE type = :type ORDER BY created_at DESC LIMIT :limit")
    suspend fun getHistoryByType(type: Int, limit: Int = 50): List<HistoryEntity>

    @Query("SELECT * FROM translation_history WHERE session_id = :sessionId ORDER BY updated_at DESC")
    suspend fun getHistoryBySessionId(sessionId: String): List<HistoryEntity>

    @Query("SELECT * FROM translation_history ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getAllHistory(limit: Int = 50): List<HistoryEntity>

    @Query("SELECT * FROM translation_history WHERE id = :id")
    suspend fun getHistoryById(id: Long): HistoryEntity?

    @Query("SELECT * FROM translation_history WHERE id IN (:ids)")
    suspend fun getHistoryByIds(ids: List<Long>): List<HistoryEntity>

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM translation_history WHERE type = :type")
    suspend fun deleteHistoryByType(type: Int)

    @Query("DELETE FROM translation_history")
    suspend fun deleteAllHistory()

    // ========== Page Cache ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(entry: PageCacheEntity): Long

    @Query("SELECT * FROM page_cache WHERE pHash = :pHash AND mode = :mode LIMIT 1")
    suspend fun findCacheByExactHash(pHash: Long, mode: Int): PageCacheEntity?

    @Query("SELECT * FROM page_cache WHERE pHash = :pHash AND mode = :mode")
    suspend fun findAllCacheByHash(pHash: Long, mode: Int): List<PageCacheEntity>

    /**
     * 按原文+语言对查找游戏翻译历史（精确匹配 sourceText）
     */
    @Query("SELECT * FROM translation_history WHERE sourceText = :sourceText COLLATE NOCASE AND type = 0 AND sourceLang = :sourceLang AND targetLang = :targetLang LIMIT 1")
    suspend fun findHistoryBySourceText(sourceText: String, sourceLang: String, targetLang: String): HistoryEntity?

    @Query("SELECT * FROM page_cache WHERE mode = :mode")
    suspend fun getAllCacheByMode(mode: Int): List<PageCacheEntity>

    @Query("UPDATE page_cache SET lastAccessedAt = :time WHERE id = :id")
    suspend fun updateLastAccessed(id: Long, time: Long)

    @Query("UPDATE translation_history SET updated_at = :time WHERE id = :id")
    suspend fun updateHistoryTimestamp(id: Long, time: Long)

    @Query("UPDATE translation_history SET updated_at = :time, last_session_id = :lastSessionId WHERE id = :id")
    suspend fun updateHistoryTimestampAndLastSession(id: Long, time: Long, lastSessionId: String)

    @Query("DELETE FROM page_cache WHERE id = :id")
    suspend fun deleteCacheById(id: Long)

    @Query("DELETE FROM page_cache WHERE historyId = :historyId")
    suspend fun deleteCacheByHistoryId(historyId: Long)

    @Query("SELECT * FROM page_cache WHERE historyId = :historyId LIMIT 1")
    suspend fun findCacheByHistoryId(historyId: Long): PageCacheEntity?

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

    /**
     * 按 pHash + cropWidth + cropHeight 精确查找缓存（用于保存时去重）。
     */
    @Query("SELECT * FROM page_cache WHERE pHash = :pHash AND mode = :mode AND cropWidth = :cropWidth AND cropHeight = :cropHeight LIMIT 1")
    suspend fun findCacheByHashAndSize(pHash: Long, mode: Int, cropWidth: Int, cropHeight: Int): PageCacheEntity?

    /**
     * 按 pHash + crop rect 精确查找缓存（使用新的 cropLeft/cropTop/cropRight/cropBottom 字段）。
     */
    @Query("SELECT * FROM page_cache WHERE pHash = :pHash AND mode = :mode AND crop_left = :cropLeft AND crop_top = :cropTop AND crop_right = :cropRight AND crop_bottom = :cropBottom LIMIT 1")
    suspend fun findCacheByHashAndCropRect(pHash: Long, mode: Int, cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int): PageCacheEntity?
}
