package com.moe.starflow.chat
import com.moe.starflow.translate.widget.*

object ChatPromptBuilder {

    /** 历史截断上限：超过保留最近 N 条（含当前输入） */
    const val MAX_HISTORY_MESSAGES = 30

    /**
     * 把历史消息 + 当前输入转成引擎消息序列（roles/contents）。
     * roles: 0=user, 1=assistant；顺序 = 历史顺序 + 当前输入（user）在最后。
     * 超过 MAX_HISTORY_MESSAGES 时保留最近 N 条（粗粒度截断，不做 token 精确计算）。
     */
    fun buildMessages(history: List<ChatMessage>, input: String): Pair<IntArray, Array<String>> {
        val all = history + ChatMessage(role = ChatRole.USER, content = input)
        val recent = all.takeLast(MAX_HISTORY_MESSAGES)
        val roles = IntArray(recent.size) { if (recent[it].role == ChatRole.USER) 0 else 1 }
        return roles to recent.map { it.content }.toTypedArray()
    }
}
