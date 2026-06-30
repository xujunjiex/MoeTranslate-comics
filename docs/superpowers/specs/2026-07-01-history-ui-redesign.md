# 历史记录管理视图 UI 重构 + 管线分离设计

日期: 2026-07-01

---

## 概述

重构历史记录页面的管理视图 UI，统一卡片布局，分离重翻引擎配置。

### 核心原则

1. 两个视图共用同一套卡片布局和显示模式（列表/大/中/小）
2. 操作按钮（重新翻译、删除）集中在全屏页，卡片只展示
3. 下载按钮在进程组 header，不在卡片或全屏页
4. 游戏 tab 不显示视图切换
5. 引擎选择器独立存储，不受悬浮窗配置影响
6. 重翻走 MangaFloatingService 广播，翻译 API 用 Service 当前配置

---

## 一、卡片布局（两个视图统一）

```
┌─────────────────────────────────┐
│  [原/译]        [🔄]  [N尺寸]   │  ← 原图/译文切换 + 重翻标记 + 多尺寸徽章
│  ┌─────────────────────────┐   │
│  │                         │   │
│  │      缩略图（可点击）     │   │
│  │                         │   │
│  └─────────────────────────┘   │
│  pHash                          │
│  翻译器名              时间      │
└─────────────────────────────────┘
```

- 删除 `item_history_manga_manage.xml`，两个视图都用 `item_history_manga.xml`
- `item_history_manga.xml` 新增 🔄 重翻标记（TextView，默认 gone，仅管理视图 bind 时显示）
- 🔄 标记：仅管理视图显示，`isRetranslated=true` 即有，不记次数
- "N尺寸" 徽章：variatIds 数量 > 1 时显示
- 列表/大/中/小四种显示方式对两个视图同时生效
- 点击缩略图 → 打开全屏页

### 两个视图的区别

| | 默认视图 | 管理视图 |
|---|---|---|
| 排序依据 | updatedAt | createdAt |
| 分组依据 | lastSessionId | sessionId |
| 🔄 重翻标记 | 不显示 | 显示 |
| 引擎选择器 | 不显示 | 显示（仅漫画 tab） |
| 进程组下载 | 不显示 | 显示 |

---

## 二、全屏页（点击缩略图进入）

```
┌─────────────────────────────────┐
│  [原图/译文切换]          [🔄]  │
│  ┌─────────────────────────┐   │
│  │                         │   │
│  │      大图（可缩放）       │   │
│  │                         │   │
│  └─────────────────────────┘   │
│                                 │
│  尺寸: 1080×1920 ▼             │  ← 多尺寸时显示
│  翻译器名   时间    pHash        │
│                                 │
│  [重新翻译]       [删除此尺寸]    │
└─────────────────────────────────┘
```

- 尺寸下拉：切换当前显示的变体（同步更新大图、翻译结果文本）
- 重新翻译 → 底部弹窗"用当前裁剪/重新裁剪" → CropFragment → 确认 → 广播
- 删除此尺寸 → 确认弹窗 → 删除该变体。只剩一个变体时删除整组并退出全屏页
- 只有单个变体时尺寸下拉隐藏

---

## 三、进程组下载

```
┌─────────────────────────────────┐
│  今天                           │
│  18:00 - 18:30 (3张)    [⬇]    │
└─────────────────────────────────┘
```

流程：
1. 遍历组内所有记录
2. 如果有任意记录存在多个尺寸变体 → 弹窗：

```
该进程组包含多个尺寸的翻译结果
[A) 全部下载]     ← 所有变体都打包进 ZIP
[B) 去选择保留的尺寸] ← 不下载，返回管理视图
```

3. A：所有渲染结果图打包 ZIP → 通过 ShareSheet 分享
4. ZIP 文件命名 `{sessionId}.zip`，内部文件名 `{index}.jpg`

使用 `java.util.zip.ZipOutputStream`。

---

## 四、游戏 tab

- 游戏 tab 始终隐藏 `viewModeTabLayout`
- 始终按默认视图行为（按修改时间排序、不显示引擎选择器、不显示 🔄）

---

## 五、引擎选择器

- 仅管理视图 + 漫画 tab 时显示
- 只保留引擎选择，无翻译选择

```
[引擎: PP-OCRv5 ▼]
```

| 选项 | OCR 引擎 |
|------|---------|
| PP-OCRv5 | PP_OCR_V5 |
| manga-ocr | MANGA_OCR |
| ML Kit | MLKIT |

存储 key: `history_retranslate_engine` (String)，独立于悬浮窗 `Manga_Det_Engine` / `Manga_Ocr_Engine`。

---

## 六、重新翻译

通过 MangaFloatingService LocalBroadcast 执行。

### 广播 extras（简化）

```
action: RETRANSLATE_REQUEST
extras:
  originalImagePath: String
  cropLeft, cropTop, cropRight, cropBottom: Int
  ocrEngine: String  ("PP_OCR_V5" / "MANGA_OCR" / "MLKIT")
```

翻译 API 不传——Service 使用自身当前配置（`translatorText`）。

### Service 侧

- 收到广播 → 检查 `isProcessing`
  - true → 回复 COMPLETE(success=false, "翻译进行中，请稍后")
  - false → isProcessing=true → 执行 → isProcessing=false → 回复 COMPLETE
- 执行时保存 `config.detEngine`/`ocrEngine`，临时切换到请求指定的引擎，finally 恢复

---

## 七、错误处理

- HistoryFragment 发广播前用 `ServiceUtils.isServiceRunning(MangaFloatingService)` 检查
  - 不在 → 弹 Toast "请先启动漫画翻译"
  - 在 → 发广播
- Service 回复 COMPLETE(success=false) → Toast 显示 errorMessage
- Service 回复 COMPLETE(success=true) → Toast "重新翻译完成" + 刷新列表

---

## 八、数据库

**无需变更。** 现有字段已满足需求。

- `originalImagePath`: 原图路径
- `isRetranslated`: 是否重翻过（true/false，不计数）
- `cropLeft/Top/Right/Bottom`: 裁剪区域

---

## 九、涉及文件

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `item_history_manga_manage.xml` | **删除** | 管理视图不再用独立布局 |
| `item_history_manga.xml` | 不变 | 和现在一样，已有原/译切换和尺寸徽章 |
| `fragment_history.xml` | 修改 | 引擎选择器简化（去掉翻译 Spinner）；viewModeTabLayout 在游戏 tab 隐藏 |
| `HistoryFragment.kt` | 修改 | 引擎选择器简化；游戏 tab 逻辑；全屏页跳转 |
| `HistoryMangaAdapter.kt` | 修改 | 删管理视图特殊代码；全屏页回调 |
| `HistoryMangaGroupAdapter.kt` | 修改 | 删 isManageView 标志；管理视图进程组下载 |
| `MangaViewerActivity.kt` | 修改 | 新增变体 Spinner + [重新翻译] [删除此尺寸] 按钮；原图/译文切换 |
| `MangaFloatingService.kt` | 修改 | broadcast extras 去掉 openaiProviderIndex |
| `strings.xml` (中/英) | 修改 | 精简文案 |

---

## 十、不在此范围

- 文件夹批量翻译
- 游戏翻译重翻
- 翻译 API 独立选择（管理视图用悬浮窗当前配置）
