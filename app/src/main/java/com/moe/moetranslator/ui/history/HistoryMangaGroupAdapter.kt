package com.moe.moetranslator.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.moe.moetranslator.utils.PerceptualHash
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
    private val onItemLongClick: (HistoryEntry) -> Unit
) : ListAdapter<HistoryGroup, HistoryMangaGroupAdapter.GroupViewHolder>(GroupDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

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

            // 跨 session 收集所有条目，按 pHash 分组，用于计算每个条目的徽章
            val allEntries = group.sessions.flatMap { it.entries }
            val pHashGroups = groupByPHash(allEntries)

            // 构建 entryId → allEntryIds 映射（用于徽章显示）
            val entryToGroupIds = mutableMapOf<Long, List<Long>>()
            for (g in pHashGroups) {
                for (id in g.allEntryIds) {
                    entryToGroupIds[id] = g.allEntryIds
                }
            }

            // 为多尺寸分组分配颜色
            val colorMap = computeGroupColorMap(pHashGroups)

            val sessionAdapter = MangaSessionAdapter(
                onItemClick = { grouped ->
                    val allIds = entryToGroupIds[grouped.representative.id]
                        ?: grouped.allEntryIds
                    onItemClick(GroupedHistoryEntry(grouped.representative, allIds.size, allIds))
                },
                onItemLongClick = onItemLongClick,
                entryToGroupIds = entryToGroupIds,
                colorMap = colorMap
            )
            rvSessions.layoutManager = LinearLayoutManager(itemView.context)
            rvSessions.adapter = sessionAdapter
            sessionAdapter.submitList(group.sessions)
        }
    }

    private inner class MangaSessionAdapter(
        private val onItemClick: (GroupedHistoryEntry) -> Unit,
        private val onItemLongClick: (HistoryEntry) -> Unit,
        private val entryToGroupIds: Map<Long, List<Long>> = emptyMap(),
        private val colorMap: Map<Long, Int> = emptyMap()
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

            fun bind(session: HistorySession) {
                val startTime = timeFormat.format(Date(session.startTime))
                val endTime = timeFormat.format(Date(session.endTime))

                // 将每个条目包装为 GroupedHistoryEntry，按 allEntryIds 去重（同组只显示一张）
                val seenGroups = mutableSetOf<List<Long>>()
                val groups = mutableListOf<GroupedHistoryEntry>()
                for (entry in session.entries) {
                    val groupIds = entryToGroupIds[entry.id] ?: listOf(entry.id)
                    val key = groupIds.sorted()
                    if (seenGroups.add(key)) {
                        groups.add(GroupedHistoryEntry(
                            representative = entry,
                            groupSize = groupIds.size,
                            allEntryIds = groupIds
                        ))
                    }
                }

                tvSessionHeader.text = "$startTime - $endTime (${groups.size})"

                val gridAdapter = HistoryMangaAdapter(onItemClick, onItemLongClick, colorMap)
                rvGrid.layoutManager = GridLayoutManager(itemView.context, 2)
                rvGrid.adapter = gridAdapter
                gridAdapter.submitList(groups)
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

    companion object {
        private const val PHASH_GROUP_THRESHOLD = 0.85f

        /**
         * 按 pHash 相似度将条目分组。
         * 同组取最新条目为代表，返回 GroupedHistoryEntry 列表。
         * 无 pHash 的条目各自独立成组。
         */
        fun groupByPHash(entries: List<HistoryEntry>): List<GroupedHistoryEntry> {
            val validEntries = entries.filter { it.pHash != 0L }
            val noPHashEntries = entries.filter { it.pHash == 0L }

            val result = noPHashEntries.map { entry ->
                GroupedHistoryEntry(representative = entry, groupSize = 1, allEntryIds = listOf(entry.id))
            }.toMutableList()

            if (validEntries.isEmpty()) return result

            // Union-Find 分组
            val parent = IntArray(validEntries.size) { it }
            fun find(x: Int): Int {
                if (parent[x] != x) parent[x] = find(parent[x])
                return parent[x]
            }
            fun union(a: Int, b: Int) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }

            for (i in validEntries.indices) {
                for (j in i + 1 until validEntries.size) {
                    val sim = PerceptualHash.similarity(validEntries[i].pHash, validEntries[j].pHash)
                    if (sim >= PHASH_GROUP_THRESHOLD) {
                        union(i, j)
                    }
                }
            }

            val groups = mutableMapOf<Int, MutableList<HistoryEntry>>()
            for (i in validEntries.indices) {
                val root = find(i)
                groups.getOrPut(root) { mutableListOf() }.add(validEntries[i])
            }

            for (group in groups.values) {
                val sorted = group.sortedByDescending { it.createdAt }
                result.add(GroupedHistoryEntry(
                    representative = sorted.first(),
                    groupSize = sorted.size,
                    allEntryIds = sorted.map { it.id }
                ))
            }

            return result
        }

        /**
         * 为多尺寸分组分配颜色。
         * 多尺寸分组的 pHash → 颜色索引（1-6）。
         */
        fun computeGroupColorMap(
            pHashGroups: List<GroupedHistoryEntry>
        ): Map<Long, Int> {
            val result = mutableMapOf<Long, Int>()
            val multiGroups = pHashGroups.filter { it.allEntryIds.size > 1 }
            var colorIdx = 1
            for (group in multiGroups) {
                for (id in group.allEntryIds) {
                    result[id] = colorIdx
                }
                colorIdx = (colorIdx % 6) + 1
            }
            return result
        }
    }
}
