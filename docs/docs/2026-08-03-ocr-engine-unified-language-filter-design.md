# OCR 引擎统一选择层 + 语言动态适配 设计

> 2026-08-03。基于用户需求：OCR 模型选择从悬浮窗扩展到模型管理页（两处同步）、首页双行状态栏、源/目标语言按模型动态适配，从源头避免"模型 × 不支持语言"的错误搭配。

## 1. 背景与目标

**现状问题：**
- OCR 引擎只能在悬浮窗切换（游戏 `cycleOcrEngine` / 漫画 `engineCombos`），模型管理页只能下载/删除模型，不能选择引擎
- 游戏与漫画 OCR 选择**分离**（`Game_OCR_Engine` / `Manga_Det_Model`+`Manga_Rec_Model` 两套 prefs）
- 源语言固定 6 种（zh/zh-TW/en/ja/ko/ru），不随 OCR 模型变化；PP-OCRv6 实际不含 ko/ru，但假设"所有语言都支持"→ KO/RU 源语言用 v6 会乱码
- 首页顶部状态栏只有 `selectedAPI`（翻译 API 名），无 OCR 模型显示

**目标：**
1. **统一 OCR 引擎选择层**：一套选择（`Ocr_Engine_Group`），游戏/漫画共用（两模式互斥不会同时启动），悬浮窗与模型管理页两处同步
2. **模型管理页可选中 OCR 模型组**，当前组高亮，未下载组置灰+提示
3. **首页顶部双行状态栏**：上=当前 OCR 模型，下=当前翻译模型
4. **语言动态适配**：源语言按 OCR 模型动态排序/置灰（不支持的下移置灰、点击提示），目标语言按翻译模型过滤；文本翻译源语言不受 OCR 影响

## 2. 统一 OCR 引擎选择层（`OcrEngineGroup`）

### 2.1 数据模型（新建 `com.moe.starflow.manga.OcrEngineGroup`）

> 放 manga 包：游戏（translate 包）已通过 `GameOcrEngine` 依赖 manga 包，模型管理页/首页可跨包引用。

```kotlin
enum class OcrEngineGroup(
    val key: String,              // "mlkit" / "ppocrv6" / "ppocrv5" / "rtmanga"
    val labelRes: Int,            // 显示名（组标题字符串复用）
    val gameEngine: Int,          // 游戏：MLKIT=0 / V5=1 / MANGA=2 / V6=3
    val mangaDet: DetEngine,      // 漫画检测
    val mangaOcr: OcrEngine,      // 漫画识别
    val sourceLangs: Set<String>, // 该组支持的源语言（30 种池内子集）
    val needsDownload: Boolean,   // 是否需下载模型
    val requiredModelsRes: Int    // 未下载提示文案（所需 OCR 模型名）
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
        needsDownload = true, requiredModelsRes = R.string.ocr_group_manga_required)
}
```

**语言池（30 种，全组并集）**：
`zh, zh-TW, en, ja, ko, ru, fr, de, es, pt, it, nl, pl, sv, da, no, fi, cs, hu, ro, tr, vi, id, ms, fil, hi, mr, ne, ca, af`

**支持矩阵**（各 OCR 组的 `sourceLangs`）：

| 语言 | MLKit | PP-OCRv6 | PP-OCRv5 | manga-ocr |
|------|:---:|:---:|:---:|:---:|
| zh / zh-TW / en / ja | ✅ | ✅ | ✅ | 仅 ja |
| ko | ✅ | ❌ | ✅ | ❌ |
| ru | ❌ | ❌ | ✅ | ❌ |
| fr/de/es/pt/it/nl/pl/sv/da/no/fi/cs/hu/ro/tr/vi/id/ms/fil/ca/af（拉丁） | ✅ | ✅ | ❌ | ❌ |
| hi/mr/ne（天城文） | ✅ | ❌ | ❌ | ❌ |

> ⚠️ **ML Kit v2 不含俄文**（Cyrillic 脚本不在 5 种脚本内）；**PP-OCRv6 不含韩文/俄文**；**PP-OCRv5 项目仅接入 ch/ko/ru/en**（latin/th/el 等模型未接入，故拉丁/泰文等对 v5 置灰）。

### 2.2 共享 prefs 与读写

- **`Ocr_Engine_Group`**（存 key 字符串）作为唯一事实来源
- 工具方法（新建 `com.moe.starflow.utils.OcrEngineManager`）：
  - `getOcrEngineGroup(prefs): OcrEngineGroup`（未设置时触发迁移）
  - `setOcrEngineGroup(prefs, group)`
- **迁移（首次读取）**：`Ocr_Engine_Group` 为空时，优先从漫画 `Manga_Det_Model`+`Manga_Rec_Model` 推断（4 组一一对应）；若漫画为默认值则从游戏 `Game_OCR_Engine` 推断。写入新 prefs。

### 2.3 游戏/漫画读引擎改共享值

- 漫画 `loadConfig()`：`detEngine`/`ocrEngine` 从 `Ocr_Engine_Group` 映射（替代直接读 `Manga_Det_Model`/`Manga_Rec_Model`；旧 prefs 保留只读兼容）
- 游戏 `FloatingBallService` 读 `Game_OCR_Engine` 处：改为从 `Ocr_Engine_Group` 映射
- 悬浮窗切换（游戏 `cycleOcrEngine` / 漫画 `cycleOcrEngine`）→ 写 `Ocr_Engine_Group`
- 漫画 `Manga_Det_Model`/`Manga_Rec_Model`、游戏 `Game_OCR_Engine` 不再写（保留兼容读）

## 3. 模型管理页选择 OCR 模型

### 3.1 交互

- **点击组标题区 = 选择该组**（已下载时）
- **当前选中组高亮**：组标题背景换主色/深色（`drawable` selector），标题右侧加「✓ 当前使用」角标
- **未下载组**（PP-OCRv5、RT-DETR+manga）：标题置灰不可选；点击弹窗（AlertDialog 自定义样式）：
  - PP-OCRv5：「该模型组未下载，需要：PP-OCRv5 检测器 + PP-OCRv5 识别器(rec_zh)」
  - RT-DETR+manga：「该模型组未下载，需要：RT-DETR-V2 检测器 + manga-ocr 识别器」
- 选择后写 `Ocr_Engine_Group` → Toast 显示模型名

### 3.2 高亮/可选刷新时机

- 页面 `onResume`/`onViewCreated`：按当前 `Ocr_Engine_Group` 刷新高亮
- 选择后：立即刷新
- 模型下载完成（Repository 状态变化）：刷新可选状态（已下载的组从置灰恢复可选）

### 3.3 实现位置

- `fragment_model_management.xml`：给 4 个组标题（`model_group_mlkit` 等 TextView）加背景可绘制 + 右侧状态 View
- `ModelManagementFragment`：读 `OcrEngineGroup`，设置标题点击 + 高亮 + 未下载置灰

## 4. 首页顶部双行状态栏

- `fragment_translate.xml`：`welcomeTitle`（原问候语）改为**第一行 = 当前 OCR 模型名**（如「PP-OCRv6」），`welcomeSubtitle` 改为**第二行 = 当前翻译模型名**（如「NLLB」「Hy-MT2」「Bing 翻译」）
- 字号 12-13sp，`maxLines=1` + ellipsize，避免遮挡重叠
- `TranslateFragment`：
  - 第一行：读 `OcrEngineGroup.labelRes`
  - 第二行：读当前翻译引擎（复用 `showAPIName` 的 `Text_API`/`Text_AI` 判断，取引擎名不带「（OCR）」后缀）
  - 监听 `Ocr_Engine_Group` 与翻译引擎 prefs 变化实时刷新（复用现有语言 prefs listener 扩展）

## 5. 语言动态过滤

### 5.1 源语言（首页 + 漫画/游戏悬浮菜单的语言切换）

**首页语言选择**（`showLanguageListDialog` → `getLanguagesList`）：
- 源语言列表 = 30 种池，按当前 `OcrEngineGroup.sourceLangs` **排序**：支持的在前，不支持的自动下移
- 不支持的项**置灰显示（不隐藏）**，点击弹窗：
  > 「该语言当前 OCR 模型不支持，请使用 {支持的模型名}」
  - 支持的模型名：当前组不支持的该语言，提示可切换到哪些组（如 ko 在 v6 下不支持 → 提示「请使用 MLKit / PP-OCRv5」）
- `getLanguagesList` 改为动态生成（接收当前 OCR 组 + 翻译引擎参数）

**文本翻译**（`TextTranslateFragment`）：
- 源语言**不受 OCR 影响**（文本翻译不经 OCR），30 种全量可选

### 5.2 目标语言（首页 + 文本翻译 + 悬浮窗）

- 按当前**翻译模型**过滤：`Text_API`+`Text_AI` → NLLB / Hy-MT2 / 具体 API
- 语言支持映射（30 种池内）：
  - **NLLB**：30 种几乎全部支持（zh/en/ja/ko/ru/fr/de/es/pt/it/nl/pl/sv/da/no/fi/cs/hu/ro/tr/vi/id/ms/fil/hi/mr/ne/ca/af 均在 NLLB-200）
  - **Hy-MT2**：支持 zh/zh-TW/en/ja/ko/ru/fr/de/es/pt/it/nl/pl/cs/tr/vi/id/ms/fil/hi/mr；**不支持** sv/da/no/fi/hu/ro/ne/ca/af
  - **API**：各 API 用其现有 `*_support_languages.xml` 过滤
- 不支持的置灰 + 点击提示（同源语言交互）
- 翻译模型选择变化 → 目标语言列表刷新

### 5.3 悬浮窗源语言循环切换（与首页不同）

- 悬浮窗 `cycleSourceLang` 只切**当前模型适配的语言**，不适配的语言不在循环范围内（直接跳过，不显示置灰项）
- 首页显示全部 30 种（排序+置灰）；悬浮窗只循环适配子集

### 5.4 实现位置

- `LanguageSelectionDialog`：支持置灰项（`enabled` 标志）+ 点击置灰弹提示
- `getLanguagesList`：动态（OCR 组 + 翻译引擎 → 语言列表）
- `MangaFloatingService.cycleSourceLang` / `FloatingBallService.cycleSourceLang`：循环子集改为当前组 `sourceLangs`

## 6. 悬浮窗同步

- 游戏/漫画悬浮窗切换引擎 → 写 `Ocr_Engine_Group`（第 2.3 节）
- 模型管理页选择 → 两悬浮窗下次读取即同步
- 切换后 `config` 刷新（漫画） / 引擎字段更新（游戏），与现有 `watchedKeys` 机制衔接

## 7. 错误处理 / 弹窗文案

| 场景 | 弹窗/提示 |
|------|---------|
| 点击置灰语言（源语言，模型不支持） | 「该语言当前 OCR 模型不支持，请使用 {支持的模型名}」 |
| 点击置灰语言（目标语言，翻译模型不支持） | 「该语言当前翻译模型不支持，请使用 {支持的翻译模型}」 |
| 点击未下载模型组 | 「该模型组未下载，需要：{requiredModelsRes}」 |

## 8. 测试

- 编译 + 现有单测全过
- 新增单测（Robolectric）：`OcrEngineGroup` 各组的 `sourceLangs` 与矩阵一致；`getLanguagesList` 排序/置灰逻辑
- 设备验证（用户）：
  1. 模型管理页选各组 → 首页双行状态栏第一行变化 + 悬浮窗切换同步
  2. 首页源语言列表随 OCR 组变化（支持上、不支持下移置灰）
  3. 点击置灰语言弹提示；文本翻译源语言全量
  4. 悬浮窗源语言循环只切适配语言
  5. v6 下韩文/俄文源语言不再可选（修复 KO/RU 乱码）
  6. 目标语言随翻译模型（NLLB/Hy-MT2/API）过滤

## 9. 风险与注意

- **迁移优先级**：漫画 prefs 优先（4 组一一对应），游戏兜底；迁移后旧 prefs 保留只读，避免破坏历史逻辑
- **`Ocr_Engine_Group` 与 `watchedKeys`**：漫画 `watchedKeys` 需加 `Ocr_Engine_Group`，设置页改引擎实时刷新
- **`LanguageSelectionDialog` 置灰**：需在 Adapter 层支持 enabled 标志，点击置灰项拦截并弹提示（不能走正常选择回调）
- **目标语言映射**：NLLB/Hy-MT2/各 API 的语言支持需集中定义（数据驱动），避免散落硬编码
- **PP-OCRv5 latin 等模型未接入**：语言池含拉丁语言但 v5 不支持，选中 v5 时这些置灰是预期（不新增 v5 latin 模型下载）
