package com.moe.starflow.translate
import com.moe.starflow.translate.screenshot.*

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.moe.starflow.R
import com.moe.starflow.utils.CustomPreference
import java.io.File

@SuppressLint("ViewConstructor")
class TranslationResultView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : FrameLayout(context) {

    companion object {
        /**
         * 重新翻译按钮：缓存命中图标颜色。
         * 选青色 #00BCD4 (Material Cyan 500) 而非橙色 ——
         * 默认翻译结果背景是暖深棕 #D94B2C23，橙色和背景色温冲突；青色与暖色形成冷暖对比，醒目且不刺眼。
         */
        private val RETRANSLATE_ICON_COLOR = Color.parseColor("#00BCD4")  // Material Cyan 500
    }

    private val textView: TextView
    private val closeButton: ImageButton
    private val lockButton: ImageButton
    private val retranslateButton: ImageButton
    private val copyButton: ImageButton
    private var isLocked: Boolean = false

    // 拖动相关变量
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    var onClose: (() -> Unit)? = null
    var onRetranslate: (() -> Unit)? = null

    init {
        val btnSize = dpToPx(14)
        val btnMargin = dpToPx(3)
        val btnSpace = btnSize + btnMargin  // 按钮占用空间 = 14dp + 3dp = 17dp

        // 创建 TextView
        textView = TextView(context).apply {
            val prefs = CustomPreference.getInstance(context)
            val shape = GradientDrawable().apply {
                setColor(prefs.getInt("Custom_Result_Background_Color", 0xD91E2C3A.toInt()))
                cornerRadius = 15f
            }
            background = shape
            setTextColor(prefs.getInt("Custom_Result_Font_Color", -1516335))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getFloat("Custom_Result_Font_Size", 16f))
            // 上方和左侧留空间给按钮，下方留空间给底部按钮
            setPadding(btnSpace, btnSpace, btnSpace, btnSpace + dpToPx(4))
            gravity = Gravity.START

            val customFont = prefs.getString("Custom_Result_Font", "")
            if (customFont.isEmpty()) {
                typeface = Typeface.DEFAULT
            } else {
                try {
                    val fontFile = File(context.getExternalFilesDir(null), "font/$customFont")
                    if (fontFile.exists()) {
                        typeface = Typeface.createFromFile(fontFile)
                    } else {
                        typeface = Typeface.DEFAULT
                    }
                } catch (e: Exception) {
                    typeface = Typeface.DEFAULT
                }
            }
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
            text = context.getString(R.string.textview_tip)
        }

        // 创建锁定按钮（小尺寸）
        lockButton = ImageButton(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(R.drawable.ic_unlock)
            setColorFilter(Color.argb(160, 80, 80, 80))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setOnClickListener { toggleLock() }
        }

        // 创建关闭按钮（小尺寸）
        closeButton = ImageButton(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(R.drawable.close_service)
            setColorFilter(Color.argb(160, 80, 80, 80))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClose?.invoke() }
        }

        // 复制按钮（左下角，始终显示）—— 与锁/关闭/重翻大小一致（14dp）
        copyButton = ImageButton(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(R.drawable.ic_copy)
            setColorFilter(Color.argb(160, 80, 80, 80))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = textView.text.toString().removePrefix("⚡")
                clipboard.setPrimaryClip(ClipData.newPlainText("translated_text", text))
                android.widget.Toast.makeText(context, R.string.text_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // 重新翻译按钮（右下角）—— 大小与其他按钮一致（14dp），但图标用亮橙色区分
        // 透明背景 + 橙色刷新图标 + 负 padding 让图标视觉上更大（溢出按钮边界 3dp）
        // 触摸区仍 14dp 不变，只让图标更醒目
        retranslateButton = ImageButton(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(RETRANSLATE_ICON_COLOR)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(-3, -3, -3, -3)
            visibility = View.GONE
            setOnClickListener { onRetranslate?.invoke() }
        }

        // 添加文字
        addView(textView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // 按钮叠加在文字区域左上角和右上角
        addView(lockButton, LayoutParams(btnSize, btnSize).apply {
            gravity = Gravity.START or Gravity.TOP
            marginStart = btnMargin
            topMargin = btnMargin
        })

        addView(closeButton, LayoutParams(btnSize, btnSize).apply {
            gravity = Gravity.END or Gravity.TOP
            marginEnd = btnMargin
            topMargin = btnMargin
        })

        // 复制按钮（左下角）—— 14dp，与其他按钮一致
        addView(copyButton, LayoutParams(btnSize, btnSize).apply {
            gravity = Gravity.START or Gravity.BOTTOM
            marginStart = btnMargin
            bottomMargin = btnMargin
        })

        // 重新翻译按钮（右下角）—— 14dp，与其他按钮一致
        addView(retranslateButton, LayoutParams(btnSize, btnSize).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            marginEnd = btnMargin
            bottomMargin = btnMargin
        })

        // 设置触摸监听（拖动）
        setupDragListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener() {
        setOnTouchListener { _, event ->
            if (isLocked) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = (initialX + (event.rawX - initialTouchX)).toInt()
                    layoutParams.y = (initialY + (event.rawY - initialTouchY)).toInt()
                    windowManager.updateViewLayout(this, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            lockButton.setImageResource(R.drawable.baseline_lock)
        } else {
            lockButton.setImageResource(R.drawable.ic_unlock)
        }
    }

    fun setText(text: String) {
        textView.text = text
    }

    fun setText(text: String, fromCache: Boolean = false) {
        textView.text = if (fromCache) "⚡$text" else text
    }

    fun getText(): String = textView.text.toString()

    /** 显示重新翻译按钮（缓存标识已迁移到文本前缀） */
    fun showCacheIndicator() {
        retranslateButton.visibility = View.VISIBLE
    }

    /** 隐藏重新翻译按钮 */
    fun hideCacheIndicator() {
        retranslateButton.visibility = View.GONE
    }

    fun getTextView(): TextView = textView

    /**
     * 重新从 prefs 读取样式（字号、颜色、字体、背景）并应用到 textView。
     * 由 FloatingBallService 在 prefs 变化时调用，实现设置页改完立刻生效。
     */
    fun applyStyle() {
        val prefs = CustomPreference.getInstance(context)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getFloat("Custom_Result_Font_Size", 16f))
        textView.setTextColor(prefs.getInt("Custom_Result_Font_Color", -1516335))
        (textView.background as? GradientDrawable)?.setColor(
            prefs.getInt("Custom_Result_Background_Color", 0xD91E2C3A.toInt())
        )
        val customFont = prefs.getString("Custom_Result_Font", "")
        if (customFont.isEmpty()) {
            textView.typeface = Typeface.DEFAULT
        } else {
            try {
                val fontFile = File(context.getExternalFilesDir(null), "font/$customFont")
                if (fontFile.exists()) {
                    textView.typeface = Typeface.createFromFile(fontFile)
                } else {
                    textView.typeface = Typeface.DEFAULT
                }
            } catch (e: Exception) {
                textView.typeface = Typeface.DEFAULT
            }
        }
        val shadowEnabled = prefs.getBoolean("text_shadow_enabled", true)
        if (shadowEnabled) {
            textView.setShadowLayer(2f, 1f, 1f, Color.BLACK)
        } else {
            textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
