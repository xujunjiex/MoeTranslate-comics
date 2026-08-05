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
