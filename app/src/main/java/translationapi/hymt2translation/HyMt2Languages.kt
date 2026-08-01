package translationapi.hymt2translation

/**
 * ISO 639 代码 → Hy-MT2 提示词中的中文目标语言名（对应桌面端 38 个目标语言选项）。
 * 仅用于拼装翻译提示词，不参与任何语言选择 UI。
 */
object HyMt2Languages {
    private val TARGET_NAMES: Map<String, String> = mapOf(
        "zh" to "中文",
        "zh-TW" to "繁体中文",
        "en" to "英语",
        "ja" to "日语",
        "ko" to "韩语",
        "ru" to "俄语",
        "fr" to "法语",
        "pt" to "葡萄牙语",
        "es" to "西班牙语",
        "tr" to "土耳其语",
        "ar" to "阿拉伯语",
        "th" to "泰语",
        "it" to "意大利语",
        "de" to "德语",
        "vi" to "越南语",
        "ms" to "马来语",
        "id" to "印尼语",
        "fil" to "菲律宾语",
        "tl" to "菲律宾语",
        "hi" to "印地语",
        "pl" to "波兰语",
        "cs" to "捷克语",
        "nl" to "荷兰语",
        "km" to "高棉语",
        "my" to "缅甸语",
        "fa" to "波斯语",
        "gu" to "古吉拉特语",
        "ur" to "乌尔都语",
        "te" to "泰卢固语",
        "mr" to "马拉地语",
        "he" to "希伯来语",
        "bn" to "孟加拉语",
        "ta" to "泰米尔语",
        "uk" to "乌克兰语",
        "bo" to "藏语",
        "kk" to "哈萨克语",
        "mn" to "蒙古语",
        "ug" to "维吾尔语",
        "yue" to "粤语",
    )

    val supportedNames: Collection<String>
        get() = TARGET_NAMES.values

    /** 取目标语言中文名；不在 38 种内时回退原代码（翻译质量不保证，接受）。 */
    fun getTargetName(code: String): String = TARGET_NAMES[code] ?: code
}
