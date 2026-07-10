package com.moe.starflow.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.data.HistoryEntry
import com.moe.starflow.data.HistoryGroup
import com.moe.starflow.data.HistorySession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 游戏历史分组适配器
 * 支持日期标题 + 会话子标题 + 历史记录项
 */
class HistoryGroupAdapter(
    private val onItemClick: (HistoryEntry) -> Unit,
    private val onItemLongClick: (HistoryEntry) -> Unit
) : ListAdapter<HistoryGroup, HistoryGroupAdapter.GroupViewHolder>(GroupDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_date_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvDateHeader: TextView = view.findViewById(R.id.tvDateHeader)
        private val rvSessions: RecyclerView = view.findViewById(R.id.rvSessions)

        fun bind(group: HistoryGroup) {
            tvDateHeader.text = group.dateLabel

            val sessionAdapter = SessionAdapter(onItemClick, onItemLongClick)
            rvSessions.layoutManager = LinearLayoutManager(itemView.context)
            rvSessions.adapter = sessionAdapter
            sessionAdapter.submitList(group.sessions)
        }
    }

    private inner class SessionAdapter(
        private val onItemClick: (HistoryEntry) -> Unit,
        private val onItemLongClick: (HistoryEntry) -> Unit
    ) : ListAdapter<HistorySession, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_session, parent, false)
            return SessionViewHolder(view)
        }

        override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvSessionHeader: TextView = view.findViewById(R.id.tvSessionHeader)
            private val rvEntries: RecyclerView = view.findViewById(R.id.rvSessionEntries)

            fun bind(session: HistorySession) {
                // 会话时间段
                val startTime = timeFormat.format(Date(session.startTime))
                val endTime = timeFormat.format(Date(session.endTime))
                tvSessionHeader.text = "$startTime - $endTime (${session.entries.size})"

                // 历史记录列表
                val entryAdapter = HistoryGameAdapter(onItemClick, onItemLongClick)
                rvEntries.layoutManager = LinearLayoutManager(itemView.context)
                rvEntries.adapter = entryAdapter
                entryAdapter.submitList(session.entries)
            }
        }
    }

    // ========== DiffCallbacks ==========

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
