package translationapi.hymt2translation

import android.content.Context
import com.moe.starflow.utils.CustomPreference

/**
 * Hy-MT2 进程级共享实例持有器。
 *
 * 背景：Hy-MT2 模型 ~440MB，全 app（游戏/漫画/文本）共享**同一个**热模型实例，
 * 复用前缀 KV 缓存（固定指令只 prefill 一次）并保持模型页热状态。
 *
 * - 实例 keepAlive=true：各调用方 release() 只取消在途任务，不释放模型（模型常驻进程）
 * - 实例仅在本持有器中缓存；引擎设置（Text_API/Text_AI）改变时重建
 * - warmUp() 后台预加载，把加载挪到用户翻译前
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

    /**
     * 引擎设置不再是 Hy-MT2 时释放缓存的共享实例。
     * 用户切换翻译模型（Hy-MT2 → NLLB/API 等）后，旧模型不再有调用方，把 440MB 换出内存。
     * TranslatorFactory.create() 每次调用开头检查（get() 只在 Hy-MT2 分支被调，切走后不会触发）。
     */
    @Synchronized
    fun releaseIfNotCurrent(prefs: CustomPreference) {
        val textApi = prefs.getInt("Text_API", com.moe.starflow.utils.Constants.TextApi.BING.id)
        val textAi = prefs.getInt("Text_AI", com.moe.starflow.utils.Constants.TextAI.NLLB.id)
        val key = "api=$textApi|ai=$textAi"
        val cur = instance
        if (cur != null && !cur.released && key != instanceKey) {
            com.moe.starflow.utils.LogCollector.d("HyMT2SharedHolder", "引擎切换：释放旧 Hy-MT2 实例（$instanceKey → $key）")
            // 先清空 Holder 状态，再后台完整释放（join + nativeRelease + statusOverlay）——
            // 不阻塞 create() 调用线程（文本页 setupEngine 在 Main，同步释放会卡 ~1s）
            instance = null
            instanceKey = null
            cur.keepAlive = false
            Thread { cur.release() }.start()
        }
    }

    /** 后台预加载模型（若当前引擎是 Hy-MT2），把加载挪到用户翻译前 */
    fun warmUp(context: Context, prefs: CustomPreference) {
        val textApi = prefs.getInt("Text_API", 1)
        val textAi = prefs.getInt("Text_AI", 0)
        if (textApi != com.moe.starflow.utils.Constants.TextApi.AI.id ||
            textAi != com.moe.starflow.utils.Constants.TextAI.HYMT2.id) return
        get(context, prefs).warmUp()
    }
}
