package com.abybijo.voxflow.domain.chunk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun `blank text has no chunks`() {
        assertTrue(TextChunker.chunk("   ").isEmpty())
        assertTrue(TextChunker.chunk("").isEmpty())
    }

    @Test
    fun `one short sentence is one chunk with no trailing pause`() {
        val chunks = TextChunker.chunk("Hello world.")
        assertEquals(1, chunks.size)
        assertEquals("Hello world.", chunks[0].text)
        assertEquals(TextChunker.Boundary.NONE, chunks[0].boundaryAfter)
        assertEquals(0, chunks[0].charOffset)
    }

    @Test
    fun `abbreviations do not split sentences`() {
        // Long enough to force several chunks; no chunk may end mid-name.
        val unit = "Dr. Adams met Mr. Jones in St. Louis. " +
            "They talked about the project for hours. "
        val text = unit.repeat(6) // ~480 chars
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.size >= 2)
        for (chunk in chunks) {
            val trimmed = chunk.text.trim()
            assertTrue("chunk ends with abbreviation: $trimmed", !trimmed.endsWith("Dr."))
            assertTrue("chunk ends with abbreviation: $trimmed", !trimmed.endsWith("Mr."))
            assertTrue("chunk ends with abbreviation: $trimmed", !trimmed.endsWith("St."))
            assertTrue(chunk.text.length <= TextChunker.HARD_CAP)
        }
    }

    @Test
    fun `decimals do not split sentences`() {
        val chunks = TextChunker.chunk("The value is 3.14 and pi is 3.14159. It works.")
        assertEquals(1, chunks.size)
    }

    @Test
    fun `ellipsis is one terminator`() {
        val chunks = TextChunker.chunk("Wait... what happened? Nothing.")
        assertEquals(1, chunks.size)
        assertEquals("Wait... what happened? Nothing.", chunks[0].text)
    }

    @Test
    fun `paragraphs are never merged across the break`() {
        val p1 = "First paragraph. ".repeat(12).trim() // ~200 chars
        val p2 = "Second paragraph. ".repeat(12).trim()
        val chunks = TextChunker.chunk("$p1\n\n$p2")
        assertEquals(2, chunks.size)
        assertEquals(TextChunker.Boundary.PARAGRAPH, chunks[0].boundaryAfter)
        assertEquals(0, chunks[0].paragraphIndex)
        assertEquals(1, chunks[1].paragraphIndex)
    }

    @Test
    fun `long sentence splits at clauses within the cap`() {
        // 420 chars: over the 400 hard cap, so it must clause-split.
        // (The old 266-char sentence fits the 180-280 target in one chunk
        // and is correctly NOT split.)
        val sentence = "The quick brown fox jumps over the lazy dog, " +
            "and then it runs through the forest, but it soon grows tired, " +
            "so it rests beside the river, and it dreams of home, " +
            "while the birds sing above the trees, and the sun sets slowly, " +
            "and the night comes quietly over the hills, " +
            "and the stars appear one by one, " +
            "while the river keeps flowing past the stones, " +
            "and the fox sleeps until morning, " +
            "and the dawn breaks softly over the valley."
        val chunks = TextChunker.chunk(sentence)
        assertTrue(chunks.size >= 2)
        for (chunk in chunks) {
            assertTrue(chunk.text.length <= TextChunker.HARD_CAP)
        }
    }

    @Test
    fun `very long paste chunks within the cap and keeps its ends`() {
        val text = ("Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
            "Sed do eiusmod tempor incididunt ut labore. ").repeat(60)
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.size > 20)
        for (chunk in chunks) {
            assertTrue(chunk.text.length <= TextChunker.HARD_CAP)
            assertTrue(chunk.text.length >= 50)
        }
        assertEquals(text.trim().take(20), chunks.first().text.take(20))
        assertTrue(chunks.last().text.endsWith(text.trim().takeLast(20)))
    }

    @Test
    fun `only punctuation does not crash`() {
        val chunks = TextChunker.chunk("!!! ??? ...")
        assertEquals(1, chunks.size)
    }

    @Test
    fun `mixed languages do not crash`() {
        val chunks = TextChunker.chunk("Hello world. 你好世界。这是一个测试。Goodbye.")
        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue(chunk.text.length <= TextChunker.HARD_CAP)
        }
    }

    @Test
    fun `numbers and urls do not crash`() {
        val chunks = TextChunker.chunk(
            "Visit https://example.com/page?a=1&b=2 for details. Call 555-1234."
        )
        assertEquals(1, chunks.size)
    }

    @Test
    fun `single word longer than the cap is hard-split at the cap`() {
        val word = "a".repeat(500)
        val chunks = TextChunker.chunk(word)
        assertEquals(2, chunks.size)
        assertEquals(400, chunks[0].text.length)
        assertEquals(100, chunks[1].text.length)
    }

    @Test
    fun `chunk offsets are exact substrings`() {
        val text = "First sentence here. Second sentence follows. " +
            "Third one completes the set."
        val chunks = TextChunker.chunk(text)
        for (chunk in chunks) {
            assertEquals(
                chunk.text,
                text.substring(chunk.charOffset, chunk.charOffset + chunk.text.length),
            )
        }
    }
}
