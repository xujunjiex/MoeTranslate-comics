package com.moe.starflow.data
import com.moe.starflow.download.DownloadState
import com.moe.starflow.download.FileInfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInfoTest {
    @Test
    fun `downloadState Partial equals same data`() {
        val a = DownloadState.Partial(100L, 200L, 0, 1, "", 100L, 200L)
        val b = DownloadState.Partial(100L, 200L, 0, 1, "", 100L, 200L)
        assertEquals(a, b)
    }

    @Test
    fun `downloadState Running holds speed`() {
        val s = DownloadState.Running(50L, 200L, 1_000_000L, 0, 1, "", 25, 50L, 200L)
        assertEquals(1_000_000L, s.speedBytesPerSec)
    }

    @Test
    fun `downloadState Idle is singleton`() {
        assertTrue(DownloadState.Idle == DownloadState.Idle)
    }

    @Test
    fun `fileInfo stores MD5 checksum`() {
        val f = FileInfo("NLLB_encoder.onnx", "https://...", 266487014L, "abc123")
        assertEquals("abc123", f.checksum)
    }
}