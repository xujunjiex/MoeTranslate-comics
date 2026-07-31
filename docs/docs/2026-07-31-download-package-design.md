# 下载流水线收拢到 download/ 包 — 设计文档

日期：2026-07-31

## 背景与目标

当前模型下载流水线横跨 4 个包（`data/`、`service/`、`manga/`、`service/helpers/`），同一套功能被拆散，新增模型/改下载逻辑需要跨目录找文件。本次把下载流水线**纯搬迁**到一个独立包 `download/`，统一包名 `com.moe.starflow.download`。

**范围约束：零逻辑改动，只做 `git mv` + 更新 import + 构建验证。** 旧 Manager 残留、utils 杂物袋等结构问题本次**只记录不改**，留给后续结构优化。

## 搬迁映射

| 原位置 | → `download/` | 原包名 |
|--------|---------------|--------|
| `data/DownloadState.kt` | `DownloadState.kt` | `com.moe.starflow.data` |
| `data/ModelInfo.kt` | `ModelInfo.kt` | `com.moe.starflow.data` |
| `data/ModelDownloadRepository.kt` | `ModelDownloadRepository.kt` | `com.moe.starflow.data` |
| `manga/ModelKey.kt` | `ModelKey.kt` | `com.moe.starflow.manga` |
| `service/ModelDownloadService.kt` | `ModelDownloadService.kt` | `com.moe.starflow.service` |
| `manga/ModelDownloadManager.kt` | `ModelDownloadManager.kt` | `com.moe.starflow.manga` |
| `service/helpers/ChecksumHelper.kt`（含 `VerifyResult`） | `ChecksumHelper.kt` | `com.moe.starflow.service.helpers` |

> 注：`ModelDownloadService.kt` 的 `helpers/` 目录搬迁后留空；`DownloadState.kt` 还包含 `DownloadSnapshot`（Repository 内部结构）。

## 需要改动的文件

### 被迁文件内部
- `package` 声明改为 `com.moe.starflow.download`
- 同包引用（如 Repository 引用 ModelKey/DownloadState/ModelInfo）的 import 删除或改为同包

### 引用方（按包）
- `me/`：`ModelManagementFragment`、`NllbModelFragment`、`ManageActivity`
- `translate/`：`TranslateFragment`
- `data/`：`TranslationCacheManager` 不引用被迁文件（已查证），无改动
- `service/`：无其他（Service 本身迁走）
- 测试：`app/src/test/.../ModelDownloadPauseProgressTest.kt`、`ModelInfoTest.kt`

### 文档
- `CLAUDE.md` 中引用旧包路径的段落
- `tools/model-download-system.md` 关键文件表

## 验证
- `./gradlew assembleDebug` 构建通过
- 单测 `ModelDownloadPauseProgressTest` / `ModelInfoTest` 跑通
- `git status` 确认 7 个文件 moved + 无遗漏

## 附带收集的结构问题（本次不改，后续优化）

1. **旧下载 Manager 残留**：`manga/` 下 `RTDetrModelManager`/`PPOcrModelManager`/`MangaOcrDownloadManager`/`DBNetModelManager` 的下载方法已迁走（55a820f），但文件仍与引擎的文件检查逻辑混杂
2. **`data/` 职责**：迁走 DownloadState/ModelInfo 后剩 Room 数据库，更纯粹
3. **`service/` 迁空**：ModelDownloadService + helpers 移走后目录空，后续处理
4. **`utils/` 杂物袋**：`TranslationStatusOverlay`（弹窗）、`PerceptualHash`（图像）等按领域拆分
5. **`manga/` 下模型管理代码**：`PPOcrModelManager` 等含"文件是否存在/大小"检查，与漫画引擎逻辑混合

## 明确的非目标
- 不删除/重命名旧 Manager（含废弃下载方法）
- 不拆分 `utils/`、不重组 `data/`
- 不做任何逻辑重构（进度、校验、队列行为不变）
