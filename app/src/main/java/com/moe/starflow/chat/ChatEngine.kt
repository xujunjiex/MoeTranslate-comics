package com.moe.starflow.chat
import com.moe.starflow.translate.widget.*

/** 对话引擎回调结果 */
sealed class ChatResult {
    data class Success(val text: String) : ChatResult()
    data class Error(val error: Exception) : ChatResult()
}

/** 对话引擎抽象：多轮自由聊天 */
interface ChatEngine {
    /**
     * @param history 历史消息（不含当前输入）
     * @param input 当前用户输入
     * @param onPhase 阶段回调（"prefill"/"generate"）
     * @param onPartial 流式：累积到当前的完整回复（后台线程调用）
     * @param callback 完成回调（后台线程）
     */
    fun chat(
        history: List<ChatMessage>,
        input: String,
        onPhase: (String) -> Unit,
        onPartial: (String) -> Unit,
        callback: (ChatResult) -> Unit
    )

    fun cancel()
    fun release()
}
