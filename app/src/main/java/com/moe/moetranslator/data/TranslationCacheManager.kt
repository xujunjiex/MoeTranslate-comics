package com.moe.moetranslator.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.PerceptualHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 翻译缓存管理器 — 统一管理游戏/漫画翻译的缓存查找、保存、淘汰和历史记录。
 */
class TranslationCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationCacheManager"
        const val MODE_GAME = 0
        const val MODE_MANGA = 1
        private const val MAX_CACHE_PER_MODE = 100
        private const val SIMILARITY_THRESHOLD_MANGA = 0.92f
        private const val THUMBNAIL_SIZE = 200
    }

    private val db = TranslationHistoryDatabase.getInstance(context)
    private val dao = db.historyDao()
    private val historyDir = File(context.filesDir, "history").also { it.mkdirs() }

    // ========== 缓存操作 ==========

    /**
     * 查找缓存。先精确匹配 pHash，再相似度匹配（阈值按模式区分）。
     * @return 命中时返回 CacheResult，未命中返回 null
     */
    suspend fun findCache(pHash: Long, mode: Int): CacheResult? = withContext(Dispatchers.IO) {
        // 1. 精确匹配
        val exactMatch = dao.findCacheByExactHash(pHash, mode)
        if (exactMatch != null) {
            dao.updateLastAccessed(exactMatch.id, System.currentTimeMillis())
            val history = dao.getHistoryById(exactMatch.historyId)
            if (history != null) {
                LogCollector.d(TAG, "findCache: 精确命中, historyId=${history.id}")
                return@withContext buildCacheResult(history)
            }
        }

        // 2. 相似度匹配（仅漫画模式，游戏模式背景几乎不变会导致误判）
        if (mode == MODE_MANGA) {
            val allCache = dao.getAllCacheByMode(mode)
            var bestMatch: PageCacheEntity? = null
            var bestSimilarity = 0f
            for (entry in allCache) {
                val sim = PerceptualHash.similarity(pHash, entry.pHash)
                if (sim >= SIMILARITY_THRESHOLD_MANGA && sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestMatch = entry
                }
            }

            if (bestMatch != null) {
                dao.updateLastAccessed(bestMatch.id, System.currentTimeMillis())
                val history = dao.getHistoryById(bestMatch.historyId)
                if (history != null) {
                    LogCollector.d(TAG, "findCache: 相似度命中 (${bestSimilarity}), historyId=${history.id}")
                    return@withContext buildCacheResult(history)
                }
            }
        }

        LogCollector.d(TAG, "findCache: 未命中, pHash=$pHash, mode=$mode")
        null
    }

    /**
     * 按原文+语言对查找游戏翻译缓存（用于自动翻译的数据库缓存层）
     * 通过精确匹配 sourceText + sourceLang + targetLang 查找
     */
    suspend fun findGameCache(sourceText: String, sourceLang: String, targetLang: String): CacheResult? = withContext(Dispatchers.IO) {
        if (sourceText.isBlank()) return@withContext null

        val history = dao.findHistoryBySourceText(sourceText.trim(), sourceLang, targetLang)
        if (history != null) {
            dao.updateLastAccessed(history.id, System.currentTimeMillis())
            LogCollector.d(TAG, "findGameCache: 命中, historyId=${history.id}")
            return@withContext buildCacheResult(history)
        }

        LogCollector.d(TAG, "findGameCache: 未命中, sourceText=${sourceText.take(20)}...")
        null
    }

    /**
     * 保存翻译结果到历史 + 缓存。自动执行 LRU 淘汰。
     */
    suspend fun saveToCache(entry: CacheEntry) = withContext(Dispatchers.IO) {
        // 1. 保存图片（漫画翻译）
        var imagePath: String? = null
        var thumbnailPath: String? = null
        if (entry.type == MODE_MANGA && entry.resultBitmap != null) {
            val timestamp = System.currentTimeMillis()
            imagePath = saveBitmap(entry.resultBitmap, "manga_${timestamp}.png")
            thumbnailPath = saveThumbnail(entry.resultBitmap, "manga_${timestamp}_thumb.png")
        }

        // 2. 插入 history 记录
        val historyEntity = HistoryEntity(
            type = entry.type,
            sourceText = entry.sourceText,
            translatedText = entry.translatedText,
            imagePath = imagePath,
            thumbnailPath = thumbnailPath,
            sourceLang = entry.sourceLang,
            targetLang = entry.targetLang,
            translatorName = entry.translatorName,
            pHash = entry.pHash,
            createdAt = System.currentTimeMillis(),
            sessionId = entry.sessionId
        )
        val historyId = dao.insertHistory(historyEntity)
        LogCollector.d(TAG, "saveToCache: 插入 history, id=$historyId")

        // 3. LRU 淘汰：检查缓存容量
        evictIfNeeded(entry.type)

        // 4. 插入 cache 记录
        val cacheEntity = PageCacheEntity(
            historyId = historyId,
            pHash = entry.pHash,
            mode = entry.type,
            lastAccessedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        dao.insertCache(cacheEntity)
        LogCollector.d(TAG, "saveToCache: 插入 cache, pHash=${entry.pHash}, mode=${entry.type}")
    }

    /**
     * 强制刷新缓存：删除旧缓存条目，保存新结果。
     */
    suspend fun refreshCache(pHash: Long, mode: Int, newEntry: CacheEntry) = withContext(Dispatchers.IO) {
        // 删除旧的缓存条目（保留 history 记录）
        val oldCache = dao.findCacheByExactHash(pHash, mode)
        if (oldCache != null) {
            dao.deleteCacheById(oldCache.id)
            LogCollector.d(TAG, "refreshCache: 删除旧 cache, id=${oldCache.id}")
        }

        // 保存新结果
        saveToCache(newEntry)
        LogCollector.d(TAG, "refreshCache: 保存新结果")
    }

    // ========== 历史操作 ==========

    /**
     * 获取历史记录列表。
     * @param type 0=游戏, 1=漫画, -1=全部
     */
    suspend fun getHistory(type: Int = -1, limit: Int = 50): List<HistoryEntry> = withContext(Dispatchers.IO) {
        val entities = if (type == -1) {
            dao.getAllHistory(limit)
        } else {
            dao.getHistoryByType(type, limit)
        }
        entities.map { it.toHistoryEntry() }
    }

    /**
     * 获取按日期和会话分组的历史记录（游戏模式）。
     */
    suspend fun getHistoryGrouped(type: Int, limit: Int = 200): List<HistoryGroup> = withContext(Dispatchers.IO) {
        val entities = dao.getHistoryByType(type, limit)
        val entries = entities.map { it.toHistoryEntry() }

        if (entries.isEmpty()) return@withContext emptyList()

        val calendar = java.util.Calendar.getInstance()
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val yesterday = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        val threeDaysAgo = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -3) }
        val sevenDaysAgo = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -7) }

        fun getDateLabel(timestamp: Long): String {
            calendar.timeInMillis = timestamp
            return when {
                timestamp >= today.timeInMillis -> "今天"
                timestamp >= yesterday.timeInMillis -> "昨天"
                timestamp >= threeDaysAgo.timeInMillis -> "3天前"
                timestamp >= sevenDaysAgo.timeInMillis -> "7天前"
                else -> {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(timestamp))
                }
            }
        }

        // 按日期分组
        val groupedByDate = entries.groupBy { getDateLabel(it.createdAt) }

        // 日期排序：今天的在最前面
        val dateOrder = listOf("今天", "昨天", "3天前", "7天前")

        groupedByDate.entries.sortedByDescending { entry ->
            val idx = dateOrder.indexOf(entry.key)
            if (idx >= 0) (-idx).toLong() else Long.MAX_VALUE - entry.value.maxOf { it.createdAt }
        }.map { (dateLabel, dateEntries) ->
            // 每个日期内按 sessionId 分组
            val sessions = dateEntries.groupBy { it.sessionId }
                .map { (sessionId, sessionEntries) ->
                    HistorySession(
                        sessionId = sessionId,
                        startTime = sessionEntries.minOf { it.createdAt },
                        endTime = sessionEntries.maxOf { it.createdAt },
                        entries = sessionEntries.sortedByDescending { it.createdAt }
                    )
                }
                .sortedByDescending { it.startTime }

            HistoryGroup(dateLabel = dateLabel, sessions = sessions)
        }
    }

    /**
     * 按会话 ID 获取历史记录（用于漫画图片浏览）。
     */
    suspend fun getHistoryBySessionId(sessionId: String): List<HistoryEntry> = withContext(Dispatchers.IO) {
        if (sessionId.isEmpty()) return@withContext emptyList()
        dao.getHistoryBySessionId(sessionId).map { it.toHistoryEntry() }
    }

    /**
     * 删除单条历史记录（同时删除关联文件和缓存条目）。
     */
    suspend fun deleteHistory(id: Long) = withContext(Dispatchers.IO) {
        val history = dao.getHistoryById(id) ?: return@withContext
        // 删除图片文件
        history.imagePath?.let { File(it).delete() }
        history.thumbnailPath?.let { File(it).delete() }
        // 删除缓存条目
        dao.deleteCacheByHistoryId(id)
        // 删除历史记录
        dao.deleteHistoryById(id.toInt())
        LogCollector.d(TAG, "deleteHistory: id=$id")
    }

    /**
     * 清空指定类型的历史记录。
     */
    suspend fun clearHistory(type: Int) = withContext(Dispatchers.IO) {
        val entities = dao.getHistoryByType(type, limit = 10000)
        for (entity in entities) {
            entity.imagePath?.let { File(it).delete() }
            entity.thumbnailPath?.let { File(it).delete() }
        }
        dao.deleteHistoryByType(type)
        LogCollector.d(TAG, "clearHistory: type=$type, deleted ${entities.size} entries")
    }

    // ========== 内部方法 ==========

    private suspend fun evictIfNeeded(mode: Int) {
        val count = dao.getCacheCount(mode)
        if (count >= MAX_CACHE_PER_MODE) {
            val oldest = dao.getOldestCache(mode)
            if (oldest != null) {
                dao.deleteCacheById(oldest.id)
                LogCollector.d(TAG, "evictIfNeeded: 淘汰 cache id=${oldest.id}, mode=$mode")
            }
        }
    }

    private fun buildCacheResult(history: HistoryEntity): CacheResult {
        val bitmap = history.imagePath?.let { path ->
            try {
                BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                LogCollector.e(TAG, "buildCacheResult: 加载图片失败: $path", e)
                null
            }
        }
        return CacheResult(
            historyId = history.id,
            originalText = history.sourceText,
            translatedText = history.translatedText,
            resultBitmap = bitmap,
            sourceLang = history.sourceLang,
            targetLang = history.targetLang
        )
    }

    private fun saveBitmap(bitmap: Bitmap, filename: String): String {
        val file = File(historyDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    private fun saveThumbnail(bitmap: Bitmap, filename: String): String {
        val scale = THUMBNAIL_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val thumbW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val thumbH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true)
        val file = File(historyDir, filename)
        FileOutputStream(file).use { out ->
            thumb.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        if (thumb !== bitmap) thumb.recycle()
        return file.absolutePath
    }
}

// ========== 数据类 ==========

data class CacheResult(
    val historyId: Long,
    val originalText: String?,
    val translatedText: String?,
    val resultBitmap: Bitmap?,
    val sourceLang: String,
    val targetLang: String
)

data class CacheEntry(
    val type: Int,              // MODE_GAME 或 MODE_MANGA
    val sourceText: String?,    // 游戏翻译
    val translatedText: String?,// 游戏翻译
    val resultBitmap: Bitmap?,  // 漫画翻译：渲染后图片
    val sourceLang: String,
    val targetLang: String,
    val translatorName: String,
    val pHash: Long,
    val sessionId: String = ""  // 翻译会话 ID
)

data class HistoryEntry(
    val id: Long,
    val type: Int,
    val sourceText: String?,
    val translatedText: String?,
    val imagePath: String?,
    val thumbnailPath: String?,
    val sourceLang: String,
    val targetLang: String,
    val translatorName: String,
    val createdAt: Long,
    val sessionId: String = ""
)

data class HistoryGroup(
    val dateLabel: String,           // "今天"、"昨天"、"2026-06-07"
    val sessions: List<HistorySession>
)

data class HistorySession(
    val sessionId: String,
    val startTime: Long,
    val endTime: Long,
    val entries: List<HistoryEntry>
)

// ========== Entity -> Entry 转换 ==========

fun HistoryEntity.toHistoryEntry() = HistoryEntry(
    id = id,
    type = type,
    sourceText = sourceText,
    translatedText = translatedText,
    imagePath = imagePath,
    thumbnailPath = thumbnailPath,
    sourceLang = sourceLang,
    targetLang = targetLang,
    translatorName = translatorName,
    createdAt = createdAt,
    sessionId = sessionId
)
