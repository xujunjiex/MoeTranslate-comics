package com.moe.starflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TextTranslateRecordDao {

    @Insert
    suspend fun insert(record: TextTranslateRecord): Long

    /** 最新在前，最多 limit 条。 */
    @Query("SELECT * FROM text_translate_record ORDER BY id DESC LIMIT :limit")
    suspend fun queryRecent(limit: Int): List<TextTranslateRecord>

    /** 分页查询：最新在前，从 offset 起取 limit 条。 */
    @Query("SELECT * FROM text_translate_record ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun queryPage(limit: Int, offset: Int): List<TextTranslateRecord>

    @Query("DELETE FROM text_translate_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM text_translate_record")
    suspend fun count(): Int

    /** 只保留最新的 keep 条，删除更旧的。 */
    @Query("DELETE FROM text_translate_record WHERE id NOT IN (SELECT id FROM text_translate_record ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM text_translate_record")
    suspend fun clearAll()
}
