package com.abybijo.voxflow.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abybijo.voxflow.data.models.ModelManager
import com.abybijo.voxflow.di.AppContainer
import com.abybijo.voxflow.domain.model.DEFAULT_VOICE_ID
import com.abybijo.voxflow.domain.playback.PlaybackMode
import com.abybijo.voxflow.playback.PlaybackSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * The single ViewModel for the single Home screen.
 * Exposes one immutable [UiState]; the screen collects it with
 * collectAsStateWithLifecycle().
 *
 * State survives rotation (ViewModel) and process death (text + voice
 * in DataStore, restored in init). Playback itself does not survive
 * process death in Slice 1 — resume is Slice 2 (docs/risks.md).
 */
class HomeViewModel(
    private val container: AppContainer,
) : ViewModel() {

    data class UiState(
        val text: String = "",
        val voiceId: String = DEFAULT_VOICE_ID,
        val modelState: ModelManager.State = ModelManager.State.Checking,
        val session: PlaybackSession.SessionState = PlaybackSession.SessionState(),
        val estimatedMinutes: Int = 0,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var session: PlaybackSession? = null
    private var modelDir: File? = null
    private var ensureJob: Job? = null
    private var saveTextJob: Job? = null

    init {
        viewModelScope.launch {
            val savedText = container.settingsStore.readSavedText()
            val voiceId = container.settingsStore.readVoiceId()
            _ui.update {
                it.copy(
                    text = savedText,
                    voiceId = voiceId,
                    estimatedMinutes = estimateMinutes(savedText),
                )
            }
        }
        ensureModel()
    }

    /** (Re)run the model check/download. Safe to call repeatedly. */
    fun ensureModel() {
        ensureJob?.cancel()
        // Application scope: a rotation must not cancel a 100 MB download.
        ensureJob = container.appScope.launch {
            container.modelManager.ensureModel(AppContainer.modelPack) { state ->
                if (state is ModelManager.State.Ready) modelDir = state.dir
                _ui.update { it.copy(modelState = state) }
            }
        }
    }

    fun onTextChange(newText: String) {
        _ui.update {
            it.copy(text = newText, estimatedMinutes = estimateMinutes(newText))
        }
        // Debounced persist: the text survives process death.
        saveTextJob?.cancel()
        saveTextJob = viewModelScope.launch {
            delay(500)
            container.settingsStore.saveText(newText)
        }
    }

    fun onVoiceSelected(id: String) {
        // The picker is disabled while a session is active; guard anyway.
        val mode = _ui.value.session.mode
        if (mode == PlaybackMode.PREPARING || mode == PlaybackMode.PLAYING ||
            mode == PlaybackMode.PAUSED
        ) return
        viewModelScope.launch { container.settingsStore.setVoiceId(id) }
        _ui.update { it.copy(voiceId = id) }
    }

    fun onListen() {
        val state = _ui.value
        if (state.text.isBlank()) return
        val dir = modelDir ?: return
        if (session == null) {
            session = container.newSession(dir, viewModelScope).also { s ->
                viewModelScope.launch {
                    s.state.collect { ss ->
                        _ui.update { it.copy(session = ss) }
                    }
                }
            }
        }
        session?.start(state.text, state.voiceId)
    }

    fun onPauseResume() {
        when (_ui.value.session.mode) {
            PlaybackMode.PLAYING -> session?.pause()
            PlaybackMode.PAUSED -> session?.resume()
            else -> Unit
        }
    }

    fun onStop() {
        session?.stop()
    }

    fun onRetry() {
        session?.retry()
    }

    override fun onCleared() {
        session?.release()
        super.onCleared()
    }

    companion object {
        /** ~850 characters per minute of English speech. */
        const val CHARS_PER_MINUTE = 850

        fun estimateMinutes(text: String): Int {
            if (text.isBlank()) return 0
            return (text.length + CHARS_PER_MINUTE - 1) / CHARS_PER_MINUTE
        }
    }
}
