package translationapi.hymt2translation

object HyMt2Prompt {
    /** 替换 {target_lang} / {source_text} 占位符。 */
    fun build(template: String, targetName: String, sourceText: String): String =
        template
            .replace("{target_lang}", targetName)
            .replace("{source_text}", sourceText)

    /**
     * 固定指令前缀（不含待翻译文本）：模板中 {source_text} 之前、{target_lang} 替换后的部分。
     * 同一模板 + 目标语言下跨翻译不变 → 桥接层用它做前缀 KV 缓存，跳过重复 prefill。
     */
    fun buildPrefix(template: String, targetName: String): String =
        template
            .replace("{target_lang}", targetName)
            .substringBefore("{source_text}")
}
