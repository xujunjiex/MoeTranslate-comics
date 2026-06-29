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

package com.moe.moetranslator.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import com.moe.moetranslator.R
import com.moe.moetranslator.databinding.FragmentTranslationModeBinding
import com.moe.moetranslator.utils.CustomPreference


class TranslationMode : Fragment() {
    private lateinit var binding: FragmentTranslationModeBinding
    private lateinit var prefs: CustomPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CustomPreference.getInstance(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentTranslationModeBinding.inflate(inflater,container,false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 强制使用OCR模式，隐藏图片翻译模式
        prefs.setInt("Translate_Mode", 0)
        binding.ocrModeLayout.setBackgroundResource(R.drawable.custom_radio_button_selected_background)
        binding.picModeLayout.visibility = View.GONE

        binding.ocrModeLayout.setOnClickListener {
            // OCR模式已固定，无需切换
        }

        // 截图方式选择
        updateScreenshotSelection(animate = false)
        binding.mediaprojectionLayout.setOnClickListener {
            prefs.setString("Screenshot_Method", "0")
            updateScreenshotSelection(animate = true)
        }
        binding.accessibilityLayout.setOnClickListener {
            prefs.setString("Screenshot_Method", "1")
            updateScreenshotSelection(animate = true)
        }
    }

    private fun updateScreenshotSelection(animate: Boolean) {
        val method = prefs.getString("Screenshot_Method", "0")?.toIntOrNull() ?: 0

        val selectedView: View
        val unselectedView: View

        if (method == 0) {
            selectedView = binding.mediaprojectionLayout
            unselectedView = binding.accessibilityLayout
        } else {
            selectedView = binding.accessibilityLayout
            unselectedView = binding.mediaprojectionLayout
        }

        // 更新背景
        selectedView.setBackgroundResource(R.drawable.custom_radio_button_selected_background)
        unselectedView.setBackgroundResource(R.drawable.custom_radio_button_background)

        // 选中动画：轻微放大再回弹
        if (animate) {
            val bounceIn = AnimationUtils.loadAnimation(requireContext(), R.anim.card_select_bounce_in)
            val bounceOut = AnimationUtils.loadAnimation(requireContext(), R.anim.card_select_bounce_out)
            bounceIn.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    selectedView.startAnimation(bounceOut)
                }
            })
            selectedView.startAnimation(bounceIn)
        }
    }
}
