package com.moe.moetranslator.ui.history

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        // 删除当前条目
        binding.btnDeleteEntry.setOnClickListener {
            confirmDeleteCurrentEntry()
        }

        // ViewPager 翻页监听
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
                // 翻页时关闭面板
                if (isPanelExpanded) {
                    collapsePanel()
                }
                // 更新尺寸切换按钮（根据新页组的变体数）
                updateSizeSwitcherVisibility(position)
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
                    Toast.makeText(this@MangaViewerActivity, R.string.no_translation_data, Toast.LENGTH_SHORT).show()
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
                updateSizeSwitcherVisibility(safeIndex)

                LogCollector.d(TAG, "加载漫画历史, ${pageGroups.size} 页, 跳转到 #$safeIndex")
            } catch (e: Exception) {
                LogCollector.e(TAG, "加载数据失败", e)
                Toast.makeText(this@MangaViewerActivity, R.string.no_translation_data, Toast.LENGTH_SHORT).show()
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
     * 翻页时更新尺寸切换按钮。
     * 当前页组有多个变体时显示按钮，否则隐藏。
     */
    private fun updateSizeSwitcherVisibility(position: Int) {
        val group = pageGroups.getOrNull(position)
        if (group == null || group.variants.size <= 1) {
            binding.sizeSwitcher.visibility = android.view.View.GONE
            binding.sizeSwitcher.removeAllViews()
        } else {
            showSizeSwitcher(group)
        }
    }

    /**
     * 显示尺寸切换按钮（半透明小按钮，在"查看译文"上方）。
     */
    private fun showSizeSwitcher(group: PageGroup) {
        if (group.variants.size <= 1) return

        val container = binding.sizeSwitcher
        container.removeAllViews()

        val position = binding.viewPager.currentItem
        val activeId = activeVariantIds[position] ?: group.representative.id

        for ((idx, variant) in group.variants.withIndex()) {
            val dimStr = getImageDimensions(variant.imagePath ?: variant.thumbnailPath)

            val btn = android.widget.TextView(this).apply {
                text = dimStr
                textSize = 10f
                setTextColor(if (variant.id == activeId) android.graphics.Color.parseColor("#FF9800") else android.graphics.Color.WHITE)
                setPadding(12, 4, 12, 4)
                tag = variant.id
                setOnClickListener {
                    switchVariant(it.tag as Long)
                }
            }
            container.addView(btn)

            // 分隔线
            if (idx < group.variants.size - 1) {
                val divider = android.view.View(this).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
                }
                val divParams = android.widget.LinearLayout.LayoutParams(1, android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
                container.addView(divider, divParams)
            }
        }

        container.visibility = android.view.View.VISIBLE
    }

    /**
     * 切换当前页的尺寸变体（不翻页，只换图片和译文）。
     */
    private fun switchVariant(variantId: Long) {
        val position = binding.viewPager.currentItem
        val group = pageGroups.getOrNull(position) ?: return
        val variant = group.variants.find { it.id == variantId } ?: return

        // 记录活跃变体
        activeVariantIds[position] = variantId

        // 更新 adapter 的活跃变体并刷新当前页
        val adapter = binding.viewPager.adapter as? PageGroupAdapter ?: return
        adapter.setActiveVariant(position, variantId)
        adapter.notifyItemChanged(position)

        // 更新按钮高亮
        showSizeSwitcher(group)

        // 如果面板展开，更新译文
        if (isPanelExpanded) {
            expandPanel()
        }

        LogCollector.d(TAG, "switchVariant: entryId=$variantId, size=${variant.imagePath?.let { getImageDimensions(it) }}")
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

    private fun confirmDeleteCurrentEntry() {
        val entry = getCurrentVariant()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_history_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
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
                                    Toast.makeText(this@MangaViewerActivity, R.string.history_deleted, Toast.LENGTH_SHORT).show()
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
                                updateSizeSwitcherVisibility(position)
                            }
                        }
                        Toast.makeText(this@MangaViewerActivity, R.string.history_deleted, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "删除失败", e)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun loadImage(holder: ViewHolder, entry: HistoryEntry) {
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
