# unclip 算法修复设计方案

## 问题

项目现有的 `utils/clipper/` 目录（ClipperOffset.kt、ClipperEngine.kt、ClipperUtils.kt）是一个**不完整的 Kotlin 移植版**，其 `_delta` 符号处理逻辑与 pyclipper 不一致。

**具体问题**：unclip 时 `_groupDelta` 符号可能反转，导致扩张变收缩，检测框严重缩小。

### 日志对比（修复前）

```
RAW:   AABB=Rect(375, 214 - 384, 259)  宽9, 高45
UNCLIP: AABB=Rect(368, 258 - 370, 260)  宽2, 高2  ❌ 不应该缩小！
```

## 解决方案

使用 **micycle1/Clipper2-Java** 替代现有实现：
- GitHub: https://github.com/micycle1/Clipper2-java
- 版本：2.0.1（JitPack）
- 完整的 Clipper2 移植版，与 pyclipper（C++/Cython）行为完全一致

## 实施步骤

### 1. 启用 JitPack 仓库

在 `settings.gradle` 中取消注释 JitPack：

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
```

### 2. 添加依赖

在 `app/build.gradle` 添加：

```groovy
implementation 'com.github.micycle1:Clipper2-Java:2.0.1'
```

### 3. 删除错误的 clipper 目录

删除以下文件：
- `utils/clipper/ClipperOffset.kt`
- `utils/clipper/ClipperEngine.kt`
- `utils/clipper/ClipperUtils.kt`
- `utils/clipper/Point64.kt` (如果存在)

### 4. 更新 CTDPostProcessor

修改 `unclipPolygon()` 方法，使用 Clipper2 的 API：

```kotlin
private fun unclipPolygon(contour: Path64, unclipRatio: Float): Path64 {
    if (contour.size < 3) return contour

    // 计算面积和周长
    var area = 0.0
    var perimeter = 0.0
    val n = contour.size
    for (i in 0 until n) {
        val j = (i + 1) % n
        area += contour[i].x.toDouble() * contour[j].y.toDouble()
        area -= contour[j].x.toDouble() * contour[i].y.toDouble()
        val dx = (contour[j].x - contour[i].x).toDouble()
        val dy = (contour[j].y - contour[i].y).toDouble()
        perimeter += sqrt(dx * dx + dy * dy)
    }
    area = abs(area) / 2.0

    if (perimeter < 1e-10 || area < 1e-10) return contour

    val distance = area * unclipRatio / perimeter

    // 使用 Clipper2（Pyclipper 行为一致）
    val offset = Clipper2.PyclipperOffset()
    offset.AddPath(contour, Clipper2.JoinType.Round, Clipper2.EndType.Polygon)
    val solution = offset.Execute(distance)

    return if (solution.isNotEmpty() && solution[0].size >= 3) solution[0] else contour
}
```

### 5. 更新 import 语句

从 `CTDPostProcessor.kt` 删除 clipper 相关 import，添加 Clipper2 import：

```kotlin
import com.mycle1.clipper2.Clipper2
import com.mycle1.clipper2.Clipper2.JoinType
import com.mycle1.clipper2.Clipper2.EndType
import com.mycle1.clipper2.Point64
import com.mycle1.clipper2.Path64
```

### 6. 还原调试日志

之前在 `extractQuadBoxes()` 中添加的 `>>> RAW` 和 `>>> UNCLIP` 日志需要还原（因为它们只是调试用的）。

## 验证标准

修复后 unclip 后的 AABB 应该比 RAW 的 AABB 大（或相近），而非明显缩小：

```
修复后期望：
RAW:   AABB=Rect(375, 214 - 384, 259)  宽9, 高45
UNCLIP: AABB=Rect(365, 205 - 395, 270)  宽30, 高65  ✓ 合理扩张
```