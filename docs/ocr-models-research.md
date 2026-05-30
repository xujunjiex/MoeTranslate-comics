# OCR 识别器调研

> 搜索时间：2026-05-29，通过 GitHub API + HuggingFace API 搜索
> 目标：为 RT-DETR-V2 气泡检测器寻找合适的轻量 OCR 识别器

## 当前项目已有的 OCR 引擎

| 引擎 | 速度 | 大小 | 日语精度 | 说明 |
|------|------|------|----------|------|
| MLKit | ~100ms | 0（系统自带） | 一般 | Google 通用 OCR |
| CTCOcr | ~200ms/batch | ~15MB | 好 | 48px_ctc，一次前向传播，无自回归 |
| MangaOcr | 2-5s/图 | ~460MB | 最好 | ViT+BERT 自回归解码，慢 |

---

## GitHub 项目

### RapidOCR 系列（推荐）

| 项目 | Stars | 说明 | 地址 |
|------|-------|------|------|
| RapidAI/RapidOCR | 6669★ | 多语言 OCR 工具包，基于 PaddleOCR ONNX | https://github.com/RapidAI/RapidOCR |
| RapidAI/RapidOcrOnnx | 351★ | C++ ONNX 版 | https://github.com/RapidAI/RapidOcrOnnx |
| RapidAI/RapidOcrAndroidOnnx | 111★ | Android C++ JNI 版 | https://github.com/RapidAI/RapidOcrAndroidOnnx |
| RapidAI/RapidOcrAndroidOnnxCompose | 33★ | Android 纯 Kotlin 版 | https://github.com/RapidAI/RapidOcrAndroidOnnxCompose |
| RapidAI/RapidOcrOnnxJvm | 15★ | Java/Kotlin JNI 测试 | https://github.com/RapidAI/RapidOcrOnnxJvm |

**特点：**
- 模型总大小 ~15MB（检测 4MB + 识别 10MB + 方向分类 1MB）
- 速度 ~50-200ms/张
- 支持日语/中文/韩语/英文（PaddleOCR 多语言模型）
- rec 模型输入高度 48px
- 纯 ONNX Runtime 推理，不依赖 PyTorch

### manga-ocr 相关

| 项目 | Stars | 说明 | 地址 |
|------|-------|------|------|
| liksunrice/manga-ocr-torchless | 4★ | manga-ocr ONNX 版，去 PyTorch 依赖，100% 与原版一致 | https://github.com/liksunrice/manga-ocr-torchless |
| kha-white/manga-ocr | - | 原版 manga-ocr | https://github.com/kha-white/manga-ocr |

### 其他

| 项目 | Stars | 说明 | 地址 |
|------|-------|------|------|
| frederik-uni/manga-image-translator-rust | 81★ | Rust 版漫画翻译器 | https://github.com/frederik-uni/manga-image-translator-rust |
| VrajVyas11/Multilingual_PureJS_Based_OCR | 8★ | JS 版 PaddleOCR ONNX，支持日/中/韩/英 | https://github.com/VrajVyas11/Multilingual_PureJS_Based_OCR |
| ArthurKun21/kt-ocr-onnx | 0★ | Kotlin 版 PaddleOCR v5 ONNX（WIP） | https://github.com/ArthurKun21/kt-ocr-onnx |
| sieugene/yomikomi | 4★ | 浏览器端日语漫画阅读器，ONNX OCR | https://github.com/sieugene/yomikomi |
| pl146/manga-voice-reader | 1★ | Chrome 扩展，漫画气泡朗读 | https://github.com/pl146/manga-voice-reader |

---

## HuggingFace 模型

### manga-ocr 系列

| 模型 | Likes | Downloads | 格式 | 说明 | 地址 |
|------|-------|-----------|------|------|------|
| kha-white/manga-ocr-base | 171♥ | 353K | PyTorch | 原版 manga-ocr | https://huggingface.co/kha-white/manga-ocr-base |
| onnx-community/manga-ocr-base-ONNX | 0♥ | 796 | ONNX | 当前项目使用 | https://huggingface.co/onnx-community/manga-ocr-base-ONNX |
| l0wgear/manga-ocr-2025-onnx | 8♥ | 595 | ONNX | 新版训练数据，jzhang533 训练 | https://huggingface.co/l0wgear/manga-ocr-2025-onnx |
| jzhang533/manga-ocr-base-2025 | 6♥ | 454 | Safetensors | 新版 manga-ocr，改进训练数据 | https://huggingface.co/jzhang533/manga-ocr-base-2025 |
| mayocream/manga-ocr-onnx | 5♥ | 84 | ONNX | 早期 manga-ocr ONNX 导出 | https://huggingface.co/mayocream/manga-ocr-onnx |

### 移动端优化（重点）

| 模型 | Likes | Downloads | 格式 | 说明 | 地址 |
|------|-------|-----------|------|------|------|
| bluolightning/manga-ocr-mobile | 6♥ | 85 | TFLite | **10M 参数，专为移动端设计**，RepViT backbone，7.4% CER | https://huggingface.co/bluolightning/manga-ocr-mobile |
| bluolightning/manga-ocr-tflite | 5♥ | 60 | TFLite | manga-ocr TFLite 版 | https://huggingface.co/bluolightning/manga-ocr-tflite |

**manga-ocr-mobile 详情：**
- 10M 参数（原版 ~400MB，这个小得多）
- 基于 RepViT（mobile ViT）
- 7.4% CER，73% 精确匹配
- 训练数据：60% 动漫 + 20% 网文 + 20% CC-100 + Manga109s
- 仅 TFLite 格式，需转换为 ONNX 才能在项目中使用
- GitHub: https://github.com/bluolightning/manga-ocr-mobile

### 其他 OCR 模型

| 模型 | Likes | Downloads | 格式 | 说明 | 地址 |
|------|-------|-----------|------|------|------|
| onnx-community/GLM-OCR-ONNX | 3♥ | 231 | ONNX | GLM-OCR ONNX 版 | https://huggingface.co/onnx-community/GLM-OCR-ONNX |
| psyka-101/GLM-OCR-Manga-LoRA | 5♥ | 19 | PEFT | GLM-OCR 的漫画 LoRA 微调 | https://huggingface.co/psyka-101/GLM-OCR-Manga-LoRA |

---

## 对比总结

| 方案 | 大小 | 速度 | 日语精度 | 集成难度 | 推荐度 |
|------|------|------|----------|----------|--------|
| MLKit | 0 | ~100ms | 一般 | 已有 | ⭐⭐ |
| CTCOcr | ~15MB | ~200ms/batch | 好 | 已有 | ⭐⭐⭐⭐ |
| RapidOCR | ~15MB | ~50-200ms | 好 | 中等 | ⭐⭐⭐⭐ |
| manga-ocr-mobile | ~10MB? | 快？ | 好？ | 需转 ONNX | ⭐⭐⭐ |
| MangaOcr | ~460MB | 2-5s | 最好 | 已有 | ⭐⭐⭐ |

## 建议

1. **短期**：RT-DETR-V2 + CTCOcr 组合已可用，先测试效果
2. **中期**：关注 kt-ocr-onnx（Kotlin PaddleOCR v5 ONNX），等它成熟后集成
3. **长期**：如果需要更高精度，考虑集成 RapidOCR 或等待 manga-ocr-mobile 转 ONNX
