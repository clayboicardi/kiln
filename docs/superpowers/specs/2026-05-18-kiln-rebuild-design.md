# Kiln by Clayworks — Rebuild Design Spec

**Date:** 2026-05-18
**Author:** Clay Haworth (clayboicardi) with Claude Opus 4.7 (1M context)
**Status:** Design phase — pre-implementation. Pre-MVP Research **complete** (2 sessions, 2026-05-18): all 12 vetting items decided, library versions pinned, schema sketched. See [vetting log](../../decisions/2026-05-18-library-vetting.md) and [scaffold prep](../../scaffold/2026-05-18-mvp-session-1-prep.md). Next gate: Clay's review before MVP Session 1 scaffold starts.
**Predecessor:** JAMZ!!! (at `C:\Users\chawo\Projects\JAMZ!!!` on branch `tidal-download`), preserved as artifact and reference
**Next step:** `/superpowers:writing-plans` to turn this spec into a per-phase execution plan

---

## 1. Why Kiln, why now

Several reinforcing motivations point at the same path:

- I've wanted to customize and enhance my music app further for some time. Only recently have I had the time to dive back in.
- The desktop addition isn't arbitrary scope creep — it reflects what I'll actually be using the app for. I want granular control over my listening on both Pixel and Windows.
- Separating from Gramophone serves the same reasoning as cross-platform support: the player needs to evolve at my pace, scoped to my actual use, on a codebase I can fully own and shape. The Gramophone fork couldn't do this cleanly. Kiln can.
- The portfolio motivation is real but reinforcing rather than competing. The same project that gives me the listening experience I want also serves as a demonstrable engineering artifact.

This anchor is load-bearing. If motivation flags in a future session, this section is the answer to "why are we doing this when JAMZ already works?"

---

## 2. Strategic decisions (with stability tier)

| # | Item | Decision | Stability |
|---|---|---|---|
| 1 | Project name | **Kiln by Clayworks** | Hard |
| 2 | Differentiator | REW-style measurement-mic room correction (phase 3+) | **Soft** — revisitable if new variables emerge |
| 3 | Platform targets | Android + Windows Desktop via KMP from day one. Mac/Linux/iOS not blocked architecturally | Hard |
| 4 | Tech stack | KMP Moderately Modern + Arrow showcase in `:audio:dsp` + Circuit showcase in `:ui:components` Now Playing (relocated 2026-05-18 after Tidal cut) | **Soft** — pieces may swap if implementation reveals issues |
| 5 | Design system | Kiln Dynamic aggressive theming (album-art-driven across UI) | Hard direction; iterative refinement OK |
| 6 | MVP shape | Minimal MVP done right (all phase-2 seams wired) | Hard |
| 7 | Test strategy | Aggressive full pyramid | **Soft** — depth re-tunable if effort cost outweighs portfolio value |
| 8 | License | Apache 2.0 across all modules | Hard |
| 9 | Why now | Reinforcing motivations stack (see §1) | Hard anchor |

**Soft locks (items 2, 4, 7):** these are current best decisions, made deliberately with full info. They are **not untouchable**. If a future Clay/Claude session uncovers a new variable or unforeseen factor warranting adjustment, soft locks can be revisited. Hard locks should not be changed without strong reason — they ripple through other decisions.

---

## 3. Architecture overview

### 3.1 Module graph (Gradle KMP)

```
:app-android          Android entry point, MainActivity, manifest
:app-desktop          Desktop entry point (JVM), main(), system tray
:audio:dsp            Pure-Kotlin parametric EQ + filter math (KMP, no androidx)
                      Showcase: Arrow typed errors (Either<EqError, EqResult>)
:audio:visualizer     Pure-Kotlin FFT visualizer (KMP, no androidx)
:audio:playback       Player abstraction (Media3 on Android, Java Sound on JVM)
                      Includes mic-capture path (stubbed; for phase-3 room correction)
:data:library         MusicSource for local FLAC library
                      Android: MediaStore scanner; Desktop: filesystem walker
:ui:theme             Kiln Dynamic theming engine (palette extraction + contrast post-processing)
:ui:components        Shared Compose-MP components (Now Playing, EQ controls, library lists, etc.)
                      Showcase: Circuit Presenter/UI split in Now Playing screen
                      (relocated from :data:streaming-tidal after that module was cut 2026-05-18)
```

### 3.2 KMP source set layout (per multiplatform module)

```
:audio:dsp/
  src/
    commonMain/    pure Kotlin DSP code (the actual EQ math)
    commonTest/    property-based tests via Kotest
    jvmMain/       (empty unless desktop-specific helpers needed)
    androidMain/   thin adapter wrapping pure-Kotlin API as Media3 BaseAudioProcessor
```

Same pattern for `:audio:visualizer`. UI modules use Compose-MP's `commonMain` for shared composables with `androidMain` / `jvmMain` for platform-specific entry points and integration.

### 3.3 The Source Protocol

A `MusicSource` interface defined in `:data:library` (common code, no platform dependencies):

```kotlin
interface MusicSource {
    val id: SourceId
    val displayName: String

    suspend fun search(query: String): Flow<SearchResult>
    suspend fun browse(scope: BrowseScope): Flow<MediaItem>
    suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable>

    val supportsDownload: Boolean
    val supportsStreaming: Boolean
}
```

`LocalLibrarySource` is the only implementation in scope. The interface is kept because (a) it adds almost no cost over a concrete class, and (b) it preserves optionality for future expansion (Subsonic/Navidrome self-hosted source, Bandcamp library import, etc.) without restructuring core code. Tidal streaming was cut on 2026-05-18 after risk review (Gemini adversarial critique flagged Termux/Android 14 background-process fragility; Clay's actual usage assessment: 39,500-track curated FLAC library satisfies 99%+ of listening, so Tidal-via-Termux was solving a non-problem). **No source-specific branching anywhere in the codebase** — if `if (source is LocalLibrarySource)` appears, the interface is wrong.

### 3.4 Concentric modules invariant

`:audio:dsp` and `:audio:visualizer` are **pure-Kotlin/JVM, zero `androidx.*` dependencies, KMP-ready** in their `commonMain` source set. Android-specific code (Media3 wrappers) lives in `androidMain` adapters. This is the "Concentric Modules" pattern from the brainstorm.

**Strict invariant:** if a future PR adds `androidx.*` imports to `commonMain` of either module, reject it. The adapter exists for this reason — it does not "save" a wrapper class.

---

## 4. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin 100% | Cross-platform via KMP |
| UI | Compose Multiplatform | Single UI codebase across Android + Desktop |
| DI | Kotlin-Inject (KSP) | KMP-native, no KAPT, no Hilt |
| Navigation | Voyager | Simpler than Decompose, KMP-native, sufficient |
| State | ViewModel-MP + StateFlow | Mainstream, Compose-friendly |
| Serialization | Kotlinx Serialization (KSP) | KMP-native, JetBrains-supported |
| Database | SQLDelight | SQL-first, KMP-native, transparency about queries |
| HTTP | Ktor client | KMP-native, replaces OkHttp |
| Async | kotlinx-coroutines + Flow | Standard KMP idiom |
| Audio (Android) | Media3 ExoPlayer (latest stable) | Latest stable, accepts custom audio processors |
| Audio (Desktop) | Java Sound (MVP) → WASAPI via JNI (phase 2b) | Native APIs; phase-2 upgrade for low-latency |
| Image loading | Coil-MP (or compatible KMP alternative) | KMP-compatible image loading |
| FP utilities | Arrow (only in `:audio:dsp`) | Showcase typed errors in DSP module |
| Reactive state | Molecule + Circuit (only in `:ui:components` Now Playing screen) | Showcase MVI presenter pattern on the screen with the most complex state (playback states, queue context, playback position, EQ-applied state) |

**Min SDK Android:** 21 (Lollipop)
**Compile SDK Android:** 36
**JDK target:** 21 (Temurin)
**Build system:** Gradle Kotlin DSL with the Kotlin Multiplatform plugin

---

## 5. Design system: Kiln Dynamic

### 5.1 Typography (fixed across themes)

- Body: IBM Plex Sans (Regular 400, Medium 500, SemiBold 600)
- Data readouts: IBM Plex Mono (Regular 400, Medium 500)
- Sample: `FLAC — 24/96 — 6:42 — +0.3 dB ReplayGain`

### 5.2 Brand-anchored palette (idle state)

When no music is playing, OR for always-fixed elements (body text, app icon, structural backgrounds):

```
bg-base       #0F0E0C  warm near-black
bg-surface    #1A1815  warm dark gray
text-primary  #F5EBE0  warm cream (always readable, never moves)
text-muted    #A09080  warm muted gray
accent        #D45A2C  kiln-fired clay red (idle accent)
accent-soft   #8A4226  deeper ember (low-saturation fallback)
```

### 5.3 Dynamic palette (active when music is playing)

Pulled from currently-playing album art via Compose-MP-compatible Palette extraction. Multiple swatches extracted and mapped to roles:

- `bg-base` rotates 15–30% toward album dominant hue (subtle)
- `bg-surface` rotates 40–60% toward album dominant hue
- `accent-primary` ← vibrant swatch (play button, EQ band fills, sliders, progress bar, active tab indicator)
- `accent-secondary` ← vibrant alternate (hover states, secondary highlights)
- `surface-tint` ← muted dark swatch (now-playing card, mini-player, detail-page header gradient)
- `list-highlight` ← derived from accent (selected-row tint in library)
- `on-accent-text` ← computed for WCAG AA contrast against accent surfaces

### 5.4 Post-processing for accessibility

- All extracted colors WCAG AA contrast-checked against `text-primary`
- Bright pastels darkened until usable as accents
- Low-saturation art → fallback to `accent-soft`
- Extraction failure → fallback to idle brand palette
- Body text primary (`#F5EBE0`) NEVER changes — preserved as readability anchor

### 5.5 Behavioral notes

- Album-art-driven theming applies to: library views (context-aware tint when an album/artist is selected), now-playing screen, mini-player, EQ activity, search-result detail pages
- **Settings / About / Hardware Spec Sheet also flex while music plays in the background** — total immersion when music is active
- Kiln brand assets (app icon, logo marks) stay fixed (assets, not theme tokens)
- Layout grid stays consistent across themes — only colors flex; typography and spacing are invariant

---

## 6. MVP scope and phase progression

**Effort budget:** 790–1190 hrs total across all phases (revised 2026-05-18 after Gemini adversarial critique + Tidal cut). The original estimate was 580–1015 hrs; the net rose because Gemini-driven realism adjustments to Phase 2b and Phase 3 (+200–300 hrs) outweighed the Tidal-cut savings (–100–150 hrs). No fixed timeline — "no rush" is the explicit operating constraint.

### 6.1 MVP-1.0 (~305–435 hrs)

Ships on Android + Windows Desktop simultaneously.

**Foundation work:**
- Build system (Gradle KMP, all modules scaffolded)
- DI graph (Kotlin-Inject across modules)
- Database schema (SQLDelight for library cache)
- Navigation skeleton (Voyager)
- Audio pipeline core (player abstraction + Media3 + Java Sound adapters)
- `MusicSource` interface + `LocalLibrarySource` implementation
- Theme engine (idle Kiln Warmth palette only at MVP; Dynamic comes in phase 2a)

**Features:**
- Local FLAC library scanning + indexing
- Songs / Albums / Artists library views (basic list rendering)
- Playback (queue / skip / seek / repeat / shuffle)
- Now Playing screen (basic, no blurred art yet; Circuit Presenter/UI showcase lives here)
- Basic search (not sectioned yet)
- 31-band parametric EQ (presets and band sliders, no curve display yet)
- Minimal Settings (scan folders, EQ preset management)
- **System Integration** (added 2026-05-18 after Gemini critique): Android Audio Focus, MediaSession + lock-screen controls, BLE-disconnect handling, hardware media keys, Windows SMTC (System Media Transport Controls)

**Architectural seams built but stubbed for phase 2 (this is the "done right" delta):**
- `MusicSource` interface kept for future expansion (no `TidalSource` planned — Tidal cut 2026-05-18; interface remains open for Subsonic/Navidrome/etc. should they ever be desired)
- Audio pipeline accepts processor plugins (visualizer slot reserved)
- Theme engine has dynamic-extraction plug points (stubbed, returns idle palette)
- Mic-capture path exists in `:audio:playback` (stubbed; for room correction phase 3)
- Hardware Spec Sheet route reserved in nav graph (renders TBD placeholder)
- Compose UI component slots reserved for EQ curve display, visualizer surface, etc.

### 6.2 Phase 2a — JAMZ parity (~130–195 hrs)

This is when Kiln replaces JAMZ as the daily driver. Effort reduced from 180–275 hrs after Tidal integration was cut 2026-05-18 (saved ~50–80 hrs).

- Kiln Dynamic aggressive theming (album-art extraction + post-processing)
- Blurred album art on Now Playing
- EQ frequency-response curve display
- Energy-preserving crossfade between EQ presets
- Sectioned search refinement
- FFT visualizer port

**Cut from this phase (2026-05-18):**
- ~~Tidal integration (Termux on Android, direct Python `tiddl` shell-out on Desktop)~~ — see anti-roadmap §11

### 6.3 Phase 2b — Beyond JAMZ (~205–310 hrs)

Effort revised upward 2026-05-18 after Gemini critique flagged that AAudio MMAP and WASAPI are **audio engine rewrites, not adapter swaps**. They require their own threading models, buffer management, and integration patterns separate from Media3 ExoPlayer / Java Sound.

- Hardware Spec Sheet About screen (the identity move)
- Library extraction (publish `:audio:dsp`, `:audio:visualizer` to JitPack/Maven as standalone open-source libraries — was 3 libraries; reduced to 2 after `:data:streaming-tidal` cut)
- AAudio MMAP on Android (~80–120 hrs; revised from 50–80 hrs). Caveat: this replaces the Media3 ExoPlayer engine for the bit-perfect path; track-transition logic, gapless playback, and decoder integration require separate implementation
- WASAPI shared-mode on Windows (~80–120 hrs; revised from 40–60 hrs). Same caveat — this is a new audio engine, not an adapter swap on Java Sound

### 6.4 Phase 3 — Differentiator (~150–250 hrs)

Effort revised upward 2026-05-18 after Gemini critique flagged this as **DSP R&D, not a 3-week sprint**. Sample-accurate mic-latency alignment across Android USB stacks + sweep generation + FFT response analysis + minimum-phase curve synthesis are genuinely hard. May require dropping to C++ (Oboe on Android) if pure-Kotlin proves insufficient for sample-accurate timing.

- REW-style room correction: measurement-mic sweep playback, response capture, corrective EQ curve synthesis, integration with parametric EQ preset system
- **Soft-lock revisit point before starting this phase:** confirm room correction is still the right differentiator after dogfooding through 2a+2b. If a different priority has emerged, surface explicitly.

### 6.5 Cumulative trajectory (revised 2026-05-18)

| Milestone | Cumulative hrs | What you have |
|---|---|---|
| MVP-1.0 | 305–435 | Working music player on Android + Desktop, daily-usable (including system integration — Audio Focus, MediaSession, SMTC) |
| Phase 2a | 435–630 | JAMZ parity on both platforms — switch daily driver here (NO Tidal — cut 2026-05-18) |
| Phase 2b | 640–940 | Spec Sheet identity move + low-latency audio engines + 2 published libraries |
| Phase 3 | 790–1190 | Room correction deployed (R&D phase, may require C++) |

---

## 7. Audio pipeline architecture

### 7.1 Common layer (KMP)

```
MediaItem
  ↓
MusicSource.getPlayable(itemId): Either<SourceError, Playable>
  ↓
Playable (codec-aware stream or file pointer)
  ↓
PlatformPlayer interface
  ↓
[platform-specific implementations dispatch]
```

### 7.2 Android pipeline

```
PlatformPlayer (Android)
  → Media3 ExoPlayer
  → Custom render factory
      → ReplayGain processor
      → ParametricEqProcessor (adapter wrapping :audio:dsp pure-Kotlin EQ)
      → VisualizerTee (adapter wrapping :audio:visualizer FFT)
      → AudioTrack with ENCODING_PCM_FLOAT

Phase 2b addition: AAudio MMAP path for bit-perfect output to USB DAC
```

### 7.3 Desktop pipeline (JVM)

```
PlatformPlayer (JVM)
  → Custom JVM audio pipeline
      MVP:        Java Sound (javax.sound.sampled.SourceDataLine)
      Phase 2b:   WASAPI via JNI bridge
  → ReplayGain processor (shared pure-Kotlin code)
  → ParametricEqProcessor (adapter wrapping :audio:dsp — same pure-Kotlin source as Android)
  → VisualizerTee (adapter wrapping :audio:visualizer — same source)
  → AudioOutput

Phase 2b addition: WASAPI exclusive-mode for bit-perfect output
```

### 7.4 Capture path (stubbed in MVP, for phase 3 room correction)

```
PlatformPlayer.MeasurementMode
  → AudioCapture
      Android:  AudioRecord
      JVM:      javax.sound.sampled.TargetDataLine
  → SampleBuffer (latency-tagged)
  → FFT correlator (sweep response analysis)
  → CurveSynthesizer (generates corrective EQ curve)
  → ParametricEqProcessor (apply correction)
```

The path, latency-tagged buffer types, and capture abstraction are built at MVP. The algorithm is plugged in at phase 3 — "wire up the math" rather than "design the path from scratch."

---

## 8. Test strategy (aggressive full pyramid)

### 8.1 Infrastructure

- `kotlin.test` — KMP test framework (in `commonTest` of inner modules)
- JUnit5 — JVM-side outer modules (Android + Desktop entry points)
- Mokkery — KMP-friendly mocking
- Turbine — Flow testing
- Kotest property-based — for DSP math correctness properties
- Compose UI test (Compose-MP test artifact) — critical user flows
- Paparazzi (Android) / Roborazzi or Compose-MP screenshot — screenshot regression
- Microbenchmark / JMH — DSP performance regression alerts
- Android Accessibility framework audits — accessibility checks
- E2E playback flow tests — emulator (Android) / headless JVM (Desktop)

### 8.2 Coverage targets

| Module | Target | Notes |
|---|---|---|
| `:audio:dsp` | ~95% + property-based + mutation testing | Math correctness critical for publishable library |
| `:audio:visualizer` | ~90% + property-based | FFT math correctness |
| `:audio:playback` | ~80% | Platform adapters can't be fully tested without instrumentation |
| `:data:library` | ~75% | `MusicSource` interface conformance |
| `:ui:theme` | ~85% | Palette extraction + contrast post-processing is deterministic |
| `:ui:components` | UI tests for critical compositions; screenshot tests on stable components; Circuit presenter tests for Now Playing | |
| `:app-android`, `:app-desktop` | Light smoke tests + E2E flows | Wiring code |

### 8.3 CI strategy

- GitHub Actions matrix: Android (API 21, 28, 33, 36) × JVM (17, 21)
- Inner-module tests run on JVM only — fast feedback per PR
- Outer-module tests gated to PR runs
- Screenshot updates require explicit human review
- Performance benchmarks alert on >5% regression on hot paths

### 8.4 What this depth buys

- Publishability of extracted libraries (tests serve as their de-facto spec)
- Refactor safety when phase-2 features land
- Portfolio signal — property-based tests on filter math + screenshot tests on Compose UI + accessibility audits read as a serious engineering project
- Safety net for soft-locked tech-stack pieces — if one needs swapping, tests catch behavioral regressions

---

## 9. License + distribution

### 9.1 License

**Apache License 2.0** across all modules. Each module includes:
- `LICENSE` file (Apache 2.0 text)
- `NOTICE` file (attribution to Clay Haworth / Clayworks)
- Optional source-file headers (conventional but not required by Apache 2)

### 9.2 Distribution

- **Android:** GitHub releases (APK), no Play Store initially
- **Windows:** jpackage produces `.msi` or portable `.exe`; GitHub releases
- **Libraries (phase 2b):** JitPack initially (free, no review process); Maven Central later if adoption warrants
- **Code signing:** optional initially; both platforms warn on unsigned binaries but install. Add when audience expands.

---

## 10. Named patterns and forces (carried from brainstorm)

These working labels make decisions debuggable in future sessions. Use them by name when you notice the force at play.

- **Software-as-Self-Portrait** — the portfolio narrative is load-bearing; every decision evaluated through both "serves library" and "serves architecture story" lenses
- **Personal OS for Listening** — Kiln is an integrated listening environment, not just an app; integration points matter more than individual feature polish
- **Bus-Factor-of-One** — modules must pass the "explain in 200 words" test before extraction as published libraries
- **Curator's Trap** — Clay's 39,500-track perfectly-tagged library doesn't generalize; conscious choice required between personal-tool and audience-tool
- **Architecture as Performance Art** — module polish satisfaction is real but can absorb feature-work hours; schedule polish-mode vs feature-mode sessions consciously
- **Termux Tax** — silent compounding cost of Python-subprocess dependency on Android. _Historical: Kiln avoids this entirely by cutting Tidal-via-Termux on 2026-05-18. Pattern name retained for vocabulary continuity if Tidal or similar Python-bridged source is ever reconsidered._
- **Concentric Modules** — inner core (`:audio:dsp`, `:audio:visualizer`) is platform-free; outer rings add platform deps as needed
- **The Source Protocol** — `MusicSource` interface + capability flags; no source-specific branching in the codebase
- **Mastering Engineer's Apartment** — aesthetic frame: clinical instruments arranged with care, not sterile lab

---

## 11. Out of scope (anti-roadmap)

Features explicitly NOT planned for any phase, listed so they don't quietly accrete. Each rejection has a reason.

- **Spatial audio / Dolby Atmos** — purist 2-channel scope; HRTF black-box processing destroys bit-perfect output
- **AI / LLM features** — auto-DJ, vector recommendations, mood detection. Curated library + deterministic SQL search is the model.
- **Cross-device handoff** — considered as differentiator option, rejected (pulls toward multi-app project, away from audio-precision identity)
- **MIDI controller for EQ params** — considered as differentiator option, rejected (reads as party trick, narrow audience)
- **iOS support** — not architecturally blocked but not planned (effort: ~150 hrs)
- **Linux support** — not blocked but not planned (effort: ~80 hrs)
- **macOS support** — not blocked but not planned (effort: ~100 hrs)
- **Wear OS / Android TV / Auto / Android Tablet-optimized** — none of these are Clay's listening contexts
- **Tag editing** — filesystem is treated as read-only; metadata managed in a desktop tool (Mp3tag, Picard) before files land in the library
- **Lyrics display** — clutters the UI and distracts from the FFT/DSP focus
- **Last.fm scrobbling** — legacy web-2.0 feature; local-private listening history sufficient if needed
- **Bluetooth codec readouts** — Kiln optimizes the wired-DAC path; BT is fallback, not focus
- **Podcasts, internet radio, hardware-button macros** — out of scope; different product
- **Tidal integration (and streaming sources in general)** — _cut 2026-05-18 after risk review._ Clay's 39,500-track curated FLAC library satisfies 99%+ of listening; Tidal-via-Termux was the highest-risk variable in the project (Gemini adversarial critique flagged Android 14+ background-process fragility); benefit-to-risk ratio did not justify. `MusicSource` interface remains open should a low-risk streaming source ever be desired (e.g., self-hosted Navidrome/Subsonic).

If any of these become genuinely valuable later, they get a separate spec discussion. Not silent accretion.

---

## 12. Risks and mitigations

| Risk | Mitigation |
|---|---|
| KMP audio pipeline complexity (different APIs per platform) | Pure-Kotlin DSP isolates platform risk; adapters small and focused; aggressive testing on shared math |
| Compose MP desktop maturity gaps | Stick to widely-tested Compose-MP APIs in MVP; defer fancy desktop-specific UX to phase 2 |
| Phase 2b audio-engine rewrites underestimated | AAudio MMAP and WASAPI are NOT adapter swaps over Media3/Java Sound — they replace the engine. Estimates revised upward 2026-05-18. Soft-lock revisit at end of Phase 2a: confirm low-latency push is still the right next move OR commit to ExoPlayer/Java Sound permanently |
| Compose MP Desktop LazyColumn perf with 39.5k tracks | Pre-MVP smoke test scheduled (40k mocked items in Compose MP Desktop). If stutter detected, plan custom virtualization between SQLDelight and UI state |
| Motivation lulls during 790–1190 hr trajectory | Phase progression provides ship-moments; each phase = satisfaction milestone; §1 "why now" doc anchors during dips |
| Architecture-as-Performance-Art trap | Schedule polish/feature modes consciously; the named-pattern labels are debuggable handles to call this out |
| Curator's Trap (assumes perfectly-curated library) | Build for Clay's library first; if extracting libraries publicly, design with less-perfect-data fallbacks |
| Soft locks shifting unexpectedly | Each soft lock has stated reasoning in §2; revisits must include a new variable, not just a mood |
| Daily-driver table stakes (Audio Focus / MediaSession / BLE / SMTC) | Added explicitly to MVP after Gemini critique 2026-05-18. Without these the v1.1.0 "daily driver replacement" claim was hollow |

---

## 13. Open questions for the next step

These are for `/superpowers:writing-plans` and/or `/octo:plan` to address — not for this design spec to answer.

- Specific module-by-module build sequence (which module gets scaffolded first; dependency order)
- Per-module deliverable boundaries (what defines "module N is done"?)
- Per-phase exit criteria (what defines "MVP-1.0 is ready to ship"?)
- Test infrastructure setup order (when does each tool come in during the build sequence?)
- First-build milestone (when does the first APK build happen? first Desktop binary?)
- External dependencies to evaluate before adoption (e.g., specific Compose-MP-compatible image loading library version; Compose-MP-compatible Palette extractor)
- Repository scaffolding (new GitHub repo, initial branch structure, CI from day one or after MVP)

---

## 14. Reference: where this came from

This design spec consolidates decisions from a long session on 2026-05-18. Inputs included:

- A total-project review of the JAMZ!!! codebase (Explore agent, ~3000-word report)
- A multi-LLM brainstorm via `/octo:brainstorm` (Gemini + Ollama qwen3:14b + Claude pattern-spotter agent) on six divergent questions
- A structured `/superpowers:brainstorming` round on eight open agenda items, one question at a time
- Engram memory entries persisting strategic and architectural commitments across sessions

For session continuity, the key engram memory topic keys are:
- `strategy/rebuild-pivot` — fresh-build pivot decision
- `architecture/project-shape` — modular monorepo with library extraction
- `architecture/native-audio-scope` — hificore dropped, AAudio MMAP phase 2
- `architecture/four-pillars` — the four octo:brainstorm commitments
- `patterns/named-forces` — the named patterns vocabulary

End of design spec.
