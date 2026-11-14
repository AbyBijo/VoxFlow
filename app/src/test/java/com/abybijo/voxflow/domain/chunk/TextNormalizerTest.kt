package com.abybijo.voxflow.domain.chunk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `crlf becomes lf`() {
        assertEquals("a\nb", TextNormalizer.normalize("a\r\nb"))
    }

    @Test
    fun `curly quotes become straight`() {
        assertEquals("\"hi\" 'there'", TextNormalizer.normalize("“hi” ‘there’"))
    }

    @Test
    fun `three or more newlines collapse to a paragraph break`() {
        assertEquals("a\n\nb", TextNormalizer.normalize("a\n\n\n\nb"))
    }

    @Test
    fun `runs of spaces collapse and lines are trimmed`() {
        assertEquals("a b\nc", TextNormalizer.normalize("  a   b  \n  c  "))
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", TextNormalizer.normalize("   \n\n  "))
    }
}
