package com.moe.starflow.utils

import android.graphics.Bitmap

/**
 * LRU bitmap cache backed by LinkedHashMap in access-order mode.
 *
 * - Auto-evicts eldest entries when size exceeds [maxSize], recycling the evicted bitmap.
 * - Access-order ensures recently used entries stay in cache; cold entries fall to the back.
 * - [retainEntries] proactively removes and recycles orphan entries (retranslate / variant switch).
 * - All public methods are synchronized — safe to call from any thread.
 */
class BitmapLruCache(private val maxSize: Int) {

    companion object {
        private const val TAG = "BitmapLruCache"
    }

    /** access-order=true: every get/put moves entry to front; eldest = least recently accessed */
    private val map = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?): Boolean {
            if (size > maxSize && eldest != null) {
                eldest.value?.recycle()
                LogCollector.d(TAG, "LRU evict: key=${eldest.key}, cacheSize=${size - 1}")
                return true
            }
            return false
        }
    }

    /** Read a cached bitmap (counts as access → moves to front of LRU). */
    @Synchronized
    operator fun get(key: String): Bitmap? = map[key]

    /** Write a bitmap to cache. Recycles the previous value if one exists for this key. */
    @Synchronized
    operator fun set(key: String, value: Bitmap?) {
        val old = map.put(key, value)
        if (old != null && old !== value) {
            old.recycle()
            LogCollector.d(TAG, "set: replaced existing key=$key")
        }
    }

    val size: Int get() = synchronized(this) { map.size }

    /** Recycle all cached bitmaps and empty the cache. Safe to call in onDestroy. */
    @Synchronized
    fun clear() {
        map.values.forEach { it?.recycle() }
        map.clear()
        LogCollector.d(TAG, "clear: all entries recycled")
    }

    /**
     * Remove (and recycle) entries whose keys don't correspond to any ID in [entryIds].
     *
     * Cache key format: "${entryId}_${mode}" — this method extracts the entryId portion
     * and removes any entry whose ID is not in the allowed set.
     *
     * Used to clean up orphans after retranslate / variant switch / page flip.
     */
    @Synchronized
    fun retainEntries(entryIds: Set<Long>) {
        val toRemove = map.keys.filter { key ->
            val entryId = key.substringBefore('_').toLongOrNull()
            entryId == null || entryId !in entryIds
        }
        for (key in toRemove) {
            map.remove(key)?.recycle()
        }
        if (toRemove.isNotEmpty()) {
            LogCollector.d(TAG, "retainEntries: removed ${toRemove.size} orphans, remaining=${map.size}")
        }
    }
}
