# 漫画翻译续写格式控制实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用各厂商的续写模式（assistant prefill）硬约束漫画翻译输出格式，确保模型严格按照 `[1] 译文` 格式输出。

**Architecture:** `OpenAIProviderConfig` 添加 `continuationType` 字段标识续写方式；`OpenAITranslation` 根据续写类型在消息末尾添加 assistant prefill；`MangaFloatingService` 调用时传入续写参数。

**Tech Stack:** Kotlin, OkHttp, JSON

---

## 涉及文件

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/java/com/moe/moetranslator/me/CustomStorage.kt` | 修改 | 添加 `continuationType` 字段和常量 |
| `app/src/main/java/com/moe/moetranslator/me/BuiltinProviders.kt` | 修改 | 更新提示词 + 设置续写类型 |
| `app/src/main/java/translationapi/openaitranslation/OpenAITranslation.kt` | 修改 | 支持 assistant prefill |
| `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt` | 修改 | 传递续写参数 |

---

## Task 1: 添加 continuationType 字段和常量

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/CustomStorage.kt:62-92`

- [ ] **Step 1: 在 OpenAIProviderConfig.Companion 添加常量**

在 `companion object` 中添加：
```kotlin
const val CONTINUATION_NONE = "none"
const val CONTINUATION_STANDARD = "standard"
const val CONTINUATION_PARTIAL = "partial"
const val CONTINUATION_PREFIX = "prefix"
```

- [ ] **Step 2: 在 OpenAIProviderConfig 添加字段**

在 `consoleUrl` 字段之后添加：
```kotlin
val continuationType: String = CONTINUATION_NONE
```

- [ ] **Step 3: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 2: 更新提示词和续写类型

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/BuiltinProviders.kt`

- [ ] **Step 1: 更新漫画模式默认提示词**

将漫画系统提示词改为包含格式指令：
```kotlin
private const val DEFAULT_MANGA_SYSTEM_PROMPT =
    "你是漫画翻译引擎。逐条翻译，保持每条的[N]编号格式不变。只输出译文，不输出任何解释、标注、引言或附加内容。"
```

将漫画用户提示词简化（移除格式指令）：
```kotlin
private const val DEFAULT_MANGA_USER_PROMPT =
    "将以下文本从usefromlang翻译为usetolang：\n\nusesourcetext"
```

- [ ] **Step 2: 为每个提供商设置 continuationType**

火山引擎：
```kotlin
continuationType = OpenAIProviderConfig.CONTINUATION_STANDARD
```

智谱AI：
```kotlin
continuationType = OpenAIProviderConfig.CONTINUATION_NONE
```

DeepSeek：
```kotlin
continuationType = OpenAIProviderConfig.CONTINUATION_PREFIX
```

通义千问：
```kotlin
continuationType = OpenAIProviderConfig.CONTINUATION_PARTIAL
```

- [ ] **Step 3: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 3: OpenAITranslation 支持 assistant prefill

**Files:**
- Modify: `app/src/main/java/translationapi/openaitranslation/OpenAITranslation.kt:43-51`

- [ ] **Step 1: 添加构造函数参数**

```kotlin
class OpenAITranslation(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-3.5-turbo",
    private val systemPrompt: String,
    private val userPrompt: String,
    private val maxTokens: Int = 1000,
    private val temperature: Float = 0.3f,
    private val continuationType: String = "none",
    private val prefillContent: String = ""
) : TranslationTextAPI {
```

- [ ] **Step 2: 修改 buildRequestBody() 添加 assistant prefill**

在 messages 数组中，user 消息之后添加：
```kotlin
// 续写模式：添加 assistant prefill
if (prefillContent.isNotEmpty() && continuationType != "none") {
    put(JSONObject().apply {
        put("role", "assistant")
        put("content", prefillContent)
        when (continuationType) {
            "partial" -> put("partial", true)   // 千问
            "prefix" -> put("prefix", true)     // DeepSeek
        }
    })
}
```

- [ ] **Step 3: 修改 translate() 方法，DeepSeek 使用 beta 端点**

在构建 request URL 时：
```kotlin
val endpoint = if (continuationType == "prefix") {
    "$baseUrl/beta/chat/completions"
} else {
    "$baseUrl/chat/completions"
}

val request = Request.Builder()
    .url(endpoint)
    .post(requestBody.toRequestBody(JSON))
    // ...
```

- [ ] **Step 4: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 4: MangaFloatingService 传递续写参数

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt:276-285`

- [ ] **Step 1: 修改 OpenAITranslation 创建代码**

```kotlin
Constants.TextApi.OPENAI.id -> {
    val providerList = ConfigurationStorage.loadAllProviders(prefs)
    val selectedIndex = prefs.getInt("OpenAI_Selected_Provider", 0)
    if (providerList.isNotEmpty() && selectedIndex < providerList.size) {
        val provider = providerList[selectedIndex]
        translatorText = OpenAITranslation(
            apiKey = provider.apiKey,
            baseUrl = provider.baseUrl,
            model = provider.modelName,
            systemPrompt = provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt },
            userPrompt = provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt },
            continuationType = provider.continuationType,
            prefillContent = if (provider.continuationType != OpenAIProviderConfig.CONTINUATION_NONE) "[1] " else ""
        )
    } else {
        showToast("No OpenAI Provider Config Found.")
    }
}
```

- [ ] **Step 2: 验证 FloatingBallService 不受影响**

确认 `FloatingBallService.kt:276` 的 `OpenAITranslation` 创建不传续写参数（使用默认值 `CONTINUATION_NONE`）。

- [ ] **Step 3: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 5: 最终验证

- [ ] **Step 1: 完整编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 安装到设备**

Run: `adb install app/build/outputs/apk/debug/app-debug.apk`

测试场景：
1. 漫画翻译 → 火山引擎 → 输出应从 `[1] ` 开始
2. 漫画翻译 → 通义千问 → 输出应从 `[1] ` 开始（带 partial）
3. 漫画翻译 → DeepSeek → 输出应从 `[1] ` 开始（带 prefix，beta 端点）
4. 漫画翻译 → 智谱AI → 靠提示词约束
5. 游戏翻译 → 不使用续写
