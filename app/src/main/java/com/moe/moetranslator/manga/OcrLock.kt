package com.moe.moetranslator.manga

/**
 * OCR 引擎互斥锁。
 * PP-OCRv5 / MangaOcr / CTD 等 ONNX 引擎是单例对象，同时多线程调用会崩溃。
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
}
