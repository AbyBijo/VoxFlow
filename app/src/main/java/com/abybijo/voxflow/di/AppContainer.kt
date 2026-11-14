package com.abybijo.voxflow.di

import android.content.Context
import com.abybijo.voxflow.data.audio.AudioFocusManager
import com.abybijo.voxflow.data.audio.PcmStreamPlayer
import com.abybijo.voxflow.data.models.KOKORO_EN_PACK
import com.abybijo.voxflow.data.models.ModelManager
import com.abybijo.voxflow.data.prefs.SettingsStore
import com.abybijo.voxflow.data.tts.KokoroEngine
import com.abybijo.voxflow.playback.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection (architecture §3, ADR-3). No Hilt in v1.
 * One readable object graph, built in the Application, kept lazy so cold
 * start stays fast. The Kokoro engine loads lazily on first Listen —
 * never at app start (cold-start budget, §13).
 */
class AppContainer(appContext: Context) {

    private val appContext: Context = appContext.applicationContext

    /** Application-scoped work (model download). Survives the Activity. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // No callTimeout on the download client: a 100 MB download
            // outlives any whole-call timeout (android-performance trap).
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    val modelManager: ModelManager by lazy {
        ModelManager(appContext, httpClient)
    }

    val settingsStore: SettingsStore by lazy {
        SettingsStore(appContext)
    }

    val player: PcmStreamPlayer by lazy {
        PcmStreamPlayer(appContext)
    }

    val focusManager: AudioFocusManager by lazy {
        AudioFocusManager(appContext)
    }

    private var engine: KokoroEngine? = null

    /** The Kokoro engine for the installed model dir (single session). */
    fun kokoroEngine(modelDir: File): KokoroEngine {
        val current = engine
        if (current != null) return current
        return KokoroEngine(modelDir).also { engine = it }
    }

    fun newSession(modelDir: File, scope: CoroutineScope): PlaybackSession =
        PlaybackSession(kokoroEngine(modelDir), player, focusManager, scope)

    /** Release the native engine on memory pressure (§7). Reloadable. */
    fun releaseEngine() {
        engine?.release()
        engine = null
    }

    companion object {
        val modelPack = KOKORO_EN_PACK
    }
}
