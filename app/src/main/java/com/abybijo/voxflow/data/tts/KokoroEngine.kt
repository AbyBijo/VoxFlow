package com.abybijo.voxflow.data.tts

import com.abybijo.voxflow.data.models.ModelManager
import com.abybijo.voxflow.domain.audio.PcmUtils
import com.abybijo.voxflow.domain.model.KOKORO_EN_VOICES
import com.abybijo.voxflow.domain.tts.Capabilities
import com.abybijo.voxflow.domain.tts.PcmChunk
import com.abybijo.voxflow.domain.tts.SynthRequest
import com.abybijo.voxflow.domain.tts.SynthesisFailure
import com.abybijo.voxflow.domain.tts.TtsEngine
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * Kokoro-82M (int8) via sherpa-onnx (architecture §7, ADR-4).
 *
 * Wiring learned from the sherpa-onnx Android TTS Engine sample and
 * HayaiTTS (study only — this code is VoxFlow's own):
 * - `OfflineTts(assetManager = null, config)` when the model lives in
 *   the app-private files dir, not assets.
 * - Kokoro config fields: model / voices / tokens / dataDir / lexicon /
 *   lang / dictDir, plus numThreads and provider on the model config.
 * - The speaker id MUST be clamped to `numSpeakers()` before generate:
 *   an out-of-range sid is a native SIGSEGV, not a Java exception.
 * - Catch Throwable around generate: the JNI layer can throw
 *   RuntimeException for bad payloads, and callers must see a single
 *   [SynthesisFailure] type, never a process teardown.
 *
 * One loaded session, loaded lazily on first use (never at app start),
 * kept warm while listening, released on memory pressure and reloadable.
 */
class KokoroEngine(
    private val modelDir: File,
    private val numThreads: Int = 2,
) : TtsEngine {

    override val id: String = "kokoro"

    override val capabilities: Capabilities = Capabilities(
        offline = true,
        needsNetwork = false,
        streaming = false,
        languages = listOf("en"),
        maxCharsPerRequest = 400,
    )

    private val lock = Any()
    private var tts: OfflineTts? = null

    override suspend fun voices() = KOKORO_EN_VOICES

    override suspend fun synthesize(request: SynthRequest): Result<PcmChunk> {
        return runCatching {
            val engine = load()
            // Clamp sid: out-of-range is a native crash, not an exception.
            val nSpeakers = runCatching { engine.numSpeakers() }.getOrDefault(0)
            val safeSid = if (nSpeakers > 0) {
                request.voiceSid.coerceIn(0, nSpeakers - 1)
            } else {
                request.voiceSid
            }
            val audio = try {
                engine.generate(request.text, safeSid, request.speedHint)
            } catch (t: Throwable) {
                throw SynthesisFailure(
                    "Synthesis failed (voice ${request.voiceId}): " +
                        (t.message ?: t::class.simpleName),
                    t,
                )
            }
            val rate = audio.sampleRate
            if (rate != PcmUtils.SAMPLE_RATE) {
                throw SynthesisFailure(
                    "Unexpected sample rate $rate Hz (expected ${PcmUtils.SAMPLE_RATE}).",
                )
            }
            PcmChunk(
                samples = PcmUtils.floatToPcm16(audio.samples),
                sampleRate = rate,
            )
        }
    }

    override fun release() {
        synchronized(lock) {
            runCatching { tts?.release() }
            tts = null
        }
    }

    private fun load(): OfflineTts {
        synchronized(lock) {
            tts?.let { return it }
            val modelPath = File(modelDir, "model.int8.onnx")
                .takeIf { it.isFile }
                ?.absolutePath
                ?: File(modelDir, "model.onnx").absolutePath
            val voicesPath = File(modelDir, "voices.bin").absolutePath
            val tokensPath = File(modelDir, "tokens.txt").absolutePath
            val dataDir = File(modelDir, "espeak-ng-data")
            val dataDirPath = if (dataDir.isDirectory) dataDir.absolutePath else ""
            val lexicon = File(modelDir, "lexicon-us-en.txt")
            val lexiconPath = if (lexicon.isFile) lexicon.absolutePath else ""
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = modelPath,
                        voices = voicesPath,
                        tokens = tokensPath,
                        dataDir = dataDirPath,
                        lexicon = lexiconPath,
                        lang = "", // English-only pack
                        dictDir = "",
                        lengthScale = 1.0f,
                    ),
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                ),
                maxNumSentences = 2,
            )
            val created = OfflineTts(assetManager = null, config = config)
            tts = created
            return created
        }
    }
}
