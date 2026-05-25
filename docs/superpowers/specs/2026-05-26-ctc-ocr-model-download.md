# CTCOcr OCR 引擎 + 统一模型下载管理

## 目标

1. 添加 48px_ctc OCR 引擎（快速、支持多语言）
2. 建立统一的模型下载管理系统，覆盖 CTD、DBNet、manga-ocr、ctc-ocr 模型
3. 用户可选择三种 OCR 引擎：MLKit（系统）、MangaOcr（日文）、CTCOcr（多语言快）

## 架构

### OCR 引擎枚举

```kotlin
enum class OcrEngine {
    MLKit,    // 系统 OCR（默认）
    MangaOcr, // manga-ocr（日文，慢）
    CTCOcr    // 48px_ctc（多语言，快）
}
```

### 模型管理器

| Manager | 模型 | 用途 | 下载地址 |
|---------|------|------|----------|
| CTDModelManager | comictextdetector.pt.onnx | 文字检测 | 内嵌 assets |
| DBNetModelManager | dbnet_detector.onnx | 文字检测 | 内嵌 assets |
| MangaOcrModelManager | manga_ocr_encoder/decoder.onnx | 日文 OCR | 内嵌 assets |
| CtcOcrModelManager | ocr-ctc.ckpt + alphabet-all-v5.txt | 多语言 OCR | GitHub releases |

### 文件结构

```
app/src/main/java/com/moe/moetranslator/manga/
├── ModelDownloadManager.kt    # 统一下载管理
├── CtcOcrModelManager.kt       # CTC OCR 模型管理（新建）
├── CtcOcrRecognizer.kt         # 已存在
├── CtcOcrTokenizer.kt          # 已存在
├── MangaModeConfig.kt          # 修改：ocrEngine: OcrEngine
├── DetectionBridge.kt          # 修改：支持 CTCOcr
└── MangaFloatingService.kt     # 修改：init/release/toggle
```

## 实现步骤

### 1. 创建 ModelDownloadManager

统一下载接口，支持：
- 从 GitHub releases 下载 zip
- SHA-256 校验
- 进度回调
- 断点续传（支持 Range header）

### 2. 创建 CtcOcrModelManager

```kotlin
object CtcOcrModelManager {
    const val MODEL_DIR = "ocr_ctc"
    const val MODEL_FILE = "ocr-ctc.ckpt"
    const val ALPHABET_FILE = "alphabet-all-v5.txt"
    const val DOWNLOAD_URL = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr-ctc.zip"
    const val HASH = "fc61c52f7a811bc72c54f6be85df814c6b60f63585175db27cb94a08e0c30101"
}
```

### 3. 修改 MangaModeConfig

将 `useMangaOcr: Boolean` 改为 `ocrEngine: OcrEngine`

### 4. 修改 DetectionBridge

在 CTDOCREngine 中添加 `CTCOcr` 选项

### 5. 修改 MangaFloatingService

- 添加 `initCTCOcr()`, `releaseCTCOcr()`, `initCTCOcrIfNeeded()`
- 修改 `toggleOcrEngine` 菜单支持三个选项
- 修改初始化/释放逻辑

### 6. 添加下载 UI

- 检测到模型未下载时弹出下载对话框
- 显示下载进度
- 下载完成后自动初始化

## 验证清单

- [ ] CTCOcr 模型可下载
- [ ] 下载后模型可正常加载
- [ ] CTCOcr OCR 识别正常工作
- [ ] 三种 OCR 引擎可切换
- [ ] 切换时正确初始化/释放资源