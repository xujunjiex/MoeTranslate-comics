package com.moe.starflow.translate

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import translationapi.hymt2translation.HyMt2Params

class HyMt2ParamsTest {

    private fun emptyPrefs(): SharedPreferences {
        val p = mock(SharedPreferences::class.java)
        `when`(p.getString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer { it.getArgument(1) }
        `when`(p.getInt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
            .thenAnswer { it.getArgument(1) }
        `when`(p.getFloat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyFloat()))
            .thenAnswer { it.getArgument(1) }
        return p
    }

    @Test
    fun defaultsWhenEmpty() {
        val s = HyMt2Params.read(emptyPrefs())
        assertEquals(6, s.threads)
        assertEquals(2048, s.contextSize)
        assertEquals(0.7f, s.temperature, 0.001f)
        assertEquals(0.6f, s.topP, 0.001f)
        assertEquals(20, s.topK)
        assertEquals(1.05f, s.repetitionPenalty, 0.001f)
        assertEquals(4096, s.maxTokens)
        assert(s.promptTemplate.contains("{target_lang}"))
    }
}
