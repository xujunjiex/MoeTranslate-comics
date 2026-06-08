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

## 架构

**包结构** (`app/src/main/java/com/moe/moetranslator/`):

- `translate/` — 游戏翻译引擎：`FloatingBallService`（主服务）、`ScreenShotAccessibilityService`（截屏）、`OCRTextRecognizer`（ML Kit OCR）、`TranslationTextAPI`/`TranslationPicAPI`（翻译接口）
- `manga/` — 漫画翻译引擎：气泡检测 + OCR + 翻译 + 竖排文字渲染
- `bridge/` — 桥接层：`OCRBridge`、`TranslateBridge`、`ScreenshotBridge`
- `me/` — 设置和 API 配置界面
- `launch/` — 首次启动引导
- `utils/` — 工具类、`Constants` 枚举定义、`UpdateChecker`（检查更新）
- `data/` — Room 数据库、`TranslationCacheManager`、`HistoryEntity`/`PageCacheEntity`
- `ui/history/` — 历史记录 UI：`HistoryFragment`、`HistoryGameAdapter`、`HistoryMangaAdapter`

**翻译 API 实现** (`app/src/main/java/translationapi/`):
每个子目录实现 `TranslationTextAPI` 接口：`openaitranslation/`、`bingtranslation/`、`mlkittranslation/`、`nllbtranslation/`、`niutrans/`、`volctranslation/`、`deepltranslation/`、`baidutranslation/`、`tencentcloud/`、`azuretranslation/`、`customtranslation/`

**关键接口：**
- `TranslationTextAPI.getTranslation(text, sourceLanguage, targetLanguage, callback)` — 文本翻译
- `TranslationPicAPI.getTranslation(bitmap, sourceLanguage, targetLanguage, callback)` — 图片翻译

**截图流程：** `ScreenShotAccessibilityService` → `ScreenshotManager.screenshotFlow`（SharedFlow）→ `FloatingBallService` 接收处理

**配置存储：** `CustomPreference` 单例封装 `SharedPreferences`。API 密钥通过 `KeystoreManager` 加密存储。

**UI：** 传统 Android Views + ViewBinding（非 Jetpack Compose）。导航使用 Navigation Component。

**构建模块：** `:app`。原生代码通过 CMake 构建（`app/src/main/cpp/`）。

## 模型管理

### 当前使用的模型

| 模型 | 用途 | 大小 | 来源 | 存储位置 |
|------|------|------|------|----------|
| **PP-OCRv5 det** | 文字区域检测 | ~4.6MB | RapidAI/RapidOCR | assets/ppocrv5/ 内置 |
| **PP-OCRv5 cls** | 方向分类 | ~1MB | RapidAI/RapidOCR | assets/ppocrv5/ 内置 |
| **PP-OCRv5 rec zh** | 中文识别 | ~16MB | RapidAI/RapidOCR | assets/ppocrv5/ 内置 |
| **PP-OCRv5 rec en** | 英文/拉丁文识别 | ~8.6MB | RapidAI/RapidOCR | assets/ppocrv5/ 内置 |
| **PP-OCRv5 rec ko** | 韩文识别 | ~13MB | RapidAI/RapidOCR | assets/ppocrv5/ 内置 |
| **Bubble Detector** | 气泡检测 | ~11MB | 内置 | assets/bubble_detector/ |
| **CTD** | 文字区域检测 | ~94MB | GitHub releases | filesDir/ 下载 |
| **RT-DETR v2** | 文字/气泡检测 | ~11MB | HuggingFace | filesDir/ 下载 |
| **manga-ocr** | 竖排日文识别 | ~460MB/~135MB | HuggingFace | filesDir/ 下载 |

内置模型合计约 **53MB**。

### 下载管理器

```
ModelDownloadManager          # 统一 HTTP 下载器（断点续传、重试、进度回调）
├── CTDModelManager           # CTD 模型（单文件下载，~94MB）
├── RTDetrModelManager        # RT-DETR-V2 模型（单文件下载，~11MB）
└── MangaOcrDownloadManager   # manga-ocr 模型（逐文件下载，支持 FULL/V2025 版本）
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

**核心文件：** `MangaFloatingService.kt`（主服务）、`DetectionBridge.kt`（检测桥接）、`CTDDetector.kt`/`CTDPostProcessor.kt`（CTD 检测）、`ComicBubbleDetector.kt`（RT-DETR-V2 检测）、`PPOcrV5Engine.kt`（PP-OCRv5 det+cls+rec）、`MangaOcrBridge.kt`（manga-ocr）、`BoxMerger.kt`（合并）、`OverlayRenderer.kt`（渲染）

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

**翻译流程：** 截图 → 检测 → OCR → 气泡合并（按需）→ 翻译（每气泡并行）→ 覆盖渲染

**Debug 系统：** 关于页面可开启 4 个独立 debug 开关（CTD / RT-DETR-V2 / MLKit / PP-OCRv5），按当前 `config.detEngine` 决定走哪条 debug 路径。调试菜单为二级结构（一级标题带图标，二级开关无图标）。

**全屏/调试 overlay 定位：**
- 全屏和调试模式不能用 `MATCH_PARENT`，用 `resources.displayMetrics.widthPixels/heightPixels` 获取真实尺寸
- 配合 `FIT_XY` + `FLAG_LAYOUT_NO_LIMITS`

## 自动翻译

### 游戏翻译（像素驱动）

**核心文件：** `AutoTranslateEngine.kt`（状态机）、`FloatingBallService`（主服务）、`GameOcrEngine.kt`（OCR 封装）、`PixelCompare.kt`（像素比较）、`GameDebugOverlay.kt`（调试浮窗）

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
- 漫画模式：pHash 精确匹配 + 相似度匹配（阈值 0.92）
- 游戏模式：仅精确匹配（相似度匹配会误判相似背景）
- Room 数据库 `translation_history.db`，version 2，`fallbackToDestructiveMigration`
- 历史 UI：`ui/history/HistoryFragment`，游戏列表 + 漫画网格

**缓存标识：**
- 游戏翻译：`FloatingBallService` 缓存命中时，翻译文本前显示"⚡"标识（紧凑前缀，不换行）
- 漫画翻译：`MangaFloatingService` 缓存命中时，`showCacheOverlay()` 显示左上角橙色"⚡ 缓存"标签 + 刷新按钮

## 日志规范

**所有日志必须通过 `LogCollector` 写入**，不能直接用 `Log.d/i/e`。

logcat 过滤器：
```
tag:OCRBridge | tag:CTDDetector | tag:CTDPostProcessor | tag:BoxMerger | tag:DetectionBridge | tag:BubbleDetector | tag:OverlayRenderer | tag:MangaFloatingService | tag:MangaOcrBridge | tag:MangaOcrRecognizer | tag:PPOcrV5Engine | tag:OCRTextRecognizer | tag:TranslationCacheManager | tag:AutoTranslateEngine | tag:FloatingBallService | tag:GameOcrEngine | tag:Screenshot
```

## 关键约束

- **minSdk 29**（Android 10+），**targetSdk 35**
- **仅支持 arm64-v8a** — 不支持 32 位
- 需要 Accessibility Service 进行截屏（非 MediaProjection）
- `FloatingBallService` 使用 `foregroundServiceType="mediaProjection"`
- 许可证：LGPL（原项目）

## 网络配置

- GitHub/HuggingFace 下载需代理（国内环境）
- CLI 工具（gh/curl/npm）需设置 `http_proxy`/`https_proxy` 环境变量，或开启 TUN 模式
- Windows hosts 文件可能有 `#S302` 条目将 github.com 指向 127.0.0.1，需清理
- git 代理配置：`git config --global http.proxy http://127.0.0.1:7897`

## 检查更新

`UpdateChecker.kt` 调用 GitHub Releases API 检查新版本，对比 versionCode（如 `v0.0.2` → `2`），有新版本时弹窗提示并跳转到对应 Release 页面。
