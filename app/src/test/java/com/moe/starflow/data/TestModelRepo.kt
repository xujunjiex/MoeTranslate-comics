package com.moe.starflow.data

import com.moe.starflow.download.FileInfo
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelInfo
import com.moe.starflow.download.ModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Mockito
import java.io.File

/**
 * 测试共享的 ModelDownloadRepository 单例夹具。
 *
 * `ModelDownloadRepository.getInstance()` 是 JVM 级单例，绑定**第一次**传入的 context
 * （`context.applicationContext`）。因此所有依赖该单例的测试类必须共享同一个 context/tempDir，
 * 否则后运行的类会操作先运行类的目录，磁盘扫描错位导致 initialize/cancel/delete 断言失败。
 *
 * 另：单测环境 org.json 被 returnDefaultValues 桩掉，`loadModelList()` 走不了 JSON 解析，
 * 因此通过 [seedModel] 反射注入 `modelListCached` 模拟 loadModelList 的结果
 * （`cancelDownload`/`deleteDownload` 依赖 `getModelInfo` 非空，否则会提前 return 空转）。
 */
object TestModelRepo {
    val tempDir: File = java.nio.file.Files.createTempDirectory("modelrepo-test-shared").toFile()

    val context: android.content.Context = run {
        val ctx = Mockito.mock(android.content.Context::class.java)
        Mockito.`when`(ctx.applicationContext).thenReturn(ctx)
        Mockito.`when`(ctx.getExternalFilesDir(null)).thenReturn(tempDir)
        ctx
    }

    val repo: ModelDownloadRepository by lazy { ModelDownloadRepository.getInstance(context) }

    /** 清空内存状态 + 共享目录中的模型子目录，保证每个测试从干净状态开始。 */
    fun reset() {
        repo.clearAll()
        listOf("models", "rt_detr", "manga_ocr_download", "ppocrv5", "ppocrv6")
            .forEach { File(tempDir, it).deleteRecursively() }
    }

    /** 注入模型文件清单（绕过被桩掉的 org.json），与 downloadinfo.json 对应。 */
    fun seedModel(modelKey: ModelKey, files: List<FileInfo>) {
        val field = ModelDownloadRepository::class.java.getDeclaredField("modelListCached")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(repo) as MutableStateFlow<List<ModelInfo>>
        flow.value = listOf(ModelInfo(modelKey, "", files))
    }
}
