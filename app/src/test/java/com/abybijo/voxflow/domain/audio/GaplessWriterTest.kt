package com.abybijo.voxflow.domain.audio

import com.abybijo.voxflow.domain.chunk.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessWriterTest {

    private fun collect(vararg writes: Pair<ShortArray, TextChunker.Boundary>): List<Short> {
        val out = mutableListOf<Short>()
        val writer = GaplessWriter(sink = { samples -> out.addAll(samples.toList()) })
        for ((samples, boundary) in writes) writer.writeChunk(samples, boundary)
        writer.finish()
        return out
    }

    @Test
    fun `two chunks join as body silence crossfade body tail`() {
        val chunk1 = ShortArray(1000) { 1000 }
        val chunk2 = ShortArray(1000) { 2000 }
        val out = collect(
            chunk1 to TextChunker.Boundary.SENTENCE,
            chunk2 to TextChunker.Boundary.NONE,
        )
        // 760 body + 2880 silence (120 ms) + 240 crossfade + 520 body + 240 tail
        assertEquals(760 + 2880 + 240 + 520 + 240, out.size)
        assertEquals(1000, out[0].toInt())
        assertEquals(1000, out[759].toInt())
        assertEquals(0, out[760].toInt()) // silence starts
        assertEquals(0, out[760 + 2879].toInt())
        assertEquals(1000, out[760 + 2880].toInt()) // crossfade starts at prev
        assertEquals(2000, out[760 + 2880 + 239].toInt()) // crossfade ends at next
        assertEquals(2000, out[out.size - 1].toInt())
    }

    @Test
    fun `single chunk writes body plus tail, no silence`() {
        val chunk = ShortArray(1000) { 500 }
        val out = collect(chunk to TextChunker.Boundary.PARAGRAPH)
        assertEquals(1000, out.size)
        assertTrue(out.all { it == 500.toShort() })
    }

    @Test
    fun `empty chunks are ignored`() {
        val chunk = ShortArray(1000) { 700 }
        val out = collect(
            ShortArray(0) to TextChunker.Boundary.SENTENCE,
            chunk to TextChunker.Boundary.NONE,
        )
        assertEquals(1000, out.size)
    }

    @Test
    fun `paragraph boundary inserts 450 ms of silence`() {
        val chunk1 = ShortArray(1000) { 1000 }
        val chunk2 = ShortArray(1000) { 1000 }
        val out = collect(
            chunk1 to TextChunker.Boundary.PARAGRAPH,
            chunk2 to TextChunker.Boundary.NONE,
        )
        // 760 body + 10800 silence (450 ms) + 240 crossfade + 520 + 240
        assertEquals(760 + 10800 + 240 + 520 + 240, out.size)
        assertEquals(0, out[760].toInt())
        assertEquals(0, out[760 + 10799].toInt())
    }

    @Test
    fun `finish with no chunks writes nothing`() {
        val out = mutableListOf<Short>()
        val writer = GaplessWriter(sink = { samples -> out.addAll(samples.toList()) })
        writer.finish()
        assertTrue(out.isEmpty())
    }
}
