package com.abybijo.voxflow.data.models

/**
 * The versioned model manifest (user requirement, 2026-11-14):
 * model id + version + SHA-256, written at install time.
 *
 * On launch the manager checks: same version present, all required
 * files exist with recorded sizes, and the small files' hashes match.
 * If so, the model is used as-is — an app update NEVER forces a
 * re-download. Only a missing / corrupt / explicitly newer version
 * triggers a download.
 *
 * Hand-rolled minimal JSON (6 fields) — no serialisation library for
 * one small file. Round-trip is unit-tested.
 */
data class ModelManifest(
    val packId: String,
    val version: String,
    val archiveSha256: String,
    val modelSizeBytes: Long,
    val voicesSha256: String,
    val tokensSha256: String,
    val installedAtEpochMs: Long,
) {
    fun toJson(): String = buildString {
        append("{")
        field("packId", packId)
        field("version", version)
        field("archiveSha256", archiveSha256)
        append("\"modelSizeBytes\":").append(modelSizeBytes).append(",")
        field("voicesSha256", voicesSha256)
        field("tokensSha256", tokensSha256)
        append("\"installedAtEpochMs\":").append(installedAtEpochMs)
        append("}")
    }

    private fun StringBuilder.field(name: String, value: String) {
        append("\"").append(name).append("\":\"")
        append(value.replace("\\", "\\\\").replace("\"", "\\\""))
        append("\",")
    }

    companion object {
        fun fromJson(json: String): ModelManifest? = try {
            fun str(name: String): String {
                val key = "\"$name\":\""
                val start = json.indexOf(key) + key.length
                if (start < key.length) return ""
                val end = json.indexOf('"', start)
                return json.substring(start, end)
            }

            fun num(name: String): Long {
                val key = "\"$name\":"
                val start = json.indexOf(key) + key.length
                var end = start
                while (end < json.length && (json[end].isDigit())) end++
                return json.substring(start, end).toLong()
            }

            ModelManifest(
                packId = str("packId"),
                version = str("version"),
                archiveSha256 = str("archiveSha256"),
                modelSizeBytes = num("modelSizeBytes"),
                voicesSha256 = str("voicesSha256"),
                tokensSha256 = str("tokensSha256"),
                installedAtEpochMs = num("installedAtEpochMs"),
            ).takeIf { it.packId.isNotBlank() && it.version.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
