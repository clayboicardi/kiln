# Kiln by Clayworks — Execution Plan

**Date:** 2026-05-18
**Author:** Claude Opus 4.7 (1M context) for Clay Haworth (clayboicardi)
**Status:** Plan revised 2026-05-18 — Tidal integration cut after Gemini adversarial critique + Clay's usage assessment; phase estimates updated for realism
**Spec reference:** `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`
**Intent contract:** `.claude/session-intent.md`

This plan turns the locked design spec into a session-actionable execution sequence. It does not execute any code.

---

## 0. Plan philosophy

Three operating principles drive every choice below:

1. **First builds early.** Both Android APK and Windows Desktop binary should build *something* (even a "Hello Kiln" placeholder) within the first ~15 hours of work. Motivation anchors matter more than feature completeness in early sessions.
2. **Vertical slices over horizontal layers.** Build one feature end-to-end at a time (e.g., "scan a library + play a track" before any UI polish), not "all data layer first, then all UI." Each session ships visible progress.
3. **Test infrastructure spreads gradually.** Don't set up the full testing pyramid before any feature exists. Each test tool comes in when its first concrete use case appears (the "stand up the tool when you need it" principle).

---

## 1. Phase progression overview

```
Pre-MVP Research      ~22-32 hrs    (Claude-led library vetting + system integration prep)
   ↓
MVP-1.0 Foundation    ~305-435 hrs  (working music player both platforms with daily-driver system integration)
   ↓  Ship checkpoint — Kiln runs on Pixel + Windows; JAMZ remains daily driver
Phase 2a JAMZ Parity  ~130-195 hrs  (Kiln matches JAMZ daily-use feature set — no Tidal)
   ↓  Switch daily driver — Kiln replaces JAMZ
Phase 2b Beyond JAMZ  ~205-310 hrs  (Hardware Spec Sheet, 2 published libs, AAudio + WASAPI engine rewrites)
   ↓
Phase 3 Differentiator ~150-250 hrs (REW-style room correction — DSP R&D, may need C++)
```

**Total: 812-1222 hrs across many sessions. No rush — operating constraint.**

Revised 2026-05-18: original estimate was 580-1015 hrs. Tidal cut saved ~100-150 hrs; Gemini-driven realism adjustments to Phase 2b (audio-engine rewrites, not adapter swaps) and Phase 3 (room correction is DSP R&D) added ~250-360 hrs. Net upward despite Tidal cut.

At a sustained 2 sessions per week (4-8 hrs each), the trajectory is approximately:
- Pre-MVP Research: 2-4 weeks
- MVP-1.0: 5-12 months
- Phase 2a: 3-8 months
- Phase 2b: 3-6 months
- Phase 3: 2-4 months
- **Cumulative calendar: ~15-34 months from start to Phase 3 complete**

At 1 session per week, double the calendar. Wide ranges acknowledge that unknown-unknowns exist and that the soft-lock items (differentiator, tech stack pieces, test depth) may be revisited mid-project.

---

## 2. Pre-MVP Research (~22-32 hrs, Claude-led)

Before scaffolding any module, Claude needs to do library research that Clay shouldn't be on the hook for. These are decisions where Claude's current knowledge is stale or incomplete and the cost of picking wrong is high (Bus-Factor-of-One amplified by stack-piece miscommitment).

**This phase is research only — no code is committed.** Output is a `docs/decisions/2026-MM-DD-library-vetting.md` document recording verified library versions, gotchas found, and any soft-lock revisitations triggered.

### 2.1 Research items to settle before scaffolding

| # | Item | Why | Estimated hours |
|---|---|---|---|
| 1 | **Compose Multiplatform desktop stability** | Verify current (2026) state of Compose MP for desktop. Specific concerns: window management APIs, system tray support, native menu integration, keyboard shortcut handling, file picker integration | 3-5 |
| 2 | **KMP-compatible image loader** | Coil 3 has Compose-MP support as of 2024-2025. Verify it handles disk caching properly on desktop, whether it supports Palette extraction (might need separate library) | 1-2 |
| 3 | **KMP-compatible Palette/color extractor** | Android's Palette library is androidx-only. Multiplatform alternatives needed: candidates include `kotlin-palette-extractor`, JS-port `Pal.Js`, or rolling our own using pixel-sampling. Critical for Kiln Dynamic theming. | 3-5 |
| 4 | **Voyager vs Decompose final decision** | Spec says Voyager. Verify Voyager handles the navigation needs (deep stack, save-state across config changes, multi-screen flows). If insufficient, swap to Decompose. Soft-lock revisit possible. | 2-3 |
| 5 | **Circuit + Molecule on KMP** | Spec (revised 2026-05-18) puts Circuit in `:ui:components` Now Playing screen. Vet maturity of Circuit on Compose-MP, KMP-specific gotchas, Slack's recent activity on the libraries | 2-3 |
| 6 | **SQLDelight schema design for 39.5k tracks** | Sketch schema (artists/albums/tracks/playlists/listening_history) and index strategy for fast search. Performance assumptions: filterable in <100ms | 2-3 |
| 7 | **Compose-MP screenshot testing** | Paparazzi is Android-only. Roborazzi works on Compose-MP. Verify Roborazzi maturity for desktop. Alternative: Compose-MP's own test artifact. | 1-2 |
| 8 | ~~Termux RUN_COMMAND on Android 14+~~ | _Removed 2026-05-18: Tidal cut from scope; Termux dependency eliminated._ | _was 2-3_ |
| 9 | **Java Sound capability survey on Windows** | What sample formats/rates/channels does Java Sound handle on Windows? Latency expectations? Limits define MVP audio capability before WASAPI phase-2b. | 2-3 |
| 10 | **jpackage / Windows distribution** | Vet jpackage output options (MSI vs portable EXE vs MSIX) and what code signing actually requires in 2026. | 1-2 |
| 11 | **System integration patterns (added 2026-05-18 post-Gemini critique)** | Vet Android Audio Focus APIs, MediaSession + lock-screen integration, BLE-disconnect handling, hardware media keys, Windows SMTC (System Media Transport Controls). Daily-driver table stakes — without these, the v1.1.0 parity claim is hollow. | 2-3 |
| 12 | **Compose MP Desktop LazyColumn perf with 40k items (added 2026-05-18)** | Spike: render a 40k-item LazyColumn on Compose MP Desktop with mocked SQLDelight-style backend. If stutter detected, plan custom virtualization in MVP. Gemini flagged this as Desktop-Compose blind spot. | 1-2 |
| 13 | **Audio backend architecture decision (added 2026-05-18)** | Critical question Gemini surfaced: AAudio MMAP and WASAPI are NOT interchangeable adapters with Media3 ExoPlayer / Java Sound — they replace the engine. Choose: (a) commit to ExoPlayer/Java Sound permanently and drop Phase 2b Flights I+J, OR (b) plan from MVP for an engine-swap-shaped boundary in `:audio:playback`. Soft-lock revisit. | 2-3 |

### 2.2 Pre-MVP exit criteria

- ✅ Decision log committed at `docs/decisions/2026-MM-DD-library-vetting.md`
- ✅ Specific library versions identified for all 12 active items (item 8 Termux is closed-out; revised count = 12 active items)
- ✅ Any soft-lock revisits flagged with rationale (Voyager swap, audio-backend architecture commit, etc.)
- ✅ Schema sketch committed at `docs/decisions/2026-MM-DD-sqldelight-schema-sketch.md`
- ✅ Audio backend architecture decision (item 13) explicitly recorded — does Phase 2b Flights I+J stay in scope, or do we commit to ExoPlayer/Java Sound permanently?
- ✅ Clay reviews + acknowledges decisions before scaffolding starts

---

## 3. MVP-1.0 Foundation (~305-435 hrs)

The goal of MVP-1.0 is a working music player on both platforms with all phase-2 architectural seams wired in (even when stubbed). Per spec §6.1.

### 3.1 New Kiln repo scaffold (Sessions 1-3, ~12-20 hrs total)

**First action of MVP work:** Create new GitHub repo (Clay's call: `clayboicardi/kiln` or another name).

**Initial structure:**
```
kiln/
├── .github/workflows/      CI scaffolding (see §6)
├── docs/
│   ├── DESIGN.md           Copy of locked spec
│   ├── PLAN.md             Copy of this plan
│   └── decisions/          Technology decision log (from Pre-MVP Research)
├── gradle/                 Wrapper + libs.versions.toml
├── build-logic/            Gradle convention plugins for module shape
├── app-android/
├── app-desktop/
├── audio/
│   ├── dsp/
│   ├── visualizer/
│   └── playback/
├── data/
│   └── library/
├── ui/
│   ├── theme/
│   └── components/
├── settings.gradle.kts
├── build.gradle.kts
├── LICENSE                 Apache 2.0
├── NOTICE                  Clay Haworth / Clayworks attribution
└── README.md               Minimal initial README
```

**Public-or-private repo?** This is a Clay decision. Recommendation: develop publicly from day one — embracing the work-in-progress signal aligns with the Software-as-Self-Portrait pattern. The half-built state isn't embarrassing; it's evidence of process.

### 3.2 Build order — vertical slice, not horizontal layers

Sessions are 4-8 hours each. The sequence below front-loads "something runs on both platforms" as quickly as possible.

#### Sessions 1-3: Foundation skeleton (~12-20 hrs)
- Set up Gradle KMP with all modules created (empty `commonMain`)
- Basic build verification: `:app-android:assembleDebug` and `:app-desktop:run` both succeed
- LICENSE / NOTICE / README in place
- First Android APK builds (empty placeholder activity)
- First Windows Desktop binary runs (empty placeholder window)
- **First-build milestone: "Hello Kiln" runs on Pixel + Windows**
- Initial CI: GitHub Actions builds both platforms on every push (`pull_request` + `main` push)

#### Sessions 4-7: Library + playback vertical slice (~20-30 hrs)
- `MusicSource` interface in `:data:library` `commonMain` — designed first per spec invariant
- `LocalLibrarySource` implementation:
  - Android adapter: MediaStore scanner
  - Desktop adapter: filesystem walker + tag-reader library (e.g., jaudiotagger via JVM)
- `PlatformPlayer` interface in `:audio:playback` `commonMain`
- Android adapter: Media3 ExoPlayer
- Desktop adapter: Java Sound (`SourceDataLine`)
- Wire it up: tap "play" on any track in the library → it plays on both platforms
- **Vertical-slice milestone: play a FLAC from your library on both platforms**

#### Sessions 8-11: Library UI (~16-24 hrs)
- `:ui:theme` setup with Kiln Warmth idle palette (Dynamic comes in phase 2a)
- Songs / Albums / Artists list views in `:ui:components` (Compose MP)
- Voyager navigation skeleton wired up
- Basic search (single-bucket, not sectioned yet — sectioning is phase 2a)
- **UI milestone: browse the library on both platforms**

#### Sessions 12-15: Now Playing + queue (~16-24 hrs)
- Now Playing screen (basic, no blurred art yet) — **Circuit Presenter/UI showcase lives here** (relocated 2026-05-18 from cut `:data:streaming-tidal` module). MVI pattern fits Now Playing's complex state space: playing/paused/buffering/stopped/seeking, queue context, playback position, EQ-applied state.
- Queue management: enqueue tracks, skip / seek, repeat / shuffle
- Mini-player surface (sticky bottom strip)
- **Playback milestone: navigate library, queue tracks, see what's playing**

#### Sessions 16-22: Parametric EQ port (~28-40 hrs)
- `:audio:dsp` `commonMain`: BiquadFilter (pure Kotlin, re-derived from spec/algorithms, not copied from JAMZ source)
- `:audio:dsp` `commonMain`: ParametricEqProcessor — accepts band parameters, processes float PCM
- `:audio:dsp` Arrow showcase: typed errors (`Either<EqError, EqResult>`) for malformed configs
- `:audio:dsp` `androidMain`: Media3 `BaseAudioProcessor` adapter wrapping the pure-Kotlin processor
- `:audio:dsp` `jvmMain`: Java Sound processor adapter wrapping the pure-Kotlin processor
- EQ UI in `:ui:components`: 31 sliders, presets list, save/load
- Property-based tests for BiquadFilter math (Kotest property) — this is the first place property-based testing earns its keep
- **DSP perf smoke test (added 2026-05-18 post-Gemini critique):** verify 31-band biquad processes a 10-second float-PCM buffer without GC pauses BEFORE building the EQ UI on top. JMH/Microbenchmark scaffolding lands here, not Phase 2a Flight F. Pre-allocated primitive arrays in the hot loop verified.
- **EQ milestone: parametric EQ works on both platforms with the same pure-Kotlin core**

#### Sessions 23-25: System Integration (added 2026-05-18 post-Gemini critique, ~25-35 hrs)
Daily-driver table stakes. Without these, the v1.1.0 "JAMZ parity replacement" claim is hollow.
- Android Audio Focus (pause for phone calls, ducking for notifications)
- Android MediaSession + lock-screen controls + notification media controls
- BLE-disconnect handling (pause when headphones disconnect)
- Hardware media keys (Bluetooth headphone play/pause, Pixel buttons)
- Windows SMTC (System Media Transport Controls) — lock screen + notification + keyboard media keys
- Audio device-change handling (USB-C dongle plug/unplug, default device switch)

#### Sessions 26-28: Settings, preferences, polish (~12-20 hrs)
- Scan-folder settings (where to look for library)
- EQ preset management UI (save, load, delete, rename)
- Light typography pass with Plex Sans / Plex Mono fonts
- App icon assets
- **Final MVP milestone: MVP-1.0 complete; tag `v1.0.0-mvp` and ship to your devices**

### 3.3 MVP-1.0 exit criteria

**MVP is "ready to ship" when:**

- ✅ Both Android APK and Windows Desktop binary build cleanly from a fresh clone
- ✅ CI passes (lint, unit tests, build matrix per spec §8.3)
- ✅ All locked features work on Clay's Pixel 10 Pro XL:
  - Library scans his FLAC folders, indexes 39.5k tracks within ~5 minutes
  - Songs / Albums / Artists views populate from the local index
  - Tap track → plays; queue works; skip / seek work
  - Search returns results (basic single-bucket; sectioning is phase 2a)
  - 31-band parametric EQ has presets, sliders, save / load
  - Now Playing shows current track with basic info
- ✅ Same set of features works on Clay's Windows desktop
- ✅ App doesn't crash during 1 hour of typical use
- ✅ All architectural seams (phase-2 stubs) are in place per spec §6.1:
  - `MusicSource` interface kept open for future expansion (no `TidalSource` planned; Tidal cut 2026-05-18)
  - Audio pipeline accepts processor plugins (visualizer slot reserved)
  - Theme engine has dynamic-extraction plug points (stubbed, returns idle palette)
  - Mic-capture path exists in `:audio:playback` (stubbed; for phase-3 room correction)
  - Hardware Spec Sheet route reserved in nav graph (renders TBD placeholder)
- ✅ **System integration verified** (added 2026-05-18): Audio Focus pauses for phone calls; MediaSession shows on lock screen; SMTC works on Windows; hardware media keys work both platforms; BLE disconnect pauses playback
- ✅ Test coverage targets met per spec §8.2 for shipped modules
- ✅ DSP perf verified (no GC pauses on 10-second buffer)
- ✅ README + DESIGN.md + PLAN.md in repo
- ✅ Tag the commit `v1.0.0-mvp`

---

## 4. Phase 2a — JAMZ Parity (~130-195 hrs)

After MVP ships, work in **feature flights**. Each flight is a coherent set of changes shipped together. Each flight ends with a tagged release.

Phase 2a effort revised down 2026-05-18 after Tidal Flight D was cut (saved 50-80 hrs). Remaining 5 flights below.

### Flight A: Kiln Dynamic theming (~30-50 hrs)
- Implement color extraction pipeline using vetted KMP Palette library (from Pre-MVP Research)
- Implement contrast post-processing (WCAG AA enforcement)
- Wire dynamic palette through `:ui:theme` to all UI surfaces per spec §5.3
- Test with diverse album art (low saturation, bright pastels, monochrome covers, no-art-available)
- Screenshot tests with Roborazzi for theme regressions (this is the first place screenshot testing earns its keep)
- **Ship checkpoint: tag `v1.0.1-dynamic-theming`**

### Flight B: Blurred album art + Now Playing polish (~10-15 hrs)
- Blur effect on album art background of Now Playing
- Now Playing surface refinement (animations, transitions, gesture handling)
- **Ship checkpoint: tag `v1.0.2-now-playing`**

### Flight C: EQ refinements (~35-50 hrs)
- Frequency-response curve display widget (visualize the EQ curve as user moves sliders)
- Energy-preserving crossfade between EQ presets (cos/sin crossfade window, ~10ms)
- ProGuard rules verified for `:audio:dsp` on release builds
- **Ship checkpoint: tag `v1.0.3-eq-complete`**

### ~~Flight D: Tidal integration~~ — _CUT 2026-05-18_
Original scope: `:data:streaming-tidal` with Circuit Presenter/UI showcase + Termux bridge on Android + direct Python tiddl on Desktop. Cut after Gemini adversarial critique flagged Android 14+ background-process fragility as the project's highest-risk variable, AND Clay's usage assessment confirmed his 39,500-track curated library satisfies 99%+ of listening. The Circuit showcase relocated to Now Playing in MVP Sessions 12-15. The `MusicSource` interface stays open for future low-risk streaming sources (Subsonic/Navidrome) if ever desired.

### Flight D (formerly E): Sectioned search (~15-20 hrs)
- Search results section by Songs / Albums / Artists (matches JAMZ behavior)
- Local-library-only sectioning (no cross-source unification needed; only one source in scope)
- Search performance: results render in <500ms for typical queries on 39.5k track index
- **Ship checkpoint: tag `v1.0.4-search`**

### Flight E (formerly F): FFT visualizer (~40-60 hrs)
- `:audio:visualizer` `commonMain`: pure-Kotlin FFT (re-derived, not copied from JAMZ)
- `:audio:visualizer` `androidMain`: Media3 `BaseAudioProcessor` adapter
- `:audio:visualizer` `jvmMain`: Java Sound processor adapter
- Visualizer view in `:ui:components` (Canvas-based, 60fps target)
- Wire visualizer into Now Playing
- Property tests for FFT math (second use of property-based testing — first was BiquadFilter at MVP Session 16)
- **Ship checkpoint: tag `v1.1.0-jamz-parity` (this is when Kiln replaces JAMZ as daily driver — minus Tidal, which is intentionally out of scope per anti-roadmap)**

### Phase 2a exit criteria
- ✅ All JAMZ-parity features implemented and tested
- ✅ Daily use can be migrated from JAMZ to Kiln
- ✅ At least 1 week of dogfood-testing without daily-blocking bugs
- ✅ Tag `v1.1.0-jamz-parity`

---

## 5. Phase 2b — Beyond JAMZ (~205-310 hrs)

Phase 2b effort revised upward 2026-05-18 after Gemini critique flagged that AAudio MMAP and WASAPI are **audio-engine rewrites, not adapter swaps** over Media3 ExoPlayer / Java Sound. They require their own threading models, buffer management, and integration patterns. Phase 2b items I and J are functionally new audio engines.

**Soft-lock revisit before starting Flights I+J:** confirm low-latency push is still worthwhile after dogfooding through 2a. If ExoPlayer/Java Sound proves "good enough," cut these flights and ship Phase 2b without them. Pre-MVP Research item #13 surfaces this same decision earlier in the project.

### Flight F: Hardware Spec Sheet About screen (~20-30 hrs)
The identity move. Replace the conventional About screen with a Hardware Spec Sheet that lists DSP internal float bit-depth, filter algorithms used, pipeline latency, codec support, sample rate handling, etc. Treat it like a hi-fi product spec sheet.

### Flight G: Library extraction (~30-50 hrs)
- Publish `:audio:dsp` and `:audio:visualizer` to JitPack (2 libraries, reduced from 3 after `:data:streaming-tidal` cut on 2026-05-18)
- Each library gets its own README, CONTRIBUTING, CHANGELOG
- Versioning strategy: semver starting from `0.1.0` (pre-1.0 to signal API-stabilizing)
- Code Connect / Maven coordinates documented
- **Multi-LLM review checkpoint before each library's first tagged release** (Gemini second-opinion on API surface)

### Flight H: AAudio MMAP on Android (~80-120 hrs)
Revised from 50-80 hrs after Gemini critique. **This is a new audio engine, not an adapter swap.** Requires:
- Implement bit-perfect AAudio MMAP path for Android (public API; not the `hificore` approach)
- New threading model: real-time priority audio callback thread that pulls from a ring buffer, decoupled from Media3 ExoPlayer's threading
- Track-transition logic re-implemented for the AAudio path (ExoPlayer's gapless playback doesn't carry over)
- Decoder integration: directly decode FLAC/etc. into the AAudio ring buffer (or hand off from Media3 decoders to AAudio queue)
- Verify on Pixel 10 Pro XL with USB-C dongle
- Latency measurement + reporting in Hardware Spec Sheet

### Flight I: WASAPI shared-mode on Windows (~80-120 hrs)
Revised from 40-60 hrs after Gemini critique. **Also a new audio engine.** Requires:
- JNI bridge to WASAPI shared mode (thread-safe; handle device disconnects, sample-rate conversion, buffer underruns)
- New threading model: WASAPI callback thread separate from Java Sound's
- Track transitions re-implemented for the WASAPI path
- Comparable spec-sheet reporting

### Phase 2b exit criteria
- ✅ Hardware Spec Sheet identity move shipped
- ✅ 2 libraries publicly published at JitPack with their own README/CHANGELOG
- ✅ Low-latency audio paths verified on both platforms (IF Flights H+I stay in scope after the soft-lock revisit)
- ✅ Tag `v1.2.0-beyond-jamz`

---

## 6. Phase 3 — Room Correction (~150-250 hrs)

Phase 3 effort revised upward 2026-05-18 after Gemini critique flagged this as **DSP R&D, not a 3-week sprint**. Sample-accurate mic-latency alignment across Android USB stacks + sweep generation + FFT response analysis + minimum-phase curve synthesis are genuinely hard. May require dropping to C++ (Oboe on Android) if pure-Kotlin proves insufficient for sample-accurate timing.

The differentiator. **Soft-lock revisit point** before starting: confirm room correction still makes sense after dogfooding through 2a+2b. If a different differentiator has emerged as more valuable in real use, surface it explicitly and have the revisit conversation.

If proceeding with room correction:
- Measurement-mic capture path activation (the stubbed seam from MVP)
- Sweep playback + capture timing alignment (sample-accurate latency measurement)
- Response analysis (FFT-based, using the same visualizer FFT code where possible)
- Curve synthesis (corrective EQ generation — minimum-phase or linear-phase, decision TBD)
- Integration with parametric EQ preset system (correction lives as one preset on top of user EQ)
- UI for measurement workflow (place phone → measure → save as preset)

### Phase 3 exit criteria
- ✅ Measurement workflow works with Pixel's built-in mic
- ✅ Calibrated USB mic support if Clay obtains one
- ✅ Generated correction curve audibly improves listening (Clay's ear is the final acceptance test)
- ✅ Tag `v1.3.0-room-correction`

---

## 7. Test infrastructure timeline

When each test tool comes in, mapped to the session where its first use case lands. Don't pre-install anything before a real use case justifies it.

| When | Tool | First use case |
|---|---|---|
| MVP Session 1-3 (scaffold) | `kotlin.test` + JUnit5 + GitHub Actions CI | Smoke tests; build matrix passes |
| MVP Session 4 (library vertical slice) | First unit tests on `MusicSource` interface conformance | |
| MVP Session 6 (playback) | Mokkery for mocking platform adapters | |
| MVP Session 8 (UI start) | Compose UI test infrastructure | Critical-path UI tests |
| MVP Session 12 (Now Playing) | Turbine for Flow testing on state holders | |
| MVP Session 16 (EQ port) | Kotest property-based for BiquadFilter math | DSP correctness is the killer-app for property tests |
| **MVP Session 16 (EQ port, revised 2026-05-18)** | **Microbenchmark/JMH for DSP perf smoke test** | **Verify 31-band biquad processes 10s float-PCM buffer without GC pauses BEFORE building EQ UI. Pre-allocated primitive arrays in hot loop. (Moved earlier per Gemini critique — was Phase 2a Flight F.)** |
| Phase 2a Flight A | Roborazzi screenshot tests for Kiln Dynamic theming | Catches theme regressions across diverse album art |
| Phase 2a Flight E (FFT visualizer) | Microbenchmark/JMH extended to FFT hot path | Performance regression alerts on visualizer math |
| Phase 2b Flight H (extraction) | Mutation testing on extracted libraries | Library-quality gate before tag |
| Phase 2b ongoing | Accessibility audits (Android Accessibility framework) | Compose accessibility checks |
| Phase 3 | E2E flow tests for measurement workflow | Multi-step user flow validation |

CI scaffolding (per spec §8.3) lands in MVP Session 1-3. Matrix expansion (Android API 21/28/33/36 × JVM 17/21) lands when the first feature actually requires testing across versions (typically MVP Session 4-6).

---

## 8. External dependency vetting schedule

**Vetted during Pre-MVP Research (already enumerated in §2.1):**
- Compose Multiplatform desktop maturity
- Coil 3 KMP for image loading
- KMP-compatible Palette/color extractor
- Voyager vs Decompose final call
- SQLDelight schema sketch
- Roborazzi for Compose-MP screenshot testing

**Vetted just-in-time during phase progression:**
- AAudio MMAP API surface (before Phase 2b Flight I)
- WASAPI JNI binding approach (before Phase 2b Flight J)
- USB calibrated mic SDK / driver options (before Phase 3)

**Multi-LLM second opinion warranted on:**
- Initial library-vetting decisions (end of Pre-MVP)
- Public library APIs before stable tags (Phase 2b Flight H)
- Phase 3 algorithm choices (minimum-phase vs linear-phase correction)

---

## 9. Risk-management checkpoints

### Soft-lock revisitations
- **End of Pre-MVP Research:** confirm tech stack soft lock (spec item 4). Did vetting reveal issues with Voyager/Circuit/Kotlin-Inject?
- **End of MVP-1.0:** confirm test strategy soft lock (spec item 7). Are tests adding value or absorbing time disproportionately?
- **End of Phase 2b:** confirm differentiator soft lock (spec item 2). Still want room correction, or has a different priority surfaced?

### Trap-detection prompts (at the start of every session)

A literal session-start checklist item:
- **"Architecture as Performance Art" check:** "Am I about to spend this session polishing module structure / Gradle configuration / README aesthetics, vs building a feature that ships?"
- **"Curator's Trap" check:** "Am I assuming Clay's 39,500-track perfectly-tagged library is the canonical use case in a way that will break publishability of `:data:library` later?"
- ~~**"Termux Tax" check:**~~ _Historical: no longer applies after Tidal cut on 2026-05-18. Pattern name retained in vocabulary for future Python-bridged source consideration if ever needed._
- **"Bus-Factor-of-One" check:** "Could Clay read what I just wrote in 6 months without me in the session?"

### Risk triggers requiring explicit conversation with Clay
- KMP audio pipeline blocks on a platform-specific issue not anticipated in vetting
- A library reveals problems mid-flight that warrant a soft-lock revisit
- Effort overrun on a flight (>50% over estimate) — flag and discuss adjusting scope
- A new variable arrives that affects scope (new Tidal API change, Android version policy, Compose-MP regression, etc.)
- Anything that would change a hard lock (renames, target additions, license changes, MVP scope changes)

---

## 10. Per-phase ship cadence

| Phase | Ship cadence |
|---|---|
| Pre-MVP | No shipping. Just research + decision log committed. |
| MVP-1.0 | Build to device every 2-3 sessions for the first 10 sessions; every session for the last 5 sessions before MVP exit. Each session ends with a working APK + binary committed and pushed. |
| Phase 2a | Ship per flight (5 flights through phase 2a → 5 tagged releases; revised from 6 after Tidal Flight D cut) |
| Phase 2b | Ship per flight (4 flights → 4 tagged releases; Flights H+I may be combined or skipped if soft-lock revisit chooses ExoPlayer/Java Sound permanently) |
| Phase 3 | Intermediate ship at "measurement working, correction synthesis not yet" + final ship at v1.3.0-room-correction |

Each ship event = pushed commit + tagged release on GitHub. Tags follow semver where `v1.0.0-mvp` is MVP, `v1.0.x` are Phase 2a flights, `v1.1.0` is JAMZ parity (last Phase 2a flight), `v1.2.x` are Phase 2b flights, `v1.3.0` is Phase 3.

---

## 11. Session handoff protocol

To honor Clay's "Rigorous Session Closeout" trait, every session starts and ends with a structured checklist.

### Session start checklist
1. Read `docs/PLAN.md` current-phase section
2. Read last session's closeout notes in `docs/sessions/YYYY-MM-DD-session-N.md`
3. Pull latest from origin (sync between Pixel/desktop dev environments if relevant)
4. Run `:app-android:assembleDebug` and `:app-desktop:run` to confirm last-session state is buildable
5. Run trap-detection prompts (§9)
6. State session goal in 1 sentence before starting work

### Session end checklist
1. Final commit + push for the session
2. Update `docs/sessions/YYYY-MM-DD-session-N.md` with:
   - What was built (1-3 sentences)
   - What works (verified by running)
   - What doesn't work yet (known TODOs)
   - Next session's first action (1 sentence)
3. Save engram memory entry summarizing the session's decisions/discoveries
4. Tag in spec/plan if a soft-lock got revisited
5. If the session ended mid-flight: leave a clear "this is where to pick up" marker in code (an obvious `// SESSION-RESUME:` comment) or in the session notes

---

## 12. Provider involvement

For this plan:
- 🟡 **Gemini available** — useful for second-opinion architectural calls during Pre-MVP research and Phase 2b library reviews
- 🟢 **Ollama available** — local fallback for non-critical clarifications during sessions
- 🔵 **Claude (always)** — primary execution + synthesis
- 🔴 **Codex missing** — would be useful for cross-LLM code review on showcase modules (Arrow in `:audio:dsp`, Circuit in `:ui:components` Now Playing); not blocking, but worth installing before MVP Sessions 12-15 (Now Playing with Circuit) or MVP Sessions 16-22 (EQ with Arrow) if Clay wants Codex's input there

### Recommended multi-LLM debate gates
- **End of Pre-MVP research:** cross-LLM review of library decisions (Gemini + Claude minimum)
- **Phase 2b Flight H, before each library tag:** cross-LLM review of public library APIs
- **Pre-Phase 3:** cross-LLM debate on minimum-phase vs linear-phase room correction algorithm choice

---

## 13. Time estimate summary

| Phase | Hours | Sessions (assuming 4-8 hr each) | Calendar @ 2 sessions/week | Calendar @ 1 session/week |
|---|---|---|---|---|
| Pre-MVP Research | 22-32 | 3-6 | 2-4 weeks | 3-6 weeks |
| MVP-1.0 | 305-435 | 40-70 | 6-13 months | 9-17 months |
| Phase 2a (Tidal cut) | 130-195 | 18-32 | 2-6 months | 4-10 months |
| Phase 2b (AAudio+WASAPI widened) | 205-310 | 28-50 | 4-9 months | 7-15 months |
| Phase 3 (R&D widened) | 150-250 | 20-40 | 3-8 months | 5-12 months |
| **Total** | **812-1222** | **109-198** | **17-40 months** | **28-60 months** |

Revised 2026-05-18: Tidal cut saved ~100-150 hrs but Gemini-driven realism adjustments to Phase 2b and 3 added ~250-360 hrs. Net: higher than original 580-1015. The widening reflects honest acknowledgment of audio-engine rewrite + DSP-R&D realities, not pessimism.

The wide ranges acknowledge unknown-unknowns and the possibility of soft-lock revisits mid-project. Don't treat these as deadlines — they're orientation only.

---

## 14. What this plan does NOT do

To be clear about boundaries:
- This plan does NOT execute any code
- This plan does NOT introduce features beyond the spec's anti-roadmap (§11)
- This plan does NOT prescribe specific deadline dates — "no rush" is the operating constraint
- This plan does NOT lock the soft-lock items (differentiator, tech stack pieces, test depth) — those revisit points are explicit
- This plan does NOT scaffold the new Kiln repository — that happens during MVP Session 1 once Clay has confirmed the plan and chosen the repo name

The next concrete action after this plan is reviewed and approved: Pre-MVP Research session 1 (library vetting starts).

---

End of plan.
