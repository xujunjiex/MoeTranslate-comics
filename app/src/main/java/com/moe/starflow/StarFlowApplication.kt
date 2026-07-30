package com.moe.starflow

import android.app.Application
import com.moe.starflow.data.ModelDownloadRepository
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
