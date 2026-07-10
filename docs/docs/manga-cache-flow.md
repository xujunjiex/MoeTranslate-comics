# 漫画翻译缓存流程文档

> 用于代码审查参考 — 覆盖所有缓存路径、状态机逻辑、文本匹配机制。

---

## 1. 整体架构：三层缓存

```
截图
  │
  ▼
IMAGE CACHE (findCacheExt) ─── 256-bit hash 精确/相似度匹配
  │ 命中 → 跳过全部流程，直接显示
  │
  ▼ 未命中
OCR ─── 检测、识别文本
  │
  ▼
TEXT CACHE (incrementalTranslateBubbles) ─── translatedRegions 模糊匹配
  │ 命中 → 复用译文，跳过翻译 API
  │
  ▼ 未命中
翻译 API → 渲染
  │
  ▼
saveToCache → 写数据库 + 文件（供下次查找）
```

| 缓存层 | 匹配方式 | 节省步骤 |
|--------|---------|---------|
| IMAGE（`findCacheExt`） | 256-bit hash 精确+相似度 | 跳过 OCR + 翻译 + 渲染 |
| TEXT（`translatedRegions`） | 原文加权编辑距离模糊匹配 | 跳过翻译 API |
| 都没命中 | — | 全跑 |

---

## 2. 截图入口

**手动模式：** 用户点击悬浮球 → `triggerTranslation()`
- `isProcessing = true`，关闭所有 overlay
- MP：`takeScreenshotWithProvider()` → 隐藏球 → `delay(50)` → `takeScreenshot()` → 恢复球 → 截图经 flow 到 collector
- 无障碍：`takeScreenshotWithProvider()` → 隐藏球 → `delay(50)` → `takeScreenshot()`（异步，结果到 flow）→ 恢复球 → 截图到 collector

**自动模式：** 每 500ms → `runAutoDetect()` → `triggerTranslation()` → 同上流程截图 → 截图到 collector

---

## 3. 自动翻译状态机（`processAutoDetectPHash`）

使用 64 位 `compute()` hash（9×8 dHash），与 256 位缓存 hash 独立。

```
阈值：
  PHASH_STABLE_THRESHOLD   = 0.95f  → ~3 bit（64 位），判断"画面没变"
  PHASH_NEW_PAGE_THRESHOLD = 0.60f  → ~25 bit（64 位），判断"翻页"
```

### 状态流转

```
IDLE
  │
  ├── 首次截图：直接翻译（lastTranslatedHash == 0）
  │
  ├── compare(lastTranslatedHash, currentHash) ≥ 0.95
  │   └── 同页 → skip，IDLE
  │
  └── < 0.95 → MOTION（stableCount=0，记录 motionStartTime）

MOTION（每 500ms 检测一次）
  │
  ├── compare(previousScreenshotHash, currentHash) ≥ 0.95
  │   ├── stableCount < 2 → stableCount++，继续 MOTION
  │   └── stableCount ≥ 2 → STABLE → onMotionStabilized()
  │
  └── < 0.95 → 重置 stableCount=0，继续 MOTION

onMotionStabilized(stableHash)
  │
  ├── compare(lastTranslatedHash, stableHash) ≥ 0.95 → 同已翻译页 → skip
  ├── < 0.60 → 新页面 → 翻译
  └── 0.60~0.95 → 小幅滚动/增量 → 翻译
```

### 输出：`return true` = 要翻译，`return false` = 跳过

---

## 4. Collector 路由（`setupScreenshotCollector`）

`ScreenshotManager.screenshotFlow.collect { data -> }`

```
截图 → collector
  │
  ├── pendingCleanScreenshot = true？
  │   └── 无障碍干净截图拦截：
  │       → 清除标志，取出保存的检测 pHash
  │       → showProgressOverlay("正在翻译…")
  │       → processMangaScreenshot(截图, 检测pHash, 检测extHashes)
  │       → return
  │
  └── 正常流程
        │
        ├── 自动模式：
        │   compute(data.fullBitmap) → 64 位
        │   computeExtended(data.fullBitmap) → 256 位
        │   pendingFullBitmap = data.fullBitmap
        │   shouldTranslate = processAutoDetectPHash(pHash)
        │   │
        │   ├── false → 回收 bitmap，return
        │   │
        │   └── true → （STABLE 确认翻译）
        │         │
        │         ├── MP：
        │         │   隐藏球 → delay(50) → takeScreenshot(干净)
        │         │   恢复球
        │         │   showProgressOverlay
        │         │   processMangaScreenshot(干净图, pHash, 干净extHashes)
        │         │
        │         └── 无障碍：
        │              launch {
        │                delay(300)       ← 等冷却
        │                隐藏球
        │                delay(50)        ← 等 SurfaceFlinger 刷新
        │                takeScreenshot()  ← 触发异步截图
        │                pendingDetectionPHash = pHash
        │                pendingDetectionExtHashes = extHashes
        │                pendingCleanScreenshot = true
        │                恢复球
        │              }
        │              回收当前截图，return
        │              （下一张 flow 到→pendingCleanScreenshot 拦截→翻译）
        │
        └── 手动模式：
            compute(data.fullBitmap) → 64 位
            computeExtended(data.fullBitmap) → 256 位
            processMangaScreenshot(截图, pHash, extHashes)
```

### 关键：MP 用同步重截，无障碍用异步 flow 拦截

MP 的 `takeScreenshot()` 直接返回 Bitmap，无障碍的返回 null（结果经 flow 异步回来）。

---

## 5. `processMangaScreenshot`（核心翻译管线）

```
processMangaScreenshot(bitmap, precomputedPHash?, precomputedExtHashes?)
  │
  ├── 1. 设置当前 hash
  │   currentPHash = precomputedPHash ?: compute(bitmap)          // 64 位
  │   currentExtHashes = precomputedExtHashes ?: computeExtended() // 256 位
  │
  ├── 2. 调试模式？→ 跳过缓存，直接检测
  │
  ├── 3. 缓存检查（非强制刷新时）
  │   findCacheExt(extHashes, MODE_MANGA, crop_width, crop_height, sessionId)
  │   ├── 精确匹配 → 显示结果，return
  │   ├── 相似度匹配 → 显示结果，return
  │   └── 都未命中 → 继续
  │
  ├── 4. OcrLock → OCR（按检测引擎走不同分支）
  │   ├── incrementalPPOcrV5（气泡数 >6 时分批处理）
  │   │   └── 触发了？→ 自行管理翻译+渲染+缓存，return
  │   └── 常规 OCR → 文字块列表
  │
  ├── 5. 气泡合并
  │   ├── 已前合并（PP-OCRv5/CTD/RT-DETR-V2）→ 跳过
  │   └── 需后合并（MLKit）→ BubbleDetector.detectBubbles()
  │
  ├── 6. 翻译（incrementalTranslateBubbles 走文本缓存）
  │
  ├── 7. 渲染 overlay → 显示
  │
  └── 8. saveTranslationCache → 持久化
```

---

## 6. 增量渲染（`incrementalPPOcrV5`）

当 PP-OCRv5 识别的文字行 > 6 时触发分批处理，减少首屏等待时间。

```
气泡数 > 6 → 空间聚类分组
  │
  ├── 第一批 OCR → incrementalTranslateBubbles → 先显示一批结果
  ├── 第二批 OCR（与第一批翻译并行执行）→ 合并到结果
  │
  └── finalizeIncremental → 最终渲染 + saveTranslationCache
```

### 上下文回滚

分批翻译时用 `forceContext=true` 让第二批看到第一批的上下文。两批翻译完后回滚 `contextHistory`，不污染后续页面。

---

## 7. 文本缓存（`translatedRegions` + `incrementalTranslateBubbles`）

跨页有效的气泡级文本缓存，存在于内存（非持久化）。

### 数据存储

```kotlin
translatedRegions: MutableList<TranslatedRegion>(
    ocrText: String,         // 原文
    translation: String,     // 译文
    ocrTextHash: Int,        // hashCode() 快速过滤
    translatedAt: Long       // 时间戳，TTL 5 分钟
)
```

### 生命周期
- **自动翻译开始/停止** → `clear()`（清空）
- **翻译过程中** → 每次翻译完加入
- **翻页后保留**（跨页有效，TTL 5 分钟）
- **自动翻译期间**每页开始前 `evictExpiredRegions()` 淘汰过期条目

### 查找流程

```
incrementalTranslateBubbles(bubbles, forceContext)
  │
  ├── 1. 精确匹配：hashCode + 字符串 ==
  │   └── 命中 → 复用译文
  │
  ├── 2. 模糊匹配：findFuzzyMatch(combinedText)
  │   → TextSimilarity.normalize（全角→半角，lowercase，trim）
  │   → weightedLevenshtein（易混字符低代价：カ/力、0/O、rn/m 等）
  │   → 自适应阈值（短文严格，长文宽松）
  │   └── 命中 → 复用译文
  │
  └── 3. 都没命中 → translateBubbles（调翻译 API）
```

---

## 8. IMAGE 缓存（`findCacheExt`）

### 精确匹配（快速路径）

```sql
SELECT * FROM page_cache
WHERE pHash = :e0 AND pHash2 = :e1 AND pHash3 = :e2 AND pHash4 = :e3
AND mode = 1 LIMIT 1
```

- SQL 索引查询，O(1)
- 同一截图方式同一页时 4 段 hash 全等 → 命中

### 相似度匹配（降级路径）

```
SIMILARITY_THRESHOLD_MANGA = 0.95f  → 256 位中 ~13 bit 容差
```

- 遍历全部缓存，O(n)
- 不同截图方式（MP vs 无障碍）同一页仍能命中

### 面积比校验

裁剪区域面积变化在 0.8~1.25 倍之间才认，防止不同框选区域误命中。

---

## 9. 缓存保存（`saveToCache`）

```
saveToCache(entry, originalBitmap, createdAt)
  │
  ├── 1. 精确去重：
  │   findCacheByHashAndCropRect(pHash, cropLeft/Top/Right/Bottom)
  │   ├── 找到 → 删旧图片、旧 history、旧 cache
  │   │       → 继承旧 sessionId 和 createdAt
  │   └── 未找到 → findCacheByPHash(pHash) 再次尝试继承
  │
  ├── 2. 保存渲染图、原图到文件
  │
  ├── 3. 插入 HistoryEntity（含 4 段 hash、OCR 原文/译文、图片路径）
  │
  ├── 4. LRU 淘汰：cache > 100 条时删最久一条（含关联文件）
  │
  └── 5. 插入 PageCacheEntity（供下次 findCacheExt 查找）
```

### sessionId 继承优先级

```
① 精确去重找到的旧记录 → 继承 sessionId/createdAt
② findCacheByPHash(pHash) → 继承任意同 pHash 记录
③ 都没有 → 全新 sessionId、当前时间
```

---

## 10. pHash 体系

### 两种算法

| | `compute()` | `computeExtended()` |
|---|---|---|
| 缩放 | 9×8 | 17×16 |
| 位数 | 64（1×Long） | 256（4×Long） |
| 用途 | 状态机翻页判断 | 缓存匹配 |
| 相似度分母 | 64 | 256 |

### 三个阈值

| 阈值 | 值 | 用于 | 含义 |
|------|----|------|------|
| `PHASH_STABLE_THRESHOLD` | 0.95（64 位） | 状态机判稳 | ~3 bit 差异 |
| `PHASH_NEW_PAGE_THRESHOLD` | 0.60（64 位） | 状态机判翻页 | ~25 bit 差异 |
| `SIMILARITY_THRESHOLD_MANGA` | 0.95（256 位） | 缓存相似度匹配 | ~13 bit 差异 |

### 场景相似度（256 位）

| 场景 | 差异位 | 相似度 |
|------|--------|--------|
| 同截图方式同页连续截图 | 0-3 | 0.988~1.0 |
| 不同截图方式同页 | 1-15 | 0.94~0.996 |
| 同页框选偏移 | 5-20 | 0.92~0.98 |
| 分格相似的漫画不同页 | 40-80 | 0.69~0.84 |
| 分格不同的漫画不同页 | 80-150 | 0.41~0.69 |

---

## 11. 状态机与缓存的连接

`lastTranslatedHash` 连接状态机和缓存：

```
检测截图（有球）→ compute() → 64 位 pHash
  → 状态机判断 IDLE/MOTION/STABLE
  → STABLE → 重截干净图
  → processMangaScreenshot(干净图, 检测pHash, 干净extHashes)
    ├── currentPHash = 检测pHashes（有球，64 位）
    ├── currentExtHashes = 干净extHashes（无球，256 位）
    ├── lastTranslatedHash = currentPHash（有球）
    ├── 缓存存入：pHash/2/3/4 = currentExtHashes（干净）
    └── 下次检测：compare(lastTranslatedHash=有球, 新截图=有球) → 稳定跳过
```

状态机用的 64 位 hash 和缓存用的 256 位 hash 互相独立。干净截图只影响缓存，不影响状态机。

---

## 12. 已知问题 / 边界情况

### 新旧 pHash 算法过渡期

第一次升级后翻译旧页面时，`saveToCache` 中 `findCacheByPHash(entry.pHash)` 用新算法值查不到旧记录（旧算法值不同）：
- sessionId 不继承 → 新条目排序位置变化
- 旧记录不会被删除（dedup 查不到）
- **恢复**：LRU 逐步淘汰旧条目，后续所有新条目都用新算法

### 无障碍干净截图可能失败

无障碍 `takeScreenshot` 是异步的。如果系统 API 失败（`onFailure`）：
- `pendingCleanScreenshot` 保持 true
- 下次检测截图被拦截 → 作为"干净"截图处理（但可能带球）
- **恢复**：不影响功能，只是缓存 hash 可能含球，跨方法缓存命中率略降

### 历史 pHash 显示

`HistoryMangaAdapter` 显示完整 64 位 `%016X`（已修复，之前只显示低 32 位 `%08X` 引起混淆）。

---

## 13. 审查检查清单

审查代码改动时检查：

- [ ] `findCacheExt` 精确匹配 → 相似度匹配 → 面积比校验顺序正确
- [ ] `saveToCache` dedup 用 `findCacheByHashAndCropRect`（精确 pHash + crop rect）
- [ ] `currentExtHashes` 是否被正确设置（不从 `precomputedExtHashes` 来就从 `computeExtended` 来）
- [ ] MP 重截：隐藏球 → `delay(50)` → `takeScreenshot` → 恢复球
- [ ] 无障碍重截：`delay(300)` → 隐藏球 → `delay(50)` → `takeScreenshot` → 设 flag → 恢复球
- [ ] `pendingCleanScreenshot` 拦截在 `try-catch` 内
- [ ] `lastTranslatedHash` 用检测截图的 64 位 hash（有球）
- [ ] `incrementalTranslateBubbles` 同时服务自动和手动模式
- [ ] `translatedRegions` 跨页不清空（仅 start/stop auto 时 clear）
- [ ] 文本缓存精确匹配：`hashCode` + `字符串 ==` 双重验证
- [ ] 不引入死代码（DAO query 没有调用方就是死代码）
