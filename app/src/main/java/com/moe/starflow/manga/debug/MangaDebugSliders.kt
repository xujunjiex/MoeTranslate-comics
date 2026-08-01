package com.moe.starflow.manga.debug

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import com.moe.starflow.manga.MergeParams
import com.moe.starflow.manga.PPOcrDefault
import com.moe.starflow.manga.PPOcrV5Engine
import com.moe.starflow.manga.PPOcrV6Engine
import com.moe.starflow.manga.TextRegionMerger
import com.moe.starflow.utils.CustomPreference
import kotlin.math.roundToInt

/**
 * 调试参数滑块面板构建器：读取/写入 prefs 中的调试参数，构建可交互的滑块 UI。
 * 依赖 prefs + context 通过参数注入，不持有任何服务引用。
 */
object MangaDebugSliders {

    fun createPPOcrParamSlidersView(prefs: CustomPreference, context: Context): View {
        val dp = context.resources.displayMetrics.density

        // 默认值统一从 PPOcrDefault 取（单一来源）。改默认值时只动 PPOcrParams.kt。
        // 别名（缩写）：UI 层常用短名，提高可读性。
        val DEF_BOX = PPOcrDefault.DET_BOX_THRESH_V5
        val DEF_UNCLIP = PPOcrDefault.DET_UNCLIP_RATIO
        val DEF_TEXT = PPOcrDefault.TEXT_SCORE_THRESH
        val DEF_LARGE_ENABLED = PPOcrDefault.LARGE_BOX_ENABLED
        val DEF_LARGE_RATIO = PPOcrDefault.LARGE_BOX_RATIO
        val DEF_LIMIT_SIDE_V5 = PPOcrDefault.LIMIT_SIDE_LEN
        val DEF_LIMIT_TYPE_V5 = PPOcrDefault.LIMIT_TYPE

        // 滑块范围映射
        fun boxToSeek(v: Float) = ((v - 0.01f) / 0.49f * 100).roundToInt().coerceIn(0, 100)
        fun seekToBox(v: Int) = 0.01f + v / 100f * 0.49f
        fun unclipToSeek(v: Float) = ((v - 1.0f) / 2.0f * 100).roundToInt().coerceIn(0, 100)
        fun seekToUnclip(v: Int) = 1.0f + v / 100f * 2.0f
        fun textToSeek(v: Float) = ((v - 0.1f) / 0.8f * 100).roundToInt().coerceIn(0, 100)
        fun seekToText(v: Int) = 0.1f + v / 100f * 0.8f
        fun limitSideToSeek(v: Int) = ((v - 64f) / (2048f - 64f) * 100f).roundToInt().coerceIn(0, 100)
        fun seekToLimitSide(v: Int): Int {
            // 100 段滑块范围 64~2048 不能整除，原始公式会让 1080 round-trip 成 1076
            // 强制 snap 到 20 像素倍数（64/80/100/.../1080/1100/.../2040/2048），保证默认值可往返
            val raw = 64 + v * (2048.0 - 64.0) / 100.0
            val snapped = (raw / 20.0).roundToInt() * 20
            return snapped.coerceIn(64, 2048)
        }
        fun ratioToSeek(v: Float) = ((v - 0.3f) / 0.5f * 100).roundToInt().coerceIn(0, 100)
        fun seekToRatio(v: Int) = 0.3f + v / 100f * 0.5f

        // 外层垂直容器
        val outerPanel = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setBackgroundColor(android.graphics.Color.argb(200, 30, 30, 30))
        }

        // ── 第一行：3 个滑块 ──
        val row1 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 存储引用以便恢复默认时更新
        data class SliderRef(
            val label: android.widget.TextView,
            val seekBar: android.widget.SeekBar,
            val labelText: String,
            val formatValue: (Int) -> String,
            val save: (Int) -> Unit
        )
        val sliderRefs = mutableListOf<SliderRef>()

        val sliders = listOf(
            Triple("检测置信度", boxToSeek(prefs.getFloat("ppocr_det_box_thresh", DEF_BOX)), { v: Int -> String.format("%.2f", seekToBox(v)) }),
            Triple("扩展比例", unclipToSeek(prefs.getFloat("ppocr_det_unclip_ratio", DEF_UNCLIP)), { v: Int -> String.format("%.1f", seekToUnclip(v)) }),
            Triple("识别置信度", textToSeek(prefs.getFloat("ppocr_text_score_thresh", DEF_TEXT)), { v: Int -> String.format("%.2f", seekToText(v)) })
        )
        val saveFns: List<(Int) -> Unit> = listOf(
            { v -> prefs.setFloat("ppocr_det_box_thresh", seekToBox(v)) },
            { v -> prefs.setFloat("ppocr_det_unclip_ratio", seekToUnclip(v)) },
            { v -> prefs.setFloat("ppocr_text_score_thresh", seekToText(v)) }
        )

        for ((idx, triple) in sliders.withIndex()) {
            val (name, seekInit, fmt) = triple
            val group = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val label = android.widget.TextView(context).apply {
                text = "$name\n${fmt(seekInit)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
            }

            val seekBar = android.widget.SeekBar(context).apply {
                max = 100
                progress = seekInit
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt()
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            label.text = "$name\n${fmt(progress)}"
                            saveFns[idx](progress)
                            PPOcrV5Engine.refreshParams(context)
                        }
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            }

            sliderRefs.add(SliderRef(label, seekBar, name, fmt, saveFns[idx]))
            group.addView(label)
            group.addView(seekBar)
            row1.addView(group)
        }
        outerPanel.addView(row1)

        // ── 第二行：limit_side_len + limit_type（max 推荐，避免细长框选被强制放大）──
        val rowLimitV5 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        val lslV5Raw = prefs.getInt("ppocr_limit_side_len", DEF_LIMIT_SIDE_V5)
        val lslV5SeekInit = limitSideToSeek(lslV5Raw)
        val lslV5Group = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val lslV5Label = android.widget.TextView(context).apply {
            text = "limit_side_len\n${lslV5Raw}"
            setTextColor(android.graphics.Color.WHITE); textSize = 11f; gravity = android.view.Gravity.CENTER; maxLines = 2
        }
        val lslV5Seek = android.widget.SeekBar(context).apply {
            max = 100; progress = lslV5SeekInit
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt())
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val v = seekToLimitSide(progress)
                        lslV5Label.text = "limit_side_len\n${v}"
                        prefs.setInt("ppocr_limit_side_len", v)
                        PPOcrV5Engine.refreshParams(context)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        sliderRefs.add(SliderRef(lslV5Label, lslV5Seek, "limit_side_len", { v: Int -> "${seekToLimitSide(v)}" }, { v -> prefs.setInt("ppocr_limit_side_len", seekToLimitSide(v)) }))
        lslV5Group.addView(lslV5Label); lslV5Group.addView(lslV5Seek); rowLimitV5.addView(lslV5Group)
        // limit_type：min / max 双按钮
        val ltV5Group = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
        }
        val ltV5Label = android.widget.TextView(context).apply {
            text = "limit_type"
            setTextColor(android.graphics.Color.WHITE); textSize = 11f; gravity = android.view.Gravity.CENTER
        }
        val ltV5BtnRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        val curLimitTypeV5 = prefs.getString("ppocr_limit_type", DEF_LIMIT_TYPE_V5) ?: DEF_LIMIT_TYPE_V5
        var ltV5MaxBtn: android.widget.TextView? = null
        val ltV5MinBtn = android.widget.TextView(context).apply {
            text = "min"
            textSize = 10f; gravity = android.view.Gravity.CENTER
            setPadding((4 * dp).toInt(), 2, (4 * dp).toInt(), 2)
            setTextColor(if (curLimitTypeV5 == "min") android.graphics.Color.argb(255, 76, 175, 80) else android.graphics.Color.argb(150, 200, 200, 200))
            isClickable = true; isFocusable = true
            setOnClickListener {
                prefs.setString("ppocr_limit_type", "min")
                PPOcrV5Engine.refreshParams(context)
                setTextColor(android.graphics.Color.argb(255, 76, 175, 80))
                ltV5MaxBtn?.setTextColor(android.graphics.Color.argb(150, 200, 200, 200))
            }
        }
        ltV5MaxBtn = android.widget.TextView(context).apply {
            text = "max"
            textSize = 10f; gravity = android.view.Gravity.CENTER
            setPadding((4 * dp).toInt(), 2, (4 * dp).toInt(), 2)
            setTextColor(if (curLimitTypeV5 == "max") android.graphics.Color.argb(255, 76, 175, 80) else android.graphics.Color.argb(150, 200, 200, 200))
            isClickable = true; isFocusable = true
            setOnClickListener {
                prefs.setString("ppocr_limit_type", "max")
                PPOcrV5Engine.refreshParams(context)
                setTextColor(android.graphics.Color.argb(255, 76, 175, 80))
                ltV5MinBtn.setTextColor(android.graphics.Color.argb(150, 200, 200, 200))
            }
        }
        ltV5BtnRow.addView(ltV5MinBtn); ltV5BtnRow.addView(ltV5MaxBtn!!)
        ltV5Group.addView(ltV5Label); ltV5Group.addView(ltV5BtnRow); rowLimitV5.addView(ltV5Group)
        outerPanel.addView(rowLimitV5)

        // ── 合并参数行：merge_discard_gap ──
        val rowMerge = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        val DEF_GAP = MergeParams.DISCARD_CONNECTION_GAP_DEFAULT

        fun mGapToSeek(v: Float) = ((v - 0.5f) / 4.5f * 100).toInt().coerceIn(0, 100)
        fun mSeekToGap(v: Int) = 0.5f + v / 100f * 4.5f

        data class MergeSliderRef(
            val label: android.widget.TextView,
            val seekBar: android.widget.SeekBar,
            val labelText: String,
            val formatValue: (Int) -> String,
            val save: (Int) -> Unit
        )
        val mergeSliderRefs = mutableListOf<MergeSliderRef>()

        // 唯一可调参数：距离门控（manga hardcoded 2.0）
        val mergeSliders = listOf(
            Triple("merge_gap", mGapToSeek(prefs.getFloat("merge_discard_gap", DEF_GAP)), { v: Int -> String.format("%.1f", mSeekToGap(v)) })
        )
        val mergeSaveFns: List<(Int) -> Unit> = listOf(
            { v -> prefs.setFloat("merge_discard_gap", mSeekToGap(v)); TextRegionMerger.refreshParams(context) }
        )

        for ((idx, triple) in mergeSliders.withIndex()) {
            val (name, seekInit, fmt) = triple
            val group = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val label = android.widget.TextView(context).apply {
                text = "$name\n${fmt(seekInit)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
            }

            val seekBar = android.widget.SeekBar(context).apply {
                max = 100
                progress = seekInit
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt()
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            label.text = "$name\n${fmt(progress)}"
                            mergeSaveFns[idx](progress)
                        }
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            }

            mergeSliderRefs.add(MergeSliderRef(label, seekBar, name, fmt, mergeSaveFns[idx]))
            group.addView(label)
            group.addView(seekBar)
            rowMerge.addView(group)
        }
        outerPanel.addView(rowMerge)

        // ── 第三行：大框过滤开关 + 比例滑块 ──
        val row2 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        val toggleLabel = android.widget.TextView(context).apply {
            text = "large_box"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            setPadding(0, 0, (4 * dp).toInt(), 0)
        }
        val largeBoxToggle = android.widget.Switch(context).apply {
            isChecked = prefs.getBoolean("ppocr_large_box_enabled", DEF_LARGE_ENABLED)
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.setBoolean("ppocr_large_box_enabled", isChecked)
                PPOcrV5Engine.refreshParams(context)
            }
        }
        row2.addView(toggleLabel)
        row2.addView(largeBoxToggle)

        val ratioLabel = android.widget.TextView(context).apply {
            val cur = prefs.getFloat("ppocr_large_box_ratio", DEF_LARGE_RATIO)
            text = "ratio ${String.format("%.0f%%", cur * 100)}"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }
        val ratioSeekBar = android.widget.SeekBar(context).apply {
            max = 100
            progress = ratioToSeek(prefs.getFloat("ppocr_large_box_ratio", DEF_LARGE_RATIO))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, (24 * dp).toInt(), 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val ratio = seekToRatio(progress)
                        ratioLabel.text = "ratio ${String.format("%.0f%%", ratio * 100)}"
                        prefs.setFloat("ppocr_large_box_ratio", ratio)
                        PPOcrV5Engine.refreshParams(context)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        row2.addView(ratioLabel)
        row2.addView(ratioSeekBar)
        outerPanel.addView(row2)

        // ── 第三行：恢复默认按钮 ──
        val row3 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        val resetBtn = android.widget.TextView(context).apply {
            text = "恢复默认"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            setBackgroundColor(android.graphics.Color.argb(150, 100, 100, 100))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // 重置 SharedPreferences
                prefs.setFloat("ppocr_det_box_thresh", DEF_BOX)
                prefs.setFloat("ppocr_det_unclip_ratio", DEF_UNCLIP)
                prefs.setFloat("ppocr_text_score_thresh", DEF_TEXT)
                prefs.setBoolean("ppocr_large_box_enabled", DEF_LARGE_ENABLED)
                prefs.setFloat("ppocr_large_box_ratio", DEF_LARGE_RATIO)
                prefs.setInt("ppocr_limit_side_len", DEF_LIMIT_SIDE_V5)
                prefs.setString("ppocr_limit_type", DEF_LIMIT_TYPE_V5)
                PPOcrV5Engine.refreshParams(context)

                // 更新 UI
                sliderRefs[0].apply { seekBar.progress = boxToSeek(DEF_BOX); label.text = "$labelText\n${formatValue(seekBar.progress)}" }
                sliderRefs[1].apply { seekBar.progress = unclipToSeek(DEF_UNCLIP); label.text = "$labelText\n${formatValue(seekBar.progress)}" }
                sliderRefs[2].apply { seekBar.progress = textToSeek(DEF_TEXT); label.text = "$labelText\n${formatValue(seekBar.progress)}" }
                sliderRefs[3].apply { seekBar.progress = limitSideToSeek(DEF_LIMIT_SIDE_V5); label.text = "$labelText\n${formatValue(seekBar.progress)}" }
                largeBoxToggle.isChecked = DEF_LARGE_ENABLED
                ratioSeekBar.progress = ratioToSeek(DEF_LARGE_RATIO)
                ratioLabel.text = "ratio ${String.format("%.0f%%", DEF_LARGE_RATIO * 100)}"
                // 同步 v5 limit_type 按钮高亮状态（min 灰、max 绿）
                ltV5MinBtn.setTextColor(android.graphics.Color.argb(150, 200, 200, 200))
                ltV5MaxBtn!!.setTextColor(android.graphics.Color.argb(255, 76, 175, 80))

                // 重置合并参数（仅距离门控 1 个滑块）
                TextRegionMerger.resetParams(context)
                mergeSliderRefs[0].apply { seekBar.progress = mGapToSeek(DEF_GAP); label.text = "$labelText\n${formatValue(seekBar.progress)}" }
            }
        }
        row3.addView(resetBtn)
        outerPanel.addView(row3)

        return outerPanel
    }

    /**
     * 创建 PP-OCRv6 参数滑块视图（9 个 v6 独立参数，ppocrv6_ 前缀 prefs）
     */
    @SuppressLint("SetTextI18n")
    fun createPPOcrV6ParamSlidersView(prefs: CustomPreference, context: Context): View {
        val dp = context.resources.displayMetrics.density

        // 默认值（与 PPOcrV6Engine.refreshParams 默认值一致）
        val DEF_DET_THRESH = 0.3f; val DEF_BOX = 0.5f; val DEF_UNCLIP = 1.6f
        val DEF_TEXT = 0.5f; val DEF_BATCH = 6
        val DEF_LARGE_ENABLED = false; val DEF_LARGE_RATIO = 0.6f
        val DEF_GAP = MergeParams.DISCARD_CONNECTION_GAP_DEFAULT
        // v6 新增参数默认值（对齐 v5 但稍大）
        val DEF_LIMIT_SIDE = 1200; val DEF_LIMIT_TYPE = "max"  // 比 v5 的 960 稍大，max 模式避免游戏细长框选被强制放大
        val DEF_USE_DILATION = true; val DEF_SCORE_MODE = "fast"; val DEF_MAX_CANDIDATES = 1000
        val DEF_MIN_HEIGHT = 30

        // 滑块范围映射
        fun detThreshToSeek(v: Float) = ((v - 0.1f) / 0.4f * 100).roundToInt().coerceIn(0, 100)
        fun seekToDetThresh(v: Int) = 0.1f + v / 100f * 0.4f
        fun boxToSeek(v: Float) = (v * 100).roundToInt().coerceIn(0, 100)
        fun seekToBox(v: Int) = v / 100f
        fun unclipToSeek(v: Float) = ((v - 1.0f) / 2.0f * 100).roundToInt().coerceIn(0, 100)
        fun seekToUnclip(v: Int) = 1.0f + v / 100f * 2.0f
        fun textToSeek(v: Float) = ((v - 0.1f) / 0.8f * 100).roundToInt().coerceIn(0, 100)
        fun seekToText(v: Int) = 0.1f + v / 100f * 0.8f
        fun batchToSeek(v: Int) = ((v - 1) * 100 / 11).coerceIn(0, 100)
        fun seekToBatch(v: Int) = 1 + v * 11 / 100
        fun ratioToSeek(v: Float) = ((v - 0.3f) / 0.5f * 100).roundToInt().coerceIn(0, 100)
        fun seekToRatio(v: Int) = 0.3f + v / 100f * 0.5f
        fun mGapToSeek(v: Float) = ((v - 0.5f) / 4.5f * 100).toInt().coerceIn(0, 100)
        fun mSeekToGap(v: Int) = 0.5f + v / 100f * 4.5f
        // v6 新增滑块范围映射
        fun limitSideToSeek(v: Int) = ((v - 64f) / (2048f - 64f) * 100f).roundToInt().coerceIn(0, 100)
        fun seekToLimitSide(v: Int): Int {
            // 100 段滑块范围 64~2048 不能整除，原始公式会让 1080 round-trip 成 1076
            // 强制 snap 到 20 像素倍数（64/80/100/.../1080/1100/.../2040/2048），保证默认值可往返
            val raw = 64 + v * (2048.0 - 64.0) / 100.0
            val snapped = (raw / 20.0).roundToInt() * 20
            return snapped.coerceIn(64, 2048)
        }
        fun seekToMinSide(v: Int) = (10.0 + v * 390.0 / 100.0).roundToInt()
        fun minHToSeek(v: Int) = ((v - 10f) / 190f * 100f).roundToInt().coerceIn(0, 100)
        fun seekToMinH(v: Int) = (10.0 + v * 190.0 / 100.0).roundToInt()
        fun maxCandToSeek(v: Int) = ((v - 50f) / 1950f * 100f).roundToInt().coerceIn(0, 100)
        fun seekToMaxCand(v: Int) = (50.0 + v * 1950.0 / 100.0).roundToInt()

        // 外层垂直容器
        val outerPanel = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setBackgroundColor(android.graphics.Color.argb(200, 30, 30, 30))
        }

        fun addSection(text: String) {
            val h = android.widget.TextView(context).apply {
                this.text = text
                setTextColor(android.graphics.Color.argb(255, 76, 175, 80))
                textSize = 11f
                setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
            }
            outerPanel.addView(h)
        }

        data class SliderRef(
            val label: android.widget.TextView,
            val seekBar: android.widget.SeekBar,
            val labelText: String,
            val formatValue: (Int) -> String,
            val save: (Int) -> Unit
        )
        val sliderRefs = mutableListOf<SliderRef>()

        addSection("── Det ──")
        // ── 第一行：3 个滑块（det_thresh, box_thresh, unclip_ratio）──
        val row1 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val sliders1 = listOf(
            Triple("thresh", detThreshToSeek(prefs.getFloat("ppocrv6_det_thresh", DEF_DET_THRESH)), { v: Int -> String.format("%.2f", seekToDetThresh(v)) }),
            Triple("box_thresh", boxToSeek(prefs.getFloat("ppocrv6_det_box_thresh", DEF_BOX)), { v: Int -> String.format("%.2f", seekToBox(v)) }),
            Triple("unclip_ratio", unclipToSeek(prefs.getFloat("ppocrv6_det_unclip_ratio", DEF_UNCLIP)), { v: Int -> String.format("%.1f", seekToUnclip(v)) })
        )
        val saveFns1: List<(Int) -> Unit> = listOf(
            { v -> prefs.setFloat("ppocrv6_det_thresh", seekToDetThresh(v)) },
            { v -> prefs.setFloat("ppocrv6_det_box_thresh", seekToBox(v)) },
            { v -> prefs.setFloat("ppocrv6_det_unclip_ratio", seekToUnclip(v)) }
        )

        for ((idx, triple) in sliders1.withIndex()) {
            val (name, seekInit, fmt) = triple
            val group = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val label = android.widget.TextView(context).apply {
                text = "$name\n${fmt(seekInit)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
            }

            val seekBar = android.widget.SeekBar(context).apply {
                max = 100
                progress = seekInit
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt()
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            label.text = "$name\n${fmt(progress)}"
                            saveFns1[idx](progress)
                            PPOcrV6Engine.refreshParams(context)
                        }
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            }

            sliderRefs.add(SliderRef(label, seekBar, name, fmt, saveFns1[idx]))
            group.addView(label)
            group.addView(seekBar)
            row1.addView(group)
        }
        outerPanel.addView(row1)

        addSection("── Rec ──")
        // ── 第二行：2 个滑块（text_score, rec_batch_num）──
        val row2 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        val sliders2 = listOf(
            Triple("text_score", textToSeek(prefs.getFloat("ppocrv6_text_score", DEF_TEXT)), { v: Int -> String.format("%.2f", seekToText(v)) }),
            Triple("rec_batch_num", batchToSeek(prefs.getInt("ppocrv6_rec_batch_num", DEF_BATCH)), { v: Int -> "${seekToBatch(v)}" })
        )
        val saveFns2: List<(Int) -> Unit> = listOf(
            { v -> prefs.setFloat("ppocrv6_text_score", seekToText(v)) },
            { v -> prefs.setInt("ppocrv6_rec_batch_num", seekToBatch(v)) }
        )

        for ((idx, triple) in sliders2.withIndex()) {
            val (name, seekInit, fmt) = triple
            val group = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val label = android.widget.TextView(context).apply {
                text = "$name\n${fmt(seekInit)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
            }

            val seekBar = android.widget.SeekBar(context).apply {
                max = 100
                progress = seekInit
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt()
                )
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            label.text = "$name\n${fmt(progress)}"
                            saveFns2[idx](progress)
                            PPOcrV6Engine.refreshParams(context)
                        }
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            }

            sliderRefs.add(SliderRef(label, seekBar, name, fmt, saveFns2[idx]))
            group.addView(label)
            group.addView(seekBar)
            row2.addView(group)
        }
        outerPanel.addView(row2)

        // ── 第三行：limit_side_len + limit_type ──
        val rowLimit = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        // limit_side_len 滑块
        val lslRaw = prefs.getInt("ppocrv6_limit_side_len", DEF_LIMIT_SIDE)
        val lslSeekInit = limitSideToSeek(lslRaw)
        val lslGroup = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val lslLabel = android.widget.TextView(context).apply {
            text = "limit_side_len\n${lslRaw}"
            setTextColor(android.graphics.Color.WHITE); textSize = 11f; gravity = android.view.Gravity.CENTER; maxLines = 2
        }
        val lslSeek = android.widget.SeekBar(context).apply {
            max = 100; progress = lslSeekInit
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt())
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val v = seekToLimitSide(progress)
                        lslLabel.text = "limit_side_len\n${v}"
                        prefs.setInt("ppocrv6_limit_side_len", v)
                        PPOcrV6Engine.refreshParams(context)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        sliderRefs.add(SliderRef(lslLabel, lslSeek, "limit_side_len", { v: Int -> "${seekToLimitSide(v)}" }, { v -> prefs.setInt("ppocrv6_limit_side_len", seekToLimitSide(v)) }))
        lslGroup.addView(lslLabel); lslGroup.addView(lslSeek); rowLimit.addView(lslGroup)
        // limit_type：min / max 双按钮
        val ltGroup = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
        }
        val ltLabel = android.widget.TextView(context).apply {
            text = "limit_type"
            setTextColor(android.graphics.Color.WHITE); textSize = 11f; gravity = android.view.Gravity.CENTER
        }
        val ltBtnRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        val curLimitType = prefs.getString("ppocrv6_limit_type", DEF_LIMIT_TYPE) ?: "min"
        var ltMaxBtn: android.widget.TextView? = null
        val ltMinBtn = android.widget.TextView(context).apply {
            text = "min"
            textSize = 10f; gravity = android.view.Gravity.CENTER
            setPadding((4 * dp).toInt(), 2, (4 * dp).toInt(), 2)
            setTextColor(if (curLimitType == "min") android.graphics.Color.argb(255, 76, 175, 80) else android.graphics.Color.argb(150, 200, 200, 200))
            isClickable = true; isFocusable = true
            setOnClickListener {
                prefs.setString("ppocrv6_limit_type", "min")
                PPOcrV6Engine.refreshParams(context)
                setTextColor(android.graphics.Color.argb(255, 76, 175, 80))
                ltMaxBtn?.setTextColor(android.graphics.Color.argb(150, 200, 200, 200))
            }
        }
        ltMaxBtn = android.widget.TextView(context).apply {
            text = "max"
            textSize = 10f; gravity = android.view.Gravity.CENTER
            setPadding((4 * dp).toInt(), 2, (4 * dp).toInt(), 2)
            setTextColor(if (curLimitType == "max") android.graphics.Color.argb(255, 76, 175, 80) else android.graphics.Color.argb(150, 200, 200, 200))
            isClickable = true; isFocusable = true
            setOnClickListener {
                prefs.setString("ppocrv6_limit_type", "max")
                PPOcrV6Engine.refreshParams(context)
                setTextColor(android.graphics.Color.argb(255, 76, 175, 80))
                ltMinBtn.setTextColor(android.graphics.Color.argb(150, 200, 200, 200))
            }
        }
        ltBtnRow.addView(ltMinBtn); ltBtnRow.addView(ltMaxBtn!!)
        ltGroup.addView(ltLabel); ltGroup.addView(ltBtnRow); rowLimit.addView(ltGroup)
        outerPanel.addView(rowLimit)

        // ── 第五行：min_height + width_height_ratio ──
        val rowFilter = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        // min_height 滑块
        val mhRaw = prefs.getInt("ppocrv6_min_height", DEF_MIN_HEIGHT)
        val mhSeekInit = minHToSeek(mhRaw)
        val mhGroup = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mhLabel = android.widget.TextView(context).apply {
            text = "min_height\n${mhRaw}"
            setTextColor(android.graphics.Color.WHITE); textSize = 11f; gravity = android.view.Gravity.CENTER; maxLines = 2
        }
        val mhSeek = android.widget.SeekBar(context).apply {
            max = 100; progress = mhSeekInit
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt())
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val v = seekToMinH(progress)
                        mhLabel.text = "min_height\n${v}"
                        prefs.setInt("ppocrv6_min_height", v)
                        PPOcrV6Engine.refreshParams(context)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        sliderRefs.add(SliderRef(mhLabel, mhSeek, "min_height", { v: Int -> "${seekToMinH(v)}" }, { v -> prefs.setInt("ppocrv6_min_height", seekToMinH(v)) }))
        mhGroup.addView(mhLabel); mhGroup.addView(mhSeek); rowFilter.addView(mhGroup)
        outerPanel.addView(rowFilter)

        // ── 第六行：max_candidates ──
        val rowCand = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        // max_candidates 滑块
        val mcRaw = prefs.getInt("ppocrv6_max_candidates", DEF_MAX_CANDIDATES)
        val mcSeekInit = maxCandToSeek(mcRaw)
        val mcGroup = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mcLabel = android.widget.TextView(context).apply {
            text = "max_candidates\n${mcRaw}"
            setTextColor(android.graphics.Color.WHITE); textSize = 11f; gravity = android.view.Gravity.CENTER; maxLines = 2
        }
        val mcSeek = android.widget.SeekBar(context).apply {
            max = 100; progress = mcSeekInit
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (24 * dp).toInt())
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val v = seekToMaxCand(progress)
                        mcLabel.text = "max_candidates\n${v}"
                        prefs.setInt("ppocrv6_max_candidates", v)
                        PPOcrV6Engine.refreshParams(context)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        sliderRefs.add(SliderRef(mcLabel, mcSeek, "max_candidates", { v: Int -> "${seekToMaxCand(v)}" }, { v -> prefs.setInt("ppocrv6_max_candidates", seekToMaxCand(v)) }))
        mcGroup.addView(mcLabel); mcGroup.addView(mcSeek); rowCand.addView(mcGroup)
        outerPanel.addView(rowCand)

        // use_dilation 开关（Det 最后一个）
        val rowDil = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        val dilLabel = android.widget.TextView(context).apply {
            text = "use_dilation"
            setTextColor(android.graphics.Color.WHITE); textSize = 11f
            setPadding(0, 0, (4 * dp).toInt(), 0)
        }
        val dilSwitch = android.widget.Switch(context).apply {
            isChecked = prefs.getBoolean("ppocrv6_use_dilation", DEF_USE_DILATION)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.setBoolean("ppocrv6_use_dilation", isChecked)
                PPOcrV6Engine.refreshParams(context)
            }
        }
        rowDil.addView(dilLabel); rowDil.addView(dilSwitch)
        outerPanel.addView(rowDil)

        addSection("── App ──")
        // merge_gap + large_box
        data class MergeSliderRef(
            val label: android.widget.TextView,
            val seekBar: android.widget.SeekBar,
            val labelText: String,
            val formatValue: (Int) -> String,
            val save: (Int) -> Unit
        )
        val mergeSliderRefs = mutableListOf<MergeSliderRef>()

        val mergeSeekInit = mGapToSeek(prefs.getFloat("merge_discard_gap", DEF_GAP))
        val mergeRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        val mergeLabel = android.widget.TextView(context).apply {
            text = "merge_gap"
            setTextColor(android.graphics.Color.WHITE); textSize = 11f
            setPadding(0, 0, (4 * dp).toInt(), 0)
        }
        val mergeSeek = android.widget.SeekBar(context).apply {
            max = 100; progress = mergeSeekInit
            layoutParams = android.widget.LinearLayout.LayoutParams(0, (24 * dp).toInt(), 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val v = mSeekToGap(progress)
                        mergeLabel.text = "merge_gap ${String.format("%.1f", v)}"
                        prefs.setFloat("merge_discard_gap", v)
                        TextRegionMerger.refreshParams(context)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        mergeSliderRefs.add(MergeSliderRef(mergeLabel, mergeSeek, "merge_gap",
            { v: Int -> String.format("%.1f", mSeekToGap(v)) },
            { v -> prefs.setFloat("merge_discard_gap", mSeekToGap(v)); TextRegionMerger.refreshParams(context) }))
        mergeRow.addView(mergeLabel); mergeRow.addView(mergeSeek)
        outerPanel.addView(mergeRow)

        // ── 第八行：大框过滤开关 ──
        val row3 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        val largeBoxLabel = android.widget.TextView(context).apply {
            text = "large_box"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            setPadding(0, 0, (2 * dp).toInt(), 0)
        }
        val largeBoxToggle = android.widget.Switch(context).apply {
            isChecked = prefs.getBoolean("ppocrv6_large_box_enabled", DEF_LARGE_ENABLED)
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.setBoolean("ppocrv6_large_box_enabled", isChecked)
                PPOcrV6Engine.refreshParams(context)
            }
        }

        row3.addView(largeBoxLabel)
        row3.addView(largeBoxToggle)
        outerPanel.addView(row3)

        // ── 第五行：大框丢弃比例滑块 ──
        val row4 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }

        val ratioLabel = android.widget.TextView(context).apply {
            val cur = prefs.getFloat("ppocrv6_large_box_ratio", DEF_LARGE_RATIO)
            text = "ratio ${String.format("%.0f%%", cur * 100)}"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, (4 * dp).toInt(), 0)
        }
        val ratioSeekBar = android.widget.SeekBar(context).apply {
            max = 100
            progress = ratioToSeek(prefs.getFloat("ppocrv6_large_box_ratio", DEF_LARGE_RATIO))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, (24 * dp).toInt(), 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val ratio = seekToRatio(progress)
                        ratioLabel.text = "ratio ${String.format("%.0f%%", ratio * 100)}"
                        prefs.setFloat("ppocrv6_large_box_ratio", ratio)
                        PPOcrV6Engine.refreshParams(context)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        row4.addView(ratioLabel)
        row4.addView(ratioSeekBar)
        outerPanel.addView(row4)

        // ── 第六行：恢复默认按钮 ──
        val row5 = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        val resetBtn = android.widget.TextView(context).apply {
            text = "恢复默认"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            setBackgroundColor(android.graphics.Color.argb(150, 100, 100, 100))
            isClickable = true; isFocusable = true
            setOnTouchListener { _, event ->
                if (event.action != android.view.MotionEvent.ACTION_UP) return@setOnTouchListener false
                performClick()
                // 重置 SharedPreferences
                prefs.setFloat("ppocrv6_det_thresh", DEF_DET_THRESH)
                prefs.setFloat("ppocrv6_det_box_thresh", DEF_BOX)
                prefs.setFloat("ppocrv6_det_unclip_ratio", DEF_UNCLIP)
                prefs.setFloat("ppocrv6_text_score", DEF_TEXT)
                prefs.setInt("ppocrv6_rec_batch_num", DEF_BATCH)
                prefs.setBoolean("ppocrv6_large_box_enabled", DEF_LARGE_ENABLED)
                prefs.setFloat("ppocrv6_large_box_ratio", DEF_LARGE_RATIO)
                // 新增参数
                prefs.setInt("ppocrv6_limit_side_len", DEF_LIMIT_SIDE)
                prefs.setString("ppocrv6_limit_type", DEF_LIMIT_TYPE)
                prefs.setBoolean("ppocrv6_use_dilation", DEF_USE_DILATION)
                prefs.setInt("ppocrv6_max_candidates", DEF_MAX_CANDIDATES)
                prefs.setInt("ppocrv6_min_height", DEF_MIN_HEIGHT)
                PPOcrV6Engine.refreshParams(context)

                // 更新 UI：逐个读 prefs 原始值 → 设滑块位置 → 设标签
                val rThresh = prefs.getFloat("ppocrv6_det_thresh", DEF_DET_THRESH); sliderRefs[0].apply { seekBar.progress = detThreshToSeek(rThresh); label.text = "thresh\n${String.format("%.2f", rThresh)}" }
                val rBox = prefs.getFloat("ppocrv6_det_box_thresh", DEF_BOX); sliderRefs[1].apply { seekBar.progress = boxToSeek(rBox); label.text = "box_thresh\n${String.format("%.2f", rBox)}" }
                val rUnclip = prefs.getFloat("ppocrv6_det_unclip_ratio", DEF_UNCLIP); sliderRefs[2].apply { seekBar.progress = unclipToSeek(rUnclip); label.text = "unclip_ratio\n${String.format("%.1f", rUnclip)}" }
                val rText = prefs.getFloat("ppocrv6_text_score", DEF_TEXT); sliderRefs[3].apply { seekBar.progress = textToSeek(rText); label.text = "text_score\n${String.format("%.2f", rText)}" }
                val rBatch = prefs.getInt("ppocrv6_rec_batch_num", DEF_BATCH); sliderRefs[4].apply { seekBar.progress = batchToSeek(rBatch); label.text = "rec_batch_num\n${rBatch}" }
                val rLim = prefs.getInt("ppocrv6_limit_side_len", DEF_LIMIT_SIDE); sliderRefs[5].apply { seekBar.progress = limitSideToSeek(rLim); label.text = "limit_side_len\n${rLim}" }
                val rH = prefs.getInt("ppocrv6_min_height", DEF_MIN_HEIGHT); sliderRefs[6].apply { seekBar.progress = minHToSeek(rH); label.text = "min_height\n${rH}" }
                val rCand = prefs.getInt("ppocrv6_max_candidates", DEF_MAX_CANDIDATES); sliderRefs[7].apply { seekBar.progress = maxCandToSeek(rCand); label.text = "max_candidates\n${rCand}" }
                largeBoxToggle.isChecked = DEF_LARGE_ENABLED
                ratioSeekBar.progress = ratioToSeek(DEF_LARGE_RATIO)
                ratioLabel.text = "ratio ${String.format("%.0f%%", DEF_LARGE_RATIO * 100)}"
                // 同步 v6 limit_type 按钮高亮状态（min 灰、max 绿）
                ltMinBtn.setTextColor(android.graphics.Color.argb(150, 200, 200, 200))
                ltMaxBtn!!.setTextColor(android.graphics.Color.argb(255, 76, 175, 80))
                TextRegionMerger.resetParams(context)
                mergeSliderRefs[0].apply { seekBar.progress = mGapToSeek(DEF_GAP); label.text = "merge_gap ${String.format("%.1f", DEF_GAP)}" }
                true
            }
        }
        row5.addView(resetBtn)
        outerPanel.addView(row5)

        // 包裹 ScrollView：内容过多可滚动，避免遮挡全屏
        val scrollView = android.widget.ScrollView(context).apply {
            addView(outerPanel)
            isVerticalScrollBarEnabled = true
            // 限制高度为屏幕的 50%
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.5).toInt()
            )
        }
        return scrollView
    }
}
