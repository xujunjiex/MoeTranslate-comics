package com.moe.moetranslator.data

import androidx.room.ColumnInfo
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
        Index(value = ["historyId"]),
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
    val createdAt: Long,        // 创建时间
    val cropWidth: Int = 0,     // 裁剪区域宽度（面积比校验，0=旧数据无记录；新数据用 crop_left/crop_right）
    val cropHeight: Int = 0,    // 裁剪区域高度（面积比校验，0=旧数据无记录；新数据用 crop_top/crop_bottom）
    @ColumnInfo(name = "crop_left", defaultValue = "0")
    val cropLeft: Int = 0,
    @ColumnInfo(name = "crop_top", defaultValue = "0")
    val cropTop: Int = 0,
    @ColumnInfo(name = "crop_right", defaultValue = "0")
    val cropRight: Int = 0,
    @ColumnInfo(name = "crop_bottom", defaultValue = "0")
    val cropBottom: Int = 0
)
