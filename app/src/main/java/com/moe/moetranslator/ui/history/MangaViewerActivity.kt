package com.moe.moetranslator.ui.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.moe.moetranslator.R
import com.moe.moetranslator.data.CacheEntry
import com.moe.moetranslator.data.HistoryEntry
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.databinding.ActivityMangaViewerBinding
import com.moe.moetranslator.manga.BubbleRegion
import com.moe.moetranslator.manga.ComicBubbleDetector
import com.moe.moetranslator.manga.CTDDetector
import com.moe.moetranslator.manga.DetEngine
import com.moe.moetranslator.manga.DetectionBridge
import com.moe.moetranslator.manga.MangaOcrRecognizer
import com.moe.moetranslator.manga.OcrEngine
import com.moe.moetranslator.manga.OcrLock
import com.moe.moetranslator.manga.OverlayRenderer
import com.moe.moetranslator.manga.PPOcrV5Engine
import com.moe.moetranslator.manga.TextDirection
import com.moe.moetranslator.manga.TranslatedBubble
import com.moe.moetranslator.manga.TranslateUtils
import com.moe.moetranslator.translate.ScreenshotManager
import com.moe.moetranslator.translate.TranslationResult
import com.moe.moetranslator.translate.TranslationTextAPI
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.Constants
import com.moe.moetranslator.utils.KeystoreManager
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.PerceptualHash
import com.moe.moetranslator.me.ConfigurationStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MangaViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MangaViewerActivity"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_ENTRY_IDS = "entry_ids"  // 同 pHash 多尺寸条目
        const val EXTRA_IS_MANAGE_VIEW = "is_manage_view"
    }

    private lateinit var binding: ActivityMangaViewerBinding
    private lateinit var cacheManager: TranslationCacheManager

    // 每个 pHash 组：代表条目 + 所有尺寸变体
    data class PageGroup(
        var representative: HistoryEntry,
        val variants: MutableList<HistoryEntry> = mutableListOf(representative)
    )
    private val pageGroups = mutableListOf<PageGroup>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var isPanelExpanded = false
    private var groupEntryIds: List<Long> = emptyList()
    private var showingOriginal = false
    private var savedClickedEntryId: Long = -1L
    private var savedEntryIds: LongArray? = null
    private var isManageView = false
    private var currentPagePosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivityMangaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cacheManager = TranslationCacheManager(this)

        val clickedEntryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        val entryIds = intent.getLongArrayExtra(EXTRA_ENTRY_IDS)
        savedClickedEntryId = clickedEntryId
        savedEntryIds = entryIds
        isManageView = intent.getBooleanExtra(EXTRA_IS_MANAGE_VIEW, false)

        setupViews()
        loadData(clickedEntryId, entryIds)
    }

    private fun setupViews() {
        // 关闭按钮
        binding.btnClose.setOnClickListener { finish() }

        // 查看译文按钮
        binding.btnShowTranslation.setOnClickListener {
            togglePanel()
        }

        // 关闭底部面板
        binding.btnCloseSheet.setOnClickListener {
            collapsePanel()
        }

        // 删除当前条目（顶部按钮）
        binding.btnDeleteEntry.setOnClickListener {
            confirmDeleteCurrentEntry()
        }

        // 原图/译文切换
        binding.btnToggleImage.setOnClickListener {
            showingOriginal = !showingOriginal
            binding.btnToggleImage.setImageResource(
                if (showingOriginal) android.R.drawable.ic_menu_gallery
                else android.R.drawable.ic_menu_camera
            )
            val entry = getCurrentVariant()
            val path = if (showingOriginal) entry.originalImagePath else (entry.imagePath ?: entry.thumbnailPath)
            if (path != null && java.io.File(path).exists()) {
                val bmp = android.graphics.BitmapFactory.decodeFile(path)
                val adapter = binding.viewPager.adapter as? PageGroupAdapter
                adapter?.setOverrideImage(bmp)
                adapter?.notifyItemChanged(binding.viewPager.currentItem)
                // 直接更新当前可见页面的图片（notifyItemChanged 在 ViewPager2 第一页可能不触发 rebind）
                val recyclerView = binding.viewPager.getChildAt(0) as? RecyclerView
                val viewHolder = recyclerView?.findViewHolderForAdapterPosition(binding.viewPager.currentItem) as? PageGroupAdapter.ViewHolder
                if (viewHolder != null) {
                    viewHolder.imageView.setImageBitmap(bmp)
                    viewHolder.imageView.resetZoom()
                }
            }
        }

        // Variant spinner
        binding.spinnerVariant.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val group = pageGroups.getOrNull(binding.viewPager.currentItem) ?: return
                val variant = group.variants.getOrNull(position) ?: return
                activeVariantIds[binding.viewPager.currentItem] = variant.id
                val adapter = binding.viewPager.adapter as? PageGroupAdapter
                adapter?.setActiveVariant(binding.viewPager.currentItem, variant.id)
                adapter?.notifyItemChanged(binding.viewPager.currentItem)
                if (isPanelExpanded) expandPanel()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 重新翻译按钮 — 独立 OCR+翻译+渲染流程，不需要 MangaFloatingService
        binding.btnRetranslate.setOnClickListener {
            val entry = getCurrentVariant()
            val originalPath = entry.originalImagePath
            if (originalPath.isNullOrEmpty() || !java.io.File(originalPath).exists()) {
                com.moe.moetranslator.utils.UiUtils.showToast(this, "原图不可用")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val cache = cacheManager.getCacheByHistoryId(entry.id)
                if (cache == null || cache.cropRight <= 0) {
                    com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "无裁剪信息")
                    return@launch
                }

                if (!com.moe.moetranslator.manga.OcrLock.tryAcquire()) {
                    com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "翻译进行中，请稍后")
                    return@launch
                }

                binding.btnRetranslate.isEnabled = false
                com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "正在翻译...")
                try {
                    val savedPosition = binding.viewPager.currentItem
                    withContext(Dispatchers.IO) {
                        val original = BitmapFactory.decodeFile(originalPath) ?: throw Exception("原图加载失败")
                        val cropRect = android.graphics.RectF(
                            cache.cropLeft.toFloat(), cache.cropTop.toFloat(),
                            cache.cropRight.toFloat(), cache.cropBottom.toFloat()
                        )
                        val cropped = ScreenshotManager.cropBitmap(original, cropRect, android.graphics.Point(0, 0))

                        val prefs = CustomPreference.getInstance(this@MangaViewerActivity)
                        val engineName = prefs.getString("history_retranslate_engine", "PP_OCR_V5") ?: "PP_OCR_V5"
                        val (detEngine, ocrEngine) = mapEngineToDetOcr(engineName)
                        val sourceLang = prefs.getString("Manga_Source_Language", "ja") ?: "ja"
                        val targetLang = prefs.getString("Manga_Target_Language", "zh") ?: "zh"

                        initializeEngines(detEngine, ocrEngine)

                        val ocrResults = DetectionBridge.runOCR(cropped, sourceLang, detEngine.value, ocrEngine.value, this@MangaViewerActivity)
                        if (ocrResults.isEmpty()) throw Exception("OCR 未识别到文字")

                        val bubbles = DetectionBridge.ocrToBubbleRegions(ocrResults)
                        if (bubbles.isEmpty()) throw Exception("无有效文字区域")

                        val translator = createTranslator(prefs) ?: throw Exception("翻译器创建失败")

                        val translatedBubbles = com.moe.moetranslator.manga.TranslateUtils.translateBubbles(
                            translator, bubbles, sourceLang, targetLang, prefs)
                        if (translatedBubbles.isEmpty()) throw Exception("翻译失败")

                        val rendered = OverlayRenderer.renderOverlay(
                            original = cropped, regions = translatedBubbles,
                            fontSize = prefs.getFloat("Manga_Font_Size", 16f),
                            autoFit = prefs.getBoolean("Manga_Auto_Font_Size", true),
                            textColor = prefs.getInt("Manga_Text_Color", android.graphics.Color.BLACK),
                            bgColor = prefs.getInt("Manga_BG_Color", android.graphics.Color.argb(200, 255, 255, 255))
                        )

                        val ocrTexts = bubbles.map { it.texts.first() }
                        val numberedText = ocrTexts.mapIndexed { i, t -> "[${i + 1}] $t" }.joinToString("\n")
                        val transText = translatedBubbles.mapIndexed { i, b -> "[${i + 1}] ${b.translatedText}" }.joinToString("\n")

                        cacheManager.refreshCache(entry.id, CacheEntry(
                            type = TranslationCacheManager.MODE_MANGA,
                            sourceText = numberedText, translatedText = transText,
                            resultBitmap = rendered, sourceLang = sourceLang, targetLang = targetLang,
                            translatorName = buildRetranslateName(translator, detEngine, ocrEngine, prefs), pHash = entry.pHash,
                            sessionId = "", lastSessionId = "",
                            cropLeft = cache.cropLeft, cropTop = cache.cropTop,
                            cropRight = cache.cropRight, cropBottom = cache.cropBottom,
                            isRetranslated = true,
                        ), originalBitmap = original)
                    }
                    com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "重新翻译完成")
                    val currentPos = savedPosition
                    lifecycleScope.launch {
                        val allEntries = cacheManager.getHistory(TranslationCacheManager.MODE_MANGA, limit = 500)
                        pageGroups.clear()
                        pageGroups.addAll(buildPageGroups(allEntries))
                        binding.viewPager.adapter?.notifyDataSetChanged()
                        updatePageIndicator(currentPos.coerceAtMost(pageGroups.size - 1))
                    }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Retranslate failed", e)
                    com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, e.message ?: "重新翻译失败")
                } finally {
                    binding.btnRetranslate.isEnabled = true
                    com.moe.moetranslator.manga.OcrLock.release()
                }
            }
        }

        // 非管理视图隐藏重新翻译按钮
        if (!isManageView) {
            binding.btnRetranslate.visibility = View.GONE
        }

        // ViewPager 翻页监听
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
                // 翻页时重置原图/译文切换
                showingOriginal = false
                binding.btnToggleImage.setImageResource(android.R.drawable.ic_menu_camera)
                val adapter = binding.viewPager.adapter as? PageGroupAdapter
                adapter?.setOverrideImage(null)
                // 只通知旧页面清除 override（不通知新页面，避免闪屏）
                if (currentPagePosition != position) {
                    adapter?.notifyItemChanged(currentPagePosition)
                }
                currentPagePosition = position
                // 翻页时关闭面板
                if (isPanelExpanded) {
                    collapsePanel()
                }
                updateVariantSpinner(position)
            }
        })

        // 初始隐藏底部面板
        binding.bottomSheetPanel.post {
            binding.bottomSheetPanel.translationY = binding.bottomSheetPanel.height.toFloat()
        }
    }

    private fun loadData(clickedEntryId: Long, entryIds: LongArray? = null) {
        lifecycleScope.launch {
            try {
                // 加载所有漫画历史
                val allEntries = cacheManager.getHistory(TranslationCacheManager.MODE_MANGA, limit = 500)

                if (allEntries.isEmpty()) {
                    com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, getString(R.string.no_translation_data))
                    finish()
                    return@launch
                }

                // 保存分组 ID（来自历史列表点击）
                groupEntryIds = entryIds?.toList() ?: emptyList()

                // 按 pHash 分组（相似度 ≥ 0.85 视为同一页）
                pageGroups.clear()
                pageGroups.addAll(buildPageGroups(allEntries))

                // 设置 ViewPager（每页 = 一个 pHash 组）
                val adapter = PageGroupAdapter(pageGroups)
                binding.viewPager.adapter = adapter

                // 跳转到点击的组
                val clickedGroupId = pageGroups.indexOfFirst {
                    it.variants.any { v -> v.id == clickedEntryId }
                }
                val safeIndex = if (clickedGroupId >= 0) clickedGroupId else 0

                // 设置初始活跃变体（点击的条目或代表条目）
                val clickedGroup = pageGroups.getOrNull(safeIndex)
                if (clickedGroup != null) {
                    val initialVariant = if (clickedEntryId > 0 && clickedGroup.variants.any { it.id == clickedEntryId }) {
                        clickedEntryId
                    } else {
                        clickedGroup.representative.id
                    }
                    activeVariantIds[safeIndex] = initialVariant
                }

                binding.viewPager.setCurrentItem(safeIndex, false)
                updatePageIndicator(safeIndex)
                updateVariantSpinner(safeIndex)
                updateVariantSpinner(safeIndex)

                LogCollector.d(TAG, "加载漫画历史, ${pageGroups.size} 页, 跳转到 #$safeIndex")
            } catch (e: Exception) {
                LogCollector.e(TAG, "加载数据失败", e)
                com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, getString(R.string.no_translation_data))
                finish()
            }
        }
    }

    /**
     * 将所有条目按 pHash 相似度分组，每组取最新为代表。
     */
    private fun buildPageGroups(allEntries: List<HistoryEntry>): List<PageGroup> {
        val used = mutableSetOf<Long>()
        val groups = mutableListOf<PageGroup>()

        for (entry in allEntries) {
            if (entry.id in used) continue
            if (entry.pHash == 0L) {
                // 无 pHash 的条目独立成组
                groups.add(PageGroup(entry))
                used.add(entry.id)
                continue
            }

            // 找同 pHash 的条目
            val variants = allEntries.filter {
                it.id !in used && it.pHash != 0L &&
                    PerceptualHash.similarity(entry.pHash, it.pHash) >= 0.85f
            }
            variants.forEach { used.add(it.id) }

            // 取最新为代表
            val sorted = variants.sortedByDescending { it.updatedAt }
            groups.add(PageGroup(
                representative = sorted.first(),
                variants = sorted.toMutableList()
            ))
        }
        return groups
    }

    /**
     * 更新 variant spinner（尺寸选择器）。
     */
    private fun updateVariantSpinner(position: Int) {
        val group = pageGroups.getOrNull(position)
        if (group == null || group.variants.size <= 1) {
            binding.spinnerVariant.visibility = android.view.View.GONE
            return
        }
        binding.spinnerVariant.visibility = android.view.View.VISIBLE
        val items = group.variants.map { v ->
            v.imagePath?.let { getImageDimensions(it) } ?: "?"
        }
        val adapter = android.widget.ArrayAdapter(this, R.layout.spinner_item_dark, items)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
        binding.spinnerVariant.adapter = adapter
        val activeId = activeVariantIds[position] ?: group.representative.id
        val idx = group.variants.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        binding.spinnerVariant.setSelection(idx, false)
    }

    /**
     * 根据当前偏好设置创建翻译器实例。
     * 与 MangaFloatingService.initTranslator 一致的逻辑。
     */
    private fun createTranslator(prefs: CustomPreference): TranslationTextAPI? {
        val textApi = prefs.getInt("Text_API", Constants.TextApi.BING.id)
        val textAI = prefs.getInt("Text_AI", Constants.TextAI.MLKIT.id)
        return when (textApi) {
            Constants.TextApi.AI.id -> when (textAI) {
                Constants.TextAI.MLKIT.id -> translationapi.mlkittranslation.MLKitTranslation()
                Constants.TextAI.NLLB.id -> translationapi.nllbtranslation.NLLBTranslation(this)
                else -> null
            }
            Constants.TextApi.BING.id -> translationapi.bingtranslation.BingTranslation()
            Constants.TextApi.NIUTRANS.id -> {
                val key = KeystoreManager.retrieveKey(this, "Niutrans") ?: return null
                translationapi.niutrans.NiuTranslation(key)
            }
            Constants.TextApi.OPENAI.id -> {
                val providerList = ConfigurationStorage.loadAllProviders(prefs)
                val selectedIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
                val provider = providerList.getOrNull(selectedIndex) ?: return null
                translationapi.openaitranslation.OpenAITranslation(
                    apiKey = provider.apiKey,
                    baseUrl = provider.baseUrl,
                    model = provider.modelName,
                    systemPrompt = provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt },
                    userPrompt = provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt },
                    continuationType = provider.continuationType,
                    prefillContent = if (provider.continuationType != com.moe.moetranslator.me.OpenAIProviderConfig.CONTINUATION_NONE) "[1] " else ""
                )
            }
            Constants.TextApi.VOLC.id -> {
                val account = KeystoreManager.retrieveKey(this, "Volc_ACCOUNT") ?: return null
                val secret = KeystoreManager.retrieveKey(this, "Volc_SECRETKEY") ?: return null
                translationapi.volctranslation.VolcTranslation(account, secret)
            }
            Constants.TextApi.AZURE.id -> {
                val key = KeystoreManager.retrieveKey(this, "Azure") ?: return null
                translationapi.azuretranslation.AzureTranslation(key)
            }
            Constants.TextApi.DEEPL.id -> {
                val host = KeystoreManager.retrieveKey(this, "DeepL_Translate_HOST") ?: return null
                val key = KeystoreManager.retrieveKey(this, "DeepL_Translate_APIKEY") ?: return null
                translationapi.deepltranslation.DeepLTranslation(host, key)
            }
            Constants.TextApi.BAIDU.id -> {
                val account = KeystoreManager.retrieveKey(this, "Baidu_Translate_ACCOUNT") ?: return null
                val secret = KeystoreManager.retrieveKey(this, "Baidu_Translate_SECRETKEY") ?: return null
                translationapi.baidutranslation.BaiduTranslationText(account, secret)
            }
            Constants.TextApi.TENCENT.id -> {
                val account = KeystoreManager.retrieveKey(this, "Tencent_Cloud_ACCOUNT") ?: return null
                val secret = KeystoreManager.retrieveKey(this, "Tencent_Cloud_SECRETKEY") ?: return null
                translationapi.tencentcloud.TencentTranslationText(account, secret)
            }
            Constants.TextApi.CUSTOM_TEXT.id -> {
                val textConfig = ConfigurationStorage.loadTextConfig(prefs, prefs.getInt("Custom_Text_API", 0))
                if (textConfig != null) translationapi.customtranslation.CustomTranslationText(textConfig) else null
            }
            else -> null
        }
    }

    /**
     * 将引擎名称字符串映射为 DetEngine 和 OcrEngine 枚举。
     */
    private fun mapEngineToDetOcr(engineName: String): Pair<DetEngine, OcrEngine> {
        return when (engineName) {
            "PP_OCR_V5" -> DetEngine.PP_OCR_V5 to OcrEngine.PPOcrV5
            "MANGA_OCR" -> DetEngine.PP_OCR_V5 to OcrEngine.MangaOcr
            "MLKIT" -> DetEngine.MLKIT to OcrEngine.MLKit
            else -> DetEngine.PP_OCR_V5 to OcrEngine.PPOcrV5
        }
    }

    /**
     * 初始化检测和识别所需的引擎。
     */
    private suspend fun initializeEngines(det: DetEngine, ocr: OcrEngine) {
        // Det 引擎
        when (det) {
            DetEngine.PP_OCR_V5 -> PPOcrV5Engine.initialize(this@MangaViewerActivity)
            DetEngine.RT_DETR_V2 -> ComicBubbleDetector.initialize(this@MangaViewerActivity)
            DetEngine.CTD -> CTDDetector.initialize(this@MangaViewerActivity)
            DetEngine.MLKIT -> {} // 无需初始化
        }
        // Ocr 引擎
        when (ocr) {
            OcrEngine.PPOcrV5 -> PPOcrV5Engine.initialize(this@MangaViewerActivity)
            OcrEngine.MangaOcr -> MangaOcrRecognizer.initialize(this@MangaViewerActivity)
            OcrEngine.MLKit -> {} // 无需初始化
        }
    }

    private fun buildRetranslateName(translator: TranslationTextAPI, det: DetEngine, ocr: OcrEngine, prefs: CustomPreference): String {
        val model = translator.modelName ?: ""
        val apiStr = if (model.isNotEmpty()) model else translator.javaClass.simpleName
        val detStr = when (det) {
            DetEngine.CTD -> "CTD"; DetEngine.MLKIT -> "MLKit"
            DetEngine.RT_DETR_V2 -> "RT-DETR"; DetEngine.PP_OCR_V5 -> "PP-OCRv5"
        }
        val ocrStr = when (ocr) {
            OcrEngine.MLKit -> "MLKit"; OcrEngine.MangaOcr -> "manga-ocr"; OcrEngine.PPOcrV5 -> "PP-OCRv5"
        }
        val parts = mutableListOf(apiStr, "$detStr+$ocrStr")
        if (det == DetEngine.PP_OCR_V5 || ocr == OcrEngine.PPOcrV5) {
            val box = prefs.getFloat("ppocr_det_box_thresh", 0.3f)
            val unclip = prefs.getFloat("ppocr_det_unclip_ratio", 1.6f)
            val score = prefs.getFloat("ppocr_text_score_thresh", 0.5f)
            parts.add("box=%.2f unclip=%.1f score=%.2f".format(box, unclip, score))
        }
        return parts.joinToString(" | ")
    }

    private fun getImageDimensions(path: String?): String {
        if (path == null) return "?"
        return try {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, options)
            "${options.outWidth}×${options.outHeight}"
        } catch (e: Exception) { "?" }
    }

    private fun updatePageIndicator(position: Int) {
        binding.tvPageIndicator.text = "${position + 1}/${pageGroups.size}"
    }

    /**
     * 获取当前页组的活跃变体（用户选中的尺寸，或默认代表条目）。
     */
    private fun getCurrentVariant(): HistoryEntry {
        val position = binding.viewPager.currentItem
        val group = pageGroups.getOrNull(position) ?: return pageGroups.first().representative
        // 如果有活跃变体 ID，用它；否则用代表条目
        val activeId = activeVariantIds[position]
        return if (activeId != null) {
            group.variants.find { it.id == activeId } ?: group.representative
        } else {
            group.representative
        }
    }

    // 每页当前活跃的变体 ID（尺寸按钮选中的）
    private val activeVariantIds = mutableMapOf<Int, Long>()

    private fun togglePanel() {
        if (isPanelExpanded) {
            collapsePanel()
        } else {
            expandPanel()
        }
    }

    private fun expandPanel() {
        val position = binding.viewPager.currentItem
        if (position < 0 || position >= pageGroups.size) {
            LogCollector.w(TAG, "expandPanel: invalid position=$position, size=${pageGroups.size}")
            return
        }

        val entry = getCurrentVariant()
        LogCollector.d(TAG, "expandPanel: entryId=${entry.id}, sourceText=${entry.sourceText?.take(30)}, translatedText=${entry.translatedText?.take(30)}")

        // 翻译信息栏：完整参数、尺寸、语言、时间
        val dimStr = getImageDimensions(entry.imagePath ?: entry.thumbnailPath)
        val timeStr = dateFormat.format(Date(entry.updatedAt))

        // 完整参数信息（translatorName 包含所有参数）
        val fullInfo = entry.translatorName
        val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val createdStr = fullDateFormat.format(Date(entry.createdAt))
        val updatedStr = fullDateFormat.format(Date(entry.updatedAt))
        val phashStr = if (entry.pHash != 0L) "pHash:${String.format("%08X", entry.pHash and 0xFFFFFFFFL)}" else ""
        binding.tvTranslationInfo.text = "$fullInfo\n尺寸: $dimStr  |  ${entry.sourceLang} → ${entry.targetLang}  |  $timeStr\n创建: $createdStr\n修改: $updatedStr\n$phashStr"

        val detailList = buildDetailList(entry)
        LogCollector.d(TAG, "expandPanel: detailList size=${detailList.size}")

        val adapter = TranslationDetailAdapter(detailList)
        binding.rvTranslationDetail.layoutManager = LinearLayoutManager(this)
        binding.rvTranslationDetail.adapter = adapter

        // 等布局完成后获取实际高度
        binding.bottomSheetPanel.post {
            val panelHeight = binding.bottomSheetPanel.height.toFloat()
            binding.bottomSheetPanel.translationY = panelHeight
            binding.bottomSheetPanel.animate()
                .translationY(0f)
                .setDuration(250)
                .start()
        }

        isPanelExpanded = true
    }

    private fun collapsePanel() {
        val panelHeight = binding.bottomSheetPanel.height.toFloat()
        binding.bottomSheetPanel.animate()
            .translationY(panelHeight)
            .setDuration(250)
            .start()

        isPanelExpanded = false
    }

    private fun showDarkDialog(
        message: String,
        title: String? = null,
        positiveText: String = "确定",
        negativeText: String? = null,
        onPositive: () -> Unit = {},
        onNegative: (() -> Unit)? = null
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_dark, null)
        val tvTitle = view.findViewById<TextView>(R.id.dialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.dialogMessage)
        val btnPositive = view.findViewById<TextView>(R.id.dialogBtnPositive)
        val btnNegative = view.findViewById<TextView>(R.id.dialogBtnNegative)

        tvMessage.text = message
        btnPositive.text = positiveText

        if (title != null) {
            tvTitle.visibility = View.VISIBLE
            tvTitle.text = title
        }
        if (negativeText != null) {
            btnNegative.visibility = View.VISIBLE
            btnNegative.text = negativeText
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnPositive.setOnClickListener { onPositive(); dialog.dismiss() }
        if (negativeText != null) {
            btnNegative.setOnClickListener { onNegative?.invoke(); dialog.dismiss() }
        }

        dialog.show()
    }

    private fun confirmDeleteCurrentEntry() {
        val entry = getCurrentVariant()
        showDarkDialog(
            message = getString(R.string.delete_history_confirm),
            positiveText = getString(R.string.delete),
            negativeText = getString(android.R.string.cancel),
            onPositive = {
                lifecycleScope.launch {
                    try {
                        cacheManager.deleteHistory(entry.id)
                        // 从当前组中移除该变体
                        val position = binding.viewPager.currentItem
                        val group = pageGroups.getOrNull(position)
                        if (group != null) {
                            group.variants.removeIf { it.id == entry.id }
                            if (group.variants.isEmpty()) {
                                // 组已空，移除整页
                                pageGroups.removeAt(position)
                                if (pageGroups.isEmpty()) {
                                    com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, getString(R.string.history_deleted))
                                    finish()
                                    return@launch
                                }
                                binding.viewPager.adapter?.notifyItemRemoved(position)
                                updatePageIndicator(binding.viewPager.currentItem.coerceAtMost(pageGroups.size - 1))
                            } else {
                                // 还有其他变体，切换到第一个
                                group.representative = group.variants.first()
                                activeVariantIds[position] = group.representative.id
                                binding.viewPager.adapter?.notifyItemChanged(position)
                                updateVariantSpinner(position)
                            }
                        }
                        com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, getString(R.string.history_deleted))
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "删除失败", e)
                    }
                }
            }
        )
    }

    private fun buildDetailList(entry: HistoryEntry): List<TranslationDetailItem> {
        val items = mutableListOf<TranslationDetailItem>()

        val ocrText = entry.sourceText
        val aiText = entry.translatedText

        when {
            !ocrText.isNullOrEmpty() && !aiText.isNullOrEmpty() -> {
                // 解析编号格式的译文
                val ocrLines = parseNumberedText(ocrText)
                val aiLines = parseNumberedText(aiText)

                val maxCount = maxOf(ocrLines.size, aiLines.size)
                for (i in 0 until maxCount) {
                    items.add(
                        TranslationDetailItem(
                            index = i + 1,
                            ocrText = ocrLines.getOrNull(i) ?: "",
                            translatedText = aiLines.getOrNull(i) ?: ""
                        )
                    )
                }
            }
            !aiText.isNullOrEmpty() -> {
                items.add(TranslationDetailItem(index = 1, ocrText = "", translatedText = aiText))
            }
            !ocrText.isNullOrEmpty() -> {
                items.add(TranslationDetailItem(index = 1, ocrText = ocrText, translatedText = ""))
            }
            else -> {
                items.add(TranslationDetailItem(index = 1, ocrText = getString(R.string.no_translation_data), translatedText = ""))
            }
        }

        return items
    }

    private fun parseNumberedText(text: String): List<String> {
        // 解析 [1] text 格式
        val regex = Regex("""\[(\d+)]\s*""")
        val parts = regex.split(text).filter { it.isNotBlank() }
        return parts.map { it.trim() }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.viewPager.adapter = null
    }
}

// ========== 数据类和适配器 ==========

data class TranslationDetailItem(
    val index: Int,
    val ocrText: String,
    val translatedText: String
)

class TranslationDetailAdapter(
    private val items: List<TranslationDetailItem>
) : RecyclerView.Adapter<TranslationDetailAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvIndex)
        val tvOcrText: TextView = view.findViewById(R.id.tvOcrText)
        val tvTranslatedText: TextView = view.findViewById(R.id.tvTranslatedText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_translation_detail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvIndex.text = "[${item.index}]"
        holder.tvOcrText.text = if (item.ocrText.isNotEmpty()) item.ocrText else "-"
        holder.tvTranslatedText.text = item.translatedText
    }

    override fun getItemCount() = items.size
}

/**
 * 每页显示一个 pHash 组的代表图片。
 * 支持 switchCurrentImage 切换当前页的尺寸变体。
 */
class PageGroupAdapter(
    private val pageGroups: List<MangaViewerActivity.PageGroup>
) : RecyclerView.Adapter<PageGroupAdapter.ViewHolder>() {

    // 当前每页显示的变体（position → entryId），null 表示用代表条目
    private val activeVariants = mutableMapOf<Int, Long>()
    private var overrideBitmap: Bitmap? = null

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ZoomableImageView = view.findViewById(R.id.ivFullImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manga_viewer_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = pageGroups[position]
        val activeId = activeVariants[position]
        val entry = if (activeId != null) {
            group.variants.find { it.id == activeId } ?: group.representative
        } else {
            group.representative
        }
        loadImage(holder, entry)
    }

    override fun getItemCount() = pageGroups.size

    /**
     * 切换当前 ViewPager 页面的图片（不 notify，直接操作当前 ViewHolder）。
     */
    fun updateCurrentImage(entry: HistoryEntry) {
        // 通过 ViewPager2 找到当前 ViewHolder
        // ViewPager2 内部用 RecyclerView，但没有直接 API 获取当前 holder
        // 用 notifyItemChanged 触发重新绑定
        val position = activeVariants.entries.find { it.value == entry.id }?.key ?: return
        notifyItemChanged(position)
    }

    /**
     * 记录某页的活跃变体。
     */
    fun setActiveVariant(position: Int, entryId: Long) {
        activeVariants[position] = entryId
    }

    fun setOverrideImage(bitmap: Bitmap?) {
        overrideBitmap = bitmap
    }

    private fun loadImage(holder: ViewHolder, entry: HistoryEntry) {
        if (overrideBitmap != null) {
            holder.imageView.resetZoom()
            holder.imageView.setImageBitmap(overrideBitmap)
            return
        }
        val path = entry.imagePath ?: entry.thumbnailPath
        val isThumbnail = entry.imagePath == null
        if (path != null && java.io.File(path).exists()) {
            val bitmap = BitmapFactory.decodeFile(path)
            LogCollector.d("MangaViewer", "加载图片: ${bitmap.width}x${bitmap.height}, isThumbnail=$isThumbnail, fileSize=${java.io.File(path).length() / 1024}KB")
            holder.imageView.resetZoom()
            holder.imageView.setImageBitmap(bitmap)
        } else {
            holder.imageView.resetZoom()
            holder.imageView.setImageBitmap(null)
        }
    }
}
