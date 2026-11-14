# VoxFlow

Paste text, listen to it read aloud — offline, on the device.

## Slice 1

Paste → chunk → Kokoro offline TTS → gapless PCM playback, one screen.
Library, MP3 export, speed control and model downloads are later slices.

## Build

```sh
# One-time: fetch the sherpa-onnx AAR (see app/libs/REGENERATE.md)
curl -L -o app/libs/sherpa-onnx-static-link-onnxruntime-1.13.3.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-static-link-onnxruntime-1.13.3.aar

./gradlew test                 # JVM unit tests
./gradlew assembleDebug        # debug APK (fixed debug key, see below)
./gradlew assembleRelease      # needs the release keystore properties
```

CI (GitHub Actions, `.github/workflows/android.yml`) builds debug + signed
release artifacts on every push. Release builds are artifacts only — no
GitHub Release on the main page.

## Signing

- Debug: one FIXED keystore for every debug build,
  `keystore/debug.keystore.b64` (alias `voxflow-debug`, password
  `voxflow-debug` — debug-only credentials). It is decoded at build time
  into the build directory. Debug and release keys are DISTINCT.
- Release: one FIXED keystore, alias `voxflow-release`, kept outside the
  repo (`workspace/your_files/voxflow-release-keystore/`) and supplied to
  CI as four secrets (`ANDROID_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`). Never committed, never in chat.
- No R8 / no shrinking in any build type (user decision).

## Model (downloaded once)

First run downloads the Kokoro English model (~103 MB, Apache 2.0) into
the app-private files dir (`filesDir/models/kokoro-en-v0_19/1.0/`), which
survives app updates. A versioned manifest (id + version + SHA-256) is
recorded at install; on launch the manager checks version, files and
hashes and reuses the install as-is. Only a missing / corrupt / newer
version triggers a download. See `app/src/main/java/com/abybijo/voxflow/data/models/ModelManager.kt`.

## Docs

- Architecture: `workspace/goals/voxflow-seamless-tts-app/files/voxflow-app-architecture/`
- Risks and mitigations: `docs/risks.md`
