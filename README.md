# StarFlow (星译)

Android 翻译应用，支持 Android 11+（API 29+）。包含两个核心功能：**游戏翻译**和**漫画翻译**。

## 功能

### 一、游戏翻译

截图 OCR 识别 + 翻译 API，支持两种模式：

- **本地 OCR + 文本翻译：** ML Kit / PP-OCRv5 本地 OCR 识别（中文、日文、英文、韩文），然后调用翻译 API 获取结果。
- **图片翻译：** 将截图直接上传给 API 翻译，适用于其他语言。

支持的翻译 API：ML Kit、NLLB、必应翻译、小牛翻译、OpenAI 兼容接口、火山引擎、Azure、DeepL、百度翻译、腾讯云、自定义 API。

### 二、漫画翻译

漫画/游戏气泡文字检测 + OCR + 翻译 + 竖排渲染：

- **检测引擎：** PP-OCRv5 det、CTD（Comic Text Detector）、RT-DETR-V2（气泡/文字检测）、ML Kit
- **OCR 引擎：** PP-OCRv5 rec（中/日/英/韩）、manga-ocr（竖排日文）、ML Kit
- **翻译：** 复用游戏翻译的所有 API
- **渲染：** 半透明背景 + 竖排/横排文字覆盖

## 构建

```bash
./gradlew assembleDebug
```

环境要求：JDK 17、Android SDK（compileSdk 35）、NDK 25.2.9519653、CMake 3.22.1。

## 项目结构

- `translate/` — 游戏翻译引擎
- `manga/` — 漫画翻译引擎
- `bridge/` — 桥接层
- `me/` — 设置和 API 配置
- `launch/` — 启动引导
- `utils/` — 工具类
- `translationapi/` — 翻译 API 实现
- `.reference/` — 参考项目（只读）

## 开源项目

- [RTranslator](https://github.com/niedev/RTranslator) — NLLB 翻译模型
- [manga-image-translator](https://github.com/zyddnys/manga-image-translator) — CTD 检测器参考
- [RapidOCR](https://github.com/RapidAI/RapidOCR) — PP-OCRv5 模型
- [ONNX Runtime](https://onnxruntime.ai/) — 模型推理引擎
- [JTS](https://locationtech.github.io/jts/) — 多边形几何运算
