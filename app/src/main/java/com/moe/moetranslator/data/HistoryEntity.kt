package com.moe.moetranslator.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translation_history",
    indices = [
        Index(value = ["type", "createdAt"])
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
    val pHash: Long,            // 感知哈希值
    val createdAt: Long         // 创建时间戳
)
