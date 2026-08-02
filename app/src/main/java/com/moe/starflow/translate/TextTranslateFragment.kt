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
 * Hy-MT2 等本地模型流式输出（onPartial 累积更新）；最近记录存 text_translate_record 表。
 */
class TextTranslateFragment : Fragment() {

    private var binding: FragmentTextTranslateBinding? = null
    private var translator: TranslationTextAPI? = null
    private var lastEngineKey: String? = null
    private var translating = false

    // 文本翻译页语言集（CustomLocale 驱动显示名，可扩展）
    private val languages = arrayOf("ja", "en", "zh", "zh-TW", "ko", "ru", "fr", "de", "es", "pt", "it", "th", "vi", "id", "ar")

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

    private fun setupLanguageSelectors(b: FragmentTextTranslateBinding) {
        val prefs = CustomPreference.getInstance(requireContext()).getSharedPreferences()
        val names = languages.map { CustomLocale.getInstance(it).getDisplayName() }
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
        b.srcLangSelect.setAdapter(adapter)
        b.tgtLangSelect.setAdapter(adapter)
        val srcIdx = languages.indexOf(prefs.getString("Source_Language", "ja")).coerceAtLeast(0)
        val tgtIdx = languages.indexOf(prefs.getString("Target_Language", "zh")).coerceAtLeast(0)
        b.srcLangSelect.setText(names[srcIdx], false)
        b.tgtLangSelect.setText(names[tgtIdx], false)
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
            b.engineDisplay.text = t::class.simpleName ?: "?"
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
        // 运行服务 guard：游戏/漫画翻译运行中 → 提示先关闭
        if (ServiceUtils.isServiceRunning(requireContext(), FloatingBallService::class.java) ||
            ServiceUtils.isServiceRunning(requireContext(), MangaFloatingService::class.java)) {
            android.app.AlertDialog.Builder(requireContext())
                .setMessage(R.string.text_translate_service_running)
                .setPositiveButton(R.string.user_known, null)
                .show()
            return
        }
        val t = translator ?: run {
            b.outputText.text = getString(R.string.text_translate_engine_init_failed)
            return
        }
        val srcCode = codeOf(b.srcLangSelect.text?.toString().orEmpty())
        val tgtCode = codeOf(b.tgtLangSelect.text?.toString().orEmpty())
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
        val srcName = b.srcLangSelect.text?.toString().orEmpty()
        val tgtName = b.tgtLangSelect.text?.toString().orEmpty()
        b.srcLangSelect.setText(tgtName, false)
        b.tgtLangSelect.setText(srcName, false)
        // 译文回填输入框，清空输出
        val out = b.outputText.text?.toString().orEmpty()
        if (out.isNotBlank()) b.inputEdit.setText(out)
        b.outputText.text = ""
    }

    // ========== 最近记录 ==========

    private fun saveRecord(original: String, translated: String, src: String, tgt: String) {
        val limit = CustomPreference.getInstance(requireContext())
            .getInt("text_translate_record_limit", 100).coerceIn(0, 500)
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
        val limit = CustomPreference.getInstance(requireContext())
            .getInt("text_translate_record_limit", 100).coerceIn(0, 500)
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
        b.srcLangSelect.setText(nameOf(rec.sourceLang), false)
        b.tgtLangSelect.setText(nameOf(rec.targetLang), false)
        b.inputEdit.setText(rec.originalText)
        b.outputText.text = rec.translatedText
    }

    private fun showLimitDialog() {
        val prefs = CustomPreference.getInstance(requireContext())
        val current = prefs.getInt("text_translate_record_limit", 100)
        val input = android.widget.EditText(requireContext()).apply {
            setText(current.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_translate_limit_title)
            .setView(input)
            .setPositiveButton(R.string.user_known) { _, _ ->
                val v = input.text?.toString()?.toIntOrNull()?.coerceIn(0, 500) ?: current
                prefs.setInt("text_translate_record_limit", v)
                reloadRecent()
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun confirmClearRecent() {
        android.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.text_translate_clear_history_confirm)
            .setPositiveButton(R.string.user_known) { _, _ ->
                lifecycleScope.launch {
                    TranslationHistoryDatabase.getInstance(requireContext())
                        .textTranslateRecordDao().clearAll()
                    reloadRecent()
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    // ========== 语言码 ↔ 显示名 ==========

    private fun codeOf(name: String): String =
        languages.firstOrNull { CustomLocale.getInstance(it).getDisplayName() == name } ?: name

    private fun nameOf(code: String): String =
        languages.firstOrNull { it == code }?.let { CustomLocale.getInstance(it).getDisplayName() } ?: code
}
