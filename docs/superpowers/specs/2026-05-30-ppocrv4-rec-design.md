# PP-OCRv4 JA Rec 适配设计

## 背景

项目当前 CTCOcr (15MB, ~200ms) 识别效果差，MangaOcr (460MB, 2-5s) 太慢。需要一个更小更快的 OCR 识别器。

PP-OCRv4 JA rec 模型（9.3MB, ~5ms PC CPU）来自 RapidOCR 项目，日文识别优秀，中文/英文可接受。适配到 Android 作为新 OCR 引擎。

## 设计决策

- **新建类** `PPOcrV4RecRecognizer`，独立于现有 CtcOcrRecognizer
- **新增枚举值** `PPOcrV4(3)`，不替换/修改现有引擎
- **内置在 assets**，开箱即用

## 文件结构

```
app/src/main/assets/ppocrv4_ja/
├── rec.onnx          (9.3MB)  — PP-OCRv4 日文识别模型
└── japan_dict.txt    (22KB)   — 日文字符字典（4399 字）
```

## 新增/修改文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `PPOcrV4RecRecognizer.kt` | **新增** | PP-OCRv4 rec 推理引擎，单例对象 |
| `MangaModeConfig.kt` | 修改 | OcrEngine 新增 `PPOcrV4(3)` |
| `MangaFloatingService.kt` | 修改 | 初始化和调用处添加 PPOcrV4 分支 |
| `DetectionBridge.kt` | 修改 | CTD/HYBRID 模式添加 PPOcrV4 分支 |
| `assets/ppocrv4_ja/` | 新增目录 | 模型和字典文件 |

## PPOcrV4RecRecognizer 设计

参考 `CtcOcrRecognizer` 的单例模式，核心区别在预处理。

### 核心接口

```kotlin
object PPOcrV4RecRecognizer {
    suspend fun initialize(context: Context)
    suspend fun recognizeBatch(bitmaps: List<Bitmap>): List<String>
    fun release()
    val isInitialized: Boolean
}
```

与 `CtcOcrRecognizer` 的接口一致，便于 DetectionBridge 统一调用。

### 预处理

输入：Bitmap (任意宽高, RGB)
1. Resize height → 48px，width 按比例缩放，width 必须被 4 整除
2. RGB → BGR（通道翻转）
3. 归一化：`pixel = (pixel / 255.0 - 0.5) / 0.5`（全局 mean=0.5, std=0.5）
4. 排列为 CHW，batch 为 `[N, 3, 48, alignedWidth]`

与 CtcOcr 的区别：CtcOcr 用 RGB + per-channel normalize (mean=[0.485,0.456,0.406], std=[0.229,0.224,0.225])。

### 后处理（CTC 解码）

与 CtcOcr 相同逻辑：
- blank = index 0
- 解码字符 = `dict[pid - 1]`（字典从 index 1 开始映射）
- 去重连续相同字符

### 字典加载

从 `assets/ppocrv4_ja/japan_dict.txt` 逐行读取，每行一个字符。

## 集成路径

### DetectionBridge

在 `detectWithCTD` 和 `detectWithCTDHybrid` 中添加分支：

```kotlin
OcrEngine.PPOcrV4 -> {
    val texts = PPOcrV4RecRecognizer.recognizeBatch(croppedBitmaps)
    // 映射到对应的 mergedGroup
}
```

### MangaFloatingService

初始化时根据用户选择的引擎初始化对应识别器：

```kotlin
when (config.ocrEngine) {
    OcrEngine.PPOcrV4 -> PPOcrV4RecRecognizer.initialize(this)
    OcrEngine.CTCOcr -> CtcOcrRecognizer.initialize(this)
    // ...
}
```

## 不在本次范围

- PP-OCRv4 CH rec（中英）— 后续单独适配
- det 模型 — 项目已有 CTD/MLKit 检测器，不需要
- 模型下载管理 — 内置 assets，不需要 ModelManager
- UI 改动 — 仅添加 OcrEngine 枚举值，UI 部分在 MangaFloatingService 菜单中自动显示

## 验证

1. 编译通过
2. 选择 PPOcrV4 引擎 + CTD 检测 → 能正确识别日文漫画文字
3. 选择 PPOcrV4 引擎 + CTD + 混合模式 → 能正常工作
4. 切换回 CTCOcr/MLKit → 不受影响
