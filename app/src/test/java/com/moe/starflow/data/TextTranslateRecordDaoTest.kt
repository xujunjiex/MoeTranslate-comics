package com.moe.starflow.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TextTranslateRecordDaoTest {

    private var db: TranslationHistoryDatabase? = null
    private fun dao() = db!!.textTranslateRecordDao()

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    private fun record(i: Int) = TextTranslateRecord(
        originalText = "src$i", translatedText = "t$i",
        sourceLang = "ja", targetLang = "zh", engineName = "HyMT2", createdAt = i.toLong()
    )

    @Test
    fun insertAndQueryRecent_ordersNewestFirst() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
        dao().insert(record(1))
        dao().insert(record(2))
        assertEquals(listOf("src2", "src1"), dao().queryRecent(10).map { it.originalText })
    }

    @Test
    fun trimTo_removesOldestBeyondKeep() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
        repeat(5) { dao().insert(record(it)) }
        dao().trimTo(3)
        assertEquals(3, dao().count())
        assertEquals(listOf("src4", "src3", "src2"), dao().queryRecent(10).map { it.originalText })
    }

    @Test
    fun clearAll_empties() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
        dao().insert(record(1))
        dao().clearAll()
        assertEquals(0, dao().count())
    }
}
