# VoxFlow — risks and mitigations (Slice 1)

Scope note (2026-11-14, user correction): this note covers the
**UI / settings / navigation problems that directly affect this app** —
the class of OpenFlow problems the user meant (e.g. a splash screen
Activity showing even after launch, before Home). Backend / engine
risks that do not directly affect VoxFlow's UI are listed only briefly
at the end, deprioritised, not dropped entirely.

For each risk: what it is, what was done about it in code, how it was
verified, and what is still only verifiable on a real phone.

## 1. Splash / launch flow

**Risk.** A splash screen (or a second Activity) that lingers after
launch, re-appears on resume / rotation / process restore, or a cold
start that flashes white before Home. OpenFlow showed its splash
Activity even after launch, before Home.

**Mitigation (in code).**
- There is no splash. No `SplashScreen` API call, no splash Activity,
  no splash theme — `MainActivity` is the only Activity and the only
  entry point (`AndroidManifest.xml`).
- The window background is the Home background (`res/values/colors.xml`
  + `values-night/`, `Theme.VoxFlow`), so cold start shows no flash.
- `launchMode="singleTop"` (manifest): repeated launcher taps never
  stack a second Home.
- The Kokoro engine loads lazily on first Listen, never at app start,
  so launch is not held hostage by model loading (cold-start budget,
  architecture §13).

**Verified how.** Code inspection + manifest check in CI (the workflow
greps the manifest for `SplashScreen` and fails if present; it also
asserts exactly one `<activity>`). Unit tests do not cover this —
it is a manifest property, not logic.

**Still only verifiable on a real phone.** Cold-start timing and the
absence of any flash on the user's device; rotation / resume / process
restore never showing a splash (no splash exists to show, but the
visual check is still the phone).

## 2. Navigation

**Risk.** A stuck screen, a blank screen after returning from
background, a double screen, or a back-stack that traps the user.

**Mitigation (in code).**
- Slice 1 has ONE screen and no navigation graph at all — no
  Navigation Compose dependency, no routes, no back-stack to corrupt.
  Back exits the app (platform default for a single Activity).
- The Home screen is a single `Column`; there is no fragment, no
  second destination, nothing to return to incorrectly.

**Verified how.** No navigation dependency in `gradle/libs.versions.toml`;
no `NavController` anywhere in the source. CI build confirms the app
has exactly one Activity.

**Still only verifiable on a real phone.** Back-button behaviour after
backgrounding / rotation on the user's device.

## 3. Settings

**Risk.** Settings that do not apply, dead toggles, a settings screen
that breaks navigation, theme / state lost on rotation or process death.

**Mitigation (in code).**
- There is no separate settings screen in Slice 1, so there is no
  settings navigation to break.
- Slice 1 has exactly two settings, both in DataStore
  (`SettingsStore`): the voice id and the last pasted text. Both
  apply live — the voice picker writes the id and the next Listen
  uses it; the text is saved (debounced 500 ms) on every edit.
- No dead toggles exist: there is no setting in the app that does
  nothing.
- Rotation: state lives in the ViewModel (survives config change).
  Process death: text + voice are restored from DataStore in
  `HomeViewModel.init`. The voice picker and text field are disabled
  while a session is active, so a setting cannot be changed into an
  inconsistent mid-playback state.
- Theme follows the system (light/dark) with no setting to drift.

**Verified how.** DataStore read/write path is exercised in the app;
the "settings apply live" claim is code-reviewed, not unit-tested
(DataStore needs Android). Rotation survival is ViewModel + DataStore
by construction.

**Still only verifiable on a real phone.** Kill the app mid-text and
reopen it: the text and voice must be exactly as left. Rotate while
playing: playback and picker state must be unchanged.

## 4. First run

**Risk.** An onboarding / first-run flow that loops, re-shows after
completion, or blocks the app when its one-time work fails.

**Mitigation (in code).**
- There is NO onboarding flow in Slice 1. First run has exactly one
  one-time job — the Kokoro model download — and it is a Home-screen
  state, not a screen: `Checking → Downloading (determinate %) →
  Verifying → Ready`, or `Error (message + Retry)`.
- The state machine is terminal in both directions: success ends in
  `Ready`, failure ends in `Error` with a Retry button. There is no
  auto-retry loop and no path back to `Checking` without the user
  tapping Retry.
- The model check is idempotent: if the manifest version matches and
  the files verify, `ensureModel` returns `Ready` immediately — an
  app update never re-downloads, and a second launch never re-shows
  the download.

**Verified how.** `ModelManager` launch-check logic is covered by the
manifest round-trip test (`ModelManifestTest`); the state machine has
no loop by construction (reviewed). The download itself is not
unit-tested (needs network + 103 MB).

**Still only verifiable on a real phone.** First launch on a real
network: the download progresses, verifies, installs, and the second
launch goes straight to Ready with no download. Airplane-mode first
launch must show the error with Retry, not a loop.

## 5. UI states (loading / empty / error)

**Risk.** A frozen pill / spinner that never resolves — OpenFlow's
3-dots bug, where tapping to finish a dictation left the bubble as a
pill with no loading state at all. Any state the UI cannot show is a
state the user cannot escape.

**Mitigation (in code).**
- Every async operation has a determinate state with text and an exit:
  model `Downloading` shows a real percentage; `Verifying` shows text;
  playback `PREPARING` shows "N of M chunks ready" with a determinate
  bar; `BLOCKED` names the chunk and offers Retry; `FAILED`/`Error`
  show the message with Retry / Try again.
- No bare spinners: the two indeterminate indicators in the app
  (`Checking`, `Verifying`) are transient sub-second checks that
  always carry text and always resolve to Ready or Error.
- Playback is a tested state machine (`PlaybackStateMachine`): illegal
  transitions throw in tests instead of shipping as a stuck state.
  Every mode has a rendered layout in `HomeScreen` — including
  `BLOCKED` and `FAILED`, which are real screens, not toasts.
- Chunk failure retries twice with backoff, then BLOCKED — never a
  silent skip, never an infinite retry.

**Verified how.** `PlaybackStateMachineTest` covers the happy path,
the blocked-retry path, fatal, stop-from-any-mode, and four illegal
transitions (each must throw). `GaplessWriterTest` and `PcmUtilsTest`
cover the join math. The UI renders every mode (reviewed in
`HomeScreen.kt`).

**Still only verifiable on a real phone.** A real synthesis failure
on device (e.g. corrupt model) must land on BLOCKED with Retry, not a
frozen player; a real download failure must land on the error card.

## 6. Text and place survival (cross-cutting)

**Risk.** Losing the pasted text on rotation / process death.

**Mitigation.** Text lives in the ViewModel (rotation) and in DataStore
(process death, debounced save). Voice id likewise. Playback position
does NOT survive process death in Slice 1 — resume is Slice 2, and
this is stated, not hidden.

**Verified how.** By construction (ViewModel + DataStore); on-device
check pending (kill the app, reopen, text must be there).

---

## Deprioritised: engine / backend risks (not the focus of this revision)

These were in the earlier, broader pre-mortem. They are not dropped —
the cheap mitigations are implemented — but they are not what this
revision is about, and none of them blocks Slice 1's UI.

| Risk | Mitigation implemented |
|---|---|
| Paste/insert into other apps failing or slow (OpenFlow) | Not applicable: VoxFlow Slice 1 plays in-app only. No cross-app insertion exists by design. |
| GitHub Actions CI failures / 403 on workflow-file pushes | Code pushed WITHOUT any workflow file first; the workflow is added in a separate step (browser route). |
| SDK / Gradle / version-catalog drift, minSdk/targetSdk mismatches, Compose BOM conflicts | Pinned catalog (`gradle/libs.versions.toml`), proven OpenFlow stack (AGP 8.10.1 / Kotlin 2.1.20 / Gradle 8.11.1 / compileSdk+targetSdk 36 / minSdk 26). |
| Keystore / signing mismatches breaking updates | Fixed debug key (`keystore/debug.keystore.b64`, alias voxflow-debug) and fixed release key (outside the repo, CI secrets). Distinct keys, never per-run generated. CI prints both SHA-256 fingerprints and fails if they are equal. |
| sherpa-onnx / ONNX Runtime native lib size, ABI splits | Vendored 1.13.3 static-link AAR (38,398,784 bytes); universal APK in Slice 1; ABI splits are a later packaging decision. |
| Model download failures, checksum / size verification | Archive SHA-256 verified before extraction; manifest records version + hashes; launch re-checks version, files, sizes and small-file hashes. |
| Storage permission / scoped storage (Android 13+) | App-private files dir only; no storage permission requested. |
| AudioTrack underruns / gaps at chunk boundaries | One AudioTrack, 24 kHz mono PCM16, 4x minimum buffer, 3-chunk lookahead, trimmed chunks + equal-power crossfade + boundary silence; underrun count is exposed for diagnostics. |
| Sample-rate mismatches | Single 24 kHz pipeline; the engine fails loudly if sherpa returns any other rate. |
| Foreground service / audio focus / screen-off / Doze | Audio focus (gain/loss/duck) + becoming-noisy pause are implemented in Slice 1. A foreground PlaybackService for screen-off / background listening is Slice 2 — playback is in-process in Slice 1 and stops if the app is killed (stated, not hidden). |
| Long-text chunking edge cases | Unit-tested: empty text, only punctuation, very long paste (10k chars), mixed languages, numbers/URLs, single word over the cap, abbreviations, decimals, ellipses. |
| Synthesis worker falling behind the playhead | 3-ahead lookahead with throttle; position is tracked in samples; blocked chunks pause the writer instead of skipping. |
| Seek / pause / resume state bugs | Seek is Slice 4 (not in Slice 1). Pause/resume is a tested state machine; resume continues exactly where it stopped. |
| Dependency supply-chain | Pinned versions only; no unvetted SDKs. sherpa-onnx AAR + Kokoro model bytes verified at build time (AAR 38,398,784 bytes; model tarball 103,248,205 bytes, SHA-256 c9f0dd39…08bd, Apache 2.0). |
| Number / currency / date expansion in the normaliser | Not in Slice 1 (Kokoro's Misaki G2P reads them); recorded as a refinement, not a silent gap. |
