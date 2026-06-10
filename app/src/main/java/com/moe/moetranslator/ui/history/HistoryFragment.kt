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
        mangaGroupAdapter = HistoryMangaGroupAdapter(
            onItemClick = { entry -> openMangaViewer(entry) },
            onItemLongClick = { entry -> showDeleteDialog(entry) }
        )
        binding.rvMangaHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMangaHistory.adapter = mangaGroupAdapter
    }

    private fun setupClearButton() {
        binding.btnClearHistory.setOnClickListener {
            showClearDialog()
        }
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
                when (currentTab) {
                    TranslationCacheManager.MODE_GAME -> {
                        // 游戏历史：使用分组查询
                        val groups = cacheManager.getHistoryGrouped(currentTab)
                        gameGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: 游戏分组, ${groups.size} 个日期组")
                    }
                    TranslationCacheManager.MODE_MANGA -> {
                        val groups = cacheManager.getHistoryGrouped(currentTab)
                        mangaGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: 漫画分组, ${groups.size} 个日期组")
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
     * 打开漫画图片浏览页
     */
    private fun openMangaViewer(entry: HistoryEntry) {
        val intent = Intent(requireContext(), MangaViewerActivity::class.java).apply {
            putExtra(MangaViewerActivity.EXTRA_ENTRY_ID, entry.id)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
