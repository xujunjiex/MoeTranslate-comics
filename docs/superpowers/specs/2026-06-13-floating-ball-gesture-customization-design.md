# 悬浮球手势自定义设计文档

## 概述

为悬浮球添加双击手势支持，并允许用户自定义单击、双击、长按三个手势各自执行的动作。游戏模式和漫画模式统一配置。

## 需求

- **可选手势**：单击、双击、长按
- **可选动作**：翻译、打开菜单、自动翻译开关
- **默认值**：单击=翻译，双击=自动翻译开关，长按=菜单
- **配置统一**：游戏模式和漫画模式共用一套手势配置
- **设置位置**：个性化设置页 → 悬浮球分类下

## 手势检测机制

### 双击检测

使用 Handler 延迟分派方案：

```
ACTION_DOWN → 记录位置，启动长按计时器（已有）
ACTION_MOVE → 移动超阈值取消长按（已有）
ACTION_UP →
  if 是拖动 → 忽略（已有）
  if 长按已触发 → 忽略（已有）
  else →
    if 距上次点击 < 300ms → 取消单击计时器 → 执行双击动作
    else → 启动 300ms 单击计时器
```

### 关键变量

- `lastClickTime: Long` — 上次点击时间戳
- `singleClickRunnable: Runnable` — 单击延迟执行
- `DOUBLE_CLICK_DELAY = 300L` — 双击判定间隔（毫秒）

### 单击延迟

用户第一次点击后等 300ms，如果没有第二次点击才执行单击动作。对"翻译"操作完全无感。

## 动作枚举

在 `Constants.kt` 中新增：

```kotlin
enum class BallAction(val value: Int) {
    TRANSLATE(0),       // 翻译
    MENU(1),            // 打开菜单
    AUTO_TRANSLATE(2);  // 自动翻译开关

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: TRANSLATE
    }
}
```

## 配置存储

使用 `CustomPreference`（SharedPreferences），key：

| Key | 类型 | 默认值 | 说明 |
|-----|------|--------|------|
| `Ball_Gesture_Single_Click` | Int | 0 (TRANSLATE) | 单击动作 |
| `Ball_Gesture_Double_Click` | Int | 2 (AUTO_TRANSLATE) | 双击动作 |
| `Ball_Gesture_Long_Press` | Int | 1 (MENU) | 长按动作 |

## Service 改造

### FloatingBallService（游戏模式）

将现有 `handleClick()` 拆分为：

- `doTranslate()` — 原 handleClick 中的翻译逻辑
- `executeAction(action: BallAction)` — 根据配置分派动作

触摸处理改造：

```kotlin
// ACTION_UP 中
if (currentGesture == null && withinSlop) {
    val now = System.currentTimeMillis()
    if (now - lastClickTime < DOUBLE_CLICK_DELAY) {
        // 双击
        handler.removeCallbacks(singleClickRunnable)
        lastClickTime = 0L
        executeAction(doubleClickAction)
    } else {
        // 可能是单击，延迟等待
        lastClickTime = now
        handler.removeCallbacks(singleClickRunnable)
        singleClickRunnable = Runnable { executeAction(singleClickAction) }
        handler.postDelayed(singleClickRunnable, DOUBLE_CLICK_DELAY)
    }
}
```

长按处理改造：

```kotlin
private fun handleLongPress() {
    currentGesture = GestureType.LongPress
    // 震动反馈动画（已有）
    ...
    executeAction(longPressAction)
}
```

### MangaFloatingService（漫画模式）

同样的改造：`onBallClicked()` → `doTranslate()` + `executeAction()`。

## 设置 UI

在 `personalization.xml` 的"悬浮球"分类下新增 3 个 `ListPreference`：

```xml
<ListPreference
    android:key="Ball_Gesture_Single_Click"
    android:defaultValue="0"
    android:title="单击动作"
    android:entries="@array/ball_action_entries"
    android:entryValues="@array/ball_action_values" />

<ListPreference
    android:key="Ball_Gesture_Double_Click"
    android:defaultValue="2"
    android:title="双击动作"
    android:entries="@array/ball_action_entries"
    android:entryValues="@array/ball_action_values" />

<ListPreference
    android:key="Ball_Gesture_Long_Press"
    android:defaultValue="1"
    android:title="长按动作"
    android:entries="@array/ball_action_entries"
    android:entryValues="@array/ball_action_values" />
```

在 `PersonalizationConfig.kt` 中添加对应处理，修改时需停止服务。

## 边界情况

1. **长按 + 双击冲突**：长按触发后不响应后续点击，现有逻辑不变
2. **拖动误触**：移动超阈值取消所有手势判定，现有逻辑不变
3. **三个手势分配同一动作**：允许，用户自由配置
4. **服务运行时修改设置**：提示"请先停止翻译服务"
5. **默认值兼容**：不设置 key 时用 TRANSLATE / AUTO_TRANSLATE / MENU 作为默认值

## 改动文件清单

| 文件 | 改动 |
|------|------|
| `Constants.kt` | 新增 `BallAction` 枚举 |
| `FloatingBallService.kt` | 双击检测 + `executeAction()` 分派 |
| `MangaFloatingService.kt` | 双击检测 + `executeAction()` 分派 |
| `PersonalizationConfig.kt` | 新增 3 个 ListPreference 处理 |
| `personalization.xml` | 新增 3 个 ListPreference |
| `strings.xml` / `strings-zh.xml` | 新增字符串资源 |
| `arrays.xml` | 新增 ball_action_entries / ball_action_values |
