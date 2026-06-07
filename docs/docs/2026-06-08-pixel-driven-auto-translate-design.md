# 像素驱动的双速自动翻译

## 目标

用像素比较替代 OCR 文字比较作为稳定检测手段，配合更激进的轮询间隔，提升游戏自动翻译的反应速度。

## 当前问题

- 固定 500ms 轮询间隔，反应慢
- 像素预筛只能判断"页面没变"，无法判断"页面稳定了"
- "等待稳定"状态依赖 OCR 跑两次文字比较，但像素预筛可能阻止 OCR 执行

## 设计

### 核心变化

```
当前：截图(500ms) → 像素预筛 → OCR → 文字比较 → 翻译/等待
优化：像素快检(150ms) → 像素稳定2帧? → OCR → 和上次翻译比较 → 翻译/缓存
```

### 状态机

```
CHANGED ──像素稳定──→ STABLE_1 ──像素仍稳定──→ STABLE_2 → 触发OCR
  ▲                                                           │
  │ 像素变化                                          OCR文字 ≠ 上次翻译?
  └──────────────────────────────────────────────────────────┘
```

状态定义：
- **CHANGED**：像素和上帧不同，页面正在变化。重置稳定计数。
- **STABLE_1**：像素第一次稳定。
- **STABLE_2**：像素连续稳定 2 帧（300ms），触发 OCR。

### 关键参数

| 参数 | 默认值 | 存储键 | 说明 |
|------|--------|--------|------|
| 像素快检间隔 | 150ms | 硬编码 | PixelCompare ~5-10ms |
| 稳定帧阈值 | 2 帧 | 硬编码 | 300ms 后触发 OCR |
| 像素相似阈值 | 5% | `Game_Pixel_Similar_Threshold` | diffRatio < 此值认为像素没变，用户可调 |
| 文字相似阈值 | 0.9 | 硬编码 | OCR 结果和上次翻译比较 |

### OCR 触发后的流程

1. 执行 OCR 识别
2. 结果 normalize 后和 `lastTranslatedText` 比较
3. 相似度 ≥ 0.9 → 文字相同 → 显示缓存
4. 相似度 < 0.9 → 文字不同 → 调用翻译 API
5. 翻译成功 → 更新 `lastTranslatedText`，保存缓存

### 手动翻译

点击悬浮球 → 跳过像素检查 → 立即 OCR + 翻译

### 用户可调设置

在**个性化设置 → OCR** 部分新增：

- **像素变化阈值**（`Game_Pixel_Similar_Threshold`）
  - 类型：SeekBarPreference 或 ListPreference
  - 范围：1% ~ 20%，步长 1%
  - 默认：5%
  - 说明：diffRatio 低于此值认为画面没变化。越低越敏感，越高越宽容。

### 文件改动

| 文件 | 改动 |
|------|------|
| `AutoTranslateEngine.kt` | 重写状态机：CHANGED/STABLE_1/STABLE_2，像素驱动 OCR 触发，OCR 结果和 lastTranslatedText 比较 |
| `FloatingBallService.kt` | 轮询调度改为 150ms 像素快检 + OCR 触发分离 |
| `PixelCompare.kt` | 不变 |
| `GameDebugOverlay.kt` | 不变 |
| `PersonalizationConfig.kt` | 新增像素阈值设置项 |
| `personalization.xml` | 新增像素阈值 preference |
| `CustomPreference.kt` | 新增读取像素阈值的方法（或用现有 getInt） |
| `strings.xml` | 新增设置项文案 |

### 不再需要的逻辑

- `waitingForStability` 状态（像素稳定就是稳定）
- OCR 文字间的 Levenshtein 比较（改为和 lastTranslatedText 比较）
- 500ms 固定轮询（改为 150ms 像素快检）
- `lastOCRText` 变量（改为 `lastTranslatedText`）
