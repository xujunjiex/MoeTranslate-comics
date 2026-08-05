/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.starflow
import com.moe.starflow.translate.widget.*

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.moe.starflow.utils.LanguageManager

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 应用语言设置
        val context = LanguageManager.applyLanguage(newBase)
        super.attachBaseContext(context)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // 启用 Edge-to-Edge，一定要在super.onCreate之前调用
        enableEdgeToEdge()
        // 自定义 UI 字体（开关开启时）：LayoutInflater Factory2，inflate 即应用，
        // 覆盖动态创建/列表项/弹窗等（比遍历 View 树稳定）
        setupFontInflater()
        super.onCreate(savedInstanceState)

        window.isNavigationBarContrastEnforced = false
    }

    /**
     * UI 同步字体开关开启时，给 LayoutInflater 挂 Factory2：
     * inflate 出的 TextView 自动用自定义字体（Custom_Result_Font）。
     * 开关默认关闭——字体只用于翻译结果。
     */
    private fun setupFontInflater() {
        val prefs = com.moe.starflow.utils.CustomPreference.getInstance(this)
        if (!prefs.getBoolean("ui_apply_custom_font", false)) return
        val typeface = com.moe.starflow.manga.render.OverlayRenderer.loadResultTypeface(this, prefs) ?: return
        try {
            androidx.core.view.LayoutInflaterCompat.setFactory2(
                layoutInflater,
                object : android.view.LayoutInflater.Factory2 {
                    override fun onCreateView(parent: View?, name: String, context: Context, attrs: android.util.AttributeSet): View? {
                        val view = delegate.createView(parent, name, context, attrs)
                        if (view is android.widget.TextView) view.typeface = typeface
                        return view
                    }
                    override fun onCreateView(name: String, context: Context, attrs: android.util.AttributeSet): View? =
                        onCreateView(null, name, context, attrs)
                }
            )
        } catch (e: Exception) {
            // AppCompat 已设 Factory2 时忽略（保持默认字体）
        }
    }

    /**
     * 为指定 View 应用系统栏 padding
     * @param view 需要应用 padding 的 View
     * @param applyTop 是否应用顶部 padding（状态栏）
     * @param applyBottom 是否应用底部 padding（导航栏）
     */
    protected fun applySystemBarsPadding(
        view: View,
        applyTop: Boolean = true,
        applyBottom: Boolean = true
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                if (applyTop) insets.top else v.paddingTop,
                v.paddingRight,
                if (applyBottom) insets.bottom else v.paddingBottom
            )
            windowInsets
        }
    }
}