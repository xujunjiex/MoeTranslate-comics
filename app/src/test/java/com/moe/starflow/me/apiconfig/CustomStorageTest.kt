package com.moe.starflow.me.apiconfig

import com.moe.starflow.utils.CustomPreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 单元测试：内置 API 自定义模型管理
 *
 * 覆盖：
 *  - removeCustomModelAndAdjustIndex 的 4 种下标规则 + 非法输入
 *  - 序列化往返（saveBuiltInProviderMods / loadBuiltInProviderMods）
 *  - 老数据迁移兼容（JSON 中无 customModels 字段）
 */
@RunWith(RobolectricTestRunner::class)
class CustomStorageTest {

    private lateinit var prefs: CustomPreference

    @Before
    fun setUp() {
        prefs = CustomPreference.getInstance(RuntimeEnvironment.getApplication())
        // 清空相关 key
        prefs.remove("BuiltIn_Providers_Modifications")
    }

    @After
    fun tearDown() {
        prefs.remove("BuiltIn_Providers_Modifications")
    }

    // ============ removeCustomModelAndAdjustIndex ============
    //
    // displayModels 布局：[p0..p_{presetSize-1}][custom[0]..custom[size-1]]
    // 即自定义区下标范围 [presetSize, presetSize + customModels.size)
    // customIndex = deleteIndex - presetSize

    @Test
    fun remove_selectedBeforeDelete_unchanged() {
        // preset=3, custom=[a,b,c] → display=[p0,p1,p2,a,b,c] (size=6)
        // 自定义区下标：3=a, 4=b, 5=c
        // 选 a (idx=3)，删除 c (idx=5, customIndex=2)
        // selected < deleteIndex → 不变
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 3,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 5,
            selectedIndex = 3
        )
        assertEquals(listOf("a", "b"), newCustoms)
        assertEquals(3, newSelected)
    }

    @Test
    fun remove_selectedEqualsDelete_keepIndexPointsToNext() {
        // preset=2, custom=[a,b,c] → display=[p0,p1,a,b,c] (size=5)
        // 自定义区下标：2=a, 3=b, 4=c
        // 选 a (idx=2)，删除 a (idx=2, customIndex=0)
        // selected == deleteIndex → 不变，删除后下标 2 自动指向 b
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 2,
            selectedIndex = 2
        )
        assertEquals(listOf("b", "c"), newCustoms)
        assertEquals(2, newSelected)
    }

    @Test
    fun remove_selectedEqualsDeleteMiddle_keepIndex() {
        // preset=2, custom=[a,b,c] → display=[p0,p1,a,b,c] (size=5)
        // 自定义区下标：2=a, 3=b, 4=c
        // 选 b (idx=3)，删除 b (idx=3, customIndex=1)
        // selected == deleteIndex → 不变，删除后下标 3 自动指向 c
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 3,
            selectedIndex = 3
        )
        assertEquals(listOf("a", "c"), newCustoms)
        assertEquals(3, newSelected)
    }

    @Test
    fun remove_selectedEqualsDeleteLastItem_keepIndexClamped() {
        // preset=2, custom=[a,b,c] → display=[p0,p1,a,b,c] (size=5)
        // 自定义区下标：2=a, 3=b, 4=c
        // 选 c (idx=4)，删除 c (idx=4, customIndex=2)
        // selected == deleteIndex，但删除后下标 4 越界 → 兜底为 3 (b)
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 4,
            selectedIndex = 4
        )
        assertEquals(listOf("a", "b"), newCustoms)
        assertEquals(3, newSelected)
    }

    @Test
    fun remove_selectedAfterDelete_shiftsBack() {
        // preset=2, custom=[a,b,c] → display=[p0,p1,a,b,c] (size=5)
        // 自定义区下标：2=a, 3=b, 4=c
        // 选 c (idx=4)，删除 a (idx=2, customIndex=0)
        // selected > deleteIndex → -1 → 3 (指向新位置 c)
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a", "b", "c"),
            deleteIndex = 2,
            selectedIndex = 4
        )
        assertEquals(listOf("b", "c"), newCustoms)
        assertEquals(3, newSelected)
    }

    @Test
    fun remove_lastCustom_outOfRangeReset() {
        // preset=2, custom=[a] → display=[p0,p1,a] (size=3)
        // 自定义区下标：2=a
        // 选 a (idx=2)，删除 a (idx=2)
        // 删除后 customModels 为空，displaySize=2 (只剩预设)
        // selectedIndex 2 越界，兜底为最后一个预设的下标 1
        val (newCustoms, newSelected) = ConfigurationStorage.removeCustomModelAndAdjustIndex(
            presetSize = 2,
            customModels = listOf("a"),
            deleteIndex = 2,
            selectedIndex = 2
        )
        assertEquals(emptyList<String>(), newCustoms)
        assertEquals(1, newSelected) // 兜底到最后一个预设
    }

    @Test
    fun remove_presetIndex_throws() {
        try {
            ConfigurationStorage.removeCustomModelAndAdjustIndex(
                presetSize = 3,
                customModels = listOf("a"),
                deleteIndex = 1, // 指向预设区
                selectedIndex = 0
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot delete preset") == true)
        }
    }

    // ============ 序列化 ============

    @Test
    fun roundtrip_builtInProviderMod_withCustomModels() {
        val mods = listOf(
            BuiltInProviderMod(
                name = "火山引擎",
                apiKey = "k1",
                systemPrompt = null,
                userPrompt = null,
                mangaSystemPrompt = null,
                mangaUserPrompt = null,
                selectedModelIndex = 2,
                customModels = listOf("custom-1", "custom-2")
            )
        )
        ConfigurationStorage.saveBuiltInProviderMods(prefs, mods)
        val loaded = ConfigurationStorage.loadBuiltInProviderMods(prefs)
        assertEquals(1, loaded.size)
        val first = loaded[0]
        assertEquals("火山引擎", first.name)
        assertEquals("k1", first.apiKey)
        assertEquals(2, first.selectedModelIndex)
        assertEquals(listOf("custom-1", "custom-2"), first.customModels)
    }

    @Test
    fun migration_oldJson_withoutCustomModelsField_returnsEmpty() {
        // 模拟老数据 JSON：无 customModels 字段
        prefs.setString(
            "BuiltIn_Providers_Modifications",
            """[{"name":"DeepSeek","apiKey":"old-key","selectedModelIndex":1}]"""
        )
        val loaded = ConfigurationStorage.loadBuiltInProviderMods(prefs)
        assertEquals(1, loaded.size)
        assertEquals("DeepSeek", loaded[0].name)
        assertEquals("old-key", loaded[0].apiKey)
        assertEquals(1, loaded[0].selectedModelIndex)
        assertEquals(emptyList<String>(), loaded[0].customModels) // 迁移默认空
    }
}
