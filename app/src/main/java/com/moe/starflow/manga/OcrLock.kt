package com.moe.starflow.manga

import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*

/**
 * OCR 引擎互斥锁。
 * PP-OCRv5 / MangaOcr 等 ONNX 引擎是单例对象，同时多线程调用会崩溃。
 * 正常翻译（MangaFloatingService）和重翻（MangaViewerActivity）共用此锁。
 */
object OcrLock {
    @Volatile
    var isRunning = false
        private set

    @Synchronized
    fun tryAcquire(): Boolean {
        if (isRunning) return false
        isRunning = true
        return true
    }

    @Synchronized
    fun release() {
        isRunning = false
    }

    /**
     * 安全地获取锁并执行代码块。获取失败时抛出 RejectedExecutionException。
     */
    inline fun <T> use(block: () -> T): T {
        if (!tryAcquire()) {
            throw java.util.concurrent.RejectedExecutionException("OcrLock is busy")
        }
        try {
            return block()
        } finally {
            release()
        }
    }
}
