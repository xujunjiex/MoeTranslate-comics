# Manga-Image-Translator 完整管线文档

> 官方源码完整流程分析。基于 `manga_translator.py` 主入口及所有子模块源码。

---

## 管线总览

```
输入图片
    │
    ▼
[0] 模型加载 ─────────────────────────────────────► 预加载所有模型
    │
    ▼
[1] Colorization (可选) ──────────────────────► 黑白漫上色
    │
    ▼
[2] Upscaling (可选) ────────────────────────► 超分辨率放大
    │
    ▼
[3] Detection ──────────────────────────────► 文字检测（找到文字区域边界）
    │
    ▼
[4] OCR ────────────────────────────────────► 文字识别（识别区域内的文字）
    │
    ▼
[5] Textline Merge ──────────────────────────► 合并相邻文字行成气泡
    │
    ▼
[6] Pre-Dictionary ─────────────────────────► 译前词典替换
    │
    ▼
[7] Translation ─────────────────────────────► 云端/本地翻译
    │
    ▼
[8] Post-Dictionary ────────────────────────► 译后词典替换
    │
    ▼
[9] Post-Translation Check ─────────────────► 译后验证 + 重试
    │
    ▼
[10] Mask Refinement ────────────────────────► 蒙版精细化
    │
    ▼
[11] Inpainting ────────────────────────────► 擦除原文字
    │
    ▼
[12] Rendering ────────────────────────────► 绘制翻译文字
    │
    ▼
[13] Downscale (可选) ─────────────────────► 还原到原始尺寸
    │
    ▼
输出图片
```

---

## [0] 模型加载

```python
# manga_translator.py:403-412
if (models_ttl == 0):
    await prepare_upscaling(config.upscale.upscaler)       # 放大模型
    await prepare_detection(config.detector.detector)      # 检测模型
    await prepare_ocr(config.ocr.ocr, device)              # OCR模型
    await prepare_inpainting(config.inpainter.inpainter)   # 擦除模型
    await prepare_translation(config.translator.translator_gen)  # 翻译API
    if config.colorizer.colorizer != Colorizer.none:
        await prepare_colorization(config.colorizer.colorizer)
```

所有模型采用懒加载 + 缓存机制，同一模型只加载一次。

---

## [1] Colorization（可选）

**模块**: `manga_translator/colorization/`

| 配置键 | 模型 | 说明 |
|--------|------|------|
| `none` | — | 不上色 |
| `mc2` | MC2 模型 | 黑白漫着色 |

**输入**: RGB 图片
**输出**: 彩色化图片

---

## [2] Upscaling（可选）

**模块**: `manga_translator/upscaling/`

| 配置键 | 模型 | 说明 |
|--------|------|------|
| `waifu2x` | Waifu2x | 动漫专用放大 |
| `esrgan` | ESRGAN | 通用超分辨率 |
| `4xultrasharp` | 4xUltrasharp | 高清放大 |

**作用**: 放大图片以提高检测精度（detection_size 也对应增大）

---

## [3] Detection（文字检测）

**模块**: `manga_translator/detection/`

**入口**: `dispatch_detection()` → 调用对应检测器

### 支持的检测器

| 配置键 | 类名 | 方法 | 骨干网络 | 输出 |
|--------|------|------|----------|------|
| `default` | `DefaultDetector` | DB (Differentiable Binarization) | ResNet-34 | 旋转四边形 |
| `dbconvnext` | `DBConvNextDetector` | DB | ConvNeXt | 旋转四边形 |
| `ctd` | `ComicTextDetector` | YOLO-based CTD | 自定义 | 文字行（已后处理） |
| `craft` | `CRAFTDetector` | CRAFT | VGG16 | 旋转四边形 |
| `paddle` | `PaddleDetector` | PaddleOCR | — | 旋转四边形 |
| `none` | `NoneDetector` | 无检测 | — | 整图作为一个大区域 |

### 检测流程

```python
# manga_translator.py:685-693
ctx.textlines, ctx.mask_raw, ctx.mask = await dispatch_detection(
    detector_key,
    ctx.img_rgb,
    detection_size=2048,
    text_threshold=0.5,
    box_threshold=0.7,
    unclip_ratio=2.3,
    invert=False,
    gamma_correct=False,
    rotate=False,
    auto_rotate=False,
    device='cpu'
)
```

### 输出数据结构

```python
# 每个 textline 是一个 Quadrilateral 对象
class Quadrilateral:
    pts: np.ndarray          # 4个角点坐标 [4, 2]
    text: str                 # 识别的文字（OCR后填充）
    prob: float              # 置信度
    font_size: float         # 估算字体大小
    angle: float             # 文字角度
    fg_r/g/b, bg_r/g/b: int  # 前景/背景色（OCR后填充）
    lines: List              # 拆分后的文字行
```

---

## [4] OCR（文字识别）

**模块**: `manga_translator/ocr/`

**入口**: `dispatch_ocr()` → 调用对应 OCR 模型

### 支持的 OCR 模型

| 配置键 | 类名 | 高度 | 架构 | 解码方式 | 字典 | 阈值 |
|--------|------|------|------|----------|------|------|
| `32px` | `Model32pxOCR` | 32px | ResNet + Transformer | Beam Search | alphabet-all-v5.txt | 0.7 |
| `48px` | `Model48pxOCR` | 48px | ConvNeXt + XPOS + Transformer | Beam Batch | alphabet-all-v7.txt | 0.2 |
| `48px_ctc` | `Model48pxCTCOCR` | 48px | ResNet + Transformer | CTC | alphabet-all-v5.txt | 0.5 |
| `mocr` | `ModelMangaOCR` | — | Manga-OCR 库 + OCR_AR_48px | — | — | — |

### OCR 流程

```python
# manga_translator.py:752
textlines = await dispatch_ocr(
    ocr_key,
    image=ctx.img_rgb,
    regions=ctx.textlines,      # 检测阶段的 Quadrilateral 列表
    config=OcrConfig(),
    device='cpu',
    verbose=False
)
```

### 各模型详解

#### 4.1 Model32pxOCR (ocr32px)

```
输入: [batch, 3, 32, width]  RGB，归一化到 [-1, 1]
骨干: ResNet_FeatureExtractor(3, 320) — BasicBlock [3,6,7,5]
编码: TransformerEncoderLayer(320, nhead=4) × 3层
解码: TransformerDecoderLayer(320, nhead=4) × 2层
解码: Beam Search (beams_k=5)，自回归生成
特殊符: <S> (起始), </S> (终止), <SP> (空格)
颜色: 6个独立 Linear 层 (fg_r/g/b, bg_r/g/b)，值域 [0,1]
字典: alphabet-all-v5.txt（v3/v5版本）
阈值: 0.7 (default)
模型文件: ocr.ckpt
```

#### 4.2 Model48pxOCR (ocr48px) ⭐推荐

```
输入: [batch, 3, 48, width]  RGB，归一化到 [-1, 1]
骨干: ConvNext_FeatureExtractor(48, 3, 320) — ConvNeXtBlock × (4+12+10+8)
编码: XposMultiheadAttention + norm_first × 4层, nhead=4
解码: XposMultiheadAttention + norm_first × 5层, nhead=4
解码: Beam Batch (beams_k=5)，缓存激活值批量解码
特殊符: <S> (起始), </S> (终止), <SP> (空格)
颜色: 4个 Linear 层
  - color_pred_fg: [64→3] 前景 RGB
  - color_pred_bg: [64→3] 背景 RGB
  - color_pred_fg_ind: [64→2] 前景是否预测
  - color_pred_bg_ind: [64→2] 背景是否预测
  决策: fg_ind[1] > fg_ind[0] → 预测颜色
字典: alphabet-all-v7.txt
阈值: 0.2 (default)
模型文件: ocr_ar_48px.ckpt
```

#### 4.3 Model48pxCTCOCR (ocr48px_ctc) ⭐快速

```
输入: [batch, 3, 48, width]  RGB，归一化到 [-1, 1]
骨干: ResNet_FeatureExtractor(3, 320) — BasicBlock [4,6,8,6,3]
编码: CustomTransformerEncoderLayer(320, nhead=8, dim_ff=1280) × 3层
      CustomLayer 使用 PositionalEncoding (pe) 进行位置编码
CTC头:
  - char_pred_norm: LayerNorm(320) → Dropout(0.1) → GELU
  - char_pred: Linear(320, dictSize) — 字符分类
  - color_pred1: Linear(320, 6) — 前景/背景 RGB
解码: CTC Greedy Decode (log_softmax → argmax → 去重去 blank)
输出: char_logits [batch, seqLen, dictSize]
      color_values [batch, seqLen, 6] (fr,fg,fb,br,bg,bb)
字典: alphabet-all-v5.txt
阈值: 0.5 (default)
模型文件: ocr-ctc.ckpt
权重加载: 需删除 encoders.layers.{0,1,2}.pe.pe 后再 load_state_dict
```

#### 4.4 ModelMangaOCR (mocr) ⭐高精度

```
框架: Hugging Face Transformers VisionEncoderDecoder
预训练模型: kha-white/manga-ocr-base
来源: manga-ocr 库 (Kaushalya/kha-white)

┌─────────────────────────────────────────────────────────┐
│ 架构: Vision Encoder + Text Decoder                     │
│                                                         │
│  Encoder: Deita-VIT + Relative Position (ViT)           │
│    - 视觉特征提取，处理完整图片                          │
│    - 支持变长图片输入                                    │
│    - 注意力机制捕获字符级特征                            │
│                                                         │
│  Decoder: BERT-like Transformer                         │
│    - 自回归生成文本                                      │
│    - 最大生成长度: 300 tokens                            │
│    - 自动识别语言（主要日语）                            │
└─────────────────────────────────────────────────────────┘

输入预处理:
  1. 转灰度再转 RGB (保持与训练一致)
  2. 裁剪文字区域 (get_transformed_region)
  3. 高度固定 48px，宽度等比例
  4. ViTImageProcessor 归一化

输出后处理:
  1. 删除所有空白符
  2. 省略号规范化
  3. 半角转全角 (jaconv.h2z)

颜色预测:
  - 使用 ocr_ar_48px.ckpt (Model48pxOCR) 的 4 个 Linear 层
  - 批量推理: infer_beam_batch(beams_k=5)
  - 颜色来源: 文字区域内的前景/背景 RGB 平均

特殊功能:
  - use_mocr_merge: 启用 bbox 合并（更准确的气泡识别）
  - 支持竖排和横排文字
  - 鲁棒性强: 抗字体变化、模糊、低质量图片

精度: 最高
速度: 较慢（需加载完整 ViT+Transformer 推理）
```

### OCR 后的过滤

```python
# manga_translator.py:760-768
for textline in textlines:
    if textline.text.strip():
        # 应用用户指定的字体颜色覆盖
        if config.render.font_color_fg:
            textline.fg_r, textline.fg_g, textline.fg_b = config.render.font_color_fg
        if config.render.font_color_bg:
            textline.bg_r, textline.bg_g, textline.bg_b = config.render.font_color_bg
        new_textlines.append(textline)
```

---

## [5] Textline Merge（气泡合并）

**模块**: `manga_translator/textline_merge/`

**入口**: `dispatch_textline_merge()`

### 算法流程

#### 5.1 构建连通图

```python
# 对每对 Quadrilateral 判断是否能合并
can_merge = quadrilateral_can_merge_region(q1, q2, ...)
```

判断条件：
- 距离 < `(1 + γ) × font_size`（字体大小的 γ 倍）
- 角度差 < `0.2π`（约 36°）
- 宽高比接近
- 对齐方式一致（水平/垂直）

#### 5.2 找连通分量

使用 Union-Find 或 NetworkX 找连通分量，每个连通分量是一个潜在的气泡区域。

#### 5.3 递归拆分（MST）

```python
def split_text_region(bboxes, region_indices):
    # case 1: 只有一个 → 直接返回
    if len(region_indices) == 1:
        return [region_indices]

    # case 2: 两个 → 距离判断
    if len(region_indices) == 2:
        if distance < (1+γ)*fs and angle_diff < 0.2π:
            return [region_indices]
        else:
            return [[idx1], [idx2]]

    # case 3: 多个 → 构建 MST (Kruskal)
    #   边权重 = 两 bbox 的距离
    #   找最大边，判断是否应该切断
    if max_edge_distance <= mean + std * sigma:
        return [region_indices]  # 不拆分
    else:
        # 切断最大边，递归拆分子区域
        split_edge = max_edge
        subregions = ...
        return [split(sub1), split(sub2), ...]
```

### 输出

```python
# 返回合并后的 TextBlock (气泡) 列表
class TextBlock:
    text: str              # 合并后的完整文字
    lines: List[Quadrilateral]  # 包含的文字行
    translation: str       # 翻译结果
    target_lang: str       # 目标语言
    fg_r/g/b, bg_r/g/b    # 字体/背景色
```

---

## [6] Pre-Dictionary（译前词典）

```python
# manga_translator.py:527-540
pre_dict = load_dictionary(self.pre_dict)  # 从文件加载替换规则
for region in ctx.text_regions:
    region.text = apply_dictionary(region.text, pre_dict)
```

**词典格式**（支持正则表达式）：
```
原文本    替换文本
正则模式  替换结果
.*坏词.*  # 删除整行
```

---

## [7] Translation（翻译）

**模块**: `manga_translator/translators/`

### 支持的翻译器

| 类别 | 翻译器 | 说明 |
|------|--------|------|
| 云端 | Google, DeepL, ChatGPT, Gemini, 有道, 百度, Papago 等 | API 调用 |
| 本地离线 | NLLB, NLLB-BIG, Sugoi, MBart50, Qwen2, M2M100 等 | 本地模型 |
| 链式 | `trans1:lang1;trans2:lang2` | 多级翻译链 |

### 翻译流程

```python
# manga_translator.py:1094-1096
texts = [region.text for region in ctx.text_regions]
translated_sentences = await self._dispatch_with_context(config, texts, ctx)
```

### 上下文感知翻译

```python
# ChatGPT / ChatGPT2Stage 支持上下文注入
translator.set_prev_context(prev_ctx)
# prev_ctx 格式: "Here are the previous X pages for reference:\n<|1|>句子\n<|2|>句子\n..."
```

### 批量翻译优化

```python
# 单页翻译：所有文字一起发送
texts = [region.text for region in ctx.text_regions]
translated = await dispatch_translation(texts)

# 批量翻译（batch_size > 1）：
# - 预翻译阶段：检测 + OCR + 合并（逐图处理）
# - 翻译阶段：收集所有图片的所有文字，批量发送
# - 后处理：按图片分组应用翻译结果
```

---

## [8] Post-Dictionary（译后词典）

```python
# manga_translator.py:1206-1219
post_dict = load_dictionary(self.post_dict)
for region in ctx.text_regions:
    region.translation = apply_dictionary(region.translation, post_dict)
```

---

## [9] Post-Translation Check（译后验证）

### 9.1 单区域幻觉检测

```python
# 检查重复内容
if await _check_repetition_hallucination(translation, threshold=20):
    failed_regions.append(region)
    await _retry_translation_with_validation(region, config, ctx)
```

### 9.2 页面级目标语言比例检查

```python
# 阈值 > 5 个区域时检查
if len(ctx.text_regions) > 5:
    page_lang_check_result = await _check_target_language_ratio(
        ctx.text_regions, target_lang, min_ratio=0.5
    )
    # 失败则重试整个页面（最多3次）
```

### 9.3 过滤逻辑

```python
# 过滤条件
- 翻译结果为空 → 过滤
- 纯数字翻译 → 过滤
- 匹配 filter_text 正则 → 过滤
- 翻译与原文完全相同 → 过滤（除非 translator=original）
```

### 9.4 括号修正

```python
# 自动修正中日括号混用
# 「 → "  「 → ‘  etc.
```

---

## [10] Mask Refinement（蒙版精细化）

**模块**: `manga_translator/mask_refinement/`

**入口**: `dispatch_mask_refinement()`

### 流程

```python
# 1. 缩放蒙版到 detection_size 级别（保持精度）
scale_factor = max(min((raw_mask.shape[0] - raw_image.shape[0] / 3) / raw_mask.shape[0], 1), 0.5)
mask_resized = cv2.resize(raw_mask, ...) * scale_factor

# 2. 对每个文字行生成精细化蒙版
textlines = [Quadrilateral(line * scale_factor) for line in region.lines]
final_mask = complete_mask(img_resized, mask_resized, textlines, dilation_offset=20)

# 3. 可选：气泡忽略检测
if ignore_bubble >= 1 and ignore_bubble <= 50:
    # 使用 is_ignore() 检测非气泡区域并移除
    for contour in contours:
        textblock = cv2.bitwise_and(raw_image, raw_image, mask=temp_mask)
        if is_ignore(textblock, ignore_bubble):
            cv2.drawContours(final_mask, [contour], -1, 0, -1)

# 4. 还原到原始尺寸
final_mask = cv2.resize(final_mask, raw_image.shape[::-1])
```

### 输出

灰度蒙版图（0-255），白色区域为需要擦除/替换的区域。

---

## [11] Inpainting（擦除原文字）

**模块**: `manga_translator/inpainting/`

**入口**: `dispatch_inpainting()`

### 支持的擦除器

| 配置键 | 类名 | 模型 | 精度 |
|--------|------|------|------|
| `default` | `AotInpainter` | AOT / LaMa | — |
| `lama_large` | `LamaLargeInpainter` | LaMa Large | BF16/FP32/FP16 |
| `lama_mpe` | `LamaMPEInpainter` | LaMa MPE | — |
| `sd` | `StableDiffusionInpainter` | Stable Diffusion | — |
| `none` | `NoneInpainter` | — | 保留原图 |
| `original` | `OriginalInpainter` | — | 保留 mask 区域原图 |

### 流程

```python
ctx.img_inpainted = await dispatch_inpainting(
    inpainter_key,
    image=ctx.img_rgb,
    mask=ctx.mask,
    config=InpainterConfig(),
    inpainting_size=2048,
    device='cuda',
    verbose=False
)
# 输出: 擦除原文字后的图片
```

---

## [12] Rendering（绘制翻译）

**模块**: `manga_translator/rendering/`

**入口**: `dispatch_rendering()` / `dispatch_eng_render()`

### 支持的渲染器

| 配置键 | 说明 |
|--------|------|
| `default` | 默认渲染（支持竖排） |
| `manga2eng` | 漫画转英文渲染（仅水平） |
| `manga2eng_pillow` | Pillow 版本英文渲染 |
| `none` | 不渲染（保留 inpainted 图片） |

### 渲染配置

```python
class RenderConfig:
    renderer: Renderer              # 渲染器选择
    alignment: Alignment           # 对齐方式 (auto/left/center/right)
    direction: Direction           # 文字方向 (auto/horizontal/vertical)
    font_size: Optional[int]       # 固定字号
    font_size_offset: int          # 字号偏移
    font_size_minimum: int         # 最小字号
    font_color: Optional[str]       # 强制字体颜色 (#FFFFFF:000000)
    line_spacing: Optional[int]     # 行间距
    uppercase/lowercase: bool     # 大小写转换
    rtl: bool                      # 从右到左阅读顺序
    no_hyphenation: bool          # 禁用连字符
```

### 竖排文字渲染

```python
# 默认渲染器支持竖排（direction=auto 时自动检测）
if direction == 'vertical':
    # 旋转绘制，上下排列
else:
    # 标准水平绘制
```

### 阅读顺序排序

```python
# manga_translator.py:910-915
text_regions = sort_regions(
    text_regions,
    right_to_left=config.render.rtl,  # 日漫从右到左
    img=ctx.img_rgb,
    force_simple_sort=config.force_simple_sort
)
```

---

## [13] Downscale（可选还原）

```python
# 如果启用了 upscale 且设置了 revert_upscaling
if config.upscale.revert_upscaling:
    ctx.result = ctx.result.resize(ctx.input.size)
```

---

## 配置枚举汇总

### Detector 枚举

```python
class Detector(str, Enum):
    default = "default"      # DB + ResNet34（默认）
    dbconvnext = "dbconvnext"  # DB + ConvNeXt
    ctd = "ctd"               # ComicTextDetector
    craft = "craft"           # CRAFT + VGG16
    paddle = "paddle"         # PaddleOCR
    none = "none"             # 无检测
```

### OCR 枚举

```python
class Ocr(str, Enum):
    ocr32px = "32px"         # ResNet + Beam Search
    ocr48px = "48px"         # ConvNeXt + XPOS + Beam Batch（推荐）
    ocr48px_ctc = "48px_ctc" # ResNet + CTC（快速）
    mocr = "mocr"            # Manga-OCR 库
```

### Inpainter 枚举

```python
class Inpainter(str, Enum):
    default = "default"      # AOT
    lama_large = "lama_large" # LaMa Large（推荐）
    lama_mpe = "lama_mpe"    # LaMa MPE
    sd = "sd"                # Stable Diffusion
    none = "none"            # 不擦除
    original = "original"    # 保留原图
```

---

## 管线关键参数

### 检测参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `detection_size` | 2048 | 检测图片尺寸 |
| `text_threshold` | 0.5 | 文字区域二值化阈值 |
| `box_threshold` | 0.7 | Bbox 生成阈值 |
| `unclip_ratio` | 2.3 | 文字区域扩展比例 |
| `det_rotate` | False | 是否旋转检测 |
| `det_auto_rotate` | False | 自动检测竖排文字 |

### OCR 参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `use_mocr_merge` | False | manga-ocr 合并 bbox |
| `min_text_length` | 0 | 最小文字长度 |
| `ignore_bubble` | 0 | 忽略非气泡区域阈值（1-50） |
| `prob` | None | 最小识别置信度（None=模型默认） |

### 擦除参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `inpainting_size` | 2048 | 擦除图片尺寸 |
| `inpainting_precision` | BF16 | 精度格式 |
| `mask_dilation_offset` | 20 | 蒙版扩展像素 |
| `kernel_size` | 3 | 卷积核大小 |

---

## 文件位置

| 模块 | 路径 |
|------|------|
| 主入口 | `manga_translator/manga_translator.py` |
| 配置枚举 | `manga_translator/config.py` |
| 检测 | `manga_translator/detection/` |
| OCR | `manga_translator/ocr/` |
| 气泡合并 | `manga_translator/textline_merge/` |
| 蒙版精细化 | `manga_translator/mask_refinement/` |
| 擦除 | `manga_translator/inpainting/` |
| 渲染 | `manga_translator/rendering/` |
| 翻译 | `manga_translator/translators/` |
| 上色 | `manga_translator/colorization/` |
| 放大 | `manga_translator/upscaling/` |