package com.moe.starflow.chat
import com.moe.starflow.translate.widget.*

data class ChatTemplate(
    val id: String,
    val label: String,
    val zh: String,
    val en: String,
    val variableHints: Map<String, String>
)

object ChatTemplates {

    /** 对话默认系统提示词：自由聊天助手 */
    const val DEFAULT_SYSTEM = "你是一个乐于助人的 AI 助手，请直接回答用户的问题。"

    /** 7 套官方翻译模板（桌面端 MODES 移植） */
    val all: List<ChatTemplate> = listOf(
        ChatTemplate(
            id = "default", label = "默认翻译",
            zh = "将以下文本翻译为 {target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{source_text}",
            en = "Translate the following text into {target_lang}. Note that you should only output the translated result without any additional explanation:\n\n{source_text}",
            variableHints = mapOf("{source_text}" to "待翻译文本", "{target_lang}" to "目标语言")
        ),
        ChatTemplate(
            id = "terminology", label = "术语",
            zh = "参考下面的翻译：\n{glossary}\n将以下文本翻译为 {target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{source_text}",
            en = "Reference the following translations:\n{glossary}\nTranslate the following text into {target_lang}. Note that you must ONLY output the translated result without any additional explanation:\n\n{source_text}",
            variableHints = mapOf(
                "{glossary}" to "术语对照（每行：原文 译文，多词用 = 分隔）",
                "{source_text}" to "待翻译文本", "{target_lang}" to "目标语言"
            )
        ),
        ChatTemplate(
            id = "style", label = "风格",
            zh = "请将以下文本翻译为 {target_lang}。\n注意翻译的风格要严格符合【{target_style}】\n\n{source_text}",
            en = "Please translate the following text into {target_lang}. Note that the translation style must strictly conform to [{target_style}]:\n\n{source_text}",
            variableHints = mapOf(
                "{target_style}" to "翻译风格（古风/现代/口语化/正式/可爱/书面语/方言/动漫风）",
                "{source_text}" to "待翻译文本", "{target_lang}" to "目标语言"
            )
        ),
        ChatTemplate(
            id = "personalization", label = "个性化",
            zh = "【待翻译文本】\n{source_text}\n\n【翻译任务】\n{prefs}",
            en = "[Source Text]\n{source_text}\n\n[Translation Tasks]\n{prefs}",
            variableHints = mapOf(
                "{prefs}" to "翻译偏好（每行一条，如：人名保留原文）",
                "{source_text}" to "待翻译文本"
            )
        ),
        ChatTemplate(
            id = "delimiters", label = "分隔符",
            zh = "请将以下文本准确翻译为 {target_lang}。\n你必须在译文中保留等量的分隔符，绝对不可遗漏、转义或翻译该符号，并注意分隔符的位置。\n\n{source_text}",
            en = "Please accurately translate the following text into {target_lang}.\nYou must retain the exact same number of delimiters in the translation. Strictly do not omit, escape, or translate these symbols, and pay close attention to their placement.\n\n{source_text}",
            variableHints = mapOf("{source_text}" to "待翻译文本", "{target_lang}" to "目标语言")
        ),
        ChatTemplate(
            id = "structured1", label = "结构化数据",
            zh = "# 任务目标\n将下方 {source_text} 中的 {format_type} 格式数据翻译为 {target_lang}。\n\n# 严格约束\n1. 结构锁定：绝对保持原有的 {format_type} 数据结构、缩进和层级完全不变。\n2. 选择性翻译：仅翻译面向用户展示的可见文本内容。\n3. 禁止修改：严禁翻译或更改任何代码标签、键名 (Key)、变量占位符（如 {{var}}、\${var}、%s、%d 等）或代码属性。\n\n# 数据输入\n{source_text}",
            en = "### Task\nTranslate the user-facing text within the following {format_type} data into {target_lang}.\n\n### Strict Rules\n1. Structure Preservation: You MUST preserve the original {format_type} data structure, nesting, hierarchy, and indentation exactly as they are.\n2. Selective Translation: Translate ONLY the visible, user-facing text content/values.\n3. Strict Non-Translation: NEVER translate or alter code tags, keys, properties, object names, or variable placeholders. Leave them exactly in their original English/code form.\n\n### Source Data\n{source_text}",
            variableHints = mapOf(
                "{format_type}" to "数据结构类型（JSON/XML/YAML/代码/配置文件）",
                "{source_text}" to "待翻译数据", "{target_lang}" to "目标语言"
            )
        ),
        ChatTemplate(
            id = "structured2", label = "带背景",
            zh = "【背景信息】\n{background_text}\n\n请结合背景信息将以下文本翻译为 {target_lang}。\n\n【待翻译文本】\n{source_text}",
            en = "[Background Information]\n{background_text}\n\nPlease translate the following text into {target_lang}, taking the provided background information into consideration.\n\n[Source Text]\n{source_text}",
            variableHints = mapOf(
                "{background_text}" to "背景信息（如：这是游戏攻略，术语保持官方译名）",
                "{source_text}" to "待翻译文本", "{target_lang}" to "目标语言"
            )
        )
    )
}
