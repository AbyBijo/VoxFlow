package com.abybijo.voxflow.data.models

/**
 * The model catalogue (architecture §8). One entry in Slice 1.
 *
 * The rule is enforced in code: no single download over 200 MB, ever.
 * Every entry carries its exact size, SHA-256 and licence; the manager
 * refuses an entry that violates the rule.
 *
 * kokoro-int8-en-v0_19 was verified at build time (2026-11-14):
 * downloaded from the sherpa-onnx release, 103,248,205 bytes, SHA-256
 * c9f0dd39…08bd (matches the published manifest), containing
 * model.int8.onnx, voices.bin, tokens.txt, espeak-ng-data/, LICENSE
 * (Apache 2.0), README.md. No lexicon file ships in this pack.
 */
data class ModelPack(
    val id: String,
    val version: String,
    val displayName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val licence: String,
    /** Files (or directories) that must exist after install. */
    val requiredFiles: List<String>,
) {
    init {
        require(sizeBytes <= MAX_PACK_BYTES) {
            "Model pack $id is $sizeBytes bytes — over the 200 MB rule."
        }
    }

    companion object {
        const val MAX_PACK_BYTES = 200L * 1024 * 1024
    }
}

val KOKORO_EN_PACK = ModelPack(
    id = "kokoro-en-v0_19",
    version = "1.0",
    displayName = "Kokoro English",
    downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
        "tts-models/kokoro-int8-en-v0_19.tar.bz2",
    sizeBytes = 103_248_205L,
    sha256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
    licence = "Apache 2.0",
    requiredFiles = listOf(
        "model.int8.onnx",
        "voices.bin",
        "tokens.txt",
        "espeak-ng-data",
    ),
)
