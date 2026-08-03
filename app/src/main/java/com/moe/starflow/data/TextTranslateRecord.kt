package com.moe.starflow.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 文本翻译页最近记录。独立于 history 表，只在本页内消费。 */
@Entity(tableName = "text_translate_record")
data class TextTranslateRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "original_text") val originalText: String,
    @ColumnInfo(name = "translated_text") val translatedText: String,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    @ColumnInfo(name = "target_lang") val targetLang: String,
    @ColumnInfo(name = "engine_name") val engineName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
