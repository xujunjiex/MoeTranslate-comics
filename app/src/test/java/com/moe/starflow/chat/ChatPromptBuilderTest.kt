package com.moe.starflow.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPromptBuilderTest {

    @Test
    fun `空历史只返回当前输入作为 user`() {
        val (roles, contents) = ChatPromptBuilder.buildMessages(emptyList(), "你好")
        assertArrayEquals(intArrayOf(0), roles)
        assertArrayEquals(arrayOf("你好"), contents)
    }

    @Test
    fun `多轮历史保持顺序并追加当前输入`() {
        val history = listOf(
            ChatMessage(role = ChatRole.USER, content = "q1"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "a1"),
            ChatMessage(role = ChatRole.USER, content = "q2")
        )
        val (roles, contents) = ChatPromptBuilder.buildMessages(history, "q3")
        assertArrayEquals(intArrayOf(0, 1, 0, 0), roles)
        assertArrayEquals(arrayOf("q1", "a1", "q2", "q3"), contents)
    }

    @Test
    fun `历史超过上限时保留最近 N 条含当前输入`() {
        val history = (0 until 40).map {
            ChatMessage(role = if (it % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT, content = "m$it")
        }
        val (roles, contents) = ChatPromptBuilder.buildMessages(history, "input")
        assertEquals(ChatPromptBuilder.MAX_HISTORY_MESSAGES, roles.size)
        assertEquals("input", contents.last())
        // 40 条历史 + 1 当前 = 41，保留最近 30 → 丢前 11 条，第一条是 m11
        assertEquals("m11", contents.first())
    }
}
