package com.moe.starflow.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.data.TextTranslateRecord
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.databinding.FragmentTextTranslateBinding
import com.moe.starflow.manga.MangaFloatingService
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.ServiceUtils
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.launch
import translationapi.TranslatorFactory

/**
 * 文本翻译页：粘贴文本 → 翻译，上下双栏。
 * 引擎用全局配置（TranslatorFactory.Mode.TEXT），页面显示引擎名 + 本地/API 徽标。
 * 语言选择用 app 自带的 LanguageSelectionDialog（无下拉过滤问题）。
 * Hy-MT2 等本地模型流式输出；最近记录存 text_translate_record 表。
 */
class TextTranslateFragment : Fragment() {

    private var binding: FragmentTextTranslateBinding? = null
    private var translator: TranslationTextAPI? = null
    private var lastEngineKey: String? = null
    /** 是否正在翻译（MainActivity 拦截底部导航切换用） */
    @Volatile var isTranslating = false
    private var leaveDialogShowing = false
    private var pendingLeave: (() -> Unit)? = null
    private var forceLeaveCancelled = false
    /** 流式 UI 节流：最多每 80ms 更新一次输出框，避免 Main 布局工作抢占解码线程 CPU/内存带宽 */
    @Volatile private var lastUiUpdate = 0L

    // 当前源/目标语言码（语言选择对话框更新；⇄ 互换时对调）
    private var srcCode = "ja"
    private var tgtCode = "zh"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentTextTranslateBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return
        setupLanguageSelectors(b)
        setupEngine(b)
        setupActions(b)
    }

    override fun onResume() {
        super.onResume()
        // 重新进入页面时重读 prefs：引擎/语言改动跟随
        setupEngine(binding ?: return)
        reloadRecent()
    }

    override fun onDestroyView() {
        // Hy-MT2 走进程级共享实例（HyMT2SharedHolder）：跨页面切换保留模型，避免每次重载 440MB。
        // 共享实例页面销毁不调 release()（keepAlive 模型常驻）→ 不在 Main 线程 join/nativeRelease 阻塞、
        // 也不清掉游戏/漫画服务正在显示的共享状态浮层。
        if (translator != null && translator !is translationapi.hymt2translation.HyMT2Translation) {
            translator?.release()
        }
        translator = null
        super.onDestroyView()
        binding = null
    }

    // ========== 语言选择 ==========

    private fun setupLanguageSelectors(b: FragmentTextTranslateBinding) {
        val prefs = CustomPreference.getInstance(requireContext())
        // 优先用文本页自己保存的语言选择（跨页面切换持久，不恢复默认），从未设置过则跟随全局语言设置
        srcCode = resolveTextLang(
            prefs.getString(KEY_TEXT_SOURCE_LANG, ""),
            prefs.getString("Source_Language", "ja"),
            "ja"
        )
        tgtCode = resolveTextLang(
            prefs.getString(KEY_TEXT_TARGET_LANG, ""),
            prefs.getString("Target_Language", "zh"),
            "zh"
        )
        updateLangLabels(b)
        b.srcLangButton.setOnClickListener { showLanguageDialog(1) }
        b.tgtLangButton.setOnClickListener { showLanguageDialog(2) }
    }

    /** 持久化文本页自己的语言选择：切换页面后不恢复默认 */
    private fun persistLangSelection() {
        val prefs = CustomPreference.getInstance(requireContext())
        prefs.setString(KEY_TEXT_SOURCE_LANG, srcCode)
        prefs.setString(KEY_TEXT_TARGET_LANG, tgtCode)
    }

    private fun updateLangLabels(b: FragmentTextTranslateBinding) {
        b.srcLangButton.text = CustomLocale.getInstance(srcCode).getDisplayName()
        b.tgtLangButton.text = CustomLocale.getInstance(tgtCode).getDisplayName()
    }

    private fun showLanguageDialog(type: Int) {
        val list = TranslateTools.getLanguagesList(requireContext(), type) ?: return
        // 目标语言(type=2)：Hy-MT2 只支持官方 38 种，白名单置灰其余（此前文本页无过滤，可选中模型不支持的语种输出垃圾）
        val prefs = CustomPreference.getInstance(requireContext())
        val isHyMt2 = prefs.getInt("Text_API", 1) == com.moe.starflow.utils.Constants.TextApi.AI.id &&
            prefs.getInt("Text_AI", 0) == com.moe.starflow.utils.Constants.TextAI.HYMT2.id
        val enabled = if (type == 2 && isHyMt2) {
            list.map { it.getOriCode() in translationapi.hymt2translation.HyMt2Languages.supportedCodes }
        } else null
        LanguageSelectionDialog(requireContext(), type, list, enabled = enabled) { locale ->
            if (type == 1) {
                srcCode = locale.getOriCode()
                if (srcCode == tgtCode) tgtCode = "zh"
            } else {
                tgtCode = locale.getOriCode()
                if (tgtCode == srcCode) srcCode = "ja"
            }
            persistLangSelection()
            updateLangLabels(binding ?: return@LanguageSelectionDialog)
        }.show()
    }

    private fun setupEngine(b: FragmentTextTranslateBinding) {
        val prefs = CustomPreference.getInstance(requireContext())
        val key = "api=${prefs.getInt("Text_API", 1)}|ai=${prefs.getInt("Text_AI", 0)}"
        if (key == lastEngineKey && translator != null) return
        lastEngineKey = key
        translator?.release()
        translator = TranslatorFactory.createForText(requireContext(), prefs)
        val t = translator
        if (t != null) {
            b.engineDisplay.text = TranslatorFactory.engineLabel(requireContext(), prefs)
            b.engineBadge.text = if (TranslatorFactory.isLocal(t))
                getString(R.string.text_translate_local_badge) else getString(R.string.text_translate_api_badge)
            b.engineBadge.setBackgroundColor(
                if (TranslatorFactory.isLocal(t)) 0xFF2E7D32.toInt() else 0xFF1565C0.toInt()
            )
        } else {
            b.engineDisplay.text = getString(R.string.text_translate_engine_loading)
            b.engineBadge.text = ""
        }
    }

    private fun setupActions(b: FragmentTextTranslateBinding) {
        b.translateButton.setOnClickListener { translate() }
        b.clearButton.setOnClickListener {
            b.inputEdit.setText("")
            b.outputText.text = ""
        }
        b.swapButton.setOnClickListener { swapLanguages() }
        b.recentLimitButton.setOnClickListener { showLimitDialog() }
        b.recentClearButton.setOnClickListener { confirmClearRecent() }
        b.prevPageButton.setOnClickListener {
            if (currentPage > 0) { currentPage--; reloadRecent() }
        }
        b.nextPageButton.setOnClickListener {
            if (currentPage < totalPages - 1) { currentPage++; reloadRecent() }
        }
    }

    // ========== 翻译 ==========

    private fun translate() {
        val b = binding ?: return
        if (isTranslating) return  // 翻译中忽略再次点击
        val text = b.inputEdit.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            b.outputText.text = getString(R.string.text_translate_input_hint)
            return
        }
        if (srcCode == tgtCode) {
            b.outputText.text = getString(R.string.text_translate_same_language)
            return
        }
        // 运行服务 guard：游戏/漫画翻译运行中 → 提示先关闭
        if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) ||
            ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)) {
            val dialog = android.app.AlertDialog.Builder(requireContext())
                .setMessage(R.string.text_translate_service_running)
                .setPositiveButton(R.string.user_known, null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            return
        }
        val t = translator ?: run {
            b.outputText.text = getString(R.string.text_translate_engine_init_failed)
            return
        }
        forceLeaveCancelled = false
        isTranslating = true
        lastUiUpdate = 0L  // 重置节流计时：上一次翻译的残留时间戳不吞掉本次首段输出
        val tStart = System.currentTimeMillis()
        // 内存诊断：确认是否因可用内存不足导致模型堆页被换出（解码极慢）
        logMemory("translate 前")
        LogCollector.d(TAG, "translate: 开始 src=$srcCode→$tgtCode 引擎=${t::class.simpleName} text='${text.take(50)}'")
        b.translateButton.isEnabled = false
        b.outputText.text = getString(R.string.text_translate_translating)
        // 预捕获字符串：回调可能在 fragment 脱离后触发，直接 getString 会抛 not attached
        val readingText = getString(R.string.manga_reading)
        val translatingText = getString(R.string.text_translate_translating)
        val failedText = getString(R.string.translation_failed, "%s")
        lifecycleScope.launch {
            try {
                t.getTranslationStreaming(
                    text, srcCode, tgtCode,
                    onPhase = { phase ->
                        lifecycleScope.launch {
                            LogCollector.d(TAG, "translate: phase=$phase (+${System.currentTimeMillis() - tStart}ms)")
                            when (phase) {
                                "prefill" -> b.outputText.text = readingText
                                "generate" -> b.outputText.text = translatingText
                            }
                        }
                    },
                    onPartial = { partial ->
                        val now = android.os.SystemClock.elapsedRealtime()  // 单调时钟：系统改时间/NTP 校准不会冻结输出
                        if (now - lastUiUpdate >= UI_THROTTLE_MS) {
                            lastUiUpdate = now
                            lifecycleScope.launch { b.outputText.text = partial }
                        }
                    },
                    callback = { result ->
                    lifecycleScope.launch {
                        val elapsed = System.currentTimeMillis() - tStart
                        LogCollector.d(TAG, "translate: 回调=${result.javaClass.simpleName} 总耗时=${elapsed}ms")
                        isTranslating = false
                        b.translateButton.isEnabled = true
                        when (result) {
                            is TranslationResult.Success -> {
                                b.outputText.text = result.translatedText
                                if (forceLeaveCancelled) {
                                    // 已确认强制切换：不记录
                                } else if (pendingLeave != null) {
                                    // 切换确认弹窗期间正常完成：正常记录 + 执行待定切换
                                    saveRecord(text, result.translatedText, srcCode, tgtCode)
                                    val leave = pendingLeave
                                    pendingLeave = null
                                    leaveDialogShowing = false
                                    leave?.invoke()
                                } else {
                                    saveRecord(text, result.translatedText, srcCode, tgtCode)
                                }
                            }
                            is TranslationResult.Error -> {
                                if (!forceLeaveCancelled) {
                                    b.outputText.text = failedText.replace("%s", result.error.message ?: "")
                                }
                            }
                        }
                    }
                }
            )
            } catch (e: Exception) {
                // 引擎同步抛异常（如自定义 API 配置非法、JSON 构建失败）：callback 永远不会触发，
                // 手动恢复 UI 状态，否则 isTranslating 卡 true、按钮禁用、confirmLeave 无限拦截
                LogCollector.e(TAG, "translate: 引擎调用异常 ${e.message}", e)
                isTranslating = false
                b.translateButton.isEnabled = true
                b.outputText.text = failedText.replace("%s", e.message ?: "")
            }
        }
    }

    /**
     * MainActivity 拦截底部导航切换时调用：翻译中弹确认框。
     * - 「切换页面」→ 强制终止翻译、不记录，执行切换
     * - 「继续翻译」→ 留在本页
     * - 弹窗期间翻译正常完成 → 正常记录并自动执行切换
     */
    fun confirmLeave(onLeave: () -> Unit) {
        if (!isTranslating || leaveDialogShowing) { onLeave(); return }
        leaveDialogShowing = true
        pendingLeave = onLeave
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.text_translate_leave_confirm)
            .setPositiveButton(R.string.text_translate_leave_button) { _, _ ->
                leaveDialogShowing = false
                pendingLeave = null
                forceLeaveCancelled = true
                translator?.cancelTranslation()
                onLeave()
            }
            .setNegativeButton(R.string.text_translate_stay_button) { _, _ ->
                leaveDialogShowing = false
                pendingLeave = null
            }
            .setOnCancelListener {
                leaveDialogShowing = false
                pendingLeave = null
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun swapLanguages() {
        val b = binding ?: return
        val tmp = srcCode
        srcCode = tgtCode
        tgtCode = tmp
        persistLangSelection()
        updateLangLabels(b)
        // 只互换语言，绝不清空输入框；仅当输入框为空时把上次译文回填进去
        val out = b.outputText.text?.toString().orEmpty()
        if (out.isNotBlank() && b.inputEdit.text?.toString().isNullOrBlank()) {
            b.inputEdit.setText(out)
        }
        b.outputText.text = ""
    }

    // ========== 最近记录 ==========

    private companion object {
        const val PAGE_SIZE = 5  // 每页最多显示 5 条，超过需翻页
        const val UI_THROTTLE_MS = 80L  // 流式输出框节流间隔：避免 Main 布局工作抢占解码线程
        const val KEY_TEXT_SOURCE_LANG = "text_translate_source_lang"
        const val KEY_TEXT_TARGET_LANG = "text_translate_target_lang"
        const val TAG = "TextTranslateFragment"
    }

    private var currentPage = 0
    private var totalPages = 0

    /** 记录条数上限（0-200；0 = 不记录） */
    private fun recordLimit(): Int =
        CustomPreference.getInstance(requireContext()).getInt("text_translate_record_limit", 100).coerceIn(0, 200)

    private fun saveRecord(original: String, translated: String, src: String, tgt: String) {
        val limit = recordLimit()
        if (limit <= 0) return
        lifecycleScope.launch {
            val dao = TranslationHistoryDatabase.getInstance(requireContext()).textTranslateRecordDao()
            dao.insert(TextTranslateRecord(
                originalText = original,
                translatedText = translated,
                sourceLang = src,
                targetLang = tgt,
                engineName = binding?.engineDisplay?.text?.toString() ?: "?",
                createdAt = System.currentTimeMillis()
            ))
            dao.trimTo(limit)
            currentPage = 0  // 新记录 → 回第 1 页
            reloadRecent()
        }
    }

    private fun reloadRecent() {
        val b = binding ?: return
        val limit = recordLimit()
        b.recentLimitButton.text = getString(R.string.text_translate_recent_limit_format, limit)
        if (limit <= 0) {
            b.recentContainer.removeAllViews()
            b.pagerRow.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val dao = TranslationHistoryDatabase.getInstance(requireContext()).textTranslateRecordDao()
            val total = dao.count().coerceAtMost(limit)
            totalPages = ((total + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
            currentPage = currentPage.coerceIn(0, totalPages - 1)
            val records = dao.queryPage(PAGE_SIZE, currentPage * PAGE_SIZE)
            b.recentContainer.removeAllViews()
            records.forEach { rec -> b.recentContainer.addView(buildRecordItem(rec)) }
            b.pageIndicator.text = getString(R.string.text_translate_page_indicator, currentPage + 1, totalPages)
            b.prevPageButton.isEnabled = currentPage > 0
            b.nextPageButton.isEnabled = currentPage < totalPages - 1
            b.pagerRow.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
        }
    }

    private fun buildRecordItem(rec: TextTranslateRecord): View {
        val item = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_text_translate_record, binding!!.recentContainer, false)
        item.findViewById<android.widget.TextView>(R.id.recordOriginal).text = rec.originalText
        item.findViewById<android.widget.TextView>(R.id.recordTranslated).text = rec.translatedText
        item.findViewById<android.widget.TextView>(R.id.recordMeta).text =
            "${CustomLocale.getInstance(rec.sourceLang).getDisplayName()} → ${CustomLocale.getInstance(rec.targetLang).getDisplayName()} · ${rec.engineName}"
        // 点击 → 详情面板（长按选择复制）
        item.setOnClickListener { showRecordDetail(rec) }
        // 长按 → 快速删除
        item.setOnLongClickListener {
            confirmDeleteRecord(rec)
            true
        }
        // 右侧快速复制按钮（按钮消费点击，不触发条目点击）
        item.findViewById<com.google.android.material.button.MaterialButton>(R.id.copyOriginalButton)
            .setOnClickListener { copyToClipboard(rec.originalText) }
        item.findViewById<com.google.android.material.button.MaterialButton>(R.id.copyTranslatedButton)
            .setOnClickListener { copyToClipboard(rec.translatedText) }
        return item
    }

    private fun showRecordDetail(rec: TextTranslateRecord) {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(rec.createdAt))
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_record_detail, null, false)
        // textIsSelectable=true 支持长按选择框选复制
        view.findViewById<android.widget.TextView>(R.id.detailOriginal).apply {
            text = rec.originalText
            setTextIsSelectable(true)
        }
        view.findViewById<android.widget.TextView>(R.id.detailTranslated).apply {
            text = rec.translatedText
            setTextIsSelectable(true)
        }
        view.findViewById<android.widget.TextView>(R.id.detailMeta).text =
            "${CustomLocale.getInstance(rec.sourceLang).getDisplayName()} → ${CustomLocale.getInstance(rec.targetLang).getDisplayName()}\n" +
            getString(R.string.text_translate_detail_time, time) + "\n" +
            getString(R.string.text_translate_detail_model, rec.engineName)
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_translate_recent)
            .setView(view)
            .setNegativeButton(R.string.text_translate_detail_close, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun confirmDeleteRecord(rec: TextTranslateRecord) {
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.text_translate_delete_record)
            .setPositiveButton(R.string.text_translate_confirm) { _, _ ->
                lifecycleScope.launch {
                    TranslationHistoryDatabase.getInstance(requireContext())
                        .textTranslateRecordDao().deleteById(rec.id)
                    reloadRecent()
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translation", text))
        UiUtils.showToast(requireContext(), getString(R.string.text_copied), isShort = true)
    }

    /** 内存诊断：可用内存 + 进程实际驻留(VmRSS)，判断模型页是否被换出 */
    private fun logMemory(where: String) {
        try {
            val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val rssKb = try {
                java.io.File("/proc/self/status").readLines()
                    .firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(":")?.trim()
                    ?.substringBefore("kB")?.trim()?.toLong() ?: 0L
            } catch (_: Throwable) { 0L }
            LogCollector.d(TAG, "内存[$where]: 可用=${mi.availMem / 1024 / 1024}MB VmRSS=${rssKb / 1024}MB 低内存=${mi.lowMemory}")
        } catch (_: Exception) {
        }
    }

    private fun showLimitDialog() {
        val prefs = CustomPreference.getInstance(requireContext())
        val current = prefs.getInt("text_translate_record_limit", 100).coerceIn(0, 200)
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_record_limit, null, false)
        val input = view.findViewById<android.widget.EditText>(R.id.limitInput).apply {
            setText(current.toString())
            setSelection(text.length)
        }
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_translate_limit_title)
            .setView(view)
            .setPositiveButton(R.string.user_known) { _, _ ->
                val v = input.text?.toString()?.toIntOrNull()?.coerceIn(0, 200) ?: current
                prefs.setInt("text_translate_record_limit", v)
                reloadRecent()
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun confirmClearRecent() {
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.text_translate_clear_history_confirm)
            .setPositiveButton(R.string.user_known) { _, _ ->
                lifecycleScope.launch {
                    TranslationHistoryDatabase.getInstance(requireContext())
                        .textTranslateRecordDao().clearAll()
                    reloadRecent()
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }
}

/**
 * 文本页语言解析：优先页面自有选择（own），其次全局设置（global），最后回退默认值。
 * 纯函数，供单测覆盖三条 fallback 分支。
 */
fun resolveTextLang(own: String, global: String, default: String): String =
    if (own.isNotEmpty()) own else if (global.isNotEmpty()) global else default
