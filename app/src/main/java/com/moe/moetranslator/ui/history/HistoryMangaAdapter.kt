package com.moe.moetranslator.ui.history

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moe.moetranslator.data.HistoryEntry
import com.moe.moetranslator.databinding.ItemHistoryMangaBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryMangaAdapter(
    private val onItemClick: (HistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit
) : ListAdapter<HistoryEntry, HistoryMangaAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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

        fun bind(entry: HistoryEntry) {
            // 加载缩略图
            if (entry.thumbnailPath != null && File(entry.thumbnailPath).exists()) {
                val bitmap = BitmapFactory.decodeFile(entry.thumbnailPath)
                binding.ivThumbnail.setImageBitmap(bitmap)
            } else {
                binding.ivThumbnail.setImageBitmap(null)
            }

            binding.tvTranslatorName.text = entry.translatorName
            binding.tvTime.text = dateFormat.format(Date(entry.createdAt))

            binding.root.setOnClickListener { onItemClick(entry) }
            binding.root.setOnLongClickListener {
                onItemLongClick(entry)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<HistoryEntry>() {
        override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean {
            return oldItem == newItem
        }
    }
}
