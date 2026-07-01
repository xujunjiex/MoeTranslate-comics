# StarFlow (星译)

<p align="center">
  <img src="images/appicon.png" width="128" height="128" alt="StarFlow Logo">
</p>

<p align="center">
  <b>Android 翻译应用 — 游戏/视频翻译 & 漫画翻译</b><br>
  支持 Android 11+（API 29+）| 仅 arm64-v8a
</p>

---

## 功能

### 🔄 双模式截图

两种截图方式可切换，适配不同使用场景：

- **MediaProjection（默认）：** 弹窗授权，门槛低，每次启动需重新授权。需要前台服务支持。
- **AccessibilityService：** 手动开启无障碍服务，永久有效，无需重复授权。

通过 `ScreenshotProvider` 接口抽象，`ScreenshotManager` 单例解耦截图生产者与消费者，游戏和漫画模式共用同一套截图架构。

### 🎮 游戏/视频翻译

截图 OCR 识别 + 翻译 API，悬浮窗覆盖显示翻译结果。

**两种翻译模式：**
- **文字翻译（TEXT）：** ML Kit / PP-OCRv5 本地 OCR 识别文字，调用翻译 API 获取结果
- **图片翻译（PIC）：** 截图直接上传给图片翻译 API（百度/腾讯/自定义），适用于其他语言

**翻译 API（10+）：**
- **免费：** 必应翻译、ML Kit 本地翻译、NLLB 本地翻译
- **云端：** DeepL、百度翻译、腾讯云、Azure、小牛翻译、火山引擎
- **AI：** OpenAI 兼容接口（火山/智谱/DeepSeek/通义千问/用户自建）
- **自建 API：** 填入 API 地址 + Key + 模型名，支持任何 OpenAI 兼容接口

**本地离线翻译：**
- ML Kit 和 NLLB 支持完全离线翻译，不需要网络
- 选 NLLB 时自动检测设备 RAM（< 6GB 警告）
- 适合无网络或不想泄露翻译内容的场景

**像素驱动自动翻译：**
- YIQ 感知像素对比，跳过稳定页面的重复 OCR
- 翻页稳定性检测：开启适合游戏翻页，关闭适合视频字幕
- LRU 缓存（20 条），相同文字直接返回翻译结果（⚡ 标识）
- 像素变化阈值、检测间隔均可自定义

**AI 上下文翻译：**
- 开启后自动携带历史翻译对（1-10 轮），提升翻译连贯性
- 仅 OpenAI 兼容 API 生效
- 对比开启前后的翻译质量差异明显

**提示词配置：**
- 游戏和漫画提示词分别独立配置
- 支持自定义系统提示词，控制翻译风格
- 仅对 OpenAI 兼容 API 生效

**悬浮球交互：**
- 单击/双击/长按手势可自定义分配动作（翻译、打开菜单、自动翻译开关）
- 三个手势互斥配置，选择时自动交换冲突项
- 双击有动画反馈，长按延迟可调

**翻译结果容器：**
- 可拖动定位、锁定后固定位置
- 锁定/关闭按钮，关闭后再次点击悬浮球可恢复
- 半透明穿透模式（alpha 控制），开启后仍可拖动

**运行时语言切换：**
- 游戏和漫画悬浮菜单都支持运行时切换源语言
- 循环切换 ja → en → zh → ko → ru，跳过未下载的 OCR 模型
- 切换后即时生效，无需重启翻译

**权限预检：**
- 启动前自动检查 5 项：Android 版本、无障碍服务、悬浮窗权限、通知权限、API 配置
- 缺少权限弹窗引导跳转设置页

**调试浮窗：** 关于页面可开启，实时显示翻译状态、像素差异、耗时，可折叠日志面板。

### 📖 漫画翻译

漫画/游戏气泡文字检测 + OCR + 翻译 + 竖排文字渲染覆盖。

**检测引擎（4 种）：**
- PP-OCRv5 det（默认）— 内置，无需下载
- CTD（Comic Text Detector）— ~94MB，需下载
- RT-DETR-V2（气泡/文字检测）— ~11MB，需下载
- ML Kit — 内置

**OCR 引擎（3 种）：**
- PP-OCRv5 rec（默认）— 内置，支持中/日/英混合识别，韩文/俄文可选下载
- manga-ocr（竖排日文）— ~135MB/~460MB，需下载
- ML Kit — 内置

**普通/高级模式：**
- **普通模式：** 固定搭配循环切换（MLKit → PP-OCRv5 → manga-ocr），跳过未下载的模型
- **高级模式：** 自由搭配检测器 + 识别器，菜单分两个独立选项

**TextRegionMerger 合并引擎：**
- 基于 MST（最小生成树）+ UnionFind 的区域合并算法
- 统一处理 CTD/RT-DETR-V2/PP-OCRv5/MLKit 各检测器的后处理
- 替代旧版 BoxMerger + TextLineMerger，代码更精简

**倾斜文字处理：**
- PP-OCRv5 检测框可能倾斜（QuadBox 4 顶点非正交），全链路支持
- 角度检测（atan2）+ 方向判断（真实边长） + 合并（沿倾斜角投影）
- 渲染时 `canvas.rotate(angle)` 旋转背景和文字
- 正常 overlay 和调试 overlay 均支持倾斜渲染

**增量渲染：**
- 超过 6 个气泡自动分批处理（2/5 + 3/5）
- 首批翻译完立即显示，减少用户等待时间
- 支持组合：RT-DETR-V2/MangaOcr、CTD/MangaOcr、PP-OCRv5 独立模式

**自动翻页翻译：**
- pHash 相似度检测页面变化（阈值 0.95）
- 连续翻页自动检测，停稳后 ~1s 内自动触发翻译
- 手动点击悬浮球可跳过等待，强制翻译

**翻译缓存：**
- pHash 精确匹配 + 相似度匹配（阈值 0.85），翻过的页面秒显示
- 左上角橙色 ⚡ 缓存标识 + 刷新按钮
- 区域级缓存（IoU ≥ 0.4 判重，TTL 5 分钟）
- 缓存数量可调（滑块设置），清除缓存按钮在历史页面

**渲染：** 半透明背景 + 竖排/横排文字覆盖，支持倾斜文字旋转渲染，全屏和调试模式用真实屏幕像素定位。

### 📚 翻译历史

- **双视图架构：** 默认视图（按修改时间排序）和管理视图（按进程 sessionId 分组）
- **双 sessionId 架构：** 原始 sessionId（按创建排序） + lastSessionId（按修改排序）
- 漫画翻译支持全屏翻页浏览原图 + 译文详情面板 + 尺寸变体切换
- **管理视图重新翻译：** 加载原始截图 → 裁剪 → OCR → 翻译 → 渲染 → 替换原变体，无需启动翻译服务
- **管理视图引擎选择：** 独立 OCR 引擎配置（PP-OCRv5 / manga-ocr / ML Kit），不影响悬浮窗设置
- **管理视图进程组下载：** 打包 ZIP 通过 SAF 文件选择器保存
- **原文/译文切换：** 详情页可切换查看原始图片或翻译渲染图
- 同 pHash 页面分组显示，多尺寸变体可切换
- Room 数据库本地持久化，version 9

### ⚙️ 个性化设置

- 悬浮球手势自定义（单击/双击/长按分配翻译/菜单/自动翻译）
- 源语言/目标语言切换（翻译运行中禁止切换，避免结果错乱）
- 悬浮窗可穿透性设置
- 悬浮窗长按延迟调整

### 📦 模型管理

- PP-OCRv5 核心模型内置（~22MB：det + cls + rec_zh + 字典）
- 可选下载：rec_en（~7.5MB）、rec_ko（~12.9MB）、rec_ru（~7.7MB）
- 按需下载：CTD（~94MB）、RT-DETR-V2（~11MB）、manga-ocr（~135MB/~460MB）
- 下载管理器支持断点续传、重试、进度回调
- 404/403 不重试，其他错误最多重试 3 次
- OCR 模型加载失败时弹窗报错，不再静默吞掉异常

### 🎛️ PP-OCRv5 调试面板

开发者选项中可开启 PP-OCRv5 调试面板，实时调节 5 个参数：

| 参数 | 默认值 | 范围 | 作用 |
|------|--------|------|------|
| 检测置信度 | 0.3 | 0.01–0.5 | 低于此值的检测框被丢弃 |
| 扩展比例 | 1.6 | 1.0–3.0 | unclip 扩展，越大检测框越宽松 |
| 识别置信度 | 0.5 | 0.1–0.9 | 低于此值的识别结果被丢弃 |
| 大框过滤 | 关 | 开/关 | 过滤占图片比例过大的检测框 |
| 丢弃比例 | 0.6 | 0.3–0.8 | 大框过滤阈值（宽/高/面积占图片比例） |

调试面板默认折叠，含图例说明（绿=检测框、青=合并区、红虚线=检测丢弃、橙虚线=识别丢弃）。

### 🔔 通知系统

- **应用内公告：** 开发者通过 Gist 推送公告，app 启动时自动检查
- **版本更新通知：** 从 GitHub Releases 自动检测新版本
- Android 13+ 运行时通知权限请求（POST_NOTIFICATIONS）

### 🔧 其他功能

- **首次启动引导：** 权限申请 + API 配置引导
- **主页版本号：** 主页显示当前版本号，点击可检查更新
- **FAQ 页面：** 常见问题解答（含 PP-OCRv5 调试面板参数详解）
- **开发者选项：** 各引擎调试浮窗（CTD/RT-DETR-V2/MLKit/PP-OCRv5）+ 参数调节面板
- **检查更新：** GitHub Release 自动检测，支持直接下载/百度网盘/夸克网盘，仅在启动时触发一次
- **日志收集：** 所有日志通过 LogCollector 统一管理，支持导出

### 🔒 安全

- 禁止明文 HTTP 流量（network_security_config）
- API 密钥通过 KeystoreManager 加密存储
- Room 数据库排除在系统备份之外

---

## 构建

```bash
# 首次克隆后，创建 local.properties（路径用正斜杠）
echo sdk.dir=C:/Users/<username>/AppData/Local/Android/Sdk > local.properties

./gradlew assembleDebug
```

环境要求：JDK 17、Android SDK（compileSdk 35）、NDK 25.2.9519653、CMake 3.22.1。

## 下载

- [GitHub Releases](https://github.com/xujunjiex/StarFlow/releases) — 最新版本下载
- 百度网盘：https://pan.baidu.com/s/1AXbakx6apJcIthF4CenWdA?pwd=star

## 检查更新

app 内置检查更新功能，支持：
- **直接下载**：从 GitHub Releases 直接下载 APK
- **网盘下载**：百度网盘、夸克网盘

## 项目结构

- `translate/` — 游戏/视频翻译引擎（FloatingBallService、AutoTranslateEngine、GameOcrEngine、CropView、TranslationResultView、Shooter、ScreenshotManager、ScreenshotProvider、MediaProjectionProvider、AccessibilityProvider、Dialogs）
- `manga/` — 漫画翻译引擎（MangaFloatingService、DetectionBridge、PPOcrV5Engine、ComicBubbleDetector、CTDDetector、MangaOcrBridge、TextRegionMerger、OverlayRenderer、TranslateUtils、OcrLock、GeometryUtils、OnnxUtils）
- `bridge/` — 桥接层（OCRBridge、DetectionBridge、TranslateBridge、ScreenshotBridge）
- `me/` — 设置和 API 配置（PersonalizationConfig、APIConfig、TranslationMode、AboutMe、FAQPage、Developer）
- `launch/` — 首次启动引导
- `utils/` — 工具类（PixelCompare、PerceptualHash、LogCollector、UpdateChecker、NotificationChecker、UiUtils、ServiceUtils、Constants、CustomPreference、KeystoreManager）
- `data/` — Room 数据库、TranslationCacheManager、HistoryEntity、PageCacheEntity
- `ui/history/` — 历史记录 UI（HistoryFragment、MangaViewerActivity、CropFragment、HistoryGroupAdapter、HistoryMangaGroupAdapter）
- `translationapi/` — 翻译 API 实现（openaitranslation、bingtranslation、mlkittranslation、nllbtranslation、niutrans、volctranslation、deepltranslation、baidutranslation、tencentcloud、azuretranslation、customtranslation）

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

本项目基于 LGPL 许可证发布。详见 [licenses/](licenses/) 目录。

### 第三方许可

| 项目 | 许可证 |
|------|--------|
| RapidOCR | Apache 2.0 |
| Comic Text and Bubble Detector | MIT |
| RT-DETR | Apache 2.0 |
| manga-ocr | Apache 2.0 |
| manga-image-translator | Apache 2.0 |
| pixelmatch | ISC |
| ONNX Runtime | MIT |
| JTS | EPL 2.0 / EDL 1.0 |
