package translationapi.hymt2translation

/** JNI 桥声明，对应 cpp/hymt2/hymt2_bridge.cpp 的 nativeInit/nativeTranslate/nativeRelease。 */
object HyMt2Native {
    init {
        System.loadLibrary("hymt2")
    }

    /** 加载模型 + 建 context。返回句柄，0 = 失败。 */
    external fun nativeInit(modelPath: String, nThreads: Int, nCtx: Int): Long

    /** 翻译一段已拼装好提示词的文本，返回译文。 */
    external fun nativeTranslate(
        handle: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        maxTokens: Int
    ): String

    /** 释放模型与 context。 */
    external fun nativeRelease(handle: Long)
}
