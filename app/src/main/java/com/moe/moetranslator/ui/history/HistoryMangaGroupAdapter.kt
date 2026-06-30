package com.moe.moetranslator.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moe.moetranslator.R
import com.moe.moetranslator.data.HistoryEntry
import com.moe.moetranslator.data.HistoryGroup
import com.moe.moetranslator.data.HistorySession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * pHash 分组后的条目：代表条目 + 组内所有条目。
 */
data class GroupedHistoryEntry(
    val representative: HistoryEntry,  // 显示的代表条目（最新）
    val groupSize: Int,                // 组内条目数
    val allEntryIds: List<Long>        // 组内所有条目 ID
)

/**
 * 漫画历史分组适配器
 * 日期标题 + 会话子标题 + 缩略图网格
 */
class HistoryMangaGroupAdapter(
    private val onItemClick: (GroupedHistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit,
    private var displayMode: String = "large",
    private var sortByUpdated: Boolean = false,
    var isManageView: Boolean = false,
    private val onRetranslateClick: ((HistoryEntry) -> Unit)? = null,
    private val onDeleteVariantClick: ((HistoryEntry) -> Unit)? = null,
    var retranslateCountMap: Map<Long, Int> = emptyMap(),
    private val onSwitchVariant: ((HistoryEntry, Int) -> Unit)? = null,
    private val onDownloadSessionClick: ((HistorySession) -> Unit)? = null
) : ListAdapter<HistoryGroup, HistoryMangaGroupAdapter.GroupViewHolder>(GroupDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setDisplayMode(mode: String) {
        if (displayMode != mode) {
            displayMode = mode
            notifyDataSetChanged()
        }
    }

    fun setSortByUpdated(sortByUpdated: Boolean) {
        if (this.sortByUpdated != sortByUpdated) {
            this.sortByUpdated = sortByUpdated
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_manga_date_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvDateHeader: TextView = view.findViewById(R.id.tvMangaDateHeader)
        private val rvSessions: RecyclerView = view.findViewById(R.id.rvMangaSessions)

        fun bind(group: HistoryGroup) {
            tvDateHeader.text = group.dateLabel

            // 构建颜色映射（多尺寸分组）
            val allEntries = group.sessions.flatMap { it.entries }
            val colorMap = mutableMapOf<Long, Int>()
            var colorIdx = 1
            for (entry in allEntries) {
                if (entry.variantCount > 1) {
                    for (id in entry.variantIds) {
                        colorMap[id] = colorIdx
                    }
                    colorIdx = (colorIdx % 6) + 1
                }
            }

            val sessionAdapter = MangaSessionAdapter(
                onItemClick = { grouped ->
                    val representative = grouped.representative
                    val allIds = if (representative.variantIds.isNotEmpty()) representative.variantIds else listOf(representative.id)
                    onItemClick(GroupedHistoryEntry(representative, allIds.size, allIds))
                },
                onItemLongClick = onItemLongClick,
                colorMap = colorMap,
                retranslateCountMap = retranslateCountMap,
                onSwitchVariant = onSwitchVariant,
                onDownloadSessionClick = onDownloadSessionClick
            )
            rvSessions.layoutManager = LinearLayoutManager(itemView.context)
            rvSessions.adapter = sessionAdapter
            sessionAdapter.submitList(group.sessions)
        }
    }

    private inner class MangaSessionAdapter(
        private val onItemClick: (GroupedHistoryEntry) -> Unit,
        private val onItemLongClick: (HistoryEntry) -> Unit,
        private val colorMap: Map<Long, Int> = emptyMap(),
        private val retranslateCountMap: Map<Long, Int> = emptyMap(),
        private val onSwitchVariant: ((HistoryEntry, Int) -> Unit)? = null,
        private val onDownloadSessionClick: ((HistorySession) -> Unit)? = null
    ) : ListAdapter<HistorySession, MangaSessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_manga_session, parent, false)
            return SessionViewHolder(view)
        }

        override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvSessionHeader: TextView = view.findViewById(R.id.tvMangaSessionHeader)
            private val rvGrid: RecyclerView = view.findViewById(R.id.rvMangaSessionGrid)
            private val btnDownloadSession: ImageButton = view.findViewById(R.id.btnDownloadSession)

            fun bind(session: HistorySession) {
                val startTime = timeFormat.format(Date(session.startTime))
                val endTime = timeFormat.format(Date(session.endTime))

                // 每个条目直接作为 GroupedHistoryEntry（pHash 去重已在 getHistoryGrouped 完成）
                val groups = session.entries.map { entry ->
                    val ids = if (entry.variantIds.isNotEmpty()) entry.variantIds else listOf(entry.id)
                    GroupedHistoryEntry(
                        representative = entry,
                        groupSize = entry.variantCount,
                        allEntryIds = ids
                    )
                }

                tvSessionHeader.text = "$startTime - $endTime (${groups.size})"

                val gridAdapter = HistoryMangaAdapter(
                    onItemClick, onItemLongClick, colorMap, displayMode, sortByUpdated,
                    isManageView = isManageView,
                    onRetranslateClick = onRetranslateClick,
                    onDeleteVariantClick = onDeleteVariantClick,
                    retranslateCountMap = retranslateCountMap,
                    onSwitchVariant = onSwitchVariant
                )
                val spanCount = when (displayMode) {
                    "list" -> 1
                    "large" -> 2
                    "medium" -> 3
                    "small" -> 4
                    else -> 2
                }
                rvGrid.layoutManager = GridLayoutManager(itemView.context, spanCount)
                rvGrid.adapter = gridAdapter
                gridAdapter.submitList(groups)

                // 下载按钮
                btnDownloadSession.visibility = if (groups.isNotEmpty()) View.VISIBLE else View.GONE
                btnDownloadSession.setOnClickListener {
                    onDownloadSessionClick?.invoke(session)
                }
            }
        }
    }

    class GroupDiffCallback : DiffUtil.ItemCallback<HistoryGroup>() {
        override fun areItemsTheSame(oldItem: HistoryGroup, newItem: HistoryGroup): Boolean {
            return oldItem.dateLabel == newItem.dateLabel
        }
        override fun areContentsTheSame(oldItem: HistoryGroup, newItem: HistoryGroup): Boolean {
            return oldItem == newItem
        }
    }

    class SessionDiffCallback : DiffUtil.ItemCallback<HistorySession>() {
        override fun areItemsTheSame(oldItem: HistorySession, newItem: HistorySession): Boolean {
            return oldItem.sessionId == newItem.sessionId
        }
        override fun areContentsTheSame(oldItem: HistorySession, newItem: HistorySession): Boolean {
            return oldItem == newItem
        }
    }

}
