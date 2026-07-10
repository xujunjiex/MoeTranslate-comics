package com.moe.starflow.utils

/**
 * OCR-aware text similarity utility.
 *
 * Provides methods to compare OCR text results with character-level tolerance
 * for common OCR errors (similar-looking characters, fullwidth/halfwidth, etc.).
 *
 * Key features:
 * - Weighted Levenshtein distance: similar characters have lower substitution cost
 * - OCR confusion groups: Japanese kana, Chinese similar chars, digits, punctuation
 * - Fullwidth/Halfwidth normalization
 * - Adaptive thresholds based on text length
 */
object TextSimilarity {

    // ========== OCR Character Confusion Groups ==========
    // Characters within the same group are considered "similar" with reduced substitution cost.

    /** Japanese kana confusion groups */
    private val KANA_CONFUSIONS = listOf(
        "カ力タ夕ロ口ニ二ハ八ホ木",
        "ンソヌスシツオ才",
        "エ工キ丰サ七セ世",
        "ノ乃ヒ匕ミ三ム厶メ女",
        "ラリルレヲ",
        "ガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポ"
    )

    /** Chinese similar-looking character groups */
    private val CJK_CONFUSIONS = listOf(
        "未末", "己已巳", "大太犬", "天夫", "日曰",
        "田由甲申", "入人", "刀力", "午牛", "土士",
        "干千于", "王玉主", "白自", "目日且",
        "贝见页", "几九儿", "八入", "又叉",
        "了子", "马与", "车东", "长张",
        "办为", "方万", "元无", "云去", "五王",
        "开井", "共其", "兴与", "内肉", "冈网",
        "再冉", "冒曼", "最取", "从丛", "众从",
        "全金", "令今", "会合", "舍合", "命令",
        "向问", "因困", "团困", "围国", "图国",
        "固困", "圆图", "圈圆", "四回", "夫天",
        "失夫", "央夫", "头夫", "夷大", "夸大",
        "奋大", "套大", "奠大", "奢大", "奥大",
        "女大", "奴女", "奸女", "她女", "好女",
        "如女", "妇女", "妈女", "妊女", "妒女",
        "妙女", "妥女", "妨女", "妩女", "妹女",
        "姊女", "始女", "姐女", "姑女", "姓女",
        "委女", "姚女", "姜女", "姝女", "姣女",
        "姥女", "姨女", "姬女", "姻女", "姿女",
        "威女", "娃女", "娄女", "娇女", "娘女",
        "娟女", "娠女", "娥女", "娩女", "娱女",
        "娲女", "娴女", "娶女", "婴女", "婆女",
        "婶女", "婉女", "婚女", "媒女", "媚女",
        "媳女", "嫁女", "嫂女", "嫉女", "嫌女",
        "嫡女", "嫣女", "嫦女", "嫩女", "嬉女",
        "嬗女", "嬴女", "嬷女", "孀女"
    )

    /** Digit/Letter confusion groups */
    private val ALNUM_CONFUSIONS = listOf(
        "0OoQ", "1lIi|", "5Ss", "8B", "6G", "2Zz",
        "rn m", "cl d", "vv w", "Il1", "O0"
    )

    /** Punctuation confusion groups */
    private val PUNCTUATION_CONFUSIONS = listOf(
        ",.，。", ";:；:", "'\"「」『』", "-–—~～",
        "（）()", "【】[]", "！!？?", "、,"
    )

    /** Fullwidth to halfwidth mapping */
    private val FULLWIDTH_MAP: Map<Char, Char> = buildMap {
        // Fullwidth ASCII (Ａ-Ｚ, ａ-ｚ, ０-９)
        for (i in 0..25) {
            put(('Ａ'.code + i).toChar(), ('A'.code + i).toChar())
            put(('ａ'.code + i).toChar(), ('a'.code + i).toChar())
        }
        for (i in 0..9) {
            put(('０'.code + i).toChar(), ('0'.code + i).toChar())
        }
        // Fullwidth punctuation
        put('　', ' ')
        put('，', ',')
        put('。', '.')
        put('！', '!')
        put('？', '?')
        put('；', ';')
        put('：', ':')
        put('（', '(')
        put('）', ')')
        put('【', '[')
        put('】', ']')
        put('「', '"')
        put('」', '"')
        put('『', '\'')
        put('』', '\'')
        put('＋', '+')
        put('－', '-')
        put('＝', '=')
        put('＊', '*')
        put('／', '/')
        put('＼', '\\')
        put('｜', '|')
        put('＆', '&')
        put('＾', '^')
        put('％', '%')
        put('＄', '$')
        put('＃', '#')
        put('＠', '@')
        put('～', '~')
        put('｀', '`')
    }

    /** Build confusion lookup: character → set of confusable characters */
    private val confusionLookup: Map<Char, Set<Char>> by lazy {
        val lookup = mutableMapOf<Char, MutableSet<Char>>()

        // Process all confusion groups
        for (group in KANA_CONFUSIONS + CJK_CONFUSIONS + ALNUM_CONFUSIONS + PUNCTUATION_CONFUSIONS) {
            val chars = group.filter { it != ' ' }.toSet()
            if (chars.size < 2) continue

            // Add all characters in this group as confusable with each other
            for (ch in chars) {
                val existing = lookup.getOrPut(ch) { mutableSetOf() }
                existing.addAll(chars)
                existing.remove(ch)  // Don't include self
            }
        }

        lookup
    }

    // ========== Core Functions ==========

    /**
     * Normalize a string for comparison:
     * - Trim leading/trailing whitespace
     * - Collapse consecutive whitespace into single space
     * - Convert fullwidth characters to halfwidth
     * - Lowercase
     */
    fun normalize(s: String): String {
        return s.trim()
            .replace(Regex("\\s+"), " ")
            .map { FULLWIDTH_MAP[it] ?: it }
            .joinToString("")
            .lowercase()
    }

    /**
     * Check if two characters are "confusable" (belong to the same confusion group).
     */
    private fun areConfusable(a: Char, b: Char): Boolean {
        if (a == b) return true
        val confusableWithA = confusionLookup[a] ?: return false
        return confusableWithA.contains(b)
    }

    /**
     * Get substitution cost between two characters.
     * - Same character → 0.0
     * - Confusable characters → 0.3
     * - Different characters → 1.0
     */
    private fun substitutionCost(a: Char, b: Char): Float {
        if (a == b) return 0.0f
        if (areConfusable(a, b)) return 0.3f
        return 1.0f
    }

    /**
     * Weighted Levenshtein distance with early exit.
     *
     * Unlike standard Levenshtein, substitution cost varies:
     * - Same character: 0
     * - Confusable characters (e.g., カ/力): 0.3
     * - Different characters: 1.0
     *
     * @param a First string (normalized)
     * @param b Second string (normalized)
     * @param maxCost Maximum allowed cost. If exceeded, returns maxCost + 1 early.
     * @return The weighted edit distance, or maxCost + 1 if exceeded.
     */
    fun weightedLevenshtein(a: String, b: String, maxCost: Float): Float {
        if (a == b) return 0.0f
        if (a.isEmpty()) return b.length.toFloat()
        if (b.isEmpty()) return a.length.toFloat()

        // Ensure a is shorter for smaller array
        if (a.length > b.length) return weightedLevenshtein(b, a, maxCost)

        val m = a.length
        val n = b.length

        // Use two-row approach
        var prev = FloatArray(m + 1) { it.toFloat() }
        var curr = FloatArray(m + 1)

        for (i in 1..n) {
            curr[0] = i.toFloat()
            var rowMin = curr[0]

            for (j in 1..m) {
                val cost = substitutionCost(a[j - 1], b[i - 1])
                curr[j] = minOf(
                    prev[j] + 1.0f,       // deletion
                    curr[j - 1] + 1.0f,   // insertion
                    prev[j - 1] + cost     // substitution
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }

            // Early exit: if minimum value in current row exceeds maxCost,
            // the final result will also exceed it
            if (rowMin > maxCost) return maxCost + 1.0f

            // Swap rows
            val temp = prev
            prev = curr
            curr = temp
        }

        return prev[m]
    }

    /**
     * Standard Levenshtein distance (unweighted).
     */
    fun levenshtein(a: String, b: String): Int {
        if (a.length > b.length) return levenshtein(b, a)
        if (a.isEmpty()) return b.length

        var prev = IntArray(a.length + 1) { it }
        var curr = IntArray(a.length + 1)

        for (i in 1..b.length) {
            curr[0] = i
            for (j in 1..a.length) {
                val cost = if (a[j - 1] == b[i - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }

        return prev[a.length]
    }

    /**
     * Compute similarity ratio (0.0 to 1.0) using weighted Levenshtein.
     */
    fun weightedSimilarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() && nb.isEmpty()) return 1.0f
        val maxLen = maxOf(na.length, nb.length)
        if (maxLen == 0) return 0.0f
        val distance = weightedLevenshtein(na, nb, maxLen.toFloat())
        return (1.0f - distance / maxLen).coerceIn(0.0f, 1.0f)
    }

    /**
     * Compute similarity ratio (0.0 to 1.0) using standard Levenshtein.
     */
    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1.0f
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 0.0f
        return 1.0f - (levenshtein(a, b).toFloat() / maxLen.toFloat())
    }

    /**
     * Get adaptive threshold based on text length.
     *
     * Returns the maximum allowed weighted distance for fuzzy matching:
     * - len 1: 0.3 (allow 1 confusable character)
     * - len 2: 0.3 (allow 1 confusable character)
     * - len 3-5: 0.6 (allow 2 confusable or 1 regular error)
     * - len 6-10: 1.0 (allow 1 error)
     * - len 11-20: 1.5 (allow 2 errors)
     * - len > 20: len * 0.08 (allow more errors for longer text)
     */
    fun getAdaptiveThreshold(textLength: Int): Float {
        return when {
            textLength <= 2 -> 0.3f  // Allow 1 confusable character
            textLength <= 5 -> 0.6f
            textLength <= 10 -> 1.0f
            textLength <= 20 -> 1.5f
            else -> (textLength * 0.08f).coerceAtLeast(2.0f)
        }
    }

    /**
     * Check if two strings are similar using OCR-aware weighted matching.
     *
     * This is the main entry point for OCR text comparison. It:
     * 1. Normalizes both strings (fullwidth→halfwidth, lowercase, trim)
     * 2. Computes weighted Levenshtein distance with adaptive threshold
     * 3. Returns true if distance ≤ threshold
     *
     * @param a First string
     * @param b Second string
     * @param customThreshold Optional custom threshold. If null, uses adaptive threshold.
     * @return true if strings are considered similar
     */
    fun isOcrSimilar(a: String, b: String, customThreshold: Float? = null): Boolean {
        val na = normalize(a)
        val nb = normalize(b)

        // Both empty → similar
        if (na.isEmpty() && nb.isEmpty()) return true

        // One empty, one not → not similar
        if (na.isEmpty() != nb.isEmpty()) return false

        // Quick exact check
        if (na == nb) return true

        // Length difference quick reject
        val maxLen = maxOf(na.length, nb.length)
        val minLen = minOf(na.length, nb.length)
        val threshold = customThreshold ?: getAdaptiveThreshold(maxLen)

        // If length difference exceeds threshold, can't match
        if ((maxLen - minLen).toFloat() > threshold) return false

        // Weighted Levenshtein with early exit
        val distance = weightedLevenshtein(na, nb, threshold)
        return distance <= threshold
    }

    /**
     * Standard similarity check (unweighted Levenshtein).
     */
    fun isSimilar(a: String, b: String, threshold: Float = 0.9f): Boolean {
        val na = normalize(a)
        val nb = normalize(b)

        if (na.isEmpty() && nb.isEmpty()) return true
        if (na.isEmpty() != nb.isEmpty()) return false
        if (na.length < 3 && nb.length < 3) return na == nb

        return similarity(na, nb) >= threshold
    }

    /**
     * Find the best fuzzy match from a list of candidates.
     *
     * @param target The target string to match
     * @param candidates List of candidate strings
     * @param customThreshold Optional custom threshold
     * @return Pair of (best match, distance) or null if no match found
     */
    fun findBestMatch(target: String, candidates: List<String>, customThreshold: Float? = null): Pair<String, Float>? {
        val nTarget = normalize(target)
        if (nTarget.isEmpty()) return null

        val threshold = customThreshold ?: getAdaptiveThreshold(nTarget.length)
        var bestMatch: String? = null
        var bestDistance = threshold + 1.0f

        for (candidate in candidates) {
            val nCandidate = normalize(candidate)

            // Length quick reject
            if (kotlin.math.abs(nTarget.length - nCandidate.length).toFloat() > threshold) continue

            val distance = weightedLevenshtein(nTarget, nCandidate, bestDistance)
            if (distance < bestDistance) {
                bestDistance = distance
                bestMatch = candidate
            }
        }

        return if (bestMatch != null && bestDistance <= threshold) {
            Pair(bestMatch, bestDistance)
        } else {
            null
        }
    }
}
