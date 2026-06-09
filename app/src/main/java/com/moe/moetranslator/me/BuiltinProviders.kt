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

    val providers = listOf(
        OpenAIProviderConfig(
            name = "豆包",
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
            selectedModelIndex = 0
        ),
        OpenAIProviderConfig(
            name = "GLM",
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
            selectedModelIndex = 0
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
            selectedModelIndex = 0
        ),
        OpenAIProviderConfig(
            name = "千问",
            apiKey = "",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            modelName = "qwen3.5-plus",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = listOf(
                "qwen3.6-plus",
                "qwen3.6-flash",
                "qwen3.5-plus",
                "qwen3.5-flash"
            ),
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            selectedModelIndex = 0
        )
    )
}
