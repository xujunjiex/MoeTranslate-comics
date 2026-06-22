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
        private const val SIMILARITY_THRESHOLD_MANGA = 0.85f
        private const val THUMBNAIL_SIZE = 200
        private const val AREA_RATIO_MIN = 0.8f   // 面积比下限（框选偏移面积变化 <1%，宽松允许 ±20%）
        private const val AREA_RATIO_MAX = 1.25f  // 面积比上限
    }

    private val db = TranslationHistoryDatabase.getInstance(context)
    private val dao = db.historyDao()
    private val historyDir = File(context.filesDir, "history").also { it.mkdirs() }

    // 漫画文本指纹 → historyId 内存缓存，避免每次翻译都扫描全部历史
    private val textFingerprintCache = mutableMapOf<String, Long>()

    // ========== 缓存操作 ==========

    /**
     * 查找缓存。先精确匹配 pHash，再相似度匹配（阈值按模式区分）。
     * 漫画模式下校验面积比：裁剪区域差异过大时跳过缓存（防止框选完全不同区域误命中）。
     * @param currentCropWidth 当前裁剪区域宽度（0=不校验面积比）
     * @param currentCropHeight 当前裁剪区域高度（0=不校验面积比）
     * @return 命中时返回 CacheResult，未命中返回 null
     */
    suspend fun findCache(pHash: Long, mode: Int, currentCropWidth: Int = 0, currentCropHeight: Int = 0): CacheResult? = withContext(Dispatchers.IO) {
        // 1. 精确匹配（同 pHash 可能有多个条目，选面积最接近的）
        val allExactMatches = dao.findAllCacheByHash(pHash, mode)
        if (allExactMatches.isNotEmpty()) {
            val bestExact = findBestAreaMatch(allExactMatches, currentCropWidth, currentCropHeight)
            if (bestExact != null) {
                dao.updateLastAccessed(bestExact.id, System.currentTimeMillis())
                val history = dao.getHistoryById(bestExact.historyId)
                if (history != null) {
                    LogCollector.d(TAG, "findCache: 精确命中, historyId=${history.id}, crop=${bestExact.cropWidth}x${bestExact.cropHeight}")
                    return@withContext buildCacheResult(history, bestExact.cropWidth, bestExact.cropHeight)
                }
            } else {
                LogCollector.d(TAG, "findCache: 精确匹配存在但面积比都不兼容, 跳过")
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
                    if (isCropAreaCompatible(entry, currentCropWidth, currentCropHeight)) {
                        bestSimilarity = sim
                        bestMatch = entry
                    }
                }
            }

            if (bestMatch != null) {
                dao.updateLastAccessed(bestMatch.id, System.currentTimeMillis())
                val history = dao.getHistoryById(bestMatch.historyId)
                if (history != null) {
                    LogCollector.d(TAG, "findCache: 相似度命中 (${bestSimilarity}), historyId=${history.id}")
                    return@withContext buildCacheResult(history, bestMatch.cropWidth, bestMatch.cropHeight)
                }
            }
        }

        LogCollector.d(TAG, "findCache: 未命中, pHash=$pHash, mode=$mode")
        null
    }

    /**
     * 校验缓存条目的裁剪面积与当前裁剪面积是否兼容。
     * 旧数据（cropWidth=0）跳过校验，保持向后兼容。
     */
    private fun isCropAreaCompatible(entry: PageCacheEntity, currentCropWidth: Int, currentCropHeight: Int): Boolean {
        // 不校验：当前未提供裁剪尺寸（游戏模式）或缓存无裁剪记录（旧数据）
        if (currentCropWidth <= 0 || currentCropHeight <= 0) return true
        if (entry.cropWidth <= 0 || entry.cropHeight <= 0) return true

        val cachedArea = entry.cropWidth.toLong() * entry.cropHeight
        val currentArea = currentCropWidth.toLong() * currentCropHeight
        if (cachedArea <= 0) return true

        val ratio = currentArea.toFloat() / cachedArea.toFloat()
        val compatible = ratio in AREA_RATIO_MIN..AREA_RATIO_MAX
        if (!compatible) {
            LogCollector.d(TAG, "面积比不兼容: current=${currentCropWidth}x${currentCropHeight}, cached=${entry.cropWidth}x${entry.cropHeight}, ratio=${String.format("%.2f", ratio)}")
        }
        return compatible
    }

    /**
     * 从多个同 pHash 的缓存条目中，选面积最接近当前裁剪面积的兼容条目。
     * @return 最佳匹配条目，无兼容条目时返回 null
     */
    private fun findBestAreaMatch(entries: List<PageCacheEntity>, currentCropWidth: Int, currentCropHeight: Int): PageCacheEntity? {
        if (currentCropWidth <= 0 || currentCropHeight <= 0) {
            // 无裁剪尺寸信息（游戏模式），返回第一个
            return entries.firstOrNull()
        }
        val currentArea = currentCropWidth.toLong() * currentCropHeight
        var bestEntry: PageCacheEntity? = null
        var bestRatioDiff = Float.MAX_VALUE
        var hasRealMatch = false
        for (entry in entries) {
            if (entry.cropWidth <= 0 || entry.cropHeight <= 0) {
                // 旧数据：只有在没有真实匹配时才作为 fallback
                if (!hasRealMatch && bestEntry == null) bestEntry = entry
                continue
            }
            hasRealMatch = true
            val cachedArea = entry.cropWidth.toLong() * entry.cropHeight
            val ratio = currentArea.toFloat() / cachedArea.toFloat()
            if (ratio in AREA_RATIO_MIN..AREA_RATIO_MAX) {
                val ratioDiff = kotlin.math.abs(ratio - 1f)
                if (ratioDiff < bestRatioDiff) {
                    bestRatioDiff = ratioDiff
                    bestEntry = entry
                }
            }
        }
        return bestEntry
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
     * 按 OCR 原文查找漫画翻译缓存。
     * 传入 OCR 识别的原文列表（纯文本，无编号），排序后拼接作为指纹。
     * 同一页不同框选范围，只要 OCR 识别到相同的文字，就能命中缓存。
     * 使用内存缓存避免每次扫描全部历史。
     */
    suspend fun findMangaCacheByText(
        ocrTexts: List<String>,
        sourceLang: String,
        targetLang: String
    ): CacheResult? = withContext(Dispatchers.IO) {
        if (ocrTexts.isEmpty()) return@withContext null

        val queryFingerprint = ocrTexts.sorted().joinToString("\n")

        // 1. 先查内存缓存
        val cachedId = textFingerprintCache[queryFingerprint]
        if (cachedId != null) {
            val history = dao.getHistoryById(cachedId)
            if (history != null) {
                dao.updateLastAccessed(history.id, System.currentTimeMillis())
                val cacheEntry = dao.findCacheByHistoryId(history.id)
                LogCollector.d(TAG, "findMangaCacheByText: 内存缓存命中, historyId=${history.id}")
                return@withContext buildCacheResult(history, cacheEntry?.cropWidth ?: 0, cacheEntry?.cropHeight ?: 0)
            }
            // history 已被删除，清除失效缓存
            textFingerprintCache.remove(queryFingerprint)
        }

        // 2. 内存未命中，扫描数据库
        val allHistory = dao.getHistoryByType(MODE_MANGA, limit = 200)
        for (history in allHistory) {
            if (history.sourceText.isNullOrBlank()) continue
            val storedFingerprint = extractTextFingerprint(history.sourceText)
            if (storedFingerprint == queryFingerprint) {
                textFingerprintCache[storedFingerprint] = history.id
                dao.updateLastAccessed(history.id, System.currentTimeMillis())
                val cacheEntry = dao.findCacheByHistoryId(history.id)
                LogCollector.d(TAG, "findMangaCacheByText: 数据库命中, historyId=${history.id}")
                return@withContext buildCacheResult(history, cacheEntry?.cropWidth ?: 0, cacheEntry?.cropHeight ?: 0)
            }
        }

        LogCollector.d(TAG, "findMangaCacheByText: 未命中, texts=${ocrTexts.size}条")
        null
    }

    /**
     * 从 "[1] text1\n[2] text2..." 格式中提取纯文本，排序后拼接为指纹。
     */
    private fun extractTextFingerprint(numberedText: String): String {
        val regex = Regex("""\[\d+]\s*""")
        val texts = numberedText.split("\n")
            .map { it.replace(regex, "").trim() }
            .filter { it.isNotEmpty() }
        return texts.sorted().joinToString("\n")
    }

    /**
     * 保存翻译结果到历史 + 缓存。自动执行 LRU 淘汰。
     * 漫画模式：同 pHash 且面积比兼容的旧记录会被替换（防止重复/半成品残留）。
     * @param skipDedup 跳过去重（由 refreshCache 调用时，旧条目已删除，无需重复删除）
     */
    suspend fun saveToCache(entry: CacheEntry, skipDedup: Boolean = false) = withContext(Dispatchers.IO) {
        // 0. 漫画模式去重：同 pHash 且面积比兼容时替换旧记录
        if (!skipDedup && entry.type == MODE_MANGA && entry.pHash != 0L) {
            val allSameHash = dao.findAllCacheByHash(entry.pHash, MODE_MANGA)
            val target = findBestAreaMatch(allSameHash, entry.cropWidth, entry.cropHeight)
            if (target != null) {
                val oldHistory = dao.getHistoryById(target.historyId)
                dao.deleteCacheById(target.id)
                if (oldHistory != null) {
                    oldHistory.imagePath?.let { File(it).delete() }
                    oldHistory.thumbnailPath?.let { File(it).delete() }
                    if (!oldHistory.sourceText.isNullOrBlank()) {
                        textFingerprintCache.remove(extractTextFingerprint(oldHistory.sourceText))
                    }
                    dao.deleteHistoryById(oldHistory.id.toInt())
                    LogCollector.d(TAG, "saveToCache: 替换匹配旧缓存, historyId=${oldHistory.id}, crop=${target.cropWidth}x${target.cropHeight}")
                }
            }
        }

        // 1. 保存图片（漫画翻译）
        var imagePath: String? = null
        var thumbnailPath: String? = null
        if (entry.type == MODE_MANGA && entry.resultBitmap != null) {
            val timestamp = System.currentTimeMillis()
            imagePath = saveBitmap(entry.resultBitmap, "manga_${timestamp}.jpg")
            thumbnailPath = saveThumbnail(entry.resultBitmap, "manga_${timestamp}_thumb.jpg")
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

        // 更新内存指纹缓存（漫画模式）
        if (entry.type == MODE_MANGA && !entry.sourceText.isNullOrBlank()) {
            val fingerprint = extractTextFingerprint(entry.sourceText)
            if (textFingerprintCache.size > 500) {
                // 淘汰最早的 100 条，而非清空全部
                val iter = textFingerprintCache.iterator()
                repeat(100) { if (iter.hasNext()) { iter.next(); iter.remove() } }
            }
            textFingerprintCache[fingerprint] = historyId
        }

        // 3. LRU 淘汰：检查缓存容量
        evictIfNeeded(entry.type)

        // 4. 插入 cache 记录
        val cacheEntity = PageCacheEntity(
            historyId = historyId,
            pHash = entry.pHash,
            mode = entry.type,
            lastAccessedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            cropWidth = entry.cropWidth,
            cropHeight = entry.cropHeight
        )
        dao.insertCache(cacheEntity)
        LogCollector.d(TAG, "saveToCache: 插入 cache, pHash=${entry.pHash}, mode=${entry.type}")
    }

    /**
     * 强制刷新缓存：用与 findCache 相同的匹配逻辑找到要替换的条目，删除后保存新结果。
     * 只删除当前尺寸的条目，不同尺寸的条目保留。
     */
    suspend fun refreshCache(pHash: Long, mode: Int, newEntry: CacheEntry) = withContext(Dispatchers.IO) {
        // 用与 findCache 相同的匹配逻辑找到要替换的条目
        val allSameHash = dao.findAllCacheByHash(pHash, mode)
        val target = findBestAreaMatch(allSameHash, newEntry.cropWidth, newEntry.cropHeight)
        if (target != null) {
            dao.deleteCacheById(target.id)
            LogCollector.d(TAG, "refreshCache: 删除匹配旧 cache, id=${target.id}, crop=${target.cropWidth}x${target.cropHeight}")

            val oldHistory = dao.getHistoryById(target.historyId)
            if (oldHistory != null) {
                oldHistory.imagePath?.let { File(it).delete() }
                oldHistory.thumbnailPath?.let { File(it).delete() }
                if (!oldHistory.sourceText.isNullOrBlank()) {
                    textFingerprintCache.remove(extractTextFingerprint(oldHistory.sourceText))
                }
                dao.deleteHistoryById(oldHistory.id.toInt())
                LogCollector.d(TAG, "refreshCache: 删除匹配旧 history, id=${oldHistory.id}")
            }
        }

        // 保存新结果（跳过去重，旧条目已删除）
        saveToCache(newEntry, skipDedup = true)
        LogCollector.d(TAG, "refreshCache: 保存新结果")
    }

    /**
     * 游戏模式重新翻译：先删除旧的同源 history + cache，再保存新结果。
     */
    suspend fun refreshGameCache(
        sourceText: String,
        sourceLang: String,
        targetLang: String,
        newEntry: CacheEntry
    ) = withContext(Dispatchers.IO) {
        // 查找旧的同源 history
        val oldHistory = dao.findHistoryBySourceText(sourceText.trim(), sourceLang, targetLang)
        if (oldHistory != null) {
            // 删除关联的 cache 条目
            dao.deleteCacheByHistoryId(oldHistory.id)
            LogCollector.d(TAG, "refreshGameCache: 删除旧 cache by historyId=${oldHistory.id}")
            // 删除旧 history
            dao.deleteHistoryById(oldHistory.id.toInt())
            LogCollector.d(TAG, "refreshGameCache: 删除旧 history, id=${oldHistory.id}")
        }

        // 保存新结果（跳过去重，旧条目已删除）
        saveToCache(newEntry, skipDedup = true)
        LogCollector.d(TAG, "refreshGameCache: 保存新结果")
    }

    /**
     * 同步 pHash 缓存：当文本匹配命中时，将当前 pHash 也关联到同一 historyId。
     * 后续相同 pHash 可直接命中，无需再走文本匹配。
     * 检查精确匹配和相似 pHash（≥0.85），避免创建重复条目。
     */
    suspend fun syncPHashCache(pHash: Long, mode: Int, historyId: Long, cropWidth: Int = 0, cropHeight: Int = 0) = withContext(Dispatchers.IO) {
        // 1. 精确匹配已存在，跳过
        val exactMatch = dao.findCacheByPHash(pHash, mode)
        if (exactMatch != null) return@withContext

        // 2. 相似 pHash 已存在，跳过（避免为同一页面创建多个近似 pHash 条目）
        val allCache = dao.getAllCacheByMode(mode)
        for (entry in allCache) {
            val sim = PerceptualHash.similarity(pHash, entry.pHash)
            if (sim >= SIMILARITY_THRESHOLD_MANGA) {
                LogCollector.d(TAG, "syncPHashCache: 跳过, pHash=$pHash 与已有条目相似度=$sim")
                return@withContext
            }
        }

        // 3. 无匹配，创建新条目
        val cacheEntity = PageCacheEntity(
            historyId = historyId,
            pHash = pHash,
            mode = mode,
            lastAccessedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            cropWidth = cropWidth,
            cropHeight = cropHeight
        )
        dao.insertCache(cacheEntity)
        LogCollector.d(TAG, "syncPHashCache: 新增 pHash=$pHash → historyId=$historyId, crop=${cropWidth}x${cropHeight}")
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
     * 按 ID 获取单条历史记录。
     */
    suspend fun getHistoryById(id: Long): HistoryEntry? = withContext(Dispatchers.IO) {
        dao.getHistoryById(id)?.toHistoryEntry()
    }

    /**
     * 删除单条历史记录（同时删除关联文件和缓存条目）。
     */
    suspend fun deleteHistory(id: Long) = withContext(Dispatchers.IO) {
        val history = dao.getHistoryById(id) ?: return@withContext
        // 删除图片文件
        history.imagePath?.let { File(it).delete() }
        history.thumbnailPath?.let { File(it).delete() }
        // 清除内存指纹缓存
        if (!history.sourceText.isNullOrBlank()) {
            textFingerprintCache.remove(extractTextFingerprint(history.sourceText))
        }
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
        // 清除该类型的所有内存指纹缓存
        if (type == MODE_MANGA) {
            textFingerprintCache.clear()
        }
        LogCollector.d(TAG, "clearHistory: type=$type, deleted ${entities.size} entries")
    }

    // ========== 内部方法 ==========

    private suspend fun evictIfNeeded(mode: Int) {
        val count = dao.getCacheCount(mode)
        if (count >= MAX_CACHE_PER_MODE) {
            val oldest = dao.getOldestCache(mode)
            if (oldest != null) {
                dao.deleteCacheById(oldest.id)
                // 同时删除关联的 history 记录和图片文件，避免孤儿数据
                val history = dao.getHistoryById(oldest.historyId)
                if (history != null) {
                    history.imagePath?.let { File(it).delete() }
                    history.thumbnailPath?.let { File(it).delete() }
                    if (!history.sourceText.isNullOrBlank()) {
                        textFingerprintCache.remove(extractTextFingerprint(history.sourceText))
                    }
                    dao.deleteHistoryById(history.id.toInt())
                }
                LogCollector.d(TAG, "evictIfNeeded: 淘汰 cache id=${oldest.id}, historyId=${oldest.historyId}, mode=$mode")
            }
        }
    }

    private fun buildCacheResult(history: HistoryEntity, cropWidth: Int = 0, cropHeight: Int = 0): CacheResult {
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
            targetLang = history.targetLang,
            cropWidth = cropWidth,
            cropHeight = cropHeight
        )
    }

    private fun saveBitmap(bitmap: Bitmap, filename: String): String {
        val file = File(historyDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        LogCollector.d(TAG, "saveBitmap: ${bitmap.width}x${bitmap.height}, file=${file.length() / 1024}KB, path=$filename")
        return file.absolutePath
    }

    private fun saveThumbnail(bitmap: Bitmap, filename: String): String {
        val scale = THUMBNAIL_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val thumbW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val thumbH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true)
        val file = File(historyDir, filename)
        FileOutputStream(file).use { out ->
            thumb.compress(Bitmap.CompressFormat.JPEG, 85, out)
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
    val targetLang: String,
    val cropWidth: Int = 0,     // 缓存时的裁剪宽度（用于面积比校验）
    val cropHeight: Int = 0     // 缓存时的裁剪高度（用于面积比校验）
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
    val sessionId: String = "",  // 翻译会话 ID
    val cropWidth: Int = 0,     // 漫画翻译：裁剪区域宽度（用于面积比校验）
    val cropHeight: Int = 0     // 漫画翻译：裁剪区域高度（用于面积比校验）
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
    val sessionId: String = "",
    val pHash: Long = 0
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
    sessionId = sessionId,
    pHash = pHash
)
