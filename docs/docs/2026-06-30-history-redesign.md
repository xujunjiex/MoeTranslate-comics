# 历史记录页面重构设计

日期: 2026-06-30

---

## 概述

### 目标

将历史记录页面从单一视图重构为**默认视图 + 管理视图**双视图架构。
管理视图支持重新翻译、进程组下载、多尺寸管理等功能。

### 核心原则

1. 重翻必须在漫画翻译进程（MangaFloatingService）启动后执行
2. HistoryFragment 只做裁剪发起，OCR/翻译/渲染全交给 Service
3. 不新增独立翻译管道，复用 Service 现有能力
4. Service 通过 `isProcessing` 串行化所有翻译任务

---

## 一、UI 布局

```
┌─────────────────────────────────────────┐
│  [游戏] [漫画]          [⚙️设置] [🔄刷新]│  ← Tab + 按钮
├─────────────────────────────────────────┤
│  [默认视图]  [管理视图]                   │  ← 视图切换
├─────────────────────────────────────────┤
│  (管理视图时) [引擎: ▼] [翻译: ▼]         │  ← 重翻引擎选择
├─────────────────────────────────────────┤
│                                         │
│  历史记录卡片列表                          │
│                                         │
└─────────────────────────────────────────┘
```

### 视图切换

- 默认视图 = 当前"按修改排序"行为
- 管理视图 = 当前"按创建排序"行为 + 新增功能
- 原设置弹窗中"排列方式"选项移除，由视图 Tab 取代
- 存储 key: `history_view_mode` ("default" | "manage")

---

## 二、管理视图 — 引擎选择

管理视图顶部一行简化引擎选择：

```
[引擎: PP-OCRv5 ▼] [翻译: OpenAI(火山) ▼]
```

### 引擎下拉（3 个固定搭配）

| 选项 | DetEngine | OcrEngine |
|------|-----------|-----------|
| PP-OCRv5 | PP_OCR_V5 | PP_OCR_V5 |
| manga-ocr | 无（独立模式）| MANGA_OCR |
| ML Kit | MLKIT | MLKIT |

### 翻译下拉

列出所有已配置的 OpenAI 提供商。

### 存储

| prefs key | 类型 | 说明 |
|-----------|------|------|
| `history_ocr_engine` | String | PP_OCR_V5 / MANGA_OCR / MLKIT |
| `history_openai_provider_index` | String | 提供商列表索引 |

独立于悬浮窗配置，互不影响。

---

## 三、管理视图 — 卡片结构

```
┌─────────────────────────────────────────┐
│  [📷原文/译文]                    [🔄x2]│  ← 切换按钮 + 重翻角标
│  ┌─────────────────────────────────┐   │
│  │                                 │   │
│  │           缩略图                  │   │
│  │                                 │   │
│  └─────────────────────────────────┘   │
│  尺寸: 1080×1920 ▼                     │  ← 多尺寸下拉
│  源语言 → 目标语言    翻译器名            │
│  [重新翻译] [删除此尺寸]                 │  ← 操作按钮
└─────────────────────────────────────────┘
```

### 原文/译文切换

- 两个视图的漫画卡片都有此按钮
- 点击后在原图和渲染结果图之间切换缩略图
- 存储为临时 UI 状态，不持久化
- 游戏翻译卡片不显示（无图片）

### 重翻角标

- 仅管理视图显示
- 右上角 `🔄xN`，N = 该 pHash 组内 `isRetranslated=true` 的记录数
- N=0 时不显示角标

### 多尺寸处理

- 同 pHash 多变体时，下拉选择器切换当前显示的变体
- 切换时同步更新：缩略图、尺寸信息、翻译结果文本
- 原文/译文切换基于当前选中变体

---

## 四、重新翻译

### 入口

- 管理视图每条记录有"重新翻译"按钮
- 悬浮窗翻译进行中（`isProcessing=true`）时按钮置灰

### 流程

```
点"重新翻译" → 底部弹窗：[用当前裁剪] [重新裁剪]
  │
  ├─ 用当前裁剪: 加载原图 → 自动套用该变体的 cropRect → 用户可微调 → 确认
  │
  └─ 重新裁剪: 加载原图 → 无预设裁剪框 → 用户手动框选 → 确认
  │
确认 → HistoryFragment 发 LocalBroadcast:
  action: RETRANSLATE_REQUEST
  extras:
    originalImagePath,
    cropLeft, cropTop, cropRight, cropBottom,
    ocrEngine (String), openaiProviderIndex (Int)
  │
MangaFloatingService 收到:
  ├─ isProcessing == true → 忽略，回复 COMPLETE (success=false, "翻译进行中")
  │
  └─ isProcessing == false:
      isProcessing = true
      → 加载原图 → cropBitmap(cropRect)
      → 用指定 OcrEngine 做 OCR（创建临时引擎实例）
      → 用指定 providerIndex 创建临时翻译器实例
      → 翻译 → 渲染
      → saveToCache(isRetranslated=true)
      → isProcessing = false
      → 发 RETRANSLATE_COMPLETE 广播
  │
HistoryFragment 收到完成广播 → 刷新列表
```

### 裁剪界面

- Fragment 内全屏显示原图
- 复用 CropView 的框选逻辑（setRect/mRect/onConfirmCrop）
- 确认后计算 cropRect（绝对像素坐标），发广播
- 用当前裁剪：cropRect 直接从 PageCacheEntity 的 cropLeft/Top/Right/Bottom 读取

### 结果

| | 用当前裁剪 | 重新裁剪 |
|---|---|---|
| croppedRect 来源 | PageCacheEntity 的 cropLeft/Top/Right/Bottom | 用户手动框选 |
| 替换旧变体 | ✅ | ❌ |
| 生成新变体 | ❌ | ✅ |
| isRetranslated | true | true |
| 同 pHash 组 | 保持 | 加入 |

---

## 五、进程组下载

- 管理视图每个进程组有下载按钮
- 流程：
  1. 点击下载
  2. 如果组内某 pHash 记录有多尺寸变体 → 弹窗让用户选保留哪个尺寸
  3. 所有单尺寸 + 用户选择的多尺寸 → 打包 ZIP
  4. 通过 ShareSheet 或保存到相册
- ZIP 内容：仅渲染结果图（JPEG）

---

## 六、数据库

### HistoryEntity 新增字段

```kotlin
@ColumnInfo(name = "original_image_path")
val originalImagePath: String? = null,

@ColumnInfo(name = "is_retranslated", defaultValue = "0")
val isRetranslated: Boolean = false,
```

### PageCacheEntity 新增字段

```kotlin
// 取代现有的 cropWidth/cropHeight（仅宽高无法做"用当前裁剪"重翻）
@ColumnInfo(name = "crop_left", defaultValue = "0")
val cropLeft: Int = 0,
@ColumnInfo(name = "crop_top", defaultValue = "0")
val cropTop: Int = 0,
@ColumnInfo(name = "crop_right", defaultValue = "0")
val cropRight: Int = 0,
@ColumnInfo(name = "crop_bottom", defaultValue = "0")
val cropBottom: Int = 0,
```

旧 `cropWidth`/`cropHeight` 废弃，Migration 保留旧列不动，新增 4 列。旧数据为 0（表示无裁剪记录）。

DB 版本: 8 → 9。

### HistoryEntry 新增字段

```kotlin
val originalImagePath: String? = null,
val isRetranslated: Boolean = false,
```

### 原始截图保存（TranslationCacheManager.saveToCache）

新增参数 `originalBitmap: Bitmap?`：

```kotlin
// 调用处（MangaFloatingService）：
// 截图后、裁剪前，saveOriginalImage(原图) → originalImagePath
// saveToCache 传入 originalImagePath

// 如果传入了 originalBitmap，saveToCache 内部：
// 1. 保存为 JPEG 到 historyDir
// 2. 在 HistoryEntity 中记录 originalImagePath
```

仅漫画翻译写入，游戏翻译保持 null。

---

## 七、Broadcast 协议

### 重翻请求

```
发送方: HistoryFragment
接收方: MangaFloatingService
action: "com.moe.moetranslator.RETRANSLATE_REQUEST"

extras:
  originalImagePath: String    // 原图绝对路径
  cropLeft: Int                // 裁剪左边界（像素）
  cropTop: Int                 // 裁剪上边界
  cropRight: Int               // 裁剪右边界
  cropBottom: Int              // 裁剪下边界
  ocrEngine: String            // "PP_OCR_V5" / "MANGA_OCR" / "MLKIT"
  openaiProviderIndex: Int     // OpenAI 提供商列表索引
```

### 重翻完成

```
发送方: MangaFloatingService
接收方: HistoryFragment
action: "com.moe.moetranslator.RETRANSLATE_COMPLETE"

extras:
  success: Boolean
  historyId: Long              // 新记录的 historyId（success=true 时有效）
```

---

## 八、涉及文件

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `HistoryEntity.kt` | 修改 | 新增 2 字段 |
| `PageCacheEntity.kt` | 修改 | 新增 cropLeft/cropTop/cropRight/cropBottom（替代仅存宽高的 cropWidth/cropHeight）|
| `TranslationCacheManager.kt` | 修改 | saveToCache 新增 originalBitmap、cropRect 参数 |
| `HistoryFragment.kt` | 大改 | 视图切换、管理视图 UI、裁剪、广播 |
| `MangaFloatingService.kt` | 修改 | 接收广播、retranslate 方法 |
| `fragment_history.xml` | 修改 | 视图 Tab、引擎选择器、裁剪容器 |
| `strings.xml` | 修改 | 新增文案 |
| `TranslationHistoryDatabase.kt` | 修改 | DB 版本 8→9、Migration |

---

## 九、不在此范围

- 文件夹批量翻译功能（后续独立实现）
- 游戏翻译的重翻
- 跨设备同步
- 缓存关闭时视图行为不变（两个视图都为空）
