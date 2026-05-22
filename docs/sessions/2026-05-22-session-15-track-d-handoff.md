# Session 15 Handoff — Phase 2a Track D continuing (D-A wrap-up + D-B/D-C choice)

**Authored:** 2026-05-22 at the close of Session 14 (after Track D-A shipped)
**For:** Fresh CC session continuing Phase 2a Track D
**Goal:** Pick one of three follow-on sub-tracks (D-A wrap-up, D-B consumer gain, or D-C backfill UI), draft a plan with `superpowers:writing-plans`, execute via `superpowers:subagent-driven-development`.

---

## TL;DR

- **Track D-A (analyzer in isolation) shipped Session 14.** New module: `:audio:dsp/src/commonMain/.../replaygain` with K-weighting filter, LoudnessGate, TruePeakMeter, LoudnessAnalyzer, 40 new tests (36 unit + 4 property).
- All builds green; full canonical verify-build PASS, 153+ tests across all modules.
- **Three follow-on sub-tracks remain for Track D.** D-A has wrap-up scope; D-B + D-C are still ahead.

## What Session 14 shipped

- `BiquadFilter` (Direct Form II Transposed, internal primitive)
- `KWeightingFilter` (BS.1770-4 pre + RLB cascade via bilinear transform with frequency pre-warping)
- `BiquadCoefficients` data class (mixed-normalization to match BS.1770-4's documented coefficient table)
- `LoudnessGate` (400 ms / 100 ms sliding window, EBU R128 dual gating, channel-weighted mean square)
- `TruePeakMeter` (4-point Lagrange interpolation, dBTP output)
- `LoudnessAnalyzer` (public API, `createLoudnessAnalyzer(sampleRateHz, channels)` factory)
- `AnalysisError` (InsufficientAudio, NoGatedBlocks)
- 36 unit tests + 4 property tests; EBU Tech 3341-style integration tests; chunked-vs-single-shot determinism test

## Pending Track D sub-tracks

### D-A wrap-up (small, low-risk)

- **Album-level aggregation.** Aggregate per-track LUFS values to compute album-level LUFS. Spec: BS.1770-4 §5.3 (energy-weighted average of track integrated LUFS). New API: `albumGain(trackLufsValues: List<Double>): Double`.
- **Scanner integration.** Hook the analyzer into JvmFilesystemScanner / SafScanner: after metadata extraction, decode the file once more (or stream during initial scan), feed PCM into the analyzer, persist `replay_gain_track_db` and `replay_gain_track_peak` to the `track` table. Columns already exist.
- **Performance profiling.** Measure throughput on Clay's D:\tiddl (~40k tracks). Target: <5 sec/track on desktop, <15 sec/track on Pixel 7.

### D-B consumer-side gain (~10-20h)

- Apply `replay_gain_track_db` / `replay_gain_album_db` to the audio pipeline as a linear pre-line-write gain. Android: Media3 AudioProcessor; Desktop: JavaSoundPlayerImpl multiplier.
- New setting key: `replayGainMode` (Off / Track / Album).
- New setting key: `replayGainPreAmpDb` (default 0.0, range -12.0 to +12.0).
- Peak limiting to prevent clipping when applying positive gain.

### D-C settings UI + backfill (~8-21h)

- Settings screen: ReplayGain mode radio group (Off/Track/Album) + pre-amp slider.
- Backfill UI: button that re-runs the scanner's analyzer pass over all tracks where `replay_gain_track_db IS NULL`. Progress notification (long-running for large libraries — ~22-37h for 27k tracks).

## Recommendation

Continue with **D-A wrap-up** (album aggregation + scanner integration) as a single session. It unlocks D-B (which needs scanner-populated RG values to test against) and is more contained than D-B's Media3-RenderersFactory work. Alternative: start D-B if Clay wants to validate the analyzer against a manually-curated test track set first.

## Reference

- Track D-A plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-replaygain-analyzer.md`
- D-A engram entries: `mem_search "kiln/track-d-a-plan"` or `mem_search "kiln/replaygain"`
- BS.1770-4 + EBU R128 reference math: inline in the plan, top of the file.
- CLAUDE.md gotchas: section "Build/Dep Gotchas (discovered MVP Sessions 1-7)" — last 11 bullets added in Session 14 for Track D-A.

---

**End of Session 15 Handoff.**
