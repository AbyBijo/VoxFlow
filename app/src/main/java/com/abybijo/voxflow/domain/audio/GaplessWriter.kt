package com.abybijo.voxflow.domain.audio

import com.abybijo.voxflow.domain.chunk.TextChunker

/**
 * Turns synthesised chunks into ONE continuous PCM stream (§6).
 *
 * The writer holds back only the last [FADE_SAMPLES] of each chunk.
 * When the next chunk arrives it writes, in order:
 *   1. the boundary silence of the chunk that just ended,
 *   2. the equal-power crossfade of the held tail with the new head,
 *   3. the new chunk's body (minus its own held tail).
 * At end of stream the held tail is written raw (it was trimmed to
 * near-silence, so there is no click).
 *
 * First audio is never delayed: a chunk's body is written as soon as it
 * arrives — only its final 10 ms is held for the join.
 *
 * Pure Kotlin over a sink, so the join math is unit-testable without an
 * AudioTrack. The AudioTrack-backed player just passes its write call.
 */
class GaplessWriter(
    private val sink: (ShortArray) -> Unit,
    private val fadeSamples: Int = FADE_SAMPLES,
) {
    companion object {
        /** 10 ms at 24 kHz — the architecture's 5–10 ms join crossfade. */
        const val FADE_SAMPLES = 240
    }

    private var pendingTail: ShortArray? = null
    private var pendingBoundary: TextChunker.Boundary = TextChunker.Boundary.NONE
    private var started = false

    /** Feed one synthesised chunk (already trimmed). */
    fun writeChunk(samples: ShortArray, boundaryAfter: TextChunker.Boundary) {
        if (samples.isEmpty()) return
        val tail = pendingTail
        if (tail == null) {
            // First chunk: no join partner. Write everything but the tail.
            writeBody(samples)
            started = true
        } else {
            // 1. Boundary silence of the chunk that just ended.
            val silenceMs = PcmUtils.boundarySilenceMs(pendingBoundary)
            if (silenceMs > 0) sink(PcmUtils.silenceSamples(silenceMs))
            // 2. Crossfade held tail with the new head (if both are long
            //    enough; a very short chunk just concatenates).
            if (tail.size == fadeSamples && samples.size >= fadeSamples) {
                val head = samples.copyOfRange(0, fadeSamples)
                sink(PcmUtils.crossfade(tail, head))
                pendingTail = null // consumed by the crossfade
                writeBody(samples.copyOfRange(fadeSamples, samples.size))
            } else {
                sink(tail)
                pendingTail = null // written raw
                writeBody(samples)
            }
        }
        pendingBoundary = boundaryAfter
    }

    private fun writeBody(samples: ShortArray) {
        if (samples.size <= fadeSamples) {
            // Too short to split: hold the whole thing as the tail.
            // (pendingTail is null here — the caller consumed it already.)
            val prev = pendingTail
            pendingTail = if (prev == null) {
                samples.copyOf()
            } else {
                prev + samples
            }
            // Keep the held tail bounded: never hold more than fadeSamples.
            val held = pendingTail!!
            if (held.size > fadeSamples) {
                sink(held.copyOfRange(0, held.size - fadeSamples))
                pendingTail = held.copyOfRange(held.size - fadeSamples, held.size)
            }
            return
        }
        sink(samples.copyOfRange(0, samples.size - fadeSamples))
        pendingTail = samples.copyOfRange(samples.size - fadeSamples, samples.size)
    }

    /** End of stream: flush the held tail. No trailing silence. */
    fun finish() {
        pendingTail?.let { sink(it) }
        pendingTail = null
        pendingBoundary = TextChunker.Boundary.NONE
        started = false
    }

    val hasStarted: Boolean get() = started
}
