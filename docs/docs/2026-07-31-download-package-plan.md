# 下载流水线收拢到 download/ 包 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 7 个下载流水线文件从 4 个包纯搬迁到 `com.moe.starflow.download`，零逻辑改动。

**Architecture:** `git mv` 到 `app/src/main/java/com/moe/starflow/download/`，改 package 声明，更新 8 个外部引用方的 import，构建 + 单测验证，最后更新文档。

**Tech Stack:** Android/Kotlin, Gradle。

## Global Constraints

- **零逻辑改动**：只做文件移动 + import 更新；不改任何方法体、状态机、校验逻辑、队列行为
- 包名统一 `com.moe.starflow.download`
- 不删除/不重命名旧 Manager（`RTDetrModelManager` 等），不拆分 utils/data
- 所有日志用 `LogCollector`
- 单测在本机必须用 PowerShell + 干净 PATH + `--no-daemon` + 指定测试类（见 CLAUDE.md 构建命令节）

---

### Task 1: git mv 7 个文件到 download/

**Files:**
- Create: `app/src/main/java/com/moe/starflow/download/`（git mv 自动创建）
- Move: 7 个文件（见步骤）

**Interfaces:**
- Consumes: 无
- Produces: `download/` 目录下 7 个文件（此时 package 仍是旧包名，**不编译**）

- [ ] **Step 1: git mv 全部 7 个文件**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics"
mkdir -p app/src/main/java/com/moe/starflow/download
git mv app/src/main/java/com/moe/starflow/data/DownloadState.kt      app/src/main/java/com/moe/starflow/download/
git mv app/src/main/java/com/moe/starflow/data/ModelInfo.kt          app/src/main/java/com/moe/starflow/download/
git mv app/src/main/java/com/moe/starflow/data/ModelDownloadRepository.kt app/src/main/java/com/moe/starflow/download/
git mv app/src/main/java/com/moe/starflow/manga/ModelKey.kt          app/src/main/java/com/moe/starflow/download/
git mv app/src/main/java/com/moe/starflow/service/ModelDownloadService.kt app/src/main/java/com/moe/starflow/download/
git mv app/src/main/java/com/moe/starflow/manga/ModelDownloadManager.kt app/src/main/java/com/moe/starflow/download/
git mv app/src/main/java/com/moe/starflow/service/helpers/ChecksumHelper.kt app/src/main/java/com/moe/starflow/download/
```

- [ ] **Step 2: 验证移动结果**

```bash
git status --short | grep "^R"
```
Expected: 7 行 `R`（renamed）；`download/` 下有 7 个文件。

---

### Task 2: 更新 7 个文件的 package 声明 + 清理冗余内部 import

**Files:**
- Modify: 上面 7 个被迁文件

**Interfaces:**
- Consumes: Task 1 的文件位置
- Produces: 7 个文件包名统一 `com.moe.starflow.download`，内部不再有跨包 import（同包引用删除）

- [ ] **Step 1: 改 package 声明（7 个文件）**

每个文件第一行 `package` 改为：
```kotlin
package com.moe.starflow.download
```
文件对应：
- `download/DownloadState.kt`、`ModelInfo.kt`、`ModelKey.kt`、`ModelDownloadRepository.kt`、`ModelDownloadService.kt`、`ModelDownloadManager.kt`、`ChecksumHelper.kt`

- [ ] **Step 2: 删除被迁文件内的冗余同包 import**

`ModelInfo.kt`：删除行 `import com.moe.starflow.manga.ModelKey`
`ModelDownloadRepository.kt`：删除行 `import com.moe.starflow.manga.ModelKey`
`ModelDownloadService.kt`：删除以下 5 行
```kotlin
import com.moe.starflow.data.DownloadState
import com.moe.starflow.data.ModelDownloadRepository
import com.moe.starflow.manga.ModelKey
import com.moe.starflow.service.helpers.ChecksumHelper
import com.moe.starflow.service.helpers.VerifyResult
```
其余文件（DownloadState/ModelKey/ModelDownloadManager/ChecksumHelper）无同包引用，跳过。

- [ ] **Step 3: 验证**

```bash
grep -rn "package com.moe.starflow" app/src/main/java/com/moe/starflow/download/
```
Expected: 7 行都是 `package com.moe.starflow.download`；且 `download/` 内无 `import com.moe.starflow.data`/`manga`/`service` 残留。

---

### Task 3: 更新 8 个外部引用方的 import

**Files:**
- Modify: 8 个文件（见步骤）

**Interfaces:**
- Consumes: Task 2 的包名
- Produces: 全部外部引用指向 `com.moe.starflow.download.*`

- [ ] **Step 1: 对 8 个引用方统一替换旧 import**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics"
FILES="
app/src/main/java/com/moe/starflow/StarFlowApplication.kt
app/src/main/java/com/moe/starflow/me/AboutMe.kt
app/src/main/java/com/moe/starflow/me/ManageActivity.kt
app/src/main/java/com/moe/starflow/me/ModelManagementFragment.kt
app/src/main/java/com/moe/starflow/me/NllbModelFragment.kt
app/src/main/java/com/moe/starflow/translate/TranslateFragment.kt
app/src/test/java/com/moe/starflow/data/ModelDownloadPauseProgressTest.kt
app/src/test/java/com/moe/starflow/data/ModelDownloadRepositoryTest.kt
"
for f in $FILES; do
  sed -i \
    -e 's/import com\.moe\.starflow\.data\.ModelDownloadRepository/import com.moe.starflow.download.ModelDownloadRepository/' \
    -e 's/import com\.moe\.starflow\.data\.DownloadState/import com.moe.starflow.download.DownloadState/' \
    -e 's/import com\.moe\.starflow\.data\.ModelInfo/import com.moe.starflow.download.ModelInfo/' \
    -e 's/import com\.moe\.starflow\.manga\.ModelKey/import com.moe.starflow.download.ModelKey/' \
    -e 's/import com\.moe\.starflow\.manga\.ModelDownloadManager/import com.moe.starflow.download.ModelDownloadManager/' \
    -e 's/import com\.moe\.starflow\.service\.ModelDownloadService/import com.moe.starflow.download.ModelDownloadService/' \
    -e 's/import com\.moe\.starflow\.service\.helpers\.ChecksumHelper/import com.moe.starflow.download.ChecksumHelper/' \
    -e 's/import com\.moe\.starflow\.service\.helpers\.VerifyResult/import com.moe.starflow.download.VerifyResult/' \
    "$f"
done
```

- [ ] **Step 2: 验证无残留旧 import**

```bash
grep -rnE "import com\.moe\.starflow\.(data\.(ModelDownloadRepository|DownloadState|ModelInfo)|manga\.(ModelKey|ModelDownloadManager)|service\.(ModelDownloadService|helpers\.(ChecksumHelper|VerifyResult)))" app/src/ | grep -v download/
```
Expected: 无输出。

- [ ] **Step 3: 提交到这一步**

```bash
git add -A app/src/main/java app/src/test
git commit -m "refactor: 下载流水线收拢到 download/ 包（git mv + import 更新）"
```

---

### Task 4: 构建 + 单测验证

**Files:** 无（验证）

**Interfaces:**
- Consumes: Task 2/3 完成
- Produces: 可编译、单测通过

- [ ] **Step 1: 构建**

```bash
cd "D:/xjj20/Desktop/fyapp/MoeTranslate-comics" && ./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`。若报错（通常是漏改的 import），修复后重跑（可能有个别文件引用了旧包名，`grep -rn "com.moe.starflow.data.DownloadState\|com.moe.starflow.manga.ModelKey" app/src` 排查）。

- [ ] **Step 2: 跑单测（必须 PowerShell + 干净 PATH）**

```powershell
Set-Location 'D:\xjj20\Desktop\fyapp\MoeTranslate-comics'
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:PATH='C:\Windows\System32;C:\Windows;'+$env:JAVA_HOME+'\bin'
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests com.moe.starflow.data.ModelDownloadPauseProgressTest --tests com.moe.starflow.data.ModelInfoTest
```
Expected: `BUILD SUCCESSFUL`，5/5 + 4/4 通过。

- [ ] **Step 3: 确认无遗漏**

```bash
git status --short
```
Expected: 无未提交改动（除文档任务）。

---

### Task 5: 更新文档 + 最终提交

**Files:**
- Modify: `CLAUDE.md`、`tools/model-download-system.md`（gitignore，不提交）

**Interfaces:**
- Consumes: 新的包名
- Produces: 文档路径与代码一致

- [ ] **Step 1: 更新 CLAUDE.md 中的旧包路径**

```bash
grep -n "data/ModelDownloadRepository\|manga/ModelDownloadManager\|service/ModelDownloadService\|data/DownloadState\|service/helpers/ChecksumHelper\|manga/ModelKey" CLAUDE.md
```
对每个命中行改为 `download/<同名>`。重点：架构节的下载管理器树、模型管理节。

- [ ] **Step 2: 更新 tools/model-download-system.md 关键文件表**

`tools/model-download-system.md` 第 8 节「关键文件与配置」表，路径改为 `download/*`。

- [ ] **Step 3: 提交 CLAUDE.md**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md 下载流水线路径更新为 download/ 包"
```

- [ ] **Step 4: 最终验证**

```bash
git status --short
```
Expected: 仅 `tools/`（gitignore）未跟踪；工作区干净。
