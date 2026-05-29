# Manga-OCR 模型调用流程详解

> 基于官方源码 `manga_translator/ocr/model_manga_ocr.py` 完整分析。

---

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ModelMangaOCR                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  输入: image (np.ndarray) + textlines (List[Quadrilateral])                │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. 加载两个模型                                                        │   │
│  │    - MangaOcr(): manga-ocr 文字识别                                   │   │
│  │    - OCR(48px): Model48pxOCR 颜色预测                                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 2. 文字区域预处理                                                      │   │
│  │    - 方向判断 (horizontal/vertical)                                    │   │
│  │    - 裁剪为 48px 高图片                                                │   │
│  │    - 按宽度排序                                                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 3. 气泡合并 (use_mocr_merge=True 时)                                   │   │
│  │    - NetworkX 构建连通图                                               │   │
│  │    - MST 拆分判断                                                      │   │
│  │    - 合并相邻文字区域                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 4. manga-ocr 串行识别文字 (每次一张)                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 5. Model48pxOCR 批量预测颜色 (每 batch 16 张)                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 6. 合并结果                                                            │   │
│  │    - manga-ocr 文字 + 颜色加权平均                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  输出: List[TextBlock]                                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 模型加载

```python
# model_manga_ocr.py

class ModelMangaOCR(OfflineOCR):
    async def _load(self, device: str):
        # 1. 加载 48px OCR 模型（用于颜色预测）
        with open(self._get_file_path('alphabet-all-v7.txt'), 'r', encoding='utf-8') as fp:
            dictionary = [s[:-1] for s in fp.readlines()]
        self.model = OCR(dictionary, 768)  # Model48pxOCR
        sd = torch.load(self._get_file_path('ocr_ar_48px.ckpt'))
        self.model.load_state_dict(sd)

        # 2. 加载 manga-ocr（用于文字识别）
        self.mocr = MangaOcr()
```

**关键点**：加载两个独立的模型
- `self.mocr`: manga-ocr，负责文字识别
- `self.model`: Model48pxOCR，只用于颜色预测

---

## 3. 完整推理流程

```python
async def _infer(self, image, textlines, config, verbose=False, ignore_bubble=0):
    text_height = 48
    max_chunk_size = 16

    # ============================================
    # 步骤 1: 文字区域预处理
    # ============================================

    # 生成方向和裁剪图片
    quadrilaterals = list(self._generate_text_direction(textlines))
    region_imgs = [q.get_transformed_region(image, d, text_height)
                   for q, d in quadrilaterals]

    # 按宽度排序
    perm = range(len(region_imgs))
    is_quadrilaterals = False
    if len(quadrilaterals) > 0 and isinstance(quadrilaterals[0][0], Quadrilateral):
        perm = sorted(range(len(region_imgs)), key=lambda x: region_imgs[x].shape[1])
        is_quadrilaterals = True

    texts = {}

    # ============================================
    # 步骤 2: 气泡合并 (use_mocr_merge)
    # ============================================

    if config.use_mocr_merge:
        # 合并相邻文字区域
        merged_textlines, merged_idx = await merge_bboxes(
            textlines, image.shape[1], image.shape[0])
        merged_quadrilaterals = list(self._generate_text_direction(merged_textlines))
    else:
        # 不合并，每个区域独立
        merged_idx = [[i] for i in range(len(region_imgs))]
        merged_quadrilaterals = quadrilaterals

    # 准备合并区域的图片
    merged_region_imgs = []
    for q, d in merged_quadrilaterals:
        if d == 'h':
            merged_text_height = q.aabb.w  # 水平文字用宽度作为高度
            merged_d = 'h'
        elif d == 'v':
            merged_text_height = q.aabb.h  # 垂直文字用高度
            merged_d = 'h'  # manga-ocr 只支持水平，旋转90度
        merged_region_imgs.append(
            q.get_transformed_region(image, merged_d, merged_text_height))

    # ============================================
    # 步骤 3: manga-ocr 串行文字识别
    # ============================================

    for idx in range(len(merged_region_imgs)):
        # 每次只处理一张图！这是瓶颈所在
        texts[idx] = self.mocr(Image.fromarray(merged_region_imgs[idx]))

    # ============================================
    # 步骤 4: Model48pxOCR 批量颜色预测
    # ============================================

    out_regions = {}
    for indices in chunks(perm, max_chunk_size):
        N = len(indices)
        widths = [region_imgs[i].shape[1] for i in indices]
        max_width = 4 * (max(widths) + 7) // 4  # 4字节对齐

        # 构建批量张量 [N, 48, max_width, 3]
        region = np.zeros((N, text_height, max_width, 3), dtype=np.uint8)
        idx_keys = []
        for i, idx in enumerate(indices):
            idx_keys.append(idx)
            W = region_imgs[idx].shape[1]
            region[i, :, :W, :] = region_imgs[idx]

        # 归一化并转换为 CHW 格式
        image_tensor = (torch.from_numpy(region).float() - 127.5) / 127.5
        image_tensor = einops.rearrange(image_tensor, 'N H W C -> N C H W')

        if self.use_gpu:
            image_tensor = image_tensor.to(self.device)

        # 批量推理（只用于获取颜色，不获取文字）
        with torch.no_grad():
            ret = self.model.infer_beam_batch(
                image_tensor, widths, beams_k=5, max_seq_length=255)

        # 解析颜色
        for i, (pred_chars_index, prob, fg_pred, bg_pred,
                fg_ind_pred, bg_ind_pred) in enumerate(ret):
            if prob < 0.2:
                continue

            # 提取平均颜色
            fr = AvgMeter()
            fg = AvgMeter()
            fb = AvgMeter()
            br = AvgMeter()
            bg = AvgMeter()
            bb = AvgMeter()

            has_fg = (fg_ind_pred[:, 1] > fg_ind_pred[:, 0])
            has_bg = (bg_ind_pred[:, 1] > bg_ind_pred[:, 0])

            for chid, c_fg, c_bg, h_fg, h_bg in zip(
                    pred_chars_index, fg_pred, bg_pred, has_fg, has_bg):
                ch = self.model.dictionary[chid]
                if ch == '<S>':
                    continue
                if ch == '</S>':
                    break

                if h_fg.item():
                    fr(int(c_fg[0] * 255))
                    fg(int(c_fg[1] * 255))
                    fb(int(c_fg[2] * 255))
                if h_bg.item():
                    br(int(c_bg[0] * 255))
                    bg(int(c_bg[1] * 255))
                    bb(int(c_bg[2] * 255))
                else:  # 无背景色时用前景色
                    br(int(c_fg[0] * 255))
                    bg(int(c_fg[1] * 255))
                    bb(int(c_fg[2] * 255))

            fr = min(max(int(fr()), 0), 255)
            # ... (其他颜色通道)

            # 保存结果
            cur_region = quadrilaterals[indices[i]][0]
            cur_region.fg_r = fr
            cur_region.fg_g = fg
            cur_region.fg_b = fb
            cur_region.bg_r = br
            cur_region.bg_g = bg
            cur_region.bg_b = bb
            cur_region.prob = prob

            out_regions[idx_keys[i]] = cur_region

    # ============================================
    # 步骤 5: 合并结果
    # ============================================

    output_regions = []
    for i, nodes in enumerate(merged_idx):
        # 收集所有子区域的概率和颜色
        total_logprobs = 0
        total_area = 0
        fg_r, fg_g, fg_b = [], [], []
        bg_r, bg_g, bg_b = [], [], []

        for idx in nodes:
            if idx not in out_regions:
                continue
            region_out = out_regions[idx]
            total_logprobs += np.log(region_out.prob) * region_out.area
            total_area += region_out.area
            fg_r.append(region_out.fg_r)
            fg_g.append(region_out.fg_g)
            fg_b.append(region_out.fg_b)
            bg_r.append(region_out.bg_r)
            bg_g.append(region_out.bg_g)
            bg_b.append(region_out.bg_b)

        # 加权平均概率
        if total_area > 0:
            prob = np.exp(total_logprobs / total_area)
        else:
            prob = 0.0

        # 平均颜色
        fr = round(np.mean(fg_r)) if fg_r else 0
        fg = round(np.mean(fg_g)) if fg_g else 0
        fb = round(np.mean(fg_b)) if fg_b else 0
        br = round(np.mean(bg_r)) if bg_r else 0
        bg = round(np.mean(bg_g)) if bg_g else 0
        bb = round(np.mean(bg_b)) if bg_b else 0

        # 最终结果
        cur_region = merged_quadrilaterals[i][0]
        cur_region.text = texts[i]  # manga-ocr 识别结果
        cur_region.prob = prob
        cur_region.fg_r = fr
        cur_region.fg_g = fg
        cur_region.fg_b = fb
        cur_region.bg_r = br
        cur_region.bg_g = bg
        cur_region.bg_b = bb

        output_regions.append(cur_region)

    return output_regions
```

---

## 4. 合并逻辑 (merge_bboxes)

当 `use_mocr_merge=True` 时，调用 `merge_bboxes()` 合并相邻文字区域。

### 4.1 合并条件

```python
# 合并两个 Quadrilateral 的条件 (quadrilateral_can_merge_region)
def quadrilateral_can_merge_region(
    a: Quadrilateral,
    b: Quadrilateral,
    ratio=1.9,                    # 宽高比阈值
    discard_connection_gap=2,     # 字符间距容忍度
    char_gap_tolerance=0.6,       # 字符间隙容忍度
    char_gap_tolerance2=1.5,      # 较大间隙容忍度
    font_size_ratio_tol=1.5,      # 字号比例容忍度
    aspect_ratio_tol=2           # 宽高比容忍度
) -> bool:
```

**核心判断条件**：

1. **距离条件**：`dist < discard_connection_gap * char_size`
   - 两个四边形的 polygon 距离必须小于字号 × 2

2. **字号条件**：`max(font_size) / min(font_size) < font_size_ratio_tol`
   - 字号差异不能太大

3. **宽高比条件**：不能一个横排一个竖排

4. **轴对齐情况**：
   ```python
   if a_aa and b_aa:  # 都是轴对齐矩形
       if dist < char_size * char_gap_tolerance:
           # 水平排列：x 坐标接近
           # 垂直排列：y 坐标接近
   ```

5. **旋转四边形**：
   ```python
   if not a_aa and not b_aa:
       if abs(a.angle - b.angle) < 15°:  # 角度差小于15度
           if a.poly_distance(b) < fs * char_gap_tolerance2:
               if abs(fs_a - fs_b) / fs < 0.25:  # 字号差小于25%
                   return True
   ```

### 4.2 合并流程

```python
async def merge_bboxes(bboxes, width, height):
    # 步骤 1: 构建连通图
    G = nx.Graph()
    for i, box in enumerate(bboxes):
        G.add_node(i, box=box)

    for ((u, ubox), (v, vbox)) in itertools.combinations(enumerate(bboxes), 2):
        if quadrilateral_can_merge_region(ubox, vbox, ...):
            G.add_edge(u, v)  # 可合并则加边

    # 步骤 2: 找连通分量，然后 MST 拆分
    region_indices = []
    for node_set in nx.algorithms.components.connected_components(G):
         region_indices.extend(split_text_region(bboxes, node_set, width, height))

    # 步骤 3: 处理每个合并区域
    merge_box = []
    merge_idx = []
    for node_set in region_indices:
        nodes = list(node_set)
        txtlns = np.array(bboxes)[nodes]

        # 方向投票
        dirs = [box.direction for box in txtlns]
        majority_dir = Counter(dirs).most_common(1)[0][0]

        # 排序
        if majority_dir == 'h':
            nodes = sorted(nodes, key=lambda x: bboxes[x].centroid[1])
        elif majority_dir == 'v':
            nodes = sorted(nodes, key=lambda x: -bboxes[x].centroid[0])

        merge_box.append(np.array(bboxes)[nodes])
        merge_idx.append(nodes)

    # 步骤 4: 合并为最小外接矩形
    return_box = []
    for bbox in merge_box:
        if len(bbox) == 1:
            return_box.append(bbox[0])
        else:
            # 使用 Shapely 合并所有四边形
            min_rect = Polygon([*base_box.pts, *box.pts]).minimum_rotated_rectangle
            base_box = Quadrilateral(min_rect.exterior.coords[:4], '', prob)
            return_box.append(base_box)

    return return_box, merge_idx
```

### 4.3 MST 拆分 (split_text_region)

```python
def split_text_region(bboxes, connected_region_indices, width, height, gamma=0.5, sigma=2):
    # case 1: 只有一个区域
    if len(connected_region_indices) == 1:
        return [set(connected_region_indices)]

    # case 2: 两个区域
    if len(connected_region_indices) == 2:
        fs = max(fs1, fs2)
        if distance < (1 + gamma) * fs and angle_diff < 0.2π:
            return [set(connected_region_indices)]  # 合并
        else:
            return [set([idx1]), set([idx2])]  # 拆分

    # case 3: 多个区域，构建 MST
    G = nx.Graph()
    for idx in connected_region_indices:
        G.add_node(idx)
    for (u, v) in itertools.combinations(connected_region_indices, 2):
        G.add_edge(u, v, weight=bboxes[u].distance(bboxes[v]))

    # 找最大边 (MST 的性质：去掉最大边后分成两部分)
    edges = nx.algorithms.tree.minimum_spanning_edges(G, algorithm='kruskal')
    edges = sorted(edges, key=lambda a: a[2]['weight'], reverse=True)

    # 判断是否需要拆分
    if should_split(edges, distances_mean, distances_std, sigma):
        # 去掉最大边，递归处理
        G.remove_edge(max_edge[0], max_edge[1])
        for node_set in nx.algorithms.components.connected_components(G):
            ans.extend(split_text_region(bboxes, node_set, width, height))
        return ans
    else:
        return [set(connected_region_indices)]  # 保持合并
```

---

## 5. 数据流示例

假设有 3 个文字区域 A、B、C，其中 A 和 B 相邻可合并：

```
输入: textlines = [A, B, C]
         A  B  C  (A 和 B 距离近，可合并)

步骤 1: merge_bboxes
  merged_textlines = [AB, C]  (AB 合并为一个区域)
  merged_idx = [[0, 1], [2]]

步骤 2: manga-ocr 识别
  texts[0] = mocr(AB区域图) → "Hello"
  texts[1] = mocr(C区域图) → "World"

步骤 3: Model48pxOCR 颜色预测
  [A图, B图] → infer_beam_batch → [colors_A, colors_B]
  [C图] → infer_beam_batch → [colors_C]
  out_regions[0] = {colors_A, prob=0.9}
  out_regions[1] = {colors_B, prob=0.85}
  out_regions[2] = {colors_C, prob=0.95}

步骤 4: 合并结果
  for i=0, nodes=[0,1]:
    # AB 合并
    prob = (0.9*area_A + 0.85*area_B) / (area_A + area_B)
    fg = mean(colors_A, colors_B)
    output[0] = {text="Hello", prob, fg, bg}

  for i=1, nodes=[2]:
    # C 独立
    output[1] = {text="World", prob=0.95, colors_C}

输出: [AB结果, C结果]
```

---

## 6. 为什么 manga-ocr 必须串行？

| 原因 | 说明 |
|------|------|
| **固定输入尺寸** | Encoder 需要 `[3, 224, 224]`，文字区域必须 resize |
| **自回归解码** | Decoder 逐字符生成，无法并行 |
| **变长生成长度** | 不同图片生成长度不同，无法 batch |

```python
# 串行调用
for idx in range(len(merged_region_imgs)):
    texts[idx] = self.mocr(Image.fromarray(merged_region_imgs[idx]))
    #                          ↑ 每次只处理一张图
```

---

## 7. 关键代码位置

| 文件 | 说明 |
|------|------|
| `manga_translator/ocr/model_manga_ocr.py` | 主入口，完整流程 |
| `manga_translator/ocr/model_manga_ocr.py::merge_bboxes` | 合并函数 |
| `manga_translator/textline_merge/__init__.py::split_text_region` | MST 拆分 |
| `manga_translator/utils/generic.py::quadrilateral_can_merge_region` | 合并条件判断 |
| `manga-ocr/manga_ocr/ocr.py` | manga-ocr 核心实现 |