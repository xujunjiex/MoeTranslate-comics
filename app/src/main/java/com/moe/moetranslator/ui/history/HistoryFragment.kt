package com.moe.moetranslator.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.moe.moetranslator.R
import com.moe.moetranslator.data.HistoryEntry
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.databinding.FragmentHistoryBinding
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
        setupRecyclerViews()
        setupClearButton()
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
        mangaGroupAdapter = HistoryMangaGroupAdapter(
            onItemClick = { grouped -> openMangaViewer(grouped) },
            onItemLongClick = { entry -> showDeleteDialog(entry) },
            displayMode = displayMode
        )
        binding.rvMangaHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMangaHistory.adapter = mangaGroupAdapter
    }

    private fun setupClearButton() {
        binding.btnClearHistory.setOnClickListener {
            showClearDialog()
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
        val sortByUpdated = prefs.getString("history_sort_mode", "created") == "updated"
        val displayMode = prefs.getString("history_display_mode", "large")

        val sortOptions = arrayOf(
            getString(R.string.history_sort_created),
            getString(R.string.history_sort_updated)
        )
        val displayOptions = arrayOf(
            getString(R.string.history_display_list),
            getString(R.string.history_display_large),
            getString(R.string.history_display_medium),
            getString(R.string.history_display_small)
        )
        val currentSortIdx = if (sortByUpdated) 1 else 0
        val currentDisplayIdx = when (displayMode) {
            "list" -> 0; "large" -> 1; "medium" -> 2; "small" -> 3
            else -> 1
        }

        // 用 LinearLayout 组合两组选项
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        // 排序标题
        container.addView(android.widget.TextView(requireContext()).apply {
            text = "排列方式"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            setPadding(0, 0, 0, 8)
        })

        // 排序单选
        val sortGroup = android.widget.RadioGroup(requireContext()).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        sortOptions.forEachIndexed { idx, label ->
            sortGroup.addView(android.widget.RadioButton(requireContext()).apply {
                text = label
                id = idx
                isChecked = idx == currentSortIdx
            })
        }
        container.addView(sortGroup)

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

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("历史记录设置")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newSortIdx = sortGroup.checkedRadioButtonId
                val newDisplayIdx = displayGroup.checkedRadioButtonId
                if (newSortIdx >= 0) {
                    prefs.setString("history_sort_mode", if (newSortIdx == 1) "updated" else "created")
                }
                if (newDisplayIdx >= 0) {
                    prefs.setString("history_display_mode", displayValues[newDisplayIdx])
                }
                loadHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchTab(tab: Int) {
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
        loadHistory()
    }

    private fun loadHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val prefs = com.moe.moetranslator.utils.CustomPreference.getInstance(requireContext())
                val sortByUpdated = prefs.getString("history_sort_mode", "created") == "updated"
                val displayMode = prefs.getString("history_display_mode", "large")

                when (currentTab) {
                    TranslationCacheManager.MODE_GAME -> {
                        val groups = cacheManager.getHistoryGrouped(currentTab, sortByUpdated = sortByUpdated)
                        gameGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: 游戏分组, ${groups.size} 个日期组, sort=${if (sortByUpdated) "updated" else "created"}")
                    }
                    TranslationCacheManager.MODE_MANGA -> {
                        mangaGroupAdapter.setDisplayMode(displayMode)
                        mangaGroupAdapter.setSortByUpdated(sortByUpdated)
                        val groups = cacheManager.getHistoryGrouped(currentTab, sortByUpdated = sortByUpdated)
                        mangaGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: 漫画分组, ${groups.size} 个日期组, sort=${if (sortByUpdated) "updated" else "created"}, display=$displayMode")
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
        MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
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
