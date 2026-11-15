package com.abybijo.voxflow.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManagerTest {

    @Test
    fun `required bytes are size times one and a half`() {
        // The Kokoro pack: 103,248,205 bytes -> 154,872,307 bytes (~147 MB).
        assertEquals(154_872_307L, ModelManager.requiredBytes(103_248_205L))
        assertEquals(150L, ModelManager.requiredBytes(100L))
    }

    @Test
    fun `storage error states real free and needed megabytes`() {
        // 12 GB free, 98 MB model -> 147 MB needed, so the message must
        // say 12288 MB free and 147 MB needed, not just "98 MB needed".
        val message = ModelManager.storageErrorMessage(12L * 1_024 * 1_024 * 1_024, 103_248_205L)
        assertEquals(
            "Not enough storage: 12288 MB free, 147 MB needed for the voice.",
            message,
        )
    }

    @Test
    fun `storage error never hides the free space`() {
        val message = ModelManager.storageErrorMessage(0L, 103_248_205L)
        assertTrue(message.contains("0 MB free"))
        assertTrue(message.contains("147 MB needed"))
    }
}
