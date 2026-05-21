# manga-image-translator 完整流程分析

> 基于 `.reference/manga-image-translator/` 源码分析，用于指导 MoeTranslate-comics 的开发。

## 流程概览

```
截图 → 上色(可选) → 超分(可选) → 文字检测 → 文字识别 → 文本行合并 → 翻译 → 蒙版生成 → 修复(Inpainting) → 渲染
```

---

## 第一步：文字检测（Detection）

### 支持的检测器（用户手动选择，非自动切换）

通过 `--detector` 参数或配置文件 `{"detector":{"detector": "ctd"}}` 指定。

| 检测器 | 模型 | 适用场景 | 备注 |
|--------|------|----------|------|
| **default** | ResNet34 + DBHead (`detect-20241225.ckpt`) | 黑白漫画、大多数场景 | **默认推荐**，搭配参数调整效果更佳 |
| **ctd** | ComicTextDetector（YOLOv5 + DB） | 检测不到足够文字时 | 漫画专用，可增加检测到的文本行数 |
| **dbconvnext** | ConvNeXt + DBHead | 实验性 | 更新的 backbone，理论更强 |
| **craft** | CRAFT（VGG16+RefineNet） | **不推荐用于漫画** | README 明确说明"不要对漫画使用 craft" |
| **paddle** | PaddleOCR | PaddlePaddle 生态用户 | 需额外安装 PaddlePaddle |

**实际使用**：大多数用户用 `default`，检测不到足够文字时切换到 `ctd`。其他三个是实验性/特殊场景选项。

### Default 检测器流程

1. **预处理**：保持宽高比缩放到 `detect_size`（默认 2048），双边滤波
2. **推理**：输入 `[1, 3, H, W]`，输出 `db [1, 2, H/4, W/4]` + `mask [1, 1, H, W]`
3. **后处理**（`SegDetectorRepresenter`）：
   - 阈值化（textThreshold=0.5）→ 二值图
   - 连通域标记
   - 轮廓提取
   - 旋转最小外接矩形
   - 概率过滤（boxThreshold=0.6）
   - Vatti unclip 扩展（unclipRatio=1.8）
   - 再次旋转最小外接矩形
4. **输出**：`Quadrilateral` 列表（4 角点 + 概率）

### 关键数据结构：`Quadrilateral`

```python
class Quadrilateral:
    pts: np.ndarray      # 4 个角点 [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]
    text: str            # 识别文字
    prob: float          # 置信度
    direction: str       # 'h' 或 'v'（根据宽高比判断）
    font_size: float     # 结构线较短边长度
    aspect_ratio: float  # 结构线较长边/较短边
    angle: float         # 主轴角度
    fg_r, fg_g, fg_b    # 前景色
    bg_r, bg_g, bg_b    # 背景色
```

**关键方法**：
- `distance(other)`：基于方向的距离计算（水平用左/右边缘，垂直用上/下边缘）
- `poly_distance(other)`：Shapely Polygon.distance()
- `get_transformed_region(img, direction, textheight)`：透视变换裁剪文字区域

**`structure` 属性**（对边中点连线）：
```
p1 = (pts[0]+pts[1])/2  →  p2 = (pts[2]+pts[3])/2  （边1中点到边3中点）
p3 = (pts[1]+pts[2])/2  →  p4 = (pts[3]+pts[0])/2  （边2中点到边4中点）
```

---

## 第二步：文字识别（OCR）

### 支持的 OCR 模型（用户手动选择）

通过 `--ocr` 参数或配置文件指定。

| OCR | 模型 | 适用场景 | 备注 |
|-----|------|----------|------|
| **ocr32px** | 自定义 32px 模型 | 早期版本 | 已较少使用 |
| **ocr48px** | 自定义 48px 模型 | 日语/韩语 | README 推荐 |
| **ocr48px_ctc** | 48px + CTC 解码 | 需要更快推理时 | CTC 解码比 beam search 快 |
| **mocr** | manga-ocr（ViT+BERT）| 日文漫画专用 | Python `manga_ocr` 库，效果最好 |

**实际使用**：日语推荐 `48px` 或 `mocr`，韩语推荐 `48px`。

### manga-ocr 流程（`ModelMangaOCR`）

1. **透视变换裁剪**：对每个 `Quadrilateral` 调用 `get_transformed_region()`，裁剪出文字区域
2. **manga-ocr 识别**：
   - 使用 Python 的 `manga_ocr` 库（ViT Encoder + BERT Decoder）
   - 输入：PIL Image
   - 输出：识别文字
3. **批量处理**：`max_chunk_size=16`，按宽度排序后批量编码
4. **`use_mocr_merge` 模式**：
   - 先调用 `merge_bboxes()` 合并 box
   - 对合并后的区域用 manga-ocr 识别
   - 更适合长文本

### 关键：OCR 识别**在合并之前**

```
检测到的 boxes → 逐个 OCR → 合并（有文字信息）
```

这与 ML Kit 的"检测+识别一体化"不同，manga-image-translator 是**先识别再合并**。

---

## 第三步：文本行合并（Textline Merge）

### `merge_bboxes_text_region()` 流程

**Step 1: 建图（Union-Find）**

对所有 `Quadrilateral` 对，调用 `quadrilateral_can_merge_region()`，满足条件的对连边。

**`quadrilateral_can_merge_region()` 条件**：

```python
def quadrilateral_can_merge_region(
    a, b,
    ratio=1.9,              # 横竖判断阈值
    discard_connection_gap=2,  # 距离容差（字符大小的倍数）
    char_gap_tolerance=0.6,    # 距离门控
    char_gap_tolerance2=1.5,   # 对齐容差
    font_size_ratio_tol=1.5,   # 字体大小比容差
    aspect_ratio_tol=2         # 宽高比容差
):
```

判断逻辑：
1. **距离粗筛**：`dist > discard_connection_gap × char_size` → 拒绝
2. **字体大小比**：`max(fs) / min(fs) > font_size_ratio_tol` → 拒绝
3. **宽高比交叉检查**：一个偏横一个偏竖 → 拒绝
4. **轴对齐分支**（两个都近似轴对齐）：
   - 距离 < `char_size × char_gap_tolerance` 才检查对齐
   - x-center 对齐 → 接受
   - 横竖交叉 → 拒绝
   - 两个都偏横 → x 边对齐
   - 两个都偏竖 → y 边对齐
5. **非轴对齐分支**：
   - 角度差 < 15°
   - 多边形距离 < `fs × char_gap_tolerance2`
   - 字体大小差 / fs < 0.25

**Step 2: MST 分割（`split_text_region`）**

对每个连通分量，使用 Kruskal 最小生成树判断是否需要分割：

```python
def split_text_region(bboxes, connected_region_indices, width, height, gamma=0.5, sigma=2):
```

- **case 1**：单个 box → 直接返回
- **case 2**：两个 box → 距离 < `(1+gamma)×fs` 且角度差 < 0.2π → 合并
- **case 3**：3+ 个 box → MST 分析：
  1. 构建完全图（边权 = Quadrilateral.distance()）
  2. Kruskal 最小生成树
  3. 按边权降序排列
  4. 检查最大边是否需要切断：
     - `max_dist <= mean + std × sigma` 或 `max_dist <= fs × (1+gamma)`
     - 且 `std < std_threshold` 或 `max_centroid_alignment < 5`
  5. 不满足 → 切断最大边，递归处理

**Step 3: Majority Vote 方向**

```python
dirs = [box.direction for box in txtlns]
majority_dir_top_2 = Counter(dirs).most_common(2)
# 平票时取 aspect_ratio 最大的 box 的方向
```

**Step 4: 排序**
- 竖排（'v'）：按 x 降序（从右到左），再按 y 升序
- 横排（'h'）：按 y 升序（从上到下）

**Step 5: 构建 TextBlock**

合并后的多个 `Quadrilateral` 组成一个 `TextBlock`：
```python
TextBlock(
    lines=[txtln.pts for txtln in txtlns],  # 所有角点
    texts=[txtln.text for txtln in txtlns],  # 所有文字
    font_size=min([txtln.font_size for txtln in txtlns]),
    angle=mean([txtln.angle for txtln in txtlns]),
    fg_color=mean(fg_colors),
    bg_color=mean(bg_colors),
)
```

---

## 第四步：翻译（Translation）

### 支持的翻译器

| 翻译器 | 类型 | 特点 |
|--------|------|------|
| **google** | 在线 | Google Translate |
| **baidu** | 在线 | 百度翻译 |
| **deepl** | 在线 | DeepL |
| **chatgpt** | 在线 | GPT-3.5/4 |
| **gemini** | 在线 | Google Gemini |
| **sakura** | 在线 | Sakura 模型 |
| **nllb** | 离线 | Meta NLLB |
| **sugoi** | 离线 | Sugoi 翻译器 |
| **m2m100** | 离线 | Meta M2M100 |
| **mbart50** | 离线 | MBart50 |
| **qwen2** | 离线 | 通义千问 |
| **deepseek** | 在线 | DeepSeek |
| **original** | - | 保持原文 |
| **none** | - | 不翻译 |

### 翻译流程

1. 每个 `TextBlock` 的文字拼接成完整文本
2. 调用翻译 API
3. 翻译结果存入 `TextBlock.translation`

---

## 第五步：蒙版生成（Mask Refinement）

- 使用检测阶段的 `mask_raw`
- 对每个文字区域生成精确蒙版
- 用于后续修复

---

## 第六步：修复（Inpainting）

### 支持的修复器

| 修复器 | 模型 | 特点 |
|--------|------|------|
| **default** | LaMa Large | 默认，效果最好 |
| **lama_large** | LaMa Large | 大模型 |
| **lama_mpe** | LaMa + MPE | 文字感知 |
| **sd** | Stable Diffusion | 最强但最慢 |
| **none** | - | 不修复 |
| **original** | - | 使用原图 |

### 流程

1. 使用蒙版遮盖文字区域
2. 使用修复模型填充背景
3. 输出 `img_inpainted`

---

## 第七步：渲染（Rendering）

### `render()` 流程

1. **字体大小调整**（`resize_regions_to_font_size()`）：
   - 计算目标字体大小（fixed / offset / minimum）
   - 根据翻译文本长度调整：
     - 翻译比原文长 → 放大字体 + 扩大 bounding box
     - 单轴扩展（水平扩宽，垂直扩高）
     - 双轴等比扩展（fallback）

2. **文字渲染**：
   - 根据方向选择 `put_text_horizontal()` 或 `put_text_vertical()`
   - 使用 OpenCV 渲染文字（带描边）
   - 生成 RGBA 临时图

3. **透视变换**：
   - 计算 Homography 矩阵
   - `cv2.warpPerspective()` 将文字贴回原图位置
   - Alpha 混合

### 关键特性

- **前景/背景色自动检测**：OCR 阶段预测每个字符的前景色和背景色
- **自动方向**：根据语言预设和宽高比判断
- **自适应字体大小**：根据翻译文本长度自动调整
- **边界扩展**：文字放不下时自动扩大区域

---

## 关键参数对照表

| 参数 | 默认值 | 用途 |
|------|--------|------|
| `detect_size` | 2048 | 检测输入尺寸 |
| `text_threshold` | 0.5 | 二值化阈值 |
| `box_threshold` | 0.6 | 概率过滤阈值 |
| `unclip_ratio` | 1.8 | Vatti unclip 扩展比例 |
| `ratio` | 1.9 | 横竖判断阈值 |
| `discard_connection_gap` | 2 | 距离容差（字符大小的倍数） |
| `char_gap_tolerance` | 0.6 | 距离门控 |
| `char_gap_tolerance2` | 1.5 | 对齐容差 |
| `font_size_ratio_tol` | 1.5 | 字体大小比容差 |
| `aspect_ratio_tol` | 2 | 宽高比容差 |
| `gamma` | 0.5 | MST 分割距离容差系数 |
| `sigma` | 2 | MST 分割标准差倍数 |
| `max_chunk_size` | 16 | OCR 批量大小 |

---

## 与 MoeTranslate-comics 的对比

| 功能 | manga-image-translator | MoeTranslate-comics |
|------|------------------------|---------------------|
| **检测** | 5 种可选（default/dbconvnext/ctd/craft/paddle），用户手动指定 | 2 种可选（ML Kit/DBNet），配置切换 |
| **OCR** | 4 种（32px, 48px, 48px-CTC, manga-ocr） | 2 种（ML Kit, manga-ocr） |
| **合并** | Union-Find + MST（Quadrilateral 级别） | Union-Find + MST（TextLine/QuadBox 级别） |
| **翻译** | 15+ 种翻译器 | 11 种翻译器 |
| **修复** | LaMa, SD 等 5 种 | 无（直接覆盖） |
| **渲染** | OpenCV 透视变换 + Alpha 混合 | Android Canvas + 竖排渲染 |
| **上色** | 支持（DDColor） | 不支持 |
| **超分** | 支持 | 不支持 |
| **运行环境** | Python + PyTorch | Android + ONNX Runtime |

**MoeTranslate-comics 简化了**：去掉了上色、超分、修复步骤，专注于"检测 → OCR → 合并 → 翻译 → 覆盖渲染"的核心流程，并用 ONNX Runtime 替代 PyTorch 以在 Android 上运行。

---

## 源码位置参考

```
manga_translator/
├── detection/
│   ├── __init__.py          # 检测器分发
│   ├── default.py           # ResNet34+DBHead 检测器
│   ├── dbnet_convnext.py    # ConvNeXt 检测器
│   ├── ctd.py               # ComicTextDetector
│   ├── craft.py             # CRAFT 检测器
│   └── default_utils/
│       ├── DBNet_resnet34.py # 模型定义
│       └── dbnet_utils.py    # SegDetectorRepresenter 后处理
├── ocr/
│   ├── __init__.py          # OCR 分发
│   ├── model_manga_ocr.py   # manga-ocr 包装
│   ├── model_48px.py        # 48px OCR 模型
│   └── common.py            # OCR 基类
├── textline_merge/
│   └── __init__.py          # merge_bboxes_text_region + split_text_region
├── translators/
│   ├── __init__.py          # 翻译器分发
│   ├── google.py            # Google Translate
│   ├── chatgpt.py           # ChatGPT
│   └── ...
├── rendering/
│   ├── __init__.py          # 渲染主逻辑
│   ├── text_render.py       # 文字渲染（OpenCV）
│   └── text_render_eng.py   # 英文渲染
├── inpainting/
│   └── ...                  # 修复模型
├── utils/
│   ├── generic.py           # Quadrilateral 类 + 合并判断函数
│   ├── generic2.py          # 工具函数
│   └── textblock.py         # TextBlock 类
└── manga_translator.py      # 主流程 _translate()
```
