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

import android.app.ActivityManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.moe.moetranslator.databinding.FragmentTranslationModeBinding
import com.moe.moetranslator.R
import com.moe.moetranslator.translate.AccessibilityServiceManager
import com.moe.moetranslator.translate.FloatingBallService
import com.moe.moetranslator.manga.MangaFloatingService

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
        if(prefs.getInt("Translate_Mode", 0) == 0){
            binding.ocrModeLayout.setBackgroundResource(R.drawable.custom_radio_button_selected_background)
            binding.picModeLayout.setBackgroundResource(R.drawable.custom_radio_button_background)
        }else{
            binding.ocrModeLayout.setBackgroundResource(R.drawable.custom_radio_button_background)
            binding.picModeLayout.setBackgroundResource(R.drawable.custom_radio_button_selected_background)
        }
        binding.ocrModeLayout.setOnClickListener {
            if (isAnyTranslationServiceRunning()) {
                Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.ocrModeLayout.setBackgroundResource(R.drawable.custom_radio_button_selected_background)
            binding.picModeLayout.setBackgroundResource(R.drawable.custom_radio_button_background)
            prefs.setInt("Translate_Mode", 0)
            Log.d("RADIO","A")
        }

        binding.picModeLayout.setOnClickListener {
            if (isAnyTranslationServiceRunning()) {
                Toast.makeText(requireContext(), getString(R.string.stop_service_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.ocrModeLayout.setBackgroundResource(R.drawable.custom_radio_button_background)
            binding.picModeLayout.setBackgroundResource(R.drawable.custom_radio_button_selected_background)
            prefs.setInt("Translate_Mode", 1)
            Log.d("RADIO","B")
        }

    }

    private fun isAnyTranslationServiceRunning(): Boolean {
        return AccessibilityServiceManager.getService() != null &&
                (isServiceRunning(FloatingBallService::class.java) ||
                 isServiceRunning(MangaFloatingService::class.java))
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = requireContext().getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }
}