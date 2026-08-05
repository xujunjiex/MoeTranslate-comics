package com.moe.starflow.chat
import com.moe.starflow.translate.widget.*

/** 对话消息角色 */
enum class ChatRole { USER, ASSISTANT }

/** 对话消息（领域对象；持久化用 data 层的 ChatMessageEntity） */
data class ChatMessage(
    val id: Long = 0,
    val role: ChatRole,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
