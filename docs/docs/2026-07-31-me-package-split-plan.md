# B1: me/ 包拆子包 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `me/` 下 20 个文件按职责拆成 4 个子包（apiconfig / model / settings / about），`ManageActivity` 留根，零逻辑改动。

**Architecture:** 纯目录移动 + 地址更新。每个子包一个 commit，按「about → model → settings → apiconfig」顺序执行（依赖顺序：被引用的类先挪、引用方同步补 import）。每个 commit 后 `assembleDebug` 验证可编译。

**Tech Stack:** Kotlin / Android（Manifest + XML 含全限定名引用）、git mv。

**设计文档:** `docs/docs/2026-07-31-me-package-split-design.md`（执行前先读，含完整引用清单）。

## Global Constraints

- **零逻辑改动**：只改 `package` 声明、import、资源/Manifest 里的类路径。不重命名类、不改方法、不改字符串。
- **每任务独立可编译**：git mv + package 更新后，必须同步更新**所有引用该类的地方**（含尚未移动的 me/ 根文件、外部文件、资源/Manifest），再构建。
- **⚠️ 全限定名陷阱**：除了 `import`，还有 3 类非 import 引用会漏（memory `[[package-move-manifest-check]]`）：Manifest 组件名、XML 里的 `android:name`/`tools:context`/自定义 View 标签、Kotlin 内联 `com.moe.starflow.me.X`。
- **构建命令**：`./gradlew assembleDebug`（工作目录 = 仓库根）。
- **测试命令**（PowerShell + 干净 PATH，Git Bash 直跑 worker 崩溃）：见 Task 5。
- 主目录根：`app/src/main/java/com/moe/starflow/`，下文简写 `ME=`。

---

### Task 1: about/ 子包（5 文件）

**Files:**
- Move: `app/src/main/java/com/moe/starflow/me/{AboutMe,Developer,FAQPage,OpenSource,CardAdapter}.kt` → `me/about/`
- Modify（补/改 import）: `me/AboutMe.kt`(迁后), `me/Developer.kt`(迁后), `me/ManageActivity.kt`, `me/SettingPageActivity.kt`, `translate/TranslateFragment.kt`
- Modify（资源）: `res/navigation/navigation_manage.xml`, 5 个布局 `tools:context`

**Interfaces:**
- Consumes: 现有交叉引用（已验证）：`AboutMe→SettingPageActivity`、`Developer→ManageActivity`、`ManageActivity→OpenSource`、`SettingPageActivity→Developer/FAQPage`、`TranslateFragment→AboutMe`、`FAQPage/OpenSource→CardAdapter`（同组内，无需 import）
- Produces: `com.moe.starflow.me.about.{AboutMe,Developer,FAQPage,OpenSource,CardAdapter}` 五个类的新包路径

- [ ] **Step 1: git mv 5 个文件并改 package**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics/app/src/main/java/com/moe/starflow"
mkdir -p about
git mv AboutMe.kt about/AboutMe.kt
git mv Developer.kt about/Developer.kt
git mv FAQPage.kt about/FAQPage.kt
git mv OpenSource.kt about/OpenSource.kt
git mv CardAdapter.kt about/CardAdapter.kt
sed -i 's/^package com\.moe\.starflow\.me$/package com.moe.starflow.me.about/' about/AboutMe.kt about/Developer.kt about/FAQPage.kt about/OpenSource.kt about/CardAdapter.kt
```

- [ ] **Step 2: 给迁出文件的跨包引用补 import**

`about/AboutMe.kt` 引用 `SettingPageActivity`（仍在根）→ 在 import 区加：
```kotlin
import com.moe.starflow.me.SettingPageActivity
```

`about/Developer.kt` 引用 `ManageActivity`（仍在根）→ 加：
```kotlin
import com.moe.starflow.me.ManageActivity
```

`about/FAQPage.kt` / `about/OpenSource.kt` 引用 `CardAdapter`（同组 about/）→ **不需要** import。

- [ ] **Step 3: 更新仍在 me/ 根的引用方**

`me/ManageActivity.kt` 引用 `OpenSource` → 加：
```kotlin
import com.moe.starflow.me.about.OpenSource
```

`me/SettingPageActivity.kt`（尚未迁移）引用 `Developer`、`FAQPage` → 加：
```kotlin
import com.moe.starflow.me.about.Developer
import com.moe.starflow.me.about.FAQPage
```

- [ ] **Step 4: 更新外部引用**

`translate/TranslateFragment.kt:47` 的 import 改包路径：
```bash
sed -i 's|import com\.moe\.starflow\.me\.AboutMe|import com.moe.starflow.me.about.AboutMe|' app/src/main/java/com/moe/starflow/translate/TranslateFragment.kt
```

`res/navigation/navigation_manage.xml:41`：
```xml
<!-- 改前 --> android:name="com.moe.starflow.me.AboutMe"
<!-- 改后 --> android:name="com.moe.starflow.me.about.AboutMe"
```

5 个布局 `tools:context`（用 sed 逐个替换，目标都是 about 子包）：
```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics/app/src/main/res/layout"
sed -i 's|tools:context="\.me\.AboutMe"|tools:context=".me.about.AboutMe"|' fragment_about_me.xml fragment_translation_mode.xml
sed -i 's|tools:context="\.me\.Developer"|tools:context=".me.about.Developer"|' fragment_developer.xml
sed -i 's|tools:context="\.me\.FAQPage"|tools:context=".me.about.FAQPage"|' fragment_faq_page.xml
sed -i 's|tools:context="\.me\.OpenSource"|tools:context=".me.about.OpenSource"|' fragment_open_source.xml
```

- [ ] **Step 5: 构建验证**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics" && ./gradlew assembleDebug -q
```
Expected: 退出码 0（BUILD SUCCESSFUL）。

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "refactor: me/ 拆出 about/ 子包（关于/FAQ/开发者/开源）"
```

---

### Task 2: model/ 子包（2 文件）

**Files:**
- Move: `me/{ModelManagementFragment,NllbModelFragment}.kt` → `me/model/`
- Modify（补 import）: `me/ManageActivity.kt`, `me/SettingPageActivity.kt`

**Interfaces:**
- Consumes: `ManageActivity→NllbModelFragment`、`SettingPageActivity→ModelManagementFragment`
- Produces: `com.moe.starflow.me.model.{ModelManagementFragment,NllbModelFragment}`

- [ ] **Step 1: git mv + 改 package**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics/app/src/main/java/com/moe/starflow"
mkdir -p model
git mv ModelManagementFragment.kt model/ModelManagementFragment.kt
git mv NllbModelFragment.kt model/NllbModelFragment.kt
sed -i 's/^package com\.moe\.starflow\.me$/package com.moe.starflow.me.model/' model/ModelManagementFragment.kt model/NllbModelFragment.kt
```

- [ ] **Step 2: 更新引用方**

`me/ManageActivity.kt` 加：
```kotlin
import com.moe.starflow.me.model.NllbModelFragment
```

`me/SettingPageActivity.kt`（尚未迁移）加：
```kotlin
import com.moe.starflow.me.model.ModelManagementFragment
```

- [ ] **Step 3: 构建验证**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics" && ./gradlew assembleDebug -q
```
Expected: 退出码 0。

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "refactor: me/ 拆出 model/ 子包（模型管理页）"
```

---

### Task 3: settings/ 子包（4 文件）

**Files:**
- Move: `me/{SettingPageActivity,PersonalizationConfig,TranslationMode,PreferenceWithPreview}.kt` → `me/settings/`
- Modify（补/改 import）: `me/settings/SettingPageActivity.kt`(迁后), `me/about/AboutMe.kt`
- Modify（资源）: `AndroidManifest.xml`, `res/layout/activity_setting_page.xml`, `res/xml/personalization.xml`

**Interfaces:**
- Consumes: `AboutMe→SettingPageActivity`（Task 1 加的 import 需改路径）、`SettingPageActivity→APIConfig`（根，待 Task 4）、`SettingPageActivity→Developer/FAQPage`（Task 1 已 import，路径不变）、`SettingPageActivity→ModelManagementFragment`（Task 2 已 import，路径不变）
- Produces: `com.moe.starflow.me.settings.{SettingPageActivity,PersonalizationConfig,TranslationMode,PreferenceWithPreview}`

- [ ] **Step 1: git mv + 改 package**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics/app/src/main/java/com/moe/starflow"
mkdir -p settings
git mv SettingPageActivity.kt settings/SettingPageActivity.kt
git mv PersonalizationConfig.kt settings/PersonalizationConfig.kt
git mv TranslationMode.kt settings/TranslationMode.kt
git mv PreferenceWithPreview.kt settings/PreferenceWithPreview.kt
sed -i 's/^package com\.moe\.starflow\.me$/package com.moe.starflow.me.settings/' settings/SettingPageActivity.kt settings/PersonalizationConfig.kt settings/TranslationMode.kt settings/PreferenceWithPreview.kt
```

- [ ] **Step 2: 给迁出的 SettingPageActivity 补跨包 import**

`me/settings/SettingPageActivity.kt` 加（`PersonalizationConfig`/`TranslationMode` 同组内不需 import）：
```kotlin
import com.moe.starflow.me.APIConfig
```
> Task 1 加的 `import com.moe.starflow.me.about.Developer` / `.about.FAQPage`、Task 2 加的 `import com.moe.starflow.me.model.ModelManagementFragment` 都仍有效，不动。

- [ ] **Step 3: 更新资源引用**

`AndroidManifest.xml`（`.me.SettingPageActivity` → `.me.settings.SettingPageActivity`）：
```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics"
sed -i 's|\.me\.SettingPageActivity|.me.settings.SettingPageActivity|' app/src/main/AndroidManifest.xml
```

`res/layout/activity_setting_page.xml:26` 的 `tools:context`：
```bash
sed -i 's|tools:context="\.me\.SettingPageActivity"|tools:context=".me.settings.SettingPageActivity"|' app/src/main/res/layout/activity_setting_page.xml
```

`res/xml/personalization.xml`（2 处 `<com.moe.starflow.me.PreferenceWithPreview`）：
```bash
sed -i 's|com\.moe\.starflow\.me\.PreferenceWithPreview|com.moe.starflow.me.settings.PreferenceWithPreview|g' app/src/main/res/xml/personalization.xml
```

- [ ] **Step 4: 更新 AboutMe 的 import 路径**

`me/about/AboutMe.kt` 中 Task 1 加的 import 从根改到 settings 子包：
```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics"
sed -i 's|import com\.moe\.starflow\.me\.SettingPageActivity|import com.moe.starflow.me.settings.SettingPageActivity|' app/src/main/java/com/moe/starflow/me/about/AboutMe.kt
```

- [ ] **Step 5: 构建验证**

```bash
./gradlew assembleDebug -q
```
Expected: 退出码 0。⚠️ 若失败，重点检查 `SettingPageActivity` 引用 `APIConfig`（现在 import 的是根路径，Task 4 才改）是否编译通过。

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "refactor: me/ 拆出 settings/ 子包（设置/个性化/翻译模式/偏好组件）"
```

---

### Task 4: apiconfig/ 子包（8 文件 + 测试）

**Files:**
- Move: `me/{APIConfig,OnlineAPI,OpenAIText,CustomTextAPI,CustomPicAPI,BuiltinProviders,CustomStorage,UrlUtils}.kt` → `me/apiconfig/`
- Move (test): `app/src/test/java/com/moe/starflow/me/CustomStorageTest.kt` → `me/apiconfig/`
- Modify（import）: `me/ManageActivity.kt`, `me/settings/SettingPageActivity.kt`, `bridge/TranslateBridge.kt`, `manga/MangaFloatingService.kt`, `translate/FloatingBallService.kt`, `translate/TranslateFragment.kt`, `ui/history/MangaViewerActivity.kt`, `translationapi/customtranslation/CustomTranslationImage.kt`, `translationapi/customtranslation/CustomTranslationText.kt`

**Interfaces:**
- Consumes: `ManageActivity→{OnlineAPI,OpenAIText,CustomTextAPI,CustomPicAPI}`、`SettingPageActivity→APIConfig`（Task 3 加的根路径 import 需改 apiconfig）、`APIConfig→ManageActivity`、外部 7 文件 import、`MangaViewerActivity` 内联全限定名 4 处
- Produces: `com.moe.starflow.me.apiconfig.{APIConfig,OnlineAPI,OpenAIText,CustomTextAPI,CustomPicAPI,BuiltinProviders,CustomStorage,UrlUtils}`

- [ ] **Step 1: git mv 8 个文件 + 测试，改 package**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics/app/src/main/java/com/moe/starflow"
mkdir -p apiconfig
git mv APIConfig.kt apiconfig/APIConfig.kt
git mv OnlineAPI.kt apiconfig/OnlineAPI.kt
git mv OpenAIText.kt apiconfig/OpenAIText.kt
git mv CustomTextAPI.kt apiconfig/CustomTextAPI.kt
git mv CustomPicAPI.kt apiconfig/CustomPicAPI.kt
git mv BuiltinProviders.kt apiconfig/BuiltinProviders.kt
git mv CustomStorage.kt apiconfig/CustomStorage.kt
git mv UrlUtils.kt apiconfig/UrlUtils.kt
sed -i 's/^package com\.moe\.starflow\.me$/package com.moe.starflow.me.apiconfig/' apiconfig/APIConfig.kt apiconfig/OnlineAPI.kt apiconfig/OpenAIText.kt apiconfig/CustomTextAPI.kt apiconfig/CustomPicAPI.kt apiconfig/BuiltinProviders.kt apiconfig/CustomStorage.kt apiconfig/UrlUtils.kt

# 测试随包移动（CustomStorage.kt 8 个类同包引用，无需 import）
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics/app/src/test/java/com/moe/starflow/me"
mkdir -p apiconfig
git mv CustomStorageTest.kt apiconfig/CustomStorageTest.kt
sed -i 's/^package com\.moe\.starflow\.me$/package com.moe.starflow.me.apiconfig/' apiconfig/CustomStorageTest.kt
```

- [ ] **Step 2: 给迁出的 APIConfig 补跨包 import**

`me/apiconfig/APIConfig.kt` 引用 `ManageActivity`（根）→ 加：
```kotlin
import com.moe.starflow.me.ManageActivity
```
（其余 7 个 apiconfig 文件的同包引用——`OpenAIText→ConfigurationStorage` 等——同组内，无需 import。）

- [ ] **Step 3: 更新 me/ 内部引用方**

`me/ManageActivity.kt` 引用 `OnlineAPI/OpenAIText/CustomTextAPI/CustomPicAPI` → 加 4 个 import：
```kotlin
import com.moe.starflow.me.apiconfig.OnlineAPI
import com.moe.starflow.me.apiconfig.OpenAIText
import com.moe.starflow.me.apiconfig.CustomTextAPI
import com.moe.starflow.me.apiconfig.CustomPicAPI
```

`me/settings/SettingPageActivity.kt` 中 Task 3 加的 `import com.moe.starflow.me.APIConfig` → 改路径：
```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics"
sed -i 's|import com\.moe\.starflow\.me\.APIConfig|import com.moe.starflow.me.apiconfig.APIConfig|' app/src/main/java/com/moe/starflow/me/settings/SettingPageActivity.kt
```

- [ ] **Step 4: 更新外部 7 个文件 import + 内联全限定名**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics/app/src/main/java/com/moe/starflow"
# bridge/TranslateBridge.kt（3 条：BuiltinProviders / ConfigurationStorage / ConfigurationStorage.loadTextConfig）
sed -i 's|com\.moe\.starflow\.me\.BuiltinProviders|com.moe.starflow.me.apiconfig.BuiltinProviders|g; s|com\.moe\.starflow\.me\.ConfigurationStorage|com.moe.starflow.me.apiconfig.ConfigurationStorage|g' bridge/TranslateBridge.kt
# manga/MangaFloatingService.kt（3 条）
sed -i 's|com\.moe\.starflow\.me\.BuiltinProviders|com.moe.starflow.me.apiconfig.BuiltinProviders|g; s|com\.moe\.starflow\.me\.ConfigurationStorage|com.moe.starflow.me.apiconfig.ConfigurationStorage|g; s|com\.moe\.starflow\.me\.OpenAIProviderConfig|com.moe.starflow.me.apiconfig.OpenAIProviderConfig|g' manga/MangaFloatingService.kt
# translate/FloatingBallService.kt（2 条）
sed -i 's|com\.moe\.starflow\.me\.BuiltinProviders|com.moe.starflow.me.apiconfig.BuiltinProviders|g; s|com\.moe\.starflow\.me\.ConfigurationStorage|com.moe.starflow.me.apiconfig.ConfigurationStorage|g' translate/FloatingBallService.kt
# translate/TranslateFragment.kt（仅 ConfigurationStorage；AboutMe/ManageActivity 已在 Task 1/3 处理）
sed -i 's|com\.moe\.starflow\.me\.ConfigurationStorage|com.moe.starflow.me.apiconfig.ConfigurationStorage|g' translate/TranslateFragment.kt
# ui/history/MangaViewerActivity.kt：ConfigurationStorage import 改路径 + 4 处内联全限定名转裸类名
sed -i 's|com\.moe\.starflow\.me\.ConfigurationStorage|com.moe.starflow.me.apiconfig.ConfigurationStorage|g' ui/history/MangaViewerActivity.kt
sed -i 's|com\.moe\.starflow\.me\.OpenAIProviderConfig\.|OpenAIProviderConfig.|g; s|com\.moe\.starflow\.me\.BuiltinProviders\.|BuiltinProviders.|g' ui/history/MangaViewerActivity.kt

# translationapi/customtranslation（各 1 条）
sed -i 's|com\.moe\.starflow\.me\.CustomPicAPIConfig|com.moe.starflow.me.apiconfig.CustomPicAPIConfig|g' ../../../translationapi/customtranslation/CustomTranslationImage.kt
sed -i 's|com\.moe\.starflow\.me\.CustomTextAPIConfig|com.moe.starflow.me.apiconfig.CustomTextAPIConfig|g' ../../../translationapi/customtranslation/CustomTranslationText.kt
```

然后给 `MangaViewerActivity.kt` 的 import 区加 2 行（替换内联全限定名的类）：
```kotlin
import com.moe.starflow.me.apiconfig.OpenAIProviderConfig
import com.moe.starflow.me.apiconfig.BuiltinProviders
```

- [ ] **Step 5: 构建验证**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics" && ./gradlew assembleDebug -q
```
Expected: 退出码 0。

- [ ] **Step 6: 残留检查（应只剩 ManageActivity 引用根包）**

```bash
grep -rn "com\.moe\.starflow\.me\." app/src/main --include="*.kt" --include="*.xml" | grep -v "apiconfig\|settings\|about\|model\|starflow/me/ManageActivity\|\.me\.starflow\|me\.ManageActivity"
```
Expected: 空（或只有 `com.moe.starflow.me.ManageActivity`）。

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "refactor: me/ 拆出 apiconfig/ 子包（API 配置 + 存储），测试随包移动"
```

---

### Task 5: 最终验证

**Files:** 无改动，仅验证。

- [ ] **Step 1: 全量单测**

```powershell
# PowerShell 运行（Git Bash 直跑 worker 崩溃，必须干净 PATH + --no-daemon）
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
cd 'D:\xjj20\Desktop\fyapp\MoeTranslate-comics'
.\gradlew.bat --no-daemon :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`（含随包移动的 `CustomStorageTest`）。

- [ ] **Step 2: 结构确认**

```bash
ls app/src/main/java/com/moe/starflow/me/          # 应只剩 ManageActivity.kt + 4 个目录
ls app/src/main/java/com/moe/starflow/me/about/    # 5 个文件
ls app/src/main/java/com/moe/starflow/me/model/    # 2 个文件
ls app/src/main/java/com/moe/starflow/me/settings/ # 4 个文件
ls app/src/main/java/com/moe/starflow/me/apiconfig/# 8 个文件
```
Expected: `me/` 根只有 `ManageActivity.kt`，4 个子目录文件数 = 5/2/4/8。

- [ ] **Step 3: 提交收尾（若 Step 2 有未提交改动）**

```bash
git status --short   # 应为空（前 4 个 Task 已各自提交）
```

**验证说明（交付时转告用户）**：纯地址变更，功能零影响。装机后建议过一遍「设置 → 关于 → 翻译模式 / API 配置 / 个性化 / 模型管理 / FAQ / 开发者选项」各入口跳转正常；NLLB 下载页、OCR 模型管理页打开正常。
