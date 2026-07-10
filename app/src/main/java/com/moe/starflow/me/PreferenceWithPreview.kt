package com.moe.starflow.me

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.moe.starflow.R
import com.moe.starflow.utils.CustomPreference
import java.io.File

class PreferenceWithPreview : Preference {
    private var previewImageView: ImageView? = null
    private lateinit var prefs: CustomPreference
    var prefKey: String = "Custom_Floating_Pic"
        private set

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        attrs?.let { readAttrs(context, it) }
    }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        attrs?.let { readAttrs(context, it) }
    }

    private fun readAttrs(context: Context, attrs: AttributeSet) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PreferenceWithPreview)
        try {
            val key = a.getString(R.styleable.PreferenceWithPreview_prefKey)
            if (!key.isNullOrEmpty()) prefKey = key
        } finally {
            a.recycle()
        }
    }

    init {
        layoutResource = R.layout.item_preference_with_preview
        prefs = CustomPreference.getInstance(context)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        previewImageView = holder.findViewById(R.id.preview_image) as ImageView
        updatePreviewImage()
    }

    fun refreshPreview() {
        updatePreviewImage()
    }

    /**
     * 默认图按 prefKey 决定。Game → icon_game_default；Comic → icon_comic_default；其它 →
     * 历史默认 floating_ball_icon。
     */
    private fun defaultDrawableRes(): Int = when (prefKey) {
        "Icon_Game" -> R.mipmap.icon_game_default
        "Icon_Comic" -> R.mipmap.icon_comic_default
        else -> R.drawable.floating_ball_icon
    }

    private fun updatePreviewImage() {
        previewImageView?.let { imageView ->
            val customPicName = prefs.getString(prefKey, "")
            val fallback = defaultDrawableRes()

            if (customPicName.isEmpty()) {
                Glide.with(context)
                    .load(fallback)
                    .transform(CircleCrop())
                    .override(100, 100)
                    .into(imageView)
            } else {
                val iconFile = File(context.getExternalFilesDir(null), "icon/$customPicName")
                if (iconFile.exists()) {
                    Glide.with(context)
                        .load(iconFile)
                        .transform(CircleCrop())
                        .override(100, 100)
                        .error(fallback)
                        .into(imageView)
                } else {
                    Glide.with(context)
                        .load(fallback)
                        .transform(CircleCrop())
                        .override(100, 100)
                        .into(imageView)
                }
            }
        }
    }
}
