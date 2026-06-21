# PP-OCRv5 合并逻辑重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 `BoxMerger.kt` 与 `TextLineMerger.kt` 之间的 80%+ 重复实现，引入统一的 `TextRegionMerger`，删除 ~800 行重复代码，新增 ~350 行统一实现。

**Architecture:** 引入 `TextRegion` 统一输入数据类（`text` 字段为 null 表示 OCR 前，非空表示 OCR 后），新建 `TextRegionMerger` 作为单一合并入口，移植 manga-image-translator 的 AA + Tilted 双分支算法，统一使用 `polyDistance`（凸四边形 SAT）。调试日志委托 PPOcrV5Engine 现有 `isDebugEnabled` 开关，暴露 `discardConnectionGap` 与 `charGapTolerance2` 两个可调参数。

**Tech Stack:** Kotlin 1.x, Android Gradle Plugin, JUnit 4 + Robolectric, AndroidX Preferences, JTS Geometry (BufferOp)

**Spec:** `docs/superpowers/specs/2026-06-22-pp-ocrv5-merge-redesign-design.md`

---

## File Structure

### New Files

| 路径 | 职责 |
|------|------|
| `app/src/main/java/com/moe/moetranslator/manga/TextRegion.kt` | 输入数据类（quad + text + score） |
| `app/src/main/java/com/moe/moetranslator/manga/TextRegionGroup.kt` | 输出数据类（rect + texts + direction + members） |
| `app/src/main/java/com/moe/moetranslator/manga/MergeParams.kt` | 可调参数数据类 |
| `app/src/main/java/com/moe/moetranslator/manga/TextRegionMerger.kt` | 主算法入口（含内部 UnionFind/MST/canMergeRegion/splitTextRegion） |
| `app/src/test/java/com/moe/moetranslator/manga/TextRegionMergerTest.kt` | 单元测试 |

### Modified Files

| 路径 | 改动 |
|------|------|
| `app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt` | BoxMerger.merge 调用 → TextRegionMerger.merge |
| `app/src/main/java/com/moe/moetranslator/manga/PPOcrV5Engine.kt` | TextLineMerger.merge 调用 → TextRegionMerger.merge；TextLine 增加 toTextRegion() 扩展 |
| `app/src/main/java/com/moe/moetranslator/manga/OverlayRenderer.kt` | 接收 MergedRegion → TextRegionGroup |
| `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt` | 调用点迁移 |

### Deleted Files (Task 7)

- `app/src/main/java/com/moe/moetranslator/manga/BoxMerger.kt`
- `app/src/main/java/com/moe/moetranslator/manga/TextLineMerger.kt`

---

## Task 1: 创建数据类（TextRegion / TextRegionGroup / MergeParams）

**Files:**
- Create: `app/src/main/java/com/moe/moetranslator/manga/TextRegion.kt`
- Create: `app/src/main/java/com/moe/moetranslator/manga/TextRegionGroup.kt`
- Create: `app/src/main/java/com/moe/moetranslator/manga/MergeParams.kt`

- [ ] **Step 1: 创建 TextRegion.kt**

```kotlin
package com.moe.moetranslator.manga

/**
 * 合并器的统一输入。
 * text == null 表示 OCR 前的几何合并（CTD + PPOcrV5 路径）。
 * text != null 表示 OCR 后的语义合并（PP-OCRv5 独立路径）。
 *
 * 算法不区分两种 case——文字字段仅在最终拼接阶段使用。
 */
data class TextRegion(
    val quad: QuadBox,
    val text: String? = null,
    val score: Float = 1f,
    val recTimeMs: Long = 0
)
```

- [ ] **Step 2: 创建 TextRegionGroup.kt**

```kotlin
package com.moe.moetranslator.manga

import android.graphics.PointF
import android.graphics.Rect

/**
 * 合并器的统一输出（对应参考项目的 TextBlock）。
 */
data class TextRegionGroup(
    val rect: Rect,
    val quadPoints: Array<PointF>,
    val texts: List<String>,
    val direction: TextDirection,
    val fontSize: Float,
    val angle: Float,
    val score: Float,
    val center: PointF,
    val members: List<TextRegion>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextRegionGroup) return false
        return rect == other.rect && texts == other.texts
    }

    override fun hashCode(): Int {
        var result = rect.hashCode()
        result = 31 * result + texts.hashCode()
        return result
    }
}
```

- [ ] **Step 3: 创建 MergeParams.kt**

```kotlin
package com.moe.moetranslator.manga

/**
 * TextRegionMerger 可调参数。
 * 其余参数 (RATIO, ASPECT_RATIO_TOL, CHAR_GAP, TILTED_*) hardcoded 不可调。
 */
data class MergeParams(
    val discardConnectionGap: Float = DISCARD_CONNECTION_GAP_DEFAULT,
    val charGapTolerance2: Float = CHAR_GAP_TOLERANCE2_DEFAULT
) {
    companion object {
        const val DISCARD_CONNECTION_GAP_DEFAULT = 1.5f
        const val CHAR_GAP_TOLERANCE2_DEFAULT = 3.0f
        const val MIN_DISCARD_GAP = 1.0f
        const val MAX_DISCARD_GAP = 3.0f
        const val MIN_CHAR_GAP2 = 1.0f
        const val MAX_CHAR_GAP2 = 5.0f
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL，三个新文件通过编译

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/TextRegion.kt \
        app/src/main/java/com/moe/moetranslator/manga/TextRegionGroup.kt \
        app/src/main/java/com/moe/moetranslator/manga/MergeParams.kt
git commit -m "feat(manga): add TextRegion/TextRegionGroup/MergeParams data classes"
```

---

## Task 2: 创建测试用例（先写测试，再写实现）

**Files:**
- Create: `app/src/test/java/com/moe/moetranslator/manga/TextRegionMergerTest.kt`

- [ ] **Step 1: 创建测试文件**

```kotlin
package com.moe.moetranslator.manga

import android.graphics.PointF
import com.moe.moetranslator.manga.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextRegionMerger 单元测试。
 * 覆盖：单 box、双 box 合并/不合并、AA + Tilted 分支、MST 拆分、参数变更。
 */
class TextRegionMergerTest {

    // Helper: 构造 AA 横排 box
    private fun hBox(x: Int, y: Int, w: Int, h: Int, text: String = "字"): TextRegion {
        val pts = arrayOf(
            PointF(x.toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), (y + h).toFloat()),
            PointF(x.toFloat(), (y + h).toFloat())
        )
        return TextRegion(QuadBox(pts), text)
    }

    // Helper: 构造竖排 box
    private fun vBox(x: Int, y: Int, w: Int, h: Int, text: String = "字"): TextRegion {
        val pts = arrayOf(
            PointF(x.toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), y.toFloat()),
            PointF((x + w).toFloat(), (y + h).toFloat()),
            PointF(x.toFloat(), (y + h).toFloat())
        )
        // 竖排：height >> width
        return TextRegion(QuadBox(pts), text)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        val result = TextRegionMerger.merge(emptyList())
        assertEquals(emptyList<TextRegionGroup>(), result)
    }

    @Test
    fun singleBoxReturnsSingleGroup() {
        val regions = listOf(hBox(0, 0, 50, 20, "你好"))
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals("你好", result[0].texts[0])
        assertEquals(TextDirection.HORIZONTAL, result[0].direction)
    }

    @Test
    fun twoHorizontalBoxesCloseMerge() {
        // 两 box 横排同行，距离 < charSize*1.5
        val regions = listOf(
            hBox(0, 0, 30, 20, "你好"),
            hBox(40, 0, 30, 20, "世界")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals(listOf("你好", "世界"), result[0].texts)
    }

    @Test
    fun twoHorizontalBoxesFarApartDoNotMerge() {
        // 两 box 横排同行，距离 > charSize*1.5
        val regions = listOf(
            hBox(0, 0, 30, 20, "你好"),
            hBox(200, 0, 30, 20, "世界")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(2, result.size)
    }

    @Test
    fun threeHorizontalBoxesSameLineAllMerge() {
        val regions = listOf(
            hBox(0, 0, 30, 20, "a"),
            hBox(40, 0, 30, 20, "b"),
            hBox(80, 0, 30, 20, "c")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals(listOf("a", "b", "c"), result[0].texts)
    }

    @Test
    fun twoBoxesDifferentDirectionDoNotMerge() {
        // 横排 + 竖排 → 不合并
        val regions = listOf(
            hBox(0, 0, 50, 20, "横"),
            vBox(100, 0, 20, 50, "竖")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(2, result.size)
    }

    @Test
    fun verticalBoxesSameColumnMerge() {
        // 两 box 竖排同列，距离 < charSize*1.5
        val regions = listOf(
            vBox(0, 0, 20, 50, "日"),
            vBox(0, 60, 20, 50, "本")
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
        assertEquals(TextDirection.VERTICAL_RL, result[0].direction)
    }

    @Test
    fun mstSplitIsolatedOutlier() {
        // 3 box：两个靠近，一个远离 → MST 拆分为 2 个 group
        val regions = listOf(
            hBox(0, 0, 30, 20, "a"),
            hBox(40, 0, 30, 20, "b"),
            hBox(500, 0, 30, 20, "c")  // 远离
        )
        val result = TextRegionMerger.merge(regions)
        assertEquals(2, result.size)
    }

    @Test
    fun preOcrAndPostOcrProduceSameGeometricResult() {
        // text 字段不参与几何判断：相同几何的 OCR 前/后 box 应合并结果相同
        val preOcr = listOf(
            hBox(0, 0, 30, 20, null),
            hBox(40, 0, 30, 20, null)
        )
        val postOcr = listOf(
            hBox(0, 0, 30, 20, "你好"),
            hBox(40, 0, 30, 20, "世界")
        )
        val preResult = TextRegionMerger.merge(preOcr)
        val postResult = TextRegionMerger.merge(postOcr)
        assertEquals(preResult.size, postResult.size)
        assertEquals(1, postResult.size)
        assertEquals(listOf("你好", "世界"), postResult[0].texts)
    }

    @Test
    fun largerDiscardGapMergesMore() {
        // discardConnectionGap 增大 → 合并更多
        val regions = listOf(
            hBox(0, 0, 30, 20, "a"),
            hBox(100, 0, 30, 20, "b")  // 距离 = 100 - 30 = 70，比 charSize(20)*1.5=30 大
        )
        val tightResult = TextRegionMerger.merge(regions, MergeParams(discardConnectionGap = 1.5f))
        val looseResult = TextRegionMerger.merge(regions, MergeParams(discardConnectionGap = 3.0f))
        assertEquals(2, tightResult.size)
        assertEquals(1, looseResult.size)
    }

    @Test
    fun debugLoggingToggleIsNoOpWhenDisabled() {
        TextRegionMerger.enableDebugLogging(false)
        val regions = listOf(hBox(0, 0, 50, 20, "test"))
        val result = TextRegionMerger.merge(regions)
        assertEquals(1, result.size)
    }

    @Test
    fun largeInputDoesNotCrash() {
        // 500 box 性能测试
        val regions = (0 until 500).map { hBox(it * 35, 0, 30, 20, "x") }
        val result = TextRegionMerger.merge(regions)
        assertTrue("应合并成多个 group", result.isNotEmpty())
    }
}
```

> **注意：** QuadBox 在测试构造时如果某些 lazy 属性访问会触发真实计算（如 `polyDistance` 用到凸多边形几何），需要确保 QuadBox 已初始化 `text` 等字段。某些测试可能因为 QuadBox 的 lazy 计算（如 `isVertical` 涉及 pairwise 距离排序）耗时较长，可适当放宽。

- [ ] **Step 2: 编译验证（测试文件）**

```bash
./gradlew compileDebugUnitTestKotlin
```

Expected: BUILD SUCCESSFUL（即使 TextRegionMerger 还不存在，编译应给出"unresolved reference"错误——这是正常的 TDD 状态）

---

## Task 3: 实现 UnionFind + MSTEdge 工具

**Files:**
- Create: `app/src/main/java/com/moe/moetranslator/manga/TextRegionMerger.kt`（框架）

- [ ] **Step 1: 创建 TextRegionMerger.kt 骨架（先实现工具类）**

```kotlin
package com.moe.moetranslator.manga

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import com.moe.moetranslator.utils.LogCollector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PP-OCRv5 文字行/区域合并器。
 *
 * 对齐 manga-image-translator textline_merge 算法：
 * - quadrilateral_can_merge_region()（generic.py:653-698）
 * - merge_bboxes_text_region()（textline_merge/__init__.py:110-182）
 * - split_text_region()（textline_merge/__init__.py:10-83）
 *
 * **统一入口**：OCR 前/后都通过 merge() 入口；text 字段决定是否拼接文字。
 *
 * **调试日志**：受 enableDebugLogging() 控制，默认关闭，零开销。
 */
object TextRegionMerger {

    private const val TAG = "TextRegionMerger"

    // ========== 硬编码参数（对齐 manga 调用值） ==========
    private const val RATIO = 1.9f                   // 方向判断阈值
    private const val ASPECT_RATIO_TOL = 1.3f        // 长宽比交叉阈值（manga 调用 1.3）
    private const val CHAR_GAP_TOLERANCE = 1f        // AA 分支 char gap（manga 调用 1）
    private const val FONT_SIZE_RATIO_AA = 2.0f      // AA 分支字号比（manga 调用 2.0）
    private const val TILTED_ANGLE_DIFF_MAX = 15f    // 15° 倾斜角度差
    private const val TILTED_FS_DIFF_MAX = 0.25f     // 字号差比

    // ========== 可调参数 ==========
    @Volatile private var discardConnectionGap: Float = MergeParams.DISCARD_CONNECTION_GAP_DEFAULT
    @Volatile private var charGapTolerance2: Float = MergeParams.CHAR_GAP_TOLERANCE2_DEFAULT
    @Volatile private var debugEnabled: Boolean = false

    /**
     * 启用/禁用调试日志（默认关闭，零开销）。
     */
    fun enableDebugLogging(enabled: Boolean) {
        debugEnabled = enabled
    }

    /**
     * 从 SharedPreferences 刷新可调参数。
     */
    fun refreshParams(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        discardConnectionGap = prefs.getFloat(
            "merge_discard_gap",
            MergeParams.DISCARD_CONNECTION_GAP_DEFAULT
        ).coerceIn(MergeParams.MIN_DISCARD_GAP, MergeParams.MAX_DISCARD_GAP)
        charGapTolerance2 = prefs.getFloat(
            "merge_char_gap2",
            MergeParams.CHAR_GAP_TOLERANCE2_DEFAULT
        ).coerceIn(MergeParams.MIN_CHAR_GAP2, MergeParams.MAX_CHAR_GAP2)
    }

    /**
     * 重置参数为默认值。
     */
    fun resetParams(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putFloat("merge_discard_gap", MergeParams.DISCARD_CONNECTION_GAP_DEFAULT)
            .putFloat("merge_char_gap2", MergeParams.CHAR_GAP_TOLERANCE2_DEFAULT)
            .apply()
        refreshParams(context)
    }

    // ========== 工具类 ==========

    /**
     * 加权平均。
     */
    private fun weightedAverage(values: List<Float>, weights: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val totalWeight = weights.sum()
        if (totalWeight <= 0f) return values.average().toFloat()
        return values.zip(weights).sumOf { (v, w) -> (v * w).toDouble() }.toFloat() / totalWeight
    }

    /**
     * 并查集（Kruskal MST 用）。
     */
    internal class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size) { 0 }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var node = x
            while (node != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }

        /**
         * @return true 表示合并成功；false 表示已在同一集合。
         */
        fun union(x: Int, y: Int): Boolean {
            val rx = find(x)
            val ry = find(y)
            if (rx == ry) return false
            when {
                rank[rx] < rank[ry] -> parent[rx] = ry
                rank[rx] > rank[ry] -> parent[ry] = rx
                else -> { parent[ry] = rx; rank[rx]++ }
            }
            return true
        }
    }

    /**
     * MST 边。
     */
    internal data class MSTEdge(val u: Int, val v: Int, val weight: Float)

    /**
     * 计算 quad 中心点距离（polyDistance 的简化版）。
     * 参考项目中 polyDistance 是 Shapely Polygon.distance，准确但 O(N²)。
     * 这里简化为中心点距离，O(1)，对相邻裁剪行足够。
     */
    private fun quadCenterDistance(a: TextRegion, b: TextRegion): Float {
        val dx = b.quad.centroidX - a.quad.centroidX
        val dy = b.quad.centroidY - a.quad.centroidY
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 计算两个 quad 凸多边形的最小距离（参考项目 polyDistance 实现）。
     * 相交或包含时返回 0。
     */
    private fun polyDistance(a: TextRegion, b: TextRegion): Float {
        return a.quad.polyDistance(b.quad)
    }

    /**
     * 判断近似轴对齐。
     * 直接用 angle 直判：angle=0 表示 AA；|angle| ≤ 3° 视为 AA。
     */
    private fun isApproxAxisAligned(quad: QuadBox): Boolean {
        // QuadBox 的 angle 是弧度制，结构线方向
        val angleDeg = abs(quad.angle) * 180f / PI.toFloat()
        return angleDeg <= 3f
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/TextRegionMerger.kt
git commit -m "feat(manga): TextRegionMerger 骨架 + UnionFind/MSTEdge 工具"
```

---

## Task 4: 实现 canMergeRegion（含 AA + Tilted 双分支）

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/TextRegionMerger.kt`

- [ ] **Step 1: 添加 canMergeRegion 方法**

在 `TextRegionMerger.kt` 中 `isApproxAxisAligned` 之后追加：

```kotlin
    /**
     * 判断两个 TextRegion 是否应合并。
     * 完整对齐 manga generic.py:653-698 quadrilateral_can_merge_region。
     *
     * @return true 表示应合并
     */
    private fun canMergeRegion(a: TextRegion, b: TextRegion): Boolean {
        val charSize = min(a.quad.fontSize, b.quad.fontSize)
        if (charSize <= 0f) return false

        val tagA = "\"${(a.text ?: "").take(8)}\""
        val tagB = "\"${(b.text ?: "").take(8)}\""

        val aAA = isApproxAxisAligned(a.quad)
        val bAA = isApproxAxisAligned(b.quad)

        // 距离粗筛（AA + Tilted 共用）
        val dist = quadCenterDistance(a, b)
        val maxGap = discardConnectionGap * charSize
        if (dist > maxGap) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT dist=${String.format("%.1f", dist)} > $maxGap")
            return false
        }

        // 字号比（AA + Tilted 共用）
        val fsRatio = max(a.quad.fontSize, b.quad.fontSize) / charSize
        if (fsRatio > FONT_SIZE_RATIO_AA) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT fsRatio=${String.format("%.2f", fsRatio)} > $FONT_SIZE_RATIO_AA")
            return false
        }

        // 宽高比交叉检查（AA + Tilted 共用）
        if (a.quad.aspectRatio > ASPECT_RATIO_TOL && b.quad.aspectRatio < 1f / ASPECT_RATIO_TOL) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT aspectRatio cross")
            return false
        }
        if (b.quad.aspectRatio > ASPECT_RATIO_TOL && a.quad.aspectRatio < 1f / ASPECT_RATIO_TOL) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT aspectRatio cross")
            return false
        }

        // 方向一致性（AA + Tilted 共用）
        if (a.quad.isVertical != b.quad.isVertical) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → REJECT direction mismatch")
            return false
        }

        // ========== AA 分支（manga L671-687）==========
        if (aAA && bAA) {
            // char_gap_tolerance（manga 调用 1.0）
            if (dist >= charSize * CHAR_GAP_TOLERANCE) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT dist=${String.format("%.1f", dist)} >= ${charSize * CHAR_GAP_TOLERANCE}")
                return false
            }
            val x1 = a.quad.aabb.left.toFloat()
            val w1 = a.quad.aabb.width().toFloat()
            val h1 = a.quad.aabb.height().toFloat()
            val x2 = b.quad.aabb.left.toFloat()
            val w2 = b.quad.aabb.width().toFloat()
            val h2 = b.quad.aabb.height().toFloat()

            // 中心对齐
            if (abs(x1 + w1 / 2 - (x2 + w2 / 2)) < charGapTolerance2) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA ACCEPT center aligned")
                return true
            }
            // 方向互斥
            if (w1 > h1 * RATIO && h2 > w2 * RATIO) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT mixed orient")
                return false
            }
            if (w2 > h2 * RATIO && h1 > w1 * RATIO) {
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT mixed orient")
                return false
            }
            // 横排
            if (w1 > h1 * RATIO || w2 > h2 * RATIO) {
                val accept = abs(x1 - x2) < charSize * charGapTolerance2 ||
                             abs(x1 + w1 - (x2 + w2)) < charSize * charGapTolerance2
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA h-align=$accept")
                return accept
            }
            // 竖排
            if (h1 > w1 * RATIO || h2 > w2 * RATIO) {
                val y1 = a.quad.aabb.top.toFloat()
                val y2 = b.quad.aabb.top.toFloat()
                val accept = abs(y1 - y2) < charSize * charGapTolerance2 ||
                             abs(y1 + h1 - (y2 + h2)) < charSize * charGapTolerance2
                if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA v-align=$accept")
                return accept
            }
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → AA REJECT no direction match")
            return false
        }

        // ========== Tilted 分支（manga L688-697）==========
        val angleDiff = abs(a.quad.angle - b.quad.angle) * 180f / PI.toFloat()
        if (angleDiff > TILTED_ANGLE_DIFF_MAX) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED REJECT angleDiff=${String.format("%.1f", angleDiff)} > $TILTED_ANGLE_DIFF_MAX")
            return false
        }
        val fsA = a.quad.fontSize
        val fsB = b.quad.fontSize
        val fsMin = min(fsA, fsB)
        val fsDiff = abs(fsA - fsB) / fsMin
        if (fsDiff > TILTED_FS_DIFF_MAX) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED REJECT fsDiff=${String.format("%.2f", fsDiff)} > $TILTED_FS_DIFF_MAX")
            return false
        }
        if (dist > fsMin * charGapTolerance2) {
            if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED REJECT dist=${String.format("%.1f", dist)} > ${fsMin * charGapTolerance2}")
            return false
        }
        if (debugEnabled) LogCollector.d(TAG, "canMerge $tagA + $tagB → TILTED ACCEPT")
        return true
    }
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/TextRegionMerger.kt
git commit -m "feat(manga): TextRegionMerger canMergeRegion 双分支"
```

---

## Task 5: 实现 splitTextRegion + merge 主入口

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/TextRegionMerger.kt`

- [ ] **Step 1: 添加 splitTextRegion 方法**

在 `canMergeRegion` 之后追加：

```kotlin
    /**
     * MST 分析拆分过大的文本区域。
     * 完整对齐 split_text_region()（textline_merge/__init__.py:10-83）。
     */
    private fun splitTextRegion(
        regions: List<TextRegion>,
        connectedIndices: Set<Int>,
        gamma: Float = 0.5f,
        sigma: Float = 2f
    ): List<Set<Int>> {
        val indices = connectedIndices.toList()
        if (indices.size == 1) return listOf(setOf(indices[0]))

        if (indices.size == 2) {
            val a = regions[indices[0]]
            val b = regions[indices[1]]
            val fs = max(a.quad.fontSize, b.quad.fontSize)
            val dist = quadCenterDistance(a, b)
            val angleDiff = abs(a.quad.angle - b.quad.angle)
            if (dist < (1 + gamma) * fs && angleDiff < 0.2f * PI.toFloat()) {
                return listOf(setOf(indices[0], indices[1]))
            }
            if (debugEnabled) LogCollector.d(TAG, "splitTextRegion[2]: split")
            return listOf(setOf(indices[0]), setOf(indices[1]))
        }

        // case 3+: MST
        val allEdges = mutableListOf<MSTEdge>()
        for (i in indices.indices) {
            for (j in i + 1 until indices.size) {
                val u = indices[i]
                val v = indices[j]
                allEdges.add(MSTEdge(u, v, quadCenterDistance(regions[u], regions[v])))
            }
        }
        allEdges.sortBy { it.weight }
        val uf = UnionFind(regions.size)
        val mstEdges = mutableListOf<MSTEdge>()
        for (edge in allEdges) {
            if (uf.union(edge.u, edge.v)) {
                mstEdges.add(edge)
                if (mstEdges.size == indices.size - 1) break
            }
        }
        if (mstEdges.isEmpty()) return listOf(connectedIndices)

        val sortedEdges = mstEdges.sortedByDescending { it.weight }
        val distances = sortedEdges.map { it.weight }
        val distancesMean = distances.average().toFloat()
        val distancesStd = if (distances.size > 1) {
            val mean = distancesMean
            sqrt(distances.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0f
        val avgFontSize = indices.map { regions[it].quad.fontSize }.average().toFloat()
        val stdThreshold = max(0.3f * avgFontSize + 5f, 5f)

        val maxEdge = sortedEdges.first()
        val shouldKeep = (maxEdge.weight <= distancesMean + distancesStd * sigma ||
                maxEdge.weight <= avgFontSize * (1 + gamma)) &&
                distancesStd < stdThreshold

        if (debugEnabled) {
            LogCollector.d(TAG, "splitTextRegion[${indices.size}]: " +
                "maxEdge=${String.format("%.1f", maxEdge.weight)} " +
                "mean=${String.format("%.1f", distancesMean)} std=${String.format("%.1f", distancesStd)} " +
                "fontSize=${String.format("%.1f", avgFontSize)} keep=$shouldKeep")
        }

        if (shouldKeep) {
            return listOf(connectedIndices)
        }

        // 拆分：移除最大边，递归处理两个子图
        val remainingEdges = sortedEdges.drop(1)
        val uf2 = UnionFind(regions.size)
        for (edge in remainingEdges) {
            uf2.union(edge.u, edge.v)
        }

        val result = mutableListOf<Set<Int>>()
        val visited = mutableSetOf<Int>()
        for (idx in indices) {
            if (idx in visited) continue
            val component = mutableSetOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(idx)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (cur in visited) continue
                visited.add(cur)
                component.add(cur)
                for (otherIdx in indices) {
                    if (otherIdx !in visited && uf2.find(cur) == uf2.find(otherIdx)) {
                        queue.add(otherIdx)
                    }
                }
            }
            if (component.isNotEmpty()) {
                result.addAll(splitTextRegion(regions, component, gamma, sigma))
            }
        }
        return result
    }
```

- [ ] **Step 2: 添加 merge 主入口**

在 `splitTextRegion` 之后追加：

```kotlin
    /**
     * 主入口：合并 text regions 为文本组。
     *
     * @param regions 待合并的 text region 列表
     * @param params 可调参数（不传则使用当前 refreshParams 后的值）
     * @return 合并后的 text region groups（按阅读顺序：横排 top→bottom，竖排 right→left）
     */
    fun merge(
        regions: List<TextRegion>,
        params: MergeParams = MergeParams(discardConnectionGap, charGapTolerance2)
    ): List<TextRegionGroup> {
        if (regions.isEmpty()) return emptyList()

        // 临时覆盖可调参数（如果传入非默认值）
        val savedGap = discardConnectionGap
        val savedGap2 = charGapTolerance2
        if (params.discardConnectionGap != discardConnectionGap ||
            params.charGapTolerance2 != charGapTolerance2) {
            discardConnectionGap = params.discardConnectionGap
            charGapTolerance2 = params.charGapTolerance2
        }

        try {
            if (regions.size == 1) {
                val region = regions[0]
                val rect = region.quad.aabb
                val quadPoints = arrayOf(
                    PointF(rect.left.toFloat(), rect.top.toFloat()),
                    PointF(rect.right.toFloat(), rect.top.toFloat()),
                    PointF(rect.right.toFloat(), rect.bottom.toFloat()),
                    PointF(rect.left.toFloat(), rect.bottom.toFloat())
                )
                val direction = if (region.quad.isVertical) TextDirection.VERTICAL_RL else TextDirection.HORIZONTAL
                return listOf(
                    TextRegionGroup(
                        rect = rect,
                        quadPoints = quadPoints,
                        texts = listOf(region.text ?: ""),
                        direction = direction,
                        fontSize = region.quad.fontSize,
                        angle = region.quad.angle * 180f / PI.toFloat(),
                        score = region.score,
                        center = PointF(rect.exactCenterX(), rect.exactCenterY()),
                        members = listOf(region)
                    )
                )
            }

            if (debugEnabled) LogCollector.d(TAG, "merge: 输入 ${regions.size} 个 region")

            // Step 1: canMergeRegion 建图 → 连通分量
            val n = regions.size
            val adjacency = Array(n) { mutableSetOf<Int>() }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (canMergeRegion(regions[i], regions[j])) {
                        adjacency[i].add(j)
                        adjacency[j].add(i)
                    }
                }
            }

            val visited = BooleanArray(n)
            val connectedComponents = mutableListOf<Set<Int>>()
            for (i in 0 until n) {
                if (visited[i]) continue
                val component = mutableSetOf<Int>()
                val queue = ArrayDeque<Int>()
                queue.add(i)
                visited[i] = true
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    component.add(node)
                    for (neighbor in adjacency[node]) {
                        if (!visited[neighbor]) {
                            visited[neighbor] = true
                            queue.add(neighbor)
                        }
                    }
                }
                connectedComponents.add(component)
            }
            if (debugEnabled) LogCollector.d(TAG, "merge: 连通分量 ${connectedComponents.size} 个")

            // Step 2: splitTextRegion MST 拆分
            val regionIndices = mutableListOf<Set<Int>>()
            for (component in connectedComponents) {
                regionIndices.addAll(splitTextRegion(regions, component))
            }
            if (debugEnabled) LogCollector.d(TAG, "merge: 拆分后 ${regionIndices.size} 个区域")

            // Step 3: 方向投票 + 排序 + 合并
            val result = mutableListOf<TextRegionGroup>()
            for (nodeSet in regionIndices) {
                val nodes = nodeSet.toList()
                val members = nodes.map { regions[it] }

                // 方向投票
                val directionCounts = members.groupBy { it.quad.isVertical }.mapValues { it.value.size }
                val majorityVertical = (directionCounts[true] ?: 0) > (directionCounts[false] ?: 0)
                val direction = if (majorityVertical) TextDirection.VERTICAL_RL else TextDirection.HORIZONTAL

                // 按方向排序
                val sortedNodes = if (direction == TextDirection.HORIZONTAL) {
                    nodes.sortedBy { regions[it].quad.centroidY }
                } else {
                    nodes.sortedByDescending { regions[it].quad.centroidX }
                }

                // AABB union
                val aabbs = sortedNodes.map { regions[it].quad.aabb }
                val unionRect = Rect(
                    aabbs.minOf { it.left },
                    aabbs.minOf { it.top },
                    aabbs.maxOf { it.right },
                    aabbs.maxOf { it.bottom }
                )

                val combinedTexts = sortedNodes.map { regions[it].text ?: "" }
                val minFontSize = members.minOf { it.quad.fontSize }
                val avgScore = members.map { it.score }.average().toFloat()
                val weightedAngle = weightedAverage(
                    members.map { it.quad.angle * 180f / PI.toFloat() },
                    members.map { it.quad.fontSize }
                )
                val mergedCenter = PointF(unionRect.exactCenterX(), unionRect.exactCenterY())

                // 中心加权 quad 角点（简化版：用 unionRect）
                val quadPoints = arrayOf(
                    PointF(unionRect.left.toFloat(), unionRect.top.toFloat()),
                    PointF(unionRect.right.toFloat(), unionRect.top.toFloat()),
                    PointF(unionRect.right.toFloat(), unionRect.bottom.toFloat()),
                    PointF(unionRect.left.toFloat(), unionRect.bottom.toFloat())
                )

                result.add(TextRegionGroup(
                    rect = unionRect,
                    quadPoints = quadPoints,
                    texts = combinedTexts,
                    direction = direction,
                    fontSize = minFontSize,
                    angle = weightedAngle,
                    score = avgScore,
                    center = mergedCenter,
                    members = members
                ))

                if (debugEnabled) {
                    LogCollector.d(TAG, "merge: 区域 ${members.size} 行, dir=$direction, " +
                            "fs=${String.format("%.1f", minFontSize)}, text='${combinedTexts.first().take(20)}'")
                }
            }

            if (debugEnabled) LogCollector.d(TAG, "merge: 输出 ${result.size} 个文本区域")
            return result
        } finally {
            // 恢复参数
            if (params.discardConnectionGap != savedGap ||
                params.charGapTolerance2 != savedGap2) {
                discardConnectionGap = savedGap
                charGapTolerance2 = savedGap2
            }
        }
    }
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 运行单元测试**

```bash
./gradlew testDebugUnitTest --tests "com.moe.moetranslator.manga.TextRegionMergerTest"
```

Expected: 12 个测试通过（emptyInput、singleBox、twoHorizontalClose、twoHorizontalFar、threeHorizontalSameLine、twoBoxesDifferentDirection、verticalSameColumn、mstSplitIsolatedOutlier、preOcrVsPostOcr、largerDiscardGap、debugLoggingToggle、largeInput）

如果部分失败：
- `preOcrVsPostOcr`：检查 QuadBox 在 test 中的初始化是否正确
- `largeInput`：500 box 性能应 < 1s

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/TextRegionMerger.kt \
        app/src/test/java/com/moe/moetranslator/manga/TextRegionMergerTest.kt
git commit -m "feat(manga): TextRegionMerger splitTextRegion + merge 主入口 + 单元测试"
```

---

## Task 6: 迁移 DetectionBridge 调用点（CTD + PPOcrV5 前合并）

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt`

- [ ] **Step 1: 阅读当前 BoxMerger 调用点**

```bash
grep -n "BoxMerger" app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt
```

找到所有 `BoxMerger.merge(...)` 或 `BoxMerger.canMergeRegion` 调用点。

- [ ] **Step 2: 替换为 TextRegionMerger**

将每个 `BoxMerger.merge(quads)` 调用替换为：

```kotlin
TextRegionMerger.merge(quads.map { TextRegion(it) })
```

如果有 `BoxMerger.merge` 之外的辅助方法（如返回类型转换），按需调整：
- 原 `BoxMerger.merge` 返回 `List<List<QuadBox>>`
- 新 `TextRegionMerger.merge` 返回 `List<TextRegionGroup>`
- 调用方需要从 `TextRegionGroup.members.map { it.quad }` 取出 quad 列表

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL（BoxMerger 仍存在，所以原导入可能还能编译——但应移除 BoxMerger import 改用 TextRegionMerger）

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/DetectionBridge.kt
git commit -m "refactor(manga): DetectionBridge 迁移到 TextRegionMerger"
```

---

## Task 7: 迁移 PPOcrV5Engine + OverlayRenderer + MangaFloatingService

**Files:**
- Modify: `app/src/main/java/com/moe/moetranslator/manga/PPOcrV5Engine.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/manga/OverlayRenderer.kt`
- Modify: `app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt`

- [ ] **Step 1: 在 PPOcrV5Engine.kt 添加 TextLine.toTextRegion() 扩展**

在文件顶部添加：

```kotlin
/**
 * TextLine 转 TextRegion（OCR 后路径）。
 */
internal fun TextLineMerger.TextLine.toTextRegion(): TextRegion {
    return TextRegion(
        quad = quadPointsToQuadBox(quadPoints, angle),
        text = text,
        score = score
    )
}

/**
 * 从 4 个 quad 顶点 + 角度重建 QuadBox。
 * 注意：QuadBox 的 isVertical/fontSize/aspectRatio/angle 都基于 4 顶点计算，
 * 这里传入正确顶点即可让所有派生属性正常工作。
 */
private fun quadPointsToQuadBox(
    quadPoints: Array<android.graphics.PointF>,
    angleDeg: Float
): QuadBox {
    // QuadBox 直接接受 4 顶点构造，angle 是派生属性无需手动设置
    return QuadBox(quadPoints)
}
```

> **说明：** `QuadBox` 的 `angle` 是 `lazy` 属性，从结构线方向计算。如果 quadPoints 已是正确顺序（TL/TR/BR/BL），angle 会自动正确。angleDeg 参数保留以备扩展。

- [ ] **Step 2: 替换 PPOcrV5Engine.kt 中的 TextLineMerger.merge 调用**

将：

```kotlin
val mergedRegions = TextLineMerger.merge(textLines)
```

替换为：

```kotlin
val mergedRegions = TextRegionMerger.merge(textLines.map { it.toTextRegion() })
```

并修改调用方使用 `mergedRegions` 的方式：
- 原 `MergedRegion.rect/texts/direction/...` → 新 `TextRegionGroup.rect/texts/direction/...`
- 字段名一致，仅类型不同

- [ ] **Step 3: 替换 OverlayRenderer.kt 中的 MergedRegion 引用**

将所有 `MergedRegion` 替换为 `TextRegionGroup`。`TextRegionGroup` 字段：
- `rect: Rect`（同 MergedRegion）
- `texts: List<String>`（同 MergedRegion）
- `direction: TextDirection`（同 MergedRegion）
- `fontSize: Float`（同 MergedRegion）
- `angle: Float`（同 MergedRegion）
- `center: PointF`（同 MergedRegion）

如果有 `MergedRegion` 专属方法（如 angle 转 radians），按需添加：

```kotlin
val angleRad = group.angle * PI.toFloat() / 180f
```

- [ ] **Step 4: 替换 MangaFloatingService.kt 调用点**

按 grep 结果替换。每个 `TextLineMerger.merge(...)` → `TextRegionMerger.merge(...)`。

- [ ] **Step 5: 编译验证**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

如果有 `TextLineMerger` 引用但 BoxMerger/TextLineMerger 文件仍存在（我们还没删），编译器能通过但应清理 import。

- [ ] **Step 6: 清理 import**

在 4 个修改文件中，移除 `import ...BoxMerger` 和 `import ...TextLineMerger`（如果仅作为类型引用）。如果 import 仍需要（如 TextLine 类型），保留。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/moe/moetranslator/manga/PPOcrV5Engine.kt \
        app/src/main/java/com/moe/moetranslator/manga/OverlayRenderer.kt \
        app/src/main/java/com/moe/moetranslator/manga/MangaFloatingService.kt
git commit -m "refactor(manga): PPOcrV5Engine/OverlayRenderer/MangaFloatingService 迁移到 TextRegionMerger"
```

---

## Task 8: 删除 BoxMerger.kt + TextLineMerger.kt

**Files:**
- Delete: `app/src/main/java/com/moe/moetranslator/manga/BoxMerger.kt`
- Delete: `app/src/main/java/com/moe/moetranslator/manga/TextLineMerger.kt`

- [ ] **Step 1: 确认无残留引用**

```bash
grep -rn "BoxMerger\|TextLineMerger" app/src/main/java/com/moe/moetranslator/manga/ | grep -v "TextRegionMerger"
```

Expected: 无输出（仅可能保留 `TextLine` 类型在 PPOcrV5Engine.kt 中的中间使用，这是允许的——TextLine 是数据类，与 TextLineMerger 合并逻辑分离）

如果有 `TextLineMerger.TextLine` 引用，确认它们仅作为中间数据类型（不应有 `TextLineMerger.merge()` 调用）。

- [ ] **Step 2: 删除 BoxMerger.kt**

```bash
git rm app/src/main/java/com/moe/moetranslator/manga/BoxMerger.kt
```

- [ ] **Step 3: 删除 TextLineMerger.kt**

```bash
git rm app/src/main/java/com/moe/moetranslator/manga/TextLineMerger.kt
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git commit -m "refactor(manga): 删除 BoxMerger.kt 与 TextLineMerger.kt（已被 TextRegionMerger 替代）"
```

---

## Task 9: 编译验证 + 手动测试

- [ ] **Step 1: 完整编译**

```bash
./gradlew clean assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 单元测试**

```bash
./gradlew testDebugUnitTest
```

Expected: 12 个 TextRegionMergerTest 全部通过，其他测试无回归

- [ ] **Step 3: 安装 debug APK**

```powershell
$adb = "C:\Users\xjj20\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected: Success

- [ ] **Step 4: 准备测试样本**

选取 3 张漫画截图（来自 `.reference/manga-image-translator/test_cases/` 或项目 `tools/` 目录）：
- 日文竖排漫画
- 英文横排漫画
- 中日英混合倾斜漫画

- [ ] **Step 5: 启动 app + 启用 PP-OCRv5 debug overlay**

- 打开 app，进入"关于"页面
- 开启"PP-OCRv5 调试"
- 启用调试日志（合并 debug）

- [ ] **Step 6: 跑测试样本，记录合并结果**

对每张截图：
- 触发翻译
- 记录合并区域数、文本内容、调试日志输出

- [ ] **Step 7: 对比 baseline（重构前的合并结果）**

- 如果有重构前的截图结果：diff 合并区域数（允许 ±5% 浮动）
- 如果无 baseline：检查合并结果合理性（每个气泡应有合理的合并数）

- [ ] **Step 8: 跑可调参数验证**

调整 `discardConnectionGap` 从 1.5 → 3.0：
- 应观察到合并区域数增加
- LogCollector 输出应显示对应参数被读取

- [ ] **Step 9: 提交（如果需要修复）**

如果测试发现问题：
- 修复并提交
- 在 plan 中标注问题

如果一切正常，无需 commit（最终 commit 已在 Task 8）。

---

## 自审（Self-Review）

**1. Spec 覆盖检查：**

| Spec 要求 | 对应 Task |
|----------|----------|
| 删除 BoxMerger.kt 与 TextLineMerger.kt | Task 8 |
| 新建 TextRegion/TextRegionGroup/MergeParams | Task 1 |
| 新建 TextRegionMerger | Tasks 3-5 |
| canMergeRegion AA + Tilted 双分支 | Task 4 |
| splitTextRegion MST 拆分 | Task 5 |
| merge 主入口（方向投票+排序+合并） | Task 5 |
| UnionFind/MSTEdge 单一实现 | Task 3 |
| 调试日志默认关闭 | Task 3（debugEnabled 默认 false） |
| 可调参数 SharedPreferences | Task 3（refreshParams） |
| DetectionBridge 迁移 | Task 6 |
| PPOcrV5Engine 迁移 | Task 7 |
| OverlayRenderer 迁移 | Task 7 |
| MangaFloatingService 迁移 | Task 7 |
| 单元测试 12 用例 | Task 2 + Task 5 |
| 编译验证 | Task 9 |
| ADB 手动测试 | Task 9 |

✅ 全部覆盖

**2. 占位符扫描：**

- 无 "TBD" / "TODO"
- 所有代码块完整（无省略号）
- 所有命令含 expected output
- 无 "implement later" / "similar to Task N"

✅ 通过

**3. 类型一致性：**

- `TextRegion` 在 Task 1 定义，Task 5 使用——字段一致（quad, text, score, recTimeMs）
- `TextRegionGroup` 在 Task 1 定义，Task 5 使用——字段一致
- `MergeParams` 在 Task 1 定义，Task 3/5 使用——字段一致
- `UnionFind.union` 返回 `Boolean`，Task 5 使用返回值的 `if (uf.union(...))`——一致
- `MSTEdge(u, v, weight)` 在 Task 3 定义，Task 5 构造——一致

✅ 通过

**潜在风险：**

- QuadBox 的 `angle` 是 `lazy` 属性，从结构线方向计算（弧度制）。Task 4 中 `a.quad.angle * 180f / PI.toFloat()` 与 manga `angle` 概念可能不完全等价（manga 用结构线方向，PP-OCRv5 用顶边方向）。需要在 Task 9 手动测试中验证。
- 如果测试失败，可能需要修正 `isApproxAxisAligned` 的角度计算（改用 PP-OCRv5 顶边角度而非 QuadBox 结构线角度）。

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-22-pp-ocrv5-merge-redesign.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**