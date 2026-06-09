# 游戏/漫画模式提示词分离设计

## 概述

将游戏翻译和漫画翻译的提示词配置分离，允许用户为不同翻译场景配置独立的系统提示词和用户提示词。

## 背景

当前所有翻译模式共享同一套提示词配置，但游戏翻译和漫画翻译有不同的需求：
- 游戏翻译：单条文本翻译，注重准确性和简洁性
- 漫画翻译：批量文本翻译，需要保持编号格式，注重角色语气和情感表达

## 设计方案

### 1. 数据结构变更

#### OpenAIProviderConfig

```kotlin
data class OpenAIProviderConfig(
    // 现有字段
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val systemPrompt: String,           // 游戏模式系统提示词
    val userPrompt: String,             // 游戏模式用户提示词
    val providerType: String = PROVIDER_TYPE_USER,
    val models: List<String> = emptyList(),
    val defaultSystemPrompt: String = "",  // 游戏模式默认系统提示词
    val defaultUserPrompt: String = "",    // 游戏模式默认用户提示词
    val selectedModelIndex: Int = 0,
    val apiFormat: String = FORMAT_CHAT_COMPLETIONS,
    val consoleUrl: String = "",
    
    // 新增字段
    val mangaSystemPrompt: String = "",    // 漫画模式系统提示词
    val mangaUserPrompt: String = "",      // 漫画模式用户提示词
    val defaultMangaSystemPrompt: String = "",  // 漫画模式默认系统提示词
    val defaultMangaUserPrompt: String = "",    // 漫画模式默认用户提示词
)
```

#### BuiltInProviderMod

```kotlin
data class BuiltInProviderMod(
    val name: String,
    val apiKey: String = "",
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val mangaSystemPrompt: String? = null,  // 新增
    val mangaUserPrompt: String? = null,    // 新增
    val selectedModelIndex: Int = 0
)
```

### 2. 默认提示词

#### 游戏模式

**系统提示词：**
```
你是翻译引擎。只输出译文，不输出任何解释、标注、引言或附加内容。保持原文格式。
```

**用户提示词：**
```
将以下文本从usefromlang翻译为usetolang，只输出译文：

usesourcetext
```

#### 漫画模式

**系统提示词：**
```
你是翻译引擎。只输出译文，不输出任何解释、标注、引言或附加内容。保持原文格式。你是漫画翻译专家，擅长翻译漫画对话和拟声词。翻译时保持角色语气和情感，适当意译使译文自然流畅。
```

**用户提示词：**
```
请逐条翻译以下漫画文本，保持每条的[N]编号格式不变，只输出翻译结果，不要添加额外解释：
usefromlang→usetolang：

usesourcetext
```

### 3. UI设计

#### API配置页面布局

```
┌─────────────────────────────────────────┐
│ [提供商图标] 豆包                         │
├─────────────────────────────────────────┤
│ API Key: [____________________________] │
│ 🔑 获取 API Key：https://...            │
├─────────────────────────────────────────┤
│ 模型: [doubao-seed-2-0-pro-260215  ▼]  │
├─────────────────────────────────────────┤
│ ┌─────────────┬─────────────┐           │
│ │  游戏模式   │  漫画模式   │  ← Tab切换 │
│ └─────────────┴─────────────┘           │
│                                         │
│ 系统提示词:                              │
│ ┌─────────────────────────────────────┐ │
│ │ 你是翻译引擎。只输出译文，不输出任何...│ │
│ └─────────────────────────────────────┘ │
│ [重置为默认]                             │
│                                         │
│ 用户提示词:                              │
│ ┌─────────────────────────────────────┐ │
│ │ 将以下文本从usefromlang翻译为...     │ │
│ └─────────────────────────────────────┘ │
│ [重置为默认]                             │
├─────────────────────────────────────────┤
│ [保存配置]                               │
│ [测试连接]                               │
└─────────────────────────────────────────┘
```

#### Tab切换逻辑

- 点击「游戏模式」Tab：显示游戏模式的系统提示词和用户提示词
- 点击「漫画模式」Tab：显示漫画模式的系统提示词和用户提示词
- 切换Tab时自动将当前模式的提示词保存到ViewModel临时变量
- 点击「保存配置」时同时保存两种模式的提示词到SharedPreferences

### 4. 提示词变量

| 变量 | 说明 | 游戏模式 | 漫画模式 |
|------|------|----------|----------|
| `usefromlang` | 源语言名称 | ✅ 支持 | ✅ 支持 |
| `usetolang` | 目标语言名称 | ✅ 支持 | ✅ 支持 |
| `usesourcetext` | 待翻译文本 | ✅ 支持 | ✅ 支持 |

漫画模式的用户提示词中，`usesourcetext` 会被替换为带编号的批量文本：

```
请逐条翻译以下漫画文本，保持每条的[N]编号格式不变，只输出翻译结果，不要添加额外解释：
日语→中文：

[1] こんにちは
[2] ありがとう
[3] さようなら
```

### 5. 翻译流程

#### 游戏翻译

```
读取配置 → 使用 systemPrompt + userPrompt → 替换变量 → 发送请求
```

#### 漫画翻译

```
读取配置 → 使用 mangaSystemPrompt + mangaUserPrompt → 构建编号文本 → 替换变量 → 发送请求
```

### 6. 存储方式

每个提供商独立配置游戏/漫画提示词：

```
豆包:
  游戏: systemPrompt_game, userPrompt_game
  漫画: systemPrompt_manga, userPrompt_manga

GLM:
  游戏: systemPrompt_game, userPrompt_game
  漫画: systemPrompt_manga, userPrompt_manga

DeepSeek:
  游戏: systemPrompt_game, userPrompt_game
  漫画: systemPrompt_manga, userPrompt_manga
```

### 7. 向后兼容

- 现有配置中 `mangaSystemPrompt` 和 `mangaUserPrompt` 为空时，使用默认值
- 首次启动时自动为现有提供商填充默认漫画提示词
- 保存配置时，如果提示词与默认相同，则存储空字符串（节省空间）
- 加载配置时，如果提示词为空字符串，则使用默认值

## 实现步骤

### 阶段1：数据结构变更

1. 修改 `OpenAIProviderConfig` 添加漫画模式提示词字段
2. 修改 `BuiltInProviderMod` 添加漫画模式提示词字段
3. 修改 `ConfigurationStorage` 的保存/加载逻辑
4. 修改 `BuiltinProviders` 添加默认漫画提示词

### 阶段2：UI变更

1. 修改 `fragment_openai_api.xml` 添加TabLayout
2. 修改 `OpenAIText.kt` 添加Tab切换逻辑
3. 实现提示词的保存和加载
4. 实现重置为默认功能

### 阶段3：翻译流程变更

1. 修改 `FloatingBallService` 使用游戏模式提示词
2. 修改 `MangaFloatingService` 使用漫画模式提示词
3. 修改 `OpenAITranslation` 支持漫画模式批量翻译

### 阶段4：测试

1. 测试游戏模式翻译
2. 测试漫画模式翻译
3. 测试提示词配置保存/加载
4. 测试重置为默认功能
5. 测试向后兼容性

## 文件变更列表

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `CustomStorage.kt` | 修改 | 添加漫画模式提示词字段 |
| `BuiltinProviders.kt` | 修改 | 添加默认漫画提示词 |
| `OpenAIText.kt` | 修改 | 添加Tab切换和提示词配置 |
| `fragment_openai_api.xml` | 修改 | 添加TabLayout |
| `FloatingBallService.kt` | 修改 | 使用游戏模式提示词 |
| `MangaFloatingService.kt` | 修改 | 使用漫画模式提示词 |
| `OpenAITranslation.kt` | 修改 | 支持漫画模式批量翻译 |
| `values/strings.xml` | 修改 | 添加Tab标题字符串 |
| `values-zh/strings.xml` | 修改 | 添加Tab标题字符串 |
