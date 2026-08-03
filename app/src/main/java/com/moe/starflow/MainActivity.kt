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

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.MotionEvent
import androidx.navigation.fragment.NavHostFragment
import com.moe.starflow.translate.TextTranslateFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : BaseActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* 无论用户是否授权都继续 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) //锁定竖屏

        setContentView(R.layout.activity_main)

        applySystemBarsPadding(findViewById(R.id.fragment_view), true, false)

        //关联NavController与BottonNavigationView
        val navHost = supportFragmentManager.findFragmentById(R.id.fragment_view) as NavHostFragment
        val navController = navHost.navController
        val bottomNavigation:BottomNavigationView=findViewById(R.id.bottomNavigation)
        // 自定义选中监听：文本翻译页翻译中切换 → 弹确认框（强制终止/不记录）
        bottomNavigation.setOnItemSelectedListener { item ->
            val destId = item.itemId
            val currentId = navController.currentDestination?.id
            if (currentId != destId) {
                if (currentId == R.id.text_translate_fragment && destId != R.id.text_translate_fragment) {
                    val frag = navHost.childFragmentManager.fragments.firstOrNull() as? TextTranslateFragment
                    if (frag?.isTranslating == true) {
                        frag.confirmLeave {
                            navController.navigate(destId)
                            // 确认切换后手动同步底部导航选中项（监听返回 false 不会自动勾选）
                            bottomNavigation.menu.findItem(destId)?.isChecked = true
                        }
                        return@setOnItemSelectedListener false
                    }
                }
                navController.navigate(destId)
            }
            true
        }

        // Android 13+ 请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

}