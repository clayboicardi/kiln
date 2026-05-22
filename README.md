# Kiln by Clayworks

A from-scratch Android + Windows Desktop music player. Personal-use audiophile player + developer portfolio piece by Clay Haworth (clayboicardi).

## Status

**Status as of 2026-05-22:** MVP vertical slice + library + DI graphs complete (Sessions 1-12). Pre-Phase-2a stabilization shipped — every P1 finding from the 2026-05-21 tooling-armed review is closed. **91 tests + 1 skipped pass on the canonical `kiln-verify-build` 5-target suite; +15 host-side Android tests via the new `androidHostTest` source sets.** CI green on every push (Ubuntu Android job + Windows Desktop job). **Vertical slice empirically verified on Pixel 7 Pro / Android 14** — clean launch, permission gate works, post-grant UI renders correctly. **kotlin-lsp is now operational for cross-module symbol navigation** (v262.4739.0; the prior deferral is lifted).

**Next:** Phase 2a track-picker session — see [`docs/sessions/2026-05-22-session-13-handoff.md`](docs/sessions/2026-05-22-session-13-handoff.md). Six tracks on the menu: Settings UI (A), SAF folder-picker (B), Proper UI (C), full Kiln-internal ReplayGain (D, scope pivoted to ~30-66h analyzer), MediaSession (E). Track F (CI gate) shipped during stabilization.

## What this is

Kiln is the from-scratch successor to JAMZ!!! (a Gramophone fork at `C:\Users\chawo\Projects\JAMZ!!!\`). Kiln is built fresh on Kotlin Multiplatform + Compose Multiplatform, targets Android + Windows Desktop simultaneously, and is licensed under Apache 2.0. No GPL-licensed code is carried over from Gramophone.

Feature progression (MVP shipped, Phase 2 in flight):

- **MVP-1.0** ✓ vertical slice: Local FLAC library scanning (jaudiotagger Desktop + MediaStore Android) + JNA-bridged libFLAC 1.5.0 decode + JavaSound (Desktop) + Media3 ExoPlayer (Android) playback. SQLDelight FTS5 search. Source Protocol + LibraryScanner abstractions. Single-button play proof-of-concept.
- **Phase 2a** (next): Kiln Dynamic theming (album-art-driven palette), blurred album art backgrounds, EQ frequency-response curve, sectioned search, FFT visualizer, Settings UI, SAF folder picker, full MediaSession integration. ReplayGain track scope pivoted to full Kiln-internal EBU R128 / BS.1770-4 analyzer.
- **Phase 2b:** Hardware Spec Sheet About screen, library extraction (publish 2 Apache 2.0 libraries to JitPack/Maven Central), AAudio MMAP + WASAPI low-latency audio engines, 31-band parametric EQ.
- **Phase 3:** REW-style measurement-mic room correction (the differentiator).

## Build and verify

JDK 21 (Temurin recommended, NOT JBR) + Android SDK (Pixel 10 Pro XL target, Compile SDK 36, min SDK 23) + Gradle wrapper (9.x).

**Canonical session-validation build** — runs both apps + load-bearing libs + the 63-test desktopTest suite:

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```

Expected: PASS, 5 targets green, 91 tests / 1 skipped.

**Full module test sweep including new Android host-side tests** (Robolectric + bundled SQLite):

```powershell
./gradlew :data:library:testAndroidHostTest :audio:playback:testAndroidHostTest :app-android:testDebugUnitTest
```

**CI** runs `:app-android:assembleDebug` + `:app-desktop:assemble` + all desktop and host-side tests on every push to `main` via `.github/workflows/build.yml` (Ubuntu Android job + Windows Desktop job; `:audio:playback:desktopTest` is Windows-only because it loads the vendored libFLAC.dll).

## Key documents

| Document | Purpose |
|---|---|
| [`docs/README.md`](docs/README.md) | Documentation index — navigation for the full docs set (decisions/, scaffold/, reference/, sessions/, superpowers/, reviews/) |
| [`docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`](docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md) | Design contract — 9 strategic decisions, architecture, design system, MVP scope, phase progression, named patterns |
| [`docs/superpowers/plans/2026-05-18-kiln-execution-plan.md`](docs/superpowers/plans/2026-05-18-kiln-execution-plan.md) | Execution plan — module-by-module build sequence, phase 2a flights, session handoff protocol |
| [`docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md`](docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md) | Stabilization plan executed in Sessions 11-12 — all 8 phases shipped or closed-no-work |
| [`docs/reviews/2026-05-21-tooling-armed-review.md`](docs/reviews/2026-05-21-tooling-armed-review.md) | Holistic tooling-armed project review (24 findings: 0 P0 / 6 P1 / 11 P2 / 7 P3); all blocking items closed |
| [`docs/decisions/2026-05-18-library-vetting.md`](docs/decisions/2026-05-18-library-vetting.md) | Pre-MVP Research decision log (append-only) |
| [`docs/sessions/2026-05-22-session-13-handoff.md`](docs/sessions/2026-05-22-session-13-handoff.md) | Latest session handoff — pre-flight gate + Phase 2a track menu |
| [`CLAUDE.md`](CLAUDE.md) | Project guide for future Claude Code sessions |

## Tech stack (committed)

- **Language:** Kotlin 2.3.21 via Kotlin Multiplatform (KMP); AGP 9 with `com.android.kotlin.multiplatform.library` plugin for KMP modules
- **UI:** Compose Multiplatform 1.11 (Android + JVM Desktop targets); Skiko 0.144.6 (pinned)
- **DI:** Kotlin-Inject 0.9.0 (KMP-native, KSP)
- **Navigation:** Voyager 1.1.0-beta03 (revisit at Track C kickoff per review)
- **State:** ViewModel-MP + StateFlow
- **Serialization:** Kotlinx Serialization (KSP)
- **Database:** SQLDelight 2.3.2 (KMP-native); FTS5 contentless virtual table; bundled SQLite via Requery on Android per Session 10 H8 Pixel discovery
- **HTTP:** Ktor client (deferred; not in MVP)
- **Async:** kotlinx-coroutines 1.11 + Flow
- **Audio decode (Desktop):** JNA 5.17.0 bridge to vendored Xiph libFLAC 1.5.0 (BSD-3); jaudiotagger 3.0.1 for tag extraction
- **Audio output (Android):** Media3 ExoPlayer 1.5.x in MVP; AAudio MMAP planned phase-2b
- **Audio output (Desktop JVM):** Java Sound (`javax.sound.sampled.SourceDataLine`) in MVP; WASAPI via JNI planned phase-2b
- **FP utilities:** Arrow Core 2.2.2.1 (typed errors via `Either` in source protocol + decoder boundaries)
- **Testing:** kotlin-test + kotlinx-coroutines-test for commonTest/desktopTest; Robolectric 4.16.1 + AndroidX Test Core 1.7.0 for `androidHostTest` (host-side Android tests, no emulator); JUnit 4 on `:app-android`'s legacy `testImplementation` source set
- **License:** Apache 2.0 across all modules

## Targets

- **Android:** min SDK 23 (Compose-MP 1.11 components-resources floor), compile SDK 36 (per AGP 9 baseline). Primary device: Pixel 10 Pro XL.
- **Desktop:** Windows 11 (i5-13400F class). JBR is incompatible with Gradle TLS — use Temurin JDK 21.
- **Out of MVP scope:** Mac, Linux, iOS, Wear, Auto, Tablet-optimized layouts. Not blocked architecturally, just not built.

## Effort budget

580-1015 hrs original → 812-1222 hrs revised after 2026-05-18 Gemini adversarial critique. Phase progression: MVP-1.0 (305-435h) → Phase 2a JAMZ-parity-minus-Tidal (130-195h) → Phase 2b Spec Sheet + libs + low-latency (205-310h) → Phase 3 room correction (150-250h). No fixed timeline — "no rush" is the operating constraint.

## Predecessor

JAMZ!!! (the predecessor Gramophone fork) lives at `C:\Users\chawo\Projects\JAMZ!!!\` and is preserved as artifact and reference. Kiln is a clean re-derivation, NOT a continuation. Read Gramophone source code only to understand WHAT it does, then close the file and re-derive HOW. See JAMZ folder's `CLAUDE.md` for legacy context.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for details.

Copyright 2026 Clay Haworth.
