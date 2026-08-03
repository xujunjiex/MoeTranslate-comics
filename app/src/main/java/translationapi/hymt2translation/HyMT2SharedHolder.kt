package translationapi.hymt2translation

import android.content.Context
import com.moe.starflow.utils.CustomPreference

/**
 * Hy-MT2 进程级共享实例持有器。
 *
 * 背景：Hy-MT2 模型 440MB，若每个页面/服务各自创建实例，会重复加载（每次 14s 冷读）且可能双实例占 880MB。
 * 文本翻译页等常驻使用场景应通过本持有器获取**唯一**实例：跨页面切换不释放、不重载。
 *
 * - 实例仅在本持有器中缓存；引擎设置（Text_API/Text_AI）改变时重建
 * - 实例被外部 release() 后（released=true），下次 get() 重建
 * - 不主动释放（模型常驻进程，直到引擎切换）
 */
object HyMT2SharedHolder {

    @Volatile private var instance: HyMT2Translation? = null
    @Volatile private var instanceKey: String? = null

    @Synchronized
    fun get(context: Context, prefs: CustomPreference): HyMT2Translation {
        val key = "api=${prefs.getInt("Text_API", 1)}|ai=${prefs.getInt("Text_AI", 0)}"
        val cur = instance
        if (cur != null && !cur.released && key == instanceKey) return cur
        cur?.release()  // 引擎设置变了或实例已被释放 → 释放旧实例
        val created = HyMT2Translation(context.applicationContext)
        instance = created
        instanceKey = key
        return created
    }
}
