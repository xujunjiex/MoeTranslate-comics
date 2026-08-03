# OCR 引擎统一选择层 + 语言动态适配 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一游戏/漫画的 OCR 引擎选择为单一共享源（`OcrEngineGroup`），模型管理页可选中（高亮/未下载置灰），首页双行状态栏，源语言按 OCR 模型动态排序置灰、目标语言按翻译模型过滤。

**Architecture:** 新建 `OcrEngineGroup` 枚举（4 组：MLKit/PP-OCRv6/PP-OCRv5/RT-DETR+manga-ocr）作为唯一事实来源，含游戏/漫画引擎映射、支持源语言、是否需下载；`OcrEngineManager` 读写共享 prefs `Ocr_Engine_Group`（含首次迁移）。模型管理页、悬浮窗、首页状态栏、语言过滤全部从该层读取/写入，游戏/漫画不再各自维护独立 OCR prefs。

**Tech Stack:** Kotlin、Android View/ViewBinding、SharedPreferences（`CustomPreference`）、Robolectric 单测。

## Global Constraints

- 语言池固定 30 种：`zh, zh-TW, en, ja, ko, ru, fr, de, es, pt, it, nl, pl, sv, da, no, fi, cs, hu, ro, tr, vi, id, ms, fil, hi, mr, ne, ca, af`
- PP-OCRv5 项目仅接入 ch/ko/ru/en 模型（latin/th/el 等**未接入**，这些语言对 v5 置灰，不新增下载）
- PP-OCRv6 不含 ko/ru（v6 下这 2 种置灰）；ML Kit v2 不含 ru（Cyrillic 脚本不属于 v2）
- 漫画引擎枚举：`DetEngine` = MLKIT(0)/RT_DETR_V2(3)/PP_OCR_V5(4)/PP_OCR_V6(5)；`OcrEngine` = MLKit(0)/MangaOcr(1)/PPOcrV5(4)/PPOcrV6(5)
- 游戏引擎值：MLKIT=0 / V5=1 / MANGA=2 / V6=3
- 现有 `Manga_Det_Model`/`Manga_Rec_Model`/`Game_OCR_Engine` 保留**只读兼容**（迁移用），不再写
- 所有日志用 `LogCollector`；弹窗用 `AlertDialog` 自定义样式（`dialog_background`）
- 单测用 PowerShell + 干净 PATH + `--no-daemon`（见 CLAUDE.md）

---
## File Structure

| 文件 | 动作 | 职责 |
|------|------|------|
| `app/src/main/java/com/moe/starflow/manga/OcrEngineGroup.kt` | 新建 | 4 组枚举（映射/语言/下载） |
| `app/src/main/java/com/moe/starflow/utils/OcrEngineManager.kt` | 新建 | 共享 prefs 读写 + 迁移 |
| `app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt` | 修改 | loadConfig 读共享、toggleModelSimple 写共享、watchedKeys、cycleSourceLang 过滤 |
| `app/src/main/java/com/moe/starflow/translate/FloatingBallService.kt` | 修改 | 引擎读共享、cycleOcrEngine 写共享、cycleSourceLang 过滤 |
| `app/src/main/java/com/moe/starflow/me/model/ModelManagementFragment.kt` | 修改 | 组标题选择 + 高亮 + 未下载置灰 |
| `app/src/main/res/layout/fragment_model_management.xml` | 修改 | 组标题加背景 selector + 状态 View |
| `app/src/main/java/com/moe/starflow/translate/TranslateFragment.kt` | 修改 | 双行状态栏、源语言动态列表 |
| `app/src/main/res/layout/fragment_translate.xml` | 修改 | welcomeTitle/Subtitle → OCR/翻译模型双行 |
| `app/src/main/java/com/moe/starflow/translate/TranslateTools.kt` | 修改 | `getLanguagesList` 动态化（OCR 组/翻译引擎 → 排序+enabled） |
| `app/src/main/java/com/moe/starflow/translate/LanguageSelectionDialog.kt` | 修改 | 置灰项支持 + 点击置灰弹提示 |
| `app/src/main/res/values/strings.xml` + `values-zh/strings.xml` | 修改 | 未下载提示、语言不支持提示模板、翻译引擎名 |
| `app/src/test/java/com/moe/starflow/manga/OcrEngineGroupTest.kt` | 新建 | 支持矩阵单测 |
| `app/src/test/java/com/moe/starflow/utils/OcrEngineManagerTest.kt` | 新建 | 迁移/读写单测 |

---
## 任务分解

### Task 1: OcrEngineGroup 枚举 + 支持矩阵单测

**Files:**
- Create: `app/src/main/java/com/moe/starflow/manga/OcrEngineGroup.kt`
- Test: `app/src/test/java/com/moe/starflow/manga/OcrEngineGroupTest.kt`

**Interfaces:**
- Produces: `enum class OcrEngineGroup` 含 `key/labelRes/gameEngine/mangaDet/mangaOcr/sourceLangs/needsDownload/requiredModelsRes`；`val ALL_LANGS: List<String>`（30 种池，排序）

- [ ] **Step 1: 写失败单测**

`app/src/test/java/com/moe/starflow/manga/OcrEngineGroupTest.kt`:
```kotlin
package com.moe.starflow.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEngineGroupTest {
    private val latin = setOf("fr","de","es","pt","it","nl","pl","sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","ca","af")
    private val deva = setOf("hi","mr","ne")

    @Test fun allLangs_is30Inclusive() {
        assertEquals(30, OcrEngineGroup.ALL_LANGS.size)
        assertEquals(listOf("zh","zh-TW","en","ja","ko","ru","fr","de","es","pt","it","nl","pl","sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","hi","mr","ne","ca","af"), OcrEngineGroup.ALL_LANGS)
    }
    @Test fun mlkit_langs() {
        val g = OcrEngineGroup.MLKIT
        assertTrue(g.sourceLangs.contains("zh") && g.sourceLangs.contains("ko"))
        assertTrue(g.sourceLangs.containsAll(latin) && g.sourceLangs.containsAll(deva))
        assertFalse(g.sourceLangs.contains("ru"))  // ML Kit v2 无 Cyrillic 脚本
    }
    @Test fun v6_noKoRu() {
        val g = OcrEngineGroup.PP_OCR_V6
        assertFalse(g.sourceLangs.contains("ko"))
        assertFalse(g.sourceLangs.contains("ru"))
        assertTrue(g.sourceLangs.contains("fr"))
    }
    @Test fun v5_sixLangs() {
        assertEquals(setOf("zh","zh-TW","en","ja","ko","ru"), OcrEngineGroup.PP_OCR_V5.sourceLangs)
    }
    @Test fun manga_onlyJa() {
        assertEquals(setOf("ja"), OcrEngineGroup.RT_MANGA.sourceLangs)
    }
    @Test fun gameMappings() {
        assertEquals(0, OcrEngineGroup.MLKIT.gameEngine)
        assertEquals(3, OcrEngineGroup.PP_OCR_V6.gameEngine)
        assertEquals(1, OcrEngineGroup.PP_OCR_V5.gameEngine)
        assertEquals(2, OcrEngineGroup.RT_MANGA.gameEngine)
    }
}
```

- [ ] **Step 2: 运行确认失败**

PowerShell + 干净 PATH（下同）：
```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'; $env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.manga.OcrEngineGroupTest
```
Expected: FAIL（`OcrEngineGroup` unresolved）。

- [ ] **Step 3: 实现枚举**

`app/src/main/java/com/moe/starflow/manga/OcrEngineGroup.kt`:
```kotlin
package com.moe.starflow.manga

import com.moe.starflow.R

/**
 * 统一 OCR 引擎选择：4 组，游戏/漫画共用唯一事实来源。
 * 游戏/漫画引擎映射、支持源语言、是否需下载全部收敛于此。
 */
enum class OcrEngineGroup(
    val key: String,
    val labelRes: Int,
    val gameEngine: Int,          // 游戏 FloatingBallService：MLKIT=0/V5=1/MANGA=2/V6=3
    val mangaDet: DetEngine,
    val mangaOcr: OcrEngine,
    val sourceLangs: Set<String>, // 30 种池内子集
    val needsDownload: Boolean,
    val requiredModelsRes: Int
) {
    MLKIT("mlkit", R.string.model_group_mlkit, 0, DetEngine.MLKIT, OcrEngine.MLKit,
        setOf("zh","zh-TW","en","ja","ko","fr","de","es","pt","it","nl","pl","sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","ca","af","hi","mr","ne"),
        needsDownload = false, requiredModelsRes = 0),
    PP_OCR_V6("ppocrv6", R.string.model_group_ppocrv6, 3, DetEngine.PP_OCR_V6, OcrEngine.PPOcrV6,
        setOf("zh","zh-TW","en","ja","fr","de","es","pt","it","nl","pl","sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","ca","af"),
        needsDownload = false, requiredModelsRes = 0),
    PP_OCR_V5("ppocrv5", R.string.model_group_ppocrv5, 1, DetEngine.PP_OCR_V5, OcrEngine.PPOcrV5,
        setOf("zh","zh-TW","en","ja","ko","ru"),
        needsDownload = true, requiredModelsRes = R.string.ocr_group_v5_required),
    RT_MANGA("rtmanga", R.string.model_group_rt_manga, 2, DetEngine.RT_DETR_V2, OcrEngine.MangaOcr,
        setOf("ja"),
        needsDownload = true, requiredModelsRes = R.string.ocr_group_manga_required);

    companion object {
        /** 30 种语言池（全组并集），顺序即首页源语言列表顺序 */
        val ALL_LANGS = listOf(
            "zh","zh-TW","en","ja","ko","ru","fr","de","es","pt","it","nl","pl",
            "sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","hi","mr","ne","ca","af"
        )
        fun fromKey(key: String): OcrEngineGroup = entries.firstOrNull { it.key == key } ?: MLKIT
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

同上命令，Expected: PASS（5 tests）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/moe/starflow/manga/OcrEngineGroup.kt app/src/test/java/com/moe/starflow/manga/OcrEngineGroupTest.kt
git commit -m "feat(ocr): OcrEngineGroup 统一 4 组引擎（映射/语言/下载）+ 30 种语言池"
```

---
### Task 2: OcrEngineManager（共享 prefs + 迁移）+ 单测

**Files:**
- Create: `app/src/main/java/com/moe/starflow/utils/OcrEngineManager.kt`
- Test: `app/src/test/java/com/moe/starflow/utils/OcrEngineManagerTest.kt`

**Interfaces:**
- Consumes: `OcrEngineGroup`（Task 1）
- Produces: `OcrEngineManager.getOcrEngineGroup(prefs): OcrEngineGroup`、`setOcrEngineGroup(prefs, group)`；prefs 键常量 `OcrEngineManager.PREF_KEY = "Ocr_Engine_Group"`

- [ ] **Step 1: 写失败单测**

`app/src/test/java/com/moe/starflow/utils/OcrEngineManagerTest.kt`:
```kotlin
package com.moe.starflow.utils

import androidx.preference.PreferenceManager
import com.moe.starflow.manga.OcrEngineGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OcrEngineManagerTest {
    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())

    @Test fun default_noLegacyPrefs_returnsMlkitAndMigrates() {
        val p = prefs()
        p.edit().clear().commit()
        assertEquals(OcrEngineGroup.MLKIT, OcrEngineManager.getOcrEngineGroup(p))
        // 迁移已写共享值
        assertEquals("mlkit", p.getString(OcrEngineManager.PREF_KEY, ""))
    }
    @Test fun legacy_mangaV6_migratesToV6() {
        val p = prefs()
        p.edit().clear().commit()
        // 漫画 v6：DetEngine.PP_OCR_V6=5, OcrEngine.PPOcrV6=5
        p.edit().putInt("Manga_Det_Model", 5).putInt("Manga_Rec_Model", 5).commit()
        assertEquals(OcrEngineGroup.PP_OCR_V6, OcrEngineManager.getOcrEngineGroup(p))
    }
    @Test fun setAndRead() {
        val p = prefs()
        p.edit().clear().commit()
        OcrEngineManager.setOcrEngineGroup(p, OcrEngineGroup.RT_MANGA)
        assertEquals(OcrEngineGroup.RT_MANGA, OcrEngineManager.getOcrEngineGroup(p))
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.utils.OcrEngineManagerTest
```
Expected: FAIL（`OcrEngineManager` unresolved）。

- [ ] **Step 3: 实现 OcrEngineManager**

`app/src/main/java/com/moe/starflow/utils/OcrEngineManager.kt`:
```kotlin
package com.moe.starflow.utils

import android.content.SharedPreferences
import com.moe.starflow.manga.DetEngine
import com.moe.starflow.manga.OcrEngine
import com.moe.starflow.manga.OcrEngineGroup

/** 统一 OCR 引擎选择的共享 prefs 读写（游戏/漫画共用）。 */
object OcrEngineManager {
    const val PREF_KEY = "Ocr_Engine_Group"

    fun getOcrEngineGroup(prefs: SharedPreferences): OcrEngineGroup {
        val key = prefs.getString(PREF_KEY, null)
        if (key != null) return OcrEngineGroup.fromKey(key)
        // 迁移：优先漫画 prefs（4 组一一对应），漫画为默认时用游戏 prefs
        val det = prefs.getInt("Manga_Det_Model", DetEngine.PP_OCR_V6.value)
        val rec = prefs.getInt("Manga_Rec_Model", OcrEngine.PPOcrV6.value)
        val migrated = OcrEngineGroup.entries.firstOrNull {
            it.mangaDet.value == det && it.mangaOcr.value == rec
        } ?: when (prefs.getInt("Game_OCR_Engine", 0)) {
            0 -> OcrEngineGroup.MLKIT
            1 -> OcrEngineGroup.PP_OCR_V5
            2 -> OcrEngineGroup.RT_MANGA
            3 -> OcrEngineGroup.PP_OCR_V6
            else -> OcrEngineGroup.MLKIT
        }
        setOcrEngineGroup(prefs, migrated)
        return migrated
    }

    fun setOcrEngineGroup(prefs: SharedPreferences, group: OcrEngineGroup) {
        prefs.edit().putString(PREF_KEY, group.key).apply()
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Expected: PASS（3 tests）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/moe/starflow/utils/OcrEngineManager.kt app/src/test/java/com/moe/starflow/utils/OcrEngineManagerTest.kt
git commit -m "feat(ocr): OcrEngineManager 共享 prefs 读写 + 首次迁移（漫画优先/游戏兜底）"
```

---
### Task 3: 漫画服务读/写共享引擎 + watchedKeys

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt`

**Interfaces:**
- Consumes: `OcrEngineManager`（Task 2）
- Behavior: `loadConfig()` 的 det/ocr 从 `OcrEngineManager` 映射；`toggleModelSimple`/`applyCombo` 写 `Ocr_Engine_Group`；`watchedKeys` 加 `Ocr_Engine_Group`

- [ ] **Step 1: loadConfig 改读共享**

`MangaFloatingService.loadConfig()`（约 line 722）：
```kotlin
// 替换：
val detEngine = DetEngine.fromValue(prefs.getInt("Manga_Det_Model", DetEngine.PP_OCR_V6.value))
// 为：
val group = com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(prefs)
val detEngine = group.mangaDet
```
后续 `ocrEngine = group.mangaOcr`（loadConfig 里原本读 `Manga_Rec_Model` 处替换）。保留 `Manga_Det_Model` 读取的兼容逻辑不再使用（旧 prefs 只读迁移）。

- [ ] **Step 2: applyCombo / toggleModelSimple 写共享**

`applyCombo(combo)` 内（约 line 256），替换：
```kotlin
prefs.setInt("Manga_Det_Model", combo.detEngine.value)
prefs.setInt("Manga_Rec_Model", combo.ocrEngine.value)
```
为：
```kotlin
val group = OcrEngineGroup.entries.firstOrNull {
    it.mangaDet == combo.detEngine && it.mangaOcr == combo.ocrEngine
} ?: OcrEngineGroup.MLKIT
com.moe.starflow.utils.OcrEngineManager.setOcrEngineGroup(prefs, group)
```

- [ ] **Step 3: watchedKeys 加 Ocr_Engine_Group**

`watchedKeys` setOf 加 `"Ocr_Engine_Group"`（约 line 363）。

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew.bat --no-daemon :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt
git commit -m "refactor(manga): loadConfig/applyCombo 改从 Ocr_Engine_Group 读写共享引擎"
```

---
### Task 4: 游戏服务读/写共享引擎

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/translate/FloatingBallService.kt`

**Interfaces:**
- Consumes: `OcrEngineManager` / `OcrEngineGroup`（Task 1/2）

- [ ] **Step 1: 游戏引擎读取改共享**

`getOcrEngineName()`（约 line 391）及所有读 `Game_OCR_Engine` 处：
```kotlin
val group = com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(prefs)
val engine = group.gameEngine
```
`engineLabel(engine)` 现有映射保留（V5/V6/MLKIT/MANGA 字符串）。

- [ ] **Step 2: cycleOcrEngine 写共享**

`cycleOcrEngine()`（约 line 937）写引擎后，同步：
```kotlin
prefs.setInt("Game_OCR_Engine", next)   // 兼容保留（不再作为事实来源）
val group = OcrEngineGroup.entries.firstOrNull { it.gameEngine == next } ?: OcrEngineGroup.MLKIT
com.moe.starflow.utils.OcrEngineManager.setOcrEngineGroup(prefs, group)
```

- [ ] **Step 3: 编译验证 + 提交**

```powershell
.\gradlew.bat --no-daemon :app:compileDebugKotlin
```
提交：`git commit -m "refactor(game): 游戏引擎读 Ocr_Engine_Group，cycleOcrEngine 写共享"`

---
### Task 5: 模型管理页选择 OCR 组（高亮/置灰/弹窗）

**Files:**
- Modify: `app/src/main/res/layout/fragment_model_management.xml`
- Modify: `app/src/main/java/com/moe/starflow/me/model/ModelManagementFragment.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`

**Interfaces:**
- Consumes: `OcrEngineGroup` / `OcrEngineManager`（Task 1/2）

- [ ] **Step 1: 加字符串**

`values-zh/strings.xml`:
```xml
<string name="ocr_group_v5_required">该模型组未下载，需要：PP-OCRv5 检测器 + PP-OCRv5 识别器(rec_zh)</string>
<string name="ocr_group_manga_required">该模型组未下载，需要：RT-DETR-V2 检测器 + manga-ocr 识别器</string>
<string name="ocr_group_selected">当前使用</string>
<string name="ocr_group_choose">选择为当前 OCR 模型</string>
```
`values/strings.xml` 对应英文。

- [ ] **Step 2: 组标题加选择状态**

`fragment_model_management.xml`：给 4 个组标题 TextView（`model_group_mlkit`/`model_group_ppocrv6`/`model_group_ppocrv5`/`model_group_rt_manga`，当前是 `<TextView ... />`）改为带 id + 可点击 + 背景 selector，并各加一个 `TextView` 状态角标（如 `mlkit_group_selected`，默认 `visibility="gone"`）：
```xml
<TextView
    android:id="@+id/mlkit_group_title"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="@string/model_group_mlkit"
    android:textSize="18sp"
    android:textStyle="bold"
    android:padding="4dp"
    android:layout_marginTop="8dp"
    android:background="@drawable/ocr_group_selector"
    android:clickable="true" />
<TextView
    android:id="@+id/mlkit_group_selected"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="@string/ocr_group_selected"
    android:textColor="#4CAF50"
    android:textSize="12sp"
    android:visibility="gone" />
```
（4 组同构：`mlkit`/`ppocrv6`/`ppocrv5`/`rt_manga` 前缀）。

新增 `app/src/main/res/drawable/ocr_group_selector.xml`：
```xml
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_selected="true">
        <shape><solid android:color="#2200B4D7"/><corners android:radius="6dp"/></shape>
    </item>
    <item android:state_enabled="false">
        <shape><solid android:color="#22000000"/><corners android:radius="6dp"/></shape>
    </item>
    <item><shape><solid android:color="#00000000"/></shape></item>
</selector>
```

- [ ] **Step 3: ModelManagementFragment 选择逻辑**

在 `ModelManagementFragment` 加：
```kotlin
// 4 组：groupKey 前缀 → OcrEngineGroup
private val groupViews = listOf(
    OcrEngineGroup.MLKIT to (R.id.mlkit_group_title to R.id.mlkit_group_selected),
    OcrEngineGroup.PP_OCR_V6 to (R.id.ppocrv6_group_title to R.id.ppocrv6_group_selected),
    OcrEngineGroup.PP_OCR_V5 to (R.id.ppocrv5_group_title to R.id.ppocrv5_group_selected),
    OcrEngineGroup.RT_MANGA to (R.id.rt_manga_group_title to R.id.rt_manga_group_selected)
)

private fun refreshOcrGroupSelection() {
    val prefs = CustomPreference.getInstance(requireContext())
    val current = com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(prefs)
    for ((group, ids) in groupViews) {
        val title = rootView.findViewById<android.view.View>(ids.first)
        val badge = rootView.findViewById<android.widget.TextView>(ids.second)
        title.isSelected = (group == current)
        badge.visibility = if (group == current) android.view.View.VISIBLE else android.view.View.GONE
        // 未下载组置灰：PP-OCRv5 需 det+rec_zh，RT-MANGA 需 RT-DETR+manga-ocr
        val available = when (group) {
            OcrEngineGroup.PP_OCR_V5 -> com.moe.starflow.manga.PPOcrModelFiles.isV5DetDownloaded(requireContext()) && com.moe.starflow.manga.PPOcrModelFiles.isV5RecZhDownloaded(requireContext())
            OcrEngineGroup.RT_MANGA -> com.moe.starflow.manga.RTDetrModelFiles.isModelAvailable(requireContext()) && com.moe.starflow.manga.MangaOcrModelFiles.isModelDownloaded(requireContext())
            else -> true
        }
        title.isEnabled = available
        title.alpha = if (available) 1f else 0.4f
        title.setOnClickListener {
            if (available) {
                com.moe.starflow.utils.OcrEngineManager.setOcrEngineGroup(prefs, group)
                com.moe.starflow.utils.UiUtils.showToast(requireContext(), getString(group.labelRes), isShort = true)
                refreshOcrGroupSelection()
            } else {
                android.app.AlertDialog.Builder(requireContext())
                    .setMessage(getString(group.requiredModelsRes))
                    .setPositiveButton(R.string.user_known, null)
                    .create().also { it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }.show()
            }
        }
    }
}
```
在 `onViewCreated` 末尾调用 `refreshOcrGroupSelection()`。

- [ ] **Step 4: 编译验证 + 提交**

```powershell
.\gradlew.bat --no-daemon :app:compileDebugKotlin
```
提交：`git commit -m "feat(ui): 模型管理页可选 OCR 组（高亮/未下载置灰/弹窗）"`

---
### Task 6: 首页双行状态栏

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/translate/TranslateFragment.kt`
- ⚠️ **不改 `fragment_translate.xml`**（并行会话占用该文件；`welcome_title`/`welcome_subtitle` 已存在，字号在代码里 `setTextSize` 设置，避免布局冲突）

**Interfaces:**
- Consumes: `OcrEngineGroup` / `OcrEngineManager`（Task 1/2）

- [ ] **Step 1: TranslateFragment 设置两行（代码设字号，不改布局）**

新增方法并加入现有 prefs 监听（`onStart` 的 `languagePrefsListener` 扩展）：
```kotlin
private fun refreshEngineStatusBar() {
    val prefs = CustomPreference.getInstance(requireContext())
    val group = com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(prefs.getSharedPreferences())
    binding.welcomeTitle.text = getString(group.labelRes)          // 上：OCR 模型
    binding.welcomeTitle.textSize = 14f                            // 字号，避免遮挡
    binding.welcomeTitle.maxLines = 1
    binding.welcomeTitle.ellipsize = android.text.TextUtils.TruncateAt.END
    binding.welcomeSubtitle.text = getCurrentTranslatorName()      // 下：翻译模型
    binding.welcomeSubtitle.textSize = 13f
    binding.welcomeSubtitle.maxLines = 1
    binding.welcomeSubtitle.ellipsize = android.text.TextUtils.TruncateAt.END
}

/** 当前翻译模型名（NLLB/Hy-MT2/各 API），从 Text_API/Text_AI 判断，不带「（OCR）」后缀 */
private fun getCurrentTranslatorName(): String {
    val prefs = CustomPreference.getInstance(requireContext())
    val api = prefs.getInt("Text_API", Constants.TextApi.BING.id)
    return when (api) {
        Constants.TextApi.AI.id -> if (prefs.getInt("Text_AI", Constants.TextAI.NLLB.id) == Constants.TextAI.HYMT2.id) "Hy-MT2" else "NLLB"
        Constants.TextApi.BING.id -> getString(R.string.bingapi_name)
        Constants.TextApi.NIUTRANS.id -> getString(R.string.niuapi_name)
        Constants.TextApi.OPENAI.id -> { val list = com.moe.starflow.me.apiconfig.ConfigurationStorage.loadAllProviders(prefs); val i = prefs.getInt("OpenAI_Selected_Provider", 0); if (i < list.size) list[i].name else getString(R.string.uniaiapi_name) }
        Constants.TextApi.VOLC.id -> getString(R.string.volcapi_name)
        Constants.TextApi.AZURE.id -> getString(R.string.azureapi_name)
        Constants.TextApi.DEEPL.id -> getString(R.string.deeplapi_name)
        Constants.TextApi.BAIDU.id -> getString(R.string.baiduapi_name)
        Constants.TextApi.TENCENT.id -> getString(R.string.tencentapi_name)
        Constants.TextApi.CUSTOM_TEXT.id -> getString(R.string.custom)
        else -> getString(R.string.bingapi_name)
    }
}
```
`onViewCreated` 调 `refreshEngineStatusBar()`；prefs listener 里 `Source_Language`/`Target_Language`/`Ocr_Engine_Group`/`Text_API`/`Text_AI` 变化时刷新。

- [ ] **Step 2: 编译验证 + 提交**

```powershell
.\gradlew.bat --no-daemon :app:compileDebugKotlin
```
提交：`git commit -m "feat(ui): 首页顶部双行状态栏（上 OCR 模型/下翻译模型）"`

---
### Task 7: 源语言动态排序/置灰 + LanguageSelectionDialog 置灰

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/translate/TranslateTools.kt`
- Modify: `app/src/main/java/com/moe/starflow/translate/LanguageSelectionDialog.kt`
- Modify: `app/src/main/java/com/moe/starflow/translate/TranslateFragment.kt`
- ⚠️ **不改 `TextTranslateFragment.kt`**（并行会话占用；它已调 `getLanguagesList(requireContext(), 1)` 不传 ocrGroup，天然全量不受 OCR 影响，仅确认）

**Interfaces:**
- Consumes: `OcrEngineGroup.ALL_LANGS` / `sourceLangs`（Task 1）
- Produces: `TranslateTools.getLanguagesList(context, type, ocrGroup: OcrEngineGroup? = null): List<CustomLocale>?`（新增可选参数；type=1 源语言按 ocrGroup 排序+enabled；type=2 目标语言后续 Task 8 过滤）；`LanguageSelectionDialog` 增加 `enabled: List<Boolean>` + 置灰提示

- [ ] **Step 1: getLanguagesList 动态化**

`TranslateTools.getLanguagesList(context, type, ocrGroup: OcrEngineGroup? = null)`：签名加可选 `ocrGroup`。源语言（type=1）分支改为：
```kotlin
if (type == 1) {
    // OCR 源语言池 30 种，按当前组排序：支持的在前面，不支持的自动下移
    val langs = if (ocrGroup != null) {
        OcrEngineGroup.ALL_LANGS.filter { ocrGroup.sourceLangs.contains(it) } +
            OcrEngineGroup.ALL_LANGS.filterNot { ocrGroup.sourceLangs.contains(it) }
    } else OcrEngineGroup.ALL_LANGS
    return@runCatching langs.map { CustomLocale(it) }
}
```
（原 `ocr_support_languages.xml` 固定 6 种不再用于源语言；目标语言分支保留原逻辑，后续 Task 8 覆盖。）

- [ ] **Step 2: LanguageSelectionDialog 支持置灰**

构造参数加 `enabled: List<Boolean>? = null` 与 `onDisabledClick: ((CustomLocale) -> Unit)? = null`。adapter `getView` 中按 `enabled[position]` 设 `textView.isEnabled` + `alpha=0.4f`；`setOnItemClickListener` 中：
```kotlin
val locale = locales[position]
if (enabled == null || enabled[position]) {
    onLanguageSelected(locale); dialog.dismiss()
} else {
    onDisabledClick?.invoke(locale)   // 点击置灰 → 弹提示
}
```

- [ ] **Step 3: TranslateFragment 源语言选择传 OCR 组 + 置灰提示**

`showLanguageListDialog(1)` 分支：
```kotlin
val ocrGroup = com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(prefs.getSharedPreferences())
val locales = TranslateTools.getLanguagesList(requireContext(), 1, ocrGroup) ?: emptyList()
val supported = locales.map { ocrGroup.sourceLangs.contains(it.getOriCode()) }
LanguageSelectionDialog(requireContext(), 1, locales,
    onLanguageSelected = { sel -> /* 原逻辑 */ },
    enabled = supported,
    onDisabledClick = { sel ->
        val hint = ocrGroup.requiredModelsRes.takeIf { ocrGroup.needsDownload }?.let { getString(it) } ?: ""
        android.app.AlertDialog.Builder(requireContext())
            .setMessage("该语言当前 OCR 模型不支持，请使用 ${supportedModelsText(sel.getOriCode())}")
            .setPositiveButton(R.string.user_known, null)
            .create().also { it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }.show()
    }).show()
```
其中 `supportedModelsText(lang)`：遍历 `OcrEngineGroup.entries.filter { it.sourceLangs.contains(lang) }` 拼接显示名（如「ML Kit / PP-OCRv5」）；空则显示「其他支持该语言的模型」。

- [ ] **Step 4: 确认文本翻译源语言不受 OCR 影响**

`TextTranslateFragment.showLanguageDialog(1)` 调 `getLanguagesList(requireContext(), 1)`（**不传 ocrGroup**）→ 全量 30 种可选，天然不受 OCR 影响。

- [ ] **Step 5: 编译验证 + 提交**

```powershell
.\gradlew.bat --no-daemon :app:compileDebugKotlin
```
提交：`git commit -m "feat(lang): 源语言按 OCR 组动态排序/置灰 + LanguageSelectionDialog 置灰提示；文本翻译不受 OCR 影响"`

---
### Task 8: 目标语言按翻译模型过滤

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/translate/TranslateTools.kt`
- Modify: `app/src/main/java/com/moe/starflow/translate/TranslateFragment.kt`
- Modify: `app/src/main/java/com/moe/starflow/translate/TextTranslateFragment.kt`

**Interfaces:**
- Consumes: 翻译模型类型（`Text_API`+`Text_AI`）
- Produces: 目标语言按 NLLB/Hy-MT2/API 过滤的 enabled 列表

- [ ] **Step 1: 目标语言 enabled 计算**

`TranslateTools` 新增：
```kotlin
/** 当前翻译模型不支持的目标语言集合（30 种池内） */
fun getDisabledTargetLangs(prefs: com.moe.starflow.utils.CustomPreference): Set<String> {
    val api = prefs.getInt("Text_API", Constants.TextApi.BING.id)
    return when {
        api == Constants.TextApi.AI.id && prefs.getInt("Text_AI", Constants.TextAI.NLLB.id) == Constants.TextAI.HYMT2.id ->
            setOf("sv","da","no","fi","hu","ro","ne","ca","af")  // Hy-MT2 38 种内不支持的
        api == Constants.TextApi.AI.id -> emptySet()              // NLLB 支持全部 30 种
        else -> emptySet()                                        // API：各 API 现有语言文件已覆盖主流；视为全支持
    }
}
```

- [ ] **Step 2: 首页/文本翻译目标语言过滤**

`TranslateFragment.showLanguageListDialog(2)` 与 `TextTranslateFragment.showLanguageDialog(2)`：目标语言列表 = `getLanguagesList(requireContext(), 2)`，enabled = `!getDisabledTargetLangs(prefs).contains(code)`；置灰点击提示「该语言当前翻译模型不支持，请使用 {支持的翻译模型}」（如 Hy-MT2 不支持瑞典语 → 提示「请使用 NLLB 或 API 翻译」）。

- [ ] **Step 3: 编译验证 + 提交**

```powershell
.\gradlew.bat --no-daemon :app:compileDebugKotlin
```
提交：`git commit -m "feat(lang): 目标语言按翻译模型过滤（Hy-MT2 9 种置灰）"`

---
### Task 9: 悬浮窗源语言循环只切适配语言

**Files:**
- Modify: `app/src/main/java/com/moe/starflow/manga/MangaFloatingService.kt`
- Modify: `app/src/main/java/com/moe/starflow/translate/FloatingBallService.kt`

**Interfaces:**
- Consumes: `OcrEngineGroup.sourceLangs`（Task 1）

- [ ] **Step 1: 漫画 cycleSourceLang**

`MangaFloatingService.cycleSourceLang()`（约 line 1180）：当前用 `langCycle = arrayOf("ja","en","zh","zh-TW","ko","ru")` + `isOcrLangAvailable`。改为：
```kotlin
val current = prefs.getString("Source_Language", "ja")
val group = com.moe.starflow.utils.OcrEngineManager.getOcrEngineGroup(prefs)
val cycle = group.sourceLangs.toList()
val currentIdx = cycle.indexOf(current).coerceAtLeast(0)
for (i in 1..cycle.size) {
    val next = cycle[(currentIdx + i) % cycle.size]
    prefs.setString("Source_Language", next)
    config = loadConfig()
    val langName = com.moe.starflow.translate.CustomLocale.getInstance(next).getDisplayName()
    showToast(getString(R.string.language_switched_to, langName), true)
    checkLanguageHints()
    return
}
```
（移除 `isOcrLangAvailable` 逐语言检查——组 `sourceLangs` 已保证适配；`manga` 组只有 ja → 切换即 ja。）

- [ ] **Step 2: 游戏 cycleSourceLang**

`FloatingBallService.cycleSourceLang()`（约 line 965）：同样改为从 `OcrEngineManager.getOcrEngineGroup(prefs).sourceLangs` 循环（替换原 `langCycle` + `isOcrLangAvailable`）。

- [ ] **Step 3: 编译验证 + 提交**

```powershell
.\gradlew.bat --no-daemon :app:compileDebugKotlin
```
提交：`git commit -m "feat(lang): 悬浮窗源语言循环只切当前 OCR 组适配语言"`

---
### Task 10: 最终验证

**Files:**
- 无新增

- [ ] **Step 1: 全量单测**

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'; $env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL（含新增 OcrEngineGroupTest 5 + OcrEngineManagerTest 3）。

- [ ] **Step 2: 构建 APK**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: 提交（如有多余未提交）**

```bash
git status --short
```

## Self-Review 记录

- **Spec 覆盖**：①统一层 → Task 1/2；②模型管理页选择/高亮/置灰 → Task 5；③首页双行 → Task 6；④源语言动态 → Task 7；⑤目标语言过滤 → Task 8；⑥文本翻译不受 OCR → Task 7 Step 4；⑦悬浮窗同步 → Task 3/4；⑧悬浮窗循环只切适配 → Task 9；⑨迁移 → Task 2。全部覆盖。
- **占位符**：无 TBD/TODO；每步含代码。
- **类型一致**：`OcrEngineGroup`（key/labelRes/gameEngine/mangaDet/mangaOcr/sourceLangs/needsDownload/requiredModelsRes）、`OcrEngineManager.getOcrEngineGroup(prefs)/setOcrEngineGroup`、`getLanguagesList(context, type, ocrGroup)`、`LanguageSelectionDialog(context, type, locales, onLanguageSelected, enabled, onDisabledClick)` 跨任务一致。
- **已知限制**：`R.string.model_group_mlkit` 等组标题字符串复用现有；`ppi` 目标语言 API 视为全支持（各 API 现有语言文件已覆盖 30 种主流，未逐 API 校验超范围语言）。
