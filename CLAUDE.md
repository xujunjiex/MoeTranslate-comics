# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 提供项目上下文。

## 项目概述

星译（StarFlow）— Android 翻译应用，支持 Android 11+（API 29+）。包含两个核心功能：游戏翻译（截图 OCR + 翻译 API）和漫画翻译（气泡检测 + OCR + 翻译 + 竖排渲染）。

## 目录规范

- **`.reference/`** — 参考项目，只读，已 gitignore。用于克隆第三方开源项目作为代码参考。
- **`tools/`** — 测试模型和脚本，已 gitignore。用于本地测试转换后的模型。
- **`models/`** — 模型源文件集中目录，已 gitignore（体积大不适合提交）。存放从各服务器下载的真实模型文件（manga-ocr / PP-OCRv5 / PP-OCRv6 medium / RT-DETR），用于计算 MD5 和核对大小。参见 memory `[[model-download-md5-not-size]]`。
- **`docs/docs/`** — 文档内容（提交到 GitHub），`docs/` 其余文件已 gitignore。
- 禁止在项目根目录散落模型文件；模型源文件统一放 `models/`。

## 环境搭建

**必需：** JDK 17、Android SDK（compileSdk 35）、NDK 25.2.9519653、CMake 3.22.1。

**首次克隆后：**
1. 创建 `local.properties`（已 gitignore）：`sdk.dir=C:/Users/%USERNAME%/AppData/Local/Android/Sdk`，路径用正斜杠 `/`
2. 配置 git 代理（国内环境）：`git config --global http.proxy http://127.0.0.1:7897`
3. 确认 Windows hosts 无 `#S302` 条目将 github.com 指向 127.0.0.1
4. `./gradlew assembleDebug` 验证构建

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

# 运行单元测试（⚠️ 直接 ./gradlew test 会因 Git Bash 弄乱 PATH 使 test worker 崩溃
# ClassNotFoundException: Files\Git\mingw64\bin;...，需用 PowerShell + 干净 PATH + --no-daemon + 指定测试类）：
# powershell:
#   $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
#   $env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
#   .\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.data.XxxTest

# 实时查看应用日志
adb logcat --pid=$(adb shell pidof com.moe.starflow)

# 指定设备安装（多设备时）
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

# 查看连接设备
adb devices
```

**Windows 注意：** `adb` 命令需要通过 PowerShell 调用完整路径：
```powershell
# 安装 APK
& "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk

# 监控日志（按 tag 过滤）
& "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat --pid=$(& "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell pidof com.moe.starflow) | Select-String -Pattern "MangaFloatingService|OpenAITranslation|TranslateBridge|FloatingBallService"
```

## 架构

**包结构** (`app/src/main/java/com/moe/starflow/`):

- `translate/` — 游戏翻译引擎：`FloatingBallService`（主服务）、`AutoTranslateEngine`（自动翻译状态机）、`GameOcrEngine`（游戏 OCR 封装）、`GameDebugOverlay`（调试浮窗）、`TranslationResultView`（翻译结果容器）、`CropView`（框选视图）、`Shooter`（MediaProjection 截图）、`ScreenshotManager`（截图管理器单例）、`ScreenshotProvider`（截图提供者接口）/`MediaProjectionProvider`/`AccessibilityProvider`、`ScreenShotAccessibilityService`（无障碍截图）、`Dialogs`（菜单/弹窗工具）、`MenuDialogAdapter`（菜单列表项适配器）、`BallStateManager`（悬浮球状态图标管理器）
- `manga/` — 漫画翻译引擎：`MangaFloatingService`（主服务）、`DetectionBridge`（检测桥接）、`PPOcrV5Engine`（PP-OCRv5 流水线）、`PPOcrV6Engine`（PP-OCRv6 流水线，默认）、`ComicBubbleDetector`/`DBNetDetector`（检测器）、`MangaOcrBridge`/`MangaOcrRecognizer`（manga-ocr）、`TextRegionMerger`（区域合并）、`OverlayRenderer`（覆盖层渲染）、`TranslateUtils`（翻译管线公共层）、`OcrLock`（引擎互斥锁）、`GeometryUtils`/`OnnxUtils`（工具）、`MangaModeConfig`（漫画模式配置 + 引擎组合定义）
- `bridge/` — 桥接层：`OCRBridge`、`DetectionBridge`、`TranslateBridge`、`ScreenshotBridge`
- `me/` — 设置和 API 配置界面：`PersonalizationConfig`（个性化设置）、`APIConfig`（API 配置）、`TranslationMode`（翻译模式）、`AboutMe`（关于页面）、`Developer`（开发者选项）、`FAQPage`（常见问题，11 条 FAQ）
- `launch/` — 首次启动引导
- `utils/` — 工具类：`Constants`（枚举定义）、`CustomPreference`（配置封装）、`LogCollector`（日志收集）、`PixelCompare`（像素比较）、`UiUtils`（Toast 统一）、`ServiceUtils`（服务状态检测）、`UpdateChecker`（检查更新）
- `data/` — Room 数据库、`TranslationCacheManager`、`HistoryEntity`/`PageCacheEntity`
- `ui/history/` — 历史记录 UI：`HistoryFragment`（双视图：默认/管理）、`HistoryGroupAdapter`（游戏分组）、`HistoryMangaGroupAdapter`（漫画分组）、`HistoryGameAdapter`、`HistoryMangaAdapter`、`MangaViewerActivity`（全屏图片浏览+译文详情+重翻操作）、`CropFragment`（重翻裁剪界面）

**翻译 API 实现** (`app/src/main/java/translationapi/`):
每个子目录实现 `TranslationTextAPI` 接口：`openaitranslation/`、`bingtranslation/`、`nllbtranslation/`、`niutrans/`、`volctranslation/`、`deepltranslation/`、`baidutranslation/`、`tencentcloud/`、`azuretranslation/`、`customtranslation/`、`doubaotranslation/`、`hymt2translation/`
- `hymt2translation/` — **Hy-MT2 本地翻译引擎**（llama.cpp 设备端推理，需下载模型，非联网 API）；`nllbtranslation/` 同属本地引擎类

**关键接口：**
- `TranslationTextAPI.getTranslation(text, sourceLanguage, targetLanguage, callback)` — 文本翻译
- `TranslationPicAPI.getTranslation(bitmap, sourceLanguage, targetLanguage, callback)` — 图片翻译

**截图流程：** `ScreenshotProvider`（双模式）→ `ScreenshotManager.screenshotFlow`（SharedFlow）→ `FloatingBallService` / `MangaFloatingService` 接收处理

**漫画翻译缓存机制：**

三层缓存结构，截图后按以下顺序查找：

```
截图
  ↓
IMAGE CACHE (findCacheExt) ─── 256-bit hash
  │ 精确匹配（4段全等）或相似度匹配（≥0.95）→ 直接显示
  │ 未命中 → OCR
  ↓
TEXT CACHE (translatedRegions + incrementalTranslateBubbles)
  │ 精确匹配（hashCode + 字符串==）或加权编辑距离模糊匹配 → 复用译文
  │ 未命中 → 调翻译 API
  ↓
翻译 API → 渲染 → saveToCache
```

| 缓存 | 触发函数 | 匹配算法 | 节省步骤 |
|------|---------|---------|---------|
| IMAGE | `TranslationCacheManager.findCacheExt` | 256-bit pHash（SQL + 遍历） | OCR + 翻译 + 渲染 |
| TEXT | `incrementalTranslateBubbles` | `TextSimilarity.weightedLevenshtein` | 翻译 API 调用 |
| DB 历史 | `MangaViewerActivity` | 256-bit hash 相似度分组 (≥0.85) | 用户翻历史时复用 |

**两种 pHash 算法并存：**
- `PerceptualHash.compute()` — 9×8 dHash，64 位（1×Long），用于自动翻译状态机翻页判断（`PHASH_STABLE_THRESHOLD=0.95`、`PHASH_NEW_PAGE_THRESHOLD=0.60`）
- `PerceptualHash.computeExtended()` — 17×16 dHash，256 位（4×Long），用于缓存匹配（`SIMILARITY_THRESHOLD_MANGA=0.95`）
- **已删除的函数（不要调用）：** `isUniform()`（方差检测，已被 dHash 全零替换）、`quickSameCheck()`/`computeHist()`/`histogramDiff()`（直方图预筛，全项目无调用者）

**纯色/纯白页面检测：** `processMangaScreenshot` 入口处检查 `currentExtHashes.all { it == 0L }`（dHash 全零 = 中心区域无纹理结构）。检测到纯色页直接 `showImmediate("未检测到文字")` + toast → 跳过缓存+OCR+翻译 → IDLE。替换了不可靠的 `isUniform()` 方差检测（已删除）。

**历史分组一致性：** `TranslationCacheManager.groupMangaEntriesByPHash()` — 统一的 256-bit 相似度分组方法，供 `getHistoryGrouped`（历史列表）和 `MangaViewerActivity.buildPageGroups`（图片浏览器）共同调用，保证两处分组数量一致。

- **相似度阈值：0.85**（256-bit Hamming 距离 / 256，约容差 38 bit）
- **稀疏 hash 守卫：`MIN_INFO_BITS_HISTORY = 16`** — 当 4 段 hash 总 bits < 16（≈6.25%）时视为「无判别力 hash」（纯色/几乎纯色页面 dHash 4 段几乎全 0），**单独成组**，不参与正常相似度合并。**这是必要的**，否则稀疏 hash 间 Hamming distance 绝对值极小（2-3 bits 不同），会被 `1 - 2/256 = 0.992` 相似度误判为同一页面（曾导致 id=237/159/152 三张不同纯色页被错误合并成一组）。

**缓存保存（`saveToCache`）** 写入 4 段 hash：
```kotlin
pHash  = currentExtHashes[0]   // 17×16 第 0 段（兼容旧版）
pHash2 = currentExtHashes[1]
pHash3 = currentExtHashes[2]
pHash4 = currentExtHashes[3]
```

**`processMangaScreenshot` 函数签名变化：**
```kotlin
private suspend fun processMangaScreenshot(
    bitmap: Bitmap,
    precomputedPHash: Long? = null,
    precomputedExtHashes: LongArray? = null  // ← 新增：从 collector 传入避免重复计算
)
```

**实时渲染共享层（2026-07 重构）：** 数据库不再存储渲染后的译文 overlay 图片（`imagePath` 停止写入），只存原始截图（`originalImagePath`）+ 气泡元数据（`bubbleRects` JSON）。所有 overlay 显示通过 `TranslationCacheManager.renderOverlay()` 实时渲染。

```kotlin
suspend fun renderOverlay(
    history: HistoryEntry,
    pageCache: PageCacheEntity,
    mode: OverlayMode,                 // TRANSLATED / ORIGINAL / PLAIN
    forFullImage: Boolean              // true=全屏（MangaViewerActivity），false=裁剪（overlay/下载）
): Bitmap?
```

| 调用方 | forFullImage | mode |
|--------|--------------|------|
| MangaFloatingService 缓存命中 | false | TRANSLATED |
| MangaFloatingService 复制模式切换 | false | TRANSLATED/ORIGINAL（**二态**，不加 PLAIN） |
| MangaViewerActivity 预览（首次加载/翻页/三态切换） | false（scope=crop，只渲染框选区域） | TRANSLATED→ORIGINAL→PLAIN |
| HistoryFragment 下载 | false | TRANSLATED |

**坐标映射规则：**
- `forFullImage=false`：从 `originalImagePath` 全屏原图按 `pageCache.cropLeft/Top/Right/Bottom` 裁剪，气泡坐标已在裁剪空间，**无需映射**
- `forFullImage=true`：渲染到全屏原图，气泡坐标需 +`(cropLeft, cropTop)` 映射

**`OverlayMode` 枚举：** `TRANSLATED`（译文）/ `ORIGINAL`（原文）/ `PLAIN`（纯原图，无 overlay）

**变体独立存储：** 每个变体各自保存 `bubbleRects` + crop 坐标，不同框选尺寸互不干扰。`groupMangaEntriesByPHash()` 把所有变体都加入 groups（之前只返回代表 entry，导致下载漏图）。

**三态循环：** MangaViewerActivity 切换按钮（btnToggleImage）三态循环：译文→原文→纯原图→译文。`OverlayMode.PLAIN` 仅在此处使用。翻页自动重置为译文。

**自动翻译干净截图流程：**

状态机 STABLE 后重截干净图用于翻译和缓存。

| 模式 | 流程 | 延迟 |
|------|------|------|
| **MP** | `dismissProgressOverlay()` → 隐藏球 → `delay(50)` → `takeScreenshot()` → 恢复球 → `showProgressOverlay` | +~80ms |
| **无障碍** | `launch { delay(350)` 冷却 → 隐藏球 → `delay(50)` → `takeScreenshot()` 异步 → `pendingCleanScreenshot=true }` → 下一张 flow 拦截 | +~500-900ms |

**⚠️ frameSeq 行为（Shooter.kt）：** `frameSeq` 在 `ImageReader.OnImageAvailableListener` 回调中递增（line 113）。`VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` 在 Redmi/HyperOS 上仅画面内容变化时产帧 → 静态页面 frameSeq 停止递增，翻页/滑动时跳跃增长。其他 ROM 可能持续产帧 → frameSeq 不适用于跨设备屏幕变化检测。

**⚠️ VD 死亡（录屏冲突）处理：** `frameSeq` 不能用于 VD 死亡检测——已尝试并移除（commit `4628010`）。原因：框选模式下悬浮球在选定区域外，VD 可能不捕捉框外像素变化 → 藏球唤醒测试不可靠，频繁误报。当前方案：自动翻译用 40s pHash 超时兜底（同页面 40s 无变化 → 自动停止翻译 + 弹窗提示）；手动翻译无法检测，遇卡死症状（始终缓存命中同一页、自动翻页无变化）见 FAQ Q11。

**⚠️ 所有 overlay（球、进度条）必须在截图前隐藏。** 进度条文字被截入图会导致 pHash 污染和缓存误命中。

- `lastTranslatedHash` 保存检测截图 pHash，保证状态机下次比较有效
- 缓存保存干净截图 extHashes，保证跨模式命中
- 无障碍 `takeScreenshot` API 需至少 350ms 冷却（Android 12+ 后台截图频率限制），详见 [[accessibility-screenshot-cooldown]]

**手动模式隐藏球流程（`takeScreenshotWithProvider`）：** 手动模式截图前隐藏悬浮球，`finally` 块恢复。⚠️ 藏球后必须 `delay(50)` 等至少一个 VSYNC 周期，确保 VD 产出无球的新帧后再截图，否则球图标会被截入翻译结果。自动模式由上述重截图机制处理。

**悬浮球状态图标（`BallStateManager`）：** 管理悬浮球图标的状态机，5 种状态瞬时切换（无动画，仅 Error 红圈脉冲）：
- `Idle` — 用户自定义图标（从 `getExternalFilesDir/icon/` 加载）
- `Processing` — OCR 识别中图标（state2 mipmap）
- `Translating` — 翻译中图标（state3 mipmap）
- `Completed` — 翻译完成图标（state4 mipmap）
- `Error` — Processing 图标 + 红圈 1100ms 脉冲动画

游戏/漫画模式独立配置（`Mode.Game` / `Mode.Comic`），各有 4 套 mipmap。`MangaFloatingService` 在翻译流程入口设 `Processing`，非分批路径在调翻译 API 前切 `Translating`，分批路径在第一批翻译开始时切 `Translating`（保持到全部完成）。`finalizeIncremental` 结束后切 `Completed`。

**DB 版本 11（MIGRATION_10_11）：** 修复漏加的 `last_session_id` 列 + `createdAt`→`created_at` 列名问题。迁移幂等：先 `PRAGMA table_info` 检查列是否存在再操作。

**MIGRATION_9_10：** 增加 `pHash2`/`pHash3`/`pHash4` 三列存 256 位扩展 hash。

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

**⚠️ 自定义 API prefill 守卫：** 用户自定义 provider 的 `continuationType` 默认为空字符串，会给不支持续写的 API 发送假的 assistant prefill → 服务端 hang → 30s 超时。三层防御：
1. `MangaFloatingService` / `MangaViewerActivity`：对 `!provider.isBuiltin` 强制 `CONTINUATION_NONE`
2. `buildRequestBody` 白名单：仅 `standard/partial/prefix` 启用 prefill，空字符串/未知值不放行
3. 自定义 API 漫画 prompt 为空时回退到内置 `DEFAULT_MANGA_SYSTEM_PROMPT`（漫画翻译引擎），避免空 prompt 导致模型返回聊天废话。UI 侧 `setupUserMode()` 也为自定义 API 显示重置按钮，漫画 tab 重置到 `fallbackMangaSystemPrompt` / `fallbackMangaUserPrompt`（和 `BuiltinProviders` 的 `DEFAULT_MANGA_*` 一致）

**内容安全审查：** 各 API 平台可能拦截敏感内容翻译（错误码 `data_inspection_failed`，HTTP 400）。不同平台审查阈值不同，被拦截时换平台或换模型。

**AI 上下文（游戏模式）：** `FloatingBallService` 维护 `LinkedList<Pair<String, String>>` 存储历史翻译对（原文, 译文）。开启后系统提示词追加"根据上下文剧情进行翻译"，messages 中插入历史 user/assistant 对。用户可配置轮数（5-20，默认 5）。仅 OpenAI 兼容 API 生效。设置项：`game_context_enabled`（开关）、`game_context_count`（轮数，存为 String）。

**AI 上下文（漫画模式）：** 正常漫画翻译不使用上下文。仅增量渲染的两批之间使用上下文（`forceContext=true`），翻译完后回滚，不污染后续页面的上下文历史。

**配置存储：** `CustomPreference` 单例封装 `SharedPreferences`。API 密钥通过 `KeystoreManager` 加密存储。

**UI：** 传统 Android Views + ViewBinding（非 Jetpack Compose）。导航使用 Navigation Component。

**构建模块：** `:app`。原生代码通过 CMake 构建（`app/src/main/cpp/`）。

## 模型管理

### 当前使用的模型

| 模型 | 用途 | 大小 | 来源 | 存储位置 |
|------|------|------|------|----------|
| **PP-OCRv5 det** | 文字区域检测 | ~4.6MB | RapidAI/RapidOCR | filesDir/ppocrv5/ 需下载 |
| **PP-OCRv5 rec zh** | 中日英混合识别 | ~16MB | RapidAI/RapidOCR | filesDir/ppocrv5/ 需下载 |
| **PP-OCRv5 rec en** | 英文专用识别 | ~7.5MB | ModelScope | filesDir/ppocrv5/ 需下载 |
| **PP-OCRv5 rec ko** | 韩文专用识别 | ~12.9MB | ModelScope | filesDir/ppocrv5/ 需下载 |
| **PP-OCRv5 rec ru** | 俄文/西里尔文字识别 | ~7.7MB | ModelScope | filesDir/ppocrv5/ 需下载 |
| **PP-OCRv6 det small** | 文字区域检测 | ~9.9MB | RapidAI/RapidOCR | **assets/ 内置** |
| **PP-OCRv6 rec small** | 多语言混合识别 | ~21MB | RapidAI/RapidOCR | **assets/ 内置** |
| **PP-OCRv6 det medium** | 文字区域检测（高精度） | ~60MB | ModelScope | filesDir/ppocrv6/ 需下载 |
| **PP-OCRv6 rec medium** | 多语言混合识别（高精度） | ~74MB | ModelScope | filesDir/ppocrv6/ 需下载 |
| **RT-DETR-V2** | 文字/气泡检测 | ~11MB | HuggingFace | getExternalFilesDir/ 下载 |
| **manga-ocr** | 竖排日文识别 | ~135MB | HuggingFace | getExternalFilesDir/ 下载 |

**PP-OCRv5/v6 cls（方向分类）已删除** — v5 和 v6 的 cls 模型、代码、`runOCR(useCls)` 参数全部移除（commit 5d6e235 / 5a1e83e）。

PP-OCRv5 全部模型（det + rec_zh + 字典 + 可选 rec en/ko/ru）改为下载，**assets 中已无 v5 模型文件**。PP-OCRv6 small 仍内置，medium 必须从 ModelScope 下载。`PPOcrModelFiles` 提供所有 v5/v6 模型的文件查询、浏览器 URL、删除接口（下载走 `download/`）。

### PP-OCRv5 模型下载地址（ModelScope）

基础 URL: `https://modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/rec/`

| 文件名 | ModelScope 文件名 | 大小 |
|--------|-------------------|------|
| rec_en.onnx | `en_PP-OCRv5_rec_mobile.onnx` | ~7.5MB |
| rec_ko.onnx | `korean_PP-OCRv5_rec_mobile.onnx` | ~12.9MB |
| rec_ru.onnx | `cyrillic_PP-OCRv5_rec_mobile.onnx` | ~7.7MB |

字典文件（rec_en_dict.txt / rec_ko_dict.txt / rec_ru_dict.txt）随 rec 模型一起从 ModelScope 下载（app 内串行下载 ONNX + 字典两个文件），不再从 assets 读取。`PPOcrV5Engine.loadDictionary()` 仅从 `filesDir/ppocrv5/` 读取，不 fallback assets。

### PP-OCRv5 语言 fallback 逻辑

`PPOcrV5Engine.resolveRecLang()` 处理语言选择：
- **ZH/JA**：始终可用（内置 rec_zh 模型，支持中日英混合识别）
- **EN**：已下载 rec_en → 用 EN 模型；未下载 → fallback 到 ZH 模型（ch 也支持英文）
- **KO**：已下载 rec_ko → 用 KO 模型；未下载 → 返回提示"请下载韩文模型"
- **RU**：已下载 rec_ru → 用 RU 模型；未下载 → 返回提示"请下载俄文模型"

### 下载管理器

下载流水线收拢在独立包 **`download/`**（`com.moe.starflow.download`）：

```
download/
├── ModelDownloadManager.kt      # 统一 HTTP 下载器（断点续传、重试、进度回调）
├── ModelDownloadRepository.kt   # 状态机 + 磁盘扫描 + JSON 解析
├── ModelDownloadService.kt      # 下载编排 + 前台服务 + 通知
├── DownloadState.kt / ModelInfo.kt / ModelKey.kt  # 状态/元数据/枚举定义
└── ChecksumHelper.kt            # MD5 校验（含 VerifyResult）
```

`manga/` 下的 `RTDetrModelFiles`/`PPOcrModelFiles`/`MangaOcrModelFiles`/`DBNetModelFiles` 仅保留**模型文件检查**逻辑（下载方法已迁到 download/，待后续清理）。

### 模型管理 UI 架构（v4 起）

`ModelManagementFragment` + `fragment_model_management.xml` 按「引擎组合」分组，不再按「检测器/识别器」分两大块。页面顺序固定（不要随意调整）：

| # | 组 | 类型 | 说明 |
|---|----|----|------|
| 1 | ML Kit | 内置 | Google 设备端 |
| 2 | PP-OCRv6 | small 内置 / medium 可选下载 | 两个内置相邻，v6 small 右侧绿色「内置」文本 |
| 3 | PP-OCRv5 | 全部下载 | 内部顺序：检测器（det）→ 识别器（多语言 → 4 个语言） |
| 4 | RT-DETR-V2 + manga-ocr | 全部下载 | 漫画组合，RT-DETR-V2 是检测器、manga-ocr 是识别器 |

每组条目统一 4 段 UI（**v4 起不再有「· 检测器」「· 识别器」灰色角标**——与标题同义重复，已删除）：
1. **标题**（粗体 13-14sp）
2. **状态文本**（12sp，含实际或预估文件大小，weight=1 占满剩余空间）
3. **🔗 浏览器按钮**（11sp 灰色，链接图标 + 文件名/类型：模型/字典/encoder/decoder/vocab/浏览器）
4. **下载/删除按钮**（绿色 btn_download 或红色 btn_delete，weight=0 固定宽度）

v6 medium 用 RadioButton 切档（det+rec 全部下载后才显示 medium RadioButton），RadioButton 与「medium」标题同行右侧，与 small RadioButton 互斥。删除任一 medium 文件 → 自动切回 small + Toast 提示。

**可扩展设计（v5 起）：** `ModelManagementFragment` 是**数据驱动**的——`modelRows` 列表驱动渲染、浏览器按钮接线、磁盘状态刷新，不再逐模型写方法。

**新增一个可下载模型只需 3 步：**
1. `downloadinfo.json` 加模型定义（`model_key` + `files` 数组：文件名/URL/大小/MD5）
2. `fragment_model_management.xml` 加一行：`status` + `action_button` + `cancel_button`（+ 可选浏览器按钮，命名 `xxx_browser_button` / 每文件一个）
3. `ModelManagementFragment.modelRows` 加一条 `ModelRow`（modelKey + 显示名 + 预估大小 + 全部 view ID；单文件浏览器按钮用 `browserUrlBtnId`，多文件逐文件用 `fileBrowserBtnIds`）

**本地翻译模型下载页：** `NllbModelFragment` 已参数化——`NllbModelFragment.newInstance(ModelKey.X)` 可复用于任意本地翻译模型（默认 NLLB），`ManageActivity` 按 `EXTRA_FRAGMENT_TYPE` 传 modelKey 即可。

**下载系统关键约定（防踩坑）：**
- **校验走 MD5，不按 `file_size` 精确匹配**（JSON 的 file_size 常与服务器实际文件不符，曾导致"下载完成→误删→死循环"）；`downloadinfo.json` 各模型 checksum 从本地 `models/` 真实文件计算，NLLB 的 5 个 checksum 未验证
- **多文件 Done 必须所有文件都有完整 target**（`computeStateFromDisk`），不能只检查第一个文件（曾导致强退后 NLLB 误判已下载）
- **下载完成立即 `markDone`**，否则状态卡 Running，只有重启 `initialize()` 才识别
- 页面进入先调 `refreshFromDisk()` 让 UI 反映磁盘真实状态；取消/删除已入队的下载会从 `queuedKeys` 移除，不会被自动重启

**关键规则：**
- **所有日志用 `LogCollector`**，不用 `Log.d/i/e`
- **下载前检查文件是否存在**，避免重复下载
- **404/403 不重试**，其他错误最多重试 3 次
- **大文件用 `.part` 后缀**，下载完成再 rename

## 漫画模块

**核心文件：** `MangaFloatingService.kt`（主服务）、`DetectionBridge.kt`（检测桥接）、`ComicBubbleDetector.kt`（RT-DETR-V2 检测）、`PPOcrV5Engine.kt`（PP-OCRv5 det+cls+rec）、`MangaOcrBridge.kt`（manga-ocr）、`BoxMerger.kt`（合并）、`TextRegionMerger.kt`（识别后合并）、`OverlayRenderer.kt`（渲染）
**工具类：** `GeometryUtils.kt`（凸包、点在多边形等几何算法）、`OnnxUtils.kt`（ONNX 张量提取、资源拷贝）
**调试渲染子包 `manga/debug/`：** `MangaDebugOverlays.kt`（object：4 个 render*DebugOverlay 纯渲染函数 + applyCropDimming/createInfoPanelView/createToggleButton/MaxHeightScrollView 辅助）、`MangaDebugSliders.kt`（object：PP-OCRv5/v6 参数滑块面板构建器，注入 `CustomPreference` + `context`，无服务引用）。这些函数从 `MangaFloatingService` 提取（C1 重构），无状态、依赖全部参数化；`show*DebugResultOverlay`/`show*DebugView` 等编排函数仍在主服务里。

**检测引擎（DetEngine）：**
- `MLKIT(0)` — ML Kit 检测+识别一体化
- `RT_DETR_V2(3)` — RT-DETR-V2 气泡/文字检测
- `PP_OCR_V5(4)` — PP-OCRv5 独立流水线
- `PP_OCR_V6(5)` — PP-OCRv6 独立流水线（**默认**）

**OCR 引擎（OcrEngine）：**
- `MLKit(0)`、`MangaOcr(1)`、`PPOcrV5(4)`、`PPOcrV6(5)`（**默认**）

**引擎切换架构（重要）：**

游戏模式和漫画模式均使用"单一声源"模式避免分支遗漏：

- **游戏模式**（`FloatingBallService`）：`engineCycle` 数组定义切换顺序 `[V5, V6, MLKIT, MANGA]`，`engineLabel()` 统一值→标签映射，`cycleOcrEngine()` 用 `engineCycle.indexOf() + 1` 查找下一个
- **漫画模式**（`MangaFloatingService`）：`engineCombos` 列表定义 det+ocr 固定搭配，包含 `key`/`detEngine`/`ocrEngine`/`labelRes`/`needsDownloadCheck` 五元组。四个方法覆盖所有需求：
  - `currentCombo()` — config → 组合
  - `comboLabel()` — 组合 → 标签字符串
  - `isComboAvailable()` — 检查是否可用（自动处理 manga-ocr 下载检测）
  - `applyCombo()` — 应用组合（更新 config + 持久化 prefs + 启动引擎）

**添加新引擎只需：** 1) 枚举加值 2) `engineCombos`/`engineCycle` 加一项 3) `applyCombo`/`initEngineAsync` 加 `when` 分支。不需要同步多个分散的 `when` 表达式。

**⚠️ 漫画引擎的「两个默认值」：** `MangaFloatingService.loadConfig()` 中 `prefs.getInt("Manga_Det_Model", DetEngine.PP_OCR_V6.value)` 的默认值（第一默认，source of truth）和新安装用户 `MangaModeConfig.detEngine = DetEngine.PP_OCR_V6` 的 data class 默认值（第二默认）**在代码逻辑中等价**——首次运行时 prefs 无值 → 返回第一默认覆盖 data class 默认。但两者分别维护，修改默认引擎时必须同时更新两处。只改 data class 默认值而 prefs 默认值不变，升级用户（已有 prefs 值）不受影响；只改 prefs 默认值而 data class 默认值不变，某些间接路径（如 `loadConfig()` 未覆盖的代码）会读到旧值。

**合并机制：**

| 检测器 + 识别器 | 前合并 (BoxMerger) | 后合并 (BubbleDetector) | 说明 |
|---|---|---|---|
| RT-DETR-V2 + 任意 | ❌ | ❌ | 检测器直接输出气泡级结果 |
| MLKit 独立 | ❌ | ✅ | 行级文字块 → BubbleDetector 合并成气泡 |
| PP-OCRv5 独立 | ❌ | ✅ | 行级检测框 → BubbleDetector 合并成气泡 |

**单字符噪声过滤：** `textBlocksToBubbleRegions` 过滤单字符纯标点（OTHER_PUNCTUATION、DASH_PUNCTUATION、START/END_PUNCTUATION、MATH_SYMBOL、OTHER_SYMBOL），避免标点符号被当作独立气泡翻译。

**识别后合并（`TextRegionMerger`，V5/V6 独立路径 OCR 后调用，两阶段）：**
- **阶段一 `canMergeRegion`（局部成对判定）**：AABB 间隙 < 1×字符宽 + 字号比/方向/对齐校验 → 构建相邻图 → 连通分量。判定参数可在调试面板滑块调（`merge_discard_gap` 粗筛 1.5×字号 / `merge_char_gap2`）
- **阶段二 `splitTextRegion`（整体 MST 拆分）**：检测传递连接（A近B、B近C 但 A、C 是不同句首尾）造成的过度合并，按 MST 最长边切分。**2 元素组件直接信任 canMerge**——旧实现用中心距离 < 1.5×字号 重判，与阶段一 AABB 间隙指标矛盾，曾导致竖排相邻行（`[2][3]`/`[7][8]`）被错误拆开（`dd48f63` 修复）。给合并逻辑加/改判定时，必须保证两阶段指标一致
- **调试日志**：PP-OCRv5/v6 调试模式自动 `TextRegionMerger.enableDebugLogging(true)` → 逐对 `canMerge [i]"文本" + [j]"文本" → REJECT/ACCEPT 原因`（tag `TextRegionMerger`，`[i]` 对得上调试面板「原始识别」编号）。`enableDebugLogging` 默认关闭，只该在调试路径开启

**翻译流程：** 截图 → 检测 → OCR → 气泡合并（按需）→ 翻译（每气泡并行）→ 覆盖渲染

**增量渲染（分批 OCR+翻译）：**
超过 6 个气泡时自动分批处理，首批翻译完立即渲染，减少用户等待时间。
- 触发条件：`Incremental_Render` 开启 + 气泡数 > 6
- 支持组合：RT-DETR-V2/MangaOcr、PP-OCRv5 独立模式
- 流程：检测 → 分批(2/5+3/5) → OCR第一批 → 翻译第一批+OCR第二批并行 → 渲染第一批 → 翻译第二批 → 最终渲染
- 上下文仅批次间使用：`forceContext=true` 强制开启，第二批能看到第一批译文；两批翻译完后回滚 contextHistory，不污染后续页面
- 正常漫画翻译不使用上下文（`forceContext=false` 时直接关闭）
- MangaOcr encoder 是批处理瓶颈（~3s），分批可提前显示部分结果

**Debug 系统：** 关于页面可开启 4 个独立 debug 开关（RT-DETR-V2 / MLKit / PP-OCRv5 / PP-OCRv6），按当前 `config.detEngine` 决定走哪条 debug 路径。调试菜单为二级结构（一级标题带图标，二级开关无图标）。

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
- **合并**：`TextRegionMerger.canMergeRegion` 两分支——**AA 分支**（正交框）AABB 间隙 < 1×字符宽 + 边/中心对齐；**Tilted 分支**（倾斜框）角度差 < 15°、字号差 < 0.25、AABB 距离 < 3×字号
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

**受限区域截图：**
- 系统 `takeScreenshot()` API 对受限区域（DRM/安全应用、相册/银行/支付）返回 `onSuccess` + 全黑 bitmap 或无文字图片
- 此检测已移除（之前用的 `isRestrictedScreenshot()` 因大面积白底/黑底误判严重已删除）
- 提示信息在 FAQ Q10 说明

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
- MLKit(0)、PP-OCRv5(1)、manga-ocr(2)、PP-OCRv6(3)
- 切换顺序：v5 → v6 → MLKit → manga（`engineCycle` 数组，`engineLabel()` 统一标签映射）
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
- 漫画模式：pHash 精确匹配 + 相似度匹配（256-bit 阈值 0.95，约 13 bit 容差）
- 游戏模式：仅精确匹配（相似度匹配会误判相似背景）
- Room 数据库 `translation_history.db`，version 11，`fallbackToDestructiveMigration`
  - v9→v10 迁移：`ALTER TABLE ... ADD COLUMN pHash2/pHash3/pHash4 INTEGER NOT NULL DEFAULT 0`（256-bit 扩展 hash）
  - v10→v11 迁移：幂等修复漏加的 `last_session_id` 列 + `createdAt`→`created_at` 列名（先 `PRAGMA table_info` 检查再操作）
- 历史 UI：`ui/history/HistoryFragment`，游戏和漫画均按时间+会话分组显示
- 漫画图片浏览：`MangaViewerActivity` 全屏翻页 + 底部译文详情面板
- 重翻引擎选择：`history_retranslate_engine` 偏好（PP_OCR_V5/MANGA_OCR/MLKIT），通过 `mapEngineToDetOcr` 映射
  - ⚠️ **manga-ocr 必须配 `RT_DETR_V2`**（不可配 `PP_OCR_V5`，后者 `runOCR` 忽略 ocr 参数直接走独立管线）

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

**缓存标识（⚡ 严格区分来源）：**
- 游戏翻译：`FloatingBallService` **仅内存 LRU 命中**显示"⚡"前缀（`setText(it, fromCache = true)`）
- 游戏翻译：**数据库命中**不显示"⚡"（`setText(it)`，不带 fromCache）
- 漫画翻译：仅 `TranslatedBubble.isInMemoryCache = true` 时在 overlay 显示"⚡"
- 漫画翻译：**数据库反序列化的 bubbles 永远不显示"⚡"**（`fromCache=true` 但 `isInMemoryCache=false`）
- 底层规则：`OverlayRenderer.renderOverlay` 只检查 `region.isInMemoryCache`，不检查 `fromCache`。数据库反序列化走 `rebuildBubblesFromCache` 默认 `isInMemoryCache=false`

## 日志规范

**所有日志必须通过 `LogCollector` 写入**，不能直接用 `Log.d/i/e`。

logcat 过滤器：
```
tag:OCRBridge | tag:BoxMerger | tag:DetectionBridge | tag:BubbleDetector | tag:OverlayRenderer | tag:MangaFloatingService | tag:MangaOcrBridge | tag:MangaOcrRecognizer | tag:PPOcrV5Engine | tag:OCRTextRecognizer | tag:TranslationCacheManager | tag:AutoTranslateEngine | tag:FloatingBallService | tag:GameOcrEngine | tag:Screenshot | tag:Shooter | tag:OpenAITranslation | tag:TranslateUtils | tag:TranslateBridge
```

## 安装规范（最高优先级）

**绝对禁止未经用户确认就执行 `adb uninstall`！**
安装失败时：只报告错误，询问用户是否需要卸载重装，等用户明确同意后才能执行。这条规则没有例外。

## 设备操作规范（最高优先级）

**禁止未经用户确认直接操作设备**。以下命令都属于「操作设备」，一律先问用户或只报告不执行：
- `adb shell input tap/swipe/text/keyevent`（点击/滑动/输入）
- `adb install` / `adb uninstall` / `adb shell pm`（安装/卸载）
- `adb shell screencap` + pull（截图）
- `adb shell svc power` / `input keyevent KEYCODE_POWER`（电源/亮屏）
- `adb shell wm` / `input` 解锁或关闭通知栏

**允许的只读调查手段**：
- 读代码、读 logcat（`adb logcat -d` 只读查询）
- `adb shell ls/stat` 查看设备文件状态（不修改）
- `adb shell pidof` 查进程

**验证由用户自己做**：修复后提供清晰的验证步骤（观察什么、预期什么），用户自己构建、安装、点击测试。需要设备状态时，明确说「请你测试 X」。

这条规则的背景：曾多次未经确认直接点击/截图/安装设备，用户明确要求禁止。详见 memory `[[no-device-operations]]`。

## 双模式截图

截图方式通过 `Screenshot_Method` 偏好设置（关于页面选择）：
- **MediaProjection（默认）**：弹窗授权，门槛低，每次启动都要授权
- **AccessibilityService**：需要手动开启无障碍服务，永久有效

### 架构

```
ScreenshotProvider（接口）
├── MediaProjectionProvider — Shooter 截图
└── AccessibilityProvider — 无障碍服务截图

ScreenshotManager（单例）
├── screenshotFlow: SharedFlow<ScreenshotData>
└── contentChangedFlow: SharedFlow<Unit>
```

### 前台服务

MediaProjection 模式需要前台服务 + FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION。
FloatingBallService 和 MangaFloatingService 在 MediaProjection 模式下自动启动前台服务。

### 权限请求

ScreenCapturePermissionActivity — 透明 Activity，弹出系统授权弹窗。
MediaProjectionIntentHolder — 存储授权 Intent。

### 响应时间差异

- 游戏模式：无差异（300ms 轮询）
- 漫画模式：MediaProjection 翻页后等轮询周期，AccessibilityService 翻页后 ~500ms 触发

### Shooter `convert()` 失败保护

`Shooter.kt` 的 `OnImageAvailableListener` 在 `convert(image)` 返回 null 时**必须设置 `imageAvailable = true`**，否则后续所有 `shot()` 调用永久超时，返回同一张缓存图 → 状态机永远 `simToTranslated=1.0` → 自动翻译卡死。此 bug 已在 listener 中添加保护。

## 设置项实时生效规范（重要）

**添加任何 prefs 时，必须确认 service 侧有对应监听器**，否则用户运行时修改不会生效，必须重启服务。

三种实现方式（按优先级）：

1. **每次调用重新读 prefs**（最简单，适合"每次翻译都查"的值）
   - 例：`Incremental_Render`（每次 incrementalTranslateFlow 重读 prefs）
   - 例：`Game_Pixel_Similar_Threshold`（AutoTranslateEngine 每帧读 prefs）
   - 例：`Status_Position` / `Status_Duration`（TranslationStatusOverlay.show 每次读 prefs）

2. **service 监听 prefs listener + 刷新内部缓存**（适合"启动时读一次到字段"的性能敏感场景）
   - 例：`Manga_Text_Color` / `Manga_BG_Color` → 加入 `MangaFloatingService.watchedKeys`，触发 `loadConfig()`
   - 例：`Custom_Result_Font_Size` / `Custom_Result_Font_Color` 等 → 加入 `FloatingBallService.styleKeys`，触发 `applyStyle()`
   - 例：`game_context_enabled` / `game_context_count` → 加入 `FloatingBallService.watchedKeys`，直接更新 `contextEnabled` / `contextMaxCount` 字段

3. **service-running 检查 + 拒绝运行时修改**（适合必须重启才能生效的关键配置）
   - 例：`Ball_Gesture_Single_Click` / `Double_Click` / `Long_Press`（手势冲突检测依赖所有手势值，必须停服务才能保证安全）

**debug checklist**：新加 prefs 后，自查：
- service 启动时把它读到哪里？（字段？每次读？）
- 字段被缓存了 → 是否加入 watcher？
- 每次调用重新读 → 验证调用路径确实每次读

## 偏好键名规范（防踩坑）

漫画翻译的字号/颜色 prefs 键名统一使用 **PascalCase**：
- `Manga_Font_Size`、`Manga_Auto_Font_Size`、`Manga_Text_Color`、`Manga_BG_Color`
- **禁止混用 snake_case**（如 `manga_font_size`）— 键名不一致会导致 set 写到一个 key、get 读到另一个 key → 用户设置永远不生效（永远返回默认值）
- `TranslationCacheManager.getOverlayConfig(prefs)` 已统一为 PascalCase，被 MangaFloatingService / MangaViewerActivity / HistoryFragment 三处调用
- **所有跨文件共享的 prefs key 必须在共享位置定义为常量**，避免硬编码字符串

## 关键约束

- **minSdk 29**（Android 10+），**targetSdk 35**
- **仅支持 arm64-v8a** — 不支持 32 位
- 双模式截图：MediaProjection（默认）/ AccessibilityService
- `FloatingBallService` 使用 `foregroundServiceType="mediaProjection"`
- 许可证：LGPL（原项目）

## 高频踩坑（gotchas）

- **`Bitmap.createBitmap(src, x, y, w, h)` 是子 bitmap**，共享原图底层数据。原图 `recycle()` 后子 bitmap 失效，再调用 `.copy()` 抛 `Can't copy a recycled bitmap`。**正确顺序：先渲染（产生独立副本），再 try/finally 中 recycle 源 bitmap。**（cache 实时渲染 + 下载修复踩过）

- **`TranslationStatusOverlay` 窗口生命周期**：`TYPE_APPLICATION_OVERLAY` 窗口可能被系统移除而 `isShowing` 状态过期（MIUI 屏幕录制切换/窗口策略变化等）。**所有显示路径（`show`/`showImmediate`/`showError`/`update`）都必须确保窗口附着**——复用已有 chip 时也要调 `addToWindowIfNeeded()`；`updateViewLayout` 失败（窗口已脱）要自愈重新 `addView`。曾因 `showImmediate` 的"复用 chip"分支漏调 `addToWindowIfNeeded` 导致翻译过程状态条消失（初始化 `show()` 走 `addChip` 正常），根因是 `d7f4ce7` 重写弹窗时把旧版"每次 `displayMessage` 都确保窗口"的行为拆丢了。**给此组件加/改显示逻辑时，必须保证每个入口都触发窗口附着。**

- **`libsentencepiece_train.so` 可安全排除**：NLLB 推理不需要 sentencepiece 训练库。在 `build.gradle` 的 `packaging { jniLibs { excludes += ['**/libsentencepiece_train.so'] } }` 中添加排除规则可节省 ~1.6MB APK 体积。不影响 NLLB 翻译功能。

- **`MangaFloatingService` 前台服务类型（无障碍模式兼容性）**：Android 14+（targetSdk 35）要求 `startForeground()` 的类型与 Manifest 声明的 `foregroundServiceType` 匹配。如果服务声明了 `mediaProjection` 但以无障碍模式启动（无需 MediaProjection 授权），直接 `SecurityException` 崩溃。**修复**：始终以 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 启动，仅在切换到 MediaProjection 模式时通过重新 `startForeground()` 升级到 `MEDIA_PROJECTION`。`FloatingBallService` 若后续添加无障碍截图模式也需同样处理。

- **Android 11+ Scoped Storage**：直接 `File` 写 `/storage/emulated/0/Download/...` 会 EACCES 被拒。下载等需要写入公共目录的场景，用 `MediaStore.Downloads.EXTERNAL_CONTENT_URI` 写入。**SAF `openOutputStream(uri).use { ... .copyTo(out) }` 在某些 Android 版本上会丢数据（zip 显示 0B）**，优先 MediaStore，失败 fallback SAF。

- **`groupMangaEntriesByPHash()` 必须返回所有变体**（不能只返回代表 entry）。否则下载/历史浏览只下载/看到代表那张，多尺寸变体丢失。

- **Manifest 没存声明过的权限，运行时 API 也会失败。** Android 13+ `WRITE_EXTERNAL_STORAGE` 是 legacy 权限，但 `MediaStore.Downloads` 不需要任何运行时权限就能写入。

- **`OverlayRenderer.renderOverlay` 第一个参数是源 bitmap**，函数内部会 `.copy()` 创建独立副本。如果传入的是子 bitmap（来自 `Bitmap.createBitmap(src, ...)`）且原图已 recycle，会崩溃。

- **复制模式按钮在独立 WindowManager 窗口**，没有 Window 系统焦点反馈。需要手动加 `setOnTouchListener` 实现 scale 0.92→1.0 动画（80ms down + 120ms up）。

- **`spinnerVariant` 显示"?"**：新条目 `imagePath=null`，但 `pageCacheMap[entry.id]?.cropRect` 有框选尺寸。**用 cropRect 宽高当 spinner 显示文本**，不要 fallback 到文件头尺寸（那是原图尺寸，不是用户框选的）。

- **`renderCache` bitmap 回收纪律（防静默 recycle 崩溃）**：`MangaViewerActivity.renderCache` 用 `utils/BitmapLruCache`（key = `"${entryId}_${mode.name}_crop"`）。**运行期绝不 recycle**：LRU 淘汰（`removeEldestEntry`）/ `set` 同 key 替换 / `remove` / `retainEntries` 全部只移除 cache 条目，旧 bitmap 交给 GC 回收 native buffer——被移除的 bitmap 可能仍被某个相邻未 detach 的 ViewHolder 的 ImageView 引用，静默 recycle 必触发 `Canvas: trying to use a recycled bitmap` 崩溃。**只有 `clear()`（onDestroy，ViewHolder 已全部释放）才 recycle**。`PageGroupAdapter.onViewDetachedFromWindow` 仅打 log，不做回收。`loadImage` 异步渲染完成后用 `ViewHolder.boundEntryId`/`boundMode` 校验 holder 是否仍属同一 entry+mode，过期则丢弃（`bitmap.recycle()`，此时 bitmap 未上屏，安全）。

- **`ZoomableImageView` 旋转用 imageMatrix 而非 View.rotation**：旋转按钮调 `rotateAndFit90()` 累加 `imgRotation`（0/90/180/270）并用 `imageMatrix` 旋转 + 按旋转后视觉外接尺寸 contain 铺满（消除黑边、不溢出）。**绝不用 `View.rotation`**——旋转后 View bounds 外接矩形变大，被父容器（ViewPager2 的 RecyclerView，默认 clipChildren=true）裁切出黑边。旋转态下 `constrainMatrix` 禁用边界限制（未旋转几何公式不适用，会错误回拉图像到屏外）。三态切换/翻页重绑定触发 `resetRotation()` 还原正常方向（imgRotation=0 + fitCenter）。

- **`updateToggleSegments` 协程必须 cancel**：每次 toggle 启动新协程前 `renderToggleJob?.cancel()`，避免用户快速点击产生并发渲染浪费 CPU。`dismissCacheOverlay` 回收 `currentOriginalBitmap` 前也要 cancel render job，否则协程回到 Main 时 bitmap 已回收 → `IllegalStateException`。

- **缓存命中路径必须检查 bubbleRects**：新数据有 `bubbleRects` → 走实时渲染。旧数据无 `bubbleRects` → 回退加载 `imagePath`（预渲染译文 overlay）。`buildCacheResult` 需根据 `bubbleRects.isNullOrBlank()` 选择加载路径。漏检查会导致旧数据用户看到原图而非译文 overlay。

- **MangaViewerActivity 重翻流程（`performRetranslate`）**：按当前条目**原有框选区域**（读 `pageCacheMap[entry.id]` 的 crop 坐标）原地重 OCR+翻译，调 `refreshCacheInPlace`（保 historyId 不变，替换 sourceText/translatedText/bubbleRects/translatorName）。完成后 `cacheManager.getHistoryById` + `updateInMemoryEntry` 覆盖 `pageGroups` 内存快照（否则 `expandPanel` 显示旧译文），清该 entry 三态 renderCache 后 `notifyItemChanged`，必要时调 `expandPanel` 刷新详细面板。**无「重新框选」对话框**（已删除 `view_manga_retranslate_recrop.xml`）。

- **稀疏 hash 误判合并 bug**：`groupMangaEntriesByPHash` 用 256-bit Hamming 距离相似度（阈值 0.85）。**纯色 / 几乎纯色页面 dHash 4 段几乎全 0**（每段 1-3 bits），两张低纹理页间 distance=2~3 bits → `similarity = 1 - 2/256 = 0.992` 远超 0.85 → **错误合并**。**修复**：`MIN_INFO_BITS_HISTORY = 16`（~6.25%）守卫，infoBits < 16 的 entry 单独成组，不参与 normal 相似度判定。**应用范围**：`findCacheExt` 用 0.95 阈值独立判断（line 363），且有 `curBits=0/256` 早退保护，但 `groupMangaEntriesByPHash` 用 0.85 没早退 → 必须加守卫。新加 hash 相似度判定时也要加 infoBits 守卫。详见 [[manga-history-group-sparse-hash]]。

- **pHash 显示格式必须用 `%016X`（完整 64 位）**：MangaViewerActivity 详情面板 `tvTranslationInfo` 显示 pHash 时**不要**用 `entry.pHash and 0xFFFFFFFFL` + `"%08X"`（只显示低 32 位）。曾经修复：`pHash = 0x800000000000`（高 51 位 bit）被显示成 `00000000`，用户看不到真实值。统一用 `String.format("%016X", entry.pHash)` 完整 64-bit hex，与 history 列表 `HistoryMangaAdapter` (`entry.pHash = "%016X"`) 保持一致。

- **DialogPreference（ColorPreferenceCompat 等）不能用 `setOnPreferenceClickListener` 拦截点击**：`DialogPreference` 通过 `PreferenceFragmentCompat.onDisplayPreferenceDialog()` 展示弹窗，普通 click listener 返回 `true` 也无法阻止弹窗。**正确方式**：重写 `onDisplayPreferenceDialog(pref)`，匹配 `pref.key` 后显示自定义弹窗，其余走 `super`。

- **AlertDialog 自定义布局 View 不用 `dialog.findViewById`**：`AlertDialog.Builder.setView(view)` 传入自定义布局后，通过 `dialog.findViewById(R.id.xxx)` 查找子 View 不可靠（可能返回 null，尤其是 dialog 未 show 时）。**正确做法**：inflate 布局后从 `view.findViewById(...)` 直接持有 View 引用，在 `create()` 前后操作该引用。

- **`AlertDialog.setView()` + `window.setLayout()` 按钮被推出屏幕**：`AlertDialog.Builder.setView(view)` 替换内容区域后，再通过 `dialog.window.setLayout(width, fixedHeight)` 约束高度，如果固定高度过小（<55% 屏幕高），底部的确定/取消按钮可能被推出屏幕外。**原因**：固定高度限制的是 content 区域而非 dialog 整体（title + content + button 区总高 > fixedHeight）。**修复**：用 `setMessage()`（内部自动绑定 ScrollView）替代 `setView()` 约束内容高度；或 `setView()` 时高度用 `WRAP_CONTENT`，只限制宽度百分比。

- **`AlertDialog.setMessage()` 公告内容必须 `Html.fromHtml()` 渲染**：`TranslateFragment.showNotificationDialog` 接收 Gist content 用 `android.text.Html.fromHtml(content, FROM_HTML_MODE_LEGACY)` 渲染。**直接 `setMessage` 纯文本会显示字面 `<br>` 标签**（v0.9.2 早期版本踩坑）。Gist content 已约定用 HTML 标记（见「Gist 公告格式」），不要换成纯文本。

- **`OpenAIText.testConnection` 在 `isNew=true` 时 `providerIndex` 越界**：`ManageActivity` 启动「新增自定义 API」fragment 时传 `custom_code = allProviders.size`（越界值，APIConfig.kt:674）。OpenAIText 接收后 `providerIndex = allProviders.size` 指向数组末尾的下一个位置。`testConnection` line 746 无条件访问 `allProviders[providerIndex]` → `ArrayIndexOutOfBoundsException` → HTTP 请求根本没发出，弹"测试失败：Index N out of bounds for length N"（N 为当前 provider 数）。修复：line 746 用 `if (providerIndex in allProviders.indices)` 安全访问，越界时显示 `"custom[new]"`；`setupUserMode()` 在 `isNew=true` 时显式 `switchAutoAppendPath.isChecked = true`。

## UI 规范

- **禁止使用系统原生弹窗和选择器**：所有弹窗、菜单、选择器必须使用应用自身的样式，禁止系统原生样式（白色背景、系统字体）
  - 弹窗：使用 `android.app.AlertDialog` + 自定义布局（⚠️ Material3 主题与 MaterialAlertDialogBuilder 不兼容，会崩溃）
  - 选择列表/选项：使用 `android.app.AlertDialog` + `setItems` / `setSingleChoiceItems` / 自定义 RadioGroup
  - 底部弹窗：使用 `BottomSheetDialogFragment`，禁止系统 `Dialog`
  - 下拉选择器：禁止系统 `Spinner`（白色下拉菜单），使用 Material `MaterialAutoCompleteTextView` + `ExposedDropdownMenu` 或自定义下拉
- **禁止使用系统级窗口**：所有 UI 必须在 Activity/Fragment 内实现，禁止 `TYPE_APPLICATION_OVERLAY` 以外的系统窗口
- 所有 UI 组件优先使用 Material Design 组件库（`com.google.android.material.*`）

## 弹窗系统（选型规则）

**翻译状态类消息 → 统一用 `TranslationStatusOverlay`，不要用系统 Toast。** 它是全局共享单例，最多 3 条消息纵向堆叠（第 2/3 行不重叠），可配置位置（上/中/下）和时长。

| 需求场景 | 用哪个 | 说明 |
|---------|--------|------|
| 翻译状态/初始化/进度/模型切换 | `TranslationStatusOverlay` | 共享单例、多消息堆叠、圆角动画 |
| 一次性操作反馈（保存成功、复制、下载完成） | `UiUtils.showToast` | 底部系统 Toast |
| 需要确认/操作/选择 | `android.app.AlertDialog` | 必须自定义布局，禁止原生样式 |
| 底部面板/菜单 | `BottomSheetDialogFragment` | 禁止系统 `Dialog` |
| 常驻可拖动浮层 | `TranslationResultView` / 悬浮球 | WindowManager |

**消息方法选择（`TranslationStatusOverlay`）：**
- `show(message)`：普通消息，堆叠显示，到时自动消失（初始化信息、启停提示）
- `showImmediate(message, autoDismiss = false)`：**进度/状态**消息，复用顶部第一条，下面堆叠的保留（检测中/翻译中/模型切换）
- `showError(message)`：错误，红色、可点击复制
- `update(message)`：更新顶部文字（进度实时刷新，不重置计时器）
- `dismiss()`：清空全部（进度结束）；`release()`：服务销毁时调用

**代码示例：**
```kotlin
// 1. 翻译状态浮层（共享单例）
val overlay = TranslationStatusOverlay.getInstance(this)
overlay.show("初始化中...")                                  // 第 1 行
overlay.show("翻译 API 初始化成功")                          // 第 2 行（堆叠）
overlay.showImmediate("检测中...", autoDismiss = false)       // 进度，复用顶部
overlay.showError("翻译失败: ${e.message}")                   // 红色可复制
overlay.dismiss()                                            // 清空

// 2. 一次性操作反馈（底部系统 Toast）
UiUtils.showToast(context, "已保存", isShort = true)

// 3. 确认/操作弹窗（AlertDialog + 自定义布局）
AlertDialog.Builder(requireContext())
    .setTitle(R.string.xxx)
    .setMessage(R.string.xxx)
    .setPositiveButton(R.string.confirm) { _, _ -> /* 操作 */ }
    .setNegativeButton(R.string.user_cancel, null)
    .show()
```

**个性化配置键**（`CustomPreference`）：`status_overlay_enabled`（总开关）、`Status_Position`（top/center/bottom）、`Status_Duration`（毫秒，默认 2000）。

⚠️ 详细设计与消息堆叠行为见本地参考文档 `tools/popup-system.md`（gitignore，不提交）。

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
$pid = & $adb shell pidof com.moe.starflow
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

**单文件要求**：Gist 只能保留一个名为 `gist_v086_final.txt` 的文件。`NotificationChecker` 通过 `raw` URL 拉取（按文件名字母序返回第一个），多余文件（如 `gist_content.txt`）会让 raw URL 返回旧版本内容（v0.9.2 发布时踩坑）。

**Content 用 HTML 标记**：`content` 字段支持 `<b>...</b>`、`<br>`、`<ul><li>` 等简单标记，app 用 `Html.fromHtml(content, FROM_HTML_MODE_LEGACY)` 渲染。**不要用 Markdown `**bold**`**——app 不渲染星号。`\n` 在 JSON 字符串里用字面 `\\n`（JSONParser 解析为真换行），不要用真实换行（破坏 JSON 结构）。

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

## Skill routing

当用户请求匹配可用 skill 时，通过 Skill 工具调用。不确定时也调用。

关键路由规则：
- 发布版本 → 调用 `/release-replace`
- 审查代码变更 → 调用 `/review`
- Bug/错误排查 → 先查日志，定位代码后修复
- UI 布局问题 → 直接对比代码找差异，不要求看日志
- 构建/安装 → 使用构建命令，安装前不卸载

## .claude/ 目录

```
.claude/
├── skills/                   # 项目级 skill（如 release-replace）
│   └── release-replace/      # 版本发布管理
├── plans/                    # 实现计划草稿（gitignored）
└── worktrees/                # git worktree 隔离工作区（自动清理）
```

## 常见问题排查

### 构建失败

```powershell
# 检查 local.properties 是否存在
Test-Path local.properties
# 内容应为：sdk.dir=C:/Users/<username>/AppData/Local/Android/Sdk
```

### 安装失败

**绝对禁止未经确认执行 `adb uninstall`。** 常见原因：
- **签名不一致**：debug/release 签名不同，需先卸载旧版本（用户确认后）
- **INSTALL_FAILED_UPDATE_INCOMPATIBLE**：同上
- **设备空间不足**：清理设备存储

### 日志无输出
- PID 过期：app 重启后 PID 变化，需重新获取
- 日志 tag 未匹配：检查 `LogCollector` tag 是否在过滤列表中
