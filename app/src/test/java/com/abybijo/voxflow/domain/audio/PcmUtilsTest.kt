package com.abybijo.voxflow.domain.audio

import com.abybijo.voxflow.domain.chunk.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmUtilsTest {

    @Test
    fun `float to pcm16 maps endpoints and clamps`() {
        val out = PcmUtils.floatToPcm16(floatArrayOf(0f, 1f, -1f, 2f, -2f, 0.5f))
        assertEquals(0, out[0].toInt())
        assertEquals(32767, out[1].toInt())
        assertEquals(-32767, out[2].toInt())
        assertEquals(32767, out[3].toInt())
        assertEquals(-32767, out[4].toInt())
        assertEquals(16383, out[5].toInt())
    }

    @Test
    fun `trim silence keeps a margin and empties pure silence`() {
        val samples = ShortArray(1000) { if (it in 400..599) 1000 else 0 }
        val trimmed = PcmUtils.trimSilence(samples)
        // Keeps 240 samples (10 ms) of margin each side: 160..839.
        assertEquals(680, trimmed.size)
        assertEquals(0, trimmed[0].toInt())
        assertEquals(1000, trimmed[240].toInt())
        assertEquals(0, trimmed[679].toInt())
        assertEquals(0, PcmUtils.trimSilence(ShortArray(100)).size)
    }

    @Test
    fun `silence samples are 24k per second`() {
        assertEquals(2880, PcmUtils.silenceSamples(120).size)
        assertEquals(0, PcmUtils.silenceSamples(0).size)
    }

    @Test
    fun `boundary silence durations match the architecture`() {
        assertEquals(120, PcmUtils.boundarySilenceMs(TextChunker.Boundary.SENTENCE))
        assertEquals(250, PcmUtils.boundarySilenceMs(TextChunker.Boundary.CLAUSE))
        assertEquals(450, PcmUtils.boundarySilenceMs(TextChunker.Boundary.PARAGRAPH))
        assertEquals(0, PcmUtils.boundarySilenceMs(TextChunker.Boundary.NONE))
    }

    @Test
    fun `crossfade starts at previous and ends at next`() {
        val prev = ShortArray(240) { 1000 }
        val next = ShortArray(240) { 2000 }
        val out = PcmUtils.crossfade(prev, next)
        assertEquals(240, out.size)
        assertEquals(1000, out[0].toInt())
        assertEquals(2000, out[239].toInt())
        // Middle is a true mix, louder than either alone would be linearly.
        assertTrue(out[120].toInt() > 1000 && out[120].toInt() < 2800)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `crossfade rejects mismatched lengths`() {
        PcmUtils.crossfade(ShortArray(10), ShortArray(20))
    }
}
