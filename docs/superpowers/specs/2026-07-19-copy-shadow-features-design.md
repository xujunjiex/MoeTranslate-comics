# 翻译文本复制 + 阴影开关 + 历史增强 设计文档

> 日期：2026-07-19

---

## 1. 文字阴影开关（仅游戏翻译）

### 现状
`TranslationResultView.kt` 第 78 行硬编码 `setShadowLayer(2f, 1f, 1f, Color.BLACK)`。
漫画翻译结果 (`OverlayRenderer` → `VerticalTextRenderer`) 无阴影，漫画调试覆盖层的 `setShadowLayer` 是调试标签文字用的，不是用户翻译结果。

### 方案
- `PersonalizationConfig` (个性化设置) 新增 `SwitchPreference`：key `text_shadow_enabled`，默认 `true`
- `FloatingBallService.styleKeys` 集合加入 `text_shadow_enabled`，prefs 变化时调 `translationResultView.applyStyle()`
- `TranslationResultView.applyStyle()` 读取 prefs：
  - 开启：`setShadowLayer(2f, 1f, 1f, Color.BLACK)`
  - 关闭：`setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)`（Android 没有 clearShadowLayer API，用 0 参数等效）

### 涉及文件
- `TranslationResultView.kt` — applyStyle() 读 prefs 控阴影
- `FloatingBallService.kt` — styleKeys 加 key
- `PersonalizationConfig.kt` — 新增 SwitchPreference
- `res/xml/personalization.xml` — 新增控件
- `res/values/strings.xml` / `values-zh` / `values-en` — 新字符串

---

## 2. 颜色恢复默认按钮

### 方案
- 字体颜色 (`result_view_font_color`) 和背景颜色 (`result_view_background_color`) 旁各加「↺」小按钮
- 点击恢复默认值 + 更新 prefs + 通知 service 实时生效
- 默认值：
  - 字体色：`-1516335`（`Color.parseColor("#FFE8E8E8")`）
  - 背景色：`-649384925`（`Color.argb(60, 60, 60, 60)`）

### 涉及文件
- `PersonalizationConfig.kt` — 恢复按钮逻辑
- `res/xml/personalization.xml` — 按钮布局

---

## 3. 快速复制翻译文本

### 3a — 游戏翻译复制按钮

#### 布局变更
`TranslationResultView` 左下角新增复制按钮：
- 位置：左下角（Gravity.START | Gravity.BOTTOM）
- 图标：使用 `R.drawable.ic_copy`（需新建矢量图标，两个重叠的文档样式）
- 颜色：半透明灰白 `Color.argb(160, 80, 80, 80)`，与锁/关闭按钮统一
- 尺寸：16dp（与 retranslateButton 一致）
- **始终显示**（不依赖缓存命中）

#### ⚡ 缓存标志迁移
- 缓存命中时在译文文本前加 `⚡` 前缀
- `TextView` 的 `text` 设置处处理：缓存命中 → `"⚡$text"`，否则 → `text`
- 移除独立 `cacheIndicator` View（左下角 TextView），清理相关 addView 和 visibility 代码

#### 复制逻辑
- 点击复制按钮 → 复制当前 textView 的全部内容（去除 ⚡ 前缀）到系统剪贴板
- Toast 提示"已复制"

### 3b — 漫画翻译复制模式

#### 按钮布局
翻译结果覆盖层右下角「📋」按钮**始终显示**在有翻译结果时。进入复制模式后，右下角展开为三按钮：
1. **📋 复制模式切换** — 进入/退出复制模式
2. **🔄 原文/译文切换** — 切换当前复制的文本类型（默认译文）
3. **📄 复制全部** — 复制全部原文或全部译文

缓存命中 overlay (`showCacheOverlay`) 同样在右下角显示📋按钮。

#### 复制模式交互
**进入：** 点击 📋 → 进入复制模式
- bitmap 覆盖层保持不变，在上面叠加透明可点击区域
- 按合并后气泡位置（`translatedRegions` 中每个气泡的 rect）动态创建透明 clickable View
- 每个透明 View 设置 `setOnClickListener` → 点击复制对应文本
- 右下角展开三按钮

**显示气泡框：** 进入复制模式时绘制气泡框（参考调试模式样式）
- 半透明色填充 + 边框
- 被点击时短暂高亮闪烁（约 200ms）

**文本复制逻辑：**
- 原文模式：复制该气泡对应的 OCR 原文（`bubble.texts.joinToString("")`）
- 译文模式：复制该气泡的译文（`bubble.translatedText`）
- 复制后 Toast "已复制"

**复制全部：**
- 点击 📄 → 根据当前原文/译文切换，复制当前页面全部气泡的对应文本
- 格式（与历史记录一致）：
```
[1] 气泡1文本
[2] 气泡2文本
...
```

**退出：** 再次点击 📋 → 移除可点击区域和气泡框覆盖层，回到正常覆盖层

**数据来源与缓存兼容：**
- 正常翻译流程：`translatedRegions`（`List<TranslatedBubble>`）在内存中，包含 rect、texts、translatedText
- 缓存命中时：从 DB 读取气泡位置数据（新数据）→ 重建可点击区域；无位置数据（旧数据）→ 仅支持复制全部

**DB 升级（v11 → v12）：**
- `translation_history` 表新增列 `bubble_rects TEXT DEFAULT NULL`
- 迁移：`ALTER TABLE translation_history ADD COLUMN bubble_rects TEXT`
- ⚠️ 必须在 `TranslationCacheManager` 的 `addMigrations()` 中注册 `MIGRATION_11_12`，否则 `fallbackToDestructiveMigration()` 会清空数据库
- `saveToCache` 时序列化气泡 rect 列表为 JSON 存入：`[{"l":10,"t":20,"r":100,"b":60}, ...]`
- 缓存命中时读取：`bubble_rects` 不为 NULL → 反序列化重建 rect 列表 → 支持单气泡点击
- 兼容性：旧数据 `bubble_rects` 为 NULL → 不显示单气泡点击层，仅 📄 复制全部可用
- `CacheEntry` 新增字段 `bubbleRects: String? = null`

#### 涉及文件
- `MangaFloatingService.kt` — 复制模式全部逻辑（按钮、透明点击层、气泡框、复制逻辑）；saveToCache 时写入 bubble_rects
- `data/CacheEntry.kt` — 新增 bubbleRects 字段
- `data/TranslationCacheManager.kt` — MIGRATION_11_12、缓存读写携带 bubble_rects

---

## 4. 历史记录增强

### 4a — 游戏历史点击复制原文+译文

**现状：** `HistoryFragment.copyTranslatedText()` → 仅复制 `entry.translatedText`

**改为：** 同时复制原文和译文
```
原文: xxx
译文: xxx
```

### 4b — 游戏历史按组下载 txt

**参考：** 漫画历史已有 `downloadSession()` 下载组内图片为 ZIP

**方案：**
- `HistoryGroupAdapter` 中每个游戏进程组标题栏右侧新增下载按钮（图标 `⬇` 或下载矢量图标）
- 下载为 `.txt` 文件，使用 SAF (`ACTION_CREATE_DOCUMENT`) 让用户选择保存位置
- 文件名：`session_<sessionId前8位>.txt`
- 文件内容按时间正序排列，每组原文+译文用分隔线隔开：
```
[MM-dd HH:mm]
原文: xxx
译文: xxx
---
[MM-dd HH:mm]
原文: yyy
译文: yyy
```

### 4c — 漫画译文详情文本可选

**文件：** `res/layout/item_translation_detail.xml`

- `tvOcrText` 和 `tvTranslatedText` 设置 `android:textIsSelectable="true"`
- 用户可长按选择文字，系统自带复制菜单

### 涉及文件
- `HistoryFragment.kt` — 复制原文+译文、游戏组下载 txt（SAF 回调）
- `HistoryGroupAdapter.kt` — 新增 `onDownloadSessionClick` 回调参数
- `res/layout/item_history_session.xml` — 会话标题栏右侧新增下载按钮
- `res/layout/item_translation_detail.xml` — textIsSelectable

---

## 涉及文件汇总

| 文件 | 改动 |
|------|------|
| `TranslationResultView.kt` | 阴影开关、复制按钮、⚡ 迁移到文本前缀 |
| `FloatingBallService.kt` | styleKeys 加 text_shadow_enabled；传给 TranslationResultView fromCache 信息 |
| `PersonalizationConfig.kt` | 阴影 SwitchPreference + 颜色恢复按钮 |
| `res/xml/personalization.xml` | 新控件 |
| `MangaFloatingService.kt` | 复制模式：按钮 + 透明点击层 + 气泡框 + 复制逻辑；saveToCache 写入 bubble_rects |
| `data/CacheEntry.kt` | 新增 bubbleRects 字段 |
| `data/TranslationCacheManager.kt` | MIGRATION_11_12、缓存读写 bubble_rects |
| `HistoryFragment.kt` | 游戏复制原文+译文、游戏组下载 txt（SAF 回调） |
| `HistoryGroupAdapter.kt` | 新增 onDownloadSessionClick 回调 |
| `res/layout/item_history_session.xml` | 会话标题栏下载按钮 |
| `res/layout/item_translation_detail.xml` | textIsSelectable |
| `res/drawable/ic_copy.xml` | 新建复制矢量图标 |
| `res/values/strings.xml` / `values-zh` / `values-en` | 新字符串 |

---

## 不涉及的内容

- 漫画翻译文字阴影（当前无，不需要加）
- overlay 上拖拽选区复制（Android overlay 窗口不支持系统文本选区手柄）
- 游戏翻译覆盖层工具栏（只有一个复制按钮）
