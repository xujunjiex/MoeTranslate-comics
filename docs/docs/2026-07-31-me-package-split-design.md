# B1: `me/` 包拆子包 设计

**目标**：把 `me/` 下 20 个文件、28 个顶层类按职责拆成 4 个子包（apiconfig / model / settings / about），`ManageActivity` 留根。**纯目录移动 + 地址更新，零逻辑改动**。

**范围约束**：不做逻辑重构、不动 `download/` 后端、不改 `ManageActivity` 承载行为、不顺手重构其他包。

## 1. 背景与动机

`me/`（设置和 API 配置界面）混装 5 类职责，找文件靠翻 20 个文件名。目标「各功能模块目录封装清楚」，把 `me/` 拆成职责清晰的一组子包，与 `ui/history/` 的子包模式一致。

**归属依据是「组内自洽性」**（通过全量交叉引用扫描得出）：

| 组 | 内部自洽度 | 依据 |
|----|-----------|------|
| apiconfig | 高 | API 配置 6 页全部依赖 `CustomStorage.kt`（8 类） |
| model | 高 | 两文件互不依赖 |
| about | 高 | FAQPage/OpenSource 依赖 CardAdapter |
| settings | 中 | PersonalizationConfig 依赖 PreferenceWithPreview |

## 2. 目标结构

```
com/moe/starflow/me/
├── ManageActivity.kt                # 容器（留根）：跨 3 组路由，且被非 me 包启动
├── apiconfig/                       # API 配置 + 存储（8 文件）
│   ├── APIConfig.kt                 #   配置入口页（发起 ManageActivity 跳转）
│   ├── OnlineAPI.kt                 #   Niu/Volc/Azure/Baidu/Tencent/DeepL
│   ├── OpenAIText.kt                #   OpenAI 兼容 API
│   ├── CustomTextAPI.kt             #   自定义文本 API
│   ├── CustomPicAPI.kt              #   自定义图片 API
│   ├── BuiltinProviders.kt          #   内置 provider 默认提示词
│   ├── CustomStorage.kt             #   8 类：ConfigurationStorage / OpenAIProviderConfig /
│   │                                #     CustomPicAPIConfig / CustomTextAPIConfig /
│   │                                #     NamedPicAPIConfig / NamedTextAPIConfig /
│   │                                #     BuiltInProviderMod / KeyValuePair（随文件整迁）
│   └── UrlUtils.kt                  #   仅 apiconfig 内使用（已核实）
├── model/                           # 模型管理（2 文件）★用户确认
│   ├── ModelManagementFragment.kt   #   OCR 模型下载页
│   └── NllbModelFragment.kt         #   NLLB 本地翻译模型下载页
├── settings/                        # 设置（4 文件）
│   ├── SettingPageActivity.kt       #   设置容器（入口来自 about/AboutMe）
│   ├── PersonalizationConfig.kt     #   个性化设置
│   ├── TranslationMode.kt           #   翻译模式
│   └── PreferenceWithPreview.kt     #   自定义偏好组件
└── about/                           # 关于/帮助（5 文件）
    ├── AboutMe.kt                   #   关于页（me tab 主页 + 设置入口）
    ├── Developer.kt                 #   开发者选项
    ├── FAQPage.kt                   #   常见问题
    ├── OpenSource.kt                #   开源许可
    └── CardAdapter.kt               #   CardAdapter + CustomCard（FAQ/开源共用）
```

**已确认的决策**：
1. `ManageActivity` 留根——它承载 NLLB 页 + API 配置页 + 开源页（跨 apiconfig/model/about 三组），且被 `translate/TranslateFragment` 启动，归任何子包都不合适。
2. `SettingPageActivity` 进 `settings/`——入口唯一来自 `about/AboutMe`，主体承载设置类页面。
3. **模型管理页留 `me/model/`**，`download/` 保持纯后端（用户确认：下载的「发动机」与「门面」分开，`download/` 不做界面）。
4. 测试随包移动（`CustomStorageTest` → `me/apiconfig/`），与被测类同包，无需 import。

## 3. 调用点同步更新清单（「调用清新」）

拆包后所有跨子包引用都要更新。**特别注意非 import 的全限定名引用**（sed 只改 import 会漏，参见 memory `[[package-move-manifest-check]]`）：

### 3.1 资源/Manifest（全限定名）

**运行时引用**（改错必崩）：

| 文件 | 原值 | 新值 |
|------|------|------|
| `AndroidManifest.xml` | `.me.SettingPageActivity` | `.me.settings.SettingPageActivity` |
| `res/navigation/navigation_manage.xml` | `com.moe.starflow.me.AboutMe` | `com.moe.starflow.me.about.AboutMe` |
| `res/xml/personalization.xml` | `<com.moe.starflow.me.PreferenceWithPreview>` | `<com.moe.starflow.me.settings.PreferenceWithPreview>`（2 处） |

**`tools:context`（设计期引用，构建不报错但会留脏引用/IDE 跳转失效）**：

| 文件 | 原值 | 新值 |
|------|------|------|
| `res/layout/activity_setting_page.xml` | `.me.SettingPageActivity` | `.me.settings.SettingPageActivity` |
| `res/layout/fragment_about_me.xml` | `.me.AboutMe` | `.me.about.AboutMe` |
| `res/layout/fragment_developer.xml` | `.me.Developer` | `.me.about.Developer` |
| `res/layout/fragment_faq_page.xml` | `.me.FAQPage` | `.me.about.FAQPage` |
| `res/layout/fragment_open_source.xml` | `.me.OpenSource` | `.me.about.OpenSource` |
| `res/layout/fragment_translation_mode.xml` | `.me.AboutMe`（原样照搬） | `.me.about.AboutMe` |

> `fragment_model_management.xml` / `fragment_nllb_model.xml` / `activity_manage.xml` 均无 `tools:context`，无需改。

### 3.2 代码 import 更新（非 me 包，7 个文件）

| 文件 | 引用的 me 类 | 新包 |
|------|-------------|------|
| `bridge/TranslateBridge` | BuiltinProviders, ConfigurationStorage | apiconfig |
| `manga/MangaFloatingService` | BuiltinProviders, ConfigurationStorage, OpenAIProviderConfig | apiconfig |
| `translate/FloatingBallService` | BuiltinProviders, ConfigurationStorage | apiconfig |
| `translate/TranslateFragment` | AboutMe→about, ConfigurationStorage→apiconfig, ManageActivity→根（不变） | — |
| `ui/history/MangaViewerActivity` | ConfigurationStorage→apiconfig | — |
| `translationapi/customtranslation/CustomTranslationImage` | CustomPicAPIConfig | apiconfig |
| `translationapi/customtranslation/CustomTranslationText` | CustomTextAPIConfig | apiconfig |

### 3.3 代码内联全限定名（MangaViewerActivity:399-418）

`com.moe.starflow.me.OpenAIProviderConfig` / `com.moe.starflow.me.BuiltinProviders` 为**无 import 的内联全限定名**，改成正规 import（顺手做「调用清新」）。

### 3.4 me/ 内部新增 import（原同包引用，拆后变跨包）

| 文件（新位置） | 新增 import |
|---------------|------------|
| `apiconfig/APIConfig` | ManageActivity（根） |
| `about/AboutMe` | SettingPageActivity（settings） |
| `about/Developer` | ManageActivity（根） |
| 根 `ManageActivity` | OnlineAPI, OpenAIText, CustomTextAPI, CustomPicAPI（apiconfig）+ NllbModelFragment（model）+ OpenSource（about） |
| `settings/SettingPageActivity` | APIConfig（apiconfig）+ Developer, FAQPage（about）+ ModelManagementFragment（model） |

同包引用无需改（BuiltinProviders↔CustomStorage 等）。

### 3.5 测试

`app/src/test/java/com/moe/starflow/me/CustomStorageTest.kt` 随包移动到 `me/apiconfig/`，包声明改 `com.moe.starflow.me.apiconfig`。

## 4. 执行方式（每步可验证）

**每步原则**：每个子包一个 commit。commit 内既要改**被移动文件**（package 声明 + 其跨包引用），也要改**所有引用这些类的文件**（含尚未移动的 me/ 根文件、外部 7 文件、资源/Manifest）——保证每一步独立 `assembleDebug` 通过。

1. **about/ 子包**：git mv AboutMe/Developer/FAQPage/OpenSource/CardAdapter → about/ + 改 package + 更新引用（`navigation_manage.xml` 的 AboutMe、5 个 fragment 布局 `tools:context`、留根 Developer→ManageActivity 的跨包引用等）→ 构建 → commit
2. **model/ 子包**：git mv ModelManagementFragment/NllbModelFragment → model/ + 改 package + 更新引用（留根 ManageActivity 加 NllbModelFragment import、未迁移 SettingPageActivity 加 ModelManagementFragment import）→ 构建 → commit
3. **settings/ 子包**：git mv SettingPageActivity/PersonalizationConfig/TranslationMode/PreferenceWithPreview → settings/ + 改 package + 改 Manifest `.me.SettingPageActivity` + 改 `personalization.xml` + 改 `activity_setting_page.xml` 的 `tools:context` + 更新引用（about/AboutMe 的 SettingPageActivity import 改包路径等）→ 构建 → commit
4. **apiconfig/ 子包**：git mv APIConfig/OnlineAPI/OpenAIText/CustomTextAPI/CustomPicAPI/BuiltinProviders/CustomStorage/UrlUtils → apiconfig/ + 改 package + 更新外部 7 文件 import + MangaViewerActivity 内联 FQN 转 import + 移测试 CustomStorageTest → 构建 → commit
5. **最终验证**：`assembleDebug` + `testDebugUnitTest`（全量）→ 确认全绿

> 执行顺序即上表（about → model → settings → apiconfig）。每步移动的文件数固定，引用更新清单见第 3 节；同 commit 内补全所有引用，避免中间态无法构建。

## 5. 非目标（明确不做）

- 不改任何逻辑（下载流程、状态机、UI 行为、字符串）
- 不动 `download/` 后端包
- 不重构 `ManageActivity` / `SettingPageActivity` 的承载逻辑
- 不顺手拆分巨型文件（`MangaFloatingService` 等另立项）
- 不重命名类（类名保持原样，只换包）

## 6. 验证标准

- 每个子包 commit 后 `./gradlew assembleDebug` 通过
- 最终 `testDebugUnitTest` 全量通过（CustomStorageTest 已随包移动）
- 设备实测：拆包是纯地址变更，功能零影响；仍建议装机后过一遍「设置→关于→各入口」确认跳转正常
