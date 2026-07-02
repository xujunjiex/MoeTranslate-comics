package com.moe.moetranslator.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translation_history",
    indices = [
        Index(value = ["type", "created_at"])
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: Int,              // 0=游戏翻译, 1=漫画翻译
    val sourceText: String?,    // 原文（游戏翻译）
    val translatedText: String?,// 译文（游戏翻译）
    val imagePath: String?,     // 渲染后图片路径（漫画翻译）
    val thumbnailPath: String?, // 缩略图路径（漫画翻译）
    val sourceLang: String,     // 源语言代码
    val targetLang: String,     // 目标语言代码
    val translatorName: String, // 翻译器名称
    val pHash: Long,            // 感知哈希值（64 位低段，后向兼容）
    val pHash2: Long = 0,       // 扩展哈希（256-bit 第 2 段）
    val pHash3: Long = 0,       // 扩展哈希（256-bit 第 3 段）
    val pHash4: Long = 0,       // 扩展哈希（256-bit 第 4 段）
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0,    // session 创建时间（继承自同 pHash 旧记录，永不改变）
    @ColumnInfo(name = "session_id", defaultValue = "")
    val sessionId: String = "", // 原始创建会话 ID（首次翻译时分配，永不改变，用于按创建排序分组）
    @ColumnInfo(name = "last_session_id", defaultValue = "")
    val lastSessionId: String = "", // 最后修改会话 ID（任何修改时更新为当前会话，用于按修改排序分组）
    @ColumnInfo(name = "original_image_path")
    val originalImagePath: String? = null,

    @ColumnInfo(name = "is_retranslated", defaultValue = "0")
    val isRetranslated: Boolean = false,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0     // 最后修改时间戳（翻译/缓存命中时更新）
)
