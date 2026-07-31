package com.moe.starflow.data

import com.moe.starflow.download.ModelKey
import com.moe.starflow.download.DownloadState
import com.moe.starflow.download.FileInfo
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ModelDownloadRepositoryTest {
    private lateinit var repo: ModelDownloadRepository
    private lateinit var tempDir: File
    private lateinit var context: android.content.Context

    @Before
    fun setup() {
        tempDir = java.nio.file.Files.createTempDirectory("modelrepo").toFile()
        context = org.mockito.Mockito.mock(android.content.Context::class.java)
        org.mockito.Mockito.`when`(context.applicationContext).thenReturn(context)
        org.mockito.Mockito.`when`(context.getExternalFilesDir(null))
            .thenReturn(tempDir)
        repo = ModelDownloadRepository.getInstance(context)
    }

    @Test
    fun `initialize - no files - all Idle`() = runTest {
        repo.clearAll()
        repo.initialize()
        val state = repo.getState(ModelKey.RT_DETR_V2)
        assertEquals(DownloadState.Idle, state)
    }

    @Test
    fun `initialize - part exists - Partial`() = runTest {
        repo.clearAll()
        val modelDir = File(context.getExternalFilesDir(null), "rt_detr")
        modelDir.mkdirs()
        File(modelDir, "detector-v4-s_int8.onnx.part").writeBytes(ByteArray(100))
        repo.initialize()
        val state = repo.getState(ModelKey.RT_DETR_V2)
        assertTrue("expected Partial, got $state", state is DownloadState.Partial)
    }

    @Test
    fun `initialize - target file exists - Done`() = runTest {
        repo.clearAll()
        val modelDir = File(context.getExternalFilesDir(null), "rt_detr")
        modelDir.mkdirs()
        File(modelDir, "detector-v4-s_int8.onnx").writeBytes(ByteArray(100))
        repo.initialize()
        assertEquals(DownloadState.Done, repo.getState(ModelKey.RT_DETR_V2))
    }

    @Test
    fun `markDone then markRunning - Running wins`() = runTest {
        repo.clearAll()
        repo.markDone(ModelKey.RT_DETR_V2)
        repo.markRunning(ModelKey.RT_DETR_V2)
        val state = repo.getState(ModelKey.RT_DETR_V2)
        assertTrue("expected Running, got $state", state is DownloadState.Running)
    }

    @Test
    fun `cancelDownload - deletes parts and returns to Idle`() = runTest {
        repo.clearAll()
        val modelDir = File(context.getExternalFilesDir(null), "rt_detr")
        modelDir.mkdirs()
        val partFile = File(modelDir, "detector-v4-s_int8.onnx.part")
        partFile.writeBytes(ByteArray(100))
        repo.markPartial(ModelKey.RT_DETR_V2)
        repo.cancelDownload(ModelKey.RT_DETR_V2)
        assertEquals(DownloadState.Idle, repo.getState(ModelKey.RT_DETR_V2))
        assertTrue("part file should be deleted", !partFile.exists())
    }

    @Test
    fun `deleteDownload - removes target and parts`() = runTest {
        repo.clearAll()
        val modelDir = File(context.getExternalFilesDir(null), "rt_detr")
        modelDir.mkdirs()
        val targetFile = File(modelDir, "detector-v4-s_int8.onnx")
        val partFile = File(modelDir, "detector-v4-s_int8.onnx.part")
        targetFile.writeBytes(ByteArray(100))
        partFile.writeBytes(ByteArray(50))
        repo.markDone(ModelKey.RT_DETR_V2)
        repo.deleteDownload(ModelKey.RT_DETR_V2)
        assertEquals(DownloadState.Idle, repo.getState(ModelKey.RT_DETR_V2))
        assertTrue("target should be deleted", !targetFile.exists())
        assertTrue("part should be deleted", !partFile.exists())
        assertTrue("folder should be preserved", modelDir.exists())
    }

    @Test
    fun `observe snapshots - state changes emit`() = runTest {
        repo.clearAll()
        val states = mutableListOf<DownloadState>()
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repo.observe().collect { snapshot ->
                states.add(snapshot.states[ModelKey.RT_DETR_V2] ?: DownloadState.Idle)
            }
        }
        repo.markRunning(ModelKey.RT_DETR_V2)
        kotlinx.coroutines.delay(50)
        repo.markDone(ModelKey.RT_DETR_V2)
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertTrue("should emit at least 2 states, got ${states.size}", states.size >= 2)
    }
}
