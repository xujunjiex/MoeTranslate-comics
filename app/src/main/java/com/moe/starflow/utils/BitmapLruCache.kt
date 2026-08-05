package com.moe.starflow.utils
import com.moe.starflow.translate.widget.*

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
            // ⚠️ 淘汰时只移除条目，不 recycle：eldest bitmap 可能仍被某个 ImageView
            // （ViewPager2 相邻未 detach 的 holder）引用，静默 recycle 会导致
            // "Canvas: trying to use a recycled bitmap" 崩溃。native buffer 交给 GC 回收。
            if (size > maxSize && eldest != null) {
                LogCollector.d(TAG, "LRU evict (no recycle): key=${eldest.key}, cacheSize=${size - 1}")
                return true
            }
            return false
        }
    }

    /** Read a cached bitmap (counts as access → moves to front of LRU). */
    @Synchronized
    operator fun get(key: String): Bitmap? = map[key]

    /** Write a bitmap to cache. 不自动 recycle 旧值：旧 bitmap 可能仍被 ImageView 引用，
     *  静默 recycle 会触发绘制崩溃。同名 key 旧值交给 GC 回收 native buffer。 */
    @Synchronized
    operator fun set(key: String, value: Bitmap?) {
        map.put(key, value)
    }

    /** Remove the entry for a single key. 运行时（重翻译强制重渲染）调用，
     *  不 recycle：旧 bitmap 可能仍被 ViewHolder ImageView 引用，静默 recycle 会触发
     *  "Canvas: trying to use a recycled bitmap" 崩溃。native buffer 交给 GC 回收。 */
    @Synchronized
    fun remove(key: String): Boolean {
        val old = map.remove(key)
        if (old != null) {
            LogCollector.d(TAG, "remove: evicted key=$key (no recycle)")
            return true
        }
        return false
    }

    val size: Int get() = synchronized(this) { map.size }

    /** Recycle all cached bitmaps and empty the cache. 仅在 onDestroy 安全调用：
     *  此时 ViewHolder 已全释放，无 ImageView 引用残留。 */
    @Synchronized
    fun clear() {
        map.values.forEach { it?.recycle() }
        map.clear()
        LogCollector.d(TAG, "clear: all entries recycled")
    }

    /**
     * Remove entries whose keys don't correspond to any ID in [entryIds]. 只移除条目，
     * 不 recycle：被移除的 bitmap 可能仍被已 detach 但尚未被复用/绘制完毕的 ViewHolder
     * 引用，静默 recycle 会触发绘制崩溃。native buffer 交给 GC 回收。
     *
     * Cache key format: "${entryId}_${mode}". Used after retranslate / variant switch / page flip.
     */
    @Synchronized
    fun retainEntries(entryIds: Set<Long>) {
        val toRemove = map.keys.filter { key ->
            val entryId = key.substringBefore('_').toLongOrNull()
            entryId == null || entryId !in entryIds
        }
        for (key in toRemove) {
            map.remove(key)
        }
        if (toRemove.isNotEmpty()) {
            LogCollector.d(TAG, "retainEntries: removed ${toRemove.size} orphans (no recycle), remaining=${map.size}")
        }
    }
}
