# Decision: pull a WASAPI shared-mode slice forward, ahead of the Phase 2c gate (#37)

**Date:** 2026-09-23 (Session 28) · **Decided by:** Clay · **Issue:** #37
**Supersedes (for this slice only):** Phase 2b plan §11, the Phase 2c trigger conditions ([`../superpowers/plans/2026-05-23-phase-2b-plan.md`](../superpowers/plans/2026-05-23-phase-2b-plan.md)).
**Design:** [`../superpowers/specs/2026-09-23-issue-37-wasapi-shared-design.md`](../superpowers/specs/2026-09-23-issue-37-wasapi-shared-design.md)

Append-only. Later changes go in addendums below.

## Context

The desktop player can't play any 24-bit or 32-bit FLAC. That's 5,760 of 27,766 tracks (~21%). Java Sound on Windows only opens 8/16-bit `SourceDataLine`s, per a `jshell` probe on Cortex, JDK 21 (#37). The Phase 2b plan scheduled a native Windows engine as **Phase 2c** (80–120 h: C++ DLL + JNA/COM, exclusive + `IAudioClient3`). Phase 2c was gated on Phase 2b shipping plus 30 days of dogfooding (§11), so it was months away.

## Options considered

1. **Stopgap:** keep Java Sound and add a 16-bit fallback with a TPDF-dithered requantize. A few hours of work, with a permanent fidelity cost on hi-res files until Phase 2c.
2. **Pull WASAPI forward.** ← **chosen**
3. **Leave 24-bit unplayable** until Phase 2c.

## Decision

Pull WASAPI forward, **sliced**:
- **Slice 1 (now, closes #37):** a WASAPI **shared-mode** render engine. Windows converts 16/24/32-bit PCM to its float mixer losslessly (`AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM`), so the whole library plays at full precision. No dither, and other apps keep their sound.
- **Slice 2 (later, own issue):** WASAPI **exclusive** mode, i.e. bit-perfect at the source rate and depth. This is the desktop twin of Android's `MIXER_BEHAVIOR_BIT_PERFECT` path.
- **`IAudioClient3` low-latency:** not planned. Kiln is a playback app, and latency isn't a goal (plan §10: "Bit-perfect is NOT a latency feature").

**Approach (slice 1):** pure Kotlin over `jna-platform` COM (already a dependency), behind a new narrow `PcmOutputLine` seam. There's no native DLL and no C toolchain. Java Sound stays as the fallback, used only when WASAPI can't initialize. This replaces the plan-of-record "C++ DLL" shape for slice 1: a native real-time render thread is only needed for low latency, and a 200 ms push buffer makes JVM GC pauses irrelevant. If JNA/COM proves untenable, a miniaudio DLL can slot in behind the same seam without touching the actor.

**Also decided:** the stopgap-dither option is dropped, not deferred.
- **Failure UX:** on a per-track open failure during auto-advance or Next/Previous, skip ahead, capped at 3 consecutive failures. Device errors and explicit clicks never skip.
- **Disclosure:** the output path is shown on Now Playing and the Spec Sheet.

## Consequences

- Phase 2b-B B1 (the Android JNA-libFLAC port) moves behind slice 1. Nothing else in Phase 2b changes.
- The "Java Sound on Windows is 16-bit-only" limitation becomes fallback-only.
- The WASAPI path can't run on CI's headless Windows runner (no audio endpoint). CI covers it with device-free unit tests and fakes; the real device path is verified on Cortex.

## Prior-art check (2026-09-23)

- No maintained JVM WASAPI binding exists.
- **XT-Audio** (Java/.NET bindings): last push 2024-07-15, no releases, license unasserted. Rejected.
- **miniaudio** (C, public domain / MIT-0): healthy, 0.11.25 released 2026-03-03, but it would need a native build in the repo. Kept as the fallback approach.
- These findings are consistent with the 2026-05-23 research ([`2026-05-23-phase-2b-aaudio-wasapi-research.md`](2026-05-23-phase-2b-aaudio-wasapi-research.md)).
