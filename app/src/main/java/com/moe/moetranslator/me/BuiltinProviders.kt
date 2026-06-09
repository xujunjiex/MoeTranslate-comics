package com.moe.moetranslator.me

/**
 * 内置 OpenAI 兼容 API 提供商定义
 *
 * 内置 API 始终存在，用户只能修改 API Key、提示词和模型选择。
 * 名称、URL 等核心配置不可修改。
 */
object BuiltinProviders {

    private const val DEFAULT_SYSTEM_PROMPT =
        "你是翻译引擎。只输出译文，不输出任何解释、标注、引言或附加内容。保持原文格式。"

    private const val DEFAULT_USER_PROMPT =
        "将以下文本从usefromlang翻译为usetolang，只输出译文：\n\nusesourcetext"

    // 漫画模式默认提示词
    private const val DEFAULT_MANGA_SYSTEM_PROMPT =
        "你是漫画翻译引擎。逐条翻译，保持每条的[N]编号格式不变。只输出译文，不输出任何解释、标注、引言或附加内容。"

    private const val DEFAULT_MANGA_USER_PROMPT =
        "将以下文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    // 漫画模式JSON格式提示词（智谱AI结构化输出用）
    private const val DEFAULT_MANGA_SYSTEM_PROMPT_JSON =
        "你是漫画翻译引擎。将每条文本翻译后以JSON格式返回：{\"translations\":[\"译文1\",\"译文2\"]}。数组顺序与输入编号一致，只输出JSON。"

    private const val DEFAULT_MANGA_USER_PROMPT_JSON =
        "将以下文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    val providers = listOf(
        OpenAIProviderConfig(
            name = "火山引擎",
            apiKey = "",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            modelName = "doubao-seed-2-0-pro-260215",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = listOf(
                "doubao-seed-2-0-pro-260215",
                "doubao-seed-2-0-lite-260428",
                "doubao-seed-2-0-mini-260428",
                "doubao-seed-2-0-code-preview-260215"
            ),
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT,
            defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT,
            selectedModelIndex = 0,
            consoleUrl = "https://console.volcengine.com/ark",
            continuationType = OpenAIProviderConfig.CONTINUATION_STANDARD
        ),
        OpenAIProviderConfig(
            name = "智谱AI",
            apiKey = "",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            modelName = "glm-4-flash-250414",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = listOf(
                "glm-5",
                "glm-4.7",
                "glm-4.7-flash",
                "glm-4-flash-250414"
            ),
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT_JSON,
            defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT_JSON,
            selectedModelIndex = 0,
            consoleUrl = "https://open.bigmodel.cn/",
            continuationType = OpenAIProviderConfig.CONTINUATION_JSON
        ),
        OpenAIProviderConfig(
            name = "DeepSeek",
            apiKey = "",
            baseUrl = "https://api.deepseek.com",
            modelName = "deepseek-v4-pro",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = listOf("deepseek-v4-pro", "deepseek-v4-flash"),
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT,
            defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT,
            selectedModelIndex = 0,
            consoleUrl = "https://platform.deepseek.com/",
            continuationType = OpenAIProviderConfig.CONTINUATION_PREFIX
        ),
        OpenAIProviderConfig(
            name = "通义千问",
            apiKey = "",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            modelName = "qwen3.7-plus",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = listOf(
                "qwen3.7-max",
                "qwen3.7-plus",
                "qwen3.6-plus",
                "qwen3.6-flash"
            ),
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT,
            defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT,
            selectedModelIndex = 0,
            consoleUrl = "https://dashscope.console.aliyun.com/",
            continuationType = OpenAIProviderConfig.CONTINUATION_PARTIAL
        )
    )
}
