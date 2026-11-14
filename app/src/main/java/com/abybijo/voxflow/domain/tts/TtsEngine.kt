package com.abybijo.voxflow.domain.tts

import com.abybijo.voxflow.domain.model.Voice

/**
 * The one interface every TTS engine implements (architecture §7, ADR-4).
 * UI and playback know only this interface. Swapping Kokoro for Piper,
 * Kitten or a cloud voice changes no screen code.
 */
interface TtsEngine {
    /** Engine id, e.g. "kokoro". */
    val id: String

    val capabilities: Capabilities

    /** Voices this engine can synthesise with. */
    suspend fun voices(): List<Voice>

    /**
     * Synthesise one chunk. Returns PCM at 24 kHz mono (see [PcmChunk]).
     * A failure is a [Result.failure]; a cancelled synthesis writes nothing.
     * The caller runs this on a bounded dispatcher, one chunk at a time.
     */
    suspend fun synthesize(request: SynthRequest): Result<PcmChunk>

    /** Release the loaded native session, if any. Reloadable afterwards. */
    fun release()
}

data class Capabilities(
    val offline: Boolean,
    val needsNetwork: Boolean,
    val streaming: Boolean,
    val languages: List<String>,
    /** Hard per-request character cap for this engine. */
    val maxCharsPerRequest: Int,
)

data class SynthRequest(
    val text: String,
    val voiceId: String,
    /** Speaker id inside the model (Kokoro sid). Clamped by the engine. */
    val voiceSid: Int,
    val speedHint: Float = 1.0f,
    /** For cancellation / tracing. */
    val requestId: String,
)

/**
 * One synthesised chunk: raw PCM16 samples, always at 24 kHz mono
 * (architecture ADR-2: one pipeline format, ever).
 */
data class PcmChunk(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmChunk) return false
        return sampleRate == other.sampleRate && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + samples.contentHashCode()
        return result
    }
}

/** Wraps any failure crossing the native (JNI) boundary. */
class SynthesisFailure(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
