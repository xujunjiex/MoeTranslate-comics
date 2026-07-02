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

| 缓存层 | 位置 | 匹配方式 | 节省步骤 |
|--------|------|---------|---------|
| IMAGE | `TranslationCacheManager.findCacheExt` | 256-bit hash 精确+相似度(0.95) | OCR + 翻译 + 渲染 |
| TEXT | `MangaFloatingService.incrementalTranslateBubbles` | 原文加权编辑距离模糊匹配 | 翻译 API |
| 都没命中 | — | — | 全跑 |

---

## 2. 截图入口

**手动模式：** 用户点击悬浮球 → `triggerTranslation()`
- `isProcessing = true`，关闭所有 overlay
- `takeScreenshotWithProvider()` → 手动模式隐藏球 → `delay(50)` → `takeScreenshot()` → 恢复球 → 截图经 flow 到 collector

**自动模式：** 每 500ms → `runAutoDetect()` → `triggerTranslation()` → 同上流程截图 → 截图到 collector

### `takeScreenshotWithProvider` 隐藏逻辑

```kotlin
// 仅手动模式隐藏球（自动模式由重截逻辑处理）
if (!isAutoTranslating) {
    ballWasShowing = if (球可见) { 球.GONE; true } else false
}
lifecycleScope.launch {
    if (ballWasShowing) delay(50)
    provider.takeScreenshot(...)
    // ...
} finally {
    if (ballWasShowing) 球.VISIBLE
}
```

---

## 3. 自动翻译状态机（`processAutoDetectPHash`）

使用 64 位 `PerceptualHash.compute()`（9×8 dHash），与 256 位缓存 hash 独立。

**阈值：**
- `PHASH_STABLE_THRESHOLD = 0.95f` → ~3 bit（64 位），判断"画面没变"
- `PHASH_NEW_PAGE_THRESHOLD = 0.60f` → ~25 bit（64 位），判断"翻页"

### 状态流转

```
IDLE
  ├── 首次截图：直接翻译
  ├── compare(lastTranslatedHash, currentHash) ≥ 0.95 → 同页跳过
  └── < 0.95 → MOTION（stableCount=0）

MOTION（每 500ms 截图检测）
  ├── compare(previousScreenshotHash, currentHash) ≥ 0.95
  │   ├── stableCount < 2 → 继续等
  │   └── stableCount ≥ 2 → STABLE → onMotionStabilized()
  └── < 0.95 → 重置 stableCount

onMotionStabilized(stableHash)
  ├── compare(lastTranslatedHash, stableHash) ≥ 0.95 → 同页 → 跳过
  ├── < 0.60 → 新页面 → 翻译
  └── 0.60~0.95 → 小幅滚动 → 翻译
```

---

## 4. Collector 路由

`ScreenshotManager.screenshotFlow.collect { data -> ... }`

```
截图 → collector
  │
  ├── pendingCleanScreenshot？→ 无障碍干净截图拦截
  │   → processMangaScreenshot(截图, 检测pHash, 检测extHashes)
  │
  └── 正常流程
        ├── 自动模式 STABLE:
        │   ├── MP: 隐藏球→takeScreenshot(同步)→恢复球→翻译
        │   └── 无障碍: launch{delay300→隐藏球→takeScreenshot→设flag→恢复球}→return(等下一张flow)
        └── 手动模式: 直接 processMangaScreenshot
```

---

## 5. `processMangaScreenshot`（核心管线）

```kotlin
processMangaScreenshot(bitmap, precomputedPHash?, precomputedExtHashes?)
  │
  ├── 1. 设置 hash
  │   currentPHash = precomputedPHash ?: compute(bitmap)      // 64 位
  │   currentExtHashes = precomputedExtHashes ?: computeExtended()
  │
  ├── 2. 调试模式？→ 跳过缓存
  │
  ├── 3. findCacheExt(currentExtHashes, MODE_MANGA, ...)
  │   ├── 精确命中 → 显示，return
  │   ├── 相似度命中 → 显示，return
  │   └── 未命中 → 继续
  │
  ├── 4. OCR → TextBlockInfo 列表
  │   ├── incrementalPPOcrV5（>6行时分批）→ 自行管理，return
  │   └── 常规 OCR
  │
  ├── 5. 气泡合并
  │
  ├── 6. incrementalTranslateBubbles（文本缓存+翻译）
  │
  ├── 7. renderOverlay → 显示
  │
  └── 8. saveTranslationCache → 持久化
```

---

## 6. 文本缓存（`translatedRegions`）

内存列表，跨页有效，TTL 5 分钟。

### 查找流程

```
incrementalTranslateBubbles(bubbles, forceContext)
  ├── 精确匹配: hashCode + 字符串 ==
  ├── 模糊匹配: TextSimilarity.weightedLevenshtein + 自适应阈值
  │   → OCR 易混字符（カ/力、0/O、rn/m 等）低代价
  └── 都没命中 → translateBubbles（调翻译 API）
```

### 生命周期
- 自动翻译**开始/停止**时 `clear()`
- 翻译过程中持续加入
- 每页处理前 `evictExpiredRegions()`（TTL 5 分钟）
- 翻页保留（跨页复用相同原文气泡）

---

## 7. IMAGE 缓存（`findCacheExt`）

```kotlin
findCacheExt(extHashes[4], mode, cropW, cropH, sessionId)
```

### 精确匹配（快速路径 O(1)）

SQL 索引查询 4 段 hash + 面积比校验：

```sql
SELECT * FROM page_cache WHERE pHash=e0 AND pHash2=e1 AND pHash3=e2 AND pHash4=e3 AND mode=1
```

### 相似度匹配（降级路径 O(n)）

遍历全部缓存，计算 256 位汉明距离相似度，阈值 0.95（~13 bit 容差）：

```kotlin
SIMILARITY_THRESHOLD_MANGA = 0.95f
```

### 面积比校验

裁剪面积变化在 0.8~1.25 倍之间才认，防止框选不同区域误命中。

---

## 8. 缓存保存（`saveToCache`）

```
saveToCache(entry, originalBitmap?, createdAt)
  │
  ├── 1. 精确去重: findCacheByHashAndCropRect(pHash, cropRect)
  │   ├── 找到 → 删旧记录，继承 sessionId/createdAt
  │   └── 未找到 → findCacheByPHash(pHash) 尝试继承
  │
  ├── 2. 保存渲染图、原图到文件
  │
  ├── 3. 插入 HistoryEntity（4 段 hash、原文/译文、图片路径）
  │
  └── 4. 插入 PageCacheEntity → LRU 淘汰（>100 条）
```

### sessionId 继承优先级

```
① 精确去重找到的旧记录
② findCacheByPHash(pHash)
③ 都没有 → 全新 sessionId
```

---

## 9. pHash 体系

### 两种算法

| | `compute()` | `computeExtended()` |
|---|---|---|
| 分辨率 | 9×8 | 17×16 |
| 位数 | 64（1×Long） | 256（4×Long） |
| 用途 | 状态机翻页判断 | 缓存匹配 |
| 相似度分母 | 64 | 256 |

### 三个阈值

| 阈值 | 值 | 用于 | 含义 |
|------|----|------|------|
| `PHASH_STABLE_THRESHOLD` | 0.95（64 位） | 状态机判稳 | ~3 bit |
| `PHASH_NEW_PAGE_THRESHOLD` | 0.60（64 位） | 状态机判翻页 | ~25 bit |
| `SIMILARITY_THRESHOLD_MANGA` | 0.95（256 位） | 缓存相似度 | ~13 bit |

### 场景相似度（256 位）

| 场景 | 差异位 | 相似度 |
|------|--------|--------|
| 同截图方式同页连续截图 | 0-3 | 0.988~1.0 |
| 不同截图方式（MP vs 无障碍）同页 | 1-15 | 0.94~0.996 |
| 同页框选偏移 | 5-20 | 0.92~0.98 |
| 分格相似的漫画不同页 | 40-80 | 0.69~0.84 |
| 分格不同的漫画不同页 | 80-150 | 0.41~0.69 |

---

## 10. 状态机与缓存的连接

```
检测截图（有球）→ 64 位 pHash → 状态机 STABLE
  → processMangaScreenshot(干净图, 检测pHash, 干净extHashes)
    ├── lastTranslatedHash = 检测pHashes（有球，64 位）→ 下次检测稳定
    └── 缓存存入 = 干净extHashes（无球，256 位）→ 下次缓存命中
```

状态机和缓存使用不同 hash 算法和不同目的，互相独立。

---

## 11. 审查检查清单

- [ ] `findCacheExt` 精确匹配 → 相似度匹配顺序正确
- [ ] `saveToCache` dedup 用 `findCacheByHashAndCropRect`（精确 pHash + crop rect）
- [ ] `currentExtHashes` 正确设置（precomputed → computeExtended）
- [ ] MP 重截：隐藏球 → delay(50) → takeScreenshot → 恢复球
- [ ] 无障碍重截：delay(300) → 隐藏球 → delay(50) → takeScreenshot → 设 flag → 恢复球
- [ ] `pendingCleanScreenshot` 拦截在 try-catch 内
- [ ] `lastTranslatedHash` 用检测截图 64 位 hash（有球）
- [ ] `incrementalTranslateBubbles` 同时服务自动和手动模式
- [ ] `translatedRegions` 跨页不清空（仅 start/stop auto 时 clear）
- [ ] 文本缓存精确匹配双重验证：hashCode + 字符串 ==
- [ ] 不引入死代码
