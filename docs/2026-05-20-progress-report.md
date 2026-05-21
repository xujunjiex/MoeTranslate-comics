# 2026-05-20 进度报告

## 已有的 Markdown 文件目录

| 文件 | 说明 |
|------|------|
| `C:\Users\xjj20\.claude\plans\ml-kit-effervescent-swan.md` | 当前计划：DBNet 检测模型集成 |
| `C:\Users\xjj20\.claude\plans\*.md`（共 14 个） | 历史计划文件 |
| `docs\superpowers\plans\2026-05-18-manga-translation-fixes.md` | 漫画翻译 bug 修复计划 |
| `docs\superpowers\plans\2026-05-20-in-app-log-viewer.md` | 应用内日志查看器计划 |
| `C:\Users\xjj20\.claude\projects\...\memory\feedback_log_viewer_for_errors.md` | 记忆：错误应在 app 内可见 |

---

## 文件变更明细

### 一、修改的文件（15 个）

| # | 文件 | 变更内容 | 编译 | 备注 |
|---|------|----------|------|------|
| 1 | `manga/BubbleMerger.kt` | 放宽合并参数（CHAR_GAP_TOLERANCE 0.6→1.0, CHAR_GAP_TOLERANCE2 1.5→3.0, FONT_SIZE_RATIO_TOL 1.5→2.0, ASPECT_RATIO_TOL 1.9→1.3），对齐参考项目 manga-image-translator | OK | 修复"一个气泡识别为两个" |
| 2 | `manga/OverlayRenderer.kt` | 重构为两轮绘制（先所有背景→再所有文字），防止重叠气泡背景覆盖文字；calculateExpandedRect 增加 minOf/maxOf 约束防止文字超出气泡 | OK | 修复背景叠加+文字溢出 |
| 3 | `manga/MangaModeConfig.kt` | 新增 `useMangaOcr: Boolean` 和 `useDBNet: Boolean` 字段 | OK | |
| 4 | `manga/MangaFloatingService.kt` | 全面重构：Log.*→LogCollector.*；新增 manga-ocr/DBNet 初始化/释放；翻译流程支持 DBNet 检测；菜单从 7 项扩展为 8 项（新增检测模型 index 3）；菜单切换同步写入偏好键 | OK | 最大改动文件，+259/-87 行 |
| 5 | `translate/Dialogs.kt` | mangaMenuDialog 新增 `detModelLabel`、`ocrEngineLabel` 参数；菜单图标数组新增两个 ocr_engine 图标 | OK | |
| 6 | `me/AboutMe.kt` | 新增"查看日志"按钮处理：showLogViewerDialog() + exportLogsToFile() | OK | |
| 7 | `me/PersonalizationConfig.kt` | 新增 mangaDetModel、mangaRecModel 两个 ListPreference 及其变更监听 | OK | |
| 8 | `res/xml/personalization.xml` | 新增"漫画翻译模型设置"分类，含文字检测模型和文字识别模型两个 ListPreference | OK | |
| 9 | `res/layout/fragment_about_me.xml` | 新增"查看日志"按钮区域（log_icon + 文字 + 右箭头） | OK | |
| 10 | `res/values/strings.xml` | 新增：manga_det_*, manga_rec_*, manga_model_*, view_logs, log_* 等字符串 | OK | |
| 11 | `res/values-zh/strings.xml` | 同上（中文） | OK | |
| 12 | `res/values-en/strings.xml` | 同上（英文） | OK | |
| 13 | `res/values/arrays.xml` | manga_menu_items 新增 Detection Model / OCR Engine 项；新增 manga_det_model_entries/values、manga_rec_model_entries/values 数组 | OK | |
| 14 | `res/values-zh/arrays.xml` | 同上（中文） | OK | |
| 15 | `res/values-en/arrays.xml` | 同上（英文） | OK | |

### 二、新建的文件（10 个）

| # | 文件 | 功能 | 编译 |
|---|------|------|------|
| 1 | `manga/DBNetDetector.kt` | DBNet ONNX Runtime 推理引擎（预处理→推理→后处理） | OK |
| 2 | `manga/DBNetModelManager.kt` | DBNet 模型文件管理（检查 assets 中 .onnx 文件） | OK |
| 3 | `manga/DBNetPostProcessor.kt` | 纯 Kotlin 后处理（Union-Find 连通域 → bounding box 提取） | OK |
| 4 | `manga/DetectionBridge.kt` | 统一检测接口（DBNet 检测 + ML Kit/manga-ocr 识别） | OK |
| 5 | `manga/MangaOcrBridge.kt` | manga-ocr 混合 OCR 桥接（ML Kit 检测 + manga-ocr 识别） | OK |
| 6 | `manga/MangaOcrRecognizer.kt` | manga-ocr ONNX 推理引擎（ViT Encoder + BERT Decoder） | OK |
| 7 | `manga/MangaOcrModelManager.kt` | manga-ocr 模型文件管理 | OK |
| 8 | `manga/MangaOcrTokenizer.kt` | manga-ocr tokenizer | OK |
| 9 | `utils/LogCollector.kt` | 日志收集器（环形缓冲区，500 条，包装 android.util.Log） | OK |
| 10 | `.reference/export_dbnet_onnx.py` | PyTorch → ONNX 导出脚本 | N/A |

### 三、新增的资源文件（4 个）

| 文件 | 功能 |
|------|------|
| `res/drawable/log_icon.xml` | 日志图标（#55AEEA 色调） |
| `res/drawable/log_content_bg.xml` | 日志内容背景（圆角卡片） |
| `res/drawable/ocr_engine.xml` | OCR 引擎图标 |
| `res/layout/dialog_log_viewer.xml` | 日志查看器对话框布局 |

### 四、新增的 Assets 文件

| 文件 | 大小 | 功能 |
|------|------|------|
| `assets/dbnet/dbnet_detector.onnx` | 60KB | DBNet 检测模型（图结构） |
| `assets/dbnet/dbnet_detector.onnx.data` | 292MB | DBNet 检测模型（权重） |
| `assets/manga_ocr/` | ~430MB | manga-ocr 模型（encoder + decoder + vocab） |

---

## (1) 已完成的工作

### Bug 修复
- **气泡合并过严**：放宽 BubbleMerger 参数，对齐参考项目 manga-image-translator
- **背景叠加**：OverlayRenderer 重构为两轮绘制（先背景后文字）
- **文字溢出**：calculateExpandedRect 增加矩形约束
- **菜单标签双重拼接**：arrays.xml 菜单项改为纯标签，动态拼接在 Dialogs.kt 完成

### 新功能
- **应用内日志查看器**：LogCollector 环形缓冲 + 查看/复制/导出，UI 配色匹配 app 主题
- **manga-ocr 识别引擎**：ONNX 推理（ViT+BERT），混合模式（ML Kit 检测 + manga-ocr 识别）
- **DBNet 检测模型**：ONNX 推理 + 纯 Kotlin 后处理（连通域分析），替代 ML Kit 检测
- **检测模型菜单切换**：悬浮窗菜单 8 项（新增检测模型 index 3）
- **设置页面模型选项**：个人化设置新增"漫画翻译模型设置"（检测模型 + 识别模型），带描述文字
- **设置同步**：悬浮窗菜单和设置页面共享同一偏好键（Manga_Det_Model / Manga_Rec_Model）

---

## (2) 已知 Bug / 问题

| # | 严重度 | 描述 |
|---|--------|------|
| 1 | 中 | DBNet 模型 292MB + manga-ocr ~430MB，APK 约 751MB，首次安装体积过大 |
| 2 | 低 | DBNet 后处理未使用 unclip 扩展（参考项目的 PyClipper），仅用 bounding box，可能不够精确 |
| 3 | 低 | 未使用 OpenCV 的 minAreaRect/四边形拟合，纯 Kotlin 连通域方案对倾斜文字可能不够准确 |
| 4 | 低 | DBNet 检测 + manga-ocr 识别是串行的（逐区域裁剪→识别），大量文字区域时速度较慢 |
| 5 | 信息 | 悬浮窗菜单从 7→8 项，原有索引全部 +1，如果其他代码引用了旧索引会出错 |
| 6 | 信息 | `detect-20241225.ckpt`（294MB）和导出中间文件仍在 `.reference/` 目录，不应提交 git |

---

## (3) 建议的下一步

### 优先级高
1. **实际测试**：在真机上测试 DBNet 检测效果 vs ML Kit，验证连通域后处理是否足够
2. **性能优化**：DBNet 检测 + manga-ocr 识别的串行流程改为并行（多区域同时识别）
3. **APK 瘦身**：考虑将模型文件改为首次启动时下载（而非打包进 APK）

### 优先级中
4. **DBNet 后处理增强**：实现 unclip 扩展（面积/周长比膨胀），提高检测框精度
5. **四边形拟合**：用 Android Canvas/Path API 实现 minAreaRect 替代纯 bounding box
6. **模型量化**：将 ONNX 模型量化为 INT8 减小体积（292MB → ~73MB）

### 优先级低
7. **LaMa inpainting**：原图文字擦除（需 GPU，移动端不实际，可暂不考虑）
8. **CRAFT 检测模型**：作为 DBNet 的备选方案
9. **菜单图标**：检测模型和 OCR 引擎目前共用 ocr_engine 图标，可设计独立图标
