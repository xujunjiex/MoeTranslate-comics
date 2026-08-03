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
    private var translating = false

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
        translator?.release()   // Hy-MT2 释放 440MB
        translator = null
        super.onDestroyView()
        binding = null
    }

    // ========== 语言选择 ==========

    private fun setupLanguageSelectors(b: FragmentTextTranslateBinding) {
        val prefs = CustomPreference.getInstance(requireContext()).getSharedPreferences()
        srcCode = prefs.getString("Source_Language", "ja") ?: "ja"
        tgtCode = prefs.getString("Target_Language", "zh") ?: "zh"
        updateLangLabels(b)
        b.srcLangButton.setOnClickListener { showLanguageDialog(1) }
        b.tgtLangButton.setOnClickListener { showLanguageDialog(2) }
    }

    private fun updateLangLabels(b: FragmentTextTranslateBinding) {
        b.srcLangButton.text = CustomLocale.getInstance(srcCode).getDisplayName()
        b.tgtLangButton.text = CustomLocale.getInstance(tgtCode).getDisplayName()
    }

    private fun showLanguageDialog(type: Int) {
        val list = TranslateTools.getLanguagesList(requireContext(), type) ?: return
        LanguageSelectionDialog(requireContext(), type, list) { locale ->
            if (type == 1) {
                srcCode = locale.getOriCode()
                if (srcCode == tgtCode) tgtCode = "zh"
            } else {
                tgtCode = locale.getOriCode()
                if (tgtCode == srcCode) srcCode = "ja"
            }
            updateLangLabels(binding ?: return@LanguageSelectionDialog)
        }.show()
    }

    private fun setupEngine(b: FragmentTextTranslateBinding) {
        val prefs = CustomPreference.getInstance(requireContext())
        val key = "api=${prefs.getInt("Text_API", 1)}|ai=${prefs.getInt("Text_AI", 0)}"
        if (key == lastEngineKey && translator != null) return
        lastEngineKey = key
        translator?.release()
        translator = TranslatorFactory.create(requireContext(), prefs, TranslatorFactory.Mode.TEXT)
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
    }

    // ========== 翻译 ==========

    private fun translate() {
        val b = binding ?: return
        if (translating) return  // 翻译中忽略再次点击
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
        translating = true
        b.translateButton.isEnabled = false
        b.outputText.text = getString(R.string.manga_translating)
        lifecycleScope.launch {
            t.getTranslationStreaming(
                text, srcCode, tgtCode,
                onPhase = { phase ->
                    lifecycleScope.launch {
                        when (phase) {
                            "prefill" -> b.outputText.text = getString(R.string.manga_reading)
                            "generate" -> b.outputText.text = getString(R.string.manga_translating)
                        }
                    }
                },
                onPartial = { partial ->
                    lifecycleScope.launch { b.outputText.text = partial }
                },
                callback = { result ->
                    lifecycleScope.launch {
                        translating = false
                        b.translateButton.isEnabled = true
                        when (result) {
                            is TranslationResult.Success -> {
                                b.outputText.text = result.translatedText
                                saveRecord(text, result.translatedText, srcCode, tgtCode)
                            }
                            is TranslationResult.Error -> {
                                b.outputText.text = getString(R.string.translation_failed, result.error.message ?: "")
                            }
                        }
                    }
                }
            )
        }
    }

    private fun swapLanguages() {
        val b = binding ?: return
        val tmp = srcCode
        srcCode = tgtCode
        tgtCode = tmp
        updateLangLabels(b)
        // 译文回填输入框，清空输出
        val out = b.outputText.text?.toString().orEmpty()
        if (out.isNotBlank()) b.inputEdit.setText(out)
        b.outputText.text = ""
    }

    // ========== 最近记录 ==========

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
            reloadRecent()
        }
    }

    private fun reloadRecent() {
        val b = binding ?: return
        val limit = recordLimit()
        b.recentLimitButton.text = getString(R.string.text_translate_recent_limit_format, limit)
        if (limit <= 0) {
            b.recentContainer.removeAllViews()
            return
        }
        lifecycleScope.launch {
            val records = TranslationHistoryDatabase.getInstance(requireContext())
                .textTranslateRecordDao().queryRecent(limit)
            b.recentContainer.removeAllViews()
            records.forEach { rec -> b.recentContainer.addView(buildRecordItem(rec)) }
        }
    }

    private fun buildRecordItem(rec: TextTranslateRecord): View {
        val item = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_text_translate_record, binding!!.recentContainer, false)
        item.findViewById<android.widget.TextView>(R.id.recordOriginal).text = rec.originalText
        item.findViewById<android.widget.TextView>(R.id.recordTranslated).text = rec.translatedText
        item.findViewById<android.widget.TextView>(R.id.recordMeta).text =
            "${CustomLocale.getInstance(rec.sourceLang).getDisplayName()} → ${CustomLocale.getInstance(rec.targetLang).getDisplayName()} · ${rec.engineName}"
        item.setOnClickListener { showRecordDetail(rec) }
        return item
    }

    private fun showRecordDetail(rec: TextTranslateRecord) {
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("${CustomLocale.getInstance(rec.sourceLang).getDisplayName()} → ${CustomLocale.getInstance(rec.targetLang).getDisplayName()}")
            .setMessage("【原文】\n${rec.originalText}\n\n【译文】\n${rec.translatedText}")
            .setPositiveButton(R.string.text_translate_copy_original) { _, _ ->
                copyToClipboard(rec.originalText)
            }
            .setNeutralButton(R.string.text_translate_copy_translated) { _, _ ->
                copyToClipboard(rec.translatedText)
            }
            .setNegativeButton(R.string.text_translate_retranslate) { _, _ ->
                retranslate(rec)
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translation", text))
        UiUtils.showToast(requireContext(), getString(R.string.text_copied), isShort = true)
    }

    private fun retranslate(rec: TextTranslateRecord) {
        val b = binding ?: return
        srcCode = rec.sourceLang
        tgtCode = rec.targetLang
        updateLangLabels(b)
        b.inputEdit.setText(rec.originalText)
        b.outputText.text = rec.translatedText
    }

    private fun showLimitDialog() {
        val prefs = CustomPreference.getInstance(requireContext())
        val current = prefs.getInt("text_translate_record_limit", 100).coerceIn(0, 200)
        val input = android.widget.EditText(requireContext()).apply {
            setText(current.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_translate_limit_title)
            .setView(input)
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
