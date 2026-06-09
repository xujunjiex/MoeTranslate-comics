package com.moe.moetranslator.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.moe.moetranslator.R
import com.moe.moetranslator.utils.CustomPreference
import java.io.File

@SuppressLint("ViewConstructor")
class TranslationResultView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : FrameLayout(context) {

    private val textView: TextView
    private val closeButton: ImageButton
    private val lockButton: ImageButton
    private var isLocked: Boolean = false

    // 拖动相关变量
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    var onClose: (() -> Unit)? = null
    var onLockChanged: ((Boolean) -> Unit)? = null

    init {
        val btnSize = dpToPx(14)
        val btnMargin = dpToPx(3)
        val btnSpace = btnSize + btnMargin  // 按钮占用空间 = 14dp + 3dp = 17dp

        // 创建 TextView
        textView = TextView(context).apply {
            val prefs = CustomPreference.getInstance(context)
            val shape = GradientDrawable().apply {
                setColor(prefs.getInt("Custom_Result_Background_Color", -649384925))
                cornerRadius = 15f
            }
            background = shape
            setTextColor(prefs.getInt("Custom_Result_Font_Color", -1516335))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getFloat("Custom_Result_Font_Size", 16f))
            // 上方和左侧留空间给按钮
            setPadding(btnSpace, btnSpace, btnSpace, 15)
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
        onLockChanged?.invoke(isLocked)
    }

    fun setText(text: String) {
        textView.text = text
    }

    fun getText(): String = textView.text.toString()

    fun setLocked(locked: Boolean) {
        isLocked = locked
        if (locked) {
            lockButton.setImageResource(R.drawable.baseline_lock)
        } else {
            lockButton.setImageResource(R.drawable.ic_unlock)
        }
    }

    fun isLockedView(): Boolean = isLocked

    fun getTextView(): TextView = textView

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
