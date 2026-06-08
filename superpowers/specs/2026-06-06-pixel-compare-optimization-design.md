# 游戏翻译轮询优化：区域像素比较

## 背景

游戏自动翻译每 500ms 截图 + OCR，即使画面没变也会执行完整流程。当前用 dHash 感知哈希比较全图，但文本变化占全图比例不到 40%，灵敏度不够。

方案：移植 pixelmatch 的**逐像素比较**算法到 Android，作为独立工具类。只比较选框裁剪后的区域，像素级比较 + YIQ 感知色彩差异 + 抗锯齿排除，能精确检测文本变化同时忽略背景微变。

## 设计

### 1. PixelCompare 独立接口

**文件**：`utils/PixelCompare.kt`

**定位**：通用像素比较工具，不耦合翻译逻辑。任何需要"检测画面是否变化"的场景都能复用。

```kotlin
/**
 * 逐像素图像比较工具（移植自 pixelmatch）
 *
 * 用途：
 * - 检测相邻帧画面是否变化
 * - 检测画面是否稳定（翻页完成后）
 * - 作为 OCR 前的轻量预筛
 */
object PixelCompare {

    /**
     * 比较两个 Bitmap 的像素差异
     * @param prev 上一帧
     * @param curr 当前帧
     * @param threshold YIQ 颜色差异阈值 (0~1)，默认 0.1（同 pixelmatch 默认值）
     * @return CompareResult
     */
    fun compare(prev: Bitmap, curr: Bitmap, threshold: Float = 0.1f): CompareResult
}

data class CompareResult(
    val diffPixels: Int,      // 差异像素数（排除抗锯齿后）
    val totalPixels: Int,     // 总像素数
    val diffRatio: Float,     // diffPixels / totalPixels (0.0~1.0)
    val isSimilar: Boolean    // diffRatio < SIMILAR_THRESHOLD (0.02)
)
```

**核心算法**（完整移植 pixelmatch）：

```
compare(prev, curr, threshold)
├── 快速路径：IntArray 32位比较，完全相同直接返回 0
├── 逐像素比较：
│   ├── colorDelta(r1,g1,b1,a1, r2,g2,b2,a2): Float
│   │   ├── alpha < 255 时混合白色背景
│   │   └── YIQ 感知差异: 0.5053*Y² + 0.299*I² + 0.1957*Q²
│   ├── |delta| > maxDelta (35215 * threshold²) → 候选差异像素
│   └── antialiased() 检测 → 排除抗锯齿像素
├── antialiased(img, x, y, width, height, a32, b32): Boolean
│   ├── 查 8 邻域 brightnessDelta
│   ├── 有明有暗 + zeroes ≤ 2
│   └── hasManySiblings: 极端邻居有 3+ 相同色
└── 返回 CompareResult
```

**Android 适配**：
- `Bitmap.getPixels()` → `IntArray`（ARGB），直接用 `IntArray[i]` 做 32 位快速比较
- 通道提取：`(pixel shr 16) and 0xFF` (R), `(pixel shr 8) and 0xFF` (G), `pixel and 0xFF` (B), `(pixel shr 24) and 0xFF` (A)

### 2. 集成到自动翻译流程

**修改 `AutoTranslateEngine.kt`**：

新增字段：
- `lastBitmap: Bitmap?` — 上一帧裁剪图（用于像素比较）
- `lastDiffRatio: Float = 0f` — 上次差异比例（传给 debug 显示）

`processScreenshot()` 入口新增逻辑：
```
if (lastBitmap != null && !isManualForceTranslate) {
    val result = PixelCompare.compare(lastBitmap, bitmap)
    lastDiffRatio = result.diffRatio
    if (result.isSimilar) {
        return Decision.PixelSkip(result.diffRatio)  // 像素未变，跳过 OCR
    }
}
lastBitmap = bitmap.copy(ARGB_8888, true)
// 继续原有 OCR + 文字比较流程
```

手动翻译（`isManualForceTranslate`）跳过像素比较，直接 OCR。

**修改 `Decision` 密封类**：
```kotlin
sealed class Decision {
    /** 像素未变，跳过（不执行 OCR） */
    data class PixelSkip(val diffRatio: Float) : Decision()
    /** OCR 后文字不同或空，跳过 */
    object TextSkip : Decision()
    /** 命中缓存 */
    data class CacheHit(val cachedText: String, val source: String) : Decision()
    /** 需要翻译 */
    data class Translate(val ocrText: String, val similarity: Float) : Decision()
}
```

**修改 `FloatingBallService.kt`**：

`processScreenshot()` 中 Decision 处理新增 `PixelSkip` 分支：
```kotlin
is AutoTranslateEngine.Decision.PixelSkip -> {
    updateDebugStatus("【跳过·画面未变】${"%.1f%%".format(decision.diffRatio * 100)}")
    isTranslating.set(false)
    scheduleNextDetection(DETECT_INTERVAL_MS)
}
```

debug overlay 增加 diffRatio 显示。

### 3. 阈值参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `threshold` | 0.1f | YIQ 颜色差异阈值（pixelmatch 默认值） |
| `SIMILAR_THRESHOLD` | 0.02f | diffRatio < 2% 认为画面没变 |

## 文件清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `utils/PixelCompare.kt` | 新建 | pixelmatch 核心算法移植 |
| `translate/AutoTranslateEngine.kt` | 修改 | 加入像素比较预筛、Decision 扩展 |
| `translate/FloatingBallService.kt` | 修改 | 处理 PixelSkip、debug 显示 |

## 验证

1. `./gradlew assembleDebug` 编译通过
2. 安装到设备，开启游戏翻译调试模式
3. 测试场景：
   - 画面静止 → debug 显示【跳过·画面未变】，不触发 OCR
   - 画面翻页 → diffRatio 高，等稳定后触发翻译
   - 背景有轻微动画（光影/粒子） → diffRatio < 2%，不误触发
   - 手动点击翻译 → 跳过像素比较，直接 OCR
   - 调试模式 → overlay 实时显示像素差异百分比
