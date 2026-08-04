package com.moe.starflow.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatMessageDaoTest {

    private lateinit var db: TranslationHistoryDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java).build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `insert 后 queryAll 按时间升序返回`() = runBlocking {
        val dao = db.chatMessageDao()
        dao.insert(ChatMessageEntity(role = 1, content = "a1", createdAt = 200))
        dao.insert(ChatMessageEntity(role = 0, content = "q1", createdAt = 100))
        val all = dao.queryAll()
        assertEquals(2, all.size)
        assertEquals("q1", all[0].content)
        assertEquals("a1", all[1].content)
    }

    @Test
    fun `clearAll 清空`() = runBlocking {
        val dao = db.chatMessageDao()
        dao.insert(ChatMessageEntity(role = 0, content = "x", createdAt = 1))
        dao.clearAll()
        assertEquals(0, dao.count())
    }
}
