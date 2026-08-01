package translationapi.hymt2translation

object HyMt2Prompt {
    /** 替换 {target_lang} / {source_text} 占位符。 */
    fun build(template: String, targetName: String, sourceText: String): String =
        template
            .replace("{target_lang}", targetName)
            .replace("{source_text}", sourceText)
}
