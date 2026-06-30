package com.moe.moetranslator.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import android.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.moe.moetranslator.R
import com.moe.moetranslator.data.HistoryEntry
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.databinding.FragmentHistoryBinding
import com.moe.moetranslator.me.ConfigurationStorage
import com.moe.moetranslator.utils.LogCollector
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    companion object {
        private const val TAG = "HistoryFragment"
    }

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var cacheManager: TranslationCacheManager
    private lateinit var gameGroupAdapter: HistoryGroupAdapter
    private lateinit var mangaGroupAdapter: HistoryMangaGroupAdapter

    // 0=游戏, 1=漫画
    private var currentTab = TranslationCacheManager.MODE_GAME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cacheManager = TranslationCacheManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupViewModeTabs()
        setupEngineSelectors()
        setupRecyclerViews()
        setupClearButton()
        setupClearAllCacheButton()
        setupRefreshButton()
        setupSettingsButton()
        loadHistory()
    }

    private fun setupTabs() {
        binding.historyTabLayout.addTab(
            binding.historyTabLayout.newTab().setText(R.string.history_game_tab)
        )
        binding.historyTabLayout.addTab(
            binding.historyTabLayout.newTab().setText(R.string.history_manga_tab)
        )

        binding.historyTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: TranslationCacheManager.MODE_GAME
                switchTab(currentTab)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerViews() {
        // 游戏历史：分组适配器
        gameGroupAdapter = HistoryGroupAdapter(
            onItemClick = { entry -> copyTranslatedText(entry) },
            onItemLongClick = { entry -> showDeleteDialog(entry) }
        )
        binding.rvGameHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGameHistory.adapter = gameGroupAdapter

        // 漫画历史：分组适配器
        val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(requireContext())
        val displayMode = prefs.getString("history_display_mode", "large")
        val viewMode = prefs.getString("history_view_mode", "default")
        mangaGroupAdapter = HistoryMangaGroupAdapter(
            onItemClick = { grouped -> openMangaViewer(grouped) },
            onItemLongClick = { entry -> showDeleteDialog(entry) },
            displayMode = displayMode,
            isManageView = (viewMode == "manage"),
            onRetranslateClick = { entry -> /* Task 11: 重新翻译逻辑 */ },
            onDeleteVariantClick = { entry -> /* Task 11: 删除尺寸逻辑 */ }
        )
        binding.rvMangaHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMangaHistory.adapter = mangaGroupAdapter
    }

    private fun setupClearButton() {
        binding.btnClearHistory.setOnClickListener {
            showClearDialog()
        }
    }

    private fun setupClearAllCacheButton() {
        binding.btnClearAllCache.setOnClickListener {
            showClearAllCacheDialog()
        }
    }

    private fun setupRefreshButton() {
        binding.btnRefreshHistory.setOnClickListener {
            loadHistory()
            Toast.makeText(requireContext(), R.string.history_refreshed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSettingsButton() {
        binding.btnHistorySettings.setOnClickListener { view ->
            showHistorySettingsMenu(view)
        }
    }

    private fun showHistorySettingsMenu(anchor: View) {
        val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(requireContext())
        val displayMode = prefs.getString("history_display_mode", "large")

        val displayOptions = arrayOf(
            getString(R.string.history_display_list),
            getString(R.string.history_display_large),
            getString(R.string.history_display_medium),
            getString(R.string.history_display_small)
        )
        val currentDisplayIdx = when (displayMode) {
            "list" -> 0; "large" -> 1; "medium" -> 2; "small" -> 3
            else -> 1
        }

        // 用 LinearLayout 组合两组选项
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        // 显示标题
        container.addView(android.widget.TextView(requireContext()).apply {
            text = "显示方式"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            setPadding(0, 24, 0, 8)
        })

        // 显示单选
        val displayGroup = android.widget.RadioGroup(requireContext()).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val displayValues = arrayOf("list", "large", "medium", "small")
        displayOptions.forEachIndexed { idx, label ->
            displayGroup.addView(android.widget.RadioButton(requireContext()).apply {
                text = label
                id = idx
                isChecked = idx == currentDisplayIdx
            })
        }
        container.addView(displayGroup)

        // 缓存数量标题 + 当前值
        val cacheCountValues = intArrayOf(0, 20, 50, 100, 200, 500)
        val currentCacheCount = prefs.getString("translation_cache_count", "100").toIntOrNull() ?: 100
        val currentCacheIdx = cacheCountValues.indexOfFirst { it == currentCacheCount }.coerceAtLeast(3)

        val cacheLabel = android.widget.TextView(requireContext()).apply {
            text = getString(R.string.cache_count_title) + ": ${cacheCountValues[currentCacheIdx]}"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            setPadding(0, 24, 0, 8)
        }
        container.addView(cacheLabel)

        val cacheSeekBar = android.widget.SeekBar(requireContext()).apply {
            max = cacheCountValues.size - 1
            progress = currentCacheIdx
        }
        cacheSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val v = cacheCountValues[progress]
                cacheLabel.text = getString(R.string.cache_count_title) + ": " + if (v == 0) getString(R.string.cache_disabled) else "$v"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        container.addView(cacheSeekBar)

        AlertDialog.Builder(requireContext())
            .setTitle("历史记录设置")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newDisplayIdx = displayGroup.checkedRadioButtonId
                if (newDisplayIdx >= 0) {
                    prefs.setString("history_display_mode", displayValues[newDisplayIdx])
                }
                prefs.setString("translation_cache_count", cacheCountValues[cacheSeekBar.progress].toString())
                loadHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupViewModeTabs() {
        val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(requireContext())

        binding.viewModeTabLayout.addTab(binding.viewModeTabLayout.newTab().setText("默认视图"))
        binding.viewModeTabLayout.addTab(binding.viewModeTabLayout.newTab().setText("管理视图"))

        val savedMode = prefs.getString("history_view_mode", "default")
        if (savedMode == "manage") {
            binding.viewModeTabLayout.selectTab(binding.viewModeTabLayout.getTabAt(1))
        }

        binding.viewModeTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val isManage = tab?.position == 1
                prefs.setString("history_view_mode", if (isManage) "manage" else "default")
                updateEngineSelectorVisibility()
                loadHistory()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupEngineSelectors() {
        val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(requireContext())

        // OCR Engine spinner
        val ocrEngines = arrayOf("PP-OCRv5", "manga-ocr", "ML Kit")
        val ocrValues = arrayOf("PP_OCR_V5", "MANGA_OCR", "MLKIT")
        val savedOcr = prefs.getString("history_ocr_engine", "PP_OCR_V5") ?: "PP_OCR_V5"
        val ocrIdx = ocrValues.indexOfFirst { it == savedOcr }.coerceAtLeast(0)

        binding.spinnerOcrEngine.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, ocrEngines)
        binding.spinnerOcrEngine.setSelection(ocrIdx)
        binding.spinnerOcrEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.setString("history_ocr_engine", ocrValues[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Translation provider spinner
        val providerList = ConfigurationStorage.loadAllProviders(prefs)
        val providerNames = providerList.map { it.modelName }
        if (providerNames.isEmpty()) {
            binding.spinnerTranslateApi.isEnabled = false
            return
        }
        val savedIdx = prefs.getString("history_openai_provider_index", "0")?.toIntOrNull()?.coerceAtMost(providerNames.size - 1) ?: 0
        binding.spinnerTranslateApi.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, providerNames.toTypedArray())
        binding.spinnerTranslateApi.setSelection(savedIdx)
        binding.spinnerTranslateApi.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.setString("history_openai_provider_index", position.toString())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateEngineSelectorVisibility() {
        val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(requireContext())
        val isManageView = prefs.getString("history_view_mode", "default") == "manage"
        val isMangaTab = currentTab == TranslationCacheManager.MODE_MANGA
        binding.engineSelectorLayout.visibility = if (isManageView && isMangaTab) View.VISIBLE else View.GONE
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        when (tab) {
            TranslationCacheManager.MODE_GAME -> {
                binding.rvGameHistory.visibility = View.VISIBLE
                binding.rvMangaHistory.visibility = View.GONE
            }
            TranslationCacheManager.MODE_MANGA -> {
                binding.rvGameHistory.visibility = View.GONE
                binding.rvMangaHistory.visibility = View.VISIBLE
            }
        }
        updateEngineSelectorVisibility()
        loadHistory()
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(requireContext())
                val viewMode = prefs.getString("history_view_mode", "default")
                val sortByUpdated = (viewMode == "default")
                val displayMode = prefs.getString("history_display_mode", "large")

                when (currentTab) {
                    TranslationCacheManager.MODE_GAME -> {
                        val groups = cacheManager.getHistoryGrouped(currentTab, sortByUpdated = sortByUpdated)
                        gameGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: 游戏分组, ${groups.size} 个日期组, sort=${if (sortByUpdated) "default" else "manage"}")
                    }
                    TranslationCacheManager.MODE_MANGA -> {
                        mangaGroupAdapter.isManageView = (viewMode == "manage")
                        mangaGroupAdapter.setDisplayMode(displayMode)
                        mangaGroupAdapter.setSortByUpdated(sortByUpdated)
                        val groups = cacheManager.getHistoryGrouped(currentTab, sortByUpdated = sortByUpdated)
                        mangaGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: 漫画分组, ${groups.size} 个日期组, sort=${if (sortByUpdated) "default" else "manage"}, display=$displayMode")
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "loadHistory failed", e)
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun copyTranslatedText(entry: HistoryEntry) {
        val text = entry.translatedText
        if (text.isNullOrEmpty()) return

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translated_text", text))
        Toast.makeText(requireContext(), R.string.history_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteDialog(entry: HistoryEntry) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.delete_history_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        cacheManager.deleteHistory(entry.id)
                        LogCollector.d(TAG, "Deleted history: id=${entry.id}")
                        loadHistory()
                        Toast.makeText(requireContext(), R.string.history_deleted, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Delete history failed", e)
                    }
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun showClearDialog() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.confirm_clear_history)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        cacheManager.clearHistory(currentTab)
                        LogCollector.d(TAG, "Cleared history: type=$currentTab")
                        loadHistory()
                        Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Clear history failed", e)
                    }
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    private fun showClearAllCacheDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_cache_title)
            .setMessage(R.string.clear_cache_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        cacheManager.clearAllCache()
                        LogCollector.d(TAG, "Cleared all cache")
                        loadHistory()
                        Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Clear all cache failed", e)
                    }
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    /**
     * 打开漫画图片浏览页（支持同 pHash 多尺寸切换）
     */
    private fun openMangaViewer(grouped: GroupedHistoryEntry) {
        val intent = Intent(requireContext(), MangaViewerActivity::class.java).apply {
            putExtra(MangaViewerActivity.EXTRA_ENTRY_ID, grouped.representative.id)
            putExtra(MangaViewerActivity.EXTRA_ENTRY_IDS, grouped.allEntryIds.toLongArray())
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
