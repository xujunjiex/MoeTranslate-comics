package translationapi.hymt2translation

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelKey
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.TranslationStatusOverlay

class HyMT2Translation(context: Context) : TranslationTextAPI {
    private val ctx = context.applicationContext
    private val statusOverlay = TranslationStatusOverlay.getInstance(ctx)
    private val initLock = Any()
    @Volatile private var handle: Long = 0L
    @Volatile private var currentTask: Thread? = null
    @Volatile private var cancelled = false
    /** 任务世代号：cancelTranslation()/release() 递增，使在途任务的结果失效，避免旧线程误交付 Success */
    @Volatile private var currentEpoch = 0L
    /** 正在 native 调用中的任务数：release() 等待其归零后才释放模型，杜绝 use-after-free */
    @Volatile private var inFlight = 0
    /** 是否已 release（共享持有器据此判断是否需重建实例） */
    @Volatile var released = false

    override fun getTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    ) = translateInternal(text, sourceLanguage, targetLanguage, onPhase = null, onPartial = null, callback)

    override fun getTranslationStreaming(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        onPhase: (String) -> Unit,
        onPartial: (String) -> Unit,
        callback: (TranslationResult) -> Unit
    ) = translateInternal(text, sourceLanguage, targetLanguage, onPhase, onPartial, callback)

    private fun translateInternal(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        onPhase: ((String) -> Unit)?,
        onPartial: ((String) -> Unit)?,
        callback: (TranslationResult) -> Unit
    ) {
        cancelled = false  // 每次新翻译重置取消标志
        val epoch = currentEpoch  // 本任务所属世代
        val targetName = HyMt2Languages.getTargetName(targetLanguage)
        currentTask = Thread {
            try {
                val h = ensureLoaded()
                if (h == 0L) {
                    val downloaded = ModelDownloadRepository.getInstance(ctx)
                        .isFullyDownloaded(ModelKey.HY_MT2_GROUP)
                    val msg = if (downloaded) {
                        ctx.getString(R.string.hymt2_init_failed)
                    } else {
                        ctx.getString(R.string.hymt2_not_download_translate)
                    }
                    callback(TranslationResult.Error(Exception(msg)))
                    return@Thread
                }
                // 进入 native 前登记在途调用：release() 会在 inFlight 归零后才释放模型，
                // 且此处重新校验 handle/世代，杜绝 release() 后仍用已释放句柄（use-after-free）。
                // 登记 + 调用 + 递减包在同一 try/finally，保证任何异常路径都释放 inFlight。
                var registered = false
                try {
                    val nativeHandle = synchronized(initLock) {
                        if (handle != 0L && currentEpoch == epoch) {
                            inFlight++
                            registered = true
                            handle
                        } else 0L
                    }
                    if (nativeHandle == 0L) {
                        callback(TranslationResult.Error(Exception("翻译引擎已释放")))
                        return@Thread
                    }
                    val s = HyMt2Params.read(CustomPreference.getInstance(ctx).getSharedPreferences())
                    val prompt = HyMt2Prompt.build(s.promptTemplate, targetName, text)
                    val prefix = HyMt2Prompt.buildPrefix(s.promptTemplate, targetName)  // 固定指令前缀，用于前缀 KV 缓存
                    LogCollector.d(TAG, "Hy-MT2 翻译开始: $sourceLanguage→$targetLanguage, text=$text")
                    val result = if (onPartial != null) {
                        val cb = object : HyMt2StreamCallback {
                            override fun onPhase(phase: String) { onPhase?.invoke(phase) }
                            override fun onToken(text: String) { onPartial(text) }
                        }
                        HyMt2Native.nativeTranslateStreaming(
                            nativeHandle, prompt, prefix, s.temperature, s.topP, s.topK, s.repetitionPenalty, s.maxTokens, cb
                        )
                    } else {
                        HyMt2Native.nativeTranslate(
                            nativeHandle, prompt, prefix, s.temperature, s.topP, s.topK, s.repetitionPenalty, s.maxTokens
                        )
                    }.trim()
                    LogCollector.d(TAG, "Hy-MT2 翻译完成: result=$result")
                    if (cancelled || currentEpoch != epoch) {
                        // 本任务已取消/被后续任务取代：丢弃结果
                        LogCollector.d(TAG, "Hy-MT2 翻译已取消，丢弃结果")
                        callback(TranslationResult.Error(Exception("翻译已取消")))
                    } else {
                        callback(TranslationResult.Success(result))
                    }
                } finally {
                    if (registered) synchronized(initLock) { inFlight-- }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Hy-MT2 翻译异常: ${e.message}", e)
                callback(TranslationResult.Error(e))
            }
        }.apply { start() }
    }

    /** 懒加载：首次翻译才初始化模型。失败返回 0。 */
    private fun ensureLoaded(): Long {
        if (handle != 0L) return handle
        synchronized(initLock) {
            if (handle != 0L) return handle
            if (!ModelDownloadRepository.getInstance(ctx).isFullyDownloaded(ModelKey.HY_MT2_GROUP)) {
                statusOverlay.showError(ctx.getString(R.string.hymt2_not_download_translate))
                return 0L
            }
            val s = HyMt2Params.read(CustomPreference.getInstance(ctx).getSharedPreferences())
            val modelFile = ModelDownloadRepository.getInstance(ctx).targetFilePath(ModelKey.HY_MT2_GROUP)
            // 不占用共享状态浮层：加载约 2s，由游戏/漫画流程自己的"翻译中"提示覆盖，避免把进度提示顶掉
            val epochAtEntry = currentEpoch
            handle = HyMt2Native.nativeInit(modelFile.absolutePath, s.threads, s.contextSize)
            if (handle == 0L) {
                statusOverlay.showError(ctx.getString(R.string.hymt2_init_failed))
                return 0L
            }
            if (currentEpoch != epochAtEntry) {
                // release()/cancelTranslation() 在加载期间发生：立即释放刚加载的模型，防止泄漏
                HyMt2Native.nativeRelease(handle)
                handle = 0L
                return 0L
            }
            return handle
        }
    }

    override fun cancelTranslation() {
        currentEpoch++  // 使所有在途任务结果失效，不会被误当作 Success 交付
        cancelled = true
        val task = currentTask
        if (task?.isAlive == true) task.interrupt()
        currentTask = null
        synchronized(initLock) {
            if (handle != 0L) HyMt2Native.nativeAbort(handle)
        }
    }

    override fun release() {
        // 先捕获在途线程再取消：cancelTranslation() 会置空 currentTask，必须先捕获才能 join
        val task = currentTask
        cancelTranslation()
        task?.let { t ->
            if (t.isAlive) {
                try {
                    t.join(3000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        currentTask = null
        // 置零句柄：阻止新的 native 调用进入（登记处会校验 handle/世代）
        val oldHandle: Long
        synchronized(initLock) {
            oldHandle = handle
            handle = 0L
        }
        // 等所有已登记的 native 调用退出（abort 已生效，通常 <1s），再释放模型，杜绝 use-after-free
        val deadline = System.currentTimeMillis() + 3000
        while (synchronized(initLock) { inFlight } > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (oldHandle != 0L) {
            synchronized(initLock) {
                HyMt2Native.nativeRelease(oldHandle)
            }
        }
        released = true
        statusOverlay.release()
    }

    companion object {
        private const val TAG = "HyMT2Translation"
    }
}
