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
import com.moe.moetranslator.data.HistoryEntry
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.databinding.ActivityMangaViewerBinding
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.PerceptualHash
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MangaViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MangaViewerActivity"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_ENTRY_IDS = "entry_ids"  // 同 pHash 多尺寸条目
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

        // 重新翻译按钮 — 直接发广播，不弹裁剪界面
        binding.btnRetranslate.setOnClickListener {
            val entry = getCurrentVariant()
            if (!com.moe.moetranslator.utils.ServiceUtils.isServiceRunning(
                    this, com.moe.moetranslator.manga.MangaFloatingService::class.java
                )) {
                com.moe.moetranslator.utils.UiUtils.showToast(this,"请先启动漫画翻译")
                return@setOnClickListener
            }

            val originalPath = entry.originalImagePath
            if (originalPath.isNullOrEmpty() || !java.io.File(originalPath).exists()) {
                com.moe.moetranslator.utils.UiUtils.showToast(this,"原图不可用")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val cache = cacheManager.getCacheByHistoryId(entry.id)
                if (cache == null || cache.cropRight <= 0) {
                    com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "无裁剪信息")
                    return@launch
                }
                com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, "已发送重翻请求")
                sendRetranslateRequest(originalPath, entry.id, entry.pHash,
                    cache.cropLeft, cache.cropTop, cache.cropRight, cache.cropBottom)
            }
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
     * 发送重新翻译请求（广播），并注册完成接收器。
     */
    private fun sendRetranslateRequest(imagePath: String, historyIdToDelete: Long, existingPHash: Long,
                                       cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int) {
        val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(this)
        val intent = android.content.Intent("com.moe.moetranslator.RETRANSLATE_REQUEST").apply {
            putExtra("originalImagePath", imagePath)
            putExtra("historyIdToDelete", historyIdToDelete)
            putExtra("existingPHash", existingPHash)
            putExtra("cropLeft", cropLeft)
            putExtra("cropTop", cropTop)
            putExtra("cropRight", cropRight)
            putExtra("cropBottom", cropBottom)
            putExtra("ocrEngine", prefs.getString("history_retranslate_engine", "PP_OCR_V5"))
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this@MangaViewerActivity)
                    .unregisterReceiver(this)
                val success = intent?.getBooleanExtra("success", false) ?: false
                val errorMessage = intent?.getStringExtra("errorMessage")
                runOnUiThread {
                    val msg = if (success) "重新翻译完成" else (errorMessage ?: "重新翻译失败")
                    com.moe.moetranslator.utils.UiUtils.showToast(this@MangaViewerActivity, msg)
                    if (success) {
                        lifecycleScope.launch { loadData(savedClickedEntryId, savedEntryIds) }
                    }
                }
            }
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(receiver, android.content.IntentFilter("com.moe.moetranslator.RETRANSLATE_COMPLETE"))
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
        binding.tvTranslationInfo.text = "$fullInfo\n尺寸: $dimStr  |  ${entry.sourceLang} → ${entry.targetLang}  |  $timeStr"

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
