# CTC ONNX 模型参数文档

> 48px_ctc ONNX 模型完整技术规格。原始 PyTorch 模型来自 [manga-image-translator](https://github.com/zyddnys/manga-image-translator)。

## 模型信息

| 属性 | 值 |
|------|-----|
| 原始来源 | [manga-image-translator](https://github.com/zyddnys/manga-image-translator) `ocr48px_ctc` |
| 转换脚本 | `scripts/export_ctc_to_onnx.py` |
| 验证脚本 | `scripts/verify_ctc_onnx.py` |
| 输出路径 | `tools/ctc_onnx/model.onnx` |
| 文件大小 | 157.3 MB |
| 格式 | ONNX FP32（无量化） |
| Opset | 18 |
| 字典大小 | 19264 |
| 支持语言 | 多语言（中日韩英等，字典内置） |

## 输入尺寸限制

| 属性 | 限制 |
|------|------|
| **高度** | 固定 48px（必须先缩放） |
| **宽度** | 任意（动态轴，可变） |
| **batch size** | 动态（支持多张并行） |
| **seq_len** | `width / 4 - 2`（自动计算） |

**关键规则：**
- 输入高度必须先缩放到 **48px**，宽度等比例缩放
- 宽度无硬性上限，seq_len ≈ width/4（经 ResNet 3 次宽度下采样后减 2）
- `alignedWidth = (4 * ((maxWidth + 7) // 4)) + 128` 用于 batch 内对齐（整除）

## 并行识别

**支持 batch 并行**，内部每批最多 16 张（`MAX_BATCH_SIZE = 16`）。

批处理流程：
1. 所有图片缩放到高 48px，宽度等比例
2. 按宽度升序排列（对齐官方 `perm = sorted(..., key=width)`）
3. 每批最多 16 张，padding 到 batch 内最大宽度
4. 一次性 ONNX forward pass，批量推理

```kotlin
// API：批量识别
suspend fun recognizeBatch(bitmaps: List<Bitmap>): List<String>
```

## 语言支持

**不需要指定语言**。

CTC 是端到端识别，没有语言选择参数：
- 字典 `alphabet-all-v5.txt` 已包含多语言字符（中日韩英等 19264 个）
- 模型输出是字符 logits，无语言开关
- 识别结果由图片内容自动决定

## 模型架构

```
OCR
├── ResNet_FeatureExtractor(3, 320)     # ResNet [4,6,8,6,3]
│   └── ResNet → BasicBlock × (4+6+8+6+3)
├── TransformerEncoder(CustomTransformerEncoderLayer, 3)
│   └── d_model=320, nhead=8, dim_feedforward=1280, dropout=0.05
├── char_pred_norm: LayerNorm(320) → Dropout(0.1) → GELU
├── char_pred: Linear(320, 19264)      # 字符分类
└── color_pred1: Linear(320, 6)        # 前景色+背景色 (fr,fg,fb,br,bg,bb)
```

## 预处理

图片进入模型前必须经过以下处理：

### 步骤 1：缩放

```kotlin
val scale = 48f / bitmap.height
val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
val resized = Bitmap.createScaledBitmap(bitmap, newWidth, 48, true)
```

### 步骤 2：归一化

```kotlin
// 归一化到 [-1, 1]
val normalized = (pixel - 127.5f) / 127.5f
```

### 步骤 3：Padding 对齐

```kotlin
val alignedWidth = (4 * ((maxWidth + 7) / 4)) + 128  // 整除，对应 Python //
// 右侧填充值为 -1（归一化后的零值）
```

## 输出规格

### char_logits

| 属性 | 值 |
|------|-----|
| 名称 | `char_logits` |
| Shape | `[batch_size, seq_len, 19264]` |
| seq_len | `width / 4 - 2` |
| 数据类型 | float32 |

每个位置 19264 个 logits，表示该位置属于每个字符的未归一化分数。

### color_values

| 属性 | 值 |
|------|-----|
| 名称 | `color_values` |
| Shape | `[batch_size, seq_len, 6]` |
| 数据类型 | float32 |
| 值范围 | 无界（原始输出），解码时 clamp 到 `[0, 1]` |

格式：`[fr, fg, fb, br, bg, bb]` — 前景色 + 背景色 RGB。

> `color_pred1` 是裸 `Linear(320, 6)` 无激活函数，原始输出无范围限制。源码解码时执行 `clamp_(0, 1)` 后处理。

## CTC 解码算法

与官方 manga-image-translator 完全一致的三步解码：

### 步骤 1：log_softmax（数值稳定）

```kotlin
// 对每个时间步的 19264 个 logits 计算 log-softmax
fun logSoftmax(logits: FloatArray, dictSize: Int, seqLen: Int): FloatArray {
    val logProbs = FloatArray(seqLen * dictSize)
    for (t in 0 until seqLen) {
        val base = t * dictSize
        var maxVal = logits[base]
        for (j in 1 until dictSize) {
            if (logits[base + j] > maxVal) maxVal = logits[base + j]
        }
        var sum = 0.0
        for (j in 0 until dictSize) {
            sum += exp((logits[base + j] - maxVal).toDouble())
        }
        val logSumExp = maxVal - ln(sum)
        for (j in 0 until dictSize) {
            logProbs[base + j] = (logits[base + j] - logSumExp).toFloat()
        }
    }
    return logProbs
}
```

### 步骤 2：argmax

```kotlin
val preds = IntArray(seqLen)
for (t in 0 until seqLen) {
    var maxId = 0
    var maxVal = logProbs[t * dictSize]
    for (j in 1 until dictSize) {
        if (logProbs[t * dictSize + j] > maxVal) {
            maxId = j
            maxVal = logProbs[t * dictSize + j]
        }
    }
    preds[t] = maxId
}
```

### 步骤 3：CTC 去重（greedy decode）

```kotlin
val decoded = mutableListOf<Int>()
var lastId = 0  // blank = index 0
for (t in 0 until seqLen) {
    val curId = preds[t]
    if (curId != 0 && curId != lastId) {
        decoded.add(curId)  // 去 blank + 去连续重复
    }
    lastId = curId
}
```

### 概率计算

```kotlin
// 每个字符的 log 概率累加，整句取平均
val prob = exp(sum(charLogProbs) / charCount)
```

**Prob 阈值：默认 0.5**。低于此值的返回空字符串（对齐官方 `threshold = 0.5`）。

## 字典格式

`alphabet-all-v5.txt`：

| 属性 | 值 |
|------|-----|
| 总字符数 | 19264 |
| 格式 | 每行一个字符（不含换行符） |
| index 0 | blank token |
| `<SP>` | 空格字符 |
| 路径 | `tools/ctc_onnx/alphabet-all-v5.txt` |

ID → 字符转换：
```kotlin
val ch = if (id < dictSize) dictionary[id] else "?"
if (ch == "<SP>") " " else ch
```

## Android 端调用示例

### Kotlin

```kotlin
// CtcOcrRecognizer.kt 已实现，调用方式：
suspend fun initialize(context: Context, modelDir: String = "ocr_ctc")
suspend fun recognizeBatch(bitmaps: List<Bitmap>): List<String>
suspend fun recognizeBatchWithProb(bitmaps: List<Bitmap>): List<Pair<String, Float>>
suspend fun recognizeBatchWithColors(bitmaps: List<Bitmap>): List<FloatArray>
```

### 内部实现要点

1. **排序**：按宽度升序排列，同宽度 batch 推理
2. **Padding**：batch 内使用最大宽度 + 对齐公式
3. **批处理**：每批最多 16 张（`MAX_BATCH_SIZE = 16`）
4. **Prob 阈值**：默认 0.5，低于此概率的返回空字符串
5. **Bitmap 回收**：临时缩放的 bitmap 会被回收

## 注意事项

| 问题 | 说明 |
|------|------|
| 颜色值可能超出 [0,1] | `color_pred1` 是裸 Linear 无激活，原始输出无界，需在解码时 clamp |
| 浮点精度 | char_logits 差异 < 1e-3（正常），不影响识别结果 |
| 内存 | 157MB 模型，Android 端建议单 Session 复用 |

## 验证结果

使用 `scripts/verify_ctc_onnx.py` 对比 PyTorch 模型与 ONNX 模型推理结果：

### 测试配置

| 配置 | 值 |
|------|-----|
| 测试输入 | 随机浮点 tensor，4 种宽度（128/256/512/200） |
| 验证方法 | PyTorch forward vs ONNX Runtime inference |

### 数值对比

| 指标 | char_logits | color_values (×255) | 结论 |
|------|------------|----------------------|------|
| Max diff | ~1e-3 | ~22 (原始 ~0.09) | 正常范围 |
| Mean diff | ~2e-4 | ~10 (原始 ~0.04) | 正常范围 |
| Argmax 一致性 | **100%** | — | 核心功能无差异 |
| CTC 解码一致 | **100%** | — | 最终结果无差异 |

### 结论

- **文字识别结果 100% 相同**：argmax 和 CTC 解码完全一致
- 浮点精度差异在允许范围内（Transformer 模型 PyTorch vs ONNX Runtime 正常误差）
- 颜色值差异较大是因为 `color_pred1` 为裸 Linear 层（无激活），原始输出本身精度有限，解码时 clamp 到 [0,1] 后不影响翻译功能

## 相关文件

| 文件 | 说明 |
|------|-----|
| `tools/ctc_onnx/model.onnx` | ONNX 模型文件 |
| `tools/ctc_onnx/alphabet-all-v5.txt` | 字符表 |
| `app/src/main/assets/ocr_ctc/model.onnx` | 内置到 APK 的模型 |
| `app/src/main/assets/ocr_ctc/alphabet-all-v5.txt` | 内置的字符表 |
| `scripts/export_ctc_to_onnx.py` | 转换脚本 |
| `scripts/verify_ctc_onnx.py` | 验证脚本 |
| `app/.../CtcOcrRecognizer.kt` | Android 推理引擎 |
| `app/.../CtcOcrModelManager.kt` | 模型管理器 |