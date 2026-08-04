package com.moe.starflow.chat

import android.content.Context
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import translationapi.hymt2translation.HyMT2SharedHolder
import translationapi.hymt2translation.HyMt2Params

/** Hy-MT2 对话引擎：走进程级共享热实例，多轮 chat 调 nativeTranslateChat */
class HyMt2ChatEngine(context: Context, prefs: CustomPreference) : ChatEngine {

    private val appContext = context.applicationContext
    private val shared = HyMT2SharedHolder.get(appContext, prefs)
    @Volatile private var cancelled = false

    override fun chat(
        history: List<ChatMessage>,
        input: String,
        onPhase: (String) -> Unit,
        onPartial: (String) -> Unit,
        callback: (ChatResult) -> Unit
    ) {
        cancelled = false
        val (roles, contents) = ChatPromptBuilder.buildMessages(history, input)
        LogCollector.d(TAG, "Hy-MT2 chat 发送: system='${ChatTemplates.DEFAULT_SYSTEM.take(60)}'")
        LogCollector.d(TAG, "Hy-MT2 chat 消息[${contents.size}]: " + contents.mapIndexed { i, c ->
            "${if (roles[i] == 0) "user" else "assistant"}: ${c.take(80)}"
        }.joinToString(" | "))
        val params = HyMt2Params.read(CustomPreference.getInstance(appContext).getSharedPreferences())
        shared.chatNative(
            roles, contents, ChatTemplates.DEFAULT_SYSTEM,
            params.temperature, params.topP, params.topK, params.repetitionPenalty, params.maxTokens,
            onPhase, onPartial
        ) { result ->
            when (result) {
                is TranslationResult.Success -> callback(ChatResult.Success(result.translatedText))
                is TranslationResult.Error -> callback(ChatResult.Error(result.error))
            }
        }
    }

    override fun cancel() {
        cancelled = true
        shared.cancelTranslation()
    }

    override fun release() {
        cancelled = true
        // 共享实例 keepAlive 常驻，全 app 复用：不释放模型
    }

    companion object {
        private const val TAG = "HyMt2ChatEngine"
    }
}
