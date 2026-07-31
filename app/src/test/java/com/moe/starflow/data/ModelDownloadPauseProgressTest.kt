package com.moe.starflow.data

import com.moe.starflow.download.ModelKey
import com.moe.starflow.download.DownloadState
import com.moe.starflow.download.FileInfo
import com.moe.starflow.download.ModelDownloadRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * 回归测试：已暂停时显示的应是当前下载进度（读取 .part 文件），而不是 0。
 *
 * 背景：旧版 markPaused 只统计已完成的 target 文件、完全忽略 .part，
 * 导致「不管下载了多少，暂停都显示 0kb」。本测试验证 markPaused 能正确读取 .part。
 *
 * 说明：ModelDownloadRepository 是 JVM 级单例，绑定第一次创建的 context，
 * 必须与 ModelDownloadRepositoryTest 共享 [TestModelRepo] 的 context/tempDir，
 * 否则单例绑定的目录与测试写文件的目录错位。org.json 被桩掉，
 * 通过 TestModelRepo.seedModel 注入 NLLB 模型清单（与 downloadinfo.json 一致）。
 */
class ModelDownloadPauseProgressTest {

    private lateinit var repo: ModelDownloadRepository

    @Before
    fun setup() {
        repo = TestModelRepo.repo
        TestModelRepo.reset()
        seedNllbModelList()
    }

    /** 注入 NLLB 模型列表（5 个文件，与 downloadinfo.json 一致），绕过被桩掉的 org.json */
    private fun seedNllbModelList() {
        TestModelRepo.seedModel(ModelKey.NLLB_GROUP, listOf(
            FileInfo("NLLB_encoder.onnx", "https://x/NLLB_encoder.onnx", 266_487_014L, ""),
            FileInfo("NLLB_decoder.onnx", "https://x/NLLB_decoder.onnx", 179_109_694L, ""),
            FileInfo("NLLB_embed_and_lm_head.onnx", "https://x/NLLB_embed.onnx", 524_712_277L, ""),
            FileInfo("NLLB_cache_initializer.onnx", "https://x/NLLB_cache.onnx", 25_368_443L, ""),
            FileInfo("sentencepiece_bpe.model", "https://x/sentencepiece.model", 4_852_054L, "")
        ))
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
        val modelsDir = File(TestModelRepo.tempDir, "models")
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

        val modelsDir = File(TestModelRepo.tempDir, "models")
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
        val modelsDir = File(TestModelRepo.tempDir, "models")
        makeSparseFile(File(modelsDir, "NLLB_encoder.onnx"), 266_487_014L)

        repo.refreshFromDisk(ModelKey.NLLB_GROUP)

        val state = repo.getState(ModelKey.NLLB_GROUP)
        assertTrue("expected Partial (not Done), got $state", state is DownloadState.Partial)
    }

    @Test
    fun `refreshFromDisk - multi-file all complete - Done`() = runTest {
        val modelsDir = File(TestModelRepo.tempDir, "models")
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
}
