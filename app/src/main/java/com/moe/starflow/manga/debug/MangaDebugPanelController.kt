package com.moe.starflow.manga.debug
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.engine.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.moe.starflow.utils.LogCollector

/**
 * 漫画 debug 全屏 overlay 窗口骨架 + 面板折叠状态机。
 * 从 MangaFloatingService 提取（C2 重构）：4 个 show*DebugResultOverlay 共用同一套窗口管理逻辑，
 * 差异（info 面板/参数滑块/折叠按钮）通过 [buildContent] 注入。
 *
 * 状态迁移自服务字段：debugInfoPanelView/ContentView/Added/Collapsed/debugToggleButton/Added。
 * 依赖全部注入：windowManager、onDismissAll（=dismissResultOverlay）、onBringFront（=bringFloatingBallToFront）。
 *
 * ⚠️ 行为保持约束：
 * - [showDebugOverlay] 不干预 buildContent 创建的按钮文本/面板 visibility（P6 的双按钮独立折叠、
 *   P5 的初始折叠 ▲、RTDetr/MLKit 的初始展开走 collapse/expand 均依赖此）。
 * - 默认折叠初始时 [panelCollapsed] 由 [initialCollapsed] 决定，但 visibility 由 buildContent 自行设置。
 */
class MangaDebugPanelController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onDismissAll: () -> Unit,
    private val onBringFront: () -> Unit
) {
    private var panelView: View? = null
    private var panelContent: View? = null
    private var panelAdded = false
    private var panelCollapsed = false
    private var toggleButton: TextView? = null
    private var toggleButtonAdded = false

    val isPanelAdded: Boolean get() = panelAdded

    /**
     * 显示全屏 debug overlay。
     * @param buildContent 向 container 添加 info 面板/参数滑块/折叠按钮等，
     *   返回可折叠的内容 view（RTDetr/MLKit 返回 infoPanel、P5 返回 foldableContent、P6 返回 infoPanel）。
     *   P6 的 📊/⚙ 按钮直接操作各自面板 visibility，不走 [collapse]/[expand]。
     */
    fun showDebugOverlay(
        bitmap: Bitmap,
        cropRect: RectF?,
        screenSize: Size,
        initialCollapsed: Boolean,
        errorTag: String,
        buildContent: (container: FrameLayout) -> View?
    ) {
        // 清理上次显示的结果 overlay（isResultShowing 判断在 onDismissAll 内部）
        onDismissAll()

        // 应用框选外区域遮罩
        val displayBitmap = MangaDebugOverlays.applyCropDimming(bitmap, cropRect, screenSize)

        // 创建容器 FrameLayout + 全屏 ImageView
        val container = FrameLayout(context)
        val imageView = ImageView(context).apply {
            setImageBitmap(displayBitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        container.addView(imageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 添加 info 面板/滑块/按钮（调用方决定），记录可折叠内容
        panelContent = buildContent(container)

        // imageView 点击关闭全部（toggle 按钮在更高层级会优先接收点击）
        imageView.isClickable = true
        imageView.setOnClickListener {
            dismiss()
            onDismissAll()
        }

        // 始终全屏显示
        val params = WindowManager.LayoutParams(
            screenSize.width,
            screenSize.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0; y = 0
        }

        try {
            windowManager.addView(container, params)
            panelView = container
            panelAdded = true
            panelCollapsed = initialCollapsed
        } catch (e: Exception) {
            LogCollector.e("MangaDebugPanelController", "$errorTag: 显示失败", e)
        }

        onBringFront()
    }

    /** 折叠调试详情面板 */
    fun collapse() {
        if (panelAdded && !panelCollapsed && panelContent != null) {
            panelContent!!.visibility = View.GONE
            panelCollapsed = true
            toggleButton?.text = "▲"
        }
    }

    /** 展开已折叠的调试详情面板 */
    fun expand() {
        if (panelAdded && panelCollapsed && panelContent != null) {
            panelContent!!.visibility = View.VISIBLE
            panelCollapsed = false
            toggleButton?.text = "▼"
        }
    }

    /** 切换折叠状态（createToggleButton onToggle 用） */
    fun toggleCollapse() {
        if (panelCollapsed) expand() else collapse()
    }

    /** 移除调试详情面板 */
    fun dismiss() {
        if (panelAdded) {
            try {
                windowManager.removeView(panelView)
            } catch (e: Exception) {
                LogCollector.w("MangaDebugPanelController", "dismiss: ${e.message}")
            }
            panelView = null
            panelContent = null
            panelAdded = false
            panelCollapsed = false
        }
        removeToggleButton()
    }

    /** 记录展开/折叠按钮引用（buildContent 内创建按钮后调用；P6 的 ⚙ 按钮不记录） */
    fun setToggleButton(button: TextView) {
        toggleButton = button
        toggleButtonAdded = true
    }

    private fun removeToggleButton() {
        if (toggleButtonAdded) {
            try {
                // 按钮是 container 子视图，container 已 remove 时会抛 IllegalArgumentException → 无害 warning（原行为）
                windowManager.removeView(toggleButton)
            } catch (e: Exception) {
                LogCollector.w("MangaDebugPanelController", "removeToggleButton: ${e.message}")
            }
            toggleButton = null
            toggleButtonAdded = false
        }
    }
}
