# Pre-MVP Research: Library Vetting & Decision Log

**Date:** 2026-05-18 (Pre-MVP Research session 1, in progress)
**Author:** Claude Opus 4.7 (1M context) for Clay Haworth
**Spec ref:** [`docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`](../superpowers/specs/2026-05-18-kiln-rebuild-design.md)
**Plan ref:** [`docs/superpowers/plans/2026-05-18-kiln-execution-plan.md`](../superpowers/plans/2026-05-18-kiln-execution-plan.md)

This document records library version decisions, gotchas found, and any soft-lock revisitations triggered during Pre-MVP Research, per plan §2.

---

## Item 13: Audio backend architecture decision

> **Plan reference:** Pre-MVP Research §2.1 item 13 (added 2026-05-18 post-Gemini critique). Most strategic of the pre-MVP items because it determines whether Phase 2b Flights H+I (AAudio MMAP, WASAPI) stay in scope.

### Question

Should Kiln's `:audio:playback` module plan for an **engine-swap-shaped boundary** from MVP (preserving the option to swap Media3 ExoPlayer / Java Sound for AAudio MMAP / WASAPI in Phase 2b), OR **commit to Media3 ExoPlayer + Java Sound permanently** and remove Phase 2b Flights H+I from scope?

### Context

Gemini's adversarial critique (2026-05-18) correctly identified that AAudio MMAP and WASAPI are NOT interchangeable adapters with Media3 ExoPlayer / Java Sound. They are different audio engines with:
- Different threading models (Media3 owns its threads; AAudio/WASAPI require an app-managed real-time priority callback thread pulling from a ring buffer)
- Different buffer management (Media3 buffers internally; AAudio/WASAPI expose raw PCM frames)
- Different decoder integration (Media3 includes decoders; AAudio/WASAPI just consume PCM, so we'd need our own decoder path)
- Different track-transition semantics (Media3's gapless playback would need re-implementation)

If we don't design for this from MVP, swapping later means tearing out the entire playback engine. That's the "bait-and-switch" Gemini flagged.

### Decision factors

**Argument FOR engine-swap-shaped boundary in MVP:**

- Cost of the abstraction: ~10–15 hours extra in MVP (designing `PlatformPlayer` interface + adapter pattern carefully; identifying what's player-engine-specific vs. shared)
- Preserves Phase 2b option for bit-perfect audio (audiophile credibility; lower latency on USB DAC dongle path)
- Avoids Gemini's bait-and-switch concern
- Aligns with the Concentric Modules pattern from prior brainstorm (platform-specific differences hidden behind clean interfaces)
- Makes the audio pipeline more testable (mockable `PlatformPlayer` interface vs. directly-coupled Media3)

**Argument AGAINST (commit to ExoPlayer/Java Sound permanently):**

- Simpler MVP — saves 10–15 hours of abstraction design work
- Faster path to MVP-1.0
- Clay's USB-C-to-AUX dongle path works fine with Android's stock audio routing — bit-perfect difference is mostly inaudible for his actual use case (FLAC source → analog speakers via DAC dongle)
- Removes 160–240 hours of Phase 2b work (Flights H+I)
- Reduces overall project complexity
- Conversely, building the abstraction without ever exercising it (if Flights H+I never happen) is sunk cost

### Clay's prior signals (from session memory + spec)

- Wants "granular control over [his] listening experience" — leans audiophile
- Explicitly endorsed AAudio MMAP as the Phase-2 hi-res path when we discussed dropping `hificore` native code
- Stated requirement: "Don't drop below current listening experience" — but ExoPlayer/Java Sound quality is likely indistinguishable from `hificore` for his actual setup
- Approved aggressive testing pyramid — suggests appetite for engineering rigor over shipping minimalism

### Decision

**Plan for the engine-swap-shaped boundary in MVP. Do NOT commit to building AAudio MMAP / WASAPI at this time.**

Phase 2b Flights H and I get downgraded from "must build" to **"may build, soft-lock revisit at end of Phase 2a."**

### Rationale

The boundary is cheap (~10–15 hrs); the implementation is expensive (~160–240 hrs). Build the cheap part now; defer the expensive decision until you've actually dogfooded ExoPlayer/Java Sound for months.

Three reasons this is the right call:

1. **Optionality preservation.** Building the boundary keeps both paths open. Committing to ExoPlayer permanently is a one-way door. We don't have enough information today to make that call.

2. **Honest dogfooding.** The right time to decide "is ExoPlayer/Java Sound good enough" is after using Kiln daily for 3-6 months — not now. The decision criterion should be Clay's ears, not Gemini's predictions.

3. **Concentric Modules invariant honored.** The abstraction earns its keep by isolating the audio engine from the rest of the codebase. Tests get cleaner. Mockability improves. The 10-15 hours produce architecturally cleaner code regardless of whether Flights H+I ever happen.

### Implementation guidance for MVP

The `:audio:playback` module exposes a `PlatformPlayer` interface (in `commonMain`) supporting:

```kotlin
interface PlatformPlayer {
    val state: StateFlow<PlayerState>            // playing/paused/buffering/stopped/error
    val position: StateFlow<Long>                // ms
    val queue: StateFlow<List<MediaItem>>

    suspend fun loadQueue(items: List<MediaItem>, startIndex: Int = 0)
    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(positionMs: Long)
    suspend fun skipToNext()
    suspend fun skipToPrevious()
    suspend fun setRepeatMode(mode: RepeatMode)
    suspend fun setShuffleMode(enabled: Boolean)

    // Processor injection (visualizer slot, EQ slot, ReplayGain, etc.)
    fun addAudioProcessor(processor: AudioProcessor)
    fun removeAudioProcessor(processor: AudioProcessor)

    // Capture mode (stubbed at MVP; phase-3 room correction)
    fun enterMeasurementMode(): MeasurementSession
}
```

**MVP implementations:**
- `androidMain`: `Media3ExoPlayerImpl` — wraps ExoPlayer with a custom render factory injecting processors
- `jvmMain`: `JavaSoundPlayerImpl` — manages SourceDataLine + decoder thread + processor chain

**Phase 2b implementations (if built):**
- `androidMain`: `AAudioMmapPlayerImpl` — owns the real-time priority callback thread; PCM ring buffer; explicit decoder integration
- `jvmMain`: `WasapiPlayerImpl` — JNI bridge to WASAPI; similar threading model

**Track transition logic (gapless, fade) lives in `commonMain` above the player interface**, so it doesn't need re-implementation per engine. Each `PlatformPlayer` consumes PCM and forwards transitions through the shared logic.

**Decoder abstraction:**
```kotlin
interface Decoder {
    suspend fun open(playable: Playable): DecodedStream
}
```

- `androidMain`: `Media3DecoderImpl` — delegates to Media3's decoder library (for the ExoPlayer impl)
- `androidMain`: `RawAndroidDecoderImpl` — uses MediaExtractor + MediaCodec directly (for the AAudio impl, if ever built)
- `jvmMain`: `JflacDecoderImpl` — pure-JVM FLAC decoder for both Java Sound and WASAPI impls

### Soft-lock revisit point

**End of Phase 2a.** Decide: do Phase 2b Flights H and I stay in (build AAudio MMAP / WASAPI), or get cut (commit to ExoPlayer/Java Sound permanently)?

**Decision criterion:** Has ExoPlayer/Java Sound produced any audible quality complaints during 3-6+ months of daily dogfooding? Has Clay's actual listening revealed any audible difference vs. the JAMZ + hificore baseline he's coming from?

- If **no audible problems**: cut Flights H+I. Save 160-240 hrs. Recognize that the abstraction work was still worth doing because it produced cleaner architecture.
- If **yes, audible issues**: build Flights H+I per Phase 2b plan. The abstraction makes this a sane lift.

### Estimated effort impact

**MVP:** +10-15 hours for the abstraction design + careful `PlatformPlayer` interface boundary. Net MVP range updated: 315-450 hours (was 305-435).

**Phase 2b:** No change to Flights H+I estimates if they happen; both still 80-120 hours each. But these now have an explicit "may not happen" tier — soft-lock revisit at end of Phase 2a may cut them entirely, saving 160-240 hours.

### Status

**DECIDED.** Implementation begins in MVP Session 4-7 (Library + playback vertical slice) when `:audio:playback` is scaffolded.

---

## Item 1: Compose Multiplatform desktop stability

> **Plan reference:** Pre-MVP Research §2.1 item 1. Foundational — affects all UI work.

### Question

Verify the current (mid-2026) state of Compose Multiplatform for desktop. Specific concerns: window management APIs, system tray support, native menu integration, keyboard shortcut handling, file picker integration.

### Method

Context7 query against `/jetbrains/compose-multiplatform` (official JetBrains repo, source reputation: High, 326 code snippets).

### Findings

**Maturity signals (positive):**

- JetBrains actively maintains Compose Multiplatform for desktop — official JVM target with hardware-accelerated UI rendering on macOS, Windows, and Linux.
- Desktop-specific APIs are first-class in the framework:
  - `MenuBar { Menu { Item(...) } }` — native menu bar with keyboard shortcuts via `KeyShortcut(Key.X, ctrl = true)`, mnemonics, checkbox items, separators
  - `Tray(state, icon, menu)` — system tray with notifications via `rememberTrayState()` + `rememberNotification(title, body)`
  - `Window(...)` — full window manipulation
  - `application { ... }` — main entry point
- Distribution via `./gradlew packageDistributionForCurrentOS` produces native binaries (MSI/exe on Windows, dmg on macOS, deb on Linux)
- JetBrains maintains a benchmarks repo with regression-detection tooling specifically for desktop:
  - `LazyList` benchmark with complex LazyColumn patterns (pull-to-refresh, load-more, continuous scroll)
  - `LazyGrid` benchmark with **12,000 items**
  - Tooling to bisect performance regressions across versions

**Version state:**

- Context7 lists v1.11.0-alpha02 as recent. Alpha = actively iterating.
- Stable line at MVP-scaffold time will be the 1.10.x series (or whatever STABLE is current; verify before Sessions 1-3)
- **Decision: lock to current STABLE (not alpha) at scaffold time.** Pin exact version in `gradle/libs.versions.toml`.

**Gotchas identified:**

1. **File picker is third-party.** No built-in file picker in core Compose Multiplatform. Use community library `/wavesonics/compose-multiplatform-file-picker` (Context7 reputation: High, benchmark score 94.05) — provides native file dialogs across Windows / macOS / Linux / Android / iOS / JS. For Kiln's "choose scan folder" UI, this is the path. Pre-MVP item 2 (image loader + friends) will dive deeper.

2. **Alpha versions exist for cutting-edge features.** Stable lags alpha. If something we need is alpha-only, that's a soft-lock revisit; otherwise stay stable.

3. **LazyColumn 40k performance is unverified.** Official benchmarks go to ~12k items. Our library is ~39.5k (3x that). Need the Pre-MVP item 12 spike before committing to a pure-`LazyColumn` strategy. Mitigations if perf is bad: paged loading from SQLDelight, custom windowing/virtualization.

### Decision

**Compose Multiplatform desktop is mature enough to commit to.** Confidence high based on JetBrains's first-class API support and active benchmark/regression tooling.

**Specific actions:**

- Use Compose Multiplatform stable line (verify version at MVP Session 1; pin in `libs.versions.toml`)
- Use `MenuBar` for desktop native menus
- Use `Tray` + `rememberTrayState()` for desktop system tray (relates to Item 11 — provides desktop side of media-key + notification surface)
- Use `compose-multiplatform-file-picker` (latest) for folder/file selection dialogs
- Defer 40k-list perf decision to Pre-MVP item 12 spike (must run before MVP Session 8)

### Status

**DECIDED.** Compose Multiplatform stays as the UI engine for both targets. No soft-lock revisit needed unless item 12 spike reveals catastrophic LazyColumn perf.

---

## Item 4: Voyager vs Decompose final decision

> **Plan reference:** Pre-MVP Research §2.1 item 4. Foundational — affects nav architecture.

### Question

Verify Voyager handles Kiln's navigation needs (deep stack, save-state across config changes, multi-screen flows). If insufficient, soft-lock swap to Decompose.

### Method

Context7 resolve + query against both `/adrielcafe/voyager` (Voyager, source: High, benchmark 92.1, 258 snippets) and `/arkivanov/decompose` (Decompose, source: High, benchmark 88.35/94.45, 668 snippets — more documentation surface).

### Findings

**Voyager — what we get:**

- Multiplatform navigation library built specifically for Jetpack Compose / Compose Multiplatform
- Compose-idiomatic API: `Navigator(RootScreen()) { rootNavigator -> CurrentScreen() }`
- **Nested navigators with `level`/`parent` references** — natural fit for Kiln's hierarchy (Library → Album → Track → EQ-during-playback, etc.)
- **TabNavigator** with per-tab back stacks built-in — fits app's Library / Now Playing / Settings pattern. Each tab owns its own `Navigator` for independent stacks (YouTube-app style).
- `ScreenModel` pattern integrates with coroutines, Koin (our DI choice would interop), Hilt (we're not using). Lightweight VM equivalent.
- State restoration supported
- Lifecycle callbacks, back-press handling, deep-link support, type-safe multi-module navigation
- "Pragmatic API" framing — minimal ceremony, gets out of the way

**Decompose — what we get:**

- Tree-structured lifecycle-aware business logic **components** (more than navigation — architectural pattern)
- `ComponentContext` interface delegation pattern (`class X(componentContext: ComponentContext) : X, ComponentContext by componentContext`) — explicit lifecycle ownership at every level
- Rich `StackNavigation` API: `push`, `pushNew` (dedupe), `pushToFront`, `popWhile { ... }`, `popTo(index)`, `replaceCurrent`, `replaceAll`, `bringToFront`, custom navigate transformer
- Configurations are `@Serializable` (state restoration is rock-solid)
- Own `Value`/`MutableValue` state mechanism (Decompose-specific, alternative to StateFlow). Can bridge to Combine/SwiftUI on iOS.
- Designed UI-agnostic — Compose, SwiftUI, JS, etc. are all possible UI bindings
- Higher ceremony, more control, steeper learning curve

### Comparison for Kiln's actual needs

| Requirement | Voyager | Decompose |
|---|---|---|
| Compose-MP (Android + Desktop) | ✓ native fit | ✓ supported but UI-agnostic abstraction is overhead we don't need |
| Tab + per-tab back stack (Library / Now Playing / Settings) | ✓ TabNavigator built-in | possible but more code |
| Deep stack (Album → Track → EQ) | ✓ nested navigators | ✓ child stacks |
| State restoration | ✓ supported | ✓ via @Serializable configs |
| Integration with our DI (Kotlin-Inject) + state (ViewModel-MP + StateFlow) | ✓ pragmatic | works but Decompose's `Value` is its own thing |
| Multi-UI-framework target (e.g., native SwiftUI iOS) | not Decompose's strength | ✓ designed for it (we don't need this) |
| Pure-Kotlin business logic separation from UI | adequate (we use Circuit showcase + Arrow showcase for this) | ✓ Decompose's whole thesis |
| Solo-dev learning curve | low (pragmatic API) | medium-high (more concepts) |
| Bus-Factor-of-One readability | clear | more involved |

### Decision

**Stick with Voyager** as the spec calls for. Confidence high.

Reasoning:
1. Kiln targets Compose-MP only — Decompose's UI-agnostic abstraction is overhead without payoff
2. TabNavigator + nested navigators map cleanly to Kiln's actual screen structure
3. Business-logic / UI separation Kiln cares about comes from the **Circuit showcase in `:ui:components` Now Playing** + **Arrow showcase in `:audio:dsp`** + modular monorepo boundaries — NOT from a top-level navigation library
4. Bus-Factor-of-One favors Voyager's lower ceremony — future Clay reading his own code 6 months later has an easier path back into the codebase

### When this decision should be revisited (soft-lock)

- If we ever add iOS with native SwiftUI views as a target (not currently planned)
- If Voyager fails at handling Now Playing + EQ overlay flows during MVP Sessions 12-15
- If Voyager's KMP/Compose-MP support shows regressions in 2026 stable versions

### Status

**DECIDED.** Voyager confirmed as nav library. No soft-lock revisit triggered.

---

## Item 11: System integration patterns

> **Plan reference:** Pre-MVP Research §2.1 item 11 (added 2026-05-18 post-Gemini critique). Daily-driver table stakes — without these, the v1.1.0 parity claim is hollow.

### Question

Verify the approach for system-level integration on both platforms:

- **Android:** Audio Focus, MediaSession + lock-screen, BLE/wired-headphone disconnect handling, hardware media keys (Bluetooth, wired)
- **Windows:** SMTC (System Media Transport Controls), keyboard media keys, audio-device-change handling

### Method

Context7 query against `/androidx/media` (Media3, source: High, 786 snippets) for Android patterns. Synthesis from known patterns for Windows side.

### Findings — Android (covered well by Media3)

Media3 consolidates most of what Gemini flagged. The standard pattern:

1. **`MediaSessionService`** extends Android Service for long-lived background playback. Auto-handles media notification + keeps Player alive when app is backgrounded.
2. **`MediaSession.Builder(context, player).build()`** bridges Player to Android media ecosystem. One MediaSession exposes:
   - Lock-screen art + controls
   - Android Auto integration (we don't need this; out of scope per spec §11)
   - Wear OS integration (we don't need this either)
   - **Hardware media key dispatching** — Bluetooth headphone play/pause, wired remote buttons, Pixel volume buttons
   - Assistant integration (passive; would respond if used)
   - System notification controls
3. **`MediaController`** for cross-process control (UI activity controlling the service)
4. **AndroidManifest declarations required:**
   - `<service android:foregroundServiceType="mediaPlayback">` (Android 14+ requirement — would have bitten us)
   - Intent filter for `androidx.media3.session.MediaSessionService`
   - Legacy compat: `android.media.browse.MediaBrowserService` action

**Audio Focus:** Media3's Player respects Audio Focus by default. Configure via `ExoPlayer.Builder.setAudioAttributes(...)` and `setHandleAudioBecomingNoisy(true)`.

**BLE/wired disconnect:** `setHandleAudioBecomingNoisy(true)` on ExoPlayer Builder handles the `ACTION_AUDIO_BECOMING_NOISY` broadcast and pauses playback automatically. This is the standard pattern; no custom BroadcastReceiver needed.

**Hardware media keys (Bluetooth + wired):** automatically handled by MediaSession. No additional code beyond setting up the session.

**Result:** Media3 covers everything we need on Android.

**Estimated effort for Android side of MVP Sessions 23-25:** ~10-15 hrs.

### Findings — Windows desktop

No equivalent to Media3's all-in-one integration. Approach is multi-pronged:

1. **Audio device-change handling:**
   - Java Sound: enumerate `Mixer.Info` + use `LineListener` for line-event notifications
   - Bluetooth/wired disconnect: detect via Java Sound device-change listener; pause manually
   - System volume / focus integration: minimal in JVM by design; OS handles mixing externally

2. **Windows SMTC (System Media Transport Controls):**
   - SMTC is a Win32 API: `Windows.Media.SystemMediaTransportControls`
   - **No Compose Multiplatform built-in support** — confirmed via Item 1 research
   - **Options for implementing:**
     - (a) JNI bridge to SMTC (small native helper DLL + Kotlin wrapper)
     - (b) Existing community KMP/JVM library — needs JIT search before MVP Session 23
     - (c) JNA-based binding (avoids native DLL build step; pure Kotlin/JVM)
   - **Provides once integrated:** lock-screen-style media controls (Windows 10+ play/pause/skip in volume flyout + notification center), keyboard media key handling (Pause/Play on keyboards with media keys), Windows notification center integration

3. **Keyboard media keys on Windows:**
   - SMTC integration provides these "for free" once SMTC is bound
   - Without SMTC: would need Win32 low-level keyboard hook — avoid (messy, fragile, security-flag-tripping)

### Decision

**Android system integration: covered by Media3 + standard patterns.** Implementation effort: ~10-15 hrs in MVP Sessions 23-25. No further pre-MVP research needed.

**Windows system integration: requires just-in-time research before MVP Session 23.** Approach committed: SMTC for media-key + lock-screen-like integration. Specific binding library/approach deferred. Estimated effort: ~15-20 hrs (research + binding setup + integration). Total Windows side: ~20-25 hrs.

**Combined Item 11 deliverable for MVP Sessions 23-25:** ~25-35 hrs as originally estimated in plan §3.2 — confirmed plausible.

### Just-in-time research deferred (before MVP Session 23)

- Survey existing JVM/Kotlin libraries for Windows SMTC (`smtc-jvm`, JNA-based bindings, kmedia, etc. — none known to be authoritative; vet on day-of)
- Decision tree: use existing lib OR roll-our-own JNI/JNA bridge

### Status

**DECIDED for Android.** Use Media3 `MediaSessionService` + `MediaSession` + `MediaController` pattern. Audio Focus and audio-becoming-noisy handled by ExoPlayer defaults.

**PARTIALLY DECIDED for Windows.** Approach is "use SMTC via JNA or community library." Specific binding/library decision deferred to JIT research before MVP Session 23.

---

## Item 2: KMP-compatible image loader (Coil 3)

> **Plan reference:** Pre-MVP Research §2.1 item 2. Image loading is foundational — album art surfaces appear in library lists, Now Playing, mini-player, and feed the Palette extractor (Item 3).

### Question

Verify Coil 3's Compose Multiplatform support, specifically: (a) does the disk cache work correctly on JVM desktop, (b) does Coil bundle Palette extraction or does it require a separate library, (c) what specific stable version to pin?

### Method

- Context7 query against `/coil-kt/coil` (source: High, 291 snippets, benchmark 83.55)
- GitHub Releases API for authoritative release dates and stable-vs-beta status
- WebFetch of the upstream CHANGELOG for the 3.x breaking-change picture

### Findings

**Multiplatform coverage (confirmed):**

Coil 3.0.0 introduced full Compose Multiplatform support. Coil 3 builds for Android, iOS, JVM desktop, macOS, JavaScript, and WebAssembly. This covers both Kiln targets (Android + Windows Desktop via JVM) without per-platform image-loading code.

**Singleton-loader pattern is Compose-MP-friendly:**

```kotlin
setSingletonImageLoaderFactory { context ->
    ImageLoader.Builder(context)
        .crossfade(true)
        .build()
}
```

This wiring lives in `:app-android` and `:app-desktop` entry points; the `:ui:components` Compose code calls `AsyncImage(...)` against the singleton with no per-platform branching. Source Protocol pattern stays clean — `AsyncImage` consumes a model (URL, file path, ByteArray, etc.) and Coil routes to the right fetcher.

**Disk cache works on desktop:**

```kotlin
.diskCache {
    DiskCache.Builder()
        .directory(context.cacheDir.resolve("image_cache"))
        .maxSizePercent(0.02)
        .build()
}
```

`context.cacheDir` resolves to platform-appropriate temp directories — `${app.cacheDir}` on Android, `${user.home}/.cache/<app>` on JVM desktop. The desktop `PlatformContext` provides this without app code. **Important behavioral note flagged in Coil 3 upgrade docs:** Coil 3 manages its own disk cache (it does NOT delegate to OkHttp's cache as Coil 2 did). Do not configure OkHttp `Cache` alongside Coil's `DiskCache` — they will both try to cache and waste disk space.

**Palette extraction is NOT bundled — Coil's recipe references androidx.palette:**

Coil's official Palette recipe (in `docs/recipes.md`):

```kotlin
Palette.Builder(result.image.toBitmap()).generate { palette -> ... }
```

This is `androidx.palette.graphics.Palette` — Android-only, breaks the `:ui:theme` Concentric Modules invariant if used in `commonMain`. **Coil does not solve Item 3 — Palette extraction needs its own library decision (see Item 3 in the next session entry).**

The integration path is straightforward: Coil decodes the image to a `coil3.Image`, which can be converted to a Compose `ImageBitmap`; that ImageBitmap is what we'll hand to the chosen Palette library at Phase 2a Flight A.

**Version state:**

| Version | Date | Status |
|---|---|---|
| 3.4.0 | 2026-02-24 | **STABLE — pin this** |
| 3.5.0-beta01 | 2026-05-04 | beta — avoid |
| 3.3.0 | 2025-07-22 | superseded |
| 3.2.0 | 2025-05-13 | superseded |
| 3.1.0 | 2025-02-04 | superseded |

Release cadence ~3-4 months between stable releases. Active and well-maintained.

**Coil 3.x breaking-change inventory (relevant to Kiln):**

- Package namespace changed `coil` → `coil3` (clean fresh-derivation context for Kiln; not migration pain)
- Artifact rename: `coil-base` → `coil-core`, `coil-compose-base` → `coil-compose-core`
- Minimum Android API raised to 21 (Kiln's `minSdk = 21` matches exactly per spec §2)
- Min Android API later raised to 23 in some 3.x releases — **verify Coil 3.4.0 still supports API 21** at MVP Session 4 scaffold time; if API 23+ now, that's a spec §2 hard-lock revisit (would force Kiln's minSdk up to 23)
- Coil 3 owns its disk cache (do not pair with OkHttp `Cache`)

**Dependency choice for the networking layer:**

Coil 3 lets you pick a network library: OkHttp (Android-default), Ktor (KMP-friendly), or no-network (local-files-only). **For Kiln's MVP, local-files-only is the right choice** — there are no remote image loads in MVP (album art is embedded in files or sits as `folder.jpg` next to the FLAC). If Phase 3 or future Subsonic-style sources arrive, swap to Ktor.

MVP `libs.versions.toml` additions (sketch):
```toml
coil = "3.4.0"

coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
# No network engine at MVP — add coil-network-ktor only when a remote source appears
```

### Decision

**Adopt Coil 3.4.0 as Kiln's image loader.** Pin in `gradle/libs.versions.toml` at MVP Session 1-3.

- Use `coil-compose` only at MVP (local-files-only image loading, no networking dependency)
- Configure `DiskCache` with `context.cacheDir.resolve("image_cache")` for cross-platform disk caching
- Set `crossfade(true)` at the singleton-loader level (touches every `AsyncImage` call)
- Do NOT pair with OkHttp `Cache` — Coil owns the disk cache
- Plan for `coil-network-ktor` if/when Phase 3 or any remote source ever lands; not before

### Just-in-time research deferred (before MVP Session 4)

- Confirm Coil 3.4.0 still supports `minSdk = 21` (or whether 3.4.x has bumped to 23). If 23, raise the question with Clay before adopting — it's a spec §2 hard-lock implication.
- Verify Coil 3.4.0 + Compose Multiplatform 1.7.x or 1.8.x stable on the version actually pinned at scaffold time (Compose-MP version comes from Item 1's just-in-time check at scaffold).

### Soft-lock revisit triggers

- Coil 3.4.0 reveals desktop disk-cache bugs during MVP Session 8+ (library views with thousands of album-art tiles) — fall back to 3.3.0 or evaluate Compose-MP's own image loading
- Coil drops Android API 21 support and Kiln needs to keep it — fork-or-pin scenario

### Status

**DECIDED.** Coil 3.4.0 confirmed as image loader. Wire-up lands in MVP Session 1-3 (DI graph) and MVP Session 8 (first library views with album art).

---

## Next items in queue

- (Remaining items 3, 5, 6, 7, 9, 10, 12 — to be tackled in subsequent Pre-MVP Research sessions)

Items still to research:
- Item 3 — KMP-compatible Palette/color extractor (critical for Kiln Dynamic theming)
- Item 5 — Circuit + Molecule on KMP (now applies to `:ui:components` Now Playing)
- Item 6 — SQLDelight schema design for 39.5k tracks
- Item 7 — Compose-MP screenshot testing (Roborazzi maturity)
- Item 9 — Java Sound capability survey on Windows
- Item 10 — jpackage / Windows distribution
- Item 12 — Compose MP Desktop LazyColumn 40k stress test (requires actual code spike)

---

## Session 1 summary

**Date:** 2026-05-18
**Items completed:** 4 of 12 active (Items 13, 1, 4, 11)
**Items remaining:** 8

**Soft-lock revisits triggered:** None this session. Voyager confirmed (Item 4 stays as locked); Compose MP stays as UI engine (Item 1 confirmed); audio backend decided to plan engine-swap boundary without committing to AAudio/WASAPI (Item 13 = new soft-lock revisit point at end of Phase 2a).

**New just-in-time research items deferred:**
- Windows SMTC library/binding decision (before MVP Session 23)
- Item 12 actual code spike (before MVP Session 8)

**Estimated remaining Pre-MVP Research effort:** ~14-18 hrs across the 8 remaining items (per plan estimates).

