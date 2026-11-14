package com.abybijo.voxflow

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import com.abybijo.voxflow.ui.home.HomeScreen
import com.abybijo.voxflow.ui.home.HomeViewModel
import com.abybijo.voxflow.ui.theme.VoxFlowTheme

/**
 * The single Activity (docs/risks.md: splash / launch flow).
 *
 * - No splash Activity, no SplashScreen API: the app opens straight onto
 *   Home. The window background matches the Home background, so cold
 *   start shows no flash and no lingering splash.
 * - launchMode="singleTop" (manifest): repeated launcher taps never
 *   stack a second Home.
 * - One screen in Slice 1: no nav graph, no back-stack to get stuck in.
 * - JankStats is installed debug-only (android-performance) and logs
 *   throttled jank lines tagged with the screen name.
 */
class MainActivity : ComponentActivity() {

    // Kept alive for the Activity's lifetime: if this is dropped,
    // JankStats stops tracking the window.
    private var jankStats: JankStats? = null

    private val viewModel: HomeViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as VoxFlowApp
                return HomeViewModel(app.container) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        installJankMonitor(this)
        setContent {
            VoxFlowTheme {
                HomeScreen(viewModel = viewModel)
            }
        }
    }

    /**
     * Debug-only JankStats wiring (android-performance/jankstats.md).
     * Release builds skip it entirely.
     */
    private fun installJankMonitor(activity: Activity) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val holder = PerformanceMetricsState.getHolderForHierarchy(
                activity.window.decorView,
            )
            var frames = 0
            var jankFrames = 0
            var worstMs = 0.0
            jankStats = JankStats.createAndTrack(activity.window) { frameData ->
                frames++
                if (!frameData.isJank) return@createAndTrack
                jankFrames++
                val ms = frameData.frameDurationUiNanos / 1_000_000.0
                if (ms > worstMs) worstMs = ms
                if (jankFrames == 1 || jankFrames % 8 == 0) {
                    android.util.Log.w(
                        "Jank",
                        "janky ${ms.toInt()}ms screen=Home " +
                            "jank=$jankFrames/$frames worst=${worstMs.toInt()}ms",
                    )
                }
            }
            holder.state.putState("screen", "Home")
        }
    }
}
