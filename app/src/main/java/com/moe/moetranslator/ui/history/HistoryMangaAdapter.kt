package com.moe.moetranslator.ui.history

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moe.moetranslator.data.HistoryEntry
import com.moe.moetranslator.databinding.ItemHistoryMangaBinding
import com.moe.moetranslator.ui.history.GroupedHistoryEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 漫画历史条目适配器。
 * 接收 GroupedHistoryEntry 列表，同 pHash 条目合并显示，右下角显示尺寸数量。
 * @param colorMap entryId → 颜色组号（用于跨 session 的 pHash 分组着色）
 */
class HistoryMangaAdapter(
    private val onItemClick: (GroupedHistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit,
    private val colorMap: Map<Long, Int> = emptyMap()
) : ListAdapter<GroupedHistoryEntry, HistoryMangaAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    companion object {
        private val GROUP_COLORS = intArrayOf(
            android.graphics.Color.parseColor("#00000000"),  // 默认
            android.graphics.Color.parseColor("#15FF6B6B"),  // 红
            android.graphics.Color.parseColor("#154FC3F7"),  // 蓝
            android.graphics.Color.parseColor("#1581C784"),  // 绿
            android.graphics.Color.parseColor("#15FFD54F"),  // 黄
            android.graphics.Color.parseColor("#15CE93D8"),  // 紫
            android.graphics.Color.parseColor("#15FFAB91"),  // 橙
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryMangaBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemHistoryMangaBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(grouped: GroupedHistoryEntry) {
            val entry = grouped.representative

            // 加载缩略图
            if (entry.thumbnailPath != null && File(entry.thumbnailPath).exists()) {
                val bitmap = BitmapFactory.decodeFile(entry.thumbnailPath)
                binding.ivThumbnail.setImageBitmap(bitmap)
            } else {
                binding.ivThumbnail.setImageBitmap(null)
            }

            // pHash 显示（最后 8 位十六进制）
            if (entry.pHash != 0L) {
                binding.tvPhash.text = "pHash:${String.format("%08X", entry.pHash and 0xFFFFFFFFL)}"
                binding.tvPhash.visibility = android.view.View.VISIBLE
            } else {
                binding.tvPhash.visibility = android.view.View.GONE
            }

            binding.tvTranslatorName.text = entry.translatorName
            binding.tvTime.text = dateFormat.format(Date(entry.createdAt))

            // 尺寸数量徽章
            if (grouped.groupSize > 1) {
                binding.tvSizeBadge.text = "×${grouped.groupSize}"
                binding.tvSizeBadge.visibility = android.view.View.VISIBLE
            } else {
                binding.tvSizeBadge.visibility = android.view.View.GONE
            }

            // 分组颜色
            val colorIdx = colorMap[entry.id] ?: 0
            val bgColor = GROUP_COLORS.getOrElse(colorIdx) { GROUP_COLORS[0] }
            (binding.root as com.google.android.material.card.MaterialCardView).setCardBackgroundColor(bgColor)

            binding.root.setOnClickListener { onItemClick(grouped) }
            binding.root.setOnLongClickListener {
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
