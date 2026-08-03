package translationapi.hymt2translation

import android.content.Context
import com.moe.starflow.utils.CustomPreference

/**
 * Hy-MT2 进程级共享实例持有器。
 *
 * 背景：Hy-MT2 模型 440MB，若每个页面/服务各自创建实例，会重复冷加载（每次 14s）且慢 42 倍
 * （冷加载后的模型页处于坏状态）。全 app（游戏/漫画/文本）应共享**同一个**热模型实例。
 *
 * - 实例 keepAlive=true：各调用方 release() 只取消在途任务，不释放模型（模型常驻进程）
 * - 实例仅在本持有器中缓存；引擎设置（Text_API/Text_AI）改变时重建
 * - warmUp() 后台预加载，把冷加载挪到用户翻译前
 */
object HyMT2SharedHolder {

    @Volatile private var instance: HyMT2Translation? = null
    @Volatile private var instanceKey: String? = null

    @Synchronized
    fun get(context: Context, prefs: CustomPreference): HyMT2Translation {
        val key = "api=${prefs.getInt("Text_API", 1)}|ai=${prefs.getInt("Text_AI", 0)}"
        val cur = instance
        if (cur != null && !cur.released && key == instanceKey) return cur
        // 引擎设置变了或实例已被释放 → 释放旧实例（临时关掉 keepAlive 才能真正释放模型）
        cur?.let {
            it.keepAlive = false
            it.release()
        }
        val created = HyMT2Translation(context.applicationContext).also { it.keepAlive = true }
        instance = created
        instanceKey = key
        return created
    }

    /** 后台预加载模型（若当前引擎是 Hy-MT2），把冷加载挪到用户翻译前 */
    fun warmUp(context: Context, prefs: CustomPreference) {
        val textApi = prefs.getInt("Text_API", 1)
        val textAi = prefs.getInt("Text_AI", 0)
        if (textApi != com.moe.starflow.utils.Constants.TextApi.AI.id ||
            textAi != com.moe.starflow.utils.Constants.TextAI.HYMT2.id) return
        get(context, prefs).warmUp()
    }
}
