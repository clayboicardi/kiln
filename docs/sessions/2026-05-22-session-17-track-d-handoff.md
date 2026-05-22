# Session 17 Handoff — Phase 2a Track D-B (consumer-side gain)

**Authored:** 2026-05-22 at the close of Session 15 (after Track D-A wrap-up + D-C settings shipped)
**For:** Fresh CC session continuing Phase 2a Track D
**Goal:** Implement Track D-B — apply persisted ReplayGain values (D-A wrap-up populates them; D-C surfaces them via settings UI) to the audio pipeline so volume normalization is actually audible.

---

## TL;DR

- Track D is one sub-track away from done. D-B is the last piece: consumer-side gain in the playback path.
- Sessions 14+15 shipped:
  - **PR #10** (merged): EBU R128 / BS.1770-4 LoudnessAnalyzer in `:audio:dsp` (Session 14).
  - **PR #11** (open or merged by now): D-A wrap-up — album aggregator, TrackAnalysisRunner orchestrator, platform analyzer impls, DI wiring (Session 15).
  - **PR #12** (this branch): D-C — SettingsRepository extension with ReplayGainMode + replayGainPreAmpDb, runner I1+I2 fixes, AnalysisProgress + runOnceWithProgress flow, SettingsScreen "ReplayGain" section, app-route wiring (Session 15).
- The UI shows the mode + pre-amp slider + backfill button. Clicking "Analyze" populates `track.replay_gain_track_db` + `_track_peak` + `_album_db` + `_album_peak` for every NULL track. But playing a track still ignores those values — D-B's job.

## What Track D-B is

Three sub-pieces:

1. **Desktop** — `JavaSoundPlayerImpl` currently writes decoded PCM directly to a `SourceDataLine`. Insert a multiplier stage between decode and write:
   - Resolve gain at track-start: read `track.replay_gain_track_db` (Track mode) or `track.replay_gain_album_db` (Album mode) from the DB, apply pre-amp dB, convert to linear (`10^((rgDb + preAmpDb)/20)`).
   - Apply the linear multiplier to every sample before writing to the line.
   - Peak limiter (or skip the multiplier) if `replay_gain_track_peak * gain_linear > 1.0` (clipping prevention).

2. **Android** — Media3 ExoPlayer's audio pipeline uses `AudioProcessor` chain via `RenderersFactory`. Inject a custom AudioProcessor:
   - The processor reads MediaItem metadata or queries the DB for the gain at track-start.
   - Multiplies each sample.
   - Similar peak-limit guard.

3. **Settings-driven mode switch** — `replayGainMode == Off` → bypass multiplier (zero overhead). `Track` → use `replay_gain_track_db`. `Album` → use `replay_gain_album_db`, falling back to track_db if album_db is null.

## Recommendation

**Recommend D-B as a single session (~5-8 tasks):**

1. Pure-math gain resolution function (`resolveGainLinear(track: Track, mode: ReplayGainMode, preAmpDb: Double): Double`).
2. Desktop `JavaSoundPlayerImpl` multiplier stage + test.
3. Android Media3 `AudioProcessor` impl + test.
4. Peak limiter (or "skip if clipping" guard) on both.
5. DI wiring updates if needed (settings.replayGainMode + settings.replayGainPreAmpDb flow into the player).
6. Manual smoke test on a real FLAC fixture (desktop) + on-device test on Pixel.
7. CLAUDE.md gotchas + Session 18 handoff.

## Known follow-ups from D-A wrap-up + D-C reviews

- **`@Singleton` annotation missing on `analysisRunner` + `trackAnalyzer` providers in both graphs**. Reviewer noted at D-C Task 6 review: each `graph.analysisRunner` access constructs a fresh instance instead of reusing a singleton. One extra allocation per backfill button click — not a leak, just a small inconsistency. Fix: prepend `@Singleton` to both `@Provides fun analysisRunner(...)` and `@Provides fun trackAnalyzer(...)` in both `DesktopAppGraph.kt` and `AndroidAppGraph.kt`. ~4 lines per graph.
- **Search tab is "rough at best"** per Clay's 2026-05-22 demo smoke (Session 15). FTS5 backend is correct; the UI/UX layer over it needs work. Non-urgent — track in Phase 2a polish backlog.

## Reference

- D-A wrap-up plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-wrap-up.md`
- D-C plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-c-settings-backfill.md`
- Engram entries: `mem_search "kiln/track-d-c"`, `mem_search "kiln/track-d-a-wrap-up"`, `mem_search "kiln/session-15-demo-smoke"`
- CLAUDE.md gotchas: section "Build/Dep Gotchas (discovered MVP Sessions 1-7)" — ~25 bullets added across Sessions 14-15 for Track D.

---

**End of Session 17 Handoff.**
