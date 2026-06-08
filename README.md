# StarFlow (星译)

<p align="center">
  <img src="images/appicon.png" width="128" height="128" alt="StarFlow Logo">
</p>

<p align="center">
  <b>Android 翻译应用 — 游戏/视频翻译 & 漫画翻译</b><br>
  支持 Android 11+（API 29+）
</p>

---

## 功能

### 🎮 游戏/视频翻译

截图 OCR 识别 + 翻译 API，支持两种模式：

- **本地 OCR + 文本翻译：** ML Kit / PP-OCRv5 本地 OCR 识别（中文、日文、英文、韩文），然后调用翻译 API 获取结果。
- **图片翻译：** 将截图直接上传给 API 翻译，适用于其他语言。

**像素驱动自动翻译：**
- YIQ 感知像素对比，跳过稳定页面的重复 OCR
- LRU 缓存（20条），相同文字直接返回翻译结果
- 翻页稳定性检测可选：开启适合游戏翻页，关闭适合视频字幕
- 像素变化阈值、检测间隔均可自定义

**调试浮窗：** 实时显示翻译状态、像素差异、耗时，可折叠日志面板。

支持的翻译 API：ML Kit、NLLB、必应翻译、小牛翻译、OpenAI 兼容接口、火山引擎、Azure、DeepL、百度翻译、腾讯云、自定义 API。

### 📖 漫画翻译

漫画/游戏气泡文字检测 + OCR + 翻译 + 竖排渲染：

- **检测引擎：** PP-OCRv5 det、CTD（Comic Text Detector）、RT-DETR-V2（气泡/文字检测）、ML Kit
- **OCR 引擎：** PP-OCRv5 rec（中/日/英/韩）、manga-ocr（竖排日文）、ML Kit
- **翻译：** 复用游戏翻译的所有 API
- **渲染：** 半透明背景 + 竖排/横排文字覆盖
- **自动翻译：** pHash 相似度检测，翻页自动触发

### 📚 翻译历史

- 游戏列表 + 漫画网格，支持查看大图和删除
- 翻译结果自动缓存，支持 pHash 相似度匹配（漫画）
- Room 数据库本地持久化

## 构建

```bash
./gradlew assembleDebug
```

环境要求：JDK 17、Android SDK（compileSdk 35）、NDK 25.2.9519653、CMake 3.22.1。

## 项目结构

- `translate/` — 游戏/视频翻译引擎（AutoTranslateEngine、FloatingBallService、GameOcrEngine）
- `manga/` — 漫画翻译引擎（气泡检测 + OCR + 翻译 + 竖排渲染）
- `bridge/` — 桥接层（OCRBridge、TranslateBridge、ScreenshotBridge）
- `me/` — 设置和 API 配置
- `launch/` — 启动引导
- `utils/` — 工具类（PixelCompare、LogCollector、UpdateChecker）
- `data/` — Room 数据库、TranslationCacheManager
- `ui/history/` — 历史记录 UI
- `translationapi/` — 翻译 API 实现

## 致谢

本项目 forked 自 [MoeTranslate](https://github.com/murangogo/MoeTranslate)，原项目提供了翻译 API 调用、应用框架和整体架构。

### 参考项目

- [RapidOCR](https://github.com/RapidAI/RapidOCR) — 基于 PaddleOCR 的跨平台 OCR 工具包
- [Comic Text and Bubble Detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) — 漫画文字/气泡检测模型
- [RT-DETR](https://github.com/lyuwenyu/RT-DETR) — 实时目标检测 Transformer
- [manga-ocr](https://github.com/kha-white/manga-ocr) — 日漫竖排文字 OCR 模型
- [manga-image-translator](https://github.com/zyddnys/manga-image-translator) — 漫画图片翻译项目
- [pixelmatch](https://github.com/mapbox/pixelmatch) — 像素比较算法（YIQ 感知色彩差异）
- [ONNX Runtime](https://onnxruntime.ai/) — 模型推理引擎
- [JTS](https://locationtech.github.io/jts/) — 多边形几何运算

## 许可证

LGPL
