package com.abybijo.voxflow.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelManifestTest {

    private val manifest = ModelManifest(
        packId = "kokoro-en-v0_19",
        version = "1.0",
        archiveSha256 = "abc123",
        modelSizeBytes = 103_248_205L,
        voicesSha256 = "def456",
        tokensSha256 = "ghi789",
        installedAtEpochMs = 1_792_000_000_000L,
    )

    @Test
    fun `round trip preserves every field`() {
        val parsed = ModelManifest.fromJson(manifest.toJson())
        assertNotNull(parsed)
        assertEquals(manifest, parsed)
    }

    @Test
    fun `garbage is not a manifest`() {
        assertNull(ModelManifest.fromJson("not json at all"))
        assertNull(ModelManifest.fromJson("{}"))
        assertNull(ModelManifest.fromJson(""))
    }

    @Test
    fun `missing numeric field is not a manifest`() {
        val json = manifest.toJson().replace("\"modelSizeBytes\":103248205,", "")
        assertNull(ModelManifest.fromJson(json))
    }

    @Test
    fun `toJson is stable and compact`() {
        val json = manifest.toJson()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"packId\":\"kokoro-en-v0_19\""))
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
