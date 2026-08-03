package translationapi.hymt2translation

import android.content.SharedPreferences

/** Hy-MT2 详情页可调参数：读取/默认值。 */
object HyMt2Params {
    const val KEY_PROMPT = "hymt2_prompt_template"
    const val KEY_THREADS = "hymt2_threads"
    const val KEY_BATCH_THREADS = "hymt2_batch_threads"
    const val KEY_CONTEXT = "hymt2_context_size"
    const val KEY_TEMP = "hymt2_temperature"
    const val KEY_TOP_P = "hymt2_top_p"
    const val KEY_TOP_K = "hymt2_top_k"
    const val KEY_REP_PENALTY = "hymt2_rep_penalty"
    const val KEY_MAX_TOKENS = "hymt2_max_tokens"

    const val DEFAULT_PROMPT =
        "将以下文本翻译为 {target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{source_text}"
    const val DEFAULT_CONTEXT = 2048
    const val DEFAULT_TEMP = 0.7f
    const val DEFAULT_TOP_P = 0.6f
    const val DEFAULT_TOP_K = 20
    const val DEFAULT_REP_PENALTY = 1.05f
    const val DEFAULT_MAX_TOKENS = 4096

    /**
     * 默认生成线程数：按设备核心数适配，不写死固定值。
     * 1.25-bit 解码是内存带宽瓶颈，线程超过 6 个后带宽饱和、层间同步开销增大反而变慢；
     * 核心数少于 6 时取核心数（避免线程数超过核数，线程相互抢占反而变慢）。
     */
    val defaultThreads: Int
        get() = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

    /** 默认批量（prefill）线程数：用满设备全部核（与旧行为一致），可手动调小对比速度 */
    val defaultBatchThreads: Int
        get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    data class HyMt2Settings(
        val promptTemplate: String,
        val threads: Int,
        val batchThreads: Int,
        val contextSize: Int,
        val temperature: Float,
        val topP: Float,
        val topK: Int,
        val repetitionPenalty: Float,
        val maxTokens: Int,
    )

    fun read(prefs: SharedPreferences): HyMt2Settings = HyMt2Settings(
        promptTemplate = prefs.getString(KEY_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT,
        threads = prefs.getInt(KEY_THREADS, defaultThreads),
        batchThreads = prefs.getInt(KEY_BATCH_THREADS, defaultBatchThreads),
        contextSize = prefs.getInt(KEY_CONTEXT, DEFAULT_CONTEXT),
        temperature = prefs.getFloat(KEY_TEMP, DEFAULT_TEMP),
        topP = prefs.getFloat(KEY_TOP_P, DEFAULT_TOP_P),
        topK = prefs.getInt(KEY_TOP_K, DEFAULT_TOP_K),
        repetitionPenalty = prefs.getFloat(KEY_REP_PENALTY, DEFAULT_REP_PENALTY),
        maxTokens = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS),
    )
}
