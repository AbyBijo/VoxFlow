package com.abybijo.voxflow.domain.model

/**
 * A Kokoro voice. Voices are data, never code: an id, a speaker id inside
 * the model, and a display name. Adding a voice is a catalogue entry.
 *
 * These are the 11 speakers of kokoro-en-v0_19 (English only, int8).
 * sid order is the model's own order (af 0 … bm_lewis 10).
 */
data class Voice(
    val id: String,
    val sid: Int,
    val displayName: String,
)

/** Default voice: Sarah (af_sarah, sid 3). */
const val DEFAULT_VOICE_ID = "af_sarah"

val KOKORO_EN_VOICES: List<Voice> = listOf(
    Voice("af", 0, "Ava"),
    Voice("af_bella", 1, "Bella"),
    Voice("af_nicole", 2, "Nicole"),
    Voice("af_sarah", 3, "Sarah"),
    Voice("af_sky", 4, "Sky"),
    Voice("am_adam", 5, "Adam"),
    Voice("am_michael", 6, "Michael"),
    Voice("bf_emma", 7, "Emma"),
    Voice("bf_isabella", 8, "Isabella"),
    Voice("bm_george", 9, "George"),
    Voice("bm_lewis", 10, "Lewis"),
)

fun voiceById(id: String): Voice =
    KOKORO_EN_VOICES.firstOrNull { it.id == id }
        ?: KOKORO_EN_VOICES.first { it.id == DEFAULT_VOICE_ID }
