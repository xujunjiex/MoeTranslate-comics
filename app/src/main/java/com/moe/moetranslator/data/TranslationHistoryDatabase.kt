package com.moe.moetranslator.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HistoryEntity::class, PageCacheEntity::class],
    version = 8,
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

        // 版本 3 → 4：page_cache 添加 cropWidth/cropHeight 字段
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE page_cache ADD COLUMN cropWidth INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page_cache ADD COLUMN cropHeight INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 版本 4 → 5：translation_history 添加 updated_at 字段
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_history ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): TranslationHistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranslationHistoryDatabase::class.java,
                    "translation_history.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
        }
    }
}
