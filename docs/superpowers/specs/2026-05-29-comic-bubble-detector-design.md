# Comic Bubble Detector (RT-DETR-V2) 设计文档

日期: 2026-05-29
状态: 已批准

## 背景

现有检测引擎（ML Kit、CTD、HYBRID）在漫画翻译场景中存在检测精度不足的问题。用户提供了预训练的 RT-DETR-v2 模型（`detector-v4-s_int8.onnx`，11MB INT8），专门在 ~11k 漫画/漫画/网漫图片上微调，能更准确地检测气泡和自由文字区域。

## 模型规格

| 属性 | 值 |
|------|------|
| 架构 | RT-DETR-v2 (ResNet50 backbone) |
| 文件 | `detector-v4-s_int8.onnx` (11.1MB INT8) |
| 输入 | `images` [batch, 3, 640, 640] + `orig_target_sizes` [batch, 2] |
| 输出 | `labels` [1, 300], `boxes` [1, 300, 4], `scores` [1, 300] |
| 预处理 | resize 640×640, rescale /255, ImageNet normalize |
| 类别 | 0=bubble(无文字气泡), 1=text_bubble(气泡内文字), 2=text_free(自由文字) |

## 管线定位

```
截图 Bitmap
  → [检测引擎] → 文字区域框
    ├── MLKit: ML Kit 检测+OCR 一体化
    ├── CTD: CTD 检测 + 指定 OCR 引擎
    ├── HYBRID: CTD 检测 + 混合 OCR (合并组→manga-ocr, 单框→CTC)
    └── RT_DETR_V2 ★新增: 气泡检测 + 指定 OCR 引擎
  → 裁剪区域
  → [OCR 引擎] → 文字内容
  → 翻译 → 渲染
```

RT-DETR-V2 作为独立的检测引擎选项，与 MLKIT/CTD/HYBRID 平级。不影响现有引擎的任何代码路径。

## 数据流

```
截图 Bitmap
  → ComicBubbleDetector.detectBubbles(bitmap, confThreshold=0.4)
  → List<DetectedBubble> { rect: Rect, classId: Int(0/1/2), confidence: Float }
  → 过滤 classId==0（无文字气泡），只保留 text_bubble(1) + text_free(2)
  → 逐个裁剪 Rect 区域
  → OCR 引擎识别 (按 ocrEngine 配置: MLKit / MangaOcr / CTCOcr)
  → 返回 List<TextBlockInfo>（与现有管线完全兼容）
```

## 新建/修改文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `manga/ComicBubbleDetector.kt` | 新建 | ONNX 推理：预处理 → 推理 → NMS → 过滤 |
| `manga/DetectionBridge.kt` | 修改 | 添加 `detectWithRTDetrV2()` 方法 |
| `manga/MangaModeConfig.kt` | 修改 | `DetEngine` 添加 `RT_DETR_V2(3)` |
| `manga/MangaFloatingService.kt` | 修改 | 添加 RT_DETR_V2 的 init/release/route/菜单 |
| `assets/bubble_detector/model.onnx` | 新建 | 从 `.reference/` 复制模型到 assets |

## ComicBubbleDetector.kt 设计

```kotlin
object ComicBubbleDetector {
    data class DetectedBubble(rect: Rect, classId: Int, confidence: Float)

    // 生命周期（同 CTDDetector 模式）
    suspend fun initialize(context: Context)
    fun release()
    val isInitialized: Boolean

    // 检测入口
    fun detectBubbles(bitmap: Bitmap, confThreshold: Float = 0.4f): List<DetectedBubble>

    // 内部方法
    private fun preprocess(bitmap: Bitmap): OnnxTensor
    //   → resize 640×640 (双线性插值)
    //   → rescale /255.0
    //   → ImageNet normalize: (pixel - mean) / std
    //   → CHW 排列 → OnnxTensor [1, 3, 640, 640]

    private fun postprocess(boxes, labels, scores, origW, origH, confThreshold): List<DetectedBubble>
    //   → 过滤 classId==0 (bubble，无文字)
    //   → 按 confThreshold 过滤
    //   → NMS 去重 (IoU 阈值 0.5)
    //   → 返回 DetectedBubble 列表

    private fun copyAssetToCache(context: Context, assetPath: String): String
    private fun nms(boxes, scores, iouThreshold): List<Int>
}
```

## DetectionBridge.detectWithRTDetrV2() 设计

```kotlin
suspend fun detectWithRTDetrV2(
    bitmap: Bitmap,
    language: String,
    ocrEngine: OcrEngine
): List<TextBlockInfo>
```

流程:
1. `ComicBubbleDetector.detectBubbles(bitmap)` → DetectedBubble 列表
2. 过滤: 只保留 classId == 1 (text_bubble) 或 classId == 2 (text_free)
3. 按 confidence 降序排序
4. 裁剪每个检测框的 bitmap 区域
5. 按 ocrEngine 调用对应 OCR:
   - MLKit → `OCRBridge.recognizeText()`
   - MangaOcr → `MangaOcrRecognizer.recognizeBatch()`
   - CTCOcr → `CtcOcrRecognizer.recognizeBatch()`
6. 返回 `List<TextBlockInfo>` (text + boundingBox)

## MangaFloatingService 集成

### 初始化
```kotlin
// onCreate 中，当 detEngine == RT_DETR_V2 时
if (config.detEngine == DetEngine.RT_DETR_V2) {
    ComicBubbleDetector.initialize(this)
}
```

### 释放
```kotlin
// onDestroy 中
if (config.detEngine == DetEngine.RT_DETR_V2) {
    ComicBubbleDetector.release()
}
```

### 检测路由
```kotlin
// processMangaScreenshot Step 1 中
DetEngine.RT_DETR_V2 -> {
    val ocrEngine = when (config.ocrEngine) {
        OcrEngine.MLKit -> DetectionBridge.RTDETOCREngine.MLKit
        OcrEngine.MangaOcr -> DetectionBridge.RTDETOCREngine.MangaOcr
        OcrEngine.CTCOcr -> DetectionBridge.RTDETOCREngine.CTCOcr
    }
    DetectionBridge.detectWithRTDetrV2(bitmap, config.sourceLang, ocrEngine)
}
```

### 菜单切换
```kotlin
// toggleDetModel 循环中
val newEngine = when (config.detEngine) {
    DetEngine.MLKIT -> DetEngine.CTD
    DetEngine.CTD -> DetEngine.HYBRID
    DetEngine.HYBRID -> DetEngine.RT_DETR_V2
    DetEngine.RT_DETR_V2 -> DetEngine.MLKIT
}
```

## 关键约束

1. **不影响其他引擎**: MLKIT、CTD、HYBRID 的代码路径完全不变，只在 `when` 分支中增加一个新 case
2. **与 OCR 引擎解耦**: RT-DETR-V2 只做检测，OCR 由用户配置的 ocrEngine 决定
3. **模型打包**: 11MB INT8 模型打包进 APK assets，首次使用复制到缓存
4. **与 BubbleDetector 区分**: ComicBubbleDetector 是深度学习模型，BubbleDetector 是简单距离聚类。两者独立
