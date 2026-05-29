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

## Item 3: KMP-compatible Palette/color extractor

> **Plan reference:** Pre-MVP Research §2.1 item 3. **Critical for Kiln Dynamic theming** (spec §5.3). Without this, the album-art-driven palette story collapses and Phase 2a Flight A cannot ship.

### Question

Pick a Compose-MP-compatible library to extract dominant + vibrant + muted swatches from album art, callable from `:ui:theme/commonMain`, suitable for driving Kiln Dynamic theming per spec §5.3 / §5.4. Pin a specific version.

### Method

- Web search for the current KMP palette extraction landscape (current to mid-2026)
- GitHub Releases API + repo metadata on the two viable candidates (jordond/kmpalette, jordond/MaterialKolor)
- WebFetch of upstream READMEs for module-level dependency clarity
- Spec §5.3 / §5.4 re-read to confirm what the extractor actually needs to produce

### Findings

**Spec demands (the bar to clear):**

Per §5.3, the extractor must yield (or enable us to derive) these swatch roles:

| Spec role | Maps to AndroidX Palette concept |
|---|---|
| `bg-base` rotated 15-30% toward dominant hue | Dominant swatch + HSL rotation |
| `bg-surface` rotated 40-60% toward dominant hue | Dominant swatch + HSL rotation |
| `accent-primary` (vibrant swatch) | Vibrant or LightVibrant |
| `accent-secondary` (vibrant alternate) | DarkVibrant or LightVibrant |
| `surface-tint` (muted dark) | DarkMuted |
| `list-highlight` derived from accent | Computed from accent-primary |
| `on-accent-text` for WCAG AA | Computed via contrast math |

Per §5.4, the post-processing layer must:
- WCAG AA contrast-check every extracted color against `text-primary` (#F5EBE0)
- Darken bright pastels until they're usable as accents
- Fall back to `accent-soft` on low-saturation art
- Fall back to idle brand palette on extraction failure

**Candidate 1: jordond/kmpalette (the front-runner)**

- Repo: https://github.com/jordond/kmpalette
- License: MIT (with `kmpalette-androidx-palette` submodule under Apache 2.0)
- Stars: 290 — small, but author also maintains MaterialKolor (880 stars), so signal is "active small library by trusted maintainer" not "abandoned"
- Maintenance: last commit 2026-05-16 (2 days ago at time of writing — actively worked on)
- Open issues: 18 — manageable
- **Version state:**

| Version | Date | Status |
|---|---|---|
| 4.0.0-beta02 | 2026-03-03 | Beta — current development line |
| 4.0.0-beta01 | 2026-02-17 | Earlier beta |
| 3.1.0 | 2024-01-30 | Stable but 2+ years old |

- **Algorithm provenance:** "Compose multiplatform port for AndroidX Palette." Specifically a Kotlin port of `androidx.palette.graphics.Palette` — the same battle-tested median-cut quantization + HSL-based swatch role classification Android has used since 2014. Not a Material Color Utilities seed-tonal-palette generator (different problem).
- **Platform coverage:** Android, JVM desktop, iOS, macOS, JS, WASM. Covers both Kiln targets cleanly.
- **Modules:**
  - `kmpalette-core` — Compose utilities (`rememberPaletteState`, `rememberDominantColorState`)
  - `kmpalette-androidx-palette` — palette algorithm core, no Compose dependency
  - `kmpalette-extensions-base64`, `-network` (Ktor), `-file` (FileKit) — optional loaders we don't need
- **API for our use case:**

```kotlin
// In :ui:theme/commonMain, given a Compose ImageBitmap from Coil:
val palette: Palette = Palette.from(imageBitmap).generate()

val vibrant: Color? = palette.vibrantSwatch?.color
val darkVibrant: Color? = palette.darkVibrantSwatch?.color
val muted: Color? = palette.mutedSwatch?.color
val darkMuted: Color? = palette.darkMutedSwatch?.color
val dominant: Color? = palette.dominantSwatch?.color
val onAccent: Color? = palette.vibrantSwatch?.onColor  // built-in contrast color
```

Every spec §5.3 role has a direct kmpalette source. No gaps.

- **Coil integration:** No `coil-loader` module exists. Wire manually:

```kotlin
// Pseudocode for :ui:theme; actual wiring lives in MVP Phase 2a Flight A
val request = ImageRequest.Builder(context).data(albumArtPath).allowHardware(false).build()
val result = imageLoader.execute(request)
val bitmap: ImageBitmap = (result as SuccessResult).image.toBitmap().asImageBitmap()
val palette = Palette.from(bitmap).generate()
```

`allowHardware(false)` is required — Palette needs CPU-readable pixels. (Same constraint AndroidX Palette has had since day one.)

- **Beta-status risk:** kmpalette 4.0.0 has been in beta since 2026-02-17 (~3 months). The stable line is the 2-year-old 3.1.0 (released 2024-01-30, before the recent Compose-MP maturation push). The 4.0.0 beta is on the active Compose-MP path; 3.1.0 is not the version we want.

**Candidate 2: jordond/MaterialKolor (evaluated, NOT a fit)**

- Same author, more popular (880 stars), more polished release cadence (4.1.1 stable Feb 2026, 5.0.0-alpha07 active)
- License: MIT
- **What it does:** generates a Material 3 tonal palette (50/100/200/.../900 ramps for primary, secondary, tertiary, neutral, etc.) from a **seed color**
- **What it does NOT do:** extract a seed color from an image. It assumes you already have one
- Kiln Dynamic per spec §5.3 is NOT a Material 3 tonal palette system — it's an album-art-driven palette with custom role mapping (`bg-base rotates X% toward dominant hue`, etc.). MaterialKolor's primary/secondary/tertiary tonal ramps are a different design language
- **Could be combined with kmpalette** (kmpalette → seed color → MaterialKolor → M3 tonal palette), but only if Kiln ever pivots toward Material 3 design language. **Not on the roadmap.** Keep MaterialKolor in the "consider later if M3 ever lands" bin

**Candidate 3: Roll our own pixel-sampling extractor (evaluated, NOT chosen for MVP)**

- ~150-300 lines of Kotlin in `:ui:theme/commonMain`: median-cut quantization → HSL classification → top-N swatch selection
- Pros: zero external dependency; Bus-Factor-of-One friendly; complete control over the algorithm; aligns with the "fresh re-derivation" project ethos
- Cons: re-implementing what AndroidX Palette already does correctly (and what kmpalette ports faithfully); swatch-role classification heuristics (Vibrant vs LightVibrant vs DarkVibrant) are non-obvious to get right; would absorb hours that could go to feature work (Architecture-as-Performance-Art pattern)
- **Verdict:** Reserved as a Phase 2a Flight A fallback if kmpalette 4.0.0 hasn't reached stable by then AND the 4.0.0-beta line shows API instability. Not a default choice.

**Candidate 4: Pal.Js port (mentioned in plan)**

Web search returns no current Kotlin-Multiplatform port of Pal.Js with active maintenance. The plan mention is historical/speculative. **Dismissed** — kmpalette covers the same problem space with active 2026 maintenance.

### Decision

**Adopt kmpalette 4.0.0-betaN as the Palette extractor for Kiln Dynamic theming.** Specifically:

- Pin the latest stable 4.0.0-betaN available at Phase 2a Flight A start time (currently 4.0.0-beta02; expect 4.0.0 stable or later beta by ~6-13 months from now per plan §1)
- Use only the `kmpalette-core` dependency — none of the loader extensions (we feed it ImageBitmap from Coil directly)
- Write WCAG AA contrast post-processing in-house in `:ui:theme/commonMain` (~50 lines of Kotlin: relative-luminance + contrast-ratio + iterative darken/lighten). This algorithm is fully deterministic and trivially testable; depending on a library for it would be over-engineering
- Use kmpalette's `Swatch.onColor` as the starting point for contrast-checked text; verify against our `text-primary` (#F5EBE0) and fall back to our own contrast post-processor if `onColor` doesn't clear WCAG AA

### Soft-lock revisit point

**Before MVP Phase 2a Flight A (Kiln Dynamic theming) starts.** Confirm:

1. Has kmpalette 4.0.0 reached stable? If yes → pin stable.
2. If still in beta after 6-13 more months, evaluate:
   - (a) Ship with the latest 4.0.0-betaN (the line has been actively maintained — beta-status alone is not disqualifying)
   - (b) Pin the 2024 stable 3.1.0 and accept whatever Compose-MP version constraints that imposes (likely incompatible with the Compose-MP we'll be on by then — likely NOT viable)
   - (c) Roll our own extractor per Candidate 3 (~16-24 hrs in Flight A — would extend Flight A by ~30-40%)

The decision criterion at revisit time is: "Has kmpalette's API moved during the beta cycle?" If the API has stabilized (no breaking changes since 4.0.0-beta01 → 4.0.0-betaN), pin the latest beta and ship. If the API has churned, the library is signaling instability and we should evaluate (c).

### Implementation notes for Phase 2a Flight A

**Module placement:** kmpalette dependency belongs in `:ui:theme/commonMain` — this is the only place in the codebase that should know about Palette extraction. `:ui:components` should consume already-computed `KilnDynamicPalette` data classes, not raw Swatches. Keeps the dependency contained to one module per the Concentric Modules invariant.

**Caching strategy:** Palette extraction on a 500×500 album art image takes ~50-150ms. Cache extracted palettes by track ID (or album ID) in memory. Library views with thousands of visible thumbnails should not re-extract — use a Flow-backed `palette-by-album-id` cache that survives recomposition. Disk-cache extracted palettes as a SQLDelight side-table if memory cache turns out to be insufficient for 39.5k albums (most likely fine — palette is ~50 bytes serialized × 3k albums = ~150KB cold).

**Fallback chain (per spec §5.4):**
1. Extraction success + WCAG AA clear → use extracted palette
2. Extraction success + WCAG AA fail (low-saturation art) → fall back to `accent-soft` (#8A4226)
3. Extraction failure (no art / decode error) → fall back to idle Kiln Warmth palette
4. Body text primary (`#F5EBE0`) is invariant — never re-tinted by Dynamic

### Status

**DECIDED with soft-lock revisit at Phase 2a Flight A start.** kmpalette is the path; specific version (stable 4.0.0 vs. 4.0.0-betaN vs. roll-our-own) confirmed at that revisit point. Actual adoption is many months away — MVP-1.0 ships with idle Kiln Warmth only (spec §6.1 confirms this).

---

## Item 6: SQLDelight schema sketch (pointer)

> **Plan reference:** Pre-MVP Research §2.1 item 6 + §2.2 exit criteria. Schema-sketch deliverable lives in a dedicated document per plan-mandated structure.

### Decision

**Adopt SQLDelight 2.3.2 stable (released 2026-03-16) for the library cache.** Schema sketch delivered in a dedicated document: [`2026-05-18-sqldelight-schema-sketch.md`](./2026-05-18-sqldelight-schema-sketch.md). This vetting-log entry is intentionally short — schema design is a multi-page artifact and belongs in its own file per plan §2.2.

Highlights covered in the schema sketch:

- **Six core tables:** `artist`, `album`, `track`, `playlist`, `playlist_track`, `listening_history`
- **FTS5 contentless virtual table** (`track_search`) for sub-100ms search across track / album / artist text, with `unicode61 remove_diacritics 2` tokenizer for international content
- **Application-managed FTS population** (transactions co-update FTS index alongside source rows; rejected SQL-trigger approach as harder to debug)
- **Partial B-tree indexes** filtered by `WHERE deleted_at_ms IS NULL` so soft-deleted rows don't bloat lookup paths
- **Soft-delete on `track`** preserves `listening_history` references when files move or disappear during rescan
- **AUTOINCREMENT PKs everywhere** so IDs never recycle and break historical references
- **All audiophile metadata stored** (codec, bit_depth, sample_rate_hz, bitrate_kbps, channels) for Phase 2b Hardware Spec Sheet identity move
- **ReplayGain columns preserved** (track + album, gain + peak) per JAMZ-parity requirement
- **`source` provenance column** future-proofs for additional `MusicSource` implementations without later migration
- **Foreign-key enforcement explicitly enabled per platform** (JVM `Properties { put("foreign_keys", "true") }`; Android via `Callback.onOpen` PRAGMA)
- **WAL journaling + memory temp store + 32 MB page cache** for concurrent read/write during library scan

Performance projection (full details in linked sketch): all hot queries land well under the sub-100ms target; total DB on disk for 39.5k tracks projects to ~35-40 MB.

### Soft-lock revisit triggers

- Pixel 10 Pro XL's bundled SQLite lacks FTS5 (very unlikely on API 21+) — sanity-check at MVP Session 4
- Xerial `sqlite-jdbc` desktop driver shows FTS5 perf regressions during MVP Session 8+ — fall back to non-FTS LIKE-based search (degraded but functional)
- Migration verification (`verifySqlDelightMigration`) catches schema drift during MVP Session 4-7

### Status

**DECIDED.** SQLDelight 2.3.2 pinned; schema document is the canonical deliverable; see linked file. Implementation begins at MVP Session 4-7 (Library + playback vertical slice).

---

## Item 5: Circuit + Molecule on KMP

> **Plan reference:** Pre-MVP Research §2.1 item 5. Circuit is the spec §6.1 / plan §3.2 Session 12-15 showcase library in the `:ui:components` Now Playing screen (relocated from cut `:data:streaming-tidal` module on 2026-05-18). Molecule is its sibling presenter-runtime library.

### Question

Vet Slack's Circuit (Presenter/UI MVI library) and Cash App's Molecule (Compose-to-Flow runtime) for Kiln's Compose Multiplatform stack — specifically the Now Playing screen showcase in `:ui:components`. Confirm KMP/Compose-MP support, gotchas, and pin specific stable versions.

### Method

- Context7 query against `/slackhq/circuit` (source: High, 451 snippets) and `/cashapp/molecule` (source: High, 58 snippets)
- WebFetch of Circuit's doc site (`slackhq.github.io/circuit/setup/`) for explicit KMP target table
- **Direct read of Slack's own `gradle/libs.versions.toml` via GitHub API** — most authoritative signal of what Slack actually uses for their own Compose-MP showcase project
- GitHub Releases API for both repos
- Sample-directory inventory to confirm Compose-MP samples exist

### Findings

**Compose-MP support — confirmed, both libraries:**

Circuit's doc site (`slackhq.github.io/circuit/setup/`) lists official KMP targets:
- ✅ Android
- ✅ JVM (Desktop)
- ✅ iOS
- ✅ JS

Cited gotchas:
- `Saveable` (state persistence helper) is a no-op on non-Android targets. **Affects Kiln's desktop target** — anything we wanted Compose `rememberSaveable` to survive across (e.g., desktop window resize? process restart?) needs explicit state restoration. For Now Playing this is mostly fine: the player itself owns playback state via `PlatformPlayer` (Item 13 decision), so saveable-state on the screen is mostly transient (scroll positions, animation state).
- JS-specific: `asEventSinkFunction()` required for event sinks. **Not relevant to Kiln** — no JS target.

Circuit's `samples/` directory on `main` confirms Compose-MP coverage: `star` (the canonical KMP demo), `bottom-navigation`, `kotlin-inject`, `interop`, `counter`, `tacos`, `tutorial`. The `star` sample is a multi-platform Wikipedia-style app exercising the full stack.

**Strong signal — Slack's own dependency choices in `gradle/libs.versions.toml`:**

Slack's stack closely mirrors Kiln's plan:

| Dep | Slack's pin | Kiln's plan |
|---|---|---|
| Coil | `3.4.0` | `3.4.0` (Item 2 ✓ exact match) |
| SQLDelight | `2.3.2` | `2.3.2` (Item 6 ✓ exact match) |
| Molecule | `2.2.0` | `2.2.0` (this Item) |
| Roborazzi | `1.60.0` | will be Item 7 pin candidate |
| JB Compose | `1.10.3` | will be Item 1 just-in-time scaffold-time pin |
| Kotlin | `2.3.21` | will be scaffold-time pin |
| kotlinx-coroutines | `1.11.0` | scaffold-time pin |
| kotlin-inject | `0.9.0` | matches spec §4 DI choice |
| Android compileSdk | `36` | matches spec §2 |
| JVM target | `11` | Kiln targets `21` (per CLAUDE.md hardware section) — newer, fine |

**This is independent corroboration of the entire Kiln library stack from the project that originates Circuit.** When a library's own author project converges on the exact same companion libraries, that's the strongest possible signal of pattern coherence.

**Version state:**

Circuit:
| Version | Date | Status |
|---|---|---|
| 0.33.1 | 2026-02-20 | **STABLE — pin this** |
| 0.33.0 | 2026-02-11 | superseded |
| 0.32.0 | 2026-01-14 | superseded |
| 0.31.0 | 2025-11-05 | older |

Molecule:
| Version | Date | Status |
|---|---|---|
| 2.2.0 | 2025-09-24 | **STABLE — pin this** |
| 2.1.0 | 2025-04-12 | older |
| 2.0.0 | 2024-05-28 | older |

**Maintenance signals:**
- Circuit: 1,835 stars; Apache 2.0; last push 2026-05-18 (today at session time); 18 open issues; 3 stable releases in last 6 months — actively developed
- Molecule: 2,191 stars; Apache 2.0; last push 2026-05-16 (2 days ago); 28 open issues; slower release cadence (~3-6 months between minors) but each release is well-considered. 2.2.0 stable since Sept 2025 suggests Molecule has reached API maturity

**License compatibility:** Both Apache 2.0 — clean alignment with Kiln's Apache 2.0 license.

**Sub-1.0 versioning caveat (Circuit specifically):**

Circuit is at `0.33.1`, no `1.0` tag yet despite 3+ years of public release. This means Slack reserves the right to make breaking changes between minor versions. The risk for Kiln:
- We pin `0.33.1` at MVP Session 1
- By MVP Session 12-15 (Now Playing showcase work, months later), Circuit may be at `0.35.x` with breaking changes
- Migration cost depends on what changes

Mitigation: pin Circuit at adoption time (MVP Session 12-15), not now. The version landscape will be clearer then. The library is past the "1.0 imminent" stage but the 0.33.x line is API-stable across patch versions.

**Architecture pattern — what we adopt:**

Circuit's Presenter pattern is purely composable functions or classes that produce a `CircuitUiState`:

```kotlin
// State + events as data classes / sealed interfaces
data class NowPlayingState(
    val track: Track?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val queueContext: QueueContext,
    val eventSink: (NowPlayingEvent) -> Unit
) : CircuitUiState

sealed interface NowPlayingEvent : CircuitUiEvent {
    data object PlayPause : NowPlayingEvent
    data class SeekTo(val positionMs: Long) : NowPlayingEvent
    data object SkipNext : NowPlayingEvent
    data object SkipPrevious : NowPlayingEvent
    // ...
}

// Presenter as composable function
@CircuitInject(NowPlayingScreen::class, AppScope::class)
@Composable
fun NowPlayingPresenter(
    player: PlatformPlayer,
    queueRepository: QueueRepository
): NowPlayingState {
    val state by player.state.collectAsState()
    val position by player.position.collectAsState()
    val queue by player.queue.collectAsState()
    // …
    return NowPlayingState(...) { event -> ... }
}

// UI as separate composable, takes only state
@Composable
fun NowPlayingUi(state: NowPlayingState) {
    // pure UI; events sent via state.eventSink(...)
}
```

This is the spec §4 "MVI pattern fits Now Playing's complex state space" architecture made concrete. Tests can run the Presenter in isolation against fake `PlatformPlayer` / `QueueRepository` flows — no Compose UI needed.

**Compile-time `@ComposableTarget("presenter")` enforcement:** Circuit annotates `Presenter.present` so the compiler emits a warning if Compose UI is emitted inside a Presenter. Enable `allWarningsAsErrors` in the Kotlin compiler options for `:ui:components` to make this a hard failure.

**Molecule's role and its desktop dispatcher question:**

Molecule converts a `@Composable` function into a `StateFlow<T>`. The Android-canonical setup is:

```kotlin
CoroutineScope(AndroidUiDispatcher.Main).launchMolecule(mode = ContextClock) {
    NowPlayingPresenter(...)
}
```

But `AndroidUiDispatcher` is Android-only. **For Kiln's desktop target, the pattern is to provide the equivalent for the JVM target.** Two paths:

| Path | Approach | Verdict |
|---|---|---|
| (A) `RecompositionMode.Immediate` on desktop | Use Immediate mode; recompose eagerly on every state change | Documented fallback; works without a frame clock; risk of redundant recompositions if state changes mid-frame |
| (B) Custom `BroadcastFrameClock` driven by an OS timer | Wire a 60Hz BroadcastFrameClock against a coroutine scope; gives frame-synced behavior | More work; matches Android quality of recomposition timing |

Recommendation: **start with `RecompositionMode.Immediate` on desktop**, profile during MVP Sessions 12-15 (Now Playing), upgrade to BroadcastFrameClock only if Immediate-mode recomposition causes visible jitter. For Now Playing specifically (track metadata + playback position updating ~10Hz), Immediate is almost certainly fine.

Actually, for Now Playing under Circuit, Molecule may not even be strictly needed — Circuit's Presenter is already a `@Composable` function and Circuit's own runtime handles the recomposition cycle when it's mounted via `CircuitContent`. Molecule is primarily useful when you want a StateFlow *outside* the Compose tree (e.g., for a notification or a media-session callback that wants the same state without participating in Compose). For Kiln's Now Playing, the `PlatformPlayer` already exposes `StateFlow` directly; the Presenter consumes those flows.

**Likely Kiln usage:**
- Circuit: yes, full adoption in `:ui:components` for Now Playing
- Molecule: optional. Probably not needed for the Now Playing showcase itself. Pinned in `libs.versions.toml` anyway because it pairs naturally with Circuit and may earn its keep elsewhere (e.g., MediaSession callbacks, lock-screen state)

`libs.versions.toml` additions (planned at MVP Session 1-3 scaffold, finalized at MVP Session 12-15 adoption):

```toml
circuit  = "0.33.1"      # confirm latest stable at MVP Session 12
molecule = "2.2.0"       # confirm latest stable at MVP Session 12

circuit-foundation = { module = "com.slack.circuit:circuit-foundation", version.ref = "circuit" }
circuit-runtime    = { module = "com.slack.circuit:circuit-runtime", version.ref = "circuit" }
circuit-codegen-annotations = { module = "com.slack.circuit:circuit-codegen-annotations", version.ref = "circuit" }
# circuit-codegen ksp processor added at MVP Session 12-15 if we want @CircuitInject codegen

molecule-runtime = { module = "app.cash.molecule:molecule-runtime", version.ref = "molecule" }
```

### Decision

**Adopt Circuit 0.33.1 for the Now Playing screen showcase in `:ui:components`.** Final pin at MVP Session 12-15 (the version landscape at adoption time may have moved; 0.33.x line is stable across patches).

**Adopt Molecule 2.2.0 as an opt-in companion**, pinned in `libs.versions.toml` but not necessarily wired into the Now Playing flow. Earn its keep in future modules (MediaSession glue, etc.) if a "Compose presenter outside the Compose tree" pattern emerges.

Key decisions:
- `allWarningsAsErrors = true` in `:ui:components/build.gradle.kts` to enforce `@ComposableTarget("presenter")` checks
- Use Circuit's Presenter-as-composable-function pattern (not the class form) for the Now Playing showcase — simpler, less ceremony, fits Bus-Factor-of-One readability
- Skip Circuit's optional `@CircuitInject` codegen at first; the Now Playing presenter is a single class so manual wiring through kotlin-inject is fine. Re-evaluate at MVP Session 12-15 if multiple Circuit presenters appear
- `Saveable` no-op-on-non-Android caveat: state restoration on desktop relies on `PlatformPlayer.state` flows and on persisting non-UI state via SQLDelight / settings, not on Compose `rememberSaveable`

### Soft-lock revisit triggers

- Circuit hits a major breaking change between adoption-time pin and MVP Session 12-15 work — fall back one minor version or absorb the migration
- Circuit's Presenter pattern proves heavier than the spec needs for Now Playing — drop it and use plain Compose state holders. Plan §4 anticipated this kind of soft-lock revisit ("Slack's recent activity on the libraries")
- Molecule never earns its keep across the project — drop the dep at any cleanup pass (it adds ~50KB to the bundle)

### Status

**DECIDED.** Both libraries pinned in spec; final version confirmation at MVP Session 12-15 adoption time. Circuit usage scoped to Now Playing showcase per spec §4 / plan §3.2.

---

## Item 7: Compose-MP screenshot testing (Roborazzi)

> **Plan reference:** Pre-MVP Research §2.1 item 7 + §7 test-infrastructure timeline (Phase 2a Flight A). First use case: Kiln Dynamic theming regression tests across diverse album art.

### Question

Verify Roborazzi handles Compose Multiplatform desktop screenshot testing, not just Android-with-Robolectric. Pin a specific version. Confirm there's no better-fit alternative.

### Method

- Context7 query against `/takahirom/roborazzi` (source: High, 310 snippets)
- GitHub Releases API for cadence and stability
- Cross-reference Slack's `gradle/libs.versions.toml` pin (`roborazzi = "1.60.0"`)
- Read Roborazzi's `docs/topics/compose_multiplatform.md` content via Context7 corpus

### Findings

**Compose-MP Desktop support is first-class:**

Roborazzi explicitly supports Compose Multiplatform Desktop AND Compose iOS targets, using the same `captureRoboImage` extension function on top of Compose UI Test's `runDesktopComposeUiTest`. From the docs:

```kotlin
class NowPlayingScreenshotTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun nowPlaying_renders_correctly() = runDesktopComposeUiTest {
        setContent {
            KilnTheme {
                NowPlayingUi(stateFixture)
            }
        }
        val opts = RoborazziOptions(
            recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0F)
        )
        onRoot().captureRoboImage(roborazziOptions = opts)
    }
}
```

This pattern works on JVM desktop tests without Android emulator or Robolectric. The reference Compose-MP doc page is `https://github.com/takahirom/roborazzi/blob/main/docs/topics/compose_multiplatform.md`.

**Caveat — `ExperimentalTestApi`:** `runDesktopComposeUiTest` is still annotated `@OptIn(ExperimentalTestApi::class)`. The Compose-MP team has had this API in "Experimental" status across multiple versions; the API surface is stable in practice but Compose-MP reserves the right to break it. Mitigation: explicit `@OptIn` on every screenshot test method; treat as conventional pattern.

**Version state:**

| Version | Date | Status |
|---|---|---|
| 1.61.0 | 2026-05-18 | **STABLE — latest; pin this** |
| 1.60.0 | 2026-04-28 | Slack pins this |
| 1.59.0 | 2026-02-11 | superseded |
| 1.58.0 | 2026-02-02 | superseded |
| 1.57.0 | 2026-01-23 | superseded |
| 1.56.0 | 2026-01-09 | superseded |
| 1.55.0 | 2026-01-06 | superseded |
| 1.54.0 | 2026-01-03 | superseded |

**Cadence is unusually fast** — 8 stable releases in the 4.5 months from 2026-01-03 to 2026-05-18. This means we expect ~1-2 minor versions between our pin time and Phase 2a Flight A (when screenshot tests actually land per plan §7). Plan to revisit at adoption time.

**Maintenance signals:**
- 945 stars; Apache 2.0; last push hours ago at session time (2026-05-19)
- 133 open issues (high — but fast-cadence libraries accumulate them; 8 releases in 4.5 months implies they're being resolved at speed too)
- Active maintainer (takahirom), well-known in the Compose ecosystem

**Architecture — three artifacts:**

```toml
roborazzi              = { module = "io.github.takahirom.roborazzi:roborazzi",            version.ref = "roborazzi" }
roborazzi-compose      = { module = "io.github.takahirom.roborazzi:roborazzi-compose",    version.ref = "roborazzi" }
roborazzi-junit-rule   = { module = "io.github.takahirom.roborazzi:roborazzi-junit-rule", version.ref = "roborazzi" }
```

For Kiln Compose-MP desktop, we need:
- `roborazzi` (core)
- `roborazzi-compose` (Compose UI extension functions: `captureRoboImage` on `SemanticsNodeInteraction`)
- `roborazzi-junit-rule` only if we want JUnit4 `@get:Rule` integration; otherwise the `runDesktopComposeUiTest` block-scoped DSL is sufficient

Roborazzi's Gradle plugin (`io.github.takahirom.roborazzi`) provides the `recordRoborazziDebug`, `verifyRoborazziDebug`, `compareRoborazziDebug` tasks. Per plan §6 CI matrix, these run in CI.

**Snapshot storage:**

Roborazzi stores PNG snapshots under `src/test/snapshots/` (or whatever the test source set is). For Kiln this means:
- `:ui:theme/src/jvmTest/snapshots/`
- `:ui:components/src/jvmTest/snapshots/`

These are checked into git. For Kiln's diverse-album-art theming tests (Phase 2a Flight A primary use case), this could mean dozens of small PNGs (~5-30 KB each), totally manageable.

**Alternative tools considered (and rejected):**

| Tool | Verdict |
|---|---|
| Paparazzi (Android-only, layoutlib-based) | Spec already cuts this — no Compose-MP support. Out. |
| Compose-MP's own `ui-test-junit4` raw artifact | Provides `runDesktopComposeUiTest`/`runComposeUiTest` but no image capture. Roborazzi extends this; you don't choose one OR the other. |
| Shot (Karumi) | Android-only. Out. |
| Maestro / Appium | E2E flow testing, not screenshot regression. Different problem; might earn its keep later for Phase 3 measurement-workflow E2E (plan §7) but not Item 7's bucket. |

There is no Roborazzi competitor that does Compose-MP Desktop screenshot regression as a first-class concern. The library has the niche to itself.

### Decision

**Adopt Roborazzi 1.61.0** (latest stable as of session 2 close-out). Pin in `libs.versions.toml` at MVP Session 1-3 scaffold time. Wire up at Phase 2a Flight A when Kiln Dynamic theming earns its keep per plan §7 ("Roborazzi screenshot tests for Kiln Dynamic theming … catches theme regressions across diverse album art").

**Test placement:**
- `:ui:theme/src/jvmTest/` — palette extraction + WCAG post-processing regression tests (Phase 2a Flight A primary)
- `:ui:components/src/jvmTest/` — stable composition screenshot tests for individual components (mini-player, EQ slider, Now Playing layout)
- Snapshots committed to git under `snapshots/` subdirectories

**Conventions to establish at Flight A:**
- Test fixtures (e.g., synthetic 500×500 album art images representing edge cases — high saturation, low saturation, monochrome, missing art) live in a shared `:ui:test-fixtures` module
- `RoborazziOptions(changeThreshold = 0F)` for theme tests (any pixel-level difference = regression); slightly looser thresholds for animation/transition tests
- `RoborazziOptions(resizeScale = 0.5)` to halve snapshot file sizes — sufficient for regression detection, halves the git footprint

### Just-in-time research deferred (before Phase 2a Flight A)

- Re-check Roborazzi version (cadence is ~monthly; expect 1.65.x-1.70.x by Flight A start)
- Confirm `@OptIn(ExperimentalTestApi::class)` is still required or if Compose-MP stabilized the API by then
- Decide whether to enable the Roborazzi Gradle plugin per-module or via a `build-logic` convention plugin (likely the latter — `kilnConventionScreenshotTests` plugin)

### Status

**DECIDED.** Roborazzi 1.61.0 pinned for `libs.versions.toml` at MVP Session 1-3 scaffold; actual screenshot-test code lands at Phase 2a Flight A per plan §7 timeline.

---

## Item 9: Java Sound capability survey on Windows

> **Plan reference:** Pre-MVP Research §2.1 item 9. Limits define MVP audio capability before WASAPI phase-2b (Phase 2b Flight I, soft-lock-revisit per Item 13).

### Question

What sample formats, sample rates, channel configurations, and latency profile does Java Sound (`javax.sound.sampled`) expose on Windows under JDK 21? What are the **limits** that define what MVP-1.0 can and cannot do audibly? And what FLAC decoder feeds PCM into the `SourceDataLine`?

### Method

- WebSearch for current JDK 21 Java Sound behavior on Windows
- WebSearch for FLAC decoder library options in the JVM/Kotlin ecosystem
- GitHub repo health checks on candidate decoders
- Cross-reference with spec §13 audio-backend abstraction (already committed to `JflacDecoderImpl` in `:jvmMain`)

### Findings

**Java Sound on Windows is a stable but old story.**

Java Sound is part of the JDK (no external dependency). On Windows, the JDK's audio SPI ultimately routes through the operating system's **MME/WaveOut** API (also known as Windows Multimedia). DirectSound is sometimes available as an alternative. **WASAPI is not used by Java Sound** — that's why Phase 2b Flight I exists.

**Sample-format support (typical Windows JDK 21):**

| Format dimension | Java Sound on Windows | Notes |
|---|---|---|
| Sample rates | 8 kHz, 11.025 kHz, 22.05 kHz, 44.1 kHz, 48 kHz, 88.2 kHz, 96 kHz, **possibly** 176.4 / 192 kHz | High-rate support depends on the audio driver. Pixel's USB-C-to-AUX dongle for Clay's desktop reports 96 kHz max per typical USB Audio Class 1.0 spec; check at MVP Session 4 if 192 kHz path is needed |
| Bit depths | 8-bit PCM unsigned, 16-bit PCM signed (universal), 24-bit PCM signed (usually OK), 32-bit float (driver-dependent) | 24/96 is the safe ceiling. 32-bit float output requires explicit `AudioFormat.Encoding.PCM_FLOAT` and a driver that accepts it |
| Channels | mono, stereo, multi-channel up to ~8 (5.1, 7.1 if the driver exposes it) | Kiln only needs stereo for FLAC playback |
| Encoding | PCM (signed/unsigned, big/little endian), A-law, μ-law | We only need PCM signed little-endian for our decoded FLAC bytes |

**Format probing is essential** — application code must use `AudioSystem.isLineSupported(Line.Info)` and iterate `Mixer.getSourceLineInfo()` rather than assume; drivers differ. Spec §13's decoder abstraction is the right shape for handling this — decoder produces a `DecodedStream` with a known `AudioFormat`, and the `PlatformPlayer` opens a `SourceDataLine` matching that format.

**Latency on Windows is high.**

The MME/WaveOut path imposes typical output latency of **30-100ms**, dominated by the OS audio mixer's buffering. This is **acceptable for music playback** (the use case is non-interactive — no live monitoring, no real-time mixing requirements) but **disqualifies** Java Sound for:
- Phase 3 room correction (sample-accurate mic-to-speaker latency measurement) — though room correction is more about the mic-capture latency
- Any future live-mixing/instrument plugin direction (out of scope per anti-roadmap)
- Bit-perfect audiophile output (the OS mixer can resample / down-bit-depth without telling you)

This latency reality is **exactly why** Phase 2b Flight I (WASAPI shared-mode or exclusive-mode) exists in the plan. Soft-lock revisit per Item 13.

**Lifecycle and reliability:**

- `SourceDataLine.start()` / `.write(byteArray, offset, length)` / `.drain()` / `.close()` is the canonical playback flow
- Buffer underrun on `.write()` causes audible clicks; mitigate by writing in 10-50ms chunks via a dedicated decoder thread that's always 100-200ms ahead of playback
- Audio device disconnection (USB DAC unplugged) raises a `LineUnavailableException` — must be caught and surfaced via the `PlatformPlayer.state` flow (`PlayerState.Error` or transition to paused; surface a user-visible event)
- JVM-level audio bug history (mostly resolved by JDK 11+) included occasional missed device-change events; modern JDKs (17+) are stable here

### FLAC decoder candidates (the actual MVP gap)

Java Sound doesn't decode FLAC — it consumes raw PCM. Kiln's `JvmFlacDecoderImpl` per spec §13 needs an actual implementation. **This is the real Pre-MVP Research-9 gap; Java Sound output itself is well-trodden.**

| Candidate | Maintenance | 24-bit / hi-res support | License | Verdict |
|---|---|---|---|---|
| `jflac` (`org.jflac:jflac-codec`) | Unmaintained (>10 years) | NO — does not support 24-bit / 192 kHz | LGPL-2.1 (license concern with Apache 2.0 binary linking — likely OK as separable lib, but verify) | **Disqualified**. Clay's hi-res FLAC library would have decoder gaps |
| `JustFLAC` (drogatkin) | Stale (last commit 2023-05-09; 22 stars; no LICENSE file in repo) | Claims fixes to jflac bugs, but 24-bit support not clearly documented | Unstated — **dealbreaker** for Apache 2.0 fresh-derivation; would need to ask author or fork-with-explicit-license | **Risky**. License unclear blocks adoption |
| `hipxel/flac-decoder` | Dead (last touch 2021, 4 stars, no LICENSE) | Unknown | Unstated | **Disqualified** |
| `JAVE2` (FFmpeg wrapper) | Active | Yes (FFmpeg has everything) | LGPL via FFmpeg | Heavy (bundled native binaries, ~50 MB); FFmpeg license complexity. **Heavy hammer** |
| `VLCJ` (libvlc wrapper) | Active | Yes | LGPL / GPL portions | Requires VLC installed on user's system; not appropriate for a self-contained app. **Disqualified** for Kiln |
| **JNI to native libflac** (Xiph reference) | Reference implementation; bulletproof | Yes (full FLAC spec) | BSD-style | Requires per-platform native artifacts (libFLAC.dll for Windows). Adds Gradle build complexity. **Strongest correctness option but biggest plumbing cost** |
| **Pure-Kotlin FLAC decoder, re-derived from spec** | Bus-Factor-of-One friendly; matches "fresh re-derivation" project ethos | Yes (FLAC spec is well-documented) | Apache 2.0 (we own it) | ~1500-3000 lines of Kotlin; real engineering effort | **Out of scope for MVP** but interesting Phase 2b candidate |

**No clean default.** The cleanest paths are:

- **MVP path:** JNI to native libflac via a tiny Kotlin wrapper. The native `libflac.dll` (Windows) and `libflac.so` (Linux, if ever needed) ship inside the JAR (loaded via `System.loadLibrary` after extraction to temp). On Android, Media3 already has FLAC support — no native shim needed in `androidMain`. Effort: ~10-15 hrs for the JNI bridge + Gradle wiring, vs. an indeterminate "stop and fix decoder bugs as Clay encounters them" path with JustFLAC.
- **Alternative MVP path:** **Pin JustFLAC anyway** for MVP-1.0 with explicit acknowledgment that the licensing is murky and that we'll switch decoders if any of Clay's actual library files fail to decode correctly. Risk: violating license; risk: encountering files JustFLAC mis-decodes. Cheaper start (no JNI).

**Recommendation:** start with **JustFLAC under explicit "MVP-only, license-conditional" pin** to keep MVP scope tight, and plan for **JNI-libflac swap-in at Phase 2a or Phase 2b** when the audiophile-credibility costs of using an unmaintained decoder catch up. Re-evaluate at MVP Session 4-7 with empirical testing against Clay's actual library.

The decoder swap is contained by spec §13's `JvmFlacDecoderImpl` abstraction — only `:audio:playback/src/jvmMain/` knows which decoder is wired in. Everything else consumes `DecodedStream`.

### Decision

**Java Sound is OK for MVP output, with explicit acceptance of:**

1. **30-100ms output latency** — sufficient for music playback; disqualifies Java Sound for sample-accurate-latency Phase 3 room correction (which is why mic-capture path lives in `:audio:playback` with `TargetDataLine` for now, and Phase 2b Flight I exists)
2. **OS-mixer transparency loss** — Windows MME applies sample-rate conversion + volume mixing before the audio reaches the DAC. Not bit-perfect. Phase 2b Flight I addresses this
3. **Driver-dependent format ceiling** — high-rate (>96 kHz) and 32-bit float support varies. Probe via `AudioSystem.isLineSupported` at runtime; fall back to 24/96 stereo signed PCM as the universally-safe format
4. **Audio device-change handling** — `LineUnavailableException` on USB-DAC disconnect; the `PlatformPlayer.state` flow must surface this

**FLAC decoder decision deferred to MVP Session 4-7** with two viable paths (JustFLAC license-conditional vs. JNI-libflac) and a planned-swap soft-lock at Phase 2a / 2b.

### Just-in-time research deferred (before MVP Session 4-7)

- **Empirically probe Clay's Windows desktop** with `AudioSystem.getMixerInfo()` and enumerate supported sample rates + bit depths on his specific hardware (i5-13400F + RTX 4060 + onboard audio + whatever USB DAC) — single hour of code+log work
- **Empirically probe Pixel 10 Pro XL's USB-C-to-AUX dongle** for its USB Audio Class profile (96 kHz max is typical for UAC 1.0; 192 kHz needs UAC 2.0) — single hour of `adb shell dumpsys media.audio_flinger` work
- **FLAC decoder spike on Clay's library:** decode 10 random tracks with JustFLAC and verify against reference (e.g., compare PCM output to `ffmpeg -i file.flac -f f32le -`) — 1-2 hrs to confirm or disqualify JustFLAC
- **Resolve JustFLAC license question:** open a GitHub issue against drogatkin/JustFLAC asking for explicit LICENSE; if no response in 2 weeks, fork-and-relicense or move to JNI-libflac

### Soft-lock revisit triggers

- Any of Clay's hi-res FLAC files fail to decode correctly with JustFLAC at MVP Session 4-7 → switch to JNI-libflac immediately
- License clarification for JustFLAC is unresolvable → switch to JNI-libflac before Phase 2a Flight E (Library extraction) since `:audio:playback` should not transitively impose murky-license deps on published downstream libraries
- Phase 2b Flight I decision favors WASAPI rewrite → decoder choice may carry over or change (WASAPI consumes the same `DecodedStream`)

### Status

**PARTIALLY DECIDED.** Java Sound is the MVP output path with known limits documented. **FLAC decoder remains an open question** — clear MVP-Session-4 decision needed between JustFLAC (cheap-start, risk) and JNI-libflac (heavier plumbing, correctness ceiling). Spec §13 `JvmFlacDecoderImpl` abstraction contains the decision.

---

## Item 10: jpackage / Windows distribution

> **Plan reference:** Pre-MVP Research §2.1 item 10. Defines what shipping the Windows Desktop binary actually looks like — portable, installer, signing, etc.

### Question

How should Kiln distribute the Windows Desktop build? Specifically: (a) jpackage output options (MSI vs EXE installer vs portable app-image vs MSIX), (b) what code signing actually requires in 2026, (c) which path matches Kiln's MVP+phase-2 trajectory.

### Method

- WebSearch for current jpackage state under JDK 21 and Compose Multiplatform's `nativeDistributions` Gradle DSL wrapper
- WebSearch for 2026 Windows code-signing requirements (hardware token, EV cert, SmartScreen reputation)
- Cross-reference with Compose Multiplatform's official "Native distributions" doc

### Findings

**Compose Multiplatform wraps jpackage via Gradle DSL — `compose.desktop.application { nativeDistributions { … } }`.**

Canonical block (from JetBrains docs):

```kotlin
compose.desktop {
    application {
        mainClass = "com.clayworks.kiln.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Kiln"
            packageVersion = "1.0.0"
            vendor = "Clay Haworth / Clayworks"
            copyright = "Copyright (c) 2026 Clay Haworth. Licensed Apache 2.0."
            windows {
                console = false
                perUserInstall = true   // no admin elevation required at install
                shortcut = true          // Start Menu + desktop shortcut
                menu = true              // Add to Windows menu group
                upgradeUuid = "..."      // stable UUID for upgrade detection
            }
        }
    }
}
```

Task: `./gradlew packageDistributionForCurrentOS` produces all configured `targetFormats`.

**Output options compared:**

| Format | What it produces | Tradeoffs for Kiln |
|---|---|---|
| `TargetFormat.AppImage` | Portable directory bundle (zipped exe + bundled JRE; no install needed; user just runs it) | Easiest dev-loop output; no admin install; no auto-update; no Start menu entry. **First choice for MVP dogfooding** |
| `TargetFormat.Msi` | Real Windows installer (.msi); registers in Programs & Features; supports file associations and shortcuts; per-machine or per-user | Requires WiX Toolset 3.x installed on the build machine (free; download from `wixtoolset.org`). Real install experience. **Choice for shipping** |
| `TargetFormat.Exe` | NSIS-style EXE installer wrapping the same payload | Smaller than MSI; same dev-experience as AppImage but with install wizard. Less IT-friendly than MSI |
| **MSIX** (NOT directly supported by jpackage) | Modern Windows app package format; auto-update via update services; sandboxed | **Out of scope.** jpackage does not produce MSIX as of JDK 21. Would require separate MSIX Packaging Tool step. Microsoft Store path. Not aligned with Kiln's "GitHub-released binary" approach |

**Code-signing reality in 2026:**

- **Standard code signing certificate** is sufficient to sign MSI and EXE installers — **NOT** EV required for the signing itself
- **HOWEVER:** Certificate Authorities now require **hardware token storage** (USB key or HSM) for newly-issued code signing certs (CA/Browser Forum policy change ~mid-2023, fully enforced by 2026). This affects:
  - Local signing: needs the hardware token physically attached to the dev machine (DigiCert, Sectigo, SSL.com all ship USB tokens)
  - CI signing: needs a cloud HSM (Azure Key Vault, DigiCert KeyLocker, etc.) — extra complexity
- **EV (Extended Validation) certificate** is needed to *immediately* bypass Microsoft SmartScreen's "Windows protected your PC" warning. Non-EV certs build reputation over downloads/time (typically takes weeks-to-months of downloads to clear)
- **Timestamping is mandatory** — sign with `signtool sign /tr <RFC-3161-timestamp-server> /td sha256 /fd sha256 ...` so the signature remains valid after the cert expires
- Costs in 2026:
  - Standard code signing cert + USB token: ~$300-500/year (DigiCert, SSL.com, Sectigo)
  - EV cert + token: ~$300-700/year (EV is more about validation rigor than ongoing cost)
  - Self-signed cert: free but produces persistent SmartScreen warnings — fine for Clay's own dogfooding, **not fine for any user who isn't Clay**

**Build-side dependencies:**

- **WiX Toolset 3.x** required on the build machine for MSI output. Free; install via Chocolatey (`choco install wixtoolset`) or installer from `wixtoolset.org`. Set `WIX_HOME` env var so jpackage finds it
- **JDK 21 with jpackage on PATH** — already a hardware/JDK requirement per CLAUDE.md
- For signing: signtool.exe from Windows SDK; or use `jsign` (Java implementation of Authenticode — pure-Java alternative, no Windows SDK install needed) — `jsign` by ebourg is well-maintained and useful for CI on Linux runners that need to sign Windows binaries

**JRE bundling — what ships:**

jpackage uses `jlink` under the hood to produce a stripped minimal JRE that's bundled into the distribution. Typical Compose-MP desktop app weighs in around **60-90 MB compressed** (much of which is the JRE + Skia native libs for Compose rendering). Compose-MP's `nativeDistributions` block handles `modules` configuration:

```kotlin
nativeDistributions {
    modules("java.sql", "java.naming")  // anything Kiln transitively needs from JDK modules
}
```

For Kiln, the modules list will populate at MVP Session 1-3 once we know what the dependency graph pulls in.

### Decision

**Kiln Desktop ships through `compose.desktop.application` with `targetFormats(TargetFormat.AppImage, TargetFormat.Msi)`.**

Per-phase progression:

| Phase | Output formats | Code signing |
|---|---|---|
| MVP-1.0 dogfooding | AppImage only | **No signing.** Clay runs Kiln on his own desktop; SmartScreen warning is one-time-OK. Saves ~$300-500/year |
| MVP-1.0 ship to GitHub Releases | AppImage + MSI | **No signing.** Public binary downloads expected to be near-zero; absorb the SmartScreen friction for the rare downloader |
| Phase 2a Flight E (`v1.1.0-jamz-parity`) | AppImage + MSI | **Optional standard code signing.** Re-evaluate based on actual downloads + Clay's appetite for the cert annual fee. If Kiln picks up users beyond Clay, sign |
| Phase 2b Flight G (library extraction) | AppImage + MSI | **Same as Phase 2a.** Code signing decision driven by user count, not arbitrary milestone |
| Phase 3 differentiator | AppImage + MSI | **Same.** EV cert only if SmartScreen warnings become a real friction point |

**Key choices:**

- `perUserInstall = true` in the windows block — no admin elevation required; install path is `%LOCALAPPDATA%\Kiln`
- `shortcut = true`, `menu = true` — proper Windows app experience
- `upgradeUuid` set at MVP Session 1-3 (deterministic UUID, never changed across versions) — enables in-place upgrade detection by MSI
- `console = false` — Kiln is a GUI app, no console window
- MSIX **not pursued** at any phase (not on Microsoft Store roadmap; sideload signing pain not worth it for this project)

### Just-in-time research deferred (before MVP Session 26-28)

- Install WiX Toolset on the build machine and verify `packageMsi` task produces a working MSI on Clay's i5-13400F
- Decide between `jsign` (pure-Java; works on any OS) vs. `signtool.exe` (Windows SDK; native Windows tool) — only matters when signing actually starts
- Confirm Compose-MP `nativeDistributions` DSL hasn't broken on the Compose-MP stable version pinned at MVP Session 1
- Generate the stable `upgradeUuid` (e.g., from `kiln-windows-upgrade` namespace UUIDv5) and commit it as a constant

### Soft-lock revisit triggers

- Kiln gets unexpected user adoption → revisit code signing immediately to avoid SmartScreen warnings deterring downloads
- Microsoft tightens Windows publisher requirements (e.g., requires EV for all installers) → reactive signing setup
- A user reports "Windows won't let me run Kiln" → file the SmartScreen reputation curve as a real concern

### Status

**DECIDED.** Compose Multiplatform `nativeDistributions` wraps jpackage; AppImage + MSI is the output mix; no code signing at MVP (acceptable SmartScreen friction for Clay's dogfooding scope). Re-evaluate signing decision when actual downloads materialize beyond Clay.

---

## Item 12: Compose MP Desktop LazyColumn 40k stress test (research bound)

> **Plan reference:** Pre-MVP Research §2.1 item 12 (added 2026-05-18 post-Gemini critique). Gemini flagged this as a Desktop-Compose blind spot — JetBrains's official benchmarks go to ~12k items; Kiln's library is ~3× that.

### Question

Will a pure `LazyColumn` rendering 39,500 song rows on Compose Multiplatform Desktop scroll without stutter at 60fps on Clay's i5-13400F + RTX 4060? Or do we need custom virtualization / paged-loading / section-based grouping in the architecture from MVP Session 8 onward?

### Method — what this entry can and cannot answer

**This research entry sets the bounds and mitigation strategies. The actual perf answer requires an empirical spike, which is explicitly deferred per plan §2.1 item 12 ("requires actual code spike").** The spike runs at MVP Session 1-3 scaffold time, just after the empty `:app-desktop` module compiles and BEFORE MVP Session 8 (first library UI work).

Method for the research bound:
- Cross-reference session 1's Item 1 findings on JetBrains's official Compose-MP `LazyList` / `LazyGrid` benchmarks
- WebSearch for community benchmarks at >12k items
- Synthesis of LazyColumn architectural model (what it actually virtualizes and what it doesn't)

### Findings — what we know without running the spike

**Compose's LazyColumn virtualization model:**

`LazyColumn` only composes, measures, and draws items that are currently visible (plus a small over-scroll buffer of ~1-2 screens-worth). For 40k items:
- **Render cost**: O(visible items), unaffected by list length. Typically ~10-20 rows visible at a time → render cost is constant
- **Composition cost on scroll**: each new row that enters the buffer triggers composition. With `items(list, key = { it.id })`, composition reuses existing slot tables across scroll
- **State / data overhead**: the 40k-item `List<Track>` itself sits in memory — ~16 MB for the track rows per our SQLDelight schema sketch (which is acceptable; Clay's 32 GB RAM has headroom)
- **Index list overhead**: the LazyList itemProvider tracks 40k indices internally — ~few hundred KB of overhead

**The actual perf concerns:**

1. **First-load: building the `List<Track>` in memory.** Loading 40k rows from SQLDelight + materializing into a Kotlin `List<Track>` takes maybe 100-300ms on Clay's hardware. Acceptable for a one-time load; consider lazy-loading via `LazyPagingItems`-style paging if the song-list-open feels slow.

2. **Cold-scroll composition.** When the user scrolls into a region that hasn't been composed yet (e.g., jumping from "A" to "Z" via the scrollbar), Compose has to instantiate maybe 30-50 new row composables. Should be <16ms (one frame at 60fps) per the composition cost per row.

3. **Hot-scroll composition reuse.** Scrolling back through previously-composed rows triggers composable reuse — fastest path. If row composables are simple (title + artist + duration text + small art thumbnail), reuse should be invisible.

4. **Item key strategy.** **Critical**: every `items()` call MUST pass `key = { it.id }` (the SQLDelight track id). Without keys, every scroll change forces full re-composition of all visible items because LazyColumn can't diff. With keys, items move smoothly during sort changes.

5. **State holders inside row composables.** Anything `remember { }`'d inside a row composable lives or dies with that row's slot in the LazyList. Avoid heavy `remember` calls inside rows; lift state holders up to the screen-level state.

6. **Mouse-wheel fling characteristics on desktop.** Desktop Compose uses different scroll physics than Android touch scroll. Mouse wheel produces discrete tick events; trackpad and touchscreen on convertibles produce continuous events. Compose-MP 1.10+ handles all three; fling animation tuning may need tweaks but is not a perf concern per se.

**Compose-MP's own benchmarks (per session 1 Item 1):**

- `LazyList` benchmark exercises complex Compose-LazyColumn patterns
- `LazyGrid` benchmark tests **12,000 items** — the documented upper bound JetBrains exercises
- JetBrains maintains regression-detection tooling that bisects perf across Compose-MP versions

**Community signal (WebSearch synthesis):**

No prior art for explicit "40k-item Compose-MP LazyColumn" perf reports surfaced in 2026 search results. The benchmark gap is genuine — Kiln's spike will produce data the community may want.

### Mitigation strategies — what to architect for IF the spike shows stutter

The library UI architecture in MVP Sessions 8-11 must remain swappable between the three patterns below:

**Mitigation A: Paged loading (DEFAULT recommendation)**

Even if a pure-LazyColumn handles 40k rows fine, **paged loading is the more architecturally honest path** because:
- Initial-load latency stays low (load 100-500 rows first, page on scroll)
- Memory pressure stays low (only visible+buffer rows in memory)
- Future-proofs for if Clay's library grows beyond 40k

Implementation: SQLDelight `LIMIT ? OFFSET ?` query, wrap in a custom `LazyPagingItems`-style buffer. Compose-MP doesn't yet have an official `androidx.paging.compose` equivalent on JVM, but the pattern is straightforward (~100 lines of Kotlin in `:ui:components`).

**Mitigation B: Sectioned grouping**

For alphabetical-sort views, break the list into 26 section-LazyLists (A, B, C, ...). Each section is ~1500 items max. Each section has its own internal LazyColumn under a sticky header. Compose handles this pattern well.

Trade-off: sticky-header sectioning is the JAMZ-parity "sectioned search" UX (Phase 2a Flight D) anyway, so this mitigation aligns with the planned UI direction.

**Mitigation C: Custom Modifier.layout virtualization**

Hand-rolled virtualization that bypasses LazyColumn entirely. Build a `Modifier.layout` that measures only what's visible and skips the rest. Highest control, biggest code-debt; only justified if A+B prove insufficient.

**Mitigation D: Switch to non-Compose view**

Embed a native `JTable` (Swing) inside Compose-MP. Defeats the purpose of Compose-MP unification. **Disqualified.**

### The spike itself — what it must measure

When the spike runs (MVP Session 1-3 scaffold or just after):

1. **Test harness setup:** synthetic dataset of 40,000 `Track` objects (random titles, artists, durations) sitting in memory
2. **Naive case:** plain `LazyColumn { items(tracks, key = { it.id }) { TrackRow(it) } }` with a simple row composable (artwork thumbnail + 2 text rows)
3. **Measurements:**
   - **Cold scroll** from index 0 to index 39,999 via programmatic `LazyListState.scrollToItem(...)` jumps, capturing frame-time histogram
   - **Hot scroll** repeating the same scroll path, measuring composition reuse
   - **Mouse-wheel scroll** at 3 different speeds — slow, medium, fast — measuring dropped frames
   - **Memory steady-state** at idle and during scroll, via `Runtime.totalMemory() - Runtime.freeMemory()`
   - **Frame-time histogram** captured via Compose's `Choreographer`-equivalent or simple `System.nanoTime()` deltas in a `LaunchedEffect`
4. **Exit criteria:**
   - **Pass:** 95th percentile frame time <16.6ms during hot scroll AND 99th percentile <33ms (single dropped frame OK occasionally; persistent jank not OK). Mitigation A (paged loading) is still recommended for architectural cleanliness but not perf-mandated
   - **Marginal:** 95th percentile 16-33ms — proceed with Mitigation A (paged loading) MVP; reassess at Phase 2a
   - **Fail:** Persistent stutter or 95th percentile >33ms — adopt Mitigation A OR B from Day One of MVP Session 8

### Decision

**Plan for Mitigation A (paged loading) regardless of spike result** — the architectural cleanliness wins are real and the LIMIT/OFFSET pattern in SQLDelight is what we'd build anyway for memory-efficient browsing. The spike's role is to tell us **whether we can also support a "view all" un-paged sort** (rare) or whether even that path must page.

**Spike timing: MVP Session 1-3 (during scaffold), AFTER the empty `:app-desktop` module compiles but BEFORE the songs-list UI work in MVP Session 8.** Maximum ~2 hours; throwaway code; result documented in a follow-up entry to the vetting log.

**No library version pin emerges from this item** — Compose-MP version is settled in Item 1 (scaffold-time decision); LazyColumn is part of `compose-foundation`. Item 12 is purely an architectural-mitigation question, not a dependency question.

### Just-in-time research deferred (THE spike)

- Run the spike code at MVP Session 1-3
- Document results as a new vetting-log entry (Item 12-spike-results)
- Adjust MVP Session 8 plan based on results — paged loading is the planned baseline regardless

### Soft-lock revisit triggers

- Spike results FAIL → Mitigation B (sectioned grouping) gets formal architecture call-out before MVP Session 8
- Spike results PASS but library grows beyond 40k → revisit paging design at Phase 2a
- Compose-MP releases a regression that breaks LazyColumn perf at this scale → file upstream issue + temporary pin to known-good version

### Status

**RESEARCH-BOUNDED.** Architectural mitigations identified and chosen (paged loading as default). **Spike still required at MVP Session 1-3** to confirm exit criteria for the un-paged "view all" fallback. Per plan §2.1 item 12 acknowledgment, this is the expected end-state for this item at Pre-MVP.

---

## Next items in queue

✅ **All 12 Pre-MVP Research items now decided or research-bounded.** Pre-MVP Research §2.2 exit criteria fully satisfied (subject to Clay's review).

Outstanding empirical follow-ups (not Pre-MVP blockers, but scheduled into MVP):

- **MVP Session 1-3:** Item 12 LazyColumn 40k spike (~2 hrs); generate stable `upgradeUuid` for jpackage; install WiX Toolset; confirm Compose-MP version against Item 1 just-in-time check
- **MVP Session 4:** verify Coil 3.4.0 still supports `minSdk = 21`; verify Pixel 10 Pro XL SQLite has FTS5; empirically probe Clay's hardware for Java Sound format support
- **MVP Session 4-7:** FLAC decoder choice (JustFLAC license-conditional vs JNI-libflac) — empirical test against 10 of Clay's tracks
- **MVP Session 23:** Windows SMTC library/binding decision (Item 11 deferred research)
- **Phase 2a Flight A:** kmpalette 4.0.0 stability check; Compose-MP `ExperimentalTestApi` status for Roborazzi

---

## Session 2 summary

**Date:** 2026-05-18
**Items completed:** 8 of 12 active (Items 2, 3, 5, 6, 7, 9, 10, 12) → cumulative **12 of 12** with sessions 1+2
**Items remaining:** **none** — Pre-MVP Research §2.2 exit criteria are satisfied

**Soft-lock revisits triggered:** None outright. Three known soft-lock revisit points scheduled:
- kmpalette 4.0.0 stability before Phase 2a Flight A (Item 3)
- AAudio MMAP / WASAPI vs ExoPlayer/Java Sound at end of Phase 2a (Item 13, carried from session 1)
- LazyColumn 40k mitigation choice based on MVP Session 1-3 spike (Item 12 — paged loading is the default regardless)

**Cross-references established this session:**
- Coil 3 (Item 2) ↔ kmpalette (Item 3): Coil decodes ImageBitmap, kmpalette consumes it; integration wired manually
- SQLDelight (Item 6) ↔ kmpalette (Item 3): palette cache may use SQLDelight side-table at Phase 2a Flight A if memory cache insufficient
- Circuit (Item 5) ↔ Slack's stack: independent corroboration of Coil 3.4.0, SQLDelight 2.3.2, Molecule 2.2.0, Roborazzi 1.60.0, kotlin-inject, Compose-MP — same companion choices Kiln planned independently
- Java Sound (Item 9) ↔ FLAC decoder gap: actual MVP gap is decoder choice, not Java Sound itself; JustFLAC license-conditional vs JNI-libflac
- jpackage (Item 10) ↔ Compose-MP `nativeDistributions` DSL: jpackage is wrapped; no direct jpackage CLI usage planned
- LazyColumn (Item 12) ↔ Paged loading: SQLDelight `LIMIT/OFFSET` is the architectural default regardless of spike result

**Pre-MVP Research effort tally:**

Session 1 (Items 13, 1, 4, 11): ~9-13 hrs estimated; ~8 hrs actual
Session 2 (Items 2, 3, 5, 6, 7, 9, 10, 12 + the SQLDelight schema-sketch file): ~13-19 hrs estimated; ~6 hrs actual (Context7 + GitHub API automation paid off)

**Total Pre-MVP Research:** ~22-32 hrs estimated per plan §2; ~14 hrs actual. Under plan by 8-18 hrs.

**Just-in-time follow-ups carried into MVP:**

| When | What |
|---|---|
| MVP Session 1-3 (scaffold) | LazyColumn 40k spike (Item 12); generate `upgradeUuid` for jpackage (Item 10); install WiX Toolset; confirm Compose-MP version + Kotlin version against scaffold |
| MVP Session 4 | Confirm Coil 3.4.0 minSdk=21 (Item 2); verify Pixel 10 Pro XL SQLite has FTS5 (Item 6); empirically probe Clay's Windows hardware for Java Sound format support (Item 9) |
| MVP Session 4-7 | FLAC decoder choice empirical test against 10 of Clay's tracks (Item 9); JustFLAC license resolution attempt |
| MVP Session 23 | Windows SMTC library/binding decision (Item 11, carried from session 1) |
| Phase 2a Flight A | kmpalette 4.0.0 stability check (Item 3); Compose-MP `ExperimentalTestApi` status for Roborazzi (Item 7) |
| End of Phase 2a | Phase 2b Flights H+I commitment (Item 13, carried from session 1) |

**Decision-document inventory:**
- [`./2026-05-18-library-vetting.md`](./2026-05-18-library-vetting.md) — this file; all 12 vetting items
- [`./2026-05-18-sqldelight-schema-sketch.md`](./2026-05-18-sqldelight-schema-sketch.md) — Item 6 deliverable per plan §2.2 exit criteria

**Pre-MVP Research §2.2 exit criteria check:**

- ✅ Decision log committed at `docs/decisions/2026-05-18-library-vetting.md`
- ✅ Specific library versions identified for all 12 active items
- ✅ Soft-lock revisits flagged with rationale (Voyager confirmed; audio-backend abstraction; kmpalette beta-vs-stable; LazyColumn paged-loading default)
- ✅ Schema sketch committed at `docs/decisions/2026-05-18-sqldelight-schema-sketch.md`
- ✅ Audio backend architecture decision (Item 13) explicitly recorded — engine-swap boundary planned in MVP
- 🔲 **Clay's review + acknowledgment before scaffolding starts** — this is the next gate

---

## Item 9 follow-up: FLAC decoder deep-dive (session 2 addendum)

> **Plan reference:** Item 9 above marked the FLAC decoder decision as PARTIALLY DECIDED with two viable paths (JustFLAC license-conditional vs JNI-libflac). This addendum revisits with a wider survey and a narrower recommendation.

### Question

Is there an Apache-2.0-compatible, hi-res-capable, actively-maintained pure-JVM FLAC decoder we missed in the initial Item 9 pass? If not, what's the cleanest path to JNI-libflac?

### Method

- Wider Maven Central + GitHub search for any Kotlin/Java FLAC decoder library
- Repo-health checks via GitHub Releases API on every candidate
- License verification (FLAC reference impl, candidate decoders, JNA)
- Cross-reference with how JVM music players (Subsonic / Airsonic / Jellyfin) actually handle FLAC (spoiler: they don't — they transcode via subprocess)

### Findings — the complete candidate matrix

| Candidate | License | Hi-res (24/96+) | Maintenance | Effort | Verdict |
|---|---|---|---|---|---|
| **jflac** (`org.jflac:jflac-codec`) | LGPL-2.1 | **NO 24-bit** | Dead 10+ years | trivial | **DISQUALIFIED** — missing capability + LGPL concern for fresh-derivation |
| **JustFLAC** (drogatkin fork) | **Unstated (no LICENSE file)** | Unverified 24-bit claim | 3 years stale (last 2023-05-09) | trivial | **DISQUALIFIED on license risk alone** for a portfolio-public Apache 2.0 project |
| **nayuki/FLAC-library-Java** (NEW FIND) | **GPL-3.0** | **YES — 4 to 32 bit, all sample rates, all block sizes, seek tables** | Last commit 2022-09-12 (dormant but feature-complete) | trivial | **DISQUALIFIED by license incompatibility** with Kiln's Apache 2.0. Technically the best Java decoder available; copyleft blocks adoption |
| **nguillaumin/jflac fork** | Unstated ("NOASSERTION") | Same jflac codebase | Dead since 2017-05-29 | trivial | **DISQUALIFIED** |
| **hipxel/flac-decoder** (C + Kotlin bindings) | Unstated | Unknown | Dead since 2021-04-17, 4 stars | medium | **DISQUALIFIED** |
| **javaFlacEncoder** | LGPL-2.1 | n/a — encoder only | active-ish | n/a | **N/A** (encoder not decoder) |
| **korau** (pure-Kotlin audio lib) | varies | n/a — WAV/MP3/OGG only, no FLAC | active | n/a | **N/A** (no FLAC support) |
| **Subsonic/Airsonic transcode pattern** | n/a | n/a | active | n/a | **N/A** (they use external `flac`/`ffmpeg` binaries for stream-transcode; Kiln plays direct, not transcoding) |
| **VLCJ** (libvlc Java wrapper) | LGPL+GPL parts | yes | active | medium | **DISQUALIFIED** — requires VLC installed on user system; not a self-contained app dep |
| **JAVE2 / Jaffree** (ffmpeg wrappers) | LGPL bundle | yes | active | medium | Heavy (~50 MB native bundle); subprocess overhead — **rejected** for audiophile direct-DSP scope |
| **Bundled `ffmpeg.exe` subprocess** | LGPL (no `--enable-gpl`) | yes | active | medium | Reject same reasons as above — large native bundle; subprocess management surface |
| **JNI / JNA to Xiph libFLAC 1.5.0** | **BSD-3-Clause** for libraries (Apache-2.0-compatible) | **YES — full FLAC spec** | **Active** (Xiph FLAC 1.5.0 released 2025-02-11; last commit 2026-05-08; 2,313 stars) | ~10-20 hrs | **RECOMMENDED MVP PATH** |
| **Pure-Kotlin re-derivation from FLAC spec** | Apache 2.0 (we own it) | yes (would implement to full spec) | n/a | 1500-3000 lines of Kotlin (~80-150 hrs) | **Phase 2b candidate** — publishable as `:audio:decode-flac` library; not MVP scope |

**Key new finding: nayuki/FLAC-library-Java exists and is technically the gold standard among pure-Java FLAC decoders**, but its **GPL-3.0 license is incompatible with Kiln's Apache 2.0 hard-lock** (spec item 8). Linking GPL-3.0 forces the entire codebase to be GPL-3.0 — exactly the carry-over problem Kiln re-derives from Gramophone to avoid.

The existence of nayuki's library — written by a careful programmer, comprehensive, and STILL not viable for an Apache 2.0 project — is the proof that the pure-Java decoder ecosystem fundamentally lacks an Apache-2.0-compatible-and-hi-res-capable option. The market gap is real.

### Xiph libFLAC verification

- **Repo:** github.com/xiph/flac
- **Latest stable:** FLAC 1.5.0 (2025-02-11)
- **License for libraries** (libFLAC, libFLAC++): **BSD-3-Clause** — Apache-2.0-compatible
- **License for command-line tools** (`flac`, `metaflac`): GPL — Kiln does NOT redistribute these, so GPL doesn't propagate
- **License for docs:** GFDL-1.3 — irrelevant to binary linking
- **Maintenance:** 2,313 stars, last commit 2026-05-08 (~10 days ago at session time), 71 open issues — actively maintained, mature reference implementation
- **Pre-built Windows DLLs:** YES — Xiph ships MSVS 2022-built Win32 + Win64 binaries in the 1.5.0 release archive (~773 KB)
- **Android:** Media3 ExoPlayer's FLAC extension already handles FLAC on Android natively. **Only the desktop side needs the libFLAC bridge.** The `:audio:playback/src/androidMain/` retains its `Media3ExoPlayerImpl` path; the `:audio:playback/src/jvmMain/` gets a JNA-libFLAC bridge

### JNA vs raw JNI — sub-decision

| Approach | C wrapper code required? | Effort | Performance overhead | License |
|---|---|---|---|---|
| Raw JNI to libFLAC | YES — write a C shim layer (~150-300 lines) to flatten libFLAC's stream-decoder callback model into a JVM-friendly interface | ~20 hrs total | Zero per-call overhead (native) | n/a |
| **JNA (Java Native Access)** | **NO — pure Kotlin interface declarations matching libFLAC's public C API** | **~10-15 hrs total** | ~5-10% slower per call vs JNI; insignificant for streaming decode | **Apache 2.0** (`net.java.dev.jna:jna`) |

**Recommendation: JNA.** Reasons:
- ~5-10 hr effort savings vs writing the C wrapper
- Stays in Kotlin/JVM territory — Bus-Factor-of-One friendly. Clay can read the JNA bridge code in 6 months without needing C/CMake knowledge
- Per-call overhead is irrelevant for FLAC decode where each call processes thousands of samples
- JNA is itself Apache 2.0 licensed; ~1 MB additional dep (small)
- JNA is mature, well-known, decade-old library (Sun JNA → Oracle → community-maintained)

JNA library coordinates:
```toml
jna = "5.14.0"   # latest stable; verify at MVP Session 4

jna           = { module = "net.java.dev.jna:jna",         version.ref = "jna" }
jna-platform  = { module = "net.java.dev.jna:jna-platform", version.ref = "jna" }
```

### Native binary bundling pattern

**Plan for shipping libFLAC.dll alongside Kiln Desktop:**

1. Pre-built `libFLAC.dll` (Win-x64) downloaded from xiph/flac 1.5.0 GitHub release, committed to `:audio:playback/src/jvmMain/resources/native/win-x64/libFLAC.dll`. Treat as a vendored binary.
2. At runtime, Kotlin code extracts the DLL from the JAR to a temp directory (e.g., `${java.io.tmpdir}/kiln/libFLAC-1.5.0.dll`) and calls `System.load(path)` to register it before JNA Native.load(...) instantiates the libFLAC interface
3. License attribution: ship `THIRD_PARTY_LICENSES.md` in the app bundle with the xiph/flac BSD-3 license text. Compose-MP's `nativeDistributions { includeAllModules.set(true) }` handles license inclusion in the bundled output
4. Win-x86 (32-bit) not shipped initially — Kiln is x64-only on Windows desktop per Clay's hardware. Add Win-x86 fallback only if needed
5. Future macOS / Linux desktop: build separate `.dylib` and `.so` from xiph/flac source; out of scope for MVP

### Effort revision

**Item 9 original estimate: ~10-15 hrs for JNI bridge.**

**Revised with JNA path: ~10-15 hrs total**:
- ~3 hrs: write Kotlin JNA interface declarations for libFLAC's stream decoder API (10-15 native functions)
- ~3 hrs: native binary loading + extraction-from-JAR plumbing
- ~3 hrs: Gradle wiring (vendor the DLL, set up resource paths, configure jpackage to include native dir)
- ~3 hrs: smoke testing on Clay's hardware with diverse FLAC files (16/44, 24/96, 24/192)
- ~3 hrs: error handling (corrupt FLAC, missing DLL, JNA Native.load failure)

Lands in MVP Session 4-7 (`:audio:playback` vertical slice). The `JvmFlacDecoderImpl` per spec §13 abstraction is where this lives.

### Soft-lock revisit triggers (updated)

- libFLAC bridge fails on any of Clay's hi-res FLAC files at MVP Session 4-7 → file upstream issue with xiph/flac (extremely unlikely; reference implementation handles everything in the spec)
- Future macOS/Linux target appears → build `.dylib` and `.so` artifacts from xiph/flac source
- Phase 2b kicks off the pure-Kotlin re-derivation as a published `:audio:decode-flac` library — this is the "Bus-Factor-of-One library showcase" candidate, not a forced migration

### Updated status

**DECIDED.** JNA + Xiph libFLAC 1.5.0 (BSD-3) is the MVP FLAC decoder path. **Supersedes** Item 9's "JustFLAC license-conditional vs JNI-libflac" hedge. The decoder choice is now firm.

The original Item 9 "PARTIALLY DECIDED" status applied to the FLAC-decoder portion only; the Java Sound output portion was already firm. With this addendum, **the entire Item 9 is now fully DECIDED**.

Pre-MVP Research final tally: **12 of 12 items fully decided.**

### Engram + commit cross-reference

This deep-dive follow-up commit supersedes the JustFLAC mention in commit `317e950` for guidance purposes. The commit itself stays in history; the recommendation is updated here.

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

---

## Item 3 addendum: kmpalette publication gap (Pre-MVP → MVP boundary)

**Date:** 2026-05-19 (during MVP Session 1 Task #8 `./gradlew build --dry-run` smoke check)
**Trigger:** Build failure resolving `dev.jordond.kmpalette:kmpalette-core:4.0.0-beta02`

### Question

Is `dev.jordond.kmpalette:kmpalette-core:4.0.0-beta02` actually published to Maven Central (or any public Maven repo Kiln consumes from)?

### Method

Triggered by Gradle `./gradlew build --dry-run` failure during MVP Session 1 Task #8:

```
Could not find dev.jordond.kmpalette:kmpalette-core:4.0.0-beta02.
Searched in the following locations:
  - https://dl.google.com/dl/android/maven2/dev/jordond/kmpalette/...
  - https://repo.maven.apache.org/maven2/dev/jordond/kmpalette/...
```

Verified via three independent checks:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://repo.maven.apache.org/maven2/dev/jordond/kmpalette/kmpalette-core/4.0.0-beta02/kmpalette-core-4.0.0-beta02.pom
# → 404

curl -s 'https://search.maven.org/solrsearch/select?q=g:dev.jordond.kmpalette+AND+a:kmpalette-core&core=gav&rows=10&wt=json' | head -50
# → "numFound":0

gh api 'repos/jordond/kmpalette/releases?per_page=10' --jq '.[]|{tag,prerelease}'
# → tags 4.0.0-beta01, 4.0.0-beta02 exist as GitHub releases (prerelease: true)
#   but 3.x stable line ends at 3.1.0 (2024-01-30)
```

### Findings

1. **GitHub release tag exists** for `4.0.0-beta02` (published 2026-03-03 as prerelease). Source code is reachable on GitHub.
2. **NO Maven Central publication** for `4.0.0-beta02` OR `4.0.0-beta01`. The Maven Central search returns 0 hits for `g:dev.jordond.kmpalette+AND+a:kmpalette-core`. The canonical Central path returns HTTP 404.
3. **Earlier stable line (3.x) WAS published** to Maven Central historically (3.1.0 from 2024-01-30 — over a year old). The 4.0.0-betaN line was tagged on GitHub but the publishing pipeline appears to have been broken/abandoned during the 4.0.0 development cycle.
4. **Sonatype OSS snapshots returned 401** (no public access; not definitive proof of absence, but consistent with "never released").

**Root cause of Item 3 vetting gap:** The original Item 3 vetting (commit `0e2c3fe`) confirmed the GitHub release tag existed but did NOT verify Maven Central artifact resolution. Build-time discovery during MVP Session 1 Task #8 surfaced the gap.

### Decision

**Defer per Item 3's existing soft-lock cascade** (revisit at Phase 2a Flight A). No change to Pre-MVP decision intent — kmpalette adoption was already scheduled for Phase 2a Flight A, not MVP-1.0. The publication-gap discovery doesn't change the timeline, just the resolution paths.

**Three resolution paths at Phase 2a Flight A** (in priority order):

| Path | Effort | Risk |
|---|---|---|
| 1. **JitPack via `com.github.jordond.kmpalette:kmpalette-core:4.0.0-beta02`** | ~2 hrs (add JitPack repo to settings.gradle.kts, swap coordinates) | JitPack-built artifact; build reproducibility slightly worse than Maven Central |
| 2. **File issue with `jordond/kmpalette` asking for Maven Central publish** | ~1 hr Clay's action + indefinite wait | Maintainer responsiveness unknown |
| 3. **Roll-our-own WCAG-AA palette extractor** (~50-line Kotlin in `:ui:theme/commonMain`) | ~16-24 hrs | Captured complexity; Bus-Factor-of-One friendly |

Phase 2a Flight A start time is when this path is chosen, not now. MVP-1.0 ships without dynamic theming.

### Updated status

**Item 3 status unchanged: DECIDED (with soft-lock revisit at Phase 2a Flight A).** The Pre-MVP decision (kmpalette 4.0.0-beta02 as the intended palette extractor) stands as an *intent*. The *implementation* path is one of the three above, chosen at Phase 2a Flight A time.

For MVP scaffold (now), `libs.kmpalette.core` reference is **commented out in `:ui:theme/build.gradle.kts`** with explanatory note. The `kmpalette = "4.0.0-beta02"` version entry and `kmpalette-core = { ... }` library entry **remain in `gradle/libs.versions.toml`** as documentation of intent. They are not currently consumed.

### Methodology lesson for future vetting

For any library whose Pre-MVP decision pins a beta or pre-release version, add Maven Central artifact resolution check to the vetting checklist:

```bash
# Verify pom is actually published, not just tagged on GitHub:
curl -s -o /dev/null -w "%{http_code}\n" \
  "https://repo.maven.apache.org/maven2/$(echo $GROUP | tr '.' '/')/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION.pom"
# Expected: 200. If 404, the artifact only lives in GitHub source/release-assets, not in any Maven repo Kiln consumes from.
```

Should be added to Pre-MVP Research's standard methodology. Captured in engram under `kiln/library-vetting/item-3-kmpalette-publication-gap`.

### Engram + commit cross-reference

Discovery surfaced 2026-05-19 during MVP Session 1 Task #8 smoke check. Build commit fixing the symptom: `0e02112 scaffold(gradle): jvmMain→desktopMain rename + defer kmpalette dep`. Vetting log addendum committed as part of MVP Session 1 close-out.

---

## Item 1 addendum: minSdk hard-lock revisit 21 → 23 (MVP Session 2 trigger)

**Date:** 2026-05-19 (during MVP Session 2 Task #9 `./gradlew :app-android:assembleDebug` first attempt)
**Trigger:** Compose Multiplatform 1.11.0 `components-resources-android` library requires minSdk ≥ 23

### Question

Can Kiln's `:app-android` module preserve spec §2 Item 1's `minSdk = 21` hard lock given the Compose Multiplatform 1.11.0 dependency stack?

### Method

First real `./gradlew :app-android:assembleDebug` run after writing `MainActivity.kt` + `AndroidManifest.xml`. Manifest merger failed:

```
uses-sdk:minSdkVersion 21 cannot be smaller than version 23 declared in library
[org.jetbrains.compose.components:components-resources-android:1.11.0]
```

The library `org.jetbrains.compose.components:components-resources-android` is pulled transitively from `bundles.compose-mp-common` (used by `:ui:theme` and `:ui:components` per build-logic/kiln.kmp.compose convention).

### Findings

1. **Compose Multiplatform 1.11.0's components-resources-android requires minSdk ≥ 23** as a hard requirement, not a SDK target compromise. The library uses APIs unavailable on 21-22.
2. Risk-playbook R4 anticipated this class of trigger (originally framed as a Coil 3.x risk). The trigger fired earlier than expected, via a different Compose-MP library (not Coil).
3. **Compose Multiplatform's floor has moved** as of 2026. Other CMP libraries may follow. Staying on 21 would require dropping Compose-MP usage entirely — a fundamental architecture violation per spec §2 Item 1 ("Compose Multiplatform" as the UI stack hard-lock).

### Decision

**Bump spec §2 Item 1 platform target minSdk from 21 to 23. Approved by Clay 2026-05-19.**

- Was: `android-minSdk = "21"`, "Min SDK Android: 21 (Lollipop)"
- Now: `android-minSdk = "23"`, "Min SDK Android: 23 (Marshmallow)"
- Addressable Android market loss: ~0.5% (API 21-22 = Android 5.0/5.1; covers <0.5% of active devices in 2026)
- Clay's primary device (Pixel 10 Pro XL) is Android 16+, trivially satisfied
- Item 1 ("Compose Multiplatform + Android+JVM+iOS+JS targets") otherwise unchanged

### Ripple (files updated as part of this hard-lock revisit)

- `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md` §2 Item 1 (Min SDK Android 21 → 23) + §8 CI matrix (API 21 dropped)
- `CLAUDE.md` Identity section (min SDK 21 → 23)
- `gradle/libs.versions.toml` (`android-minSdk = "23"`; updated comment)
- `build-logic/src/main/kotlin/kiln.kmp.library.gradle.kts` (minSdk = 23 + header comment)
- `build-logic/src/main/kotlin/kiln.android.app.gradle.kts` (defaultConfig.minSdk = 23)
- This vetting log addendum (Item 1 §)

### JIT items resolved

- ~~"Confirm Coil 3.4.0 still supports minSdk = 21"~~ (MVP Session 4 JIT) — **RESOLVED.** Bump means Coil's own minSdk floor is no longer relevant for our app.
- Risk-playbook R4 "Coil 3.x raises minSdk above 21" — **RESOLVED.** Trigger fired (via compose-resources, not Coil); resolution is this addendum.

### Updated status

**Item 1 status: DECIDED with revised minSdk floor.** Compose-MP stable line + Android+JVM Desktop+iOS+JS targets remain. Only the minSdk number amended (21 → 23). No other Item 1 details change.

### Engram + commit cross-reference

Spec amendment commit lands during MVP Session 2 work. Engram entry under `kiln/library-vetting/item-1-minsdk-23-revisit-2026-05-19`. Risk-playbook R4 implicitly resolved by this addendum (no separate R4 update needed; future maintenance can prune R4 when the playbook is refreshed).

---

## Item 13 addendum: Phase 2b sequencing resolved — Option (a-prime) locked (2026-05-23)

> **Plan reference:** Pre-MVP Research §2.1 Item 13 originally locked the engine-swap-shaped `PlatformPlayer` boundary in MVP and downgraded Phase 2b Flights H+I from "must build" to "may build, soft-lock revisit at end of Phase 2a." This addendum records the resolution of that soft-lock revisit.

### Status update

**Original Item 13 status:** DECIDED with soft-lock-revisit at end of Phase 2a (Flights H+I in scope conditional on dogfood outcomes).

**New status:** DECIDED with resolved soft-lock. Phase 2b LOCKED to Option (a-prime); Flights H+I (AAudio MMAP + WASAPI as originally scoped) DEFERRED to Phase 2c. New Android bit-perfect path via `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` (a path not surfaced in the original 2026-05-18 research) added to Phase 2b in their place.

### Soft-lock revisit method

Triggered 2026-05-23 (Session 20) post-Phase-2a-Track-D ship. Sequence:

1. **`/multi:research`** on current state of AAudio (Android 14+) + WASAPI (Windows 11 24H2) — fan to claude+codex (gemini quota-burned); 290s wall; output at [`./2026-05-23-phase-2b-aaudio-wasapi-research.md`](./2026-05-23-phase-2b-aaudio-wasapi-research.md). Codex (web-grounded via Firecrawl) surfaced the previously-unsurfaced `MIXER_BEHAVIOR_BIT_PERFECT` AudioTrack path as a distinct audiophile option separate from low-latency Oboe.
2. **`/multi:decide`** between four sequencing options: (a) Spec Sheet UI only, (a-prime) Spec Sheet UI + Android BIT_PERFECT path, (b) Spec Sheet UI + Oboe-low-latency + WASAPI bundled, (c) Stream B only. Both providers (claude + codex) independently locked (a-prime); 112s wall. ADR engram at `architecture/kiln-phase-2b-sequencing`.
3. **`/multi:falsify`** on Option (a-prime): 3/3 valid panel (claude+gemini+codex), 23 distinct failure modes ranked, 11 at H-by-H = 9. Falsify-integration decisions captured at engram `architecture/kiln-phase-2b-falsify-integration`.

### Decision

**Phase 2b LOCKED to Option (a-prime):**
- Stream A: Spec Sheet UI per-track + library aggregate stats (~40-70hr — revised up from 30-50hr to include F10 format-fact backfill scanner pass)
- Stream B: Android `MIXER_BEHAVIOR_BIT_PERFECT` path via `BitPerfectAudioTrackPlayerImpl` (~155-245hr — revised up from 50-90hr to integrate falsify findings including JNA-libFLAC software decode swap, null-test acceptance rig, AudioFocus/MediaSession/AudioDeviceCallback reimplementation, loudness-matched toggle, per-track sample-rate rebuild)
- Phase 2b-prereq: Voyager Navigator scaffold + clickable title in NowPlayingTab + placeholder SpecSheetScreen (~10-20hr — added per F17 mitigation, since Stream A's tap-title route requires it)
- **Total Phase 2b:** 205-335hr (vs original (a-prime) 80-140hr; vs originally-baselined bundled Phase 2b 205-310hr)

**Deferred to Phase 2c:**
- Windows WASAPI engine (~80-120hr) — original Flight I
- Android Oboe low-latency path (~80-120hr) — original Flight H
- Phase 2c trigger condition: Phase 2b shipped to main with all sub-flights green + dogfooded at least 30 days + cross-platform audiophile asymmetry causing friction in actual listening sessions

### Rationale

1. **Bit-perfect is audiophile-correct for Clay's FLAC-on-USB-DAC use case;** low-latency Oboe is the right answer for the wrong problem (games/realtime, not lossless listening). Codex's research surfaced this framing distinction; claude convergence locked it.
2. **Per-phase scope honors Hard-Capped Operations + Bus-Factor-of-One.** Splitting Windows WASAPI into its own phase avoids C++/JNI/COM bridge complexity stacked against new UI work.
3. **`PlatformPlayer` Engine-Swap-Shaped Boundary preserved** — `BitPerfectAudioTrackPlayerImpl` implements the existing interface verbatim. The original Item 13 abstraction earns its keep exactly as designed.
4. **Falsify-integration costs accepted** (155-245hr Stream B vs original 50-90hr) because quality-first per Clay's 2026-05-23 directive: the "bit-perfect" claim becomes verifiable (Item 14 + Item 15) rather than locked as marketing copy.

### Single unlock condition for Stream B

`AudioManager.getSupportedMixerAttributes(usbDevice)` returns non-empty on Pixel 10 Pro XL + Clay's exact USB-C-to-AUX dongle, AS VERIFIED by the B0 capability-probe spike. If empty → drop Stream B from Phase 2b → ship Phase 2b-prereq + Phase 2b-A only → re-evaluate Stream B at Phase 2c kickoff.

### Dissent preserved

- **(b)-bundled advocate:** splitting leaves Windows on `javax.sound.sampled` until Phase 2c; cross-platform audiophile story incomplete this phase. Counter: clean phase boundaries beat narrative completeness; Phase 2c with Windows-only focus more likely to ship correctly than bundled-Phase-2b under the now-225-335hr scope.
- **(a)-only advocate:** BIT_PERFECT is vendor-optional; Pixel 10 Pro XL support empirically unmeasured (2026-05-23). Mitigated by capability-probe spike (B0) as first Stream B task — if probe fails, ship (a)-only.
- **Research-source caveat:** the `/multi:research` fan was codex-heavy (gemini quota-burned 2026-05-23). G2 gate added: gemini cross-check of BIT_PERFECT vendor-support landscape required before Stream B code lands.

### Engram + commit cross-reference

- Engram `architecture/kiln-phase-2b-sequencing` (decision lock 2026-05-23)
- Engram `architecture/kiln-phase-2b-falsify-integration` (falsify-integration scope revisions 2026-05-23)
- Plan doc: [`../superpowers/plans/2026-05-23-phase-2b-plan.md`](../superpowers/plans/2026-05-23-phase-2b-plan.md)
- Research doc: [`./2026-05-23-phase-2b-aaudio-wasapi-research.md`](./2026-05-23-phase-2b-aaudio-wasapi-research.md)

---

## Item 14: Android JNA-libFLAC port (Phase 2b Stream B-B1)

> **Plan reference:** [`../superpowers/plans/2026-05-23-phase-2b-plan.md`](../superpowers/plans/2026-05-23-phase-2b-plan.md) §6.B1 (Android JNA-libFLAC port). Falsify F1 mitigation — bit-perfect claim becomes verifiable.

### Question

Phase 2b Stream B's bit-perfect AudioTrack path requires a verifiably-correct FLAC decoder that bypasses Android's vendor MediaCodec FLAC implementation (which may resample/dither/quantize before PCM emerges, defeating the bit-perfect claim per falsify F1). Should the desktop JNA + Xiph libFLAC 1.5.0 bridge (already in production via `audio/playback/src/desktopMain/.../JvmFlacDecoderImpl.kt`) be ported to Android?

### Method

Falsify F1 surfaced the issue. Research doc "NEW PATH SURFACED" framed bit-perfect as a distinct goal from low-latency. Implementation decision flows from Clay's 2026-05-23 "quality priority" directive on RG-bypass — null-test acceptance gate (Item 15) requires a reference PCM to compare against, and the desktop JNA bridge already produces that reference.

### Decision

**Port the desktop JNA-libFLAC bridge to Android** (gated on G1 capability-probe PROBE_PASS).

Implementation shape:
- `audio/playback/src/androidMain/.../AndroidFlacDecoderImpl.kt` — mirror desktop API
- libFLAC 1.5.0 BSD-3 `.so` binaries vendored at `audio/playback/src/androidMain/jniLibs/<abi>/libFLAC.so` for `arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86` — sourced from xiph/flac NDK build OR known-Apache-2.0-or-BSD prebuilt package
- JNA `Native.load("FLAC", LibFLAC::class.java)` resolves to `libFLAC.so` (CLAUDE.md gotcha applies: drop the `lib` prefix)
- Reuse `JvmFlacDecodedStream.kt` callback model; consider moving to `commonMain` if compatible
- Android-host tests against same FLAC fixtures used in `JvmFlacTrackAnalyzerTest.kt`
- Performance gate: at least 5x realtime decode for 96 kHz on Pixel 10 Pro XL (escalation to NDK direct C linkage if insufficient)

### License + dependencies

- libFLAC 1.5.0 BSD-3-Clause (same as desktop; THIRD_PARTY_LICENSES.md already attributes)
- JNA 5.17.0 (already pinned; existing on classpath via `audio/playback`)
- No new top-level dependencies

### Estimated effort

~30-50 hr (one sub-flight of Phase 2b Stream B-B1).

### Soft-lock revisit triggers

- Performance gate fails (<5x realtime on Pixel 10 Pro XL) → escalate to NDK direct C linkage (an additional ~20-40hr; signals that the AudioTrack callback path's hot loop won't sustain real-time playback in pure-JVM)
- libFLAC 1.5.0 turns out to have a published 1.6.x major-version-bumped successor with API churn between now and Stream B start → re-pin to 1.5.0 stable; do not chase

### Status

**CONDITIONALLY DECIDED.** Implementation begins at Phase 2b Stream B-B1 IFF G1 capability probe (B0) returns PROBE_PASS for any target format. If G1 returns PROBE_FAIL all formats, Stream B is dropped and this item becomes "may be revisited at Phase 2c kickoff."

---

## Item 15: DAC null-test acceptance rig (Phase 2b Stream B-B2)

> **Plan reference:** [`../superpowers/plans/2026-05-23-phase-2b-plan.md`](../superpowers/plans/2026-05-23-phase-2b-plan.md) §6.B2 (Null-test acceptance rig). Falsify F1 + F5 mitigation — capability probe is necessary, not sufficient; external bit-identity verification is the sufficiency check.

### Question

`AudioManager.getSupportedMixerAttributes()` returning non-empty does NOT guarantee bit-identical output (per F5 — HAL/ALSA can silently resample even with probe-green). The bit-perfect claim therefore requires an out-of-tree verification: a reproducible DAC loopback rig that compares PCM emitted by the bit-perfect path against PCM decoded by the JNA-libFLAC reference (Item 14), confirming zero bit-error over a captured sample window.

### Method

Required by falsify-integration (Clay's 2026-05-23 "quality priority" directive made null-test acceptance non-negotiable for the bit-perfect claim). No prior tooling exists in the project.

### Decision

**Build a reproducible DAC loopback test harness** as part of Phase 2b Stream B-B2, before any production `BitPerfectAudioTrackPlayerImpl` code lands.

Rig shape (deferred to flight-start detailed plan; outline only):

- **Hardware:** USB audio loopback (Clay's USB-C-to-AUX dongle + external mic; OR external USB audio interface with hardware loopback such as a Focusrite Scarlett's MIDI-thru or a basic ALSA `hw:Loopback` device on a Linux laptop wired in)
- **Capture path:** depends on hardware — options include (a) `AudioRecord` from the dongle's mic-in if applicable, (b) `arecord` on a Linux laptop with the dongle wired in, (c) PortAudio-based capture on a Windows desktop
- **Comparison software:** small Python or Kotlin script aligns captured PCM with reference PCM (cross-correlation on a 1-second pilot tone), reports bit-error count over remaining test window
- **Acceptance:** 0 bit errors over 30-second test window for each of {44.1/16, 48/16, 96/24, 192/24} (4 fixture files representing the library's empirical format distribution)
- **Failure mode handling:** any non-zero bit-error for a format → UI rename "Bit-perfect output" to "Direct mixer output" for that format-class + user-visible warning. Per-format pass/fail surface
- **Documentation:** `audio/playback/null-test-rig/README.md` — reproducible from a clean checkout

### License + dependencies

- New module (or scripts tree) `audio/playback/null-test-rig/` — Apache 2.0 per project default
- Tooling deps depend on capture-path choice; all common ones (Python `scipy`/`numpy` for analysis; ALSA tooling on Linux; PortAudio on Windows) are permissively licensed (Apache 2.0 / BSD / PSF)
- Hardware: existing Clay-owned dongle + potentially one external interface or measurement mic (sub-$100 — fits Lean Technical Scaling preference)

### Estimated effort

~10-20 hr (one sub-flight of Phase 2b Stream B-B2).

### Soft-lock revisit triggers

- Test rig cannot achieve 0 bit-errors for any format on the chosen hardware → blocks Stream B-B3 indefinitely; either swap hardware OR scope cut "bit-perfect" to "Direct mixer output" (acceptable degradation per F1 mitigation framing)
- Capture path proves device-incompatible (e.g., Clay's dongle has no mic-in for loopback) → escalate to external USB audio interface purchase OR `hw:Loopback` on Linux laptop

### Status

**CONDITIONALLY DECIDED.** Implementation begins at Phase 2b Stream B-B2 IFF G1 capability probe (B0) returns PROBE_PASS for any target format AND Item 14 JNA-libFLAC port produces working reference PCM. If either prerequisite fails, this item is deferred/dropped accordingly.


