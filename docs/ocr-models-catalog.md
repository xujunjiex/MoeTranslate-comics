# OCR 识别模型清单

> 整理自 PaddleOCR 官方、RapidOCR 生态、社区第三方。更新于 2026-05-30。

---

## 一、PaddleOCR 官方 rec 模型

所有模型来自 [PaddlePaddle/PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) 官方发布。Paddle 格式需转 ONNX 才能在 Android 用。

### 1.1 中文+英文（支持中日韩字符集）

| 模型 | 版本 | 大小(Paddle) | 精度 | 字典 | 下载 |
|------|------|-------------|------|------|------|
| PP-OCRv5_server_rec | v5 | 81MB | 86.38% | 18371字 | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_server_rec_infer.tar) |
| **PP-OCRv5_mobile_rec** | v5 | **16MB** | **81.29%** | 18371字 | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_mobile_rec_infer.tar) |
| PP-OCRv4_server_rec | v4 | 88MB | 80.31% | 6623字 | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv4_server_rec_infer.tar) |
| **PP-OCRv4_mobile_rec** | v4 | **10.5MB** | **73.61%** | 6623字 | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv4_mobile_rec_infer.tar) |
| PP-OCRv4_server_rec_doc | v4 | 75MB | 86.58% | 15000+字 | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv4_server_rec_doc_infer.tar) |
| PP-OCRv3_mobile_rec | v3 | 12.4MB | 71.50% | 6623字 | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv3_mobile_rec_infer.tar) |
| PP-OCRv3_mobile_rec_slim | v3 | 4.9MB | - | 6623字 | [推理模型](https://paddleocr.bj.bcebos.com/PP-OCRv3/chinese/ch_PP-OCRv3_rec_slim_infer.tar) |

- 输入：`[N, 3, 48, dynamic_width]`，BGR，`(pixel-127.5)/127.5`
- 输出：`[N, seq_len, dict_size]`，CTC 贪心解码
- **v4/v5 中文模型可识别日文汉字、假名**（字典包含 CJK 字符），但假名精度不如专用日文模型

### 1.2 英文专用

| 模型 | 版本 | 大小 | 下载 |
|------|------|------|------|
| en_PP-OCRv4_mobile_rec | v4 | 9.7MB | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/en_PP-OCRv4_mobile_rec_infer.tar) |
| en_PP-OCRv3_mobile_rec | v3 | 9.6MB | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/en_PP-OCRv3_mobile_rec_infer.tar) |
| en_PP-OCRv3_mobile_rec_slim | v3 | 3.2MB | [推理模型](https://paddleocr.bj.bcebos.com/PP-OCRv3/english/en_PP-OCRv3_rec_slim_infer.tar) |

### 1.3 多语言专用（全部只有 v3，无官方 v4/v5）

| 模型 | 语言 | 大小 | 精度 | 字典 | 下载 |
|------|------|------|------|------|------|
| **japan_PP-OCRv3_mobile_rec** | 🇯🇵 日文 | **11MB** | 45.69% | japan_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/japan_PP-OCRv3_mobile_rec_infer.tar) |
| korean_PP-OCRv3_mobile_rec | 🇰🇷 韩文 | 11MB | 60.21% | korean_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/korean_PP-OCRv3_mobile_rec_infer.tar) |
| chinese_cht_PP-OCRv3_mobile_rec | 🇹🇼 繁体中文 | 12MB | 82.06% | chinese_cht_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/chinese_cht_PP-OCRv3_mobile_rec_infer.tar) |
| latin_PP-OCRv3_mobile_rec | 拉丁文 | 9.7MB | - | latin_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/latin_PP-OCRv3_mobile_rec_infer.tar) |
| arabic_PP-OCRv3_mobile_rec | 阿拉伯文 | 9.6MB | - | arabic_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/arabic_PP-OCRv3_mobile_rec_infer.tar) |
| cyrillic_PP-OCRv3_mobile_rec | 斯拉夫文 | 9.6MB | - | cyrillic_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/cyrillic_PP-OCRv3_mobile_rec_infer.tar) |
| devanagari_PP-OCRv3_mobile_rec | 梵文 | 9.9MB | - | devanagari_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/devanagari_PP-OCRv3_mobile_rec_infer.tar) |
| te_PP-OCRv3_mobile_rec | 泰卢固文 | 9.6MB | 95.88% | te_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/te_PP-OCRv3_mobile_rec_infer.tar) |
| ka_PP-OCRv3_mobile_rec | 卡纳达文 | 9.9MB | 96.96% | ka_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/ka_PP-OCRv3_mobile_rec_infer.tar) |
| ta_PP-OCRv3_mobile_rec | 泰米尔文 | 9.6MB | - | ta_dict.txt | [推理模型](https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/ta_PP-OCRv3_mobile_rec_infer.tar) |

- **PaddleOCR 官方没有日文 v4/v5 模型**，多语言全部停留在 v3
- 上述下载链接为 Paddle 格式（.pdparams），需 paddle2onnx 转换

---

## 二、RapidOCR ONNX 模型

[RapidOCR](https://github.com/RapidAI/RapidOCR) 把 PaddleOCR 模型转成 ONNX，可直接在 Android/iOS/Python 使用。

### 2.1 RapidOCR 可用模型

| 模型 | 来源 | 格式 | HuggingFace |
|------|------|------|-------------|
| PP-OCRv4_mobile_rec (中英文) | PaddleOCR 官方 | ONNX | [onnx-community/PaddleOCR](https://huggingface.co/onnx-community/PaddleOCR) |
| PP-OCRv3_mobile_rec (中英文) | PaddleOCR 官方 | ONNX | [onnx-community/PaddleOCR](https://huggingface.co/onnx-community/PaddleOCR) |
| japan_PP-OCRv3_mobile_rec | PaddleOCR 官方 | ONNX | 需从 Paddle 转换 |
| 其他多语言 | PaddleOCR 官方 v3 | ONNX | 需从 Paddle 转换 |

- RapidOCR Python: `pip install rapidocr-onnxruntime`
- RapidOCR Android: [RapidOcrAndroidOnnxCompose](https://github.com/RapidAI/RapidOcrAndroidOnnxCompose)（已删除参考）
- **RapidOCR 本身不训练模型**，只是 PaddleOCR 模型的 ONNX 重打包

### 2.2 社区第三方 ONNX

| 模型 | 来源 | 大小 | HuggingFace |
|------|------|------|-------------|
| japan_PP-OCRv4_rec_infer | 社区 cycloneboy | 9.3MB | [cycloneboy/japan_PP-OCRv4_rec_infer](https://huggingface.co/cycloneboy/japan_PP-OCRv4_rec_infer) |
| ppocrv4_ch_rec | 社区 | 10.8MB | - |

---

## 三、manga-ocr 模型（自回归 Transformer，非 CTC）

| 模型 | 大小 | 格式 | 来源 | 备注 |
|------|------|------|------|------|
| manga-ocr FULL | ~94MB (encoder+decoder) | ONNX | [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) | 原版，精度最高 |
| manga-ocr V2025 | ~135MB (encoder+decoder) | ONNX | tools/manga_ocr_2025_onnx/ | 新版，速度快 |
| manga-ocr Mobile | ~30MB (encoder+decoder) | ONNX+TFLite | tools/manga_ocr_mobile/ | 轻量版 |

- 专精竖排日文漫画，自回归生成（非 CTC），精度高于 PP-OCRv3 日文
- 缺点：慢（2-5s/区域），大（460MB FULL 或 135MB V2025）

---

## 四、本地 tools/ 已有模型

| 文件 | 大小 | 用途 | 状态 |
|------|------|------|------|
| `ppocrv4_ja_rec.onnx` + `ppocrv4_ja_dict.txt` | 9.3MB + 22KB | 日文 rec (社区 v4) | ⚠️ 社区第三方，推理有问题 |
| `ppocrv4_ch_rec.onnx` + `ppocrv4_ch_dict.txt` | 10.8MB + 26KB | 中文 rec (社区 v4) | 未测试 |
| `ppocrv5_onnx/rec_zh.onnx` + `rec_zh_dict.txt` | 84MB + 74KB | 中文 rec (官方 v5) | 已转 ONNX |
| `ppocrv5_onnx/det_v5.onnx` | 88MB | 中文 det (官方 v5) | 已转 ONNX |
| `ppocrv5_mobile/` | 17MB | 中文 rec+det (官方 v5 Paddle) | Paddle 格式，未转 ONNX |
| `manga_ocr_onnx/` | ~95MB | manga-ocr FULL ONNX | 已集成 |
| `manga_ocr_2025_onnx/` | ~135MB | manga-ocr V2025 ONNX | 可用 |
| `manga_ocr_mobile/` | ~37MB | manga-ocr 轻量版 | encoder.onnx + decoder.tflite |

---

## 五、推荐方案

### 日文漫画识别（当前需求）

| 优先级 | 模型 | 大小 | 速度 | 精度 | 理由 |
|--------|------|------|------|------|------|
| ⭐⭐⭐ | PP-OCRv4_mobile_rec (中日英) | 10.5MB | ~100ms | 高 | 官方 v4，字典含 CJK，可识别日文汉字+假名+中文 |
| ⭐⭐⭐ | japan_PP-OCRv3_mobile_rec | 11MB | ~100ms | 45.69% | 官方日文专用，最稳定 |
| ⭐⭐ | manga-ocr (现有) | 95-460MB | 2-5s | 最高 | 竖排日文最强，但太慢 |
| ⭐ | japan_PP-OCRv4 (现有) | 9.3MB | ~100ms | 未知 | 社区第三方，不稳定 |

### 关键发现

1. **PaddleOCR 官方没有日文 PP-OCRv4 模型** — 多语言全部只有 v3
2. **PP-OCRv4/v5 中文模型可识别日文** — 因为字典包含 CJK 统一字符集（6623-18371字），覆盖日文汉字和假名
3. **如果要稳定可靠的日文 OCR**，建议用 `japan_PP-OCRv3_mobile_rec`（官方）或 `PP-OCRv4_mobile_rec`（官方，中日英通用）
4. **社区 japan_PP-OCRv4** 可能是用日文数据在 v4 架构上微调的，但没有 PaddleOCR 官方背书

---

## 六、ONNX 模型转换方法

Paddle 格式 → ONNX：
```bash
pip install paddle2onnx
paddle2onnx --model_dir ./japan_PP-OCRv3_mobile_rec_infer \
            --model_filename inference.pdmodel \
            --params_filename inference.pdiparams \
            --save_file japan_rec.onnx \
            --opset_version 11
```

字典位置：`PaddleOCR/ppocr/utils/dict/japan_dict.txt`
