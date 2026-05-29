# Phase 2b Implementation Plan — Spec Sheet UI + Android Bit-Perfect Audio

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Each sub-flight gets its own bite-sized plan written at flight-start** — this top-level doc is a phase-shape ADR + execution sequence + risk register. Only the first executable flight (B0 capability-probe spike) has full TDD-step granularity here; subsequent flights get their detailed plans when their prerequisites land.

**Goal:** Ship Kiln Phase 2b = Spec Sheet UI (per-track audio format details + library aggregate stats) + Android `MIXER_BEHAVIOR_BIT_PERFECT` audio path with software-decode + null-test acceptance gate. Windows WASAPI + Android Oboe-low-latency deferred to Phase 2c.

**Architecture:** Three sub-flights gated on a capability probe. Phase 2b-prereq adds Voyager Navigator infrastructure inside `NowPlayingTab` so Spec Sheet is routable (10-20hr). Stream A builds `SpecSheetScreen` + format-fact backfill scanner pass (40-70hr). Stream B builds `BitPerfectAudioTrackPlayerImpl` with JNA-libFLAC software decode + DAC null-test acceptance + AudioFocus/MediaSession/AudioDeviceCallback reimplementation + loudness-matched toggle (155-245hr). All work lives behind the existing `PlatformPlayer` Engine-Swap-Shaped Boundary (vetting Item 13) which stays unchanged.

**Tech Stack:** Kotlin 2.x KMP, Compose Multiplatform, Voyager navigation, kotlin-inject DI, SQLDelight 2.3.2, kermit logging, AndroidX Media (MediaCodec) + AudioTrack + AudioManager (Android), JNA 5.17.0 + vendored Xiph libFLAC 1.5.0 (BSD-3) ported from desktop, kotest-property for invariants, Robolectric for Android-host tests, jetpack-compose-test for UI.

---

## 1. Pre-flight state (verified 2026-05-23)

- Local main at `ea51ba2` (PR #19 merged) — synced
- Canonical 8-target build green (23s) — `:app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:build :audio:dsp:desktopTest :audio:playback:desktopTest`
- `/multi:doctor` 24/27 (cloud providers all green; ollama smoke fail non-blocking, non-Phase-2b-relevant)
- ADR locked: `architecture/kiln-phase-2b-sequencing` engram + research doc at [`docs/decisions/2026-05-23-phase-2b-aaudio-wasapi-research.md`](../../decisions/2026-05-23-phase-2b-aaudio-wasapi-research.md)
- Falsify integration: 23 failure modes ranked, 11 H×H=9, integration decisions captured in engram `architecture/kiln-phase-2b-falsify-integration`
- 27,766 desktop tracks scanned; Android library not yet primed (capability-probe spike is first Stream B task and will probe with at least one device-attached scan run)

---

## 2. Phase shape

| Phase | Scope | Effort | Status |
|---|---|---|---|
| **Phase 2b-prereq** | Navigator infrastructure inside `NowPlayingTab` so `SpecSheetScreen` is routable from a tap-title affordance | **10-20hr** | Pending; first to ship |
| **Phase 2b-A** (Spec Sheet) | `SpecSheetScreen` + library aggregate query + Android format-fact backfill scanner pass | **40-70hr** | Pending; parallel-capable with Phase 2b-B after prereq + Stream B's B0/B1/B2 gates clear |
| **Phase 2b-B** (Bit-perfect Android) | `BitPerfectAudioTrackPlayerImpl` + JNA-libFLAC port to Android + null-test acceptance + AudioFocus/MediaSession/AudioDeviceCallback reimplementation + loudness-matched toggle + capability-probe spike | **155-245hr** | Pending; gated on B0 probe-result commit |
| **Phase 2b total** | | **205-335hr** | |
| **Phase 2c (deferred)** | Windows WASAPI engine (C++ DLL + JNA/COM, exclusive + IAudioClient3) | 80-120hr | Deferred; trigger condition documented §10 |
| **Phase 2c (deferred)** | Android Oboe low-latency path (use case doesn't justify; FLAC-on-USB-DAC is bit-perfect-shaped not latency-shaped) | 80-120hr | Deferred; trigger condition documented §10 |

**Scope math vs original (a′) lock:** Original (a′) was 80-140hr. Falsify-integrated reality is 205-335hr. The "smaller scope" win from Option (a′) partially evaporates because the bit-perfect claim is being made verifiable (null-test gate + JNA-libFLAC software decode) rather than locked as marketing copy. Quality-first per Clay's 2026-05-23 directive.

**Anti-roadmap §11 unchanged.** No Tidal, no Spatial Audio, no AI/LLM, no iOS/Linux/macOS, no Auto, no Tag editing, no Lyrics, no Last.fm scrobbling, no BT codec readouts, no Podcasts, no MIDI EQ controller, no cross-device handoff. All explicitly cut.

---

## 3. Pre-Stream-B hard gates (must pass before any Stream B production code lands)

1. **G1 — Capability probe on Pixel 10 Pro XL + Clay's exact USB-C-to-AUX dongle.** Run a standalone Android probe app or `:app-android` debug intent that calls `AudioManager.getSupportedMixerAttributes(usbDevice)` for the dongle. **If empty → drop Stream B from Phase 2b → ship Phase 2b-prereq + Phase 2b-A only → re-evaluate Stream B at Phase 2c kickoff.** This is the single unlock condition from the original (a′) lock.

2. **G2 — Gemini cross-check of BIT_PERFECT vendor-support landscape.** Codex-only research caveat from earlier engram entry `architecture/kiln-phase-2b-sequencing` must be triangulated. Re-fan `/multi:research` with gemini (now quota-recovered after 2026-05-23 quota burn) on: (a) confirmed Pixel-line BIT_PERFECT support matrix per Google CDD, (b) any post-2026-01 AOSP changes, (c) USB Audio Class 2 vs 3 dongle eligibility. Save findings to research doc as addendum.

3. **G3 — Plan approval by Clay.** This document is read + locked by Clay before any code in `:audio:playback/androidMain/.../BitPerfectAudioTrackPlayerImpl.kt` or `:ui:components/commonMain/.../specsheet/` lands.

4. **G4 — Library-vetting log addendum committed.** New library/path decisions land as an append-only addendum to [`docs/decisions/2026-05-18-library-vetting.md`](../../decisions/2026-05-18-library-vetting.md): Item 13 addendum (post-Phase-2a soft-lock-revisit resolution = Option a′ + falsify integration); new Item 14 (JNA-libFLAC port to Android, depending on G1).

---

## 4. Risk register (top failure modes from `/multi:falsify` 2026-05-23, 3/3 valid panel, integrated)

Full failure-mode table in engram `architecture/kiln-phase-2b-falsify-integration`. The 11 H×H=9 risks with concrete mitigations baked into this plan:

| # | Risk | Mitigation (plan-located) |
|---|---|---|
| F1 | "Bit-perfect" name unfalsifiable (MediaCodec FLAC decode is vendor-codec, may resample/dither/quantize) | **Sub-flight B1 ports JNA-libFLAC to Android** (already proven on desktop, `audio/playback/src/desktopMain/.../JvmFlacDecoderImpl.kt`); **Sub-flight B2 builds null-test acceptance gate** (DAC loopback vs JNA-libFLAC reference PCM) |
| F2 | RG-bypass loudness jump = hearing-damage vector | **Sub-flight B5 implements bit-perfect as session-level setting with pause→fade→mode-flip→resume transition**; no RG arithmetic on PCM in bit-perfect mode (truthful to contract); system-volume pre-set at toggle approximates pre-toggle loudness |
| F3 | Pixel 10 Pro XL vendor support empirically unknown | **G1 capability-probe spike (B0) gates all Stream B code** |
| F4 | `setPreferredMixerAttributes` is per-UID **process state** (leaks across crashes) | **Sub-flight B4 implements clear-on-every-release + crash recovery hygiene**; declare `MODIFY_AUDIO_SETTINGS` permission in `app-android/.../AndroidManifest.xml` |
| F5 | Capability probe necessary, **not sufficient** (HAL/ALSA can silently resample) | **Sub-flight B2 null-test rig is the sufficiency check** — accepts the inherent API gap by externally verifying bit-identity via DAC loopback |
| F6 | Per-track sample-rate negotiation kills gapless (library has 44.1/48/88.2/96/176.4/192) | **Sub-flight B3 handles per-track stream rebuild explicitly** (AudioTrack flush+release+rebuild on rate-change); **per-track engagement state surfaced in UI** so user sees which tracks actually engage bit-perfect |
| F7 | AudioTrack-direct loses Media3 free integrations | **Sub-flight B4 reimplements AudioFocus, MediaSession, AudioDeviceCallback, underrun handling explicitly** (+30-50hr already baked into Stream B 155-245hr range) |
| F8 | USB hotplug + BT crash class | **Sub-flight B4 includes AudioDeviceCallback + DeadObjectException recovery + UI route-status indicator** |
| F9 | GC/JVM pause causes underruns | **Sub-flight B3 pre-allocates all buffers + tunes to mask GC variance**; **escalation to C++/JNI write loop is acceptable** (quality-first overrides "no C++ this phase" simplicity) |
| F10 | Spec Sheet ships FALSE data (Android scanner stores placeholder format facts) | **Stream A includes a format-fact backfill scanner pass via `MediaMetadataRetriever`** BEFORE `SpecSheetScreen` ships |
| F11 | Probe-gate has no enforcement | **Hard rule: `BitPerfectAudioTrackPlayerImpl.kt` file creation BLOCKED until `docs/decisions/2026-05-XX-phase-2b-bitperfect-probe-result.md` exists in main**; enforce via CI check on filename pattern OR pre-commit hook |

Mid-tier integrated mitigations:

| # | Risk | Mitigation |
|---|---|---|
| F13 | `PlatformPlayer` boundary already broken by A+B coexistence (Spec Sheet needs bit-perfect engagement state) | **Side-channel via separate `BitPerfectEngagementState` flow exposed by graph**, NOT new methods on `PlatformPlayer` — preserves vetting Item 13 |
| F14 | bug_003-shape race may recur in `BitPerfectAudioTrackPlayerImpl` | **Copy the per-toggle `@Volatile currentPlayable` re-read pattern** from `audio/playback/src/androidMain/.../Media3ExoPlayerImpl.kt:200-220` + `audio/playback/src/desktopMain/.../JavaSoundPlayerImpl.kt:420-442`; **add cross-player invariant test suite** that runs the rapid-skip scenario against every PlatformPlayer impl |
| F17 | Stream A presumes Now Playing exists at fidelity | **Phase 2b-prereq added** — Navigator infrastructure inside `NowPlayingTab`; tap-title affordance to push to SpecSheet |
| F21 | WASAPI/Oboe defer has no anchor | **Phase 2c trigger condition documented in §10** of this plan |

---

## 5. File-touch surface

Files Phase 2b will create or modify, by sub-flight:

### Phase 2b-prereq

| Action | Path | Lines | Why |
|---|---|---|---|
| Modify | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingTab.kt` | currently 1-65 | Embed a Voyager `Navigator` so child screens (SpecSheet) can be pushed |
| Modify | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingContent.kt` | currently 1-138; titles at 76-79 | Make title `Clickable` → push SpecSheet to Navigator (callback hoisted) |

### Phase 2b-A (Spec Sheet UI + format-fact backfill)

| Action | Path | Why |
|---|---|---|
| Create | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetScreen.kt` | Voyager `Screen` per spec |
| Create | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetState.kt` | Hoisted state shape |
| Create | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/specsheet/LibraryAggregateState.kt` | Aggregate stats (total tracks, codec counts, RG coverage %, etc.) |
| Modify | `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/source/LocalLibrarySource.kt` | Add `aggregateStats(): Flow<LibraryAggregate>` |
| Modify | `data/library/src/commonMain/sqldelight/com/clayworks/kiln/library/db/track.sq` | Add aggregate queries (group-by-codec, sum-bytes, RG-coverage filter) |
| Create | `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidFormatFactBackfill.kt` | F10 mitigation — `MediaMetadataRetriever` pass to backfill `sample_rate_hz`, `bit_depth`, `channels`, `has_embedded_art` for existing rows where current placeholders are stored |
| Modify | `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt` | Trigger format-fact backfill at scan-end (idempotent; gated on `metadata_backfilled_at IS NULL`) |
| Modify | `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/internal/SafTagReader.kt` | Same backfill hook on SAF scan path |
| Modify | `data/library/src/commonMain/sqldelight/.../migrations/3.sqm` (new) | Add `track.metadata_backfilled_at_ms INTEGER` column (v3→v4) |
| Modify | `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` | Wire `AndroidFormatFactBackfill` |
| Modify | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingTab.kt` | Wire Navigator push to `SpecSheetScreen` |

### Phase 2b-B (Bit-perfect Android)

| Action | Path | Why |
|---|---|---|
| Create | `docs/decisions/2026-05-XX-phase-2b-bitperfect-probe-result.md` | F11 — hard gate: must exist before any `BitPerfectAudioTrackPlayerImpl.kt` lands |
| Create | `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/AndroidFlacDecoderImpl.kt` | Port of `JvmFlacDecoderImpl.kt` to Android-NDK-linked libFLAC (Android NDK ships libFLAC; alternatively bundle the same vendored DLL→.so) |
| Create | `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/BitPerfectAudioTrackPlayerImpl.kt` | Stream B core — implements `PlatformPlayer` |
| Create | `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/BitPerfectCapabilityProbe.kt` | B0 spike artifact |
| Create | `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/BitPerfectEngagementState.kt` | F13 mitigation — side-channel, NOT on `PlatformPlayer` |
| Create | `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/BitPerfectAudioFocusManager.kt` | F7 — reimplement Media3-free AudioFocus |
| Create | `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/BitPerfectMediaSessionManager.kt` | F7 — reimplement Media3-free MediaSession |
| Create | `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/BitPerfectDeviceRouter.kt` | F4+F8 — `setPreferredMixerAttributes` lifecycle + `AudioDeviceCallback` + DeadObjectException recovery |
| Create | `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/LoudnessMatchedToggle.kt` | F2 — pause→fade→mode-flip→resume + system-volume pre-set |
| Create | `audio/playback/null-test-rig/` (new tooling tree, may be a separate module or scripts) | F1+F5 — DAC loopback test harness (`hw:Loopback` ALSA device on Linux laptop OR external audio interface) producing a fixture-to-fixture byte-identity gate |
| Modify | `app-android/src/main/AndroidManifest.xml` | Declare `MODIFY_AUDIO_SETTINGS` permission (F4) |
| Modify | `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` | Wire `BitPerfectAudioTrackPlayerImpl` behind a settings feature flag; preserve Media3 path as default |
| Modify | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt` | Add "Bit-perfect output (USB DAC required)" toggle — capability-gated; tooltip explains gray-out conditions; engagement transition uses `LoudnessMatchedToggle` |
| Modify | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt` | Add `bitPerfectEnabled: Boolean` + `bitPerfectAvailable: BitPerfectAvailability` enum (`Available`/`UnsupportedDevice`/`NoUsbDac`/`Disabled`) |
| Modify | `data/library/src/commonMain/sqldelight/.../settings.sq` | Add `bit_perfect_enabled` key (boolean as INTEGER 0/1) |
| Create | `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/BitPerfectAudioTrackPlayerImplTest.kt` | Robolectric unit tests (golden-path + rapid-skip + USB-disconnect + RG-on-track-with-RG = engagement-refused) |
| Create | `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/CrossPlayerInvariantTest.kt` | F14 — rapid-skip race test that runs against EVERY `PlatformPlayer` impl |

### Existing-code interaction points (read-only references for context, NO modifications)

| Path | Why referenced |
|---|---|
| `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlatformPlayer.kt:14-55` | Boundary — `BitPerfectAudioTrackPlayerImpl` implements this verbatim; surface MUST NOT widen |
| `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt:200-220` | bug_003 fix pattern to copy for F14 |
| `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt:420-442` | bug_003 fix pattern (desktop sibling) |
| `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacDecoderImpl.kt` | Source for the Android port (B1) — JNA bindings + libFLAC stream-decoder callback model |
| `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/MediaProcessorAdapter.kt` | Media3 chain context — bit-perfect path BYPASSES this entirely (no RG, no EQ, no visualizer fan-out) |
| `data/library/src/commonMain/sqldelight/.../migrations/2.sqm` | Reference for sqm naming convention (v1→v2 named `1.sqm`) for new `3.sqm` |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/source/LocalLibrarySourceMappers.kt` | Mapping pattern for new `Track → SpecSheetEntry` mapper |

---

## 6. Sub-flight breakdown

### Phase 2b-prereq — Now Playing Navigator scaffold (10-20hr)

**Goal:** Make `SpecSheetScreen` routable from a tap-title affordance inside `NowPlayingTab` without rebuilding queue/reorder/album-art (those stay deferred).

Detailed bite-sized plan: **written at flight start**. Outline:

- Add Voyager `Navigator(NowPlayingHomeScreen()) { ... }` wrapper inside `NowPlayingTab.Content()`
- Refactor current `NowPlayingContent` body into `NowPlayingHomeScreen : Screen`
- Add `onTitleClick` callback on `NowPlayingContent` — title becomes clickable (Modifier.clickable)
- `NowPlayingHomeScreen` wires `onTitleClick` to `navigator.push(SpecSheetScreen(currentTrackId))`
- Stub `SpecSheetScreen` with "Spec sheet for $trackId" placeholder text so navigation is end-to-end testable before Stream A lands
- jetpack-compose-test: navigate from `NowPlayingTab` → `SpecSheetScreen` (placeholder); pop back

### Phase 2b-A — Spec Sheet UI + format-fact backfill (40-70hr)

**Goal:** `SpecSheetScreen` displays per-track format facts (codec, sample-rate, bit-depth, channels, bitrate, RG track + album dB/peak, has-embedded-art, file path, mtime) and library aggregate stats (total tracks, total bytes, per-codec count, RG-coverage %, has_known_mtime %). Android format facts are accurate (no placeholders) because of the backfill scanner pass.

Detailed bite-sized plan: **written at flight start**. Sub-flight outline:

- **A0** — Schema migration `3.sqm` adds `track.metadata_backfilled_at_ms INTEGER`; SQLDelight verify-migration regenerates snapshot
- **A1** — `AndroidFormatFactBackfill` — iterates rows where `metadata_backfilled_at_ms IS NULL`; opens each via `ContentResolver.openFileDescriptor` (.use-block per CLAUDE.md gotcha); `MediaMetadataRetriever.setDataSource(fd.fileDescriptor)`; extracts `METADATA_KEY_SAMPLERATE`, `METADATA_KEY_BITS_PER_RAW_SAMPLE`, `METADATA_KEY_NUM_TRACKS`, `METADATA_KEY_BITRATE`, embedded-art presence via `getEmbeddedPicture() != null`; writes back via `updateTrackMetadata` UPDATE statement; sets `metadata_backfilled_at_ms = System.currentTimeMillis()`
- **A2** — Wire `AndroidFormatFactBackfill` into `AndroidMediaStoreScanner` + `SafTagReader` scan-end hooks (idempotent via `IS NULL` filter)
- **A3** — `LocalLibrarySource.aggregateStats()` — emits `Flow<LibraryAggregate>` backed by `track.sq` aggregate queries (total tracks WHERE deleted_at_ms IS NULL, GROUP BY codec count, SUM(file_size_bytes), COUNT WHERE replay_gain_track_db IS NOT NULL, COUNT WHERE has_known_mtime = 1, etc.). Use SQLDelight `Query.asFlow().mapToOne()` per existing Track D-C pattern
- **A4** — `SpecSheetState` + `SpecSheetScreen` Compose — header shows track facts (read via `localLibrarySource.getPlayable(trackId)`); footer shows library aggregate stats; format display matches the Mastering Engineer's Apartment pattern (e.g. `FLAC — 24/96 — 2 ch — 1411 kbps`, `+0.3 dB ReplayGain`)
- **A5** — Wire `SpecSheetScreen` constructor to actually load + display real data (replaces Phase 2b-prereq's placeholder)
- **A6** — jetpack-compose-test: golden-path render + empty-library render + missing-RG-data render

### Phase 2b-B — Bit-perfect Android (155-245hr)

Six sub-flights, sequenced. Each gate blocks the next.

#### B0 — Capability probe spike (5-10hr) — GATE for all subsequent Stream B work

**Goal:** Empirically determine whether Pixel 10 Pro XL + Clay's USB-C-to-AUX dongle supports `MIXER_BEHAVIOR_BIT_PERFECT` for the audio formats present in the library (44.1/48/88.2/96/176.4/192 kHz × 16/24 bit).

Full bite-sized plan provided below in §7.

**Exit condition:** Either (a) result-doc committed showing PROBE_PASS for ≥1 format → Stream B-B1 starts, OR (b) result-doc committed showing PROBE_FAIL all formats → drop Stream B from Phase 2b → ship 2b-prereq + 2b-A only → save engram + revise plan + escalate to Clay.

#### B1 — Android JNA-libFLAC port (30-50hr)

**Goal:** Port `JvmFlacDecoderImpl.kt` (desktop) to Android. Same JNA bindings, libFLAC `.so` vendored under `audio/playback/src/androidMain/jniLibs/` for `arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86`. NDK toolchain compiles or vendors prebuilt libFLAC 1.5.0 BSD-3.

Detailed bite-sized plan: **written at flight start**. Sub-flight outline:

- Source vendored libFLAC 1.5.0 binaries for 4 Android ABIs (option A: build from xiph/flac source via NDK; option B: extract from a known-Apache-2.0-or-BSD package)
- Add `.so` files to `audio/playback/src/androidMain/jniLibs/<abi>/libFLAC.so`; verify `.gitignore` exception
- `audio/playback/src/androidMain/.../AndroidFlacDecoderImpl.kt` — mirror desktop `JvmFlacDecoderImpl` API + JNA loading sequence (`Native.load("FLAC", LibFLAC::class.java)`); name-resolution drops `lib` prefix per CLAUDE.md gotcha
- Reuse desktop's `JvmFlacDecodedStream.kt` + `JvmFlacTrackAnalyzerTest.kt` patterns; consider moving callback infrastructure to `commonMain` if compatible
- Android-host tests against same FLAC fixtures used in desktop tests (24/96, 24/192, 16/44.1 minimum)
- Performance gate: decode speed >= 5× realtime for 96 kHz on Pixel 10 Pro XL (else escalation to NDK direct C linkage)

#### B2 — Null-test acceptance rig (10-20hr)

**Goal:** Reproducible DAC loopback test that compares PCM emitted by the bit-perfect path to PCM decoded by JNA-libFLAC reference. Bit-identity gate (zero bit error tolerance over a captured sample window) is the acceptance criterion for the "bit-perfect" claim.

Detailed bite-sized plan: **written at flight start**. Sub-flight outline:

- Hardware: USB audio loopback (Clay's USB-C dongle + external mic OR a USB audio interface with hardware loopback)
- Capture path: `adb shell screenrecord` is wrong (video); use `arecord` on a Linux laptop with the dongle wired in OR write a tiny Android test-instrumentation app that captures via `AudioRecord` from the same dongle (if dongle has mic-in)
- Software: small Python or Kotlin script aligns captured PCM with reference PCM (cross-correlation on 1s pilot tone); reports bit-error count over remaining test window
- Acceptance: 0 bit errors over 30-second test window for each of {44.1/16, 48/16, 96/24, 192/24} (4 fixture files)
- Failure mode: any non-zero bit-error → falls back to "Direct mixer output" naming for that format AND user-visible warning OR drops bit-perfect for that format-class
- Document the rig setup in `audio/playback/null-test-rig/README.md` — reproducible from a clean checkout

#### B3 — `BitPerfectAudioTrackPlayerImpl` core (40-70hr)

**Goal:** Implements `PlatformPlayer` interface (verbatim signature from `PlatformPlayer.kt:14-55`). Owns `AudioTrack` lifecycle. Per-track stream rebuild on rate change. GC-aware buffer sizing. Decode source: `AndroidFlacDecoderImpl` (B1) feeds PCM directly to `AudioTrack.write`.

Detailed bite-sized plan: **written at flight start**. Sub-flight outline:

- Constructor establishes `AudioManager`, `setPreferredMixerAttributes` for the active USB-DAC route; `MODIFY_AUDIO_SETTINGS` permission asserted
- `loadQueue` resolves `Playable`s; opens initial `AndroidFlacDecoderImpl` for `items[startIndex]`
- `play` starts the producer coroutine + AudioTrack
- Producer coroutine: reads PCM from decoder, writes to `AudioTrack` via pre-allocated direct `ByteBuffer` (no per-loop allocation; F9 mitigation)
- `onMediaItemTransition`-equivalent: when track changes AND new track's sample-rate ≠ current `AudioTrack` rate → flush + release + rebuild AudioTrack at new rate (F6); UI-state flow emits `BitPerfectEngagementState.GapBetweenTracks` for the brief gap
- Apply bug_003-shape race-fix pattern (per-toggle `@Volatile currentPlayable` re-read) for any settings-based behavior
- `release` clears `setPreferredMixerAttributes` + releases `AudioTrack`

#### B4 — Integrations (AudioFocus / MediaSession / AudioDeviceCallback / underrun) (30-50hr)

**Goal:** F7 + F8 mitigations — explicitly reimplement what Media3 provided for free, since AudioTrack-direct path has no such bundling.

Detailed bite-sized plan: **written at flight start**. Sub-flight outline:

- `BitPerfectAudioFocusManager` — `AudioManager.requestAudioFocus` with `AudioFocusRequest` (API 26+); listener pauses on `AUDIOFOCUS_LOSS_*`; resumes on `AUDIOFOCUS_GAIN` (configurable); ducking disabled in bit-perfect mode (would defeat claim) — instead, pause-and-resume on transient losses
- `BitPerfectMediaSessionManager` — `MediaSessionCompat` (or modern equivalent at current AndroidX SDK 36); state mirrored from `PlatformPlayer.state`; handles hardware media keys; lock-screen art via `MediaMetadataCompat`
- `BitPerfectDeviceRouter` — `AudioDeviceCallback.onAudioDevicesAdded` / `onAudioDevicesRemoved`; on USB-DAC unplug: pause playback, clear `setPreferredMixerAttributes`, surface `BitPerfectEngagementState.DeviceLost`; on reconnect: re-probe + re-engage if still configured
- DeadObjectException recovery: wrap every native-call site in try/catch DeadObject; reset state + re-init AudioTrack; surface to UI
- Underrun handling: `AudioTrack.getUnderrunCount()` polled per buffer-write; if > threshold (e.g. 5/sec) → buffer size auto-doubles (within sanity cap); telemetry logged via kermit

#### B5 — Loudness-matched toggle + settings UI (10-15hr)

**Goal:** F2 mitigation — bit-perfect is session-level setting with fade-toggle so user doesn't experience sudden loudness shock; truthful about the RG-disabled contract.

Detailed bite-sized plan: **written at flight start**. Sub-flight outline:

- `LoudnessMatchedToggle.engage()`: pause current track; fade `AudioManager.STREAM_MUSIC` volume to 0 over 250ms (`AudioManager.adjustStreamVolume` loop OR `STREAM_VOLUME_CHANGED_ACTION` if API supports); swap PlatformPlayer from `Media3ExoPlayerImpl` to `BitPerfectAudioTrackPlayerImpl` (DI swap or runtime engine-switch — design TBD at flight start); restore queue/position/index; fade volume back up to a precomputed level matching pre-toggle perceived loudness (best-effort); resume
- `LoudnessMatchedToggle.disengage()`: mirror operation in reverse
- Settings UI in `SettingsScreen.kt`: "Bit-perfect output (USB DAC required)" toggle; tooltip explains capability-gating; when engaged, banner shows "Bit-perfect engaged — ReplayGain disabled, system volume locked"
- `BitPerfectAvailability` enum drives toggle enabled/disabled state and tooltip text
- jetpack-compose-test: toggle-engage during playback → expects fade + mode-flip; toggle-disengage during playback → expects mirror; toggle-disabled-when-no-USB → expects gray-out + tooltip

---

## 7. Detailed bite-sized plan — Sub-flight B0 (capability probe spike)

This is the only sub-flight with full TDD-step granularity in this top-level plan; it's the hard gate that unlocks the rest of Stream B and must be executed first.

**Files:**
- Create: `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/BitPerfectCapabilityProbe.kt`
- Create: `app-android/src/main/kotlin/com/clayworks/kiln/debug/BitPerfectProbeActivity.kt` (debug-build-only activity)
- Create: `docs/decisions/2026-05-24-phase-2b-bitperfect-probe-result.md` (commit upon completion)
- Modify: `app-android/src/main/AndroidManifest.xml` (declare `<activity android:name=".debug.BitPerfectProbeActivity">` debug-only)

### B0-T1: Write the failing test

- [ ] **Step 1:** Create test file with golden-path assertion

```kotlin
// audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/BitPerfectCapabilityProbeTest.kt
package com.clayworks.kiln.audio.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class BitPerfectCapabilityProbeTest {

    @Test
    fun `probe returns NoUsbDac when no USB output device attached`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val probe = BitPerfectCapabilityProbe(ctx)
        val result = probe.probe()
        // Robolectric default has no USB device → NoUsbDac
        assertEquals(BitPerfectAvailability.NoUsbDac, result.availability)
    }

    @Test
    fun `probe returns UnsupportedDevice when getSupportedMixerAttributes empty`() {
        // Test will require mocking AudioManager; deferred to detailed plan
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :audio:playback:androidHostTest --tests "*BitPerfectCapabilityProbeTest*"
```

Expected: FAIL with "Unresolved reference: BitPerfectCapabilityProbe"

### B0-T2: Implement `BitPerfectAvailability` enum + result data class

- [ ] **Step 3:** Create the enum + result type

```kotlin
// audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/BitPerfectCapabilityProbe.kt
package com.clayworks.kiln.audio.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager

enum class BitPerfectAvailability {
    Available,
    UnsupportedDevice,
    NoUsbDac,
    Disabled,
}

data class BitPerfectProbeResult(
    val availability: BitPerfectAvailability,
    val supportedFormats: List<AudioFormat>,
    val deviceProductName: String?,
)

class BitPerfectCapabilityProbe(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun probe(): BitPerfectProbeResult {
        val usbDevice = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }

        if (usbDevice == null) {
            return BitPerfectProbeResult(
                availability = BitPerfectAvailability.NoUsbDac,
                supportedFormats = emptyList(),
                deviceProductName = null,
            )
        }

        // API 34+: getSupportedMixerAttributes(audioDeviceInfo)
        val supported = audioManager.getSupportedMixerAttributes(usbDevice)
        val bitPerfectFormats = supported
            .filter { it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            .map { it.format }

        return BitPerfectProbeResult(
            availability = if (bitPerfectFormats.isEmpty())
                BitPerfectAvailability.UnsupportedDevice
            else
                BitPerfectAvailability.Available,
            supportedFormats = bitPerfectFormats,
            deviceProductName = usbDevice.productName?.toString(),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :audio:playback:androidHostTest --tests "*BitPerfectCapabilityProbeTest*"
```

Expected: PASS — Robolectric default audio manager has no USB device, so `NoUsbDac` result.

### B0-T3: Create debug-build probe activity

- [ ] **Step 5:** Create the activity that surfaces probe results visually

```kotlin
// app-android/src/main/kotlin/com/clayworks/kiln/debug/BitPerfectProbeActivity.kt
package com.clayworks.kiln.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clayworks.kiln.audio.playback.BitPerfectCapabilityProbe

class BitPerfectProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val probe = BitPerfectCapabilityProbe(this)
        val result = probe.probe()
        setContent {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Availability: ${result.availability}")
                Text("Device: ${result.deviceProductName ?: "(none)"}")
                Text("Supported formats:")
                result.supportedFormats.forEach { format ->
                    Text("  ${format.sampleRate} Hz / ${format.encoding} / ${format.channelCount} ch")
                }
            }
        }
    }
}
```

- [ ] **Step 6:** Declare activity in manifest

```xml
<!-- app-android/src/debug/AndroidManifest.xml (or main manifest with debug-only flavor) -->
<activity
    android:name=".debug.BitPerfectProbeActivity"
    android:exported="true"
    android:label="Kiln Bit-Perfect Probe">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

### B0-T4: Manual probe execution on Pixel 10 Pro XL

- [ ] **Step 7:** Build + install debug APK on Pixel 10 Pro XL with Clay's USB-C-to-AUX dongle attached

```
./gradlew :app-android:assembleDebug
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk
adb shell am start -n com.clayworks.kiln/.debug.BitPerfectProbeActivity
```

Expected: Activity launches; screen shows probe result.

- [ ] **Step 8:** Screenshot the result for the result-doc

```
adb shell screencap -p /sdcard/probe-result.png
adb pull /sdcard/probe-result.png docs/decisions/assets/2026-05-XX-bitperfect-probe-pixel10pro.png
```

### B0-T5: Write result document

- [ ] **Step 9:** Create `docs/decisions/2026-05-XX-phase-2b-bitperfect-probe-result.md` with:
  - Date probe was run
  - Device (Pixel 10 Pro XL, Android version, build number)
  - USB-DAC tested (Clay's exact USB-C-to-AUX dongle make/model/USB Audio Class version)
  - Screenshot of probe result
  - Verdict: PROBE_PASS / PROBE_FAIL / PROBE_PARTIAL with format list
  - Decision: proceed to B1, OR drop Stream B
  - Sign-off (Clay reviews + countersigns)

### B0-T6: Commit + decide

- [ ] **Step 10: Commit the probe-result doc + code**

```
git add audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/BitPerfectCapabilityProbe.kt
git add audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/BitPerfectCapabilityProbeTest.kt
git add app-android/src/main/kotlin/com/clayworks/kiln/debug/BitPerfectProbeActivity.kt
git add app-android/src/debug/AndroidManifest.xml
git add docs/decisions/2026-05-XX-phase-2b-bitperfect-probe-result.md
git add docs/decisions/assets/2026-05-XX-bitperfect-probe-pixel10pro.png
git commit -m "$(cat <<'EOF'
phase-2b(b0): bit-perfect capability probe + Pixel 10 Pro XL result

Result documented at docs/decisions/2026-05-XX-phase-2b-bitperfect-probe-result.md.
Gates all subsequent Phase 2b Stream B work per F11 (probe-gate enforcement).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 11:** Branch on result
  - **PROBE_PASS or PROBE_PARTIAL:** proceed to B1 (Android JNA-libFLAC port)
  - **PROBE_FAIL:** save engram entry recording the empirical result; revise this plan to drop Phase 2b-B; ship 2b-prereq + 2b-A only; escalate to Clay for Phase 2c re-evaluation

---

## 8. Test strategy

| Test layer | Tool | Scope |
|---|---|---|
| Unit (commonMain) | kotlin.test + kotest-property | New mappers in `LocalLibrarySourceMappers.kt` (e.g. `Track → SpecSheetEntry`); aggregate-stat math |
| Unit (androidHostTest) | Robolectric + kotlin.test | `BitPerfectCapabilityProbe`, `BitPerfectAudioFocusManager`, `BitPerfectMediaSessionManager`, `BitPerfectDeviceRouter` (mocked `AudioManager` + `AudioDeviceCallback` events) |
| Unit (desktopTest) | kotlin.test | `AndroidFlacDecoderImpl` golden-PCM tests against `JvmFlacDecoderImpl` reference (same fixtures, asserts byte-identity within decoder layer) |
| Integration (cross-player invariant) | Robolectric | `CrossPlayerInvariantTest` runs rapid-skip race scenario against `Media3ExoPlayerImpl` (existing) + `BitPerfectAudioTrackPlayerImpl` (new). F14 mitigation. |
| Integration (Compose UI) | jetpack-compose-test | `SpecSheetScreen` golden-path / empty / missing-RG render; `SettingsScreen` bit-perfect-toggle states (Available/Unsupported/NoUsbDac); navigation push/pop SpecSheet from NowPlayingTab |
| Integration (real device, latency budget) | Manual + screen capture | Pixel 10 Pro XL with USB-C-to-AUX dongle + JBL Spinner BT or similar reference output; bit-perfect engaged; play 24/96 FLAC fixture; measure track-to-track gap on rate-change (F6); measure underrun count over 30 min playback (F9) |
| Integration (DAC bit-identity) | Null-test acceptance rig (B2) | DAC loopback capture vs JNA-libFLAC reference PCM; 0 bit errors over 30s windows for {44.1/16, 48/16, 96/24, 192/24} |
| Performance regression | JMH/Microbenchmark | `AndroidFlacDecoderImpl` decode throughput (≥5× realtime for 96/24 on Pixel 10 Pro XL) |
| Manual smoke (Pixel 10 Pro XL) | Clay's ears + library | After each B-flight: play ≥5 tracks from real library; toggle bit-perfect on/off mid-playback; unplug USB-DAC mid-playback; receive phone call; play a Bluetooth connection; verify behavior matches plan |

---

## 9. Rollback story

| Scenario | Recovery |
|---|---|
| Capability probe FAILS on Pixel 10 Pro XL | Drop Stream B from Phase 2b. Ship Phase 2b-prereq + Phase 2b-A only. Re-evaluate Stream B at Phase 2c kickoff. Engram entry records empirical result. |
| Null-test acceptance rig (B2) fails to achieve 0 bit-errors for a given format | Rename UI label from "Bit-perfect output" to "Direct mixer output" for that format-class; surface user-visible "Format X-Y not verified bit-identical" warning; ship limited subset. Engram entry records failure case. |
| Bit-perfect path causes underruns Clay can hear | Increase `AudioTrack` buffer size (B4 mitigation already in scope); if insufficient, escalate write loop to C++/JNI per F9. Engram entry records audible failure. |
| `setPreferredMixerAttributes` leak observed (bit-perfect "stuck on" after toggle-off) | F4 mitigation already in B4 scope (clear-on-release); if still observed, escalate to hard-reset via `MediaRouter`-style API or device reboot suggestion. Engram entry records hygiene gap. |
| Stream B effort overruns 50%+ of upper estimate (>368hr) | Pause Stream B; re-litigate scope with Clay; consider cut to "Direct mixer output" rename (drops JNA-libFLAC port + null-test rig) for a smaller "honest" feature. |
| Spec Sheet aggregate query janks on 40k-track library | Add cached aggregate table populated on scan-end; SpecSheet reads cache instead of live aggregate. |
| Per-feature flag: settings toggle disables bit-perfect entirely | `bit_perfect_enabled = 0` in settings → DI binds `Media3ExoPlayerImpl` to `PlatformPlayer`; bit-perfect code paths cold. Clay can always opt out. |

---

## 10. Latency budget + verification metric

Bit-perfect is **NOT a latency feature**. The verification metric is **bit-identity**, measured numerically.

| Path | Metric | Number | Verification method |
|---|---|---|---|
| Phase 2b-B bit-perfect path | **Bit-error rate** over 30s capture window for {44.1/16, 48/16, 96/24, 192/24} | **0 bit errors** (strict) | B2 null-test rig: DAC loopback vs JNA-libFLAC reference PCM |
| Phase 2b-B bit-perfect path | Track-to-track gap on sample-rate change | **<150 ms** (audible-but-tolerable) | B3 instrumentation logs; manual ear-test on rate-mixed playlist |
| Phase 2b-B bit-perfect path | Underrun count over 30 min continuous playback | **<5 underruns total** | `AudioTrack.getUnderrunCount()` polled per buffer-write |
| Phase 2b-A SpecSheet route opening | Time-to-render after tap-title | **<100 ms** P95 on Pixel 10 Pro XL | jetpack-compose-test `composeTestRule.waitForIdle()` timestamps |
| Phase 2b-B engagement transition (toggle-engage) | End-to-end (tap toggle → bit-perfect engaged + first PCM out) | **<2 s** including fade | Manual stopwatch + on-device log timestamps |

**Phase 2c (deferred) latency budget** when WASAPI work begins: WASAPI exclusive-mode round-trip target **<10 ms output** on Windows 11 24H2 + Clay's USB-DAC; **<3 ms** stretch for pro-class drivers. Empirical measurement required at Phase 2c kickoff.

---

## 11. Phase 2c trigger conditions (deferred work anchors)

Phase 2c starts when ALL of:
- Phase 2b ships to main with all sub-flights green
- Clay has dogfooded Phase 2b bit-perfect for ≥30 days without daily-blocking issues
- Cross-platform audiophile asymmetry (Android bit-perfect vs Windows javax.sound) is causing friction in actual listening sessions
- OR: a use case beyond FLAC playback emerges that justifies Oboe low-latency (currently not in scope per anti-roadmap §11; would itself require revisit conversation)

If none of these fire within ~6 months of Phase 2b ship, Phase 2c gets a soft-lock-revisit conversation: do we still want it, or has the priority surface shifted?

---

## 12. Soft-lock revisit conditions (during Phase 2b execution)

These trigger a STOP + Clay-conversation:

- B0 probe returns PROBE_FAIL on Pixel 10 Pro XL — drop Stream B per §9
- B1 Android JNA-libFLAC port can't hit 5× realtime decode on Pixel 10 Pro XL — escalate to NDK direct C linkage (more work, but inescapable for the bit-perfect chain to be viable)
- B2 null-test rig consistently shows non-zero bit-errors for the formats Clay actually has — feature rename + scope cut (per §9)
- Stream B trajectory shows >50% overrun at any flight boundary — pause + re-litigate (per §9)
- `PlatformPlayer` interface needs new methods for Stream B to work — STOP. This is the canonical "boundary is wrong" signal (vetting Item 13 invariant). Use a side-channel (per F13 mitigation) or escalate.

---

## 13. Quality bar checklist (this plan satisfies each)

- [x] Build is green on main as the starting baseline
- [x] `/multi:decide` ADR locked + engram-saved (`architecture/kiln-phase-2b-sequencing`)
- [x] `/multi:falsify` adversarial pre-mortem run + findings integrated (engram `architecture/kiln-phase-2b-falsify-integration`; risk register §4)
- [ ] **Library-vetting log appended** with new library decisions — **TODO before Stream B starts** (Item 13 addendum; Item 14 for JNA-libFLAC port; Item 15 for null-test rig)
- [x] Plan document references file:line locations for every existing-code interaction point (§5)
- [x] Test strategy includes both unit tests (per-impl, no real device) AND integration tests (real device + null-test rig) — §8
- [x] Rollback story documented — §9
- [ ] **Clay reviews + locks the plan before code work begins** — **pending this read**
- [x] Latency budget is a NUMBER — §10 (bit-error count = 0 strict; track-gap <150ms; underrun <5/30min)

---

## 14. Out-of-scope reminders (anti-roadmap §11 + Phase 2c boundaries)

- Tidal (cut 2026-05-18; permanent)
- Spatial Audio / HRTF
- AI/LLM features
- Cross-device handoff
- iOS / Linux / macOS
- Wear / Tablet-optimized / Auto
- Tag editing
- Lyrics
- Last.fm scrobbling
- BT codec readouts
- Podcasts
- MIDI controller for EQ
- Windows WASAPI (Phase 2c)
- Android Oboe low-latency path (Phase 2c)
- Hardware Spec Sheet About screen identity move (note: the SpecSheet in Phase 2b is the *per-track* spec sheet, NOT the App-level About screen — those are distinct per spec §3)
- Library extraction to JitPack (Phase 2c+ per original plan)
- 31-band parametric EQ refinements (existing in MVP; Flight C; carries forward)
- Sectioned search (existing in MVP Flight D; carries forward)
- FFT visualizer (existing in MVP Flight E; carries forward)
- Room correction (Phase 3)

---

## 15. Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-23-phase-2b-plan.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — Each sub-flight (Phase 2b-prereq → A → B0 → B1 → ...) gets its own bite-sized plan written at flight start (this top-level doc is the phase ADR + B0 detailed plan), then a fresh subagent per sub-flight task using `superpowers:subagent-driven-development`. Review checkpoint between sub-flights. Best for Clay's solo-dev rhythm + Bus-Factor-of-One discipline.

2. **Inline Execution** — Execute sub-flights in this session using `superpowers:executing-plans` with batch checkpoints between sub-flights. Best if Clay wants to bang through Phase 2b-prereq + B0 in this session for momentum.

**Which approach?**

Either way: **B0 capability probe MUST run first** (the §7 detailed bite-sized plan is execution-ready), and the result-doc commit is the hard gate for all subsequent Stream B work.

---

End of plan.
