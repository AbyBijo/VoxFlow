package com.abybijo.voxflow.data.models

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Model Manager (architecture §8, user requirement 2026-11-14).
 *
 * Storage: app-private files dir — `filesDir/models/<packId>/<version>/`.
 * That location SURVIVES app updates (it is not cache, not cleared on
 * update). App updates never force a re-download.
 *
 * Flow, always in this order: download → verify (SHA-256) → install
 * atomically (staging dir, then rename) → record manifest. A failed
 * verify deletes the files and says why — a corrupt model is never
 * half-installed.
 *
 * Launch check (cheap, no 90 MB re-hash): manifest version matches the
 * catalogue, all required files exist with recorded sizes, and the two
 * small files (voices.bin, tokens.txt) still hash to their recorded
 * values. The big model file is size-checked; its bytes were SHA-256
 * verified at install time from the archive.
 *
 * No storage permission is needed (app-private dir, no scoped-storage
 * issue on Android 13+). No resume in Slice 1: a download cancelled
 * or killed mid-way restarts from zero (WorkManager resumable
 * downloads are Slice 3).
 */
class ModelManager(
    appContext: Context,
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // filesDir always exists on Android; modelsDir does not until it is
    // created. usableSpace on a non-existent path returns 0, so the
    // free-space check must run against filesDir (and only after
    // modelsDir.mkdirs()), never against a modelsDir that may not exist
    // yet — otherwise every first install fails with "not enough storage"
    // even on a phone with gigabytes free.
    private val filesDir = appContext.filesDir
    private val modelsDir = File(filesDir, "models")
    private val stagingDir = File(appContext.cacheDir, "model-staging")
    private val downloadDir = File(appContext.cacheDir, "model-download")

    sealed interface State {
        data object Checking : State
        data class Downloading(val progress: Float) : State
        data object Verifying : State
        data class Ready(val dir: File) : State
        data class Error(val message: String) : State
    }

    /** Installed location for a pack version. */
    fun installedDir(pack: ModelPack): File =
        File(modelsDir, "${pack.id}/${pack.version}")

    private fun manifestFile(pack: ModelPack): File =
        File(installedDir(pack), "manifest.json")

    /**
     * Ensure the model is installed and verified. Reports progress through
     * [onState]; returns the final state. Safe to call on every launch.
     */
    suspend fun ensureModel(
        pack: ModelPack,
        onState: (State) -> Unit,
    ): State = withContext(ioDispatcher) {
        onState(State.Checking)
        val installed = readInstalledManifest(pack)
        if (installed != null) return@withContext State.Ready(installedDir(pack)).also {
            onState(it)
        }
        try {
            // Create the dirs first: usableSpace on a path that does not
            // exist yet returns 0, which used to block every first
            // install. Check free space on filesDir, which always exists.
            // Free-space headroom: size x 1.5 (archive + extracted).
            modelsDir.mkdirs()
            downloadDir.mkdirs()
            stagingDir.mkdirs()
            val free = filesDir.usableSpace
            if (free < requiredBytes(pack.sizeBytes)) {
                return@withContext State.Error(
                    storageErrorMessage(free, pack.sizeBytes)
                ).also { onState(it) }
            }
            val archive = File(downloadDir, "${pack.id}.tar.bz2")
            download(pack, archive) { p -> onState(State.Downloading(p)) }
            onState(State.Verifying)
            if (sha256(archive) != pack.sha256) {
                archive.delete()
                return@withContext State.Error(
                    "Voice download failed verification. Please retry."
                ).also { onState(it) }
            }
            val staged = File(stagingDir, pack.id)
            if (staged.exists()) staged.deleteRecursively()
            staged.mkdirs()
            extract(archive, staged)
            archive.delete()
            verifyInstalledFiles(pack, staged)
                ?: return@withContext State.Error(
                    "Voice files are incomplete. Please retry."
                ).also { onState(it) }
            val manifest = ModelManifest(
                packId = pack.id,
                version = pack.version,
                archiveSha256 = pack.sha256,
                modelSizeBytes = modelFile(pack, staged).length(),
                voicesSha256 = sha256(File(staged, "voices.bin")),
                tokensSha256 = sha256(File(staged, "tokens.txt")),
                installedAtEpochMs = System.currentTimeMillis(),
            )
            // Atomic install: staging -> final, then record the manifest.
            val dest = installedDir(pack)
            if (dest.exists()) dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            if (!staged.renameTo(dest)) {
                return@withContext State.Error(
                    "Could not install the voice. Please retry."
                ).also { onState(it) }
            }
            writeManifestAtomically(manifestFile(pack), manifest)
            State.Ready(dest).also { onState(it) }
        } catch (e: CancellationException) {
            cleanupPartial()
            throw e
        } catch (e: Exception) {
            cleanupPartial()
            State.Error(e.message ?: "Voice setup failed. Please retry.")
                .also { onState(it) }
        }
    }

    /** Null when not installed, wrong version, or failing the launch check. */
    private fun readInstalledManifest(pack: ModelPack): ModelManifest? {
        val dir = installedDir(pack)
        val manifestFile = manifestFile(pack)
        if (!manifestFile.isFile) return null
        val manifest = ModelManifest.fromJson(manifestFile.readText())
            ?: return null
        if (manifest.packId != pack.id || manifest.version != pack.version) return null
        if (manifest.archiveSha256 != pack.sha256) return null
        for (name in pack.requiredFiles) {
            val f = File(dir, name)
            if (!f.exists()) return null
        }
        if (modelFile(pack, dir).length() != manifest.modelSizeBytes) return null
        if (sha256(File(dir, "voices.bin")) != manifest.voicesSha256) return null
        if (sha256(File(dir, "tokens.txt")) != manifest.tokensSha256) return null
        return manifest
    }

    /** The model .onnx inside a directory (int8 build first). */
    fun modelFile(pack: ModelPack, dir: File): File {
        val int8 = File(dir, "model.int8.onnx")
        return if (int8.isFile) int8 else File(dir, "model.onnx")
    }

    /** Verify required files exist after extraction; null if incomplete. */
    private fun verifyInstalledFiles(pack: ModelPack, dir: File): Unit? {
        for (name in pack.requiredFiles) {
            if (!File(dir, name).exists()) return null
        }
        if (!modelFile(pack, dir).isFile) return null
        return Unit
    }

    private fun download(
        pack: ModelPack,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val request = Request.Builder().url(pack.downloadUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Voice download failed (HTTP ${response.code})."
                )
            }
            val body = response.body ?: throw IllegalStateException("Empty download.")
            val total = body.contentLength().takeIf { it > 0 } ?: pack.sizeBytes
            var written = 0L
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw IllegalStateException("Could not save download.")
        }
    }

    /** Extract tar.bz2, stripping the single top-level folder, zip-slip safe. */
    private fun extract(archive: File, dest: File) {
        FileInputStream(archive).use { fis ->
            BZip2CompressorInputStream(fis).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val name = entry.name
                            .substringAfter('/', missingDelimiterValue = entry.name)
                        if (name.isNotEmpty()) {
                            val target = File(dest, name)
                            // Zip-slip guard: stay inside dest.
                            if (!target.canonicalPath.startsWith(dest.canonicalPath)) {
                                throw IllegalStateException("Unsafe entry in voice archive.")
                            }
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out ->
                                    tar.copyTo(out)
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    private fun writeManifestAtomically(file: File, manifest: ModelManifest) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(manifest.toJson())
        if (!tmp.renameTo(file)) throw IllegalStateException("Could not record install.")
    }

    private fun cleanupPartial() {
        stagingDir.deleteRecursively()
    }

    companion object {
        /** Free space an install needs: archive + extracted files. */
        fun requiredBytes(sizeBytes: Long): Long = sizeBytes * 3 / 2

        /** Storage error that states the real numbers, not just the model size. */
        fun storageErrorMessage(freeBytes: Long, sizeBytes: Long): String =
            "Not enough storage: ${freeBytes / 1_048_576} MB free, " +
                "${requiredBytes(sizeBytes) / 1_048_576} MB needed for the voice."

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
