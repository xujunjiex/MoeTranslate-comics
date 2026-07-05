# StarFlow (星译)

<p align="center">
  <img src="images/appicon.png" width="128" height="128" alt="StarFlow Logo">
</p>

<p align="center">
  <b>Android 翻译应用 — 游戏翻译 & 漫画翻译 & 视频翻译</b><br>
  支持 Android 11+（API 29+）| 仅 arm64-v8a
</p>

---

## 演示

<p align="center">
  <img src="images/game_demo.gif" width="30%" alt="游戏翻译演示">
  &nbsp;&nbsp;&nbsp;
  <img src="images/manga_demo.gif" width="30%" alt="漫画翻译演示">
  &nbsp;&nbsp;&nbsp;
  <img src="images/video_demo.gif" width="30%" alt="视频翻译演示">
</p>

## 功能

### 🎮 游戏翻译

悬浮窗覆盖翻译，支持 10+ 翻译 API（DeepL、百度、腾讯、Azure、火山引擎、OpenAI 兼容等），ML Kit / NLLB 本地离线翻译。

- **像素驱动自动翻译** — YIQ 感知色彩差异检测画面变化，翻页自动触发
- **AI 上下文** — 携带历史翻译对提升连贯性（1-10 轮可配）
- **提示词自定义** — 游戏/漫画提示词独立配置
- **悬浮球手势** — 单击/双击/长按自由分配动作

### 📖 漫画翻译

气泡检测 + OCR + 翻译 + 竖排渲染，4 种检测引擎 + 3 种 OCR 引擎可自由组合。

- **PP-OCRv5 / CTD / RT-DETR-V2 / ML Kit** 检测引擎
- **PP-OCRv5 / manga-ocr / ML Kit** 识别引擎
- **增量渲染** — 超 6 个气泡分批处理，首批译完立即显示
- **自动翻页** — pHash 检测页面变化，停稳 ~1s 自动翻译
- **翻译缓存** — pHash 相似度匹配，翻过的页面秒开

### 🎬 视频翻译

同游戏翻译引擎，适用于视频字幕、直播弹幕等场景。

### 📚 翻译历史

Room 数据库持久化，双视图（时间排序 / 进程分组），全屏翻页浏览原图 + 译文详情，支持管理视图重新翻译和打包下载。

### 🔧 其他

- **双模式截图** — MediaProjection / AccessibilityService 可切换
- **模型管理** — 核心模型内置 ~22MB，专用模型按需下载（断点续传）
- **调试面板** — PP-OCRv5 5 参数实时调节，各引擎独立调试浮窗
- **检查更新** — GitHub Releases 自动检测，支持直接下载 / 百度网盘 / 夸克网盘

## 下载

| 方式 | 链接 |
|------|------|
| GitHub Releases | [最新版本](https://github.com/xujunjiex/StarFlow/releases) |
| 百度网盘 | https://pan.baidu.com/s/1AXbakx6apJcIthF4CenWdA?pwd=star |

## 构建

```bash
# 首次克隆后创建 local.properties（路径用正斜杠）
echo sdk.dir=C:/Users/<username>/AppData/Local/Android/Sdk > local.properties

./gradlew assembleDebug
```

**环境：** JDK 17、Android SDK（compileSdk 35）、NDK 25.2.9519653、CMake 3.22.1

## 项目结构

```
app/src/main/java/com/moe/moetranslator/
├── translate/       游戏/视频翻译引擎
├── manga/           漫画翻译引擎
├── bridge/          桥接层（OCR / 检测 / 翻译 / 截图）
├── me/              设置 & API 配置 & FAQ
├── launch/          首次启动引导
├── utils/           工具类（pHash、像素比较、日志、检查更新等）
├── data/            Room 数据库 & 缓存管理
├── ui/history/      历史记录 UI
└── translationapi/  翻译 API 实现（10+ 厂商）
```

## 致谢

本项目 forked 自 [MoeTranslate](https://github.com/murangogo/MoeTranslate)。

### 参考项目

- [RapidOCR](https://github.com/RapidAI/RapidOCR) — 跨平台 OCR 推理
- [Comic Text and Bubble Detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) — 漫画文字/气泡检测
- [RT-DETR](https://github.com/lyuwenyu/RT-DETR) — 实时目标检测 Transformer
- [manga-ocr](https://github.com/kha-white/manga-ocr) — 日漫竖排文字 OCR
- [manga-image-translator](https://github.com/zyddnys/manga-image-translator) — 漫画图片翻译
- [pixelmatch](https://github.com/mapbox/pixelmatch) — YIQ 感知像素比较
- [ONNX Runtime](https://onnxruntime.ai/) — 模型推理引擎
- [JTS](https://locationtech.github.io/jts/) — 多边形几何运算

## 许可证

LGPL — 详见 [licenses/](licenses/) 目录。第三方库许可证见各项目主页。
