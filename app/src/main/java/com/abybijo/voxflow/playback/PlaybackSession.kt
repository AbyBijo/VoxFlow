package com.abybijo.voxflow.playback

import com.abybijo.voxflow.data.audio.AudioFocusManager
import com.abybijo.voxflow.data.audio.PcmStreamPlayer
import com.abybijo.voxflow.domain.audio.GaplessWriter
import com.abybijo.voxflow.domain.audio.PcmUtils
import com.abybijo.voxflow.domain.chunk.TextChunker
import com.abybijo.voxflow.domain.chunk.TextNormalizer
import com.abybijo.voxflow.domain.model.voiceById
import com.abybijo.voxflow.domain.playback.PlaybackEvent
import com.abybijo.voxflow.domain.playback.PlaybackMachineState
import com.abybijo.voxflow.domain.playback.PlaybackMode
import com.abybijo.voxflow.domain.playback.PlaybackStateMachine
import com.abybijo.voxflow.domain.tts.SynthRequest
import com.abybijo.voxflow.domain.tts.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Coordinates synthesis + playback for one Listen (architecture §4).
 *
 * - ONE synthesis worker, one chunk at a time, keeping 3 ready chunks
 *   ahead of the playhead, then throttling. It resumes when the buffer
 *   drains to 1.
 * - The writer drains ready chunks in order through [GaplessWriter]
 *   into the single AudioTrack stream.
 * - A chunk that fails retries twice with backoff, then marks the item
 *   BLOCKED at that chunk with Retry. Ready chunks keep playing;
 *   nothing is skipped silently.
 * - Pause stops the track AND stalls the worker (buffer cap); resume
 *   continues exactly where it stopped.
 * - Audio-focus loss pauses (and the writer will not feed the track
 *   while focus is lost); focus gain resumes only if VoxFlow was
 *   playing when focus was lost.
 *
 * Slice 1 runs in-process (no foreground service — that is Slice 2).
 * Playback stops if the app is killed; this is stated in docs/risks.md.
 */
class PlaybackSession(
    private val engine: TtsEngine,
    private val player: PcmStreamPlayer,
    private val focusManager: AudioFocusManager,
    parentScope: CoroutineScope,
) {
    data class SessionState(
        val mode: PlaybackMode = PlaybackMode.IDLE,
        val totalChunks: Int = 0,
        /** 0-based index of the chunk currently being heard. */
        val currentChunk: Int = 0,
        val readyChunks: Int = 0,
        val positionMs: Long = 0L,
        val blockedChunk: Int? = null,
        val error: String? = null,
    )

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var machine: PlaybackMachineState = PlaybackStateMachine.initial()
    private val retrySignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var chunks: List<TextChunker.Chunk> = emptyList()
    private var ready: Array<ShortArray?> = emptyArray()
    private var playedCount = 0
    private var totalSamplesWritten = 0L

    /** True while focus is lost; the writer will not feed the track. */
    private var focusPaused = false

    /** True when the current PAUSED state was caused by focus loss. */
    private var pausedByFocusLoss = false

    init {
        focusManager.onDuckChanged = { ducked ->
            player.setVolume(if (ducked) 0.3f else 1.0f)
        }
        focusManager.onPauseForLoss = {
            focusPaused = true
            if (_state.value.mode == PlaybackMode.PLAYING) {
                pausedByFocusLoss = true
                pause()
            }
        }
        focusManager.onResumeAfterGain = {
            focusPaused = false
            if (_state.value.mode == PlaybackMode.PAUSED && pausedByFocusLoss) {
                pausedByFocusLoss = false
                resume()
            }
        }
    }

    /** Start listening to [rawText] with the given voice id. */
    fun start(rawText: String, voiceId: String) {
        val normalized = TextNormalizer.normalize(rawText)
        val parsed = TextChunker.chunk(normalized)
        require(parsed.isNotEmpty()) { "No text to listen to." }
        if (machine.mode != PlaybackMode.IDLE) {
            stopInternal()
            machine = PlaybackStateMachine.initial()
        }
        chunks = parsed
        ready = arrayOfNulls(parsed.size)
        playedCount = 0
        totalSamplesWritten = 0L
        focusPaused = false
        pausedByFocusLoss = false
        transition(PlaybackEvent.Prepare)
        updateState(totalChunks = parsed.size)

        if (!focusManager.request()) {
            transition(PlaybackEvent.Fatal("Could not get audio focus."))
            updateState(error = "Could not get audio focus.")
            return
        }
        if (!player.start()) {
            focusManager.abandon()
            transition(PlaybackEvent.Fatal("Could not start audio output."))
            updateState(error = "Could not start audio output.")
            return
        }
        scope.launch { synthesisWorker(voiceId) }
        scope.launch { writer() }
        scope.launch { positionTicker() }
    }

    fun pause() {
        if (_state.value.mode != PlaybackMode.PLAYING) return
        player.pause()
        transition(PlaybackEvent.Pause)
        updateState()
    }

    fun resume() {
        if (_state.value.mode != PlaybackMode.PAUSED) return
        player.resume()
        transition(PlaybackEvent.Resume)
        updateState()
    }

    /** Retry the blocked chunk (BLOCKED → PREPARING, worker re-attempts). */
    fun retry() {
        if (_state.value.mode != PlaybackMode.BLOCKED) return
        transition(PlaybackEvent.Retry)
        updateState(blockedChunk = null, error = null)
        retrySignal.tryEmit(Unit)
    }

    fun stop() {
        stopInternal()
        machine = PlaybackStateMachine.initial()
        updateState(mode = PlaybackMode.IDLE)
    }

    private fun stopInternal() {
        job.cancelChildren()
        player.stop()
        focusManager.abandon()
    }

    fun release() {
        stopInternal()
        job.cancel()
    }

    // -- worker ----------------------------------------------------------

    private suspend fun synthesisWorker(voiceId: String) {
        val voice = voiceById(voiceId)
        var i = 0
        while (i < chunks.size) {
            // Throttle: keep at most 3 ready chunks ahead of the playhead.
            while (readyCount() - playedCount >= LOOKAHEAD) {
                delay(100)
            }
            val chunk = chunks[i]
            val pcm = synthesizeWithRetry(chunk, voice.id, voice.sid)
            if (pcm == null) {
                // Retries exhausted: BLOCKED at chunk i. Wait for Retry/Stop.
                transition(PlaybackEvent.ChunkFailed(i, "Could not synthesise chunk ${i + 1}."))
                updateState(blockedChunk = i, error = "Could not synthesise chunk ${i + 1}.")
                retrySignal.first() // suspends until retry() or cancellation
                continue // re-attempt chunk i (mode is PREPARING again)
            }
            ready[i] = pcm
            updateState(readyChunks = readyCount())
            i++
        }
    }

    private fun readyCount(): Int = ready.count { it != null }

    private suspend fun synthesizeWithRetry(
        chunk: TextChunker.Chunk,
        voiceId: String,
        voiceSid: Int,
    ): ShortArray? {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            val result = engine.synthesize(
                SynthRequest(
                    text = chunk.text,
                    voiceId = voiceId,
                    voiceSid = voiceSid,
                    requestId = "chunk-${chunk.id}-attempt-$attempt",
                )
            )
            if (result.isSuccess) {
                val pcm = result.getOrThrow()
                return PcmUtils.trimSilence(pcm.samples)
            }
            attempt++
            if (attempt < MAX_ATTEMPTS) delay(RETRY_BACKOFF_MS[attempt - 1])
        }
        return null
    }

    // -- writer ----------------------------------------------------------

    private suspend fun writer() {
        val gapless = GaplessWriter(sink = { samples ->
            totalSamplesWritten += samples.size
            if (!player.write(samples)) {
                throw IllegalStateException("Audio output failed.")
            }
        })
        try {
            for (i in chunks.indices) {
                while (ready[i] == null) {
                    // Blocked chunk waits here until Retry fills it.
                    delay(50)
                }
                // Honour pause and focus loss: do not feed the track.
                while (_state.value.mode == PlaybackMode.PAUSED || focusPaused) {
                    delay(100)
                }
                val samples = ready[i]!!
                if (_state.value.mode == PlaybackMode.PREPARING) {
                    transition(PlaybackEvent.FirstAudio)
                    updateState()
                }
                updateState(currentChunk = i)
                gapless.writeChunk(samples, chunks[i].boundaryAfter)
                playedCount++
            }
            gapless.finish()
            // Drain: wait until the play head reaches what was written,
            // or stalls for 2 s with the writer done (underrun guard).
            val totalMs = (totalSamplesWritten * 1000L) / PcmUtils.SAMPLE_RATE
            var stalledPolls = 0
            var lastPosition = -1L
            while (true) {
                val pos = player.positionMs()
                if (pos >= totalMs) break
                if (pos == lastPosition) {
                    stalledPolls++
                    if (stalledPolls >= 10) break
                } else {
                    stalledPolls = 0
                }
                lastPosition = pos
                delay(200)
            }
            transition(PlaybackEvent.Finish)
            updateState()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            transition(PlaybackEvent.Fatal(e.message ?: "Playback failed."))
            updateState(error = e.message ?: "Playback failed.")
        } finally {
            player.stop()
            focusManager.abandon()
        }
    }

    // -- position ticker -------------------------------------------------
    // 5 Hz is enough for chunk progress; per-frame StateFlow emission
    // would recompose the player card 60x/s for nothing (android-performance).

    private suspend fun positionTicker() {
        while (true) {
            if (_state.value.mode == PlaybackMode.PLAYING) {
                updateState(positionMs = player.positionMs())
            }
            delay(200)
        }
    }

    // -- state plumbing --------------------------------------------------

    private fun transition(event: PlaybackEvent) {
        machine = PlaybackStateMachine.transition(machine, event)
        updateState(mode = machine.mode)
    }

    private fun updateState(
        mode: PlaybackMode = _state.value.mode,
        totalChunks: Int = _state.value.totalChunks,
        currentChunk: Int = _state.value.currentChunk,
        readyChunks: Int = _state.value.readyChunks,
        positionMs: Long = _state.value.positionMs,
        blockedChunk: Int? = _state.value.blockedChunk,
        error: String? = _state.value.error,
    ) {
        _state.value = SessionState(
            mode = mode,
            totalChunks = totalChunks,
            currentChunk = currentChunk,
            readyChunks = readyChunks,
            positionMs = positionMs,
            blockedChunk = blockedChunk,
            error = error,
        )
    }

    companion object {
        /** Synthesis stays 3 chunks ahead, then throttles (§4). */
        const val LOOKAHEAD = 3

        /** Two retries after the first attempt. */
        const val MAX_ATTEMPTS = 3
        val RETRY_BACKOFF_MS = longArrayOf(500, 1500)
    }
}
