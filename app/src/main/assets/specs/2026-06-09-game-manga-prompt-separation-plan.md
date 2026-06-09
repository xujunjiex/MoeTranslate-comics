# 游戏/漫画提示词分离实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 游戏翻译和漫画翻译使用独立的系统提示词和用户提示词，用户可在 API 配置页面通过 TabLayout 切换分别配置。

**Architecture:** `OpenAIProviderConfig` 已有 `mangaSystemPrompt`/`mangaUserPrompt` 字段，需补全存储/加载/合并逻辑。UI 层在 `OpenAIText.kt` 添加 TabLayout 切换游戏/漫画配置。翻译流程中 `MangaFloatingService` 使用漫画提示词，`FloatingBallService` 使用游戏提示词。

**Tech Stack:** Kotlin, Android Views, SharedPreferences, TabLayout

---

## 涉及文件

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/java/com/moe/moetranslator/me/BuiltinProviders.kt` | 修改 | 添加漫画默认提示词常量 |
| `app/src/main/java/com/moe/moetranslator/me/CustomStorage.kt` | 修改 | `BuiltInProviderMod` 加漫画字段；存储/加载/合并函数处理漫画字段 |
| `app/src/main/java/com/moe/moetranslator/me/OpenAIText.kt` | 修改 | 添加 TabLayout 切换游戏/漫画配置；保存/加载/重置逻辑处理两套提示词 |
| `app/src/main/res/layout/fragment_openai_api.xml` | 修改 | 添加 TabLayout 组件 |
| `app/src/main/res/values/strings.xml` | 修改 | 添加字符串资源 |
| `app/src/main/res/values-zh/strings.xml` | 修改 | 添加中文字符串资源 |

---

## Task 1: 添加漫画默认提示词到 BuiltinProviders

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/BuiltinProviders.kt:9-16`

- [ ] **Step 1: 添加漫画默认提示词常量**

在 `BuiltinProviders.kt` 中添加漫画模式的默认提示词：

```kotlin
object BuiltinProviders {

    // 游戏模式默认提示词
    private const val DEFAULT_SYSTEM_PROMPT =
        "你是翻译引擎。只输出译文，不输出任何解释、标注、引言或附加内容。保持原文格式。"

    private const val DEFAULT_USER_PROMPT =
        "将以下文本从usefromlang翻译为usetolang，只输出译文：\n\nusesourcetext"

    // 漫画模式默认提示词
    private const val DEFAULT_MANGA_SYSTEM_PROMPT =
        "你是漫画翻译引擎。你会收到漫画中的文字气泡内容，可能包含OCR识别错误。请根据上下文修正识别错误后翻译。只输出译文，不输出任何解释、标注、引言或附加内容。保持原文格式。"

    private const val DEFAULT_MANGA_USER_PROMPT =
        "将以下漫画文本从usefromlang翻译为usetolang，只输出译文：\n\nusesourcetext"
```

- [ ] **Step 2: 为每个内置提供商添加漫画提示词字段**

更新每个 `OpenAIProviderConfig` 实例，添加 `defaultMangaSystemPrompt` 和 `defaultMangaUserPrompt`：

```kotlin
val providers = listOf(
    OpenAIProviderConfig(
        name = "豆包",
        // ... 现有字段 ...
        defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT,
        defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT,
        // ...
    ),
    // GLM、DeepSeek、千问 同理
)
```

- [ ] **Step 3: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 2: 更新 BuiltInProviderMod 支持漫画提示词

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/CustomStorage.kt:91-97`

- [ ] **Step 1: 添加漫画字段到 BuiltInProviderMod**

```kotlin
data class BuiltInProviderMod(
    val name: String,
    val apiKey: String = "",
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val mangaSystemPrompt: String? = null,
    val mangaUserPrompt: String? = null,
    val selectedModelIndex: Int = 0
)
```

- [ ] **Step 2: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（因为新字段有默认值）

---

## Task 3: 更新存储函数处理漫画字段

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/CustomStorage.kt`

- [ ] **Step 1: 更新 saveBuiltInProviderMods**

在 `saveBuiltInProviderMods()` 中保存漫画字段：

```kotlin
fun saveBuiltInProviderMods(prefs: CustomPreference, mods: List<BuiltInProviderMod>) {
    try {
        val jsonArray = JSONArray()
        mods.forEach { mod ->
            jsonArray.put(JSONObject().apply {
                put(KEY_NAME, mod.name)
                put(KEY_API_KEY, mod.apiKey)
                put(KEY_SYSTEM_PROMPT, mod.systemPrompt ?: JSONObject.NULL)
                put(KEY_USER_PROMPT, mod.userPrompt ?: JSONObject.NULL)
                put(KEY_MANGA_SYSTEM_PROMPT, mod.mangaSystemPrompt ?: JSONObject.NULL)
                put(KEY_MANGA_USER_PROMPT, mod.mangaUserPrompt ?: JSONObject.NULL)
                put(KEY_SELECTED_MODEL_INDEX, mod.selectedModelIndex)
            })
        }
        prefs.setString(BUILTIN_MODS_KEY, jsonArray.toString())
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
```

- [ ] **Step 2: 更新 loadBuiltInProviderMods**

在 `loadBuiltInProviderMods()` 中加载漫画字段：

```kotlin
fun loadBuiltInProviderMods(prefs: CustomPreference): List<BuiltInProviderMod> {
    return try {
        val jsonString = prefs.getString(BUILTIN_MODS_KEY, "")
        if (jsonString.isEmpty()) return emptyList()
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<BuiltInProviderMod>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(BuiltInProviderMod(
                name = obj.getString(KEY_NAME),
                apiKey = obj.optString(KEY_API_KEY, ""),
                systemPrompt = if (obj.isNull(KEY_SYSTEM_PROMPT)) null else obj.optString(KEY_SYSTEM_PROMPT, null),
                userPrompt = if (obj.isNull(KEY_USER_PROMPT)) null else obj.optString(KEY_USER_PROMPT, null),
                mangaSystemPrompt = if (obj.isNull(KEY_MANGA_SYSTEM_PROMPT)) null else obj.optString(KEY_MANGA_SYSTEM_PROMPT, null),
                mangaUserPrompt = if (obj.isNull(KEY_MANGA_USER_PROMPT)) null else obj.optString(KEY_MANGA_USER_PROMPT, null),
                selectedModelIndex = obj.optInt(KEY_SELECTED_MODEL_INDEX, 0)
            ))
        }
        list
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}
```

- [ ] **Step 3: 更新 applyMod 处理漫画提示词**

```kotlin
private fun applyMod(builtin: OpenAIProviderConfig, mod: BuiltInProviderMod?): OpenAIProviderConfig {
    if (mod == null) return builtin
    return builtin.copy(
        apiKey = mod.apiKey,
        systemPrompt = mod.systemPrompt ?: builtin.defaultSystemPrompt,
        userPrompt = mod.userPrompt ?: builtin.defaultUserPrompt,
        mangaSystemPrompt = mod.mangaSystemPrompt ?: builtin.defaultMangaSystemPrompt,
        mangaUserPrompt = mod.mangaUserPrompt ?: builtin.defaultMangaUserPrompt,
        selectedModelIndex = mod.selectedModelIndex,
        modelName = builtin.models.getOrElse(mod.selectedModelIndex) { builtin.models[0] }
    )
}
```

- [ ] **Step 4: 更新 saveOpenAIProviders 保存漫画字段**

```kotlin
fun saveOpenAIProviders(prefs: CustomPreference, list: List<OpenAIProviderConfig>) {
    try {
        val jsonArray = JSONArray()
        list.forEach { provider ->
            jsonArray.put(JSONObject().apply {
                put(KEY_NAME, provider.name)
                put(KEY_API_KEY, provider.apiKey)
                put(KEY_BASE_URL, provider.baseUrl)
                put(KEY_MODEL_NAME, provider.modelName)
                put(KEY_SYSTEM_PROMPT, provider.systemPrompt)
                put(KEY_USER_PROMPT, provider.userPrompt)
                put(KEY_MANGA_SYSTEM_PROMPT, provider.mangaSystemPrompt)
                put(KEY_MANGA_USER_PROMPT, provider.mangaUserPrompt)
                put(KEY_PROVIDER_TYPE, provider.providerType)
                put(KEY_SELECTED_MODEL_INDEX, provider.selectedModelIndex)
            })
        }
        prefs.setString("OpenAI_Providers", jsonArray.toString())
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
```

- [ ] **Step 5: 更新 loadOpenAIProviders 加载漫画字段**

```kotlin
fun loadOpenAIProviders(prefs: CustomPreference): List<OpenAIProviderConfig> {
    return try {
        val jsonString = prefs.getString("OpenAI_Providers", "")
        if (jsonString.isEmpty()) return emptyList()
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<OpenAIProviderConfig>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(OpenAIProviderConfig(
                name = obj.getString(KEY_NAME),
                apiKey = obj.getString(KEY_API_KEY),
                baseUrl = obj.getString(KEY_BASE_URL),
                modelName = obj.getString(KEY_MODEL_NAME),
                systemPrompt = obj.getString(KEY_SYSTEM_PROMPT),
                userPrompt = obj.getString(KEY_USER_PROMPT),
                mangaSystemPrompt = obj.optString(KEY_MANGA_SYSTEM_PROMPT, ""),
                mangaUserPrompt = obj.optString(KEY_MANGA_USER_PROMPT, ""),
                providerType = obj.optString(KEY_PROVIDER_TYPE, OpenAIProviderConfig.PROVIDER_TYPE_USER),
                selectedModelIndex = obj.optInt(KEY_SELECTED_MODEL_INDEX, 0)
            ))
        }
        list
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}
```

- [ ] **Step 6: 添加常量定义**

在 `ConfigurationStorage` 中添加：
```kotlin
private const val KEY_MANGA_SYSTEM_PROMPT = "mangaSystemPrompt"
private const val KEY_MANGA_USER_PROMPT = "mangaUserPrompt"
```

- [ ] **Step 7: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 4: 更新 UI 布局添加 TabLayout

**Files:**
- Modify: `app/src/main/res/layout/fragment_openai_api.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: 添加字符串资源**

`values/strings.xml`:
```xml
<string name="tab_game_mode">Game</string>
<string name="tab_manga_mode">Manga</string>
<string name="system_prompt_label">System Prompt</string>
<string name="user_prompt_label">User Prompt</string>
<string name="btn_reset">Reset</string>
```

`values-zh/strings.xml`:
```xml
<string name="tab_game_mode">游戏模式</string>
<string name="tab_manga_mode">漫画模式</string>
<string name="system_prompt_label">系统提示词</string>
<string name="user_prompt_label">用户提示词</string>
<string name="btn_reset">重置</string>
```

- [ ] **Step 2: 在布局中添加 TabLayout**

在 `fragment_openai_api.xml` 中，在提示词卡片之前添加 TabLayout：

```xml
<!-- 模式切换 Tab -->
<com.google.android.material.tabs.TabLayout
    android:id="@+id/prompt_mode_tabs"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginStart="16dp"
    android:layout_marginEnd="16dp"
    android:layout_marginBottom="8dp"
    app:tabMode="fixed"
    app:tabGravity="fill"
    app:tabIndicatorColor="@color/design_default_color_primary"
    app:tabSelectedTextColor="@color/design_default_color_primary"
    app:tabTextColor="@android:color/darker_gray" />
```

- [ ] **Step 3: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 5: 实现 OpenAIText.kt 中的 Tab 切换逻辑

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/OpenAIText.kt`

- [ ] **Step 1: 添加成员变量**

```kotlin
private var currentTab = 0  // 0=游戏模式, 1=漫画模式
private var mangaSystemPrompt: String = ""
private var mangaUserPrompt: String = ""
private var defaultMangaSystemPrompt: String = ""
private var defaultMangaUserPrompt: String = ""
```

- [ ] **Step 2: 在 onViewCreated 中设置 TabLayout**

```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    setupTabs()
    setupButtons()
    loadConfig()
}

private fun setupTabs() {
    binding.promptModeTabs.addTab(binding.promptModeTabs.newTab().setText(R.string.tab_game_mode))
    binding.promptModeTabs.addTab(binding.promptModeTabs.newTab().setText(R.string.tab_manga_mode))

    binding.promptModeTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
            currentTab = tab?.position ?: 0
            switchPromptDisplay()
        }
        override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
    })
}

private fun switchPromptDisplay() {
    if (currentTab == 0) {
        // 游戏模式：显示当前编辑器内容
        binding.editSystemPrompt.setText(binding.editSystemPrompt.text.toString())
        binding.editUserPrompt.setText(binding.editUserPrompt.text.toString())
    } else {
        // 漫画模式：显示漫画提示词
        binding.editSystemPrompt.setText(mangaSystemPrompt)
        binding.editUserPrompt.setText(mangaUserPrompt)
    }
}
```

- [ ] **Step 3: 修改 loadConfig 加载漫画提示词**

```kotlin
private fun loadConfig() {
    try {
        val allProviders = ConfigurationStorage.loadAllProviders(prefs)
        if (!isNew && providerIndex < allProviders.size) {
            val provider = allProviders[providerIndex]
            binding.editProviderName.setText(provider.name)
            binding.editApiKey.setText(provider.apiKey)
            binding.editBaseUrl.setText(provider.baseUrl)
            binding.editSystemPrompt.setText(provider.systemPrompt)
            binding.editUserPrompt.setText(provider.userPrompt)

            // 加载漫画提示词
            mangaSystemPrompt = provider.mangaSystemPrompt.ifEmpty { provider.defaultMangaSystemPrompt }
            mangaUserPrompt = provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt }
            defaultMangaSystemPrompt = provider.defaultMangaSystemPrompt
            defaultMangaUserPrompt = provider.defaultMangaUserPrompt

            if (provider.isBuiltin) {
                setupBuiltinMode(provider)
            } else {
                setupUserMode()
            }
        } else {
            binding.editSystemPrompt.setText(defaultSystemPrompt)
            binding.editUserPrompt.setText(defaultUserPrompt)
            mangaSystemPrompt = defaultMangaSystemPrompt
            mangaUserPrompt = defaultMangaUserPrompt
            setupUserMode()
        }
    } catch (e: Exception) {
        UiUtils.showToast(requireContext(), "Error loading configuration: ${e.message}")
    }
}
```

- [ ] **Step 4: 修改保存逻辑支持两套提示词**

修改 `saveBuiltinConfig`：
```kotlin
private fun saveBuiltinConfig(original: OpenAIProviderConfig) {
    val apiKey = binding.editApiKey.text.toString().trim()
    if (apiKey.isBlank()) {
        throw Exception(getString(R.string.fill_blank))
    }

    // 根据当前 tab 获取对应的提示词
    val systemPrompt: String
    val userPrompt: String
    val mangaSys: String
    val mangaUsr: String

    if (currentTab == 0) {
        systemPrompt = binding.editSystemPrompt.text.toString().ifBlank { original.defaultSystemPrompt }
        userPrompt = binding.editUserPrompt.text.toString().ifBlank { original.defaultUserPrompt }
        mangaSys = mangaSystemPrompt.ifBlank { original.defaultMangaSystemPrompt }
        mangaUsr = mangaUserPrompt.ifBlank { original.defaultMangaUserPrompt }
    } else {
        systemPrompt = original.systemPrompt.ifBlank { original.defaultSystemPrompt }
        userPrompt = original.userPrompt.ifBlank { original.defaultUserPrompt }
        mangaSys = binding.editSystemPrompt.text.toString().ifBlank { original.defaultMangaSystemPrompt }
        mangaUsr = binding.editUserPrompt.text.toString().ifBlank { original.defaultMangaUserPrompt }
    }

    val selectedModelIndex = this.selectedModelIndex

    val mods = ConfigurationStorage.loadBuiltInProviderMods(prefs).toMutableList()
    val existingIndex = mods.indexOfFirst { it.name == original.name }
    val mod = BuiltInProviderMod(
        name = original.name,
        apiKey = apiKey,
        systemPrompt = if (systemPrompt != original.defaultSystemPrompt) systemPrompt else null,
        userPrompt = if (userPrompt != original.defaultUserPrompt) userPrompt else null,
        mangaSystemPrompt = if (mangaSys != original.defaultMangaSystemPrompt) mangaSys else null,
        mangaUserPrompt = if (mangaUsr != original.defaultMangaUserPrompt) mangaUsr else null,
        selectedModelIndex = selectedModelIndex
    )
    if (existingIndex >= 0) {
        mods[existingIndex] = mod
    } else {
        mods.add(mod)
    }
    ConfigurationStorage.saveBuiltInProviderMods(prefs, mods)

    UiUtils.showToast(requireContext(), getString(R.string.save_successfully))
    requireActivity().finish()
}
```

- [ ] **Step 5: 修改重置按钮逻辑**

```kotlin
// 在 setupBuiltinMode 中更新重置按钮
binding.btnResetSystemPrompt.setOnClickListener {
    if (currentTab == 0) {
        binding.editSystemPrompt.setText(provider.defaultSystemPrompt)
    } else {
        binding.editSystemPrompt.setText(provider.defaultMangaSystemPrompt)
    }
}
binding.btnResetUserPrompt.setOnClickListener {
    if (currentTab == 0) {
        binding.editUserPrompt.setText(provider.defaultUserPrompt)
    } else {
        binding.editUserPrompt.setText(provider.defaultMangaUserPrompt)
    }
}
```

- [ ] **Step 6: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 6: 更新翻译流程使用对应模式提示词

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt:276-285`

- [ ] **Step 1: 修改 MangaFloatingService 使用漫画提示词**

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
            userPrompt = provider.mangaUserPrompt.ifEmpty { provider.defaultMangaUserPrompt }
        )
    } else {
        showToast("No OpenAI Provider Config Found.")
    }
}
```

- [ ] **Step 2: 验证 FloatingBallService 使用游戏提示词（无需修改）**

确认 `FloatingBallService.kt:276` 已使用 `provider.systemPrompt` 和 `provider.userPrompt`，无需修改。

- [ ] **Step 3: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 7: 处理自定义 API 的漫画提示词

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/me/OpenAIText.kt`

- [ ] **Step 1: 修改 saveUserConfig 保存漫画提示词**

```kotlin
private fun saveUserConfig() {
    // ... 现有验证逻辑 ...

    val systemPrompt: String
    val userPrompt: String
    val mangaSys: String
    val mangaUsr: String

    if (currentTab == 0) {
        systemPrompt = binding.editSystemPrompt.text.toString().ifBlank { defaultSystemPrompt }
        userPrompt = binding.editUserPrompt.text.toString().ifBlank { defaultUserPrompt }
        mangaSys = mangaSystemPrompt.ifBlank { defaultMangaSystemPrompt }
        mangaUsr = mangaUserPrompt.ifBlank { defaultMangaUserPrompt }
    } else {
        systemPrompt = defaultSystemPrompt
        userPrompt = defaultUserPrompt
        mangaSys = binding.editSystemPrompt.text.toString().ifBlank { defaultMangaSystemPrompt }
        mangaUsr = binding.editUserPrompt.text.toString().ifBlank { defaultMangaUserPrompt }
    }

    val provider = OpenAIProviderConfig(
        name = providerName,
        apiKey = binding.editApiKey.text.toString().trim(),
        baseUrl = normalizedUrl,
        modelName = binding.editModelName.text.toString().trim(),
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        mangaSystemPrompt = mangaSys,
        mangaUserPrompt = mangaUsr
    )

    // ... 保存逻辑 ...
}
```

- [ ] **Step 2: 验证编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 8: 最终验证

- [ ] **Step 1: 完整编译**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 安装到设备测试**

Run: `adb install app/build/outputs/apk/debug/app-debug.apk`

测试场景：
1. 打开内置 API 配置页面，验证 TabLayout 显示
2. 切换游戏/漫画 tab，验证提示词切换
3. 修改游戏模式提示词，切换到漫画 tab，再切回，验证内容正确
4. 重置按钮在不同 tab 下重置为对应默认值
5. 保存后重新打开，验证两套提示词都正确保存
6. 漫画翻译使用漫画提示词，游戏翻译使用游戏提示词
