# Session 16 Handoff — Phase 2a Track D continuing (D-B or D-C choice)

**Authored:** 2026-05-22 at the close of Session 15 (after Track D-A wrap-up shipped)
**For:** Fresh CC session continuing Phase 2a Track D
**Goal:** Pick between Track D-B (consumer-side gain application) or Track D-C (settings UI + backfill button) and execute.

---

## TL;DR

- **Track D-A wrap-up shipped Session 15.** Album-level LUFS aggregation, SQLDelight queries, TrackAnalyzer port, TrackAnalysisRunner orchestrator, desktop FLAC analyzer impl, Android MediaCodec analyzer impl, DI wiring on both platforms.
- All builds green; canonical verify-build PASS.
- **Two follow-on sub-tracks remain for Track D.** D-B (consumer-side gain) is the playback-side complement; D-C (settings UI + backfill) is the user-trigger UI.

## What Session 15 shipped

- `albumIntegratedLufs(trackLufsValues): Either<AnalysisError, Double>` in `:audio:dsp/commonMain/.../replaygain/LoudnessAggregator.kt` (energy-weighted mean per BS.1770-4 §5.3, with silent-album / NaN guard returning `NoGatedBlocks`)
- 6 new SQLDelight queries on `track.sq`: `selectTracksMissingReplayGain`, `countTracksMissingReplayGain`, `updateTrackReplayGain`, `selectAlbumsForAggregation`, `selectTrackReplayGainForAlbum`, `updateAlbumReplayGainForAlbum`
- `TrackAnalyzer` interface + `TrackLoudness` + `TrackAnalysisError` (CodecUnsupported / DecodeFailed / AnalysisFailed) in `:data:library/commonMain/.../scan/TrackAnalyzer.kt`
- `TrackAnalysisRunner` orchestrator in `:data:library/commonMain/.../scan/TrackAnalysisRunner.kt` — paginated worklist walk + per-track persist + per-album rollup
- `JvmFlacTrackAnalyzer` (desktop, FLAC-only) in `:audio:playback/desktopMain` + 3 tests against bundled fixtures
- `AndroidMediaTrackAnalyzer` (Android, MediaExtractor + MediaCodec synchronous-mode decode) in `:audio:playback/androidMain`
- `DesktopAppGraph` + `AndroidAppGraph` expose `TrackAnalysisRunner` via DI (kotlin-inject)
- 6 runner tests + 5 aggregator tests + 3 desktop analyzer tests + 2 amend-time silent-album / NaN guard tests = 16 new tests this session; canonical verify-build PASS

## Pending Track D sub-tracks

### Track D-B — Consumer-side gain (~10-20h)

- Apply `replay_gain_track_db` / `replay_gain_album_db` to the audio pipeline as a linear pre-line-write gain.
- Android: Media3 AudioProcessor via custom RenderersFactory.
- Desktop: JavaSoundPlayerImpl multiplier.
- New setting key: `replayGainMode` (Off / Track / Album).
- New setting key: `replayGainPreAmpDb` (default 0.0, range -12.0 to +12.0).
- Peak limiting to prevent clipping when applying positive gain.

### Track D-C — Settings UI + backfill (~8-21h)

- Settings screen: ReplayGain mode radio group (Off/Track/Album) + pre-amp slider.
- Backfill UI: button that triggers `TrackAnalysisRunner.runOnce()` (already wired via DI). Progress notification (long-running for large libraries — perf math says ~17-100 h for 40k tracks).

## Recommendation

D-C is the user-visible value. Without it, the analyzer pass shipped in Session 15 has no trigger surface (developers can only invoke via tests). D-B requires D-C to validate (you need RG values populated before consumer-side gain has anything to apply).

D-B is the playback-side polish — necessary for ReplayGain to actually do anything audible, but landable after D-C.

Suggested order: **D-C first, then D-B.**

Alternative: D-B + D-C in parallel if effort budget allows; they don't share files.

## Reference

- Track D-A wrap-up plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-wrap-up.md`
- Track D-A original plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-replaygain-analyzer.md`
- Engram entries: `mem_search "kiln/track-d-a-wrap-up"`
- CLAUDE.md gotchas: section "Build/Dep Gotchas (discovered MVP Sessions 1-7)" — last 9 bullets added in Session 15 for Track D-A wrap-up.

---

**End of Session 16 Handoff.**
