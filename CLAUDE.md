# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 提供项目上下文。

## 项目概述

MoeTranslate（萌译）是一款 Android 截图翻译应用，支持 Android 11+（API 29+）。使用 Accessibility Service 截屏，ML Kit 本地 OCR 识别文字，然后调用各种翻译 API 进行翻译。

## 构建命令

```bash
# 构建 debug APK
./gradlew assembleDebug

# 安装 debug APK 到设备
adb install app/build/outputs/apk/debug/app-debug.apk

# 构建 release APK
./gradlew assembleRelease

# 清理构建
./gradlew clean assembleDebug

# 运行单元测试
./gradlew test

# 运行仪器测试
./gradlew connectedAndroidTest
```

环境要求：JDK 17、Android SDK（compileSdk 35）、NDK 25.2.9519653、CMake 3.22.1。

## 架构

**包结构** (`app/src/main/java/com/moe/moetranslator/`):

- `translate/` — 核心翻译引擎：`FloatingBallService`（主服务）、`ScreenShotAccessibilityService`（截屏）、`OCRTextRecognizer`（ML Kit OCR）、`TranslationTextAPI`/`TranslationPicAPI`（翻译接口）、`AccessibilityServiceManager`（单例服务引用）
- `manga/` — **扩展模块**：漫画气泡翻译，支持竖排文字渲染
- `bridge/` — **扩展模块**：桥接层，调用原项目 API（`OCRBridge`、`TranslateBridge`、`ScreenshotBridge`）
- `me/` — 设置和 API 配置界面
- `geminiapi/` — Gemini AI 聊天功能
- `madoka/` — Live2D 查看器
- `launch/` — 首次启动引导
- `utils/` — 工具类、`Constants` 枚举定义

**翻译 API 实现** (`app/src/main/java/translationapi/`):
每个子目录实现 `TranslationTextAPI` 接口：`openaitranslation/`、`bingtranslation/`、`mlkittranslation/`、`nllbtranslation/`、`niutrans/`、`volctranslation/`、`deepltranslation/`、`baidutranslation/`、`tencentcloud/`、`azuretranslation/`、`customtranslation/`

**关键接口：**
- `TranslationTextAPI.getTranslation(text, sourceLanguage, targetLanguage, callback)` — 文本翻译
- `TranslationPicAPI.getTranslation(bitmap, sourceLanguage, targetLanguage, callback)` — 图片翻译
- `OCRTextRecognizer.getPicText(language, bitmap, mergeMode)` — 返回纯文本（无位置信息）

**截图流程：** `ScreenShotAccessibilityService` → `ScreenshotManager.screenshotFlow`（SharedFlow）→ `FloatingBallService` 接收处理

**配置存储：** `CustomPreference` 单例封装 `SharedPreferences`。API 密钥通过 `KeystoreManager` 加密存储。

**UI：** 传统 Android Views + ViewBinding（非 Jetpack Compose）。导航使用 Navigation Component。

**构建模块：** `:app` + `:framework`（Live2D SDK）。原生代码通过 CMake 构建（`app/src/main/cpp/`）。

## 扩展模块（manga/、bridge/）

作为独立模块添加。已修改原项目文件：`AndroidManifest.xml`、`strings.xml`、`arrays.xml`。

### 桥接层
- `ScreenshotBridge` 封装 `ScreenshotManager.screenshotFlow` + `AccessibilityServiceManager.takeScreenshot()`
- `OCRBridge` 直接调用 ML Kit 实现带位置信息的 OCR（原项目的 `OCRTextRecognizer` 仅返回纯文本）
- `TranslateBridge` 从 `CustomPreference` 读取配置，实例化对应的 `TranslationTextAPI`

### 漫画模块
**文件：** `MangaFloatingService.kt`、`MangaModeConfig.kt`、`VerticalTextRenderer.kt`、`OverlayRenderer.kt`、`BubbleDetector.kt`、`BackgroundAnalyzer.kt`、`DetectionBridge.kt`、`CTDDetector.kt`、`CTDPostProcessor.kt`、`BoxMerger.kt`、`BubbleMerger.kt`、`TextLine.kt`、`QuadBox.kt`、`MangaOcrRecognizer.kt`、`MangaOcrBridge.kt`、`CtcOcrRecognizer.kt`

**检测引擎（DetEngine 枚举）：**
- `CTD` — CTD 检测 + 指定 OCR 引擎，保留旋转四边形信息
- `HYBRID` — CTD 检测 + 混合 OCR（合并组→manga-ocr，单框→MLKit）
- `DBNET` — DBNet 检测 + 指定 OCR 引擎
- `MLKIT` — ML Kit 检测+识别，一体化

**OCR 引擎（OcrEngine 枚举）：**
- `MLKit` — ML Kit 识别，逐个调用，支持 language 参数
- `MangaOcr` — manga-ocr 识别，`recognizeBatch` 内部逐个调用（resize 224x224 扭曲问题未解决），过滤纯符号
- `CTCOcr` — 48px_ctc 识别，真正 batch（CTC 支持可变高度），过滤纯符号

**检测引擎特点：**
- `detectWithCTD` — CTD 检测 + 指定 OCR 引擎，统一入口，对漫画竖排文字更精确
- `detectWithCTDHybrid` — CTD + 混合模式，合并组走 manga-ocr，单框走 MLKit
- `detectWithDBNet` — DBNet 检测 + OCR 后合并，支持批量识别
- `detectWithMLKit` — ML Kit 检测+识别，一体化，返回轴对齐矩形

**参考项目：** `.reference/manga-image-translator/` — manga-image-translator 官方源码，textline_merge/__init__.py 是核心参考

**unclip 实现：**
- 使用 JTS `BufferOp`（Vatti clipping 算法）进行多边形扩张，与 manga-image-translator 的 pyclipper 行为一致
- 旧版 `utils/clipper/` 实现已删除（符号处理有 bug）

**BoxMerger.merge() vs splitTextRegion：**
- `canMergeRegion()` 使用 `polyDistance`（多边形最近边距离）判断是否连接（`discared_connection_gap=2f`）
- `splitTextRegion()` 使用 MST（Kruskal）+ 标准差判断是否需要拆分（`sigma=2`, `gamma=0.5`, `std_threshold=max(0.3*avgFontSize+5, 5)`）
- Kotlin 实现完全对齐 Python `textline_merge/__init__.py` 第 10-83 行（split）和 134-141 行（merge）

### CTD 检测特点
- `detectQuadBoxes` 返回旋转四边形（含 font_size、angle），用于精确 merge
- `canMergeWithDynamicThreshold` 对齐 manga-image-translator 过滤器模式
- 使用 QuadBox 结构线计算真实 font_size，而非 AABB 代理
- CTD 模型位于 `ctd/` 目录，需单独下载

**翻译流程：** 截图 → OCR（ML Kit 带位置信息）→ 气泡检测（聚类文字块）→ 翻译（每气泡并行）→ 覆盖渲染（半透明背景 + 竖排/横排文字）

**关键类型：**
- `TextDirection` 枚举：`VERTICAL_RL`（右→左）、`VERTICAL_LR`（左→右）、`HORIZONTAL`
- `MangaModeConfig`：textDirection、fontSize、autoFontSize、smartBackground、autoDetectBubble、sourceLang、targetLang
- `TranslatedBubble`：rect、originalText、translatedText、backgroundColor

**功能：** 框选/全屏翻译、自动翻译（定时器 + OCR 相似度检测）、自适应字体大小（自动缩小以适应区域）、菜单对话框（动态模式标签）

**服务：** `MangaFloatingService` — 独立前台服务，拥有自己的悬浮球。

## 修改过的原项目文件

| 文件 | 修改内容 |
|------|----------|
| `app/src/main/AndroidManifest.xml` | 添加 `MangaFloatingService` 服务声明、权限声明 |
| `app/src/main/res/values-zh/strings.xml` | 添加漫画翻译相关中文字符串（菜单、模式标签等） |
| `app/src/main/res/values/strings.xml` | 添加漫画翻译相关默认字符串 |
| `app/src/main/res/values-en/strings.xml` | 添加漫画翻译相关英文字符串 |
| `app/src/main/res/values-zh/arrays.xml` | 添加漫画菜单项数组（中文） |
| `app/src/main/res/values/arrays.xml` | 添加漫画菜单项数组（默认） |
| `app/src/main/res/values-en/arrays.xml` | 添加漫画菜单项数组（英文） |
| `app/src/main/res/layout/dialog_manga_menu.xml` | 漫画菜单对话框布局 |
| `app/src/main/res/drawable/fullscreen_translate.xml` | 全屏翻译图标 |

## 日志规范

**所有日志必须通过 `LogCollector` 写入**，不能直接用 `Log.d/i/e`。

`LogCollector` 同时写入：
- Android logcat（通过 `Log.d(tag, msg)`）
- 软件内置日志缓冲区（内存，最多 500 条，用户可在设置页面查看）

在 Android Studio logcat 过滤框输入以下内容，按 `|` 分隔（不需要 Regex 模式）：

```
tag:OCRBridge | tag:CTDDetector | tag:CTDPostProcessor | tag:BoxMerger | tag:DetectionBridge | tag:BubbleDetector | tag:OverlayRenderer | tag:MangaFloatingService | tag:MangaOcrBridge | tag:MangaOcrRecognizer | tag:CtcOcrRecognizer | tag:DBNetDetector | tag:DBNetPostProcessor | tag:OCRTextRecognizer
```

常用 tag：

| tag | 模块 |
|-----|------|
| `OCRBridge` | OCR 桥接（`recognizeWithLocation`、`recognizeText`） |
| `OCRTextRecognizer` | ML Kit 识别（`getPicText`） |
| `DetectionBridge` | 检测桥接（CTD Hybrid 裁剪尺寸） |
| `CTDDetector` | CTD 检测 |
| `CTDPostProcessor` | CTD 后处理 |
| `BoxMerger` | box 合并 |
| `MangaOcrBridge` | manga-ocr 桥接 |
| `MangaOcrRecognizer` | manga-ocr 识别 |
| `CtcOcrRecognizer` | 48px_ctc 识别 |
| `BubbleDetector` | 气泡检测 |
| `OverlayRenderer` | 渲染 |

## 关键约束

- **minSdk 29**（Android 10+），**targetSdk 35**
- **仅支持 arm64-v8a** — 不支持 32 位
- 需要 Accessibility Service 进行截屏（非 MediaProjection）
- `FloatingBallService` 使用 `foregroundServiceType="mediaProjection"`
- 许可证：LGPL（原项目）
