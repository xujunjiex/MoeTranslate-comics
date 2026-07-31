package com.moe.starflow.data

import com.moe.starflow.download.ModelKey
import com.moe.starflow.download.DownloadState
import com.moe.starflow.download.FileInfo
import com.moe.starflow.download.ModelDownloadRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ModelDownloadRepository 状态机测试：初始化磁盘扫描、取消/删除下载、状态流发射。
 *
 * 注意：仓库是 JVM 级单例，必须用 [TestModelRepo] 共享的 context/tempDir
 * （见其注释），否则目录错位导致 initialize/cancel/delete 断言失败。
 * org.json 被桩掉，需通过 TestModelRepo.seedModel 注入模型清单
 * （cancelDownload/deleteDownload 依赖 getModelInfo 非空，否则提前 return 空转）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadRepositoryTest {
    private lateinit var repo: ModelDownloadRepository

    @Before
    fun setup() {
        repo = TestModelRepo.repo
        TestModelRepo.reset()
        TestModelRepo.seedModel(ModelKey.RT_DETR_V2, listOf(RT_DETR_FILE))
    }

    @Test
    fun `initialize - no files - all Idle`() = runTest {
        repo.initialize()
        assertEquals(DownloadState.Idle, repo.getState(ModelKey.RT_DETR_V2))
    }

    @Test
    fun `initialize - part exists - Partial`() = runTest {
        val modelDir = File(TestModelRepo.tempDir, "rt_detr")
        modelDir.mkdirs()
        File(modelDir, RT_DETR_FILE.fileName + ".part").writeBytes(ByteArray(100))
        repo.initialize()
        val state = repo.getState(ModelKey.RT_DETR_V2)
        assertTrue("expected Partial, got $state", state is DownloadState.Partial)
    }

    @Test
    fun `initialize - target file exists - Done`() = runTest {
        val modelDir = File(TestModelRepo.tempDir, "rt_detr")
        modelDir.mkdirs()
        File(modelDir, RT_DETR_FILE.fileName).writeBytes(ByteArray(100))
        repo.initialize()
        assertEquals(DownloadState.Done, repo.getState(ModelKey.RT_DETR_V2))
    }

    @Test
    fun `markDone then markRunning - Running wins`() = runTest {
        repo.markDone(ModelKey.RT_DETR_V2)
        repo.markRunning(ModelKey.RT_DETR_V2)
        val state = repo.getState(ModelKey.RT_DETR_V2)
        assertTrue("expected Running, got $state", state is DownloadState.Running)
    }

    @Test
    fun `cancelDownload - deletes parts and returns to Idle`() = runTest {
        val modelDir = File(TestModelRepo.tempDir, "rt_detr")
        modelDir.mkdirs()
        val partFile = File(modelDir, RT_DETR_FILE.fileName + ".part")
        partFile.writeBytes(ByteArray(100))
        repo.markPartial(ModelKey.RT_DETR_V2)
        repo.cancelDownload(ModelKey.RT_DETR_V2)
        assertEquals(DownloadState.Idle, repo.getState(ModelKey.RT_DETR_V2))
        assertTrue("part file should be deleted", !partFile.exists())
    }

    @Test
    fun `deleteDownload - removes target and parts`() = runTest {
        val modelDir = File(TestModelRepo.tempDir, "rt_detr")
        modelDir.mkdirs()
        val targetFile = File(modelDir, RT_DETR_FILE.fileName)
        val partFile = File(modelDir, RT_DETR_FILE.fileName + ".part")
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
        val states = mutableListOf<DownloadState>()
        // UnconfinedTestDispatcher：collect 立即在当前线程执行，避免 runTest 虚拟时间
        // 与 GlobalScope(IO) 真线程收集器不协调导致的 0 发射竞态。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observe().collect { snapshot ->
                states.add(snapshot.states[ModelKey.RT_DETR_V2] ?: DownloadState.Idle)
            }
        }
        repo.markRunning(ModelKey.RT_DETR_V2)
        repo.markDone(ModelKey.RT_DETR_V2)
        assertTrue("should emit at least 2 states, got ${states.size}", states.size >= 2)
    }

    private companion object {
        val RT_DETR_FILE = FileInfo(
            fileName = "detector-v4-s_int8.onnx",
            downloadUrl = "https://x/detector-v4-s_int8.onnx",
            fileSize = 11_554_432L,
            checksum = ""
        )
    }
}
