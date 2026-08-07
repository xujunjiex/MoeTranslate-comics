package translationapi.hymt2translation

/** JNI 桥声明，对应 cpp/hymt2/hymt2_bridge.cpp 的 nativeInit/nativeTranslate/nativeRelease。 */
object HyMt2Native {
    init {
        System.loadLibrary("hymt2")
    }

    /**
     * 设置统一日志文件路径（<logs>/starflow.log）+ 安装 SIGSEGV/SIGABRT 处理器。
     * 之后所有 bridge 日志 + 崩溃 backtrace 都追加到该文件（与 Java 层 LogCollector 同一文件）。
     */
    external fun nativeSetLogFile(path: String)

    /** 加载模型 + 建 context。返回句柄，0 = 失败。 */
    external fun nativeInit(modelPath: String, nThreads: Int, nBatchThreads: Int, nCtx: Int): Long

    /**
     * 翻译一段已拼装好提示词的文本，返回译文。
     * @param prefix 固定指令前缀（不含待翻译文本），用于前缀 KV 缓存；传空串则每次都全量 prefill。
     */
    external fun nativeTranslate(
        handle: Long,
        prompt: String,
        prefix: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        maxTokens: Int
    ): String

    /**
     * 流式翻译：生成过程中回调阶段与译文片段（后台线程）。返回完整译文。
     * @param prefix 固定指令前缀（不含待翻译文本），用于前缀 KV 缓存；传空串则每次都全量 prefill。
     */
    external fun nativeTranslateStreaming(
        handle: Long,
        prompt: String,
        prefix: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        maxTokens: Int,
        callback: HyMt2StreamCallback
    ): String

    /** 释放模型与 context。 */
    external fun nativeRelease(handle: Long)

    /** 中止当前翻译（原子置位，解码循环提前退出）。 */
    external fun nativeAbort(handle: Long)

    /**
     * 多轮对话翻译：组装 [BOS]{system}<sys_end><hy_User>m1<hy_Assistant>m2...<hy_Assistant>。
     * @param roles 每条消息角色：0=user, 1=assistant；contents 对应文本。
     * @param systemPrompt 对话系统提示词（固定，用于前缀 KV 缓存）。
     */
    external fun nativeTranslateChat(
        handle: Long,
        systemPrompt: String,
        roles: IntArray,
        contents: Array<String>,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        maxTokens: Int,
        callback: HyMt2StreamCallback?
    ): String
}

/**
 * 流式翻译回调：
 * - [onPhase]：阶段通知，phase = "prefill"（读取原文中）/ "generate"（生成译文中）
 * - [onToken]：每生成一段译文回调，text 为「累积到当前的完整译文」
 */
interface HyMt2StreamCallback {
    fun onPhase(phase: String)
    fun onToken(text: String)
}
