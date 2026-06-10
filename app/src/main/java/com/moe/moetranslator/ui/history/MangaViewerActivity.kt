package com.moe.moetranslator.ui.history

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.moe.moetranslator.R
import com.moe.moetranslator.data.HistoryEntry
import com.moe.moetranslator.data.TranslationCacheManager
import com.moe.moetranslator.databinding.ActivityMangaViewerBinding
import com.moe.moetranslator.utils.LogCollector
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MangaViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MangaViewerActivity"
        const val EXTRA_ENTRY_ID = "entry_id"
    }

    private lateinit var binding: ActivityMangaViewerBinding
    private lateinit var cacheManager: TranslationCacheManager
    private val entries = mutableListOf<HistoryEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var isPanelExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivityMangaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cacheManager = TranslationCacheManager(this)

        val clickedEntryId = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)

        setupViews()
        loadData(clickedEntryId)
    }

    private fun setupViews() {
        // 关闭按钮
        binding.btnClose.setOnClickListener { finish() }

        // 查看译文按钮
        binding.btnShowTranslation.setOnClickListener {
            togglePanel()
        }

        // 关闭底部面板
        binding.btnCloseSheet.setOnClickListener {
            collapsePanel()
        }

        // ViewPager 翻页监听
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
                // 翻页时关闭面板
                if (isPanelExpanded) {
                    collapsePanel()
                }
            }
        })

        // 初始隐藏底部面板
        binding.bottomSheetPanel.post {
            binding.bottomSheetPanel.translationY = binding.bottomSheetPanel.height.toFloat()
        }
    }

    private fun loadData(clickedEntryId: Long) {
        lifecycleScope.launch {
            try {
                // 加载所有漫画历史，支持跨会话翻页
                val allEntries = cacheManager.getHistory(TranslationCacheManager.MODE_MANGA, limit = 500)

                entries.clear()
                entries.addAll(allEntries)

                if (entries.isEmpty()) {
                    Toast.makeText(this@MangaViewerActivity, R.string.no_translation_data, Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                // 设置 ViewPager
                val adapter = MangaImageAdapter(entries)
                binding.viewPager.adapter = adapter

                // 跳转到点击的图片
                val clickedIndex = entries.indexOfFirst { it.id == clickedEntryId }
                val safeIndex = if (clickedIndex >= 0) clickedIndex else 0
                binding.viewPager.setCurrentItem(safeIndex, false)
                updatePageIndicator(safeIndex)

                LogCollector.d(TAG, "加载漫画历史, ${entries.size} 张图片, 跳转到 #$safeIndex")
            } catch (e: Exception) {
                LogCollector.e(TAG, "加载数据失败", e)
                Toast.makeText(this@MangaViewerActivity, R.string.no_translation_data, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun updatePageIndicator(position: Int) {
        binding.tvPageIndicator.text = "${position + 1}/${entries.size}"
    }

    private fun togglePanel() {
        if (isPanelExpanded) {
            collapsePanel()
        } else {
            expandPanel()
        }
    }

    private fun expandPanel() {
        val position = binding.viewPager.currentItem
        if (position < 0 || position >= entries.size) {
            LogCollector.w(TAG, "expandPanel: invalid position=$position, size=${entries.size}")
            return
        }

        val entry = entries[position]
        LogCollector.d(TAG, "expandPanel: entryId=${entry.id}, sourceText=${entry.sourceText?.take(30)}, translatedText=${entry.translatedText?.take(30)}")

        val detailList = buildDetailList(entry)
        LogCollector.d(TAG, "expandPanel: detailList size=${detailList.size}")

        val adapter = TranslationDetailAdapter(detailList)
        binding.rvTranslationDetail.layoutManager = LinearLayoutManager(this)
        binding.rvTranslationDetail.adapter = adapter

        // 等布局完成后获取实际高度
        binding.bottomSheetPanel.post {
            val panelHeight = binding.bottomSheetPanel.height.toFloat()
            binding.bottomSheetPanel.translationY = panelHeight
            binding.bottomSheetPanel.animate()
                .translationY(0f)
                .setDuration(250)
                .start()
        }

        isPanelExpanded = true
    }

    private fun collapsePanel() {
        val panelHeight = binding.bottomSheetPanel.height.toFloat()
        binding.bottomSheetPanel.animate()
            .translationY(panelHeight)
            .setDuration(250)
            .start()

        isPanelExpanded = false
    }

    private fun buildDetailList(entry: HistoryEntry): List<TranslationDetailItem> {
        val items = mutableListOf<TranslationDetailItem>()

        val ocrText = entry.sourceText
        val aiText = entry.translatedText

        when {
            !ocrText.isNullOrEmpty() && !aiText.isNullOrEmpty() -> {
                // 解析编号格式的译文
                val ocrLines = parseNumberedText(ocrText)
                val aiLines = parseNumberedText(aiText)

                val maxCount = maxOf(ocrLines.size, aiLines.size)
                for (i in 0 until maxCount) {
                    items.add(
                        TranslationDetailItem(
                            index = i + 1,
                            ocrText = ocrLines.getOrNull(i) ?: "",
                            translatedText = aiLines.getOrNull(i) ?: ""
                        )
                    )
                }
            }
            !aiText.isNullOrEmpty() -> {
                items.add(TranslationDetailItem(index = 1, ocrText = "", translatedText = aiText))
            }
            !ocrText.isNullOrEmpty() -> {
                items.add(TranslationDetailItem(index = 1, ocrText = ocrText, translatedText = ""))
            }
            else -> {
                items.add(TranslationDetailItem(index = 1, ocrText = getString(R.string.no_translation_data), translatedText = ""))
            }
        }

        return items
    }

    private fun parseNumberedText(text: String): List<String> {
        // 解析 [1] text 格式
        val regex = Regex("""\[(\d+)]\s*""")
        val parts = regex.split(text).filter { it.isNotBlank() }
        return parts.map { it.trim() }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.viewPager.adapter = null
    }
}

// ========== 数据类和适配器 ==========

data class TranslationDetailItem(
    val index: Int,
    val ocrText: String,
    val translatedText: String
)

class TranslationDetailAdapter(
    private val items: List<TranslationDetailItem>
) : RecyclerView.Adapter<TranslationDetailAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvIndex)
        val tvOcrText: TextView = view.findViewById(R.id.tvOcrText)
        val tvTranslatedText: TextView = view.findViewById(R.id.tvTranslatedText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_translation_detail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvIndex.text = "[${item.index}]"
        holder.tvOcrText.text = if (item.ocrText.isNotEmpty()) item.ocrText else "-"
        holder.tvTranslatedText.text = item.translatedText
    }

    override fun getItemCount() = items.size
}

class MangaImageAdapter(
    private val entries: List<HistoryEntry>
) : RecyclerView.Adapter<MangaImageAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: android.widget.ImageView = view.findViewById(R.id.ivFullImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manga_viewer_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val path = entry.imagePath ?: entry.thumbnailPath
        if (path != null && java.io.File(path).exists()) {
            // 使用 inSampleSize 避免 OOM
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            val maxDim = 2048
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeFile(path, decodeOptions)
            holder.imageView.setImageBitmap(bitmap)
        } else {
            holder.imageView.setImageBitmap(null)
        }
    }

    override fun getItemCount() = entries.size
}
