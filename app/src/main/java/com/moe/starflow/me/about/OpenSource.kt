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

package com.moe.starflow.me.about
import com.moe.starflow.translate.screenshot.*

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.moe.starflow.databinding.FragmentOpenSourceBinding
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R

class OpenSource : Fragment() {

    private lateinit var binding: FragmentOpenSourceBinding
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CardAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentOpenSourceBinding.inflate(inflater,container,false)
        recyclerView = binding.cardRecyclerview
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val announce = listOf(
            CustomCard(getText(R.string.proj_androidx_core_ktx_title), getText(R.string.proj_androidx_core_ktx_content)),
            CustomCard(getText(R.string.proj_appcompat_title), getText(R.string.proj_appcompat_content)),
            CustomCard(getText(R.string.proj_mcfa_title), getText(R.string.proj_mcfa_content)),
            CustomCard(getText(R.string.proj_constraintLayout_title), getText(R.string.proj_constraintLayout_content)),
            CustomCard(getText(R.string.proj_nc_title), getText(R.string.proj_nc_content)),
            CustomCard(getText(R.string.proj_ls_title), getText(R.string.proj_ls_content)),
            CustomCard(getText(R.string.proj_preference_title), getText(R.string.proj_preference_content)),
            CustomCard(getText(R.string.proj_okhttp_title), getText(R.string.proj_okhttp_content)),
            CustomCard(getText(R.string.proj_xerces_title), getText(R.string.proj_xerces_content)),
            CustomCard(getText(R.string.proj_mlkit_title), getText(R.string.proj_mlkit_content)),
            CustomCard(getText(R.string.proj_or_title), getText(R.string.proj_or_content)),
            CustomCard(getText(R.string.proj_cp_title), getText(R.string.proj_cp_content)),
            CustomCard(getText(R.string.proj_konfetti_title), getText(R.string.proj_konfetti_content)),
            CustomCard(getText(R.string.proj_rtranslator_title), getText(R.string.proj_rtranslator_content)),
            CustomCard(getText(R.string.proj_glide_title), getText(R.string.proj_glide_content)),
            CustomCard(getText(R.string.proj_guava_title), getText(R.string.proj_guava_content)),
            CustomCard(getText(R.string.proj_kc_title), getText(R.string.proj_kc_content))
        )

        val references = listOf(
            CustomCard(getText(R.string.proj_moetranslate_title), getText(R.string.proj_moetranslate_content)),
            CustomCard(getText(R.string.proj_rapidocr_title), getText(R.string.proj_rapidocr_content)),
            CustomCard(getText(R.string.proj_rtdetr_title), getText(R.string.proj_rtdetr_content)),
            CustomCard(getText(R.string.proj_mangaocr_title), getText(R.string.proj_mangaocr_content)),
            CustomCard(getText(R.string.proj_mangaimagetran_title), getText(R.string.proj_mangaimagetran_content))
        )

        val allItems = references + listOf(null) + announce

        adapter = CardAdapter(allItems)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }
}