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

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.moe.moetranslator.R
import com.moe.moetranslator.databinding.FragmentDeveloperBinding
import com.moe.moetranslator.utils.CustomPreference
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import java.util.concurrent.TimeUnit


class Developer : Fragment() {
    private lateinit var binding: FragmentDeveloperBinding
    private lateinit var prefs: CustomPreference
    private val party = Party(
        angle = 300,
        spread = 60,
        speed = 60f,
        maxSpeed = 70f,
        damping = 0.9f,
        colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
        shapes = listOf(Shape.Square, Shape.Circle),
        timeToLive = 5000L,
        fadeOutEnabled = true,
        position = Position.Relative(0.0,0.6),
        emitter = Emitter(duration = 5000, TimeUnit.MILLISECONDS).max(600)
    )
    private val party2 = Party(
        angle = 240,
        spread = 60,
        speed = 60f,
        maxSpeed = 70f,
        damping = 0.9f,
        colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
        shapes = listOf(Shape.Square, Shape.Circle),
        timeToLive = 5000L,
        fadeOutEnabled = true,
        position = Position.Relative(1.0,0.6),
        emitter = Emitter(duration = 5000, TimeUnit.MILLISECONDS).max(600)
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDeveloperBinding.inflate(inflater,container,false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = CustomPreference.getInstance(requireContext())

        val cele = binding.konfettiViewd
        cele.start(party)
        cele.start(party2)

        // 意见反馈 - 显示联系方式
        binding.ideas.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.feedback)
                .setMessage("QQ：2057095664\nB站：小灰不怕黑")
                .setPositiveButton(R.string.user_known, null)
                .show()
        }

        binding.opensource.setOnClickListener {
            val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.OPEN_SOURCE)
            }
            startActivity(intent)
        }

        binding.github.setOnClickListener {
            val url = "https://github.com/xujunjiex/MoeTranslate-comics"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        }

        // 高级模型搭配开关
        val advancedModeEnabled = prefs.getBoolean("Manga_Advanced_Mode", false)
        binding.advancedModeSwitch.isChecked = advancedModeEnabled
        binding.advancedModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("Manga_Advanced_Mode", isChecked)
            if (isChecked) {
                showToast("高级模型搭配已开启，可自由搭配检测器和识别器")
            } else {
                showToast("高级模型搭配已关闭，使用固定搭配模式")
            }
        }

        // CTD 调试开关
        val ctdDebugEnabled = prefs.getBoolean("CTD_Debug_View", false)
        binding.ctdDebugSwitch.isChecked = ctdDebugEnabled
        binding.ctdDebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("CTD_Debug_View", isChecked)
            if (isChecked) {
                showToast("CTD 调试模式已开启，请在漫画翻译界面截图测试")
            }
        }

        // RT-DETR-V2 调试开关
        binding.rtdetrDebugSwitch.isChecked = prefs.getBoolean("RTDetrV2_Debug_View", false)
        binding.rtdetrDebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("RTDetrV2_Debug_View", isChecked)
            if (isChecked) {
                showToast("RT-DETR-V2 调试模式已开启，请在漫画翻译界面截图测试")
            }
        }

        // ML Kit 调试开关
        binding.mlkitDebugSwitch.isChecked = prefs.getBoolean("MLKit_Debug_View", false)
        binding.mlkitDebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("MLKit_Debug_View", isChecked)
            if (isChecked) {
                showToast("ML Kit 调试模式已开启，请在漫画翻译界面截图测试")
            }
        }

        // PP-OCRv5 调试开关
        binding.ppocrv5DebugSwitch.isChecked = prefs.getBoolean("PPOcrV5_Debug_View", false)
        binding.ppocrv5DebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("PPOcrV5_Debug_View", isChecked)
            if (isChecked) {
                showToast("PP-OCRv5 调试模式已开启，请在漫画翻译界面截图测试")
            }
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

}
