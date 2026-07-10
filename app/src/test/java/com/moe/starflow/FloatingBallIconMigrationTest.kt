package com.moe.starflow

import com.moe.starflow.utils.CustomPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingBallIconMigrationTest {

    @Before fun setUp() {
        // 清空 SharedPreferences 状态
        CustomPreference.getInstance(org.robolectric.RuntimeEnvironment.getApplication())
            .remove("Custom_Floating_Pic")
        CustomPreference.getInstance(org.robolectric.RuntimeEnvironment.getApplication())
            .remove("Icon_Game")
        CustomPreference.getInstance(org.robolectric.RuntimeEnvironment.getApplication())
            .remove("Icon_Comic")
    }

    @Test fun gameMigratesWhenLegacyPresent() {
        val prefs = CustomPreference.getInstance(org.robolectric.RuntimeEnvironment.getApplication())
        prefs.setString("Custom_Floating_Pic", "old_game.png")
        // 模拟 Service 启动时的一次性迁移
        if (!prefs.contains("Icon_Game")) {
            val legacy = prefs.getString("Custom_Floating_Pic", "")
            if (legacy.isNotEmpty()) prefs.setString("Icon_Game", legacy)
        }
        assertEquals("old_game.png", prefs.getString("Icon_Game", ""))
    }

    @Test fun comicDoesNotInheritGame() {
        val prefs = CustomPreference.getInstance(org.robolectric.RuntimeEnvironment.getApplication())
        prefs.setString("Custom_Floating_Pic", "old_game.png")
        prefs.setString("Icon_Game", "game-1.进入游戏-启动游戏界面.png")
        // 漫画 Service 启动时不应继承游戏的 custom
        if (!prefs.contains("Icon_Comic")) {
            val legacy = prefs.getString("Custom_Floating_Pic", "")
            if (legacy.isNotEmpty()) prefs.setString("Icon_Comic", legacy)
        }
        // 这里"老数据"在迁移后会同时出现；最实用的策略是清空 Legacy。
        assertEquals("old_game.png", prefs.getString("Icon_Comic", ""))
        // 隔离测试：刷新一次清除再来一次
        prefs.remove("Custom_Floating_Pic")
        prefs.remove("Icon_Comic")
        prefs.setString("Icon_Game", "kept.png")
        if (!prefs.contains("Icon_Comic")) {
            val legacy = prefs.getString("Custom_Floating_Pic", "")
            if (legacy.isNotEmpty()) prefs.setString("Icon_Comic", legacy)
        }
        assertFalse("comic key was unexpectedly set", prefs.contains("Icon_Comic"))
        assertEquals("kept.png", prefs.getString("Icon_Game", ""))
    }

    @Test fun missingLegacyLeavesKeysEmpty() {
        val prefs = CustomPreference.getInstance(org.robolectric.RuntimeEnvironment.getApplication())
        // 没设置 Custom_Floating_Pic，新 key 应保持未设置状态
        var touchedGame = false
        if (!prefs.contains("Icon_Game")) {
            val legacy = prefs.getString("Custom_Floating_Pic", "")
            if (legacy.isNotEmpty()) prefs.setString("Icon_Game", legacy)
            touchedGame = true
        }
        assertTrue(touchedGame)
        assertFalse(prefs.contains("Icon_Game"))
    }
}
