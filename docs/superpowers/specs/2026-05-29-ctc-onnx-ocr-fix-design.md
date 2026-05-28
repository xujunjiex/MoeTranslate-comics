# CTC ONNX 识别器修复设计

## 问题描述

CTC (48px_ctc) ONNX 识别器在 Android 端无法正确识别漫画文字：
- 14 个文字区域中 10 个返回空结果（prob=0）
- 4 个返回错误字符（如 `'ま'`、`'cust'`、`'-'`）
- **prob > 1.0**（`exp(mean(log_probs))` 数学上不可能超过 1.0，说明 log-softmax 输入异常）

对比测试：
- MLKit+MLKit：同一张图正确识别 12 个文字块
- CTD+MangaOcr：同一张图正确识别 14 个文字块
- CTD+CTCOcr：4/14 有结果，全部错误

结论：CTD 检测管道正确，问题 100% 在 `CtcOcrRecognizer` 内部。

## 分析

### 参考项目 CTC 流程（Python）

```
get_transformed_region(image, direction, 48)  # 透视变换裁剪
  → region [N, 48, W, 3] uint8 BGR
  → (region.float() - 127.5) / 127.5         # [-1, 1]
  → einops.rearrange('N H W C -> N C H W')   # NCHW
  → model.decode(images, widths, 0)           # CTC 解码
```

### 当前 Kotlin 实现

```
cropBitmap + prepareCtcInputs                  # 轴对齐裁剪 + 缩放/旋转
  → Bitmap.getPixels → R/G/B 分平面          # RGB（参考是 BGR！）
  → (pixel - 127.5) / 127.5                   # [-1, 1]
  → NCHW 写入 FloatBuffer
  → OnnxTensor.create → session.run
  → decodeCtcWithProb(logits, seqLen, dictSize)
```

### 已发现的差异

| # | 差异 | 参考 | 当前 | 影响 |
|---|------|------|------|------|
| 1 | 颜色通道 | BGR | RGB | 高 - 模型在 BGR 上训练 |
| 2 | prob > 1.0 | log_softmax(raw_logits) → prob ≤ 1.0 | 正 log prob | 核心 bug |
| 3 | 几何变换 | findHomography 透视变换 | Matrix 轴对齐缩放 | 中 - 旋转文字 |

### prob > 1.0 根因分析

`charLogProb = logits[maxId] - logNorm` 应该 ≤ 0（log_softmax 输出的 log 概率不可能为正）。

正 log 概率的可能原因：
1. ONNX 模型输出不是原始 logits（如已 softmax 过的概率）
2. FloatBuffer 数据被错误解读（byte order、tensor shape）
3. log-softmax 数值计算 bug

## 修复方案

### Step 1：添加诊断日志

在 `CtcOcrTokenizer.decodeCtcWithProb()` 中添加：
- 打印 logits 数组的 min/max/mean
- 打印前 5 个时间步的 top-1 logit 值
- 打印 logNorm 和 charLogProb 的中间值

在 `CtcOcrRecognizer.recognizeBatchInternal()` 中添加：
- 打印 ONNX 模型输入/输出名称和形状（通过 session 输入/输出信息）
- 打印 logitsTensor 的 info（形状、元素数量）
- 验证 `floatBuffer.array().size` 是否等于预期的 `batchN * seqLen * dictSize`

### Step 2：修复 RGB→BGR

修改 `CtcOcrRecognizer` 中的像素提取，交换 R 和 B 通道：

```kotlin
// 当前（RGB）
rPlane[i] = ((pixel shr 16 and 0xFF) - 127.5f) / 127.5f  // R
bPlane[i] = ((pixel and 0xFF) - 127.5f) / 127.5f          // B

// 修复后（BGR，对齐参考项目 OpenCV 读图）
bPlane[i] = ((pixel shr 16 and 0xFF) - 127.5f) / 127.5f  // 位置0 = B
rPlane[i] = ((pixel and 0xFF) - 127.5f) / 127.5f          // 位置2 = R
```

影响：`recognizeBatchInternal` 和 `recognizeBatchInternalWithColors` 两个方法。

### Step 3：根据诊断结果修复解码

根据 Step 1 的日志结果：
- 如果 ONNX 输出的是 softmax 概率 → 跳过 log_softmax，直接 `log(probabilities)`
- 如果 FloatBuffer 数据异常 → 改用 `floatBuffer.get(index)` 逐个读取或使用 `OnnxTensor.getFloatArray()`
- 如果 byte order 问题 → 设置 `ByteOrder.LITTLE_ENDIAN`

## 涉及文件

| 文件 | 修改内容 |
|------|----------|
| `CtcOcrRecognizer.kt` | RGB→BGR、诊断日志、可能的解码修复 |
| `CtcOcrTokenizer.kt` | 诊断日志、可能的 log-softmax 修复 |

## 验证方法

1. 编译安装
2. 用 CTD+CTCOcr 组合截同一张漫画图
3. 检查 logcat：
   - 诊断日志显示 logits min/max/mean 正常
   - prob ≤ 1.0
   - 识别结果与 MLKit/MangaOcr 基本一致
