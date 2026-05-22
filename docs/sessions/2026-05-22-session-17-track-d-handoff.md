# Session 17 Handoff — Phase 2a Track D-B Android (Media3 RenderersFactory)

**Authored:** 2026-05-22 at the close of Session 15 (D-A wrap-up + D-C + D-B desktop all shipped)
**For:** Fresh CC session continuing Phase 2a Track D
**Goal:** Implement Android-side consumer-side ReplayGain. Requires general Media3 infrastructure work (custom RenderersFactory + AudioSink wrapper) that's been TODO'd since MVP Session 5+.

## TL;DR

- Sessions 14+15 shipped **all of Track D except the Android-side audio pipeline**.
- PR #10 (merged): Session 14 — LoudnessAnalyzer in :audio:dsp.
- PR #11: Session 15 — D-A wrap-up: TrackAnalysisRunner orchestrator + desktop FLAC + Android MediaCodec analyzer impls + DI wiring.
- PR #12: Session 15 — D-C: SettingsRepository extension, AnalysisProgress flow, SettingsScreen "ReplayGain" section, app-route wiring.
- PR #13: Session 15 — D-B desktop: ReplayGainProcessor in :audio:dsp + wired into JavaSoundPlayerImpl. Audible RG on desktop.

## What Session 17 ships

Android-side equivalent of D-B desktop. Three sub-pieces, all in `:audio:playback/androidMain`:

1. **`KilnRenderersFactory`** — custom `androidx.media3.exoplayer.RenderersFactory` that wraps `DefaultAudioSink` with Kiln's AudioProcessor chain. Constructed in `Media3ExoPlayerImpl`'s init; passed to `ExoPlayer.Builder(context).setRenderersFactory(...)`.
2. **`MediaProcessorAdapter`** — adapts Kiln's `com.clayworks.kiln.audio.dsp.AudioProcessor` interface to Media3's `androidx.media3.common.audio.AudioProcessor` interface (different shape — Media3 uses `AudioFormat` + `ByteBuffer` cycles). Allows the same `ReplayGainProcessor` to plug into both pipelines.
3. **AndroidAppGraph wiring** — provide `ReplayGainProcessor` (singleton), thread it + `SettingsRepository` into `Media3ExoPlayerImpl`'s constructor (mirror desktop's pattern). The Media3 player's init builds the `KilnRenderersFactory` with the processor's adapter.

## Recommendation

5-6 tasks. The Media3 surface is intricate but well-documented:

- Media3 `AudioProcessor`: https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor
- Media3 `DefaultAudioSink` configuration: https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink
- `RenderersFactory` extension: https://developer.android.com/media/media3/exoplayer/customization

Expected task breakdown:

1. `MediaProcessorAdapter` in `:audio:playback/androidMain` — adapts Kiln's AudioProcessor → Media3's AudioProcessor. Tests via Robolectric.
2. `KilnRenderersFactory` in `:audio:playback/androidMain` — subclasses `DefaultRenderersFactory`; overrides `buildAudioSink` to inject the chain.
3. `Media3ExoPlayerImpl` constructor gains `settings: SettingsRepository` + `rgProcessor: ReplayGainProcessor` params; init builds the RenderersFactory; observes settings (mirror desktop pattern).
4. `AndroidAppGraph` updates: `@Singleton @Provides ReplayGainProcessor` + thread it into the Media3 player factory. Mirrors `DesktopAppGraph` changes.
5. CLAUDE.md gotchas + Session 18 handoff + verify-build + PR.

## Other known follow-ups (carried forward)

- **Search tab "rough at best"** per Clay 2026-05-22. FTS5 backend correct; UI/UX needs work. Non-urgent.

## Reference

- D-A wrap-up plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-wrap-up.md`
- D-C plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-c-settings-backfill.md`
- D-B desktop plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-b-desktop-consumer-gain.md`
- Engram entries: `mem_search "kiln/track-d-b"`, `mem_search "kiln/track-d-c"`, `mem_search "kiln/track-d-a-wrap-up"`

---

**End of Session 17 Handoff.**
