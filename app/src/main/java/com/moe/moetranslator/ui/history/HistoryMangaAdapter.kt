package com.moe.moetranslator.ui.history

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
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
    private val onDeleteVariantClick: ((HistoryEntry) -> Unit)? = null
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
        if (isManageView) return 2
        return if (displayMode == "list") 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutId = when (viewType) {
            2 -> R.layout.item_history_manga_manage
            1 -> R.layout.item_history_manga_list
            else -> R.layout.item_history_manga
        }
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
        private val tvSizeBadge: TextView = view.findViewById(R.id.tv_size_badge)
        private val btnToggleImage: TextView = view.findViewById(R.id.btnToggleImage)
        // 管理视图特有控件
        private val tvRetranslateBadge: TextView? = view.findViewById(R.id.tvRetranslateBadge)
        private val spinnerVariant: Spinner? = view.findViewById(R.id.spinnerVariant)
        private val btnRetranslate: TextView? = view.findViewById(R.id.btnRetranslate)
        private val btnDeleteVariant: TextView? = view.findViewById(R.id.btnDeleteVariant)
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
                btnToggleImage.text = "📷"
                btnToggleImage.setOnClickListener {
                    showingOriginal = !showingOriginal
                    btnToggleImage.text = if (showingOriginal) "📄" else "📷"
                    val path = if (showingOriginal) entry.originalImagePath else (entry.imagePath ?: entry.thumbnailPath)
                    if (path != null && File(path).exists()) {
                        val bmp = BitmapFactory.decodeFile(path)
                        ivThumbnail.setImageBitmap(bmp)
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

            // 尺寸数量徽章
            if (grouped.groupSize > 1) {
                tvSizeBadge.text = "×${grouped.groupSize}"
                tvSizeBadge.visibility = View.VISIBLE
            } else {
                tvSizeBadge.visibility = View.GONE
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

            // 管理视图特有逻辑
            if (isManageView) {
                bindManageViews(grouped, entry)
            }

            itemView.setOnClickListener { onItemClick(grouped) }
            itemView.setOnLongClickListener {
                onItemLongClick(entry)
                true
            }
        }

        private fun bindManageViews(grouped: GroupedHistoryEntry, entry: HistoryEntry) {
            // 重新翻译计数徽章
            // 统计组内所有变体中 isRetranslated=true 的数量
            val retranslateCount = if (!entry.variantIds.isNullOrEmpty()) {
                entry.variantIds.count { it > 0 }  // placeholder: 实际逻辑在 Task 11
                0
            } else {
                0
            }
            tvRetranslateBadge?.apply {
                if (retranslateCount > 0) {
                    text = "🔄×$retranslateCount"
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }

            // 尺寸选择 Spinner
            spinnerVariant?.apply {
                if (!entry.variantIds.isNullOrEmpty() && entry.variantIds.size > 1) {
                    val variantLabels = entry.variantIds.mapIndexed { idx, _ -> "尺寸 ${idx + 1}" }
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, variantLabels)
                    visibility = View.VISIBLE
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            // Task 11: 切换变体显示
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                } else {
                    visibility = View.GONE
                }
            }

            // 重新翻译按钮
            btnRetranslate?.setOnClickListener {
                onRetranslateClick?.invoke(entry)
            }

            // 删除此尺寸按钮
            btnDeleteVariant?.setOnClickListener {
                onDeleteVariantClick?.invoke(entry)
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
