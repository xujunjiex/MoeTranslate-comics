package com.moe.moetranslator.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "page_cache",
    foreignKeys = [
        ForeignKey(
            entity = HistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["historyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["pHash", "mode"]),
        Index(value = ["lastAccessedAt"])
    ]
)
data class PageCacheEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val historyId: Long,        // FK -> translation_history.id
    val pHash: Long,            // 感知哈希值
    val mode: Int,              // 0=游戏, 1=漫画
    val lastAccessedAt: Long,   // 最后访问时间（LRU）
    val createdAt: Long         // 创建时间
)
