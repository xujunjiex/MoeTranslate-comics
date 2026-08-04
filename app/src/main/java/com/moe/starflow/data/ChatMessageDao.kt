package com.moe.starflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insert(entity: ChatMessageEntity)

    @Query("SELECT * FROM chat_message ORDER BY created_at ASC, id ASC")
    suspend fun queryAll(): List<ChatMessageEntity>

    @Query("DELETE FROM chat_message")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM chat_message")
    suspend fun count(): Int
}
