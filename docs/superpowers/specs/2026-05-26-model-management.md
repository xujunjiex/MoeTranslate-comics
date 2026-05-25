# 模型管理功能设计

## 目标

1. 在设置菜单添加独立的"模型管理"入口
2. 模型分为检测器和识别器两类，各有说明文本
3. 用户可删除/保留已下载模型
4. 切换引擎时不会自动下载，缺失时提示用户去设置菜单下载
5. 下载时显示下载速度

## 架构

### 设置菜单结构

```
AboutMe (设置主页面)
└── 模型管理 (ModelManagementFragment) [新建]
    ├── 检测器区域
    │   ├── CTD (comictextdetector.pt.onnx) - 内嵌assets，暂不支持管理
    │   └── DBNet (dbnet_detector.onnx) - 内嵌assets，暂不支持管理
    └── 识别器区域
        ├── MLKit - 系统自带，无需管理
        ├── manga-ocr - 内嵌assets，暂不支持管理
        └── CTCOcr (48px_ctc) - 可下载/删除，显示下载速度和进度
```

### 文件结构

```
app/src/main/java/com/moe/moetranslator/
├── me/
│   ├── ModelManagementFragment.kt  # [新建] 模型管理页面
│   ├── AboutMe.kt                   # [修改] 添加"模型管理"按钮
│   └── SettingPageActivity.kt       # [修改] 添加 TYPE_FRAGMENT_MODEL_MANAGEMENT
├── manga/
│   ├── CtcOcrModelManager.kt        # [修改] 添加删除模型功能
│   ├── CtcOcrRecognizer.kt           # [修改] 修复 useAssets=false 从 filesDir 加载
│   └── ModelDownloadManager.kt       # [修改] 显示下载速度
```

## UI 设计

### 模型管理页面

- 标题: "模型管理"
- 两个区块: "检测器" 和 "识别器"
- 每个模型显示: 名称、说明、大小、状态（已下载/未下载）
- CTCOcr 显示下载进度和速度（MB/s）
- 已下载的模型显示"删除"按钮

### 模型状态

| 模型 | 状态 | 可操作 |
|------|------|--------|
| CTD | 内嵌 | 无 |
| DBNet | 内嵌 | 无 |
| MLKit | 系统自带 | 无 |
| manga-ocr | 内嵌 | 无 |
| CTCOcr | 可下载 | 下载/删除 |

## 用户流程

### 场景1: 用户切换到 CTCOcr 但未下载模型
1. 用户在悬浮窗菜单切换到 CTCOcr
2. 系统检测到模型未下载
3. Toast 提示: "48px_ctc 模型未下载，请到设置 > 模型管理 中下载"
4. 不阻塞用户切换（下次翻译时才检测）

### 场景2: 用户在模型管理页面下载 CTCOcr
1. 用户进入设置 > 模型管理
2. 点击 CTCOcr 的"下载"按钮
3. 显示下载进度对话框，包含:
   - 当前下载百分比
   - 已下载/总大小 (MB)
   - 当前下载速度 (MB/s)
   - 取消按钮
4. 下载完成后，对话框关闭，状态更新为"已下载"
5. 用户可点击"删除"按钮移除模型

## 关键实现

### 1. 修复 CtcOcrRecognizer 加载逻辑

当模型从网络下载到 filesDir 后，初始化时应使用 `useAssets=false` 从 filesDir 加载。

```kotlin
// 检测模型位置
val modelFile = CtcOcrModelManager.getModelFile(context)
val useAssets = !modelFile.exists()
initialize(context, "ocr_ctc", useAssets)
```

### 2. ModelDownloadManager 显示下载速度

在 ProgressCallback 中计算并传递速度信息：

```kotlin
typealias ProgressCallback = (bytesRead: Long, totalBytes: Long, speed: Float) -> Unit
// speed: MB/s
```

### 3. 缺失模型提示

在 `MangaFloatingService.processMangaScreenshot` 中检测：

```kotlin
if (config.ocrEngine == OcrEngine.CTCOcr && !CtcOcrModelManager.isModelDownloaded(context)) {
    showToast("48px_ctc 模型未下载，请到设置 > 模型管理 中下载")
    return
}
```

## 字符串资源

需要添加的字符串:
- `model_management` - "模型管理"
- `model_management_tip` - "管理已下载的识别模型"
- `detector_section` - "检测器"
- `detector_ctd_desc` - "CTD 检测器 - 内嵌于应用中"
- `detector_dbnet_desc` - "DBNet 检测器 - 内嵌于应用中"
- `recognizer_section` - "识别器"
- `recognizer_ctc_desc` - "48px CTC 识别器 - 多语言快速 OCR，需下载"
- `model_download` - "下载"
- `model_delete` - "删除"
- `model_downloaded` - "已下载"
- `model_not_downloaded` - "未下载"
- `model_download_speed` - "下载速度: %.1f MB/s"
- `model_delete_confirm` - "确定删除 %s 模型吗？"
- `model_delete_success` - "模型已删除"
- `model_missing_hint` - "模型未下载，请到设置 > 模型管理 中下载"

## 验证清单

- [ ] AboutMe 添加"模型管理"按钮
- [ ] SettingPageActivity 支持 TYPE_FRAGMENT_MODEL_MANAGEMENT
- [ ] ModelManagementFragment 显示检测器和识别器列表
- [ ] CTCOcr 可下载，显示进度和速度
- [ ] CTCOcr 可删除
- [ ] CtcOcrRecognizer 正确从 filesDir 加载已下载模型
- [ ] 切换到未下载的 OCR 引擎时提示用户
- [ ] 内嵌模型（CTD/DBNet/manga-ocr）不可管理