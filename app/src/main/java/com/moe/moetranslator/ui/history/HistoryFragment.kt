package com.moe.moetranslator.ui.history

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import android.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.moe.moetranslator.R
import com.moe.moetranslator.data.HistoryEntry
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.databinding.FragmentHistoryBinding
import com.moe.moetranslator.me.ConfigurationStorage
import com.moe.moetranslator.utils.CustomPreference
import com.moe.moetranslator.utils.LogCollector
import com.moe.moetranslator.utils.ServiceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resumeWithException

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

    private var retranslateCompleteReceiver: BroadcastReceiver? = null

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

        // 注册重新翻译完成广播接收器
        registerRetranslateReceiver()

        // 注册 CropFragment 结果监听
        childFragmentManager.setFragmentResultListener(
            CropFragment.RESULT_KEY, this
        ) { _, bundle ->
            val cropLeft = bundle.getInt("cropLeft", -1)
            val cropTop = bundle.getInt("cropTop", -1)
            val cropRight = bundle.getInt("cropRight", -1)
            val cropBottom = bundle.getInt("cropBottom", -1)
            val imagePath = bundle.getString("originalImagePath", "")
            if (cropLeft >= 0 && imagePath.isNotEmpty()) {
                sendRetranslateRequest(imagePath, cropLeft, cropTop, cropRight, cropBottom)
            }
        }

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
        val prefs = CustomPreference.getInstance(requireContext())
        val displayMode = prefs.getString("history_display_mode", "large")
        val viewMode = prefs.getString("history_view_mode", "default")
        mangaGroupAdapter = HistoryMangaGroupAdapter(
            onItemClick = { grouped -> openMangaViewer(grouped) },
            onItemLongClick = { entry -> showDeleteDialog(entry) },
            displayMode = displayMode,
            isManageView = (viewMode == "manage"),
            onRetranslateClick = { entry -> handleRetranslateClick(entry) },
            onDeleteVariantClick = { entry -> handleDeleteVariant(entry) },
            onSwitchVariant = { entry, position -> handleSwitchVariant(entry, position) },
            onDownloadSessionClick = { session -> downloadSession(session) }
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
        val prefs = CustomPreference.getInstance(requireContext())
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

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        container.addView(android.widget.TextView(requireContext()).apply {
            text = getString(R.string.history_display_mode_label)
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            setPadding(0, 24, 0, 8)
        })

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
            .setTitle(R.string.history_settings_title)
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
        val prefs = CustomPreference.getInstance(requireContext())

        binding.viewModeTabLayout.addTab(binding.viewModeTabLayout.newTab().setText(R.string.history_view_default))
        binding.viewModeTabLayout.addTab(binding.viewModeTabLayout.newTab().setText(R.string.history_view_manage))

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
        val prefs = CustomPreference.getInstance(requireContext())

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
        val prefs = CustomPreference.getInstance(requireContext())
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
                val prefs = CustomPreference.getInstance(requireContext())
                val viewMode = prefs.getString("history_view_mode", "default")
                val sortByUpdated = (viewMode == "default")
                val displayMode = prefs.getString("history_display_mode", "large")

                when (currentTab) {
                    TranslationCacheManager.MODE_GAME -> {
                        val groups = cacheManager.getHistoryGrouped(currentTab, sortByUpdated = sortByUpdated)
                        gameGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: game groups=${groups.size}, sort=${if (sortByUpdated) "default" else "manage"}")
                    }
                    TranslationCacheManager.MODE_MANGA -> {
                        mangaGroupAdapter.isManageView = (viewMode == "manage")
                        mangaGroupAdapter.setDisplayMode(displayMode)
                        mangaGroupAdapter.setSortByUpdated(sortByUpdated)
                        val groups = cacheManager.getHistoryGrouped(currentTab, sortByUpdated = sortByUpdated)

                        // Pre-compute retranslate counts for manage view
                        if (viewMode == "manage") {
                            val retranslateCountMap = withContext(Dispatchers.IO) {
                                val map = mutableMapOf<Long, Int>()
                                for (group in groups) {
                                    for (session in group.sessions) {
                                        for (entry in session.entries) {
                                            if (entry.variantIds.isNotEmpty()) {
                                                val count = entry.variantIds.count { variantId ->
                                                    cacheManager.getHistoryById(variantId)?.isRetranslated == true
                                                }
                                                map[entry.id] = count
                                            }
                                        }
                                    }
                                }
                                map
                            }
                            mangaGroupAdapter.retranslateCountMap = retranslateCountMap
                        }

                        mangaGroupAdapter.submitList(groups)
                        updateEmptyState(groups.isEmpty())
                        LogCollector.d(TAG, "loadHistory: manga groups=${groups.size}, sort=${if (sortByUpdated) "default" else "manage"}, display=$displayMode")
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

    // ========== Task 11: Retranslate flow ==========

    /**
     * 处理重新翻译点击
     */
    private fun handleRetranslateClick(entry: HistoryEntry) {
        // 1. 检查服务是否运行
        if (!ServiceUtils.isServiceRunning(requireContext(), com.moe.moetranslator.manga.MangaFloatingService::class.java)) {
            Toast.makeText(requireContext(), R.string.history_service_not_running, Toast.LENGTH_SHORT).show()
            return
        }

        // 2. 检查原图是否存在
        val imagePath = entry.originalImagePath
        if (imagePath.isNullOrEmpty() || !File(imagePath).exists()) {
            Toast.makeText(requireContext(), R.string.history_original_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        // 3. 弹出选项：用当前裁剪 / 重新裁剪
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_retranslate)
            .setItems(arrayOf(
                getString(R.string.history_use_current_crop),
                getString(R.string.history_new_crop)
            )) { _, which ->
                when (which) {
                    0 -> { // 用当前裁剪
                        lifecycleScope.launch {
                            val cache = cacheManager.getCacheByHistoryId(entry.id)
                            if (cache != null && cache.cropLeft >= 0 && cache.cropRight > 0) {
                                sendRetranslateRequest(
                                    imagePath = imagePath,
                                    cropLeft = cache.cropLeft,
                                    cropTop = cache.cropTop,
                                    cropRight = cache.cropRight,
                                    cropBottom = cache.cropBottom
                                )
                            } else {
                                // 没有缓存裁剪区域，用默认全图
                                sendRetranslateRequest(
                                    imagePath = imagePath,
                                    cropLeft = 0,
                                    cropTop = 0,
                                    cropRight = 0,
                                    cropBottom = 0
                                )
                            }
                        }
                    }
                    1 -> { // 重新裁剪
                        showCropFragment(imagePath)
                    }
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    /**
     * 显示裁剪弹窗
     */
    private fun showCropFragment(imagePath: String) {
        // 先检查是否有缓存的裁剪区域
        val cropFragment = CropFragment.newInstance(imagePath)
        cropFragment.show(childFragmentManager, "crop")
    }

    /**
     * 发送重新翻译广播
     */
    private fun sendRetranslateRequest(
        imagePath: String,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int
    ) {
        val prefs = CustomPreference.getInstance(requireContext())
        val intent = Intent("com.moe.moetranslator.RETRANSLATE_REQUEST").apply {
            putExtra("originalImagePath", imagePath)
            putExtra("cropLeft", cropLeft)
            putExtra("cropTop", cropTop)
            putExtra("cropRight", cropRight)
            putExtra("cropBottom", cropBottom)
            putExtra("ocrEngine", prefs.getString("history_ocr_engine", "PP_OCR_V5"))
            putExtra("openaiProviderIndex", prefs.getString("history_openai_provider_index", "0")?.toIntOrNull() ?: 0)
        }
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
    }

    /**
     * 注册重新翻译完成广播接收器
     */
    private fun registerRetranslateReceiver() {
        retranslateCompleteReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != "com.moe.moetranslator.RETRANSLATE_COMPLETE") return
                val success = intent.getBooleanExtra("success", false)
                val errorMessage = intent.getStringExtra("errorMessage")
                lifecycleScope.launch {
                    if (success) {
                        Toast.makeText(requireContext(), R.string.history_retranslate_done, Toast.LENGTH_SHORT).show()
                        loadHistory()
                    } else {
                        // 服务端忙（翻译进行中）显示专用提示，其余显示具体错误
                        if (errorMessage == "翻译进行中，请稍后") {
                            Toast.makeText(requireContext(), R.string.history_retranslate_busy, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), errorMessage ?: getString(R.string.translation_failed, "").substringBefore("%s").trim(), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(retranslateCompleteReceiver!!, IntentFilter("com.moe.moetranslator.RETRANSLATE_COMPLETE"))
    }

    /**
     * Task 11e: 删除变体
     */
    private fun handleDeleteVariant(entry: HistoryEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_delete_variant)
            .setMessage(R.string.delete_history_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        cacheManager.deleteHistory(entry.id)
                        LogCollector.d(TAG, "Deleted variant: id=${entry.id}")
                        loadHistory()
                        Toast.makeText(requireContext(), R.string.history_deleted, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogCollector.e(TAG, "Delete variant failed", e)
                    }
                }
            }
            .setNegativeButton(R.string.user_cancel, null)
            .show()
    }

    /**
     * Task 11f: 切换变体显示
     */
    private fun handleSwitchVariant(entry: HistoryEntry, position: Int) {
        lifecycleScope.launch {
            try {
                val variantId = entry.variantIds.getOrNull(position) ?: return@launch
                val variantEntry = cacheManager.getHistoryById(variantId)
                if (variantEntry != null) {
                    val dim = variantEntry.imagePath?.let { path ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, opts)
                        "${opts.outWidth}x${opts.outHeight}"
                    } ?: "?"
                    Toast.makeText(requireContext(), getString(R.string.history_variant_info, position + 1, dim), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Switch variant failed", e)
            }
        }
    }

    // ========== Task 12: Session ZIP download ==========

    /**
     * 下载进程组图片为 ZIP
     */
    private fun downloadSession(session: com.moe.moetranslator.data.HistorySession) {
        lifecycleScope.launch {
            try {
                val paths = mutableListOf<String>()
                for (entry in session.entries) {
                    if (entry.variantCount > 1) {
                        val chosen = withContext(Dispatchers.Main) {
                            showVariantPickerForDownload(entry)
                        }
                        if (chosen != null) paths.add(chosen)
                    } else {
                        entry.imagePath?.let { paths.add(it) }
                    }
                }
                if (paths.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.history_no_images_to_download, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val zipFile = File(requireContext().cacheDir, "session_${session.sessionId}.zip")
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                        paths.forEachIndexed { idx, path ->
                            val f = File(path)
                            if (f.exists()) {
                                zos.putNextEntry(ZipEntry("img_${idx}.jpg"))
                                FileInputStream(f).use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        val uri = FileProvider.getUriForFile(
                            requireContext(), "${requireContext().packageName}.fileprovider", zipFile
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(share, getString(R.string.history_share_session)))
                    }
                }
            } catch (e: Exception) {
                LogCollector.e(TAG, "Download session failed", e)
                Toast.makeText(requireContext(), getString(R.string.history_download_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun showVariantPickerForDownload(entry: HistoryEntry): String? {
        return suspendCancellableCoroutine { cont ->
            lifecycleScope.launch {
                try {
                    val variants = entry.variantIds.mapNotNull { id ->
                        cacheManager.getHistoryById(id)
                    }
                    val items = variants.map { v ->
                        val dim = v.imagePath?.let { path ->
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(path, opts)
                            "${opts.outWidth}x${opts.outHeight}"
                        } ?: "?"
                        dim
                    }.toTypedArray()
                    val dialog = AlertDialog.Builder(requireContext())
                        .setTitle(R.string.history_select_variant)
                        .setItems(items) { _, which ->
                            if (cont.isActive) cont.resume(variants[which].imagePath)
                        }
                        .setOnCancelListener {
                            if (cont.isActive) cont.resume(null)
                        }
                        .show()
                    cont.invokeOnCancellation { dialog.dismiss() }
                } catch (e: Exception) {
                    LogCollector.e(TAG, "Variant picker failed", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    override fun onDestroyView() {
        retranslateCompleteReceiver?.let {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(it)
            retranslateCompleteReceiver = null
        }
        _binding = null
        super.onDestroyView()
    }
}
