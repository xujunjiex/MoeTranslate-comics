# manga-ocr 版本管理设计

## 背景

当前模型管理页面的 manga-ocr 下载功能存在以下问题：
1. 下载后页面没有显示模型是否已下载，永远显示下载按钮
2. 不能对已下载的模型进行删除管理
3. 没有选择"当前使用哪个版本"的逻辑
4. 悬浮窗 manga-ocr（下载版）需要读取模型管理页面选择的版本

## 额外问题（需修复）

### 问题 1：下载失败（网络连接问题）
- **错误：** `java.net.ConnectException: Failed to connect to huggingface.co/4.78.139.50:443`
- **原因：** HuggingFace 下载地址被墙或不稳定
- **解决方案：** 参考 CTC 模型使用 ghproxy 镜像代理

### 问题 2：测试用 assets 模型 OCR 报错
- **错误：** `Got invalid dimensions for input: input_ids for the following indices index: 1 Got: 2 Expected: 1`
- **原因：** Decoder 输入维度错误，batch size 不匹配
- **说明：** 这个问题仅在 MangaOcrAssets（测试用 assets 模型）时出现，下载的 ONNX 模型可能没有这个问题

## 设计方案

### 模型管理页面改造

**UI 结构：**
```
▼ manga-ocr 识别器
   完整版 (460MB) [已下载✓] [删除]
   半精度 FP16 (231MB) [下载] [未下载]
   量化版 (117MB) [下载] [未下载]
```

每行显示：版本名 + 大小 + 状态 + 操作按钮

**操作按钮逻辑：**
- 已下载：显示"删除"按钮 + "当前使用✓"标记
- 未下载：显示"下载"按钮
- 下载中：显示"取消"按钮

**交互：**
- 点击版本行设置为"当前使用"版本
- 当前使用版本旁边显示"✓"标记

### 存储

新增配置项 `Manga_OCR_Active_Version`（FULL/FP16/QUANTIZED）

### 悬浮窗行为

- 读取 `Manga_OCR_Active_Version`
- 加载对应版本目录的模型
- 如果选中版本未下载，提示用户去下载

## 待实现

- [ ] 模型管理页面 UI 改造（三个版本列表）
- [ ] 当前使用版本选择逻辑
- [ ] 存储 Manga_OCR_Active_Version 配置
- [ ] 悬浮窗读取配置并加载对应版本
- [ ] 修复：manga-ocr 下载使用 ghproxy 镜像（如 HuggingFace 被墙）
- [ ] 修复：MangaOcrAssets 测试模型 OCR 报错（input_ids batch dimension 问题）