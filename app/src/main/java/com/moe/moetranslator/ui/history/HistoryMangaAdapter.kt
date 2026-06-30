package com.moe.moetranslator.ui.history

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.moe.moetranslator.R
import com.moe.moetranslator.data.HistoryEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 漫画历史条目适配器。
 * 支持多种显示模式：list / large / medium / small
 * 支持管理视图模式：isManageView=true 时使用管理布局
 * @param colorMap entryId → 颜色组号（用于跨 session 的 pHash 分组着色）
 * @param displayMode 显示模式
 * @param isManageView 是否管理视图模式
 * @param onRetranslateClick 重新翻译回调
 * @param onDeleteVariantClick 删除尺寸回调
 */
class HistoryMangaAdapter(
    private val onItemClick: (GroupedHistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit,
    private val colorMap: Map<Long, Int> = emptyMap(),
    private val displayMode: String = "large",
    private val sortByUpdated: Boolean = false,
    private val isManageView: Boolean = false,
    private val onRetranslateClick: ((HistoryEntry) -> Unit)? = null,
    private val onDeleteVariantClick: ((HistoryEntry) -> Unit)? = null,
    private val retranslateCountMap: Map<Long, Int> = emptyMap(),
    private val onSwitchVariant: ((HistoryEntry, Int) -> Unit)? = null  // entry, selectedVariantIndex
) : ListAdapter<GroupedHistoryEntry, RecyclerView.ViewHolder>(DiffCallback()) {

    private val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val shortDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val isSmall get() = displayMode == "small"

    companion object {
        private val GROUP_COLORS = intArrayOf(
            android.graphics.Color.parseColor("#FFFFFF"),    // 默认白色
            android.graphics.Color.parseColor("#15FF6B6B"),  // 红
            android.graphics.Color.parseColor("#154FC3F7"),  // 蓝
            android.graphics.Color.parseColor("#1581C784"),  // 绿
            android.graphics.Color.parseColor("#15FFD54F"),  // 黄
            android.graphics.Color.parseColor("#15CE93D8"),  // 紫
            android.graphics.Color.parseColor("#15FFAB91"),  // 橙
        )

        private val TRANSLATOR_DISPLAY_NAMES = mapOf(
            "OpenAITranslation" to "OpenAI",
            "BingTranslation" to "Bing",
            "GoogleMLKitTranslation" to "ML Kit",
            "NLLBTranslation" to "NLLB",
            "NiuTransTranslation" to "小牛",
            "VolcTranslation" to "火山",
            "DeepLTranslation" to "DeepL",
            "BaiduTranslation" to "百度",
            "TencentCloudTranslation" to "腾讯",
            "AzureTranslation" to "Azure",
        )

        fun getDisplayName(translatorName: String): String {
            val apiPart = translatorName.split(" | ").first().trim()
            val match = Regex("^(\\w+?)\\((.+)\\)$").find(apiPart)
            if (match != null) {
                val apiClass = match.groupValues[1]
                val model = match.groupValues[2]
                val friendlyName = TRANSLATOR_DISPLAY_NAMES[apiClass] ?: apiClass
                return "$friendlyName($model)"
            }
            return TRANSLATOR_DISPLAY_NAMES[apiPart] ?: apiPart
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (displayMode == "list") 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutId = if (viewType == 1) R.layout.item_history_manga_list else R.layout.item_history_manga
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as ViewHolder).bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivThumbnail: ImageView = view.findViewById(R.id.iv_thumbnail)
        private val tvPhash: TextView = view.findViewById(R.id.tv_phash)
        private val tvTranslatorName: TextView = view.findViewById(R.id.tv_translator_name)
        private val tvTime: TextView = view.findViewById(R.id.tv_time)
        private val badgeContainer: View = view.findViewById(R.id.badgeContainer)
        private val tvSizeBadge: TextView = view.findViewById(R.id.tv_size_badge)
        private val tvRetranslateBadge: TextView = view.findViewById(R.id.tvRetranslateBadge)
        private val btnToggleImage: TextView = view.findViewById(R.id.btnToggleImage)
        private var showingOriginal = false

        fun bind(grouped: GroupedHistoryEntry) {
            val entry = grouped.representative
            showingOriginal = false

            // 加载缩略图
            if (entry.thumbnailPath != null && File(entry.thumbnailPath).exists()) {
                val bitmap = BitmapFactory.decodeFile(entry.thumbnailPath)
                ivThumbnail.setImageBitmap(bitmap)
            } else {
                ivThumbnail.setImageBitmap(null)
            }

            // 原图/译文切换
            if (!entry.originalImagePath.isNullOrEmpty()) {
                btnToggleImage.visibility = View.VISIBLE
                btnToggleImage.text = itemView.context.getString(R.string.history_original_abbr)
                btnToggleImage.setOnClickListener {
                    showingOriginal = !showingOriginal
                    btnToggleImage.text = if (showingOriginal) itemView.context.getString(R.string.history_translated_abbr) else itemView.context.getString(R.string.history_original_abbr)
                    val path = if (showingOriginal) entry.originalImagePath else (entry.imagePath ?: entry.thumbnailPath)
                    if (path != null && File(path).exists()) {
                        // P1 #7: 后台线程解码避免阻塞主线程
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        Thread {
                            val bmp = BitmapFactory.decodeFile(path)
                            mainHandler.post { ivThumbnail.setImageBitmap(bmp) }
                        }.start()
                    }
                }
            } else {
                btnToggleImage.visibility = View.GONE
                btnToggleImage.setOnClickListener(null)
            }

            // pHash 显示
            if (entry.pHash != 0L) {
                tvPhash.text = if (isSmall) String.format("%08X", entry.pHash and 0xFFFFFFFFL)
                               else "pHash:${String.format("%08X", entry.pHash and 0xFFFFFFFFL)}"
                tvPhash.visibility = View.VISIBLE
            } else {
                tvPhash.visibility = View.GONE
            }

            tvTranslatorName.text = getDisplayName(entry.translatorName)
            val displayTime = entry.updatedAt
            tvTime.text = if (isSmall) shortDateFormat.format(Date(displayTime))
                          else fullDateFormat.format(Date(displayTime))

            // 尺寸数量徽章 + 重新翻译徽章
            badgeContainer.visibility = View.GONE
            tvSizeBadge.visibility = View.GONE
            tvRetranslateBadge.visibility = View.GONE
            if (grouped.groupSize > 1) {
                tvSizeBadge.text = "×${grouped.groupSize}"
                tvSizeBadge.visibility = View.VISIBLE
                badgeContainer.visibility = View.VISIBLE
            }
            val retranslateCount = retranslateCountMap[entry.id] ?: 0
            if (retranslateCount > 0) {
                tvRetranslateBadge.text = "🔄×$retranslateCount"
                tvRetranslateBadge.visibility = View.VISIBLE
                badgeContainer.visibility = View.VISIBLE
            }

            // 分组颜色
            val colorIdx = colorMap[entry.id] ?: 0
            val bgColor = GROUP_COLORS.getOrElse(colorIdx) { GROUP_COLORS[0] }
            val card = itemView as? MaterialCardView
            if (card != null) {
                card.setCardBackgroundColor(bgColor)
                val strokeWidthPx = (1 * itemView.resources.displayMetrics.density).toInt()
                card.strokeWidth = strokeWidthPx
                card.strokeColor = android.graphics.Color.parseColor("#E0E0E0")
                card.setBackgroundTintList(null)
            }

            itemView.setOnClickListener { onItemClick(grouped) }
            itemView.setOnLongClickListener {
                onItemLongClick(entry)
                true
            }
        }

    }

    class DiffCallback : DiffUtil.ItemCallback<GroupedHistoryEntry>() {
        override fun areItemsTheSame(oldItem: GroupedHistoryEntry, newItem: GroupedHistoryEntry): Boolean {
            return oldItem.representative.id == newItem.representative.id
        }

        override fun areContentsTheSame(oldItem: GroupedHistoryEntry, newItem: GroupedHistoryEntry): Boolean {
            return oldItem == newItem
        }
    }
}
