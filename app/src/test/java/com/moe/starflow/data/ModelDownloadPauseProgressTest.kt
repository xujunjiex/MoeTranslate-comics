package com.moe.starflow.data

import com.moe.starflow.download.ModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.RandomAccessFile

/**
 * 回归测试：已暂停时显示的应是当前下载进度（读取 .part 文件），而不是 0。
 *
 * 背景：旧版 markPaused 只统计已完成的 target 文件、完全忽略 .part，
 * 导致「不管下载了多少，暂停都显示 0kb」。本测试验证 markPaused 能正确读取 .part。
 *
 * 说明：
 * 1. ModelDownloadRepository 是单例，且绑定第一次创建时的 context —— 因此用静态共享 context/tempDir，
 *    避免不同测试的 tempDir 不同导致单例读到别的目录。
 * 2. 单测环境 org.json 被 returnDefaultValues 桩掉，无法走 loadModelList，
 *    因此通过反射直接注入 modelListCached（模拟 loadModelList 的结果）。
 */
class ModelDownloadPauseProgressTest {

    private lateinit var repo: ModelDownloadRepository

    @Before
    fun setup() {
        repo = ModelDownloadRepository.getInstance(SHARED_CONTEXT)
        repo.clearAll()
        seedNllbModelList()
        // 清空共享目录，保证每个测试从干净状态开始
        File(SHARED_TEMP_DIR, "models").deleteRecursively()
    }

    /** 注入 NLLB 模型列表（5 个文件，与 downloadinfo.json 一致），绕过被桩掉的 org.json */
    private fun seedNllbModelList() {
        val files = listOf(
            FileInfo("NLLB_encoder.onnx", "https://x/NLLB_encoder.onnx", 266_487_014L, ""),
            FileInfo("NLLB_decoder.onnx", "https://x/NLLB_decoder.onnx", 179_109_694L, ""),
            FileInfo("NLLB_embed_and_lm_head.onnx", "https://x/NLLB_embed.onnx", 524_712_277L, ""),
            FileInfo("NLLB_cache_initializer.onnx", "https://x/NLLB_cache.onnx", 25_368_443L, ""),
            FileInfo("sentencepiece_bpe.model", "https://x/sentencepiece.model", 4_852_054L, "")
        )
        val field = ModelDownloadRepository::class.java.getDeclaredField("modelListCached")
        field.isAccessible = true
        val flow = field.get(repo) as MutableStateFlow<List<ModelInfo>>
        flow.value = listOf(ModelInfo(ModelKey.NLLB_GROUP, "", files))
    }

    private fun makeSparseFile(file: File, size: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(size)
        }
    }

    @Test
    fun `markPaused during NLLB file1 partial - shows real part bytes`() = runTest {
        repo.initialize()

        // 模拟正在下载第一个文件（NLLB_encoder.onnx 266MB），已下载 121MB
        val modelsDir = File(SHARED_TEMP_DIR, "models")
        makeSparseFile(File(modelsDir, "NLLB_encoder.onnx.part"), 121_986_432L)

        repo.markPaused(ModelKey.NLLB_GROUP)

        val state = repo.getState(ModelKey.NLLB_GROUP)
        assertTrue("expected Paused, got $state", state is DownloadState.Paused)
        val paused = state as DownloadState.Paused
        assertEquals("bytesDownloaded should be part size", 121_986_432L, paused.bytesDownloaded)
        assertEquals("currentFileIndex should be 0", 0, paused.currentFileIndex)
        assertEquals("currentFileCount should be 5", 5, paused.currentFileCount)
        assertEquals("currentFileName", "NLLB_encoder.onnx", paused.currentFileName)
        assertEquals("currentFileBytesDownloaded", 121_986_432L, paused.currentFileBytesDownloaded)
        assertEquals("currentFileTotalBytes", 266_487_014L, paused.currentFileTotalBytes)
    }

    @Test
    fun `markPaused with completed file1 - aggregates completed target`() = runTest {
        repo.initialize()

        val modelsDir = File(SHARED_TEMP_DIR, "models")
        // 文件 1 已完成（target 存在），文件 2 正在下载到 10MB
        makeSparseFile(File(modelsDir, "NLLB_encoder.onnx"), 266_487_014L)
        makeSparseFile(File(modelsDir, "NLLB_decoder.onnx.part"), 10_000_000L)

        repo.markPaused(ModelKey.NLLB_GROUP)

        val state = repo.getState(ModelKey.NLLB_GROUP)
        assertTrue("expected Paused, got $state", state is DownloadState.Paused)
        val paused = state as DownloadState.Paused
        assertEquals(
            "bytesDownloaded should aggregate completed + part",
            276_487_014L,
            paused.bytesDownloaded
        )
        assertEquals("currentFileIndex should be 1", 1, paused.currentFileIndex)
        assertEquals("currentFileName", "NLLB_decoder.onnx", paused.currentFileName)
        assertEquals("currentFileBytesDownloaded", 10_000_000L, paused.currentFileBytesDownloaded)
    }

    @Test
    fun `refreshFromDisk - multi-file only first complete - Partial not Done`() = runTest {
        // 只下载了第一个文件（encoder target 存在），其余 4 个没下载
        val modelsDir = File(SHARED_TEMP_DIR, "models")
        makeSparseFile(File(modelsDir, "NLLB_encoder.onnx"), 266_487_014L)

        repo.refreshFromDisk(ModelKey.NLLB_GROUP)

        val state = repo.getState(ModelKey.NLLB_GROUP)
        assertTrue("expected Partial (not Done), got $state", state is DownloadState.Partial)
    }

    @Test
    fun `refreshFromDisk - multi-file all complete - Done`() = runTest {
        val modelsDir = File(SHARED_TEMP_DIR, "models")
        makeSparseFile(File(modelsDir, "NLLB_encoder.onnx"), 266_487_014L)
        makeSparseFile(File(modelsDir, "NLLB_decoder.onnx"), 179_109_694L)
        makeSparseFile(File(modelsDir, "NLLB_embed_and_lm_head.onnx"), 524_712_277L)
        makeSparseFile(File(modelsDir, "NLLB_cache_initializer.onnx"), 25_368_443L)
        makeSparseFile(File(modelsDir, "sentencepiece_bpe.model"), 4_852_054L)

        repo.refreshFromDisk(ModelKey.NLLB_GROUP)

        assertEquals(DownloadState.Done, repo.getState(ModelKey.NLLB_GROUP))
    }

    @Test
    fun `refreshFromDisk - multi-file nothing - Idle`() = runTest {
        repo.refreshFromDisk(ModelKey.NLLB_GROUP)
        assertEquals(DownloadState.Idle, repo.getState(ModelKey.NLLB_GROUP))
    }

    companion object {
        private val SHARED_TEMP_DIR: File =
            java.nio.file.Files.createTempDirectory("modelrepo-pause-shared").toFile()
        private val SHARED_CONTEXT: android.content.Context = run {
            val context = Mockito.mock(android.content.Context::class.java)
            Mockito.`when`(context.applicationContext).thenReturn(context)
            Mockito.`when`(context.getExternalFilesDir(null)).thenReturn(SHARED_TEMP_DIR)
            context
        }
    }
}
