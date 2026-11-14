package com.abybijo.voxflow.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.abybijo.voxflow.domain.audio.PcmUtils

/**
 * The one AudioTrack for live playback (architecture §6, ADR-1).
 *
 * One stream, 24 kHz mono PCM16, MODE_STREAM. No playlist, no item
 * transitions, no sample-rate renegotiation — gaps are impossible by
 * construction. The buffer is 4x the minimum so a slow chunk never
 * starves the track mid-sentence.
 *
 * Lifecycle: start() → write()… → pause()/resume() → stop().
 * stop() releases the track; a new start() builds a fresh one.
 */
class PcmStreamPlayer(appContext: Context) {

    private var track: AudioTrack? = null
    private var samplesWritten = 0L
    private var startedAtHeadPosition = 0
    private var paused = false

    val isPlaying: Boolean get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /** Underruns since start (0 is the budget — §13). */
    val underrunCount: Int get() = track?.underrunCount ?: 0

    /** Milliseconds of audio actually played (from the play head). */
    fun positionMs(): Long {
        val t = track ?: return 0L
        val head = t.playbackHeadPosition - startedAtHeadPosition
        return (head.coerceAtLeast(0) * 1000L) / PcmUtils.SAMPLE_RATE
    }

    /**
     * Build the track and start playing. Returns false when the track
     * could not be initialised (caller surfaces an error, never silence).
     */
    fun start(): Boolean {
        stop()
        val minBuffer = AudioTrack.getMinBufferSize(
            PcmUtils.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(PcmUtils.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
            newTrack.release()
            return false
        }
        track = newTrack
        samplesWritten = 0L
        paused = false
        newTrack.play()
        startedAtHeadPosition = newTrack.playbackHeadPosition
        return true
    }

    /**
     * Blocking write of PCM16 samples. Returns false when the track is
     * gone or the write failed (caller stops the session with an error).
     */
    fun write(samples: ShortArray): Boolean {
        val t = track ?: return false
        if (samples.isEmpty()) return true
        val written = t.write(samples, 0, samples.size)
        if (written < 0) return false
        samplesWritten += written
        return true
    }

    fun pause() {
        val t = track ?: return
        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
            t.pause()
            paused = true
        }
    }

    fun resume() {
        val t = track ?: return
        if (paused && t.playState == AudioTrack.PLAYSTATE_PAUSED) {
            t.play()
            paused = false
        }
    }

    fun setVolume(volume: Float) {
        track?.setVolume(volume)
    }

    /** Stop, flush and release. Safe to call repeatedly. */
    fun stop() {
        val t = track ?: return
        track = null
        runCatching {
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
        }
        runCatching { t.flush() }
        t.release()
        paused = false
    }
}
