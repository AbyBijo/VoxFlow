package com.abybijo.voxflow.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackStateMachineTest {

    private fun run(vararg events: PlaybackEvent): PlaybackMachineState {
        var state = PlaybackStateMachine.initial()
        for (event in events) {
            state = PlaybackStateMachine.transition(state, event)
        }
        return state
    }

    @Test
    fun `happy path idle preparing playing paused playing finished idle`() {
        val state = run(
            PlaybackEvent.Prepare,
            PlaybackEvent.FirstAudio,
            PlaybackEvent.Pause,
            PlaybackEvent.Resume,
            PlaybackEvent.Finish,
            PlaybackEvent.Reset,
        )
        assertEquals(PlaybackMode.IDLE, state.mode)
    }

    @Test
    fun `blocked chunk retries and resumes`() {
        var state = run(
            PlaybackEvent.Prepare,
            PlaybackEvent.FirstAudio,
            PlaybackEvent.ChunkFailed(3, "boom"),
        )
        assertEquals(PlaybackMode.BLOCKED, state.mode)
        assertEquals(3, state.blockedChunk)
        state = PlaybackStateMachine.transition(state, PlaybackEvent.Retry)
        assertEquals(PlaybackMode.PREPARING, state.mode)
        state = PlaybackStateMachine.transition(state, PlaybackEvent.FirstAudio)
        assertEquals(PlaybackMode.PLAYING, state.mode)
    }

    @Test
    fun `fatal from playing lands in failed with the error`() {
        val state = run(
            PlaybackEvent.Prepare,
            PlaybackEvent.FirstAudio,
            PlaybackEvent.Fatal("no audio"),
        )
        assertEquals(PlaybackMode.FAILED, state.mode)
        assertEquals("no audio", state.error)
    }

    @Test
    fun `stop works from any mode`() {
        val state = run(
            PlaybackEvent.Prepare,
            PlaybackEvent.FirstAudio,
            PlaybackEvent.Stop,
        )
        assertEquals(PlaybackMode.IDLE, state.mode)
    }

    @Test
    fun `pause from idle is illegal`() {
        assertThrows(IllegalStateException::class.java) {
            run(PlaybackEvent.Pause)
        }
    }

    @Test
    fun `first audio from idle is illegal`() {
        assertThrows(IllegalStateException::class.java) {
            run(PlaybackEvent.FirstAudio)
        }
    }

    @Test
    fun `retry from playing is illegal`() {
        assertThrows(IllegalStateException::class.java) {
            run(
                PlaybackEvent.Prepare,
                PlaybackEvent.FirstAudio,
                PlaybackEvent.Retry,
            )
        }
    }

    @Test
    fun `finish from idle is illegal`() {
        assertThrows(IllegalStateException::class.java) {
            run(PlaybackEvent.Finish)
        }
    }
}
