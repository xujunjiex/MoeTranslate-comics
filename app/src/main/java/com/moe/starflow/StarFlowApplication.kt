package com.moe.starflow
import com.moe.starflow.translate.widget.*

import android.app.Application
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class StarFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 日志文件落盘（native 崩溃后日志仍保留）——必须最先初始化
        LogCollector.init(this)

        // Java 层未捕获异常 → 把堆栈写入 starflow.log（进程死亡前落盘），便于排查闪退。
        // 不打断系统默认处理：先写日志，再交给原 default handler（结束进程 + 系统崩溃报告）。
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                LogCollector.e("UncaughtException", "线程 ${thread.name} 未捕获异常", throwable)
            } catch (_: Throwable) {
            }
            prevHandler?.uncaughtException(thread, throwable)
        }

        // Initialize model download repository
        val repo = ModelDownloadRepository.getInstance(this)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                repo.loadModelList()
                repo.initialize()
            } catch (e: Exception) {
                LogCollector.e("StarFlowApp", "Failed to init model download repo", e)
            }
        }
    }
}
