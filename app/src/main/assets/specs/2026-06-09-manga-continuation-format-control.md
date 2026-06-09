# 漫画翻译续写格式控制设计

## 目标

用各厂商的续写模式（assistant prefill）硬约束漫画翻译输出格式，确保模型严格按照 `[1] 译文` 格式输出。

## 提示词结构调整

**格式要求**（全局规则）→ 系统提示词
**语言对**（动态）→ 用户提示词
**待翻译文本** → 用户提示词
**`[1] `** → assistant prefill（硬约束）

### 漫画模式默认提示词

系统提示词：
```
你是漫画翻译引擎。逐条翻译，保持每条的[N]编号格式不变。只输出译文，不输出任何解释、标注、引言或附加内容。
```

用户提示词：
```
将以下文本从usefromlang翻译为usetolang：\n\nusesourcetext
```

### 游戏模式默认提示词（不变）

系统提示词：
```
你是翻译引擎。只输出译文，不输出任何解释、标注、引言或附加内容。保持原文格式。
```

用户提示词：
```
将以下文本从usefromlang翻译为usetolang，只输出译文：\n\nusesourcetext
```

## 各厂商续写方式

| 提供商 | continuationType | 额外参数 | 端点 |
|--------|-----------------|----------|------|
| 火山引擎 | `standard` | 无 | `/chat/completions` |
| 通义千问 | `partial` | `"partial": true` | `/chat/completions` |
| DeepSeek | `prefix` | `"prefix": true` | `/beta/chat/completions` |
| 智谱AI | `none` | 无 | `/chat/completions` |
| 自定义API | `none` | 无 | `/chat/completions` |

## 数据结构

### OpenAIProviderConfig 新增字段

```kotlin
val continuationType: String = CONTINUATION_NONE
```

常量（`OpenAIProviderConfig.Companion`）：
```kotlin
const val CONTINUATION_NONE = "none"
const val CONTINUATION_STANDARD = "standard"
const val CONTINUATION_PARTIAL = "partial"
const val CONTINUATION_PREFIX = "prefix"
```

### OpenAITranslation 新增参数

```kotlin
class OpenAITranslation(
    // ... 现有参数 ...
    private val continuationType: String = CONTINUATION_NONE,
    private val prefillContent: String = ""
)
```

## 请求构建逻辑

`buildRequestBody()` 修改：

```kotlin
val messages = JSONArray().apply {
    put(JSONObject().apply {
        put("role", "system")
        put("content", systemPrompt)
    })
    put(JSONObject().apply {
        put("role", "user")
        put("content", userPrompt)
    })
    // 续写模式：添加 assistant prefill
    if (prefillContent.isNotEmpty() && continuationType != CONTINUATION_NONE) {
        put(JSONObject().apply {
            put("role", "assistant")
            put("content", prefillContent)
            when (continuationType) {
                CONTINUATION_PARTIAL -> put("partial", true)   // 千问
                CONTINUATION_PREFIX -> put("prefix", true)     // DeepSeek
            }
        })
    }
}
```

DeepSeek 特殊处理：
- URL 从 `/chat/completions` 改为 `/beta/chat/completions`

## 调用方改动

### MangaFloatingService（漫画翻译）

```kotlin
translatorText = OpenAITranslation(
    apiKey = provider.apiKey,
    baseUrl = provider.baseUrl,
    model = provider.modelName,
    systemPrompt = provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt },
    userPrompt = provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt },
    continuationType = provider.continuationType,
    prefillContent = if (provider.continuationType != OpenAIProviderConfig.CONTINUATION_NONE) "[1] " else ""
)
```

### FloatingBallService（游戏翻译）

不传续写参数，默认 `CONTINUATION_NONE`。

## 修改文件清单

| 文件 | 改动 |
|------|------|
| `CustomStorage.kt` | `OpenAIProviderConfig` 添加 `continuationType` 字段和常量 |
| `BuiltinProviders.kt` | 更新提示词 + 设置各提供商的 `continuationType` |
| `OpenAITranslation.kt` | 构造函数加参数，`buildRequestBody()` 支持续写，DeepSeek beta 端点 |
| `MangaFloatingService.kt` | 创建 `OpenAITranslation` 时传入续写参数 |

## 向后兼容

- 自定义 API 默认 `CONTINUATION_NONE`，行为不变
- 智谱AI 使用 `CONTINUATION_NONE`，靠系统提示词约束
- 游戏模式不使用续写
