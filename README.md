# Kiln by Clayworks

A from-scratch Android + Windows Desktop music player. Personal-use audiophile player + developer portfolio piece by Clay Haworth (clayboicardi).

## Status

**Pre-MVP Research phase as of 2026-05-18.** Not yet runnable. Repo scaffolded as docs-only; no Gradle setup yet.

## What this is

Kiln is the from-scratch successor to JAMZ!!! (a Gramophone fork at `C:\Users\chawo\Projects\JAMZ!!!\`). Kiln is built fresh on Kotlin Multiplatform + Compose Multiplatform, targets Android + Windows Desktop simultaneously, and is licensed under Apache 2.0. No GPL-licensed code is carried over from Gramophone.

Key features (planned, in phase order):

- **MVP-1.0:** Local FLAC library scanning + playback, 31-band parametric EQ, queue management, Now Playing screen, full system integration (Audio Focus, MediaSession, lock-screen, BLE-disconnect handling, Windows SMTC)
- **Phase 2a:** Kiln Dynamic theming (album-art-driven palette), blurred album art backgrounds, EQ frequency-response curve, energy-preserving crossfade between presets, sectioned search, FFT visualizer
- **Phase 2b:** Hardware Spec Sheet About screen, library extraction (publish 2 Apache 2.0 libraries to JitPack/Maven Central), AAudio MMAP + WASAPI low-latency audio engines
- **Phase 3:** REW-style measurement-mic room correction (the differentiator)

## Key documents

| Document | Purpose |
|---|---|
| [`docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`](docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md) | Design contract — 9 strategic decisions, architecture, design system, MVP scope, phase progression, named patterns |
| [`docs/superpowers/plans/2026-05-18-kiln-execution-plan.md`](docs/superpowers/plans/2026-05-18-kiln-execution-plan.md) | Execution plan — module-by-module build sequence, test infrastructure timeline, ship cadence, session handoff protocol |
| [`docs/decisions/2026-05-18-library-vetting.md`](docs/decisions/2026-05-18-library-vetting.md) | Pre-MVP Research decision log (append-only) |
| [`CLAUDE.md`](CLAUDE.md) | Project guide for future Claude Code sessions |

## Tech stack (committed)

- **Language:** Kotlin 100% via Kotlin Multiplatform
- **UI:** Compose Multiplatform (Android + JVM Desktop targets)
- **DI:** Kotlin-Inject (KMP-native, KSP)
- **Navigation:** Voyager
- **State:** ViewModel-MP + StateFlow; Circuit Presenter/UI showcase in `:ui:components` Now Playing
- **Serialization:** Kotlinx Serialization (KSP)
- **Database:** SQLDelight (KMP-native)
- **HTTP:** Ktor client (when needed; not in MVP)
- **Async:** kotlinx-coroutines + Flow
- **Audio (Android):** Media3 ExoPlayer in MVP; AAudio MMAP planned phase-2b
- **Audio (Desktop JVM):** Java Sound in MVP; WASAPI via JNI planned phase-2b
- **FP utilities:** Arrow (typed errors only in `:audio:dsp` — showcase module)
- **License:** Apache 2.0 across all modules

## Effort budget

580-1015 hrs revised to 812-1222 hrs after Gemini adversarial critique. No fixed timeline — "no rush" is the operating constraint. See plan §13 for cumulative trajectory.

## Predecessor

JAMZ!!! (the predecessor Gramophone fork) lives at `C:\Users\chawo\Projects\JAMZ!!!\` and is preserved as artifact and reference. Kiln is a clean re-derivation, NOT a continuation. See JAMZ folder's `CLAUDE.md` for legacy context.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for details.

Copyright 2026 Clay Haworth.
