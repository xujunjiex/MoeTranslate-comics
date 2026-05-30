# PP-OCRv5 集成设计

## 目标

将 PP-OCRv5 作为"内置"OCR 引擎集成到漫画翻译模块，作为 ML Kit 的平替。支持中/日/英/韩四语言，det+cls+rec 完整三阶段管线。

## 范围

- 内置 PP-OCRv5 det + cls + 4 个 rec 模型到 `assets/ppocrv5/`
- 新增 `PPOcrV5Engine.kt`：统一推理引擎，逐行对照 RapidOCR 官方 Python 实现
- 新增 DetEngine 枚举值 + OcrEngine 枚举值
- 支持两种使用模式：独立管线 / 配合已有检测器
- 模型管理页面显示"内置"状态
- debug 可视化（参照 CTD/RT-DETR-V2 的 debug 模式）

## 原则

逐行对照 RapidOCR 官方 Python 实现，所有功能全部实现，可通过参数控制开关。不删减任何官方功能。

## 参考源码

`.reference/RapidOCR-main/python/rapidocr/`：
- `main.py` — RapidOCR 入口，det→crop→cls→rec 流程
- `ch_ppocr_det/main.py` + `utils.py` — DetPreProcess + DBPostProcess
- `ch_ppocr_cls/main.py` + `utils.py` — resize_norm_img + ClsPostProcess
- `ch_ppocr_rec/main.py` + `utils.py` — resize_norm_img + CTCLabelDecode
- `utils/process_img.py` — get_rotate_crop_image, resize_image_within_bounds, map_boxes_to_original

---

## 新增文件

### 1. `PPOcrV5Engine.kt`

单例对象，封装 det/cls/rec 三个 ONNX Session。

```kotlin
object PPOcrV5Engine {
    // 三个 ONNX Session
    private var detSession: OrtSession? = null
    private var clsSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var currentRecLang: String? = null
    private var keys: List<String> = emptyList()

    // 公开接口
    suspend fun initialize(context: Context)           // 加载 det + cls 模型
    suspend fun setRecLanguage(context: Context, lang: String)  // 切换 rec 语言模型
    suspend fun ocr(bitmap: Bitmap): List<OcrResult>   // 完整 det→cls→rec
    suspend fun rec(bitmaps: List<Bitmap>): List<String> // 仅 cls→rec（配合已有检测器）
    fun release()

    // 内部方法 — 全部对照官方实现
    // main.py
    private fun preprocessImg(oriImg: Bitmap): Pair<Bitmap, OpRecord>  // resize_image_within_bounds
    private fun applyVerticalPadding(img: Bitmap, opRecord: OpRecord): Pair<Bitmap, OpRecord>  // apply_vertical_padding
    private fun detectAndCrop(img: Bitmap, opRecord: OpRecord): Pair<List<Bitmap>, List<DetBox>>
    private fun clsAndRotate(imgList: List<Bitmap>): List<Bitmap>
    private fun recognizeTxt(imgList: List<Bitmap>): List<Pair<String, Float>>
    private fun mapBoxesToOriginal(boxes: List<DetBox>, opRecord: OpRecord, oriH: Int, oriW: Int): List<DetBox>  // map_boxes_to_original
    private fun filterByTextScore(ocrRes: List<OcrResult>, textScore: Float): List<OcrResult>

    // ch_ppocr_det
    private fun detPreprocess(img: Bitmap, limitSideLen: Int, limitType: String, mean: FloatArray, std: FloatArray): DetPreResult  // DetPreProcess
    private fun detPostprocess(pred: FloatArray, oriShape: Pair<Int, Int>): Pair<List<DetBox>, List<Float>>  // DBPostProcess
    private fun boxesFromBitmap(pred: FloatArray, bitmap: ByteArray, destW: Int, destH: Int): Pair<List<DetBox>, List<Float>>
    private fun getMiniBoxes(contour: List<PointF>): Pair<Array<PointF>, Float>
    private fun boxScoreFast(bitmap: FloatArray, box: Array<PointF>, h: Int, w: Int): Float
    private fun unclip(box: Array<PointF>): Array<PointF>
    private fun sortedBoxes(boxes: List<DetBox>): List<DetBox>

    // ch_ppocr_cls
    private fun clsResizeNormImg(img: Bitmap, imgShape: Triple<Int,Int,Int>): FloatArray  // [C,H,W]
    private fun clsPostprocess(preds: FloatArray, labelList: List<String>): List<Pair<String, Float>>  // ClsPostProcess

    // ch_ppocr_rec
    private fun recResizeNormImg(img: Bitmap, maxWhRatio: Float): FloatArray
    private fun ctcDecode(preds: Array<FloatArray>, removeDuplicate: Boolean): List<Pair<String, Float>>  // CTCLabelDecode

    // utils/process_img.py
    private fun getRotateCropImage(img: Bitmap, points: Array<PointF>): Bitmap  // 透视裁剪 + 竖排旋转
    private fun resizeImageWithinBounds(img: Bitmap, minSideLen: Int, maxSideLen: Int): Triple<Bitmap, Float, Float>

    // VisRes (debug)
    fun drawDetBoxes(bitmap: Bitmap, boxes: List<DetBox>, scores: List<Float>): Bitmap  // debug 可视化

    // cal_rec_boxes (逐字坐标，可选功能)
    private fun calcWordBoxes(croppedImgs: List<Bitmap>, detBoxes: List<DetBox>, recResults: List<Pair<String, Float>>): List<WordInfo>
}

data class OcrResult(val box: Array<PointF>, val text: String, val confidence: Float)
```

### 2. 资源文件

```
assets/ppocrv5/
├── det_v5.onnx          # 检测 4.6MB
├── cls.onnx             # 方向分类 995KB
├── rec_zh.onnx          # 中文识别 16MB
├── rec_zh_dict.txt      # 中文字典
├── rec_ja.onnx          # 日文识别 9.4MB
├── rec_ja_dict.txt      # 日文字典
├── rec_en.onnx          # 英文识别 7.6MB
├── rec_en_dict.txt      # 英文字典
├── rec_ko.onnx          # 韩文识别 13MB
└── rec_ko_dict.txt      # 韩文字典
```

总大小约 52MB。内置 assets，首次使用复制到 cache。

---

## 修改文件

### 3. `MangaModeConfig.kt`

```kotlin
enum class OcrEngine(val value: Int) {
    MLKit(0),
    MangaOcr(1),
    PPOcrV5(4);  // 新增

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: MLKit
    }
}

enum class DetEngine(val value: Int) {
    MLKIT(0),
    CTD(1),
    RT_DETR_V2(3),
    PP_OCR_V5(4);  // 新增

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: MLKIT
    }
}
```

### 4. `DetectionBridge.kt`

```kotlin
enum class CTDOCREngine {
    MLKit,
    MangaOcr,
    PPOcrV5  // 新增
}
```

在 `detectWithCTD` 和 `detectWithRTDetrV2` 中新增 `PPOcrV5` 分支：
- 调用 `PPOcrV5Engine.rec(croppedBitmaps)` 做 cls + rec

### 5. `MangaFloatingService.kt`

新增逻辑：
- **初始化**：`OcrEngine.PPOcrV5` → `PPOcrV5Engine.initialize()` + `setRecLanguage(sourceLang)`
- **释放**：`OcrEngine.PPOcrV5` → `PPOcrV5Engine.release()`
- **DetEngine.PP_OCR_V5 独立管线**：调用 `PPOcrV5Engine.ocr(bitmap)` → `List<OcrResult>` → `List<TextBlockInfo>`
- **toggle**：在 OcrEngine 循环中加入 PPOcrV5
- **标签**：PP-OCRv5 标签显示

### 6. `ModelManagementFragment.kt`

新增 PP-OCRv5 状态显示：
- 标签："PP-OCRv5 通用检测+识别（内置）"
- 状态："已内置"（模型在 assets 中，无需下载）
- 无操作按钮（不可删除，不可下载）

---

## 调用流程

### 模式 1：DetEngine = PP_OCR_V5（独立管线）

```
MangaFloatingService.processMangaScreenshot()
  → PPOcrV5Engine.ocr(bitmap)
    → det: resize → normalize → ONNX → DBPostProcess → boxes
    → for each box:
        crop = getRotateCropImage(bitmap, box)
        cls: resize_norm_img → ONNX → argmax → rotate if 180°
        rec: resize_norm_img → ONNX → CTCLabelDecode → text
    → List<OcrResult>
  → OcrResult → TextBlockInfo
```

### 模式 2：DetEngine = CTD/RT_DETR_V2, OcrEngine = PPOcrV5（配合已有检测器）

```
MangaFloatingService.processMangaScreenshot()
  → DetectionBridge.detectWithCTD(bitmap, lang, CTDOCREngine.PPOcrV5)
    → CTD 检测 → BoxMerger 合并 → 裁剪
    → PPOcrV5Engine.rec(croppedBitmaps)
      → for each bitmap:
          cls: resize_norm_img → ONNX → rotate if 180°
          rec: resize_norm_img → ONNX → CTCLabelDecode → text
      → List<String>
    → TextBlockInfo
```

---

## 关键预处理参数（对照官方）

### Det 预处理（DetPreProcess）
- limit_side_len: 960（短边最小值）
- limit_type: "min"
- mean: [0.5, 0.5, 0.5]
- std: [0.5, 0.5, 0.5]
- resize: 短边 < limit_side_len → 放大；对齐 32 倍数
- normalize: (pixel/255 - mean) / std
- permute: HWC → CHW

### Det 后处理（DBPostProcess）
- thresh: 0.3
- box_thresh: 0.5
- max_candidates: 1000
- unclip_ratio: 1.6
- use_dilation: true
- score_mode: "fast"
- min_size: 3

### Cls 预处理（PP-OCRv5 shape=[3,80,160]）
- resize: 保持宽高比，h→80px，w 不超过 160px
- normalize: /255, (x-0.5)/0.5
- pad: 零填充到 [3,80,160]
- cls_thresh: 0.9

### Rec 预处理
- rec_image_shape: [3, 48, ?]
- 计算 batch 内 max_wh_ratio
- resize: h→48px, w=48*max_wh_ratio
- normalize: /255, (x-0.5)/0.5
- pad: 零填充

### CTC 解码
- blank=0
- remove_duplicate: 相邻相同去重
- 字典: dict 文件逐行读取，insert blank 在 idx=0，insert space 在 len(dict)

---

## Debug 可视化（VisRes）

参照 CTD debug 模式，新增 `PPOcrV5DebugResult`：
- det_boxes: 检测框坐标列表
- cls_results: 每个框的 cls 角度和置信度
- rec_results: 每个框的识别文字和置信度

在 MangaFloatingService 中可通过 debug 模式触发，绘制检测框到图片上显示。

---

## 实施顺序

1. 下载字典文件（日/英/韩，从 PaddleOCR 仓库）
2. 准备 assets/ppocrv5/ 模型文件
3. 编写 PPOcrV5Engine.kt
4. 修改 MangaModeConfig.kt（枚举）
5. 修改 DetectionBridge.kt（CTDOCREngine + 分支）
6. 修改 MangaFloatingService.kt（初始化/释放/调用/标签/toggle）
7. 修改 ModelManagementFragment.kt（内置状态）
8. 编译验证
