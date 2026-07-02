package com.moe.moetranslator.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HistoryEntity::class, PageCacheEntity::class],
    version = 10,
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

        // 版本 5 → 6：无 schema 变更（版本号跳转，迁移为空）
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) { /* no schema change needed */ }
        }

        // 版本 6 → 7：无 schema 变更（版本号跳转，迁移为空）
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) { /* no schema change needed */ }
        }

        // 版本 7 → 8：无 schema 变更（版本号跳转，迁移为空）
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) { /* no schema change needed */ }
        }

        // 版本 8 → 9：translation_history 添加 original_image_path / is_retranslated
        // page_cache 添加 crop_left / crop_top / crop_right / crop_bottom
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE translation_history ADD COLUMN original_image_path TEXT")
                database.execSQL("ALTER TABLE translation_history ADD COLUMN is_retranslated INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE page_cache ADD COLUMN crop_left INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE page_cache ADD COLUMN crop_top INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE page_cache ADD COLUMN crop_right INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE page_cache ADD COLUMN crop_bottom INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 版本 9 → 10：translation_history 和 page_cache 添加 pHash2/pHash3/pHash4（256-bit 扩展感知哈希）
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE translation_history ADD COLUMN pHash2 INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE translation_history ADD COLUMN pHash3 INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE translation_history ADD COLUMN pHash4 INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE page_cache ADD COLUMN pHash2 INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE page_cache ADD COLUMN pHash3 INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE page_cache ADD COLUMN pHash4 INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): TranslationHistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranslationHistoryDatabase::class.java,
                    "translation_history.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
        }
    }
}
