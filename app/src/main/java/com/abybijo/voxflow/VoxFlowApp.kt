package com.abybijo.voxflow

import android.app.Application
import com.abybijo.voxflow.di.AppContainer

class VoxFlowApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Release the native engine under memory pressure; it reloads
        // lazily on the next Listen (§7).
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            container.releaseEngine()
        }
    }
}
