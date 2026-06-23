# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 提供项目上下文。

## 项目概述

星译（StarFlow）— Android 翻译应用，支持 Android 11+（API 29+）。包含两个核心功能：游戏翻译（截图 OCR + 翻译 API）和漫画翻译（气泡检测 + OCR + 翻译 + 竖排渲染）。

## 目录规范

- **`.reference/`** — 参考项目，只读，已 gitignore。用于克隆第三方开源项目作为代码参考。
- **`tools/`** — 测试模型和脚本，已 gitignore。用于本地测试转换后的模型。
- **`docs/docs/`** — 文档内容（提交到 GitHub），`docs/` 其余文件已 gitignore。
- 禁止在项目根目录或其他非标准位置放置模型文件或参考项目。

## 构建命令

```bash
# 构建 debug APK
./gradlew assembleDebug

# 构建 release APK
./gradlew assembleRelease

# 安装 debug APK 到设备
adb install app/build/outputs/apk/debug/app-debug.apk

# 清理构建
./gradlew clean assembleDebug

# 实时查看应用日志
adb logcat --pid=$(adb shell pidof com.moe.moetranslator)

# 指定设备安装（多设备时）
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

# 查看连接设备
adb devices
```

环境要求：JDK 17、Android SDK（compileSdk 35）、NDK 25.2.9519653、CMake 3.22.1。

**Windows 注意：** `adb` 命令需要通过 PowerShell 调用完整路径：
```powershell
# 安装 APK
& "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk

# 监控日志（按 tag 过滤）
& "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat --pid=$(& "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell pidof com.moe.moetranslator) | Select-String -Pattern "MangaFloatingService|OpenAITranslation|TranslateBridge|FloatingBallService"
```

**首次克隆后**需创建 `local.properties`（已在 .gitignore 中）：
```properties
sdk.dir=C:/Users/<username>/AppData/Local/Android/Sdk
```
注意路径用**正斜杠** `/`，不要用反斜杠 `\`。

## 架构

**包结构** (`app/src/main/java/com/moe/moetranslator/`):

- `translate/` — 游戏翻译引擎：`FloatingBallService`（主服务）、`ScreenShotAccessibilityService`（截屏）、`OCRTextRecognizer`（ML Kit OCR）、`TranslationTextAPI`/`TranslationPicAPI`（翻译接口）、`AutoTranslateEngine`（自动翻译状态机）、`GameOcrEngine`（游戏 OCR 封装）、`GameDebugOverlay`（调试浮窗）、`TranslationResultView`（翻译结果容器，含锁定/关闭按钮）、`CropView`（框选视图，含内置确认按钮）
- `manga/` — 漫画翻译引擎：气泡检测 + OCR + 翻译 + 竖排文字渲染
- `bridge/` — 桥接层：`OCRBridge`、`TranslateBridge`、`ScreenshotBridge`
- `me/` — 设置和 API 配置界面：`PersonalizationConfig`（个性化设置）、`APIConfig`（API 配置）、`TranslationMode`（翻译模式）、`AboutMe`（关于页面）、`Developer`（开发者选项）、`FAQPage`（常见问题，6 条 FAQ）
- `launch/` — 首次启动引导
- `utils/` — 工具类：`Constants`（枚举定义）、`CustomPreference`（配置封装）、`LogCollector`（日志收集）、`PixelCompare`（像素比较）、`UiUtils`（Toast 统一）、`ServiceUtils`（服务状态检测）、`UpdateChecker`（检查更新）
- `data/` — Room 数据库、`TranslationCacheManager`、`HistoryEntity`/`PageCacheEntity`
- `ui/history/` — 历史记录 UI：`HistoryFragment`、`HistoryGroupAdapter`（游戏分组）、`HistoryMangaGroupAdapter`（漫画分组）、`HistoryGameAdapter`、`HistoryMangaAdapter`、`MangaViewerActivity`（全屏图片浏览+译文详情）

**翻译 API 实现** (`app/src/main/java/translationapi/`):
每个子目录实现 `TranslationTextAPI` 接口：`openaitranslation/`、`bingtranslation/`、`mlkittranslation/`、`nllbtranslation/`、`niutrans/`、`volctranslation/`、`deepltranslation/`、`baidutranslation/`、`tencentcloud/`、`azuretranslation/`、`customtranslation/`

**关键接口：**
- `TranslationTextAPI.getTranslation(text, sourceLanguage, targetLanguage, callback)` — 文本翻译
- `TranslationPicAPI.getTranslation(bitmap, sourceLanguage, targetLanguage, callback)` — 图片翻译

**截图流程：** `ScreenShotAccessibilityService` → `ScreenshotManager.screenshotFlow`（SharedFlow）→ `FloatingBallService` 接收处理

**翻译提示词架构：**

提示词仅对 OpenAI 兼容 API 生效（火山/智谱/DeepSeek/通义千问/用户自建），非 OpenAI API（Volc/DeepL/Baidu/Azure/腾讯/Bing/Niutrans/NLLB）为纯机器翻译，不接受提示词配置。

```
定义层（源头）
├── BuiltinProviders.kt    — 内置 API 默认提示词（DEFAULT_SYSTEM_PROMPT 等 6 个常量）
└── OpenAIText.kt          — 用户自建 API 默认提示词（defaultSystemPrompt + fallbackManga*）

存储层
└── CustomStorage.kt       — 内置 API: BuiltInProviderMod（只存差异）; 用户 API: OpenAIProviderConfig（全量）

读取层
└── ConfigurationStorage.loadAllProviders() → applyMod()
    内置: systemPrompt = mod.systemPrompt ?? builtin.defaultSystemPrompt
    用户: 直接读取存储值

使用层
├── 游戏模式 FloatingBallService → OpenAITranslation(systemPrompt=provider.systemPrompt)
└── 漫画模式 MangaFloatingService → OpenAITranslation(systemPrompt=provider.mangaSystemPrompt.ifEmpty{default})

发送层（OpenAITranslation.kt）
├── buildSystemPrompt()  — 上下文开启时前缀 "根据上下文剧情进行翻译..."
├── buildUserPrompt()    — 替换 usefromlang/usetolang/usesourcetext 占位符
└── buildRequestBody()   — messages: [system, (历史user/assistant对), user]
```

游戏/漫画提示词分离：`provider.systemPrompt`（游戏）和 `provider.mangaSystemPrompt`（漫画）独立存储、独立配置。漫画模式额外支持续写格式控制（见下文）。

**聚合 AI 翻译续写模式（漫画格式控制）：**
漫画翻译使用各厂商续写模式（assistant prefill）硬约束输出 `[1] 译文` 格式：
- 火山引擎：`CONTINUATION_STANDARD`，无额外参数
- 通义千问：`CONTINUATION_PARTIAL`，`partial: true`
- DeepSeek：`CONTINUATION_PREFIX`，`prefix: true` + **beta 端点** `/beta/chat/completions`（⚠️ beta 版本，后续可能变更）
- 智谱AI：`CONTINUATION_JSON`，`response_format: json_object`，返回 JSON 格式 `{"translations": [...]}`
- `OpenAITranslation` 根据 `continuationType` 参数处理不同续写方式

**内容安全审查：** 各 API 平台可能拦截敏感内容翻译（错误码 `data_inspection_failed`，HTTP 400）。不同平台审查阈值不同，被拦截时换平台或换模型。

**AI 上下文（游戏模式）：** `FloatingBallService` 维护 `LinkedList<Pair<String, String>>` 存储历史翻译对（原文, 译文）。开启后系统提示词追加"根据上下文剧情进行翻译"，messages 中插入历史 user/assistant 对。用户可配置轮数（1-10，默认 5）。仅 OpenAI 兼容 API 生效。设置项：`game_context_enabled`（开关）、`game_context_count`（轮数，存为 String）。

**AI 上下文（漫画模式）：** 正常漫画翻译不使用上下文。仅增量渲染的两批之间使用上下文（`forceContext=true`），翻译完后回滚，不污染后续页面的上下文历史。

**配置存储：** `CustomPreference` 单例封装 `SharedPreferences`。API 密钥通过 `KeystoreManager` 加密存储。

**UI：** 传统 Android Views + ViewBinding（非 Jetpack Compose）。导航使用 Navigation Component。

**构建模块：** `:app`。原生代码通过 CMake 构建（`app/src/main/cpp/`）。

## 模型管理

### 当前使用的模型

| 模型 | 用途 | 大小 | 来源 | 存储位置 |
|------|------|------|------|----------|
| **PP-OCRv5 det** | 文字区域检测 | ~4.6MB | RapidAI/RapidOCR | **assets/ 内置** |
| **PP-OCRv5 cls** | 方向分类 | ~1MB | RapidAI/RapidOCR | **assets/ 内置** |
| **PP-OCRv5 rec zh** | 中日英混合识别 | ~16MB | RapidAI/RapidOCR | **assets/ 内置** |
| **PP-OCRv5 rec en** | 英文专用识别 | ~7.5MB | ModelScope | getExternalFilesDir/ 可选下载 |
| **PP-OCRv5 rec ko** | 韩文专用识别 | ~12.9MB | ModelScope | getExternalFilesDir/ 可选下载 |
| **PP-OCRv5 rec ru** | 俄文/西里尔文字识别 | ~7.7MB | ModelScope | getExternalFilesDir/ 可选下载 |
| **CTD** | 文字区域检测 | ~94MB | GitHub releases | getExternalFilesDir/ 下载 |
| **RT-DETR v2** | 文字/气泡检测 | ~11MB | HuggingFace | getExternalFilesDir/ 下载 |
| **manga-ocr** | 竖排日文识别 | ~460MB/~135MB | HuggingFace | getExternalFilesDir/ 下载 |

PP-OCRv5 核心模型（det + cls + rec_zh + 所有字典）内置在 assets 中，约 22MB。可选 rec 模型（en/ko/ru）需用户从模型管理页面下载。

### PP-OCRv5 模型下载地址（ModelScope）

基础 URL: `https://modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/rec/`

| 文件名 | ModelScope 文件名 | 大小 |
|--------|-------------------|------|
| rec_en.onnx | `en_PP-OCRv5_rec_mobile.onnx` | ~7.5MB |
| rec_ko.onnx | `korean_PP-OCRv5_rec_mobile.onnx` | ~12.9MB |
| rec_ru.onnx | `cyrillic_PP-OCRv5_rec_mobile.onnx` | ~7.7MB |

字典文件（rec_en_dict.txt / rec_ko_dict.txt / rec_ru_dict.txt）内置在 assets 中，无需下载。

### PP-OCRv5 语言 fallback 逻辑

`PPOcrV5Engine.resolveRecLang()` 处理语言选择：
- **ZH/JA**：始终可用（内置 rec_zh 模型，支持中日英混合识别）
- **EN**：已下载 rec_en → 用 EN 模型；未下载 → fallback 到 ZH 模型（ch 也支持英文）
- **KO**：已下载 rec_ko → 用 KO 模型；未下载 → 返回提示"请下载韩文模型"
- **RU**：已下载 rec_ru → 用 RU 模型；未下载 → 返回提示"请下载俄文模型"

### 下载管理器

```
ModelDownloadManager          # 统一 HTTP 下载器（断点续传、重试、进度回调）
├── CTDModelManager           # CTD 模型（单文件下载，~94MB）
├── RTDetrModelManager        # RT-DETR-V2 模型（单文件下载，~11MB）
├── MangaOcrDownloadManager   # manga-ocr 模型（逐文件下载，支持 FULL/V2025 版本）
└── PPOcrModelManager         # PP-OCRv5 可选模型管理（en~7.5MB/ko~13MB/ru~7.7MB）
```

**关键规则：**
- **所有日志用 `LogCollector`**，不用 `Log.d/i/e`
- **下载前检查文件是否存在**，避免重复下载
- **404/403 不重试**，其他错误最多重试 3 次
- **大文件用 `.part` 后缀**，下载完成再 rename

### 普通/高级模式

悬浮窗菜单通过 `Manga_Advanced_Mode` 偏好切换（关于页面开关）：
- **普通模式**（默认）：固定搭配循环切换
  - MLKit → PP-OCRv5 → manga-ocr → MLKit
  - **跳过不可用模型**：manga-ocr 未下载时自动跳过，不会卡住
- **高级模式**：自由搭配检测器+识别器，菜单分两个选项

## 漫画模块

**核心文件：** `MangaFloatingService.kt`（主服务）、`DetectionBridge.kt`（检测桥接）、`CTDDetector.kt`/`CTDPostProcessor.kt`（CTD 检测）、`ComicBubbleDetector.kt`（RT-DETR-V2 检测）、`PPOcrV5Engine.kt`（PP-OCRv5 det+cls+rec）、`MangaOcrBridge.kt`（manga-ocr）、`BoxMerger.kt`（合并）、`TextLineMerger.kt`（识别后合并）、`OverlayRenderer.kt`（渲染）
**工具类：** `GeometryUtils.kt`（凸包、点在多边形等几何算法）、`OnnxUtils.kt`（ONNX 张量提取、资源拷贝）

**检测引擎（DetEngine）：**
- `MLKIT(0)` — ML Kit 检测+识别一体化
- `CTD(1)` — CTD 检测 + 指定 OCR 引擎
- `RT_DETR_V2(3)` — RT-DETR-V2 气泡/文字检测
- `PP_OCR_V5(4)` — PP-OCRv5 独立 det+cls+rec 全流水线（**默认**）

**OCR 引擎（OcrEngine）：**
- `MLKit(0)`、`MangaOcr(1)`、`PPOcrV5(4)`（**默认**）

**合并机制：**

| 检测器 + 识别器 | 前合并 (BoxMerger) | 后合并 (BubbleDetector) | 说明 |
|---|---|---|---|
| CTD + PPOcrV5 | ✅ 分组 | ❌ | 逐个 QuadBox 透视裁剪识别，按组拼接文字 |
| CTD + MLKit/MangaOcr | ✅ 合并 | ❌ | 对合并区域 AABB 裁剪识别 |
| RT-DETR-V2 + 任意 | ❌ | ❌ | 检测器直接输出气泡级结果 |
| MLKit 独立 | ❌ | ✅ | 行级文字块 → BubbleDetector 合并成气泡 |
| PP-OCRv5 独立 | ❌ | ✅ | 行级检测框 → BubbleDetector 合并成气泡 |

**单字符噪声过滤：** `textBlocksToBubbleRegions` 过滤单字符纯标点（OTHER_PUNCTUATION、DASH_PUNCTUATION、START/END_PUNCTUATION、MATH_SYMBOL、OTHER_SYMBOL），避免标点符号被当作独立气泡翻译。

**翻译流程：** 截图 → 检测 → OCR → 气泡合并（按需）→ 翻译（每气泡并行）→ 覆盖渲染

**增量渲染（分批 OCR+翻译）：**
超过 6 个气泡时自动分批处理，首批翻译完立即渲染，减少用户等待时间。
- 触发条件：`Incremental_Render` 开启 + 气泡数 > 6
- 支持组合：RT-DETR-V2/MangaOcr、CTD/MangaOcr、PP-OCRv5 独立模式
- 流程：检测 → 分批(2/5+3/5) → OCR第一批 → 翻译第一批+OCR第二批并行 → 渲染第一批 → 翻译第二批 → 最终渲染
- 上下文仅批次间使用：`forceContext=true` 强制开启，第二批能看到第一批译文；两批翻译完后回滚 contextHistory，不污染后续页面
- 正常漫画翻译不使用上下文（`forceContext=false` 时直接关闭）
- MangaOcr encoder 是批处理瓶颈（~3s），分批可提前显示部分结果

**Debug 系统：** 关于页面可开启 4 个独立 debug 开关（CTD / RT-DETR-V2 / MLKit / PP-OCRv5），按当前 `config.detEngine` 决定走哪条 debug 路径。调试菜单为二级结构（一级标题带图标，二级开关无图标）。

**PP-OCRv5 参数调节：**
用户可通过调试面板滑块实时调整 5 个参数（存 SharedPreferences，`PPOcrV5Engine.refreshParams()` 每次 OCR 前读取）：

| 参数 | 键名 | 默认值 | 范围 | 作用 |
|------|------|--------|------|------|
| 检测置信度 | `ppocr_det_box_thresh` | 0.3 | 0.01-0.5 | box_thresh，低于此值的检测框被丢弃 |
| 扩展比例 | `ppocr_det_unclip_ratio` | 1.6 | 1.0-3.0 | unclip 扩展，越大检测框越宽松 |
| 识别置信度 | `ppocr_text_score_thresh` | 0.5 | 0.1-0.9 | text_score_thresh，低于此值的识别结果被丢弃 |
| 大框过滤 | `ppocr_large_box_enabled` | false | 开/关 | 过滤占图片比例过大的检测框 |
| 丢弃比例 | `ppocr_large_box_ratio` | 0.6 | 0.3-0.8 | 大框过滤阈值（宽/高/面积占图片比例） |

- `DET_THRESH`（二值化阈值）= 0.1f，硬编码不可调，影响所有检测路径
- `DET_MAX_CANDIDATES` = 100（连通域上限）
- 调试面板默认折叠，含图例说明（绿=检测框、青=合并区、红虚线=检测丢弃、橙虚线=识别丢弃）

**倾斜文字处理：**
PP-OCRv5 检测框可能倾斜（QuadBox 4 顶点非正交），全链路处理：
- **角度检测**：`atan2(topDy, topDx)` 计算顶部边与水平线夹角，±3° 内视为正交（angle=0）
- **方向判断**：用 QuadBox 真实边长（左高 vs 顶宽×1.5），不用 AABB（倾斜时 AABB 会误判）
- **fontSize**：用真实边长（横排=leftLen，竖排=topLen），不用 AABB 短边（倾斜时 AABB 会放大）
- **合并**：`TextLineMerger.canMergeTilted()` — 角度差 < 3° 时用中心距离+沿倾斜角投影判断，perpDist < charSize×1.5，alongDist < charSize×3
- **渲染**：`canvas.rotate(angle, centerX, centerY)` 旋转背景+文字，正常 overlay 和调试 overlay 均支持
- **增量路径**：`recResultsToTextLines` 只有 AABB（裁剪后），无角度信息，沿用 AABB 启发式

**屏幕尺寸获取（`getScreenSize()`）：**
- `MangaFloatingService` 和 `FloatingBallService` 都有 `getScreenSize()` 方法
- 使用 `Display.getRealSize()` 获取真实物理像素尺寸（包含系统栏区域）
- `resources.displayMetrics` 在 Service 上下文中可能返回竖屏尺寸，不能用于横屏
- `currentWindowMetrics.bounds` 返回窗口内容区域（不含系统栏），也不能用
- overlay 窗口参数用 `getScreenSize()` 的值 + `FLAG_LAYOUT_IN_SCREEN` + `FLAG_LAYOUT_NO_LIMITS`

**全屏/调试 overlay 定位：**
- 全屏和调试模式不能用 `MATCH_PARENT`，用 `getScreenSize()` 获取真实尺寸
- 配合 `FIT_XY` + `FLAG_LAYOUT_NO_LIMITS`

**受限区域截图检测：**
- 系统 `takeScreenshot()` API 对受限区域（DRM/安全应用）返回 `onSuccess` + 全黑 bitmap，不会调用 `onFailure`
- `isRestrictedScreenshot()` 在截图收集阶段检测：全黑（95%+ 像素亮度 < 16）或低方差（纯色覆盖层，方差 < 50）
- 检测到后提示用户"该区域无法截图，可能是受限内容"

**统一框选确认按钮：**
- 游戏翻译和漫画翻译共用 `CropView` 内置确认按钮
- 按钮绘制在框选框底部，跟随框选区域实时移动
- 框选初始位置：`CropView.setRectCentered()` 延迟到布局完成后用 view 自身尺寸计算居中（游戏 90%×35%，漫画 80%×60%）
- 框选触点响应区域：50px 半径（`POINT_RADIUS = 2500`）

## 自动翻译

### 游戏翻译（像素驱动）

**核心文件：** `AutoTranslateEngine.kt`（状态机）、`FloatingBallService`（主服务）、`GameOcrEngine.kt`（OCR 封装）、`PixelCompare.kt`（像素比较）、`GameDebugOverlay.kt`（调试浮窗）

**悬浮窗语言切换：**
游戏和漫画模式的悬浮菜单都支持运行时切换源语言。
- 循环切换：ja → en → zh → ko → ru → ja
- 跳过未下载的 OCR 模型（PP-OCRv5 的 KO/RU 需检查是否已下载）
- 自动翻译时禁用切换，显示提示
- 切换后不关闭菜单，显示新的语言名称
- 实时生效：切换后下次翻译使用新语言
- 主页语言切换限制已移除，翻译运行中也可在主页切换语言

**状态机：**
```
IDLE（跳过OCR）──像素变化──→ CHANGED ──稳定1帧──→ STABLE_1 ──稳定2帧──→ STABLE_2（触发OCR）→ IDLE
```
- IDLE：翻译完成后进入，像素不变则跳过 OCR（节省性能）
- CHANGED：像素和上帧不同，重新计数
- STABLE_1/STABLE_2：连续稳定帧，达到 2 帧触发 OCR
- 稳定性检测可关闭：关闭后像素变化立即触发 OCR（适合视频字幕）

**LRU 缓存：** `LruCache<String, String>(20)`，OCR 文本精确匹配，命中直接返回翻译结果

**像素比较（PixelCompare）：** YIQ 感知色彩差异（移植自 pixelmatch），`diffThreshold` 控制页面变化判定（默认 1%）

**设置：**
- `Game_Pixel_Similar_Threshold`：像素变化阈值（默认 1%）
- `Game_Pixel_Check_Interval`：检测间隔（最低 300ms）
- `pixel_stability_check`：翻页稳定性检测开关

**OCR 引擎（GameOcrEngine）：**
- MLKit(0)、PP-OCRv5(1)、manga-ocr(2)
- MLKit 和 PP-OCRv5 固定使用直接合并（不保留换行）

**调试浮窗（GameDebugOverlay）：** 关于页面开启，显示状态 + 像素差异 + 耗时，点击展开日志面板（最近 20 条，自动去重）

**翻译结果容器（TranslationResultView）：**
- 继承 `FrameLayout`，包含 TextView + 锁定按钮（左上角）+ 关闭按钮（右上角）
- 默认解锁状态可拖动，锁定后不可拖动
- 关闭后再次点击悬浮球：有缓存显示缓存，无缓存触发新翻译
- 自动翻译中临时关闭后，下次翻译自动恢复显示
- 自动翻译时不能关闭悬浮球
- 可穿透性：通过 `alpha` 控制（开启=0.5 半透明，关闭=1.0 不透明），非窗口标志

**悬浮球长按延迟：** 默认 300ms（`FloatingBallConfig.LONG_PRESS_DELAY`）

**悬浮球手势自定义：**
三个手势（单击/双击/长按）分配不同动作，互斥配置（不能重复）。
- `Constants.BallAction`：TRANSLATE(0)、MENU(1)、AUTO_TRANSLATE(2)
- 存储：`SharedPreferences` String 类型（`Ball_Gesture_Single_Click` 等）
- 读取：`prefs.getString(key, "0").toIntOrNull() ?: 0`
- 配置 UI：`PersonalizationConfig` → 悬浮球分类下 3 个 ListPreference
- 选择时自动互换冲突项（如单击=翻译改为菜单，原菜单的手势自动变为翻译）

**自动翻译框选前置：** 启动自动翻译前必须先框选翻译区域（`mRectF != null`），未框选时提示"请先框选翻译区域"。

### 漫画翻译（自动翻页）

**状态机（`MangaFloatingService`）：**
```
IDLE（等变化）──sim<0.95──→ MOTION（等稳定）──连续2次sim≥0.95──→ STABLE（翻译）→ IDLE
```
- IDLE：比较 `currentHash vs lastTranslatedHash`，相同则跳过
- MOTION：比较连续两次截图 hash，用户停翻后 ~1s 内稳定
- 手动翻译标志 `isManualTranslating`：自动翻译中点击悬浮球 → 跳过 pHash 门控，强制翻译

**关键变量：**
- `lastTranslatedHash` — 上次翻译页的哈希（IDLE 判断是否需翻译）
- `previousScreenshotHash` — 上一次截图哈希（MOTION 判断页面是否稳定）
- `translatedRegions` — 区域级翻译缓存（IoU ≥ 0.4 判重，TTL 5 分钟）

## 缓存与历史

`TranslationCacheManager` — 统一管理游戏/漫画翻译缓存
- 漫画模式：pHash 精确匹配 + 相似度匹配（阈值 0.85）
- 游戏模式：仅精确匹配（相似度匹配会误判相似背景）
- Room 数据库 `translation_history.db`，version 8，`fallbackToDestructiveMigration`
- 历史 UI：`ui/history/HistoryFragment`，游戏和漫画均按时间+会话分组显示
- 漫画图片浏览：`MangaViewerActivity` 全屏翻页 + 底部译文详情面板

**双 sessionId 架构：**
- `sessionId` — 原始创建会话 ID（首次翻译时分配，永不改变，用于**按创建排序**的进程组）
- `lastSessionId` — 最后修改会话 ID（任何修改时更新为当前会话，用于**按修改排序**的进程组）
- `createdAt` — 继承自同 pHash 旧记录（保证按创建排序位置不变）
- `updatedAt` — 每次翻译/缓存命中时更新

**翻译会话：**
- `FloatingBallService` / `MangaFloatingService` 每次启动生成 `UUID` 作为 `sessionId`
- `CacheEntry` 同时携带 `sessionId` 和 `lastSessionId`（初始值均为当前会话）
- `saveToCache`：`sessionId` 从同 pHash 旧记录继承（位置不变），`lastSessionId` 使用调用方当前会话
- `refreshCache` / `refreshGameCache`：`sessionId` 继承旧记录，`lastSessionId` 使用当前会话
- 缓存命中（`findCache` / `findMangaCacheByText`）：更新 `updatedAt` + `lastSessionId` 为当前会话
- `getHistoryGrouped(sortByUpdated=true)`：日期组 = `updatedAt`，进程组 = `lastSessionId`
- `getHistoryGrouped(sortByUpdated=false)`：日期组 = `createdAt`，进程组 = `sessionId`

**缓存标识：**
- 游戏翻译：`FloatingBallService` 缓存命中时，翻译文本前显示"⚡"标识（紧凑前缀，不换行）
- 漫画翻译：`MangaFloatingService` 缓存命中时，`showCacheOverlay()` 显示左上角橙色"⚡ 缓存"标签 + 刷新按钮

## 日志规范

**所有日志必须通过 `LogCollector` 写入**，不能直接用 `Log.d/i/e`。

logcat 过滤器：
```
tag:OCRBridge | tag:CTDDetector | tag:CTDPostProcessor | tag:BoxMerger | tag:DetectionBridge | tag:BubbleDetector | tag:OverlayRenderer | tag:MangaFloatingService | tag:MangaOcrBridge | tag:MangaOcrRecognizer | tag:PPOcrV5Engine | tag:OCRTextRecognizer | tag:TranslationCacheManager | tag:AutoTranslateEngine | tag:FloatingBallService | tag:GameOcrEngine | tag:Screenshot
```

## 安装规范（最高优先级）

**绝对禁止未经用户确认就执行 `adb uninstall`！**
安装失败时：只报告错误，询问用户是否需要卸载重装，等用户明确同意后才能执行。这条规则没有例外。

## 关键约束

- **minSdk 29**（Android 10+），**targetSdk 35**
- **仅支持 arm64-v8a** — 不支持 32 位
- 需要 Accessibility Service 进行截屏（非 MediaProjection）
- `FloatingBallService` 使用 `foregroundServiceType="mediaProjection"`
- 许可证：LGPL（原项目）

## UI 规范

- **禁止使用系统弹窗和菜单**：所有弹窗（Dialog）、菜单（Menu）、选择器（Picker）必须使用应用自身的样式，禁止使用系统默认样式
  - 弹窗：使用 `MaterialAlertDialogBuilder`，禁止 `AlertDialog.Builder`
  - 选择列表：使用 `MaterialAlertDialogBuilder` + `setItems` / `setSingleChoiceItems`，禁止 `PopupMenu`、`Spinner`
  - 选项弹窗：使用 `MaterialAlertDialogBuilder` + 自定义 View（RadioGroup 等），禁止 `PopupMenu`
  - 底部弹窗：使用 `BottomSheetDialogFragment`，禁止系统 `Dialog`
- 所有 UI 组件使用 Material Design 组件库（`com.google.android.material.*`）

## 网络配置

- GitHub/HuggingFace 下载需代理（国内环境）
- CLI 工具（gh/curl/npm）需设置 `http_proxy`/`https_proxy` 环境变量，或开启 TUN 模式
- Windows hosts 文件可能有 `#S302` 条目将 github.com 指向 127.0.0.1，需清理
- git 代理配置：`git config --global http.proxy http://127.0.0.1:7897`

## 检查更新

`UpdateChecker.kt` 调用 GitHub Releases API 检查新版本，对比 versionCode（如 `v0.0.2` → `2`），有新版本时弹窗提供三种下载方式：
- **直接下载**：从 Release assets 解析 APK 下载链接，app 内直接下载安装
- **百度网盘**：从 Release body 解析百度网盘链接（包含"百度网盘"和"http"的行）
- **夸克网盘**：从 Release body 解析夸克网盘链接（包含"夸克网盘"和"http"的行）

### Release notes 格式规范

Release notes 中必须包含以下信息，否则 app 内检查更新功能无法正常工作：

```
**下载说明**：
- 百度网盘：https://pan.baidu.com/s/xxx?pwd=xxx
- 夸克网盘：https://pan.quark.cn/s/xxx（暂无则写"暂无"）
```

创建 Release 时必须上传 APK 文件到 assets：
```bash
gh release create vX.X.X app/build/outputs/apk/release/app-release.apk --title "vX.X.X" --notes "..."
```

## 日志监控

### 正确的日志监控方法

**⚠️ 不要手动查询日志，使用后台监控！**

```powershell
# 正确：后台持续监控
$adb = "C:\Users\xjj20\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c  # 先清空旧日志
$pid = & $adb shell pidof com.moe.moetranslator
& $adb logcat --pid=$pid | Select-String -Pattern "关键词" | Out-File -FilePath "C:\Users\xjj20\Desktop\app_log.txt" -Encoding UTF8
```

用 `run_in_background: true` 执行，让 app 运行后再查看日志文件。

### 常见错误

1. **PID 过期** — app 被 force-stop 后重启会获得新 PID，旧 PID 查不到日志
2. **语法混用** — Bash 工具用 Unix 语法，PowerShell 工具用 PS 语法，不要混用
3. **没有清空** — 旧日志会干扰，先 `logcat -c` 清空

**常用过滤：**
- 翻译相关：`Select-String "MangaFloating|OpenAITrans|TranslateBridge|翻译配置|翻译结果|上下文"`
- 错误：`Select-String "Error|Exception|FAILED|失败"`
- 全部：直接 `Get-Content`

## 通知系统

### 架构

- `NotificationChecker.kt` — 从 Gist 获取公告 JSON
- `UpdateChecker.kt` — 从 GitHub Releases API 检查更新
- 两者独立，都在 `TranslateFragment` 中调用

### Gist 公告格式

Gist 内容可能包含非 JSON 前缀（如 "星译公告"），解析时需提取 JSON 部分：
```kotlin
val jsonStart = body.indexOf('{')
if (jsonStart < 0) return NotificationResult.Error
val json = JSONObject(body.substring(jsonStart))
```

### Android 13+ 通知权限

`POST_NOTIFICATIONS` 权限需运行时请求，已在 `MainActivity.onCreate()` 中添加。

## 调试面板架构

### FrameLayout 触摸事件

- `OnTouchListener` 返回 `true` 会吞掉所有触摸事件，子 View 收不到点击
- 正确做法：用 `setOnClickListener`，或在 container 上用坐标判断

### 折叠/展开机制

- `debugInfoPanelView` — 整个 container（imageView + infoPanel + toggleButton）
- `debugInfoPanelContentView` — 仅 infoPanel（可折叠部分）
- 折叠时只隐藏 infoPanel，不动 container
