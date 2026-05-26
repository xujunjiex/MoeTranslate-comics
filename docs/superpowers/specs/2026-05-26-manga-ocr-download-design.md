# manga-ocr 下载管理设计

## 背景

用户希望将社区转换的 ONNX manga-ocr 模型（来自 HuggingFace `onnx-community/manga-ocr-base-ONNX`）添加到应用的模型管理功能中。

**核心要求：**
- 内置 manga-ocr（assets）仅用于测试，代码不要动
- 下载的 manga-ocr 与内置 manga-ocr 完全独立
- 下载的 manga-ocr 可复用现有 manga-ocr 代码逻辑
- 正式版时删除内置模型文件即可切换
- 支持多个版本共存：FULL/FP16/QUANTIZED
- 下载完成后显示当前版本
- 允许下载多个版本（不互相覆盖）

## 模型文件组织

### 目录结构

```
filesDir/manga_ocr_download/
├── FULL/
│   ├── encoder_model.onnx   (343MB)
│   └── decoder_model.onnx   (117MB)
├── FP16/
│   ├── encoder_model.onnx   (172MB)
│   └── decoder_model.onnx   (59MB)
└── QUANTIZED/
    ├── encoder_model.onnx   (87MB)
    └── decoder_model.onnx   (30MB)
```

### 内置 vs 下载对比

| | 内置 (assets) | 下载 (filesDir) |
|---|---|---|
| 目录 | `assets/manga_ocr/` | `filesDir/manga_ocr_download/{VERSION}/` |
| 文件名 | `manga_ocr_encoder.onnx`, `manga_ocr_decoder.onnx` | `encoder_model.onnx`, `decoder_model.onnx` |
| 用途 | 测试用 | 正式版使用 |
| 版本 | 单一版本 | FULL/FP16/QUANTIZED |

## 下载管理

### MangaOcrDownloadManager 修改

- 新增版本目录支持：`getModelDir(context, version)` → `filesDir/manga_ocr_download/{VERSION}/`
- `getEncoderFile(context, version)` / `getDecoderFile(context, version)` 支持版本参数
- `isModelDownloaded(context, version)` 检查指定版本是否已下载
- `getDownloadedVersion(context)` 改为返回具体版本或 null
- `getModelSizeString(context, version)` 返回指定版本大小

### 下载流程

1. 用户选择版本（Spinner：完整版/FP16/量化版）
2. 点击下载 → 创建对应版本目录 → 下载 encoder + decoder
3. 下载过程显示进度条和速度
4. 下载完成 Toast 提示，UI 更新显示"已下载 - {版本描述}"

### 多版本共存

- 下载 A 版本后，不影响 B 版本目录
- 下载前检查目标版本目录是否已有完整文件，避免重复下载
- 删除只删除选中版本目录，不影响其他版本

## 识别器设置

### 版本从属关系

manga-ocr 引擎选中后，显示版本子选项（从属关系）：

```
识别引擎: [manga-ocr          ▼]  ← 主选项
版本:    [完整版 (460MB)     ▼]  ← 从属选项（仅当 manga-ocr 选中时显示）
```

### PersonalizationConfig 修改

- 新增 `manga_rec_model_version` 配置项（当 manga-ocr 选中时才生效）
- 默认值：`FULL`

## MangaOcrRecognizer 修改

### 加载逻辑

```kotlin
// useAssets = true → 加载内置测试模型（不动）
// useAssets = false + version → 加载下载的指定版本模型
fun loadModel(context: Context, useAssets: Boolean = true, version: ModelVersion? = null)
```

### 初始化流程

```
DetectionBridge 初始化
    ↓
MangaModeConfig.OcrEngine 读取
    ↓
if MangaOcr && 使用下载模型:
    → 读取 manga_rec_model_version
    → MangaOcrRecognizer.loadModel(useAssets=false, version=xxx)
else:
    → 现有逻辑
```

## 待实现清单

- [ ] MangaOcrDownloadManager 支持版本目录
- [ ] 下载完成后显示版本信息
- [ ] 多版本共存支持
- [ ] 识别设置添加版本子选项
- [ ] MangaOcrRecognizer 支持加载下载模型（useAssets=false）
- [ ] 初始化路由逻辑修改