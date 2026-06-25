# 双模式截图方案设计

## 项目目标

降低用户使用门槛，让用户无需手动开启无障碍服务即可使用翻译功能。

## 核心改动

新增 **MediaProjection 截图方式**，与现有的 **AccessibilityService 截图方式** 并存，用户可在设置中选择。

## 两种截图方式对比

| 特性 | MediaProjection | AccessibilityService |
|------|-----------------|---------------------|
| 授权方式 | 弹窗点一下 | 去系统设置开启 |
| 授权持久性 | 每次启动都要授权 | 开启后永久有效 |
| 后台能力 | 需要前台服务 | 无需前台服务 |
| 内容变化加速 | 无 | 有 |
| 用户门槛 | 低 | 高 |

## 新增组件

### 1. Shooter（截图器）

封装 MediaProjection + VirtualDisplay + ImageReader，负责实际的屏幕截图。

**核心功能：**
- `init(captureIntent)` — 初始化 MediaProjection，创建 VirtualDisplay 和 ImageReader
- `shot()` — 截图，返回 Bitmap
- `release()` — 释放所有资源

**改进点（相比 fby）：**
- 使用实际屏幕尺寸，而非固定 2000×2000
- 使用协程 suspend 替代忙等待
- 添加完善的错误处理和日志

### 2. IntentHolder（权限存储）

存储 MediaProjection 授权 Intent，在权限请求 Activity 和翻译服务之间传递。

**设计：**
- 单例 object
- 提供 `set(intent)` 和 `clear()` 方法
- Intent 只能使用一次，用后即清

### 3. PermissionActivity（权限请求页面）

透明 Activity，弹出系统授权弹窗。

**流程：**
1. `onCreate` → 调用 `MediaProjectionManager.createScreenCaptureIntent()`
2. `onActivityResult` → 保存 Intent 到 IntentHolder
3. 通知翻译服务权限已获取
4. `finish()` 关闭

### 4. ScreenshotProvider（截图提供者接口）

统一截图接口，支持两种实现。

**接口方法：**
- `isAvailable()` — 检查是否可用
- `takeScreenshot(cropRect, offset)` — 截图
- `release()` — 释放资源

**两种实现：**
- `MediaProjectionProvider` — 使用 Shooter 截图
- `AccessibilityProvider` — 使用现有无障碍服务截图

## 前台服务

使用 MediaProjection 时，翻译服务必须是前台服务（Android 要求）。

**实现：**
- 创建通知渠道"截图服务"
- 显示常驻通知"翻译服务运行中"
- 服务类型标记为 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`
- 通知栏可快捷停止翻译

## 完整场景覆盖

### 场景 1：游戏手动翻译

用户点击悬浮球 → 截图 → OCR → 翻译

两种方式流程相同，只是截图 Provider 不同。

### 场景 2：游戏自动翻译

定时截图 → 像素变化检测 → OCR → 翻译

- MediaProjection：完全依赖定时轮询
- AccessibilityService：定时轮询 + 内容变化加速

### 场景 3：漫画手动翻译

用户点击悬浮球 → 隐藏 overlay → 等待 150ms → 截图 → 检测 → OCR → 翻译 → 渲染

两种方式流程相同。

### 场景 4：漫画自动翻译

定时截图 → pHash 状态机 → 检测 → OCR → 翻译 → 渲染

- MediaProjection：翻页后等下一个轮询周期
- AccessibilityService：翻页后 500ms 内触发检测

### 场景 5：漫画分批翻译

气泡数 > 6 时分批处理。截图只发生一次，分批在 OCR + 翻译阶段。两种方式无差异。

### 场景 6：游戏翻页稳定性检查

像素变化 → 稳定 1 帧 → 稳定 2 帧 → 触发 OCR。基于像素比较，与截图方式无关。两种方式无差异。

### 场景 7：裁剪区域选择

用户拖拽选择区域 → 截图后裁剪。裁剪逻辑在 Provider 内部，与截图方式无关。

### 场景 8：图片翻译模式

截图后直接发送到图片翻译 API。两种方式无差异。

### 场景 9：受限内容检测

截图后检测全黑或纯色。检测逻辑与截图方式无关。

### 场景 10：翻译缓存

基于 pHash 或文本内容的缓存。与截图方式无关。

### 场景 11：权限请求流程

- MediaProjection：启动翻译时弹窗授权
- AccessibilityService：启动翻译时检查服务状态

### 场景 12：服务生命周期

- MediaProjection：前台服务 + 通知栏
- AccessibilityService：普通服务

### 场景 13：错误处理

| 错误场景 | MediaProjection | AccessibilityService |
|---------|-----------------|---------------------|
| 权限被拒绝 | 提示"需要截图权限" | 提示"请开启无障碍服务" |
| 截图失败 | 重试 3 次，失败后提示 | 提示"截图失败" |
| 服务被杀死 | 重新请求授权 | 重新检查服务状态 |
| DRM 内容 | 检测全黑截图，提示"受限内容" | 同左 |

### 场景 14：设置切换

用户在设置中选择截图方式。翻译运行中禁止切换。

### 场景 15：状态检测

检查截图方式是否可用：
- MediaProjection：检查 Intent 是否存在
- AccessibilityService：检查服务是否运行

## 响应时间差异

| 模式 | MediaProjection | AccessibilityService |
|------|-----------------|---------------------|
| 游戏手动翻译 | 无差异 | 无差异 |
| 游戏自动翻译 | 无差异（300ms 轮询） | 无差异 |
| 漫画手动翻译 | 无差异 | 无差异 |
| 漫画自动翻译 | 翻页后 0~轮询间隔 | 翻页后 ~500ms |

## 文件改动清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| Shooter.kt | 新增 | MediaProjection 截图器 |
| MediaProjectionIntentHolder.kt | 新增 | Intent 存储 |
| ScreenCapturePermissionActivity.kt | 新增 | 权限请求页面 |
| ScreenshotProvider.kt | 新增 | 截图提供者接口 |
| MediaProjectionProvider.kt | 新增 | MediaProjection 实现 |
| AccessibilityProvider.kt | 新增 | AccessibilityService 实现 |
| FloatingBallService.kt | 修改 | 添加 MediaProjection 路径 + 前台服务 |
| MangaFloatingService.kt | 修改 | 添加 MediaProjection 路径 + 前台服务 |
| TranslateFragment.kt | 修改 | 添加截图方式选择 |
| PersonalizationConfig.kt | 修改 | 添加截图方式设置项 |
| AndroidManifest.xml | 修改 | 注册新 Activity + 权限 |
| strings.xml | 修改 | 添加新字符串 |

## 用户体验流程

### MediaProjection 模式

1. 用户点击"开始翻译"
2. 弹出系统授权弹窗"允许截取屏幕？"
3. 用户点"允许"
4. 翻译开始，通知栏显示"翻译服务运行中"
5. 翻译结束，通知栏消失

### AccessibilityService 模式

1. 用户点击"开始翻译"
2. 检查无障碍服务是否开启
3. 未开启 → 跳转设置页面
4. 翻译开始，无通知栏
5. 翻译结束

## 设置 UI

```
截图方式
├── 录屏授权（推荐）  ← MediaProjection，门槛低
└── 无障碍服务        ← AccessibilityService，响应快
```

## 补充说明

### ScreenshotProvider 初始化时机

- 在 FloatingBallService/MangaFloatingService 的 `onCreate` 中初始化
- 根据设置选择 MediaProjectionProvider 或 AccessibilityProvider
- 如果 MediaProjectionProvider 且 Intent 不存在，不立即请求权限
- 权限请求延迟到第一次截图时（惰性初始化）

### 设置切换处理

- 翻译运行中禁止切换截图方式
- 切换时提示"请先停止翻译再切换"
- 切换后立即生效，下次翻译使用新方式

### 应用重启处理

- MediaProjection 权限不跨应用生命周期
- 应用重启后需要重新授权
- IntentHolder 在服务 onDestroy 时清空

### 通知渠道创建

- Android 8.0+ 需要创建通知渠道
- 渠道名称"截图服务"
- 重要性设为 LOW（不发出声音）

## 参考实现

参考 Bubble Translate (fby) 的 MediaProjection 实现：
- Shooter.java — MediaProjection 截图器
- RecordPermissionActivity.java — 权限请求页面
- MediaProjectionIntentHolder.java — Intent 存储
- NotificationService.java — 前台服务

取其精华（Shooter 封装、IntentHolder 解耦、透明 Activity），去其糟粕（Hilt 依赖注入、CountDownTimer、固定分辨率、忙等待）。
