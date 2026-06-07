package com.moe.moetranslator.utils

/**
 * Text similarity utility based on Levenshtein edit distance.
 *
 * Provides methods to compare OCR text results and determine whether two
 * strings are sufficiently similar — useful for deduplicating translation
 * cache entries or detecting whether an OCR result has changed between frames.
 *
 * Example usage:
 * ```
 * val a = "Hello World"
 * val b = "Hello Worl"
 * println(TextSimilarity.similarity(a, b))   // ~0.909
 * println(TextSimilarity.isSimilar(a, b))    // true  (default threshold 0.9)
 * println(TextSimilarity.isSimilar(a, b, threshold = 0.95f)) // false
 * ```
 */
object TextSimilarity {

    /**
     * Normalize a string for comparison:
     * - Trims leading/trailing whitespace.
     * - Collapses consecutive whitespace and newline characters into a single space.
     *
     * @param s The input string to normalize.
     * @return The normalized string.
     */
    fun normalize(s: String): String {
        return s.trim().replace(Regex("\\s+"), " ").lowercase()
    }

    /**
     * Compute the Levenshtein edit distance between two strings.
     *
     * Uses a space-optimized two-row approach (previous + current) instead
     * of the full m*n matrix, reducing space complexity to O(min(m, n)).
     *
     * Time complexity: O(m * n) where m and n are the lengths of [a] and [b].
     *
     * @param a The first string.
     * @param b The second string.
     * @return The minimum number of single-character edits (insertions,
     *         deletions, or substitutions) required to transform [a] into [b].
     */
    fun levenshtein(a: String, b: String): Int {
        // Ensure a is the shorter string to minimize array size.
        if (a.length > b.length) return levenshtein(b, a)

        // Edge cases: one or both strings are empty.
        if (a.isEmpty()) return b.length

        // prev and curr arrays — only two rows needed at any time.
        var prev = IntArray(a.length + 1) { it }   // row for j = 0
        var curr = IntArray(a.length + 1)

        for (i in 1..b.length) {
            curr[0] = i
            for (j in 1..a.length) {
                val cost = if (a[j - 1] == b[i - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,       // deletion
                    curr[j - 1] + 1,   // insertion
                    prev[j - 1] + cost  // substitution
                )
            }
            // Swap rows for next iteration.
            val temp = prev
            prev = curr
            curr = temp
        }

        return prev[a.length]
    }

    /**
     * Compute the similarity ratio between two strings as a value from 0.0 to 1.0.
     *
     * Formula: `1.0 - (levenshtein(a, b) / max(a.length, b.length))`
     *
     * - Both empty → 1.0 (identical).
     * - One empty, one non-empty → 0.0 (completely different).
     *
     * @param a The first string.
     * @param b The second string.
     * @return A float in [0.0, 1.0] where 1.0 means identical strings.
     */
    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1.0f
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 0.0f
        return 1.0f - (levenshtein(a, b).toFloat() / maxLen.toFloat())
    }

    /**
     * Determine whether two strings are similar enough to be considered "the same".
     *
     * Both inputs are normalized before comparison. Special handling:
     * - Both normalized empty → true (vacuously the same).
     * - One empty, one non-empty → false.
     * - Both shorter than 3 characters → exact match required (avoid false
     *   positives on very short strings where a single edit dominates).
     * - Otherwise → similarity >= [threshold].
     *
     * @param a         The first string.
     * @param b         The second string.
     * @param threshold Minimum similarity ratio to qualify as "similar" (default 0.9).
     * @return true if the strings are considered similar, false otherwise.
     */
    fun isSimilar(a: String, b: String, threshold: Float = 0.9f): Boolean {
        val na = normalize(a)
        val nb = normalize(b)

        // Both normalized to empty — treat as identical.
        if (na.isEmpty() && nb.isEmpty()) return true

        // One is empty, the other is not — not similar.
        if (na.isEmpty() != nb.isEmpty()) return false

        // Very short strings: require exact match to avoid spurious similarity.
        if (na.length < 3 && nb.length < 3) return na == nb

        return similarity(na, nb) >= threshold
    }
}
