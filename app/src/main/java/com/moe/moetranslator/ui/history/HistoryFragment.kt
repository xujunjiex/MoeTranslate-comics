package com.moe.moetranslator.ui.history

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
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
    private lateinit var gameAdapter: HistoryGameAdapter
    private lateinit var mangaAdapter: HistoryMangaAdapter

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
        gameAdapter = HistoryGameAdapter(
            onItemClick = { entry -> copyTranslatedText(entry) },
            onItemLongClick = { entry -> showDeleteDialog(entry) }
        )
        binding.rvGameHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGameHistory.adapter = gameAdapter

        mangaAdapter = HistoryMangaAdapter(
            onItemClick = { entry -> showFullImage(entry) },
            onItemLongClick = { entry -> showDeleteDialog(entry) }
        )
        // 漫画使用 2 列网格
        binding.rvMangaHistory.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvMangaHistory.adapter = mangaAdapter
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
                val history = cacheManager.getHistory(currentTab)
                LogCollector.d(TAG, "loadHistory: type=$currentTab, count=${history.size}")

                when (currentTab) {
                    TranslationCacheManager.MODE_GAME -> {
                        gameAdapter.submitList(history)
                        updateEmptyState(history.isEmpty())
                    }
                    TranslationCacheManager.MODE_MANGA -> {
                        mangaAdapter.submitList(history)
                        updateEmptyState(history.isEmpty())
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

    private fun showFullImage(entry: HistoryEntry) {
        val path = entry.imagePath ?: entry.thumbnailPath
        if (path.isNullOrEmpty() || !java.io.File(path).exists()) {
            Toast.makeText(requireContext(), R.string.no_history, Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = BitmapFactory.decodeFile(path) ?: return

        val dialog = Dialog(requireContext(), android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(imageView)
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
