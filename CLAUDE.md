# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 提供项目上下文。

## 项目概述

MoeTranslate（萌译）是一款 Android 截图翻译应用，支持 Android 11+（API 29+）。使用 Accessibility Service 截屏，PP-OCRv5 / ML Kit 本地 OCR 识别文字，然后调用各种翻译 API 进行翻译。

## 目录规范

- **`.reference/`** — 参考项目，只读。用于克隆第三方开源项目作为代码参考，禁止修改。
  - `comic-text-detector-master/` — CTD 检测器参考源码
  - `manga-image-translator/` — manga-image-translator 参考源码
  - `RapidOcrAndroidOnnxCompose/` — RapidOCR Kotlin 版 Android 集成参考
  - `RapidOcrAndroidOnnx/` — RapidOCR C++ 版 Android 集成参考
- **`tools/`** — 测试模型和脚本。用于本地测试转换后的模型（ONNX、TFLite 等），模型文件放这里。
  - `ppocrv5_onnx/` — PP-OCRv5 ONNX 模型（det + rec_zh）
  - `ctd_640.onnx` / `ctd_640.onnx.data` — CTD 640 检测模型
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
├── CTDModelManager           # CTD 模型（单文件下载，~94MB）
├── RTDetrModelManager        # RT-DETR-V2 模型（单文件下载，~11MB）
└── MangaOcrDownloadManager   # manga-ocr 模型（逐文件下载）
```

### 当前使用的模型

| 模型 | 用途 | 大小 | 来源 | 存储位置 |
|------|------|------|------|----------|
| **PP-OCRv5 det** | 文字区域检测 | ~84MB | [RapidAI/RapidOCR](https://modelscope.cn/models/RapidAI/RapidOCR) | assets/ppocrv5/ 内置 |
| **PP-OCRv5 cls** | 方向分类 | ~1MB | [RapidAI/RapidOCR](https://modelscope.cn/models/RapidAI/RapidOCR) | assets/ppocrv5/ 内置 |
| **PP-OCRv5 rec zh** | 中文识别 | ~81MB | [RapidAI/RapidOCR](https://modelscope.cn/models/RapidAI/RapidOCR) | assets/ppocrv5/ 内置 |
| **PP-OCRv4 rec ja** | 日文识别 | ~9.4MB | [RapidAI/RapidOCR](https://modelscope.cn/models/RapidAI/RapidOCR) | assets/ppocrv5/ 内置 |
| **PP-OCRv3 latin rec** | 英文/拉丁文识别 | ~8.6MB | [RapidAI/RapidOCR](https://modelscope.cn/models/RapidAI/RapidOCR) | assets/ppocrv5/ 内置 |
| **PP-OCRv4 rec ko** | 韩文识别 | ~23MB | [RapidAI/RapidOCR](https://modelscope.cn/models/RapidAI/RapidOCR) | assets/ppocrv5/ 内置 |
| **CTD** (Comic Text Detector) | 文字区域检测 | ~94MB | GitHub releases | filesDir/ 下载 |
| **RT-DETR v2** | 文字/气泡检测 | ~11MB | [HuggingFace](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) | filesDir/ 下载 |
| **manga-ocr** | 竖排日文识别 | ~460MB | [HuggingFace](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) | filesDir/ 下载 |

### 模型详解

**PP-OCRv5 — 文字检测+识别（默认，内置 ~207MB）**
- 三阶段流水线：det → cls → rec，对齐 RapidOCR Python 实现
- det：DBPostProcess（thresh=0.3, box_thresh=0.5, unclip_ratio=1.6），JTS BufferOp 替代 pyclipper
- cls：PP-OCRv5 shape=[3,80,160]，score>0.9 旋转 180°
- rec：CTCLabelDecode（blank=0, remove_duplicate），单行识别
- rec 模型按语言懒加载：zh(81MB)、ja(9.4MB)、en(8.6MB)、ko(23MB)
- `getRotateCropImage`：DLT 透视变换 + 竖排自动旋转 90°
- 所有模型输入名均为 `x`，ONNX Runtime Android 1.19.0
- 引擎类：`PPOcrV5Engine.kt`（singleton object）
- 字典加载：blank 插入 index 0，space 插入末尾

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

**RT-DETR v2（文字/气泡检测器）— 需下载，~11MB**
- 用途：同时检测文字和对话气泡，区分气泡内文字和自由文字
- 适用场景：漫画翻译（需要区分对话气泡和旁白），支持日漫、韩漫、美漫
- 输入：640×640
- 输出：300 个检测框 + 置信度 + 类别
- 类别：`bubble`（空对话气泡）、`text_bubble`（气泡内文字）、`text_free`（自由文字/旁白）
- 推理速度：INT8 小版手机 CPU < 0.5 秒/帧
- 优势：模型小、速度快、直接返回检测框无需复杂后处理、能区分文字和气泡
- 下载地址：`https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/main/detector-v4-s_int8.onnx`
- 管理器：`RTDetrModelManager.kt`，存储在 `filesDir/rt_detr/`

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
| `assets/` | APK 内置 | PP-OCRv5 全套（det+cls+rec 4语言，~207MB） |
| `filesDir/` | `/data/.../files/<modelDir>/` | CTD、RT-DETR-V2、manga-ocr（需下载，可删除） |

初始化时检查顺序：PP-OCRv5 从 `assets` 加载；CTD/RT-DETR-V2/manga-ocr 仅从 `filesDir` 加载，未下载时抛异常提示。

### 普通/高级模式

悬浮窗菜单支持两种模式，通过 `Manga_Advanced_Mode` 偏好切换（关于页面开关）：
- **普通模式**（默认）：固定搭配，菜单只有一个"模型"选项
  - MLKit → det=MLKIT, ocr=MLKit（全部内置）
  - PP-OCRv5 → det=PP_OCR_V5, ocr=PPOcrV5（全部内置）
  - manga-ocr → det=RT_DETR_V2, ocr=MangaOcr（需下载两个模型）
  - 缺失模型时 toast 提示并回退 MLKit
- **高级模式**：自由搭配检测器+识别器，菜单分两个选项

### 添加新模型下载的步骤

1. **创建 ModelManager**（参考 `CTDModelManager`）：
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
**文件：** `MangaFloatingService.kt`（主服务）、`DetectionBridge.kt`（检测桥接）、`CTDDetector.kt`/`CTDPostProcessor.kt`（CTD 检测）、`ComicBubbleDetector.kt`（RT-DETR-V2 检测）、`PPOcrV5Engine.kt`（PP-OCRv5 det+cls+rec）、`MangaOcrRecognizer.kt`/`MangaOcrBridge.kt`（manga-ocr ONNX 推理）、`MangaOcrDownloadManager.kt`（版本管理）、`ModelDownloadManager.kt`（统一下载器）、`BoxMerger.kt`/`BubbleMerger.kt`（合并）、`QuadBox.kt`（旋转四边形）、`OverlayRenderer.kt`（渲染）

**检测引擎（DetEngine 枚举）：**
- `MLKIT(0)` — ML Kit 检测+识别一体化（默认值已改为 PP-OCRv5）
- `CTD(1)` — CTD 检测 + 指定 OCR 引擎，保留旋转四边形信息
- `RT_DETR_V2(3)` — RT-DETR-V2 气泡/文字检测 + 指定 OCR 引擎
- `PP_OCR_V5(4)` — PP-OCRv5 独立 det+cls+rec 全流水线（**默认**）

**OCR 引擎（OcrEngine 枚举）：**
- `MLKit(0)` — ML Kit 识别
- `MangaOcr(1)` — manga-ocr 识别
- `PPOcrV5(4)` — PP-OCRv5 识别（**默认**）

**检测引擎特点：**
- `detectWithCTD` — CTD 检测 + 指定 OCR 引擎；**PPOcrV5 跳过前合并**（单行识别器，每个 QuadBox 独立透视裁剪），MLKit 和 MangaOcr 走 BoxMerger 前合并（可处理多行输入）；CTD + PPOcrV5 走 BubbleDetector 后合并，CTD + MLKit/MangaOcr 跳过后合并
- `detectWithRTDetrV2` — RT-DETR-V2 气泡/文字检测 + 指定 OCR 引擎；无前合并，识别结果走 BubbleDetector 后合并
- `detectWithMLKit` — ML Kit 检测+识别一体化；识别结果走 BubbleDetector 后合并
- `detectWithPPOcrV5` — PP-OCRv5 独立 det+cls+rec 全流水线；识别结果走 BubbleDetector 后合并

**Debug 系统：** 关于页面可开启 4 个独立 debug 开关（CTD / RT-DETR-V2 / MLKit / PP-OCRv5），按当前 `config.detEngine` 决定走哪条 debug 路径，其余正常翻译。

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
- CTD 模型需下载（~94MB），存储在 `filesDir/ctd/`，DETECT_SIZE=1024

**翻译流程：** 截图 → 检测 → OCR 识别 → BubbleDetector 后合并 → 翻译（每气泡并行）→ 覆盖渲染（半透明背景 + 竖排/横排文字）

**合并机制（两阶段）：**

1. **前合并（BoxMerger，OCR 之前）**：CTD + MLKit 和 CTD + MangaOcr 使用。CTD 检测出文字行级 QuadBox，BoxMerger 按距离/方向合并为区域级，再裁剪送入 OCR 引擎。PPOcrV5 是单行识别器，跳过前合并，每个 QuadBox 独立透视裁剪。
2. **后合并（BubbleDetector，OCR 之后）**：CTD + PPOcrV5、RT-DETR-V2、MLKit、PP-OCRv5 使用。`BubbleDetector.detectBubbles()` 接收已识别的 `TextBlockInfo` 列表，按距离/方向/MST 分割合并为气泡级 `BubbleRegion`。CTD + MLKit/MangaOcr 已有前合并，跳过后合并。

简记：前合并合并检测框（OCR 前），后合并合并识别结果（OCR 后）。

**关键类型：**
- `TextDirection` 枚举：`VERTICAL_RL`（右→左）、`VERTICAL_LR`（左→右）、`HORIZONTAL`
- `MangaModeConfig`：textDirection、fontSize、autoFontSize、smartBackground、sourceLang、targetLang
- `TranslatedBubble`：rect、originalText、translatedText、backgroundColor

**功能：** 框选/全屏翻译、自动翻译（定时器 + OCR 相似度检测）、自适应字体大小（自动缩小以适应区域）、菜单对话框（动态模式标签）

**全屏/调试 overlay 定位：**
- 全屏模式和所有调试模式（CTD / RT-DETR-V2 / ML Kit / PP-OCRv5）都不能用 `MATCH_PARENT`，会被系统栏影响导致左右空白或变形
- 正确做法：用 `resources.displayMetrics.widthPixels/heightPixels` 获取屏幕真实像素尺寸，显式设置 overlay 的 `width`/`height`，配合 `FIT_XY` + `FLAG_LAYOUT_NO_LIMITS`
- 框选模式不受影响，因为 overlay 精确定位到框选区域
- ML Kit / PP-OCRv5 调试模式的底部信息栏用 ScrollView 浮在图片上方，不能挤压 ImageView

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
tag:OCRBridge | tag:CTDDetector | tag:CTDPostProcessor | tag:BoxMerger | tag:DetectionBridge | tag:BubbleDetector | tag:OverlayRenderer | tag:MangaFloatingService | tag:MangaOcrBridge | tag:MangaOcrRecognizer | tag:PPOcrV5Engine | tag:OCRTextRecognizer
```

| tag | 模块 |
|-----|------|
| `OCRBridge` | OCR 桥接 |
| `CTDDetector` | CTD 检测 |
| `CTDPostProcessor` | CTD 后处理 |
| `DetectionBridge` | 检测桥接 |
| `PPOcrV5Engine` | PP-OCRv5 引擎（det+cls+rec） |
| `MangaOcrBridge` | manga-ocr 桥接 |
| `MangaOcrRecognizer` | manga-ocr 识别 |
| `BubbleDetector` | 气泡检测 |
| `OverlayRenderer` | 渲染 |

## 关键约束

- **minSdk 29**（Android 10+），**targetSdk 35**
- **仅支持 arm64-v8a** — 不支持 32 位
- 需要 Accessibility Service 进行截屏（非 MediaProjection）
- `FloatingBallService` 使用 `foregroundServiceType="mediaProjection"`
- 许可证：LGPL（原项目）
