package com.moe.starflow.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import translationapi.hymt2translation.HyMt2Languages

class HyMt2LanguagesTest {

    @Test
    fun supportedTargetNames() {
        assertEquals("日语", HyMt2Languages.getTargetName("ja"))
        assertEquals("中文", HyMt2Languages.getTargetName("zh"))
        assertEquals("繁体中文", HyMt2Languages.getTargetName("zh-TW"))
        assertEquals("英语", HyMt2Languages.getTargetName("en"))
        assertEquals("韩语", HyMt2Languages.getTargetName("ko"))
        assertEquals("俄语", HyMt2Languages.getTargetName("ru"))
    }

    @Test
    fun unsupportedCodeFallsBackToRawCode() {
        assertEquals("xx", HyMt2Languages.getTargetName("xx"))
        assertEquals("abc", HyMt2Languages.getTargetName("abc"))
    }

    @Test
    fun coversAllOfficial38TargetLanguages() {
        val official = listOf(
            "中文", "英语", "法语", "葡萄牙语", "西班牙语", "日语", "土耳其语", "俄语", "阿拉伯语", "韩语",
            "泰语", "意大利语", "德语", "越南语", "马来语", "印尼语", "菲律宾语", "印地语", "繁体中文", "波兰语",
            "捷克语", "荷兰语", "高棉语", "缅甸语", "波斯语", "古吉拉特语", "乌尔都语", "泰卢固语", "马拉地语", "希伯来语",
            "孟加拉语", "泰米尔语", "乌克兰语", "藏语", "哈萨克语", "蒙古语", "维吾尔语", "粤语",
        )
        val names = HyMt2Languages.supportedNames.toSet()
        official.forEach { assertTrue("missing: $it", it in names) }
    }
}
