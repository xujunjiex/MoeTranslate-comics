package com.moe.moetranslator.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HistoryEntity::class, PageCacheEntity::class],
    version = 3,
    exportSchema = false
)
abstract class TranslationHistoryDatabase : RoomDatabase() {

    abstract fun historyDao(): TranslationHistoryDao

    companion object {
        @Volatile
        private var instance: TranslationHistoryDatabase? = null

        // 版本 2 → 3：添加 sessionId 字段
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_history ADD COLUMN session_id TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): TranslationHistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranslationHistoryDatabase::class.java,
                    "translation_history.db"
                ).addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
        }
    }
}
