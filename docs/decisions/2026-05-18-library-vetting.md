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

## Next items in queue

- (Remaining items 5, 7, 9, 10, 12 — to be tackled in subsequent Pre-MVP Research sessions)

Items still to research:
- Item 5 — Circuit + Molecule on KMP (now applies to `:ui:components` Now Playing)
- Item 7 — Compose-MP screenshot testing (Roborazzi maturity)
- Item 9 — Java Sound capability survey on Windows
- Item 10 — jpackage / Windows distribution
- Item 12 — Compose MP Desktop LazyColumn 40k stress test (requires actual code spike)

---

## Session 2 summary

**Date:** 2026-05-18
**Items completed:** 3 of 12 active (Items 2, 3, 6) → cumulative 7 of 12 with sessions 1+2
**Items remaining:** 5 (Items 5, 7, 9, 10, 12)

**Soft-lock revisits triggered:** None this session. All three decisions are within their original scopes — Coil 3.4.0 confirmed; kmpalette adopted with a known revisit point at Phase 2a Flight A start (beta-vs-stable confirmation); SQLDelight 2.3.2 pinned with schema sketch delivered.

**Cross-references established:**
- Coil 3 (Item 2) ↔ kmpalette (Item 3): Coil decodes ImageBitmap, kmpalette consumes it; integration wired manually because no `coil-loader` ships
- SQLDelight (Item 6) ↔ kmpalette (Item 3): palette cache may use a SQLDelight side-table at Phase 2a Flight A if in-memory cache proves insufficient

**New just-in-time research items deferred:**
- Confirm Coil 3.4.0 still supports `minSdk = 21` (could trigger spec §2 hard-lock revisit)
- Confirm kmpalette 4.0.0 stability story before Phase 2a Flight A starts
- Verify Pixel 10 Pro XL's SQLite has FTS5 at MVP Session 4 (sanity check)

**Estimated remaining Pre-MVP Research effort:** ~7-12 hrs across the 5 remaining items (per plan estimates).

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

