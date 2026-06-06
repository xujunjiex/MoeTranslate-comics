package com.moe.moetranslator.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class, PageCacheEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TranslationHistoryDatabase : RoomDatabase() {

    abstract fun historyDao(): TranslationHistoryDao

    companion object {
        @Volatile
        private var instance: TranslationHistoryDatabase? = null

        fun getInstance(context: Context): TranslationHistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TranslationHistoryDatabase::class.java,
                    "translation_history.db"
                ).fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
        }
    }
}
