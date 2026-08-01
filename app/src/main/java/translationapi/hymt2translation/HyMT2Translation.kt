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

    override fun getTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        callback: (TranslationResult) -> Unit
    ) {
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
                val s = HyMt2Params.read(CustomPreference.getInstance(ctx).getSharedPreferences())
                val prompt = HyMt2Prompt.build(s.promptTemplate, targetName, text)
                LogCollector.d(TAG, "Hy-MT2 翻译开始: $sourceLanguage→$targetLanguage, text=$text")
                val result = HyMt2Native.nativeTranslate(
                    h, prompt, s.temperature, s.topP, s.topK, s.repetitionPenalty, s.maxTokens
                ).trim()
                LogCollector.d(TAG, "Hy-MT2 翻译完成: result=$result")
                callback(TranslationResult.Success(result))
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
            statusOverlay.showImmediate(ctx.getString(R.string.hymt2_model_loading), autoDismiss = false)
            handle = HyMt2Native.nativeInit(modelFile.absolutePath, s.threads, s.contextSize)
            if (handle == 0L) {
                statusOverlay.showError(ctx.getString(R.string.hymt2_init_failed))
            } else {
                statusOverlay.show(ctx.getString(R.string.hymt2_loaded))
            }
            return handle
        }
    }

    override fun cancelTranslation() {
        currentTask?.let { if (it.isAlive) it.interrupt() }
        currentTask = null
    }

    override fun release() {
        cancelTranslation()
        // 排空在途翻译：等当前线程退出 native 临界区再释放模型，避免 use-after-free
        currentTask?.let { task ->
            if (task.isAlive) {
                try {
                    task.join(2000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        currentTask = null
        synchronized(initLock) {
            if (handle != 0L) {
                HyMt2Native.nativeRelease(handle)
                handle = 0L
            }
        }
        statusOverlay.release()
    }

    companion object {
        private const val TAG = "HyMT2Translation"
    }
}
