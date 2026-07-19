package com.moe.starflow.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.PerceptualHash
import com.moe.starflow.manga.OverlayRenderer
import com.moe.starflow.manga.TextDirection
import com.moe.starflow.manga.TranslatedBubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
        private const val DEFAULT_CACHE_COUNT = 100
        private const val SIMILARITY_THRESHOLD_MANGA = 0.95f  // 256-bit hash 相似度阈值（~13 bit 容差）
        private const val THUMBNAIL_SIZE = 200
        private const val AREA_RATIO_MIN = 0.8f   // 面积比下限（框选偏移面积变化 <1%，宽松允许 ±20%）
        private const val AREA_RATIO_MAX = 1.25f  // 面积比上限

        // ========== 气泡解析工具函数 ==========

        /** 解析扩展气泡 JSON（含 fontSize + direction），兼容旧格式无 fs/dir 字段 */
        fun parseBubbleEntriesJson(json: String?, defaultFontSize: Float): List<BubbleJsonEntry> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val result = mutableListOf<BubbleJsonEntry>()
                val jsonArray = org.json.JSONArray(json)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val rect = android.graphics.Rect(
                        obj.getInt("l"), obj.getInt("t"),
                        obj.getInt("r"), obj.getInt("b")
                    )
                    val fs = if (obj.has("fs")) obj.getDouble("fs").toFloat() else defaultFontSize
                    val dir = if (obj.has("dir")) {
                        try { TextDirection.valueOf(obj.getString("dir")) }
                        catch (_: Exception) { TextDirection.VERTICAL_RL }
                    } else {
                        TextDirection.VERTICAL_RL
                    }
                    result.add(BubbleJsonEntry(rect, fs, dir))
                }
                result
            } catch (e: Exception) {
                LogCollector.e(TAG, "parseBubbleEntriesJson failed", e)
                emptyList()
            }
        }

        /**
         * 解析 "[1] text1\n[2] text2\n..." 格式的文本列表
         */
        fun parseIndexedTextList(text: String?): List<String> {
            if (text.isNullOrEmpty()) return emptyList()
            return try {
                text.split("\n").mapNotNull { line ->
                    val match = Regex("^\\[\\d+\\]\\s?(.*)$").find(line.trim())
                    match?.groupValues?.getOrNull(1)?.trim()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        /** 从缓存的 rect JSON + 文本列表重建 TranslatedBubble */
        fun rebuildBubblesFromCache(
            originals: List<String>,
            translations: List<String>,
            bubbleRectsJson: String?,
            defaultFontSize: Float,
            bgColor: Int
        ): List<TranslatedBubble> {
            val entries = parseBubbleEntriesJson(bubbleRectsJson, defaultFontSize)
            if (entries.isEmpty() || originals.isEmpty()) return emptyList()
            return entries.mapIndexed { idx, (rect, fontSize, direction) ->
                TranslatedBubble(
                    rect = rect,
                    originalText = originals.getOrElse(idx) { "" },
                    translatedText = translations.getOrElse(idx) { "" },
                    backgroundColor = bgColor,
                    fontSize = fontSize,
                    direction = direction,
                    angle = 0f,
                    fromCache = true
                )
            }
        }
    }

    // ========== 共享类型 ==========

    enum class OverlayMode { TRANSLATED, ORIGINAL, PLAIN }

    data class BubbleJsonEntry(
        val rect: android.graphics.Rect,
        val fontSize: Float,
        val direction: TextDirection
    )

    private val db = TranslationHistoryDatabase.getInstance(context)
    private val dao = db.historyDao()
    private val historyDir = File(context.filesDir, "history").also { it.mkdirs() }

    /** 获取用户设置的缓存数量上限，0 表示关闭缓存 */
    private fun getMaxCacheCount(): Int {
        return try {
            val prefs = com.moe.starflow.utils.CustomPreference.getInstance(context)
            prefs.getString("translation_cache_count", DEFAULT_CACHE_COUNT.toString()).toIntOrNull() ?: DEFAULT_CACHE_COUNT
        } catch (e: Exception) {
            DEFAULT_CACHE_COUNT
        }
    }

    // ========== 共享渲染层 ==========

    /** 渲染配置（字体、颜色等）*/
    data class OverlayConfig(
        val fontSize: Float = 16f,
        val autoFit: Boolean = true,
        val textColor: Int = android.graphics.Color.BLACK,
        val bgColor: Int = android.graphics.Color.argb(200, 255, 255, 255)
    )

    /**
     * 共享渲染方法：从 HistoryEntity + PageCacheEntity 渲染 overlay。
     * @param forFullImage true=全屏渲染（MangaViewerActivity，坐标映射），false=裁剪渲染（overlay/下载，裁剪后渲染）
     */
    suspend fun renderOverlay(
        history: HistoryEntry,
        pageCache: PageCacheEntity,
        mode: OverlayMode,
        forFullImage: Boolean,
        config: OverlayConfig = OverlayConfig()
    ): Bitmap? = withContext(Dispatchers.IO) {
        // 1. 加载全屏原图
        val originalPath = history.originalImagePath
        if (originalPath == null) {
            LogCollector.e(TAG, "renderOverlay: originalImagePath is null for history ${history.id}")
            return@withContext null
        }
        val fullBitmap = try {
            BitmapFactory.decodeFile(originalPath)
        } catch (e: Exception) {
            LogCollector.e(TAG, "renderOverlay: failed to decode $originalPath", e)
            return@withContext null
        } ?: return@withContext null

        // 2. 解析气泡数据
        val originals = parseIndexedTextList(history.sourceText)
        val translations = parseIndexedTextList(history.translatedText)
        val bubbles = rebuildBubblesFromCache(originals, translations, history.bubbleRects, config.fontSize, config.bgColor)

        // 3. 根据 forFullImage 决定渲染策略
        return@withContext if (forFullImage) {
            // MangaViewerActivity：在全屏原图上渲染，气泡坐标需映射
            if (mode == OverlayMode.PLAIN) {
                fullBitmap
            } else {
                val mappedBubbles = bubbles.map { b ->
                    b.copy(rect = android.graphics.Rect(
                        pageCache.cropLeft + b.rect.left,
                        pageCache.cropTop + b.rect.top,
                        pageCache.cropLeft + b.rect.right,
                        pageCache.cropTop + b.rect.bottom
                    ))
                }
                try {
                    OverlayRenderer.renderOverlay(
                        original = fullBitmap,
                        regions = mappedBubbles,
                        fontSize = config.fontSize,
                        autoFit = config.autoFit,
                        textColor = config.textColor,
                        bgColor = config.bgColor,
                        useOriginalText = (mode == OverlayMode.ORIGINAL)
                    )
                } finally {
                    // fullBitmap 由 OverlayRenderer 内部 copy 后返回，需要 recycle 原图
                    if (mode != OverlayMode.PLAIN) fullBitmap.recycle()
                }
            }
        } else {
            // overlay 窗口 / 下载：裁剪后渲染，气泡坐标直接对应
            val cropW = (pageCache.cropRight - pageCache.cropLeft).coerceAtLeast(1)
            val cropH = (pageCache.cropBottom - pageCache.cropTop).coerceAtLeast(1)
            val croppedBitmap = Bitmap.createBitmap(fullBitmap, pageCache.cropLeft, pageCache.cropTop, cropW, cropH)
            fullBitmap.recycle()
            if (mode == OverlayMode.PLAIN) {
                croppedBitmap
            } else {
                OverlayRenderer.renderOverlay(
                    original = croppedBitmap,
                    regions = bubbles,
                    fontSize = config.fontSize,
                    autoFit = config.autoFit,
                    textColor = config.textColor,
                    bgColor = config.bgColor,
                    useOriginalText = (mode == OverlayMode.ORIGINAL)
                )
            }
        }
    }

    fun getOverlayConfig(prefs: android.content.SharedPreferences): OverlayConfig {
        val fontSize = (prefs.getString("manga_font_size", "16")?.toFloatOrNull() ?: 16f)
        val autoFit = prefs.getBoolean("manga_auto_fit", true)
        val textColor = prefs.getInt("manga_text_color", android.graphics.Color.BLACK)
        val bgColor = prefs.getInt("manga_bg_color", android.graphics.Color.argb(200, 255, 255, 255))
        return OverlayConfig(fontSize, autoFit, textColor, bgColor)
    }

    // ========== 缓存操作 ==========

    /**
     * 查找缓存。先精确匹配 pHash，再相似度匹配（阈值按模式区分）。
     * 漫画模式下校验面积比：裁剪区域差异过大时跳过缓存（防止框选完全不同区域误命中）。
     * @param currentCropWidth 当前裁剪区域宽度（0=不校验面积比）
     * @param currentCropHeight 当前裁剪区域高度（0=不校验面积比）
     * @param lastSessionId 当前会话 ID（传入时同时更新 updatedAt 和 lastSessionId，用于按修改排序时移动到当前进程组）
     * @return 命中时返回 CacheResult，未命中返回 null
     */
    suspend fun findCache(pHash: Long, mode: Int, currentCropWidth: Int = 0, currentCropHeight: Int = 0, lastSessionId: String? = null): CacheResult? = withContext(Dispatchers.IO) {
        if (getMaxCacheCount() <= 0) return@withContext null

        // 1. 精确匹配（同 pHash 可能有多个条目，选面积最接近的）
        val allExactMatches = dao.findAllCacheByHash(pHash, mode)
        if (allExactMatches.isNotEmpty()) {
            val bestExact = findBestAreaMatch(allExactMatches, currentCropWidth, currentCropHeight)
            if (bestExact != null) {
                val now = System.currentTimeMillis()
                dao.updateLastAccessed(bestExact.id, now)
                val history = dao.getHistoryById(bestExact.historyId)
                if (history != null) {
                    // 更新 history 的 updatedAt 和 lastSessionId（用于按修改时间排序时移动到当前进程组）
                    if (history.updatedAt == 0L || history.updatedAt < now - 60_000) {
                        if (lastSessionId != null) {
                            dao.updateHistoryTimestampAndLastSession(history.id, now, lastSessionId)
                        } else {
                            dao.updateHistoryTimestamp(history.id, now)
                        }
                    }
                    LogCollector.d(TAG, "findCache: 精确命中, historyId=${history.id}, crop=${bestExact.effectiveCropWidth()}x${bestExact.effectiveCropHeight()}")
                    return@withContext buildCacheResult(history, bestExact.effectiveCropWidth(), bestExact.effectiveCropHeight(), bestExact)
                }
            } else {
                LogCollector.d(TAG, "findCache: 精确匹配存在但面积比都不兼容, 跳过")
            }
        }

        LogCollector.d(TAG, "findCache: 未命中, pHash=$pHash, mode=$mode")
        null
    }

    /**
     * 使用 256-bit 扩展哈希查找缓存（仅漫画模式）。
     * 精确匹配需要 4 段 hash 全部一致。
     * @param extHashes LongArray(4) 来自 PerceptualHash.computeExtended()
     */
    suspend fun findCacheExt(
        extHashes: LongArray,
        mode: Int,
        currentCropWidth: Int = 0,
        currentCropHeight: Int = 0,
        lastSessionId: String? = null
    ): CacheResult? = withContext(Dispatchers.IO) {
        if (getMaxCacheCount() <= 0) return@withContext null
        if (extHashes.size < 4) return@withContext findCache(extHashes[0], mode, currentCropWidth, currentCropHeight, lastSessionId)

        val pHash1 = extHashes[0]
        val pHash2 = extHashes[1]
        val pHash3 = extHashes[2]
        val pHash4 = extHashes[3]
        val currentBits = extHashes.sumOf { java.lang.Long.bitCount(it) }

        // 1. 精确匹配（4 段全部一致）— 快速路径（索引查询，O(1)）
        // 同一截图方式、同一页时 4 段 hash 完全一致，直接命中
        val exactMatch = dao.findCacheByExactHashExtended(pHash1, pHash2, pHash3, pHash4, mode)
        if (exactMatch != null) {
            // 面积比校验
            val isCompat = if (currentCropWidth > 0 && currentCropHeight > 0) {
                val ew = exactMatch.effectiveCropWidth()
                val eh = exactMatch.effectiveCropHeight()
                if (ew > 0 && eh > 0) {
                    val ratio = (currentCropWidth.toLong() * currentCropHeight).toFloat() / (ew.toLong() * eh)
                    ratio in AREA_RATIO_MIN..AREA_RATIO_MAX
                } else true
            } else true
            if (isCompat) {
                val now = System.currentTimeMillis()
                dao.updateLastAccessed(exactMatch.id, now)
                val history = dao.getHistoryById(exactMatch.historyId)
                if (history != null) {
                    if (history.updatedAt == 0L || history.updatedAt < now - 60_000) {
                        if (lastSessionId != null) {
                            dao.updateHistoryTimestampAndLastSession(history.id, now, lastSessionId)
                        } else {
                            dao.updateHistoryTimestamp(history.id, now)
                        }
                    }
                    LogCollector.d(TAG, "findCacheExt: 精确命中, historyId=${history.id}, crop=${exactMatch.effectiveCropWidth()}x${exactMatch.effectiveCropHeight()}")
                    return@withContext buildCacheResult(history, exactMatch.effectiveCropWidth(), exactMatch.effectiveCropHeight(), exactMatch)
                }
            }
        }

        // 2. 相似度匹配：精确匹配未命中的降级（容差 ~13 bit）
        // 用途：同一页在不同截图方式（无障碍/录屏）下像素略有差异时仍能命中
        // 性能：比精确匹配慢（遍历全部缓存），所以先走精确匹配的快速路径
        if (mode == MODE_MANGA) {
            val allCache = dao.getAllCacheByMode(mode)
            var bestMatch: PageCacheEntity? = null
            var bestSimilarity = 0f
            for (entry in allCache) {
                val entryHashes = longArrayOf(entry.pHash, entry.pHash2, entry.pHash3, entry.pHash4)
                val sim = PerceptualHash.similarity(extHashes, entryHashes)
                if (sim >= SIMILARITY_THRESHOLD_MANGA && sim > bestSimilarity) {
                    if (isCropAreaCompatible(entry, currentCropWidth, currentCropHeight)) {
                        bestSimilarity = sim
                        bestMatch = entry
                    }
                }
            }
            if (bestMatch != null) {
                val now = System.currentTimeMillis()
                dao.updateLastAccessed(bestMatch.id, now)
                val history = dao.getHistoryById(bestMatch.historyId)
                if (history != null) {
                    if (history.updatedAt == 0L || history.updatedAt < now - 60_000) {
                        if (lastSessionId != null) {
                            dao.updateHistoryTimestampAndLastSession(history.id, now, lastSessionId)
                        } else {
                            dao.updateHistoryTimestamp(history.id, now)
                        }
                    }
                    val entryBits = java.lang.Long.bitCount(bestMatch.pHash) + java.lang.Long.bitCount(bestMatch.pHash2) + java.lang.Long.bitCount(bestMatch.pHash3) + java.lang.Long.bitCount(bestMatch.pHash4)
                    LogCollector.d(TAG, "findCacheExt: 相似度命中 (${"%.3f".format(bestSimilarity)}, curBits=$currentBits/256, entryBits=$entryBits/256), historyId=${history.id}")
                    return@withContext buildCacheResult(history, bestMatch.effectiveCropWidth(), bestMatch.effectiveCropHeight(), bestMatch)
                }
            }
        }

        LogCollector.d(TAG, "findCacheExt: 未命中, mode=$mode")
        null
    }

    /**
     * 校验缓存条目的裁剪面积与当前裁剪面积是否兼容。
     * 旧数据（cropWidth=0）跳过校验，保持向后兼容。
     */
    private fun isCropAreaCompatible(entry: PageCacheEntity, currentCropWidth: Int, currentCropHeight: Int): Boolean {
        // 不校验：当前未提供裁剪尺寸（游戏模式）或缓存无裁剪记录（旧数据）
        if (currentCropWidth <= 0 || currentCropHeight <= 0) return true
        val entryWidth = entry.effectiveCropWidth()
        val entryHeight = entry.effectiveCropHeight()
        if (entryWidth <= 0 || entryHeight <= 0) return true

        val cachedArea = entryWidth.toLong() * entryHeight
        val currentArea = currentCropWidth.toLong() * currentCropHeight
        if (cachedArea <= 0) return true

        val ratio = currentArea.toFloat() / cachedArea.toFloat()
        val compatible = ratio in AREA_RATIO_MIN..AREA_RATIO_MAX
        if (!compatible) {
            LogCollector.d(TAG, "面积比不兼容: current=${currentCropWidth}x${currentCropHeight}, cached=${entryWidth}x${entryHeight}, ratio=${String.format("%.2f", ratio)}")
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
            val entryWidth = entry.effectiveCropWidth()
            val entryHeight = entry.effectiveCropHeight()
            if (entryWidth <= 0 || entryHeight <= 0) {
                // 旧数据：只有在没有真实匹配时才作为 fallback
                if (!hasRealMatch && bestEntry == null) bestEntry = entry
                continue
            }
            hasRealMatch = true
            val cachedArea = entryWidth.toLong() * entryHeight
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
        if (getMaxCacheCount() <= 0) return@withContext null
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
     * 漫画模式：同 pHash + 同尺寸的旧记录会被替换（防止重复/半成品残留）。
     * sessionId 从同 pHash 旧记录继承（保证按创建排序位置不变）。
     * lastSessionId 使用调用方传入的当前会话（用于按修改排序分组）。
     */
    suspend fun saveToCache(
        entry: CacheEntry,
        originalBitmap: Bitmap? = null,
        createdAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        if (getMaxCacheCount() <= 0) return@withContext

        // 漫画模式：查找并删除同 pHash + 同尺寸的旧记录，继承 sessionId 和 createdAt
        var inheritedSessionId = entry.sessionId
        var inheritedCreatedAt = createdAt
        if (entry.type == MODE_MANGA && entry.pHash != 0L) {
            var oldHistoryToDelete: HistoryEntity? = null
            var oldCacheToDelete: PageCacheEntity? = null
            val dedupWidth = entry.cropRight - entry.cropLeft
            val dedupHeight = entry.cropBottom - entry.cropTop
            if (dedupWidth > 0 && dedupHeight > 0) {
                // 优先使用新 crop rect 查询（精确匹配裁剪区域），无结果时 fallback 到旧的 cropWidth/cropHeight 查询
                oldCacheToDelete = dao.findCacheByHashAndCropRect(entry.pHash, MODE_MANGA, entry.cropLeft, entry.cropTop, entry.cropRight, entry.cropBottom)
                    ?: dao.findCacheByHashAndSize(entry.pHash, MODE_MANGA, dedupWidth, dedupHeight)
                if (oldCacheToDelete != null) {
                    oldHistoryToDelete = dao.getHistoryById(oldCacheToDelete.historyId)
                }
            }

            // 继承 sessionId 和 createdAt：优先从要删除的旧记录，否则从同 pHash 的任意记录
            // lastSessionId 不继承，使用调用方传入的当前会话
            if (oldHistoryToDelete != null) {
                inheritedSessionId = oldHistoryToDelete.sessionId
                inheritedCreatedAt = oldHistoryToDelete.createdAt
            } else {
                val anyOldCache = dao.findCacheByPHash(entry.pHash, MODE_MANGA)
                if (anyOldCache != null) {
                    val anyOldHistory = dao.getHistoryById(anyOldCache.historyId)
                    if (anyOldHistory != null) {
                        inheritedSessionId = anyOldHistory.sessionId
                        inheritedCreatedAt = anyOldHistory.createdAt
                    }
                }
            }

            // 删除旧记录
            if (oldHistoryToDelete != null && oldCacheToDelete != null) {
                dao.deleteCacheById(oldCacheToDelete.id)
                oldHistoryToDelete.imagePath?.let { f -> File(f).delete() }
                oldHistoryToDelete.thumbnailPath?.let { f -> File(f).delete() }
                dao.deleteHistoryById(oldHistoryToDelete.id.toInt())
                LogCollector.d(TAG, "saveToCache: 替换同尺寸旧记录, historyId=${oldHistoryToDelete.id}")
            }

        }

        // 保存图片（漫画翻译）
        var imagePath: String? = null
        var thumbnailPath: String? = null
        if (entry.type == MODE_MANGA && entry.resultBitmap != null) {
            val timestamp = System.currentTimeMillis()
            imagePath = saveBitmap(entry.resultBitmap, "manga_${timestamp}.jpg")
            // 缩略图优先用原图，没有原图则用 resultBitmap（兼容旧调用）
            val thumbSource = originalBitmap ?: entry.resultBitmap
            thumbnailPath = saveThumbnail(thumbSource!!, "manga_${timestamp}_thumb.jpg")
        } else if (entry.type == MODE_MANGA && originalBitmap != null) {
            // resultBitmap 为 null（新逻辑），缩略图用原图
            val timestamp = System.currentTimeMillis()
            thumbnailPath = saveThumbnail(originalBitmap, "manga_${timestamp}_thumb.jpg")
        }

        // Save original image (if provided — manga mode only)
        var originalImagePath: String? = null
        if (originalBitmap != null) {
            val timestamp = System.currentTimeMillis()
            originalImagePath = saveBitmap(originalBitmap, "manga_original_${timestamp}.jpg")
        }

        // 插入 history 记录
        val now = System.currentTimeMillis()
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
            pHash2 = entry.pHash2,
            pHash3 = entry.pHash3,
            pHash4 = entry.pHash4,
            createdAt = inheritedCreatedAt,
            sessionId = inheritedSessionId,
            lastSessionId = entry.lastSessionId,
            originalImagePath = originalImagePath,
            isRetranslated = entry.isRetranslated,
            bubbleRects = entry.bubbleRects,
            updatedAt = now
        )
        val historyId = dao.insertHistory(historyEntity)
        LogCollector.d(TAG, "saveToCache: 插入 history, id=$historyId, sessionId=$inheritedSessionId, createdAt=$inheritedCreatedAt")

        // LRU 淘汰
        evictIfNeeded(entry.type)

        // 插入 cache 记录
        val cacheEntity = PageCacheEntity(
            historyId = historyId,
            pHash = entry.pHash,
            pHash2 = entry.pHash2,
            pHash3 = entry.pHash3,
            pHash4 = entry.pHash4,
            mode = entry.type,
            lastAccessedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            cropWidth = (entry.cropRight - entry.cropLeft).coerceAtLeast(0),
            cropHeight = (entry.cropBottom - entry.cropTop).coerceAtLeast(0),
            cropLeft = entry.cropLeft,
            cropTop = entry.cropTop,
            cropRight = entry.cropRight,
            cropBottom = entry.cropBottom
        )
        dao.insertCache(cacheEntity)
        LogCollector.d(TAG, "saveToCache: 插入 cache, pHash=0x${entry.pHash.toString(16)}, pHash2=0x${entry.pHash2.toString(16)}, pHash3=0x${entry.pHash3.toString(16)}, pHash4=0x${entry.pHash4.toString(16)}, mode=${entry.type}")
    }

    /**
     * 强制刷新缓存：用 historyId 删除旧记录，保存新结果。
     * sessionId 继承旧记录（按创建排序位置不变），lastSessionId 使用当前会话，createdAt 继承旧记录。
     */
    suspend fun refreshCache(historyIdToDelete: Long, newEntry: CacheEntry, originalBitmap: Bitmap? = null) = withContext(Dispatchers.IO) {
        var inheritedSessionId = newEntry.sessionId
        var inheritedCreatedAt = System.currentTimeMillis()
        val oldHistory = dao.getHistoryById(historyIdToDelete)
        if (oldHistory != null) {
            // sessionId 继承旧值（按创建排序位置不变）
            inheritedSessionId = oldHistory.sessionId
            inheritedCreatedAt = oldHistory.createdAt
            // 删除关联的 cache 条目
            val oldCache = dao.findCacheByHistoryId(historyIdToDelete)
            if (oldCache != null) {
                dao.deleteCacheById(oldCache.id)
            }
            oldHistory.imagePath?.let { File(it).delete() }
            oldHistory.thumbnailPath?.let { File(it).delete() }
            dao.deleteHistoryById(oldHistory.id.toInt())
            LogCollector.d(TAG, "refreshCache: 删除旧记录, historyId=$historyIdToDelete, sessionId=$inheritedSessionId, createdAt=$inheritedCreatedAt")
        }

        // lastSessionId 使用调用方的当前会话（不继承旧值），sessionId 和 createdAt 继承旧值
        saveToCache(newEntry.copy(sessionId = inheritedSessionId), originalBitmap = originalBitmap, createdAt = inheritedCreatedAt)
        LogCollector.d(TAG, "refreshCache: 保存新结果, sessionId=$inheritedSessionId, lastSessionId=${newEntry.lastSessionId}, createdAt=$inheritedCreatedAt")
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
        var inheritedSessionId = newEntry.sessionId
        var inheritedCreatedAt = System.currentTimeMillis()
        val oldHistory = dao.findHistoryBySourceText(sourceText.trim(), sourceLang, targetLang)
        if (oldHistory != null) {
            // sessionId 继承旧值（按创建排序位置不变）
            inheritedSessionId = oldHistory.sessionId
            inheritedCreatedAt = oldHistory.createdAt
            dao.deleteCacheByHistoryId(oldHistory.id)
            LogCollector.d(TAG, "refreshGameCache: 删除旧 cache by historyId=${oldHistory.id}")
            dao.deleteHistoryById(oldHistory.id.toInt())
            LogCollector.d(TAG, "refreshGameCache: 删除旧 history, id=${oldHistory.id}, sessionId=$inheritedSessionId, createdAt=$inheritedCreatedAt")
        }

        // lastSessionId 使用调用方的当前会话（不继承旧值），sessionId 和 createdAt 继承旧值
        saveToCache(newEntry.copy(sessionId = inheritedSessionId), createdAt = inheritedCreatedAt)
        LogCollector.d(TAG, "refreshGameCache: 保存新结果, sessionId=$inheritedSessionId, lastSessionId=${newEntry.lastSessionId}, createdAt=$inheritedCreatedAt")
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
     * 获取按日期和会话分组的历史记录。
     *
     * 按创建排序：日期组 = createdAt 日期，进程组 = sessionId（永不改变），组内按 updatedAt DESC
     * 按修改排序：日期组 = updatedAt 日期，进程组 = lastSessionId（修改时更新为当前会话），组内按 updatedAt DESC
     */
    suspend fun getHistoryGrouped(type: Int, limit: Int = 200, sortByUpdated: Boolean = false): List<HistoryGroup> = withContext(Dispatchers.IO) {
        val entities = dao.getHistoryByType(type, limit)
        val rawEntries = entities.map { it.toHistoryEntry() }

        if (rawEntries.isEmpty()) return@withContext emptyList()

        // 漫画模式：按 256-bit 扩展哈希相似度去重（与 MangaViewerActivity.buildPageGroups 一致）
        val entries = if (type == MODE_MANGA) {
            groupMangaEntriesByPHash(rawEntries)
        } else {
            rawEntries
        }

        fun getDateLabel(timestamp: Long): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(timestamp))
        }

        if (sortByUpdated) {
            // 默认视图：按修改时间排序，日期分组，无进程分组
            val groupedByDate = entries.groupBy { getDateLabel(it.updatedAt) }

            groupedByDate.entries.sortedByDescending { entry ->
                entry.value.maxOf { it.updatedAt }
            }.map { (dateLabel, dateEntries) ->
                val session = HistorySession(
                    sessionId = dateLabel,  // date as session ID
                    startTime = dateEntries.minOf { it.createdAt },
                    endTime = dateEntries.maxOf { it.updatedAt },
                    entries = dateEntries.sortedByDescending { it.updatedAt }
                )
                HistoryGroup(dateLabel = dateLabel, sessions = listOf(session))
            }
        } else {
            // 按创建排序：日期组 = createdAt 日期，进程组 = sessionId，组内按 createdAt ASC
            val groupedByDate = entries.groupBy { getDateLabel(it.createdAt) }

            groupedByDate.entries.sortedByDescending { entry ->
                entry.value.maxOf { it.createdAt }
            }.map { (dateLabel, dateEntries) ->
                val sessions = dateEntries.groupBy { it.sessionId }
                    .map { (sessionId, sessionEntries) ->
                        HistorySession(
                            sessionId = sessionId,
                            startTime = sessionEntries.minOf { it.createdAt },
                            endTime = sessionEntries.maxOf { it.createdAt },
                            entries = sessionEntries.sortedBy { it.createdAt }
                        )
                    }
                    .sortedByDescending { it.startTime }

                HistoryGroup(dateLabel = dateLabel, sessions = sessions)
            }
        }
    }

    /**
     * 按 256-bit 扩展哈希相似度分组（漫画历史去重）。
     * 与 MangaViewerActivity.buildPageGroups 使用相同的算法和阈值（0.85），
     * 保证历史列表和图片浏览器的分组数量一致。
     *
     * @return 去重后的 HistoryEntry 列表，每个代表条目携带 variantCount 和 variantIds
     */
    fun groupMangaEntriesByPHash(entries: List<HistoryEntry>): List<HistoryEntry> {
        val used = mutableSetOf<Long>()
        val groups = mutableListOf<HistoryEntry>()

        for (entry in entries) {
            if (entry.id in used) continue
            if (entry.pHash == 0L) {
                groups.add(entry)
                used.add(entry.id)
                continue
            }

            val variants = entries.filter {
                it.id !in used && it.pHash != 0L &&
                    PerceptualHash.similarity(
                        longArrayOf(entry.pHash, entry.pHash2, entry.pHash3, entry.pHash4),
                        longArrayOf(it.pHash, it.pHash2, it.pHash3, it.pHash4)
                    ) >= 0.85f
            }
            variants.forEach { used.add(it.id) }

            val sorted = variants.sortedByDescending { it.updatedAt }
            val representative = sorted.first()
            groups.add(representative.copy(
                variantCount = sorted.size,
                variantIds = sorted.map { it.id }
            ))
        }
        return groups
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
    suspend fun getPageCachesByHistoryIds(ids: List<Long>): List<PageCacheEntity> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        dao.getPageCachesByHistoryIds(ids)
    }

    suspend fun getHistoryById(id: Long): HistoryEntry? = withContext(Dispatchers.IO) {
        dao.getHistoryById(id)?.toHistoryEntry()
    }

    /**
     * 批量获取多条历史记录。
     */
    suspend fun getHistoryByIds(ids: List<Long>): List<HistoryEntry> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        dao.getHistoryByIds(ids).map { it.toHistoryEntry() }
    }

    /**
     * 更新历史记录的 updatedAt 时间戳（缓存命中时调用）。
     */
    suspend fun updateHistoryTimestamp(id: Long) = withContext(Dispatchers.IO) {
        dao.updateHistoryTimestamp(id, System.currentTimeMillis())
    }

    /**
     * 获取当前缓存总数（游戏+漫画）。
     */
    fun getCacheCount(): Int {
        return try {
            kotlinx.coroutines.runBlocking {
                dao.getCacheCount(MODE_GAME) + dao.getCacheCount(MODE_MANGA)
            }
        } catch (e: Exception) {
            0
        }
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

    /**
     * 清空全部历史记录和缓存（游戏+漫画）。
     */
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        val allEntities = dao.getHistoryByType(MODE_GAME, limit = 10000) + dao.getHistoryByType(MODE_MANGA, limit = 10000)
        for (entity in allEntities) {
            entity.imagePath?.let { File(it).delete() }
            entity.thumbnailPath?.let { File(it).delete() }
        }
        dao.deleteAllHistory()
        LogCollector.d(TAG, "clearAllCache: deleted ${allEntities.size} entries")
    }

    // ========== 内部方法 ==========

    suspend fun getCacheByHistoryId(historyId: Long): PageCacheEntity? = withContext(Dispatchers.IO) {
        dao.findCacheByHistoryId(historyId)
    }

    /** 获取 PageCacheEntity 的裁剪宽度：优先 cropWidth，否则从 cropRight-cropLeft 计算 */
    private fun PageCacheEntity.effectiveCropWidth(): Int =
        if (cropWidth > 0) cropWidth else (cropRight - cropLeft).coerceAtLeast(0)

    /** 获取 PageCacheEntity 的裁剪高度：优先 cropHeight，否则从 cropBottom-cropTop 计算 */
    private fun PageCacheEntity.effectiveCropHeight(): Int =
        if (cropHeight > 0) cropHeight else (cropBottom - cropTop).coerceAtLeast(0)

    private suspend fun evictIfNeeded(mode: Int) {
        val maxCount = getMaxCacheCount()
        if (maxCount <= 0) return
        val count = dao.getCacheCount(mode)
        if (count >= maxCount) {
            val oldest = dao.getOldestCache(mode)
            if (oldest != null) {
                dao.deleteCacheById(oldest.id)
                // 同时删除关联的 history 记录和图片文件，避免孤儿数据
                val history = dao.getHistoryById(oldest.historyId)
                if (history != null) {
                    history.imagePath?.let { File(it).delete() }
                    history.thumbnailPath?.let { File(it).delete() }
                    dao.deleteHistoryById(history.id.toInt())
                }
                LogCollector.d(TAG, "evictIfNeeded: 淘汰 cache id=${oldest.id}, historyId=${oldest.historyId}, mode=$mode")
            }
        }
    }

    private fun buildCacheResult(history: HistoryEntity, cropWidth: Int = 0, cropHeight: Int = 0, pageCache: PageCacheEntity? = null): CacheResult {
        // 漫画模式：优先加载原图（originalImagePath），回退到渲染图（imagePath）兼容旧数据
        val bitmapPath = if (history.type == MODE_MANGA) {
            history.originalImagePath ?: history.imagePath
        } else {
            history.imagePath
        }
        val bitmap = bitmapPath?.let { path ->
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
            cropHeight = cropHeight,
            bubbleRects = history.bubbleRects,
            pageCache = pageCache,
            historyEntity = history.toHistoryEntry()
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
    val cropHeight: Int = 0,    // 缓存时的裁剪高度（用于面积比校验）
    val bubbleRects: String? = null,  // JSON: [{"l":10,"t":20,"r":100,"b":60}, ...] 气泡位置数据
    val pageCache: PageCacheEntity? = null,  // 关联的缓存条目（含 crop 坐标）
    val historyEntity: HistoryEntry? = null  // 关联的历史条目（含 bubbleRects 等）
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
    val pHash2: Long = 0,       // 扩展哈希（256-bit 第 2 段）
    val pHash3: Long = 0,       // 扩展哈希（256-bit 第 3 段）
    val pHash4: Long = 0,       // 扩展哈希（256-bit 第 4 段）
    val sessionId: String = "",      // 原始创建会话 ID（永不改变）
    val lastSessionId: String = "",  // 最后修改会话 ID（任何修改时更新为当前会话）
    val cropLeft: Int = 0,      // 裁剪区域左边界
    val cropTop: Int = 0,       // 裁剪区域上边界
    val cropRight: Int = 0,     // 裁剪区域右边界
    val cropBottom: Int = 0,    // 裁剪区域下边界
    val isRetranslated: Boolean = false,
    val bubbleRects: String? = null,  // JSON: [{"l":10,"t":20,"r":100,"b":60}, ...] 气泡位置数据
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
    val createdAt: Long = 0,            // 原始创建时间（继承自同 pHash 旧记录，永不改变）
    val sessionId: String = "",         // 原始创建会话 ID（永不改变，用于按创建排序分组）
    val lastSessionId: String = "",     // 最后修改会话 ID（任何修改时更新，用于按修改排序分组）
    val pHash: Long = 0,
    val pHash2: Long = 0,       // 扩展哈希（256-bit 第 2 段）
    val pHash3: Long = 0,       // 扩展哈希（256-bit 第 3 段）
    val pHash4: Long = 0,       // 扩展哈希（256-bit 第 4 段）
    val updatedAt: Long = 0,
    val variantCount: Int = 1,          // 同 pHash 的不同尺寸数量
    val variantIds: List<Long> = emptyList(),  // 同 pHash 的所有变体 ID
    val originalImagePath: String? = null,
    val isRetranslated: Boolean = false,
    val bubbleRects: String? = null,  // JSON: [{"l":10,"t":20,"r":100,"b":60}, ...]
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
    lastSessionId = lastSessionId,
    pHash = pHash,
    pHash2 = pHash2,
    pHash3 = pHash3,
    pHash4 = pHash4,
    updatedAt = updatedAt,
    originalImagePath = originalImagePath,
    isRetranslated = isRetranslated,
    bubbleRects = bubbleRects
)
