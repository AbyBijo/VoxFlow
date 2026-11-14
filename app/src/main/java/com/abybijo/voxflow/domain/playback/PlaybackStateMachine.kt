package com.abybijo.voxflow.domain.playback

/**
 * The playback state machine. Pure Kotlin, unit-tested.
 *
 * States, not booleans: an illegal transition is a bug, not a silent
 * no-op — it throws, so a bad sequence surfaces in tests instead of
 * shipping as a frozen pill (the OpenFlow 3-dots failure mode).
 *
 *   IDLE → PREPARING → PLAYING ⇄ PAUSED → FINISHED
 *   PREPARING/PLAYING/PAUSED → BLOCKED (chunk failed after retries)
 *   BLOCKED → PREPARING (Retry)     any → IDLE (Stop)
 *   any → FAILED (fatal error)      FINISHED/FAILED/BLOCKED → IDLE (Reset)
 */
enum class PlaybackMode {
    IDLE, PREPARING, PLAYING, PAUSED, FINISHED, BLOCKED, FAILED,
}

data class PlaybackMachineState(
    val mode: PlaybackMode,
    val blockedChunk: Int? = null,
    val error: String? = null,
)

sealed interface PlaybackEvent {
    data object Prepare : PlaybackEvent
    data object FirstAudio : PlaybackEvent
    data object Pause : PlaybackEvent
    data object Resume : PlaybackEvent
    data object Finish : PlaybackEvent
    data class ChunkFailed(val chunk: Int, val error: String) : PlaybackEvent
    data object Retry : PlaybackEvent
    data class Fatal(val error: String) : PlaybackEvent
    data object Stop : PlaybackEvent
    data object Reset : PlaybackEvent
}

object PlaybackStateMachine {

    fun initial(): PlaybackMachineState = PlaybackMachineState(PlaybackMode.IDLE)

    fun transition(
        state: PlaybackMachineState,
        event: PlaybackEvent,
    ): PlaybackMachineState = when (event) {
        PlaybackEvent.Prepare -> when (state.mode) {
            PlaybackMode.IDLE, PlaybackMode.BLOCKED ->
                PlaybackMachineState(PlaybackMode.PREPARING)
            else -> illegal(state, event)
        }
        PlaybackEvent.FirstAudio -> when (state.mode) {
            PlaybackMode.PREPARING -> PlaybackMachineState(PlaybackMode.PLAYING)
            else -> illegal(state, event)
        }
        PlaybackEvent.Pause -> when (state.mode) {
            PlaybackMode.PLAYING -> PlaybackMachineState(PlaybackMode.PAUSED)
            else -> illegal(state, event)
        }
        PlaybackEvent.Resume -> when (state.mode) {
            PlaybackMode.PAUSED -> PlaybackMachineState(PlaybackMode.PLAYING)
            else -> illegal(state, event)
        }
        PlaybackEvent.Finish -> when (state.mode) {
            PlaybackMode.PLAYING, PlaybackMode.PAUSED, PlaybackMode.PREPARING ->
                PlaybackMachineState(PlaybackMode.FINISHED)
            else -> illegal(state, event)
        }
        is PlaybackEvent.ChunkFailed -> when (state.mode) {
            PlaybackMode.PREPARING, PlaybackMode.PLAYING, PlaybackMode.PAUSED ->
                PlaybackMachineState(
                    PlaybackMode.BLOCKED,
                    blockedChunk = event.chunk,
                    error = event.error,
                )
            else -> illegal(state, event)
        }
        PlaybackEvent.Retry -> when (state.mode) {
            PlaybackMode.BLOCKED -> PlaybackMachineState(PlaybackMode.PREPARING)
            else -> illegal(state, event)
        }
        is PlaybackEvent.Fatal -> PlaybackMachineState(
            PlaybackMode.FAILED, error = event.error,
        )
        PlaybackEvent.Stop -> PlaybackMachineState(PlaybackMode.IDLE)
        PlaybackEvent.Reset -> when (state.mode) {
            PlaybackMode.FINISHED, PlaybackMode.FAILED, PlaybackMode.BLOCKED ->
                PlaybackMachineState(PlaybackMode.IDLE)
            else -> illegal(state, event)
        }
    }

    private fun illegal(
        state: PlaybackMachineState,
        event: PlaybackEvent,
    ): Nothing = throw IllegalStateException(
        "Illegal playback transition: ${state.mode} + $event",
    )
}
