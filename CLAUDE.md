# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 提供项目上下文。

## 项目概述

MoeTranslate（萌译）是一款 Android 截图翻译应用，支持 Android 11+（API 29+）。使用 Accessibility Service 截屏，ML Kit 本地 OCR 识别文字，然后调用各种翻译 API 进行翻译。

## 目录规范

- **`.reference/`** — 参考项目，只读。用于克隆第三方开源项目作为代码参考，禁止修改。
  - `comic-text-detector-master/` — CTD 检测器参考源码
  - `manga-image-translator/` — manga-image-translator 参考源码
  - `RapidOcrAndroidOnnxCompose/` — RapidOCR Kotlin 版 Android 集成参考
  - `RapidOcrAndroidOnnx/` — RapidOCR C++ 版 Android 集成参考
- **`tools/`** — 测试模型和脚本。用于本地测试转换后的模型（ONNX、TFLite 等），模型文件放这里。
  - `ppocrv4_ja_rec.onnx` — PP-OCRv4 日文识别模型（9.3MB）
  - `ppocrv4_ch_rec.onnx` — PP-OCRv4 中文识别模型（10.4MB）
  - `ctd_512.onnx` / `ctd_640.onnx` — CTD 检测模型
- 禁止在项目根目录或其他非标准位置放置模型文件或参考项目。

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

## 模型管理

### 架构

三层结构：统一下载器 → 模型管理器 → 检测器/识别器初始化

```
ModelDownloadManager          # 统一 HTTP 下载器（断点续传、重试、进度回调）
├── CtcOcrModelManager        # CTC OCR 模型（zip 下载+解压）
├── MangaOcrDownloadManager   # manga-ocr 模型（逐文件下载）
└── CTDModelManager           # CTD 模型（单文件下载）
```

### 当前使用的模型

| 模型 | 用途 | 大小 | 来源 | 存储位置 |
|------|------|------|------|----------|
| **CTD** (Comic Text Detector) | 文字区域检测 | ~94MB | [dmMaze/comic-text-detector](https://github.com/dmMaze/comic-text-detector) | filesDir/ 下载 |
| **RT-DETR v2** | 文字/气泡检测（备选） | 11~172MB | [ogkalu](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) | .reference/ 待集成 |
| **48px_ctc** (CTC OCR) | 文字识别 | ~165MB | [manga-image-translator](https://github.com/zyddnys/manga-image-translator) | assets/ocr_ctc/ |
| **PP-OCRv4 JA** | 文字识别（日文） | ~9.3MB | [HuggingFace](https://huggingface.co/cycloneboy/japan_PP-OCRv4_rec_infer) | assets/ppocrv4_ja/ |
| **manga-ocr** | 文字识别（竖排日文） | ~460MB | [HuggingFace](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) | filesDir/ 下载 |

### 模型详解

**CTD（Comic Text Detector）— 文字检测（需下载，~94MB）**
- 用途：检测漫画/图片中的文字行区域，输出旋转四边形（保留文字倾斜角度）
- 适用场景：日漫、国漫等有气泡的文字检测，竖排文字效果好
- 输入：640×640（已从原版 1024 重导出加速）
- 输出：3 个头 — blk（检测框）、seg（文字 mask）、det（DB 概率图），目前只用 det
- 推理速度：手机 CPU 约 1~1.5 秒/帧
- 后处理：DB 概率图 → 阈值化 → BFS 连通域 → unclip 多边形扩张 → QuadBox
- 注意：像素阈值（sside、fontSize、width）随输入尺寸自动缩放（scale = contentHeight / 1024）
- 下载地址：`https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/comictextdetector.pt.onnx`
- 参考：`.reference/comic-text-detector-master/`

**RT-DETR v2（文字/气泡检测器）— 待集成**
- 用途：同时检测文字和对话气泡，区分气泡内文字和自由文字
- 适用场景：漫画翻译（需要区分对话气泡和旁白），支持日漫、韩漫、美漫
- 输入：640×640
- 输出：300 个检测框 + 置信度 + 类别
- 类别：`bubble`（空对话气泡）、`text_bubble`（气泡内文字）、`text_free`（自由文字/旁白）
- 推理速度：INT8 小版预计手机 CPU < 0.5 秒/帧（模型仅 11MB）
- 优势：模型小、速度快、直接返回检测框无需复杂后处理、能区分文字和气泡
- 版本：FP32 (168MB)、INT8 完整版 (44MB)、**INT8 小版 (11MB)**
- 模型文件：`.reference/comic-text-bubble-detector/`

**48px_ctc（CTC OCR）— 文字识别（内置，~165MB）**
- 用途：识别裁剪后的文字图片，输出文字内容
- 适用场景：配合检测器使用，识别单行文字（横排/竖排均可）
- 输入：高 48px，宽任意（模型 stride=4，seq_len=width/4）
- 字典：19264 字符（中日韩英多语言）
- 预处理：竖排文字需旋转 90° 逆时针后再送入模型
- 参考：`.reference/manga-image-translator/manga_translator/ocr/model_48px_ctc.py`

**manga-ocr — 文字识别（需下载）**
- 用途：专门识别竖排日文漫画文字，识别精度高于 CTC
- 适用场景：日漫竖排文字，单行识别
- 版本：
  - `FULL`：~460MB，原版精度最高
  - `V2025`：~135MB，速度更快，精度略低
- 用户通过 `ModelManagementFragment` 下载/切换/删除版本

### ModelDownloadManager（统一下载器）

`ModelDownloadManager.kt`：通用 HTTP 下载，支持：
- 断点续传（Range 请求）
- 自动重试（最多 3 次，指数退避）
- 进度回调（bytesRead, totalBytes, speed）
- SHA256 校验（可选，HuggingFace 不提供时传空字符串）
- **404/403 不重试**（客户端错误直接失败）

```kotlin
suspend fun downloadModel(
    context: Context,
    url: String,
    sha256Hash: String,      // 空字符串跳过校验
    destFile: File,
    onProgress: ProgressCallback? = null,
    maxRetries: Int = 3
): Result<Unit>
```

### 模型存储策略

| 来源 | 路径 | 模型 |
|------|------|------|
| `assets/` | APK 内置 | 48px_ctc（CTC OCR） |
| `filesDir/` | `/data/.../files/<modelDir>/` | CTD、manga-ocr（需下载，可删除） |

初始化时检查顺序：`filesDir` 优先 → `assets` 回退。见各检测器 `initialize()` 方法。

### 添加新模型下载的步骤

1. **创建 ModelManager**（参考 `CtcOcrModelManager`）：
   - 定义 `MODEL_DIR`、`MODEL_FILE` 常量
   - 实现 `getModelDir()`、`getModelFile()`、`isModelDownloaded()`
   - 实现 `downloadModel()` 调用 `ModelDownloadManager.downloadModel()`
   - 实现 `deleteModel()`、`getModelSize()`

2. **如果是多文件模型**（参考 `MangaOcrDownloadManager`）：
   - 用枚举定义版本（`ModelVersion`），每个版本指定文件名和 baseUrl
   - 逐文件下载，**每个文件下载前检查是否已存在**（跳过已下载）
   - 某个文件 404 时记录警告但不阻断整体（如 vocab.txt）

3. **在检测器中集成**：
   - `initialize()` 先检查 `filesDir` 是否有模型，没有则用 `assets`
   - 提供触发下载的入口（从 UI 调用）

### 关键规则

- **所有日志用 `LogCollector`**，不用 `Log.d/i/e`
- **下载前检查文件是否存在**，避免重复下载
- **404/403 不重试**，其他错误最多重试 3 次
- **下载使用代理** `127.0.0.1:7897`（GitHub/HuggingFace 国内需要）
- **大文件用 `.part` 后缀**，下载完成再 rename，避免不完整文件被加载

**UI：** 传统 Android Views + ViewBinding（非 Jetpack Compose）。导航使用 Navigation Component。

**构建模块：** `:app` + `:framework`（Live2D SDK）。原生代码通过 CMake 构建（`app/src/main/cpp/`）。

## 扩展模块（manga/、bridge/）

作为独立模块添加。已修改原项目文件：`AndroidManifest.xml`、`strings.xml`、`arrays.xml`。

### 桥接层
- `ScreenshotBridge` 封装 `ScreenshotManager.screenshotFlow` + `AccessibilityServiceManager.takeScreenshot()`
- `OCRBridge` 直接调用 ML Kit 实现带位置信息的 OCR（原项目的 `OCRTextRecognizer` 仅返回纯文本）
- `TranslateBridge` 从 `CustomPreference` 读取配置，实例化对应的 `TranslationTextAPI`

### 漫画模块
**文件：** `MangaFloatingService.kt`（主服务）、`DetectionBridge.kt`（检测桥接）、`CTDDetector.kt`/`CTDPostProcessor.kt`（CTD 检测）、`DBNetDetector.kt`/`DBNetPostProcessor.kt`（DBNet 检测）、`MangaOcrRecognizer.kt`/`MangaOcrBridge.kt`（manga-ocr ONNX 推理）、`CtcOcrRecognizer.kt`（48px_ctc 推理）、`MangaOcrDownloadManager.kt`（版本管理）、`ModelDownloadManager.kt`（统一下载器）、`BoxMerger.kt`/`BubbleMerger.kt`（合并）、`QuadBox.kt`（旋转四边形）、`OverlayRenderer.kt`（渲染）

**检测引擎（DetEngine 枚举）：**
- `CTD` — CTD 检测 + 指定 OCR 引擎，保留旋转四边形信息
- `HYBRID` — CTD 检测 + 混合 OCR（合并组→manga-ocr，单框→CTC）
- `DBNET` — DBNet 检测 + 指定 OCR 引擎
- `MLKIT` — ML Kit 检测+识别，一体化

**OCR 引擎（OcrEngine 枚举）：**
- `MLKit` — ML Kit 识别，逐个调用，支持 language 参数
- `MangaOcr` — manga-ocr 识别，`recognizeBatch` 内部逐个调用（resize 224x224 扭曲问题未解决），过滤纯符号
- `CTCOcr` — 48px_ctc 识别，真正 batch（CTC 支持可变高度），过滤纯符号

**检测引擎特点：**
- `detectWithCTD` — CTD 检测 + 指定 OCR 引擎，统一入口，对漫画竖排文字更精确
- `detectWithCTDHybrid` — CTD + 混合模式，合并组走 manga-ocr，单框走 CTC
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
- CTD 模型内置 `assets/ctd/`，首次使用自动复制到 cache

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

logcat 过滤器（按 `|` 分隔，不需要 Regex）：

```
tag:OCRBridge | tag:CTDDetector | tag:CTDPostProcessor | tag:BoxMerger | tag:DetectionBridge | tag:BubbleDetector | tag:OverlayRenderer | tag:MangaFloatingService | tag:MangaOcrBridge | tag:MangaOcrRecognizer | tag:CtcOcrRecognizer | tag:DBNetDetector | tag:DBNetPostProcessor | tag:OCRTextRecognizer
```

| tag | 模块 |
|-----|------|
| `OCRBridge` | OCR 桥接 |
| `CTDDetector` | CTD 检测 |
| `CTDPostProcessor` | CTD 后处理 |
| `DetectionBridge` | 检测桥接 |
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
