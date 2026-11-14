package com.abybijo.voxflow.domain.audio

import com.abybijo.voxflow.domain.chunk.TextChunker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * PCM helpers for the single 24 kHz mono pipeline (architecture ADR-2).
 * Pure Kotlin — every function is unit-tested, including the join math,
 * because gaplessness is tested, not listened for hopefully (§13).
 */
object PcmUtils {

    const val SAMPLE_RATE = 24_000

    /** Float samples in [-1, 1] → PCM16. Clamps, never wraps. */
    fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = samples[i].coerceIn(-1f, 1f)
            out[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Trim leading/trailing near-silence, keeping a small margin so a
     * soft onset is not cut. Engine trailing padding is inconsistent —
     * the player trims it, it does not trust it (§6).
     */
    fun trimSilence(
        samples: ShortArray,
        threshold: Short = 32,
        keepSamples: Int = 240, // 10 ms at 24 kHz
    ): ShortArray {
        if (samples.isEmpty()) return samples
        var first = 0
        while (first < samples.size &&
            kotlin.math.abs(samples[first].toInt()) <= threshold
        ) first++
        var last = samples.size - 1
        while (last >= first &&
            kotlin.math.abs(samples[last].toInt()) <= threshold
        ) last--
        if (first > last) return ShortArray(0)
        val keepStart = maxOf(0, first - keepSamples)
        val keepEnd = minOf(samples.size - 1, last + keepSamples)
        return samples.copyOfRange(keepStart, keepEnd + 1)
    }

    /** Silence of [durationMs] at 24 kHz mono. */
    fun silenceSamples(durationMs: Int): ShortArray =
        ShortArray((durationMs * SAMPLE_RATE) / 1000)

    /** Boundary pause the player inserts after a chunk (§5). */
    fun boundarySilenceMs(boundary: TextChunker.Boundary): Int = when (boundary) {
        TextChunker.Boundary.SENTENCE -> 120
        TextChunker.Boundary.CLAUSE -> 250
        TextChunker.Boundary.PARAGRAPH -> 450
        TextChunker.Boundary.NONE -> 0
    }

    /**
     * Equal-power crossfade of [previousTail] into [nextHead].
     * Both arrays must be the same length ([fadeSamples]); returns the
     * overlapped region. Equal-power (cos/sin) keeps perceived loudness
     * flat through the join — a linear fade dips audibly.
     */
    fun crossfade(previousTail: ShortArray, nextHead: ShortArray): ShortArray {
        require(previousTail.size == nextHead.size) {
            "crossfade needs equal lengths: ${previousTail.size} vs ${nextHead.size}"
        }
        val n = previousTail.size
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = if (n <= 1) 1.0 else i.toDouble() / (n - 1)
            val gainOut = cos(t * PI / 2.0)
            val gainIn = sin(t * PI / 2.0)
            val mixed = previousTail[i] * gainOut + nextHead[i] * gainIn
            out[i] = mixed.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return out
    }
}
