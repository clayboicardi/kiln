# Session 14 Handoff — Phase 2a Track D: Full Kiln-Internal ReplayGain

**Authored:** 2026-05-22 at the close of Session 13 (after Tracks A + B + C shipped as 3 stacked PRs)
**For:** Fresh CC session starting Phase 2a Track D
**Goal:** Pick one sub-track of Track D (D-A is the natural starting choice; rationale below), draft a plan with `superpowers:writing-plans`, execute via `superpowers:subagent-driven-development`. Track D is the largest single Phase 2a track (~30-66h spec estimate) and is expected to span multiple sessions.

> **Default-ask reminder per Clay's standing preference**: before dispatching subagents on Track D's sub-track, confirm with Clay which sub-track to start (D-A is natural choice; D-B and D-C have explicit dependencies). Don't auto-start.

---

## TL;DR

- **Phase 2a Tracks A, B, and C shipped as 3 stacked PRs in Session 13** (2026-05-22).
- All builds green, all tests pass, Pixel 7 autonomous launch verified clean on Track B and Track C end-states.
- Track D is now the natural next Phase 2a work item. **D-A (analyzer) is independent of Track A's Settings; D-B (consumer) + D-C (backfill UI) depend on A.** All three Tracks A/B/C must merge before D-B+D-C can be cleanly merged; D-A can start on a branch from any state.
- Use the same execution pattern as Session 13: `writing-plans` for the chosen sub-track, then `subagent-driven-development` (fresh subagent per task + spec-compliance reviewer + code-quality reviewer per logical step).

---

## Session 13 outputs (newest to oldest, what to merge / pick up from)

| PR | Branch | Status | Commits | Tests added |
|---|---|---|---|---|
| **#7 (Track C)** | `phase-2a-track-c-proper-ui` | OPEN, stacked on B | 7 (1 plan + 6 task + 1 amend in T2) | +9 (`:ui:components:desktopTest`) |
| **#5 (Track B)** | `phase-2a-track-b-saf-folder-picker` | OPEN, stacked on A | 8 (1 plan + 7 task + 1 amend in T3) | +5 (`:data:library:testAndroidHostTest`) |
| **#4 (Track A)** | `phase-2a-track-a-settings-ui` | OPEN, based on `main` | 11 (1 plan + 9 task + 1 amend + 1 docs) | +13 (8 SettingsRepo + 5 SettingsScreen) |

**Merge sequence:** A → B → C. After each merge, rebase the next branch onto fresh `main` (or use squash-merge semantics).

**Current canonical verify-build state on `phase-2a-track-c-proper-ui` tip (`28d781f`):** PASS, 5/5 targets, **113 tests + 1 skipped**. Full module test surface PASS across `:data:library:desktopTest` (71) + `:data:library:testAndroidHostTest` (50) + `:ui:components:desktopTest` (14) + `:app-android:testDebugUnitTest` (3) + `:app-desktop:test` (2) + `:audio:playback:desktopTest` (28). Pixel 7 install + autonomous launch clean.

---

## Pre-flight (first 5 minutes of Session 14)

**Confirm clean baseline + know your starting branch state:**

```powershell
cd C:\Users\chawo\Projects\kiln
git log --oneline -5
git status
gh pr list --state open
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```

**Three possible starting states + how to handle:**

### State 1: All 3 PRs (#4, #5, #7) still open + not merged

- **Local `main`** is at `ad10838` (Session 13 start point). Origin matches.
- **D-A (independent) can start from `main`** — branch `phase-2a-track-d-analyzer` off `main`. Schema columns for ReplayGain (`replay_gain_track_db`, etc.) already exist in the MVP schema; analyzer only needs to populate them.
- **D-B and D-C** need Track A's Settings table — would need to wait for #4 to merge OR base on `phase-2a-track-a-settings-ui` branch tip.

### State 2: Track A (#4) merged; B (#5) + C (#7) still open

- **D-A can still start from `main`** (Track A's settings table is in `main`).
- **D-B/D-C can start from `main`** since they only need Track A.

### State 3: All 3 PRs merged

- Cleanest state. **Start any Track D sub-track from `main`.**

In all states: the engram entries from Session 13 are queryable via `mem_search "kiln/session-13"` if you need additional context.

---

## Track D scope (verbatim from Session 13 handoff §"Phase 2a track menu")

> **Track D — Full Kiln-internal ReplayGain.** EBU R128 / BS.1770-4 analyzer (scanner-side) + consumer-side gain application + Settings toggle + backfill. **~30-66 h. D-A analyzer is independent; D-B consumer + D-C backfill need Settings.** Pivoted scope (Session 11 probe).

Three sub-tracks:

### D-A: Analyzer (scanner-side, ~12-25 h)

- Pure-Kotlin EBU R128 / BS.1770-4 analyzer in `:audio:dsp` (Concentric Modules invariant — KMP, no androidx in commonMain)
- K-weighting filter chain (pre-filter + RLB filter per ITU-R BS.1770-4)
- Block-by-block LUFS gating per EBU R128 (relative-loudness + absolute-threshold gating)
- True-peak measurement via 4x oversampled peak detection (per BS.1770-4 Annex 2)
- Hook into scanner pipeline: the scanner decodes audio for tag extraction; tap the PCM stream + feed analyzer; on scan-complete, persist `replay_gain_track_db` + `replay_gain_track_peak` to the `track` table (columns already exist from MVP schema)
- Album-level gain: aggregate track LUFS values per album, compute album-LUFS, persist `replay_gain_album_db` + `replay_gain_album_peak`
- **Testable in isolation:** unit tests against known-LUFS test vectors (EBU R128 reference signals exist; can generate sine waves with known amplitudes for cross-validation)
- Property-based tests via Kotest

### D-B: Consumer-side gain (~10-20 h)

- Apply scanner-captured `replay_gain_*_db` values as a linear pre-line-write gain in the audio pipeline
- Android: insert into Media3 RenderersFactory's AudioProcessor chain (similar to how Track A's spec mentions custom RenderersFactory at MVP Session 16+)
- Desktop: pre-multiply samples before `SourceDataLine.write(...)` in JavaSoundPlayerImpl
- Mode toggle (Track / Album / Off) read from `SettingsRepository.replayGainMode` (NEW key — needs to be added to Track A's Settings)
- Peak limiting on top — prevents clipping when applying positive gain
- Pre-amp adjustment (settings.replayGainPreAmpDb) — global +/- offset (default 0 dB)

### D-C: Settings UI + backfill (~8-21 h)

- Settings screen entries: ReplayGain mode (Off/Track/Album radio group); ReplayGain pre-amp slider (-12 to +12 dB)
- Backfill UI: button to re-analyze all already-scanned tracks (because Track A+B's scanners didn't populate RG; only tracks scanned AFTER D-A lands will have RG values)
- Background scanner pass: walk all tracks where `replay_gain_track_db IS NULL`, decode + analyze + update
- Progress indication: notify users it'll take time for large libraries (27k tracks × ~3-5 seconds per track via current decoder = ~22-37 hours; needs aggressive parallelism + maybe disk-cached intermediate state for resumability)

---

## Recommended starting sub-track + first-session scope

**Start with D-A (analyzer).** Rationale:
- Independent of Track A/B/C merge status — can start immediately
- Self-contained scope: the analyzer is pure math + tests; no UI; no DI rewire
- Establishes the engram memory pattern for the math + filter chain that D-B will consume
- ~12-25 h estimate fits a single session realistically

**Scoped D-A subset for a single session** (recommended for first Session 14):
1. K-weighting filter chain implementation in `:audio:dsp`
2. Block-level LUFS gating per EBU R128
3. Track-level LUFS aggregation
4. True-peak measurement via 4x oversampling
5. Unit tests against generated sine wave reference signals (known dBFS → expected LUFS)
6. Property-based tests for filter stability under various sample rates

**Explicitly defer to Session 15+ Track D-A:**
- Album-level aggregation (needs cross-track plumbing in scanner)
- Scanner integration (writing to DB)
- Performance profiling vs. budget
- Multi-channel beyond stereo

---

## Pre-emptive design decisions (do these or surface to Clay if disagreeing)

### Module placement

- **`:audio:dsp/src/commonMain`** is the home — pure Kotlin, KMP, no androidx
- New package: `com.clayworks.kiln.audio.dsp.replaygain`
- Files:
  - `KWeightingFilter.kt` — pre-filter + RLB filter implementation per BS.1770-4
  - `LoudnessGate.kt` — relative + absolute gating per EBU R128
  - `TruePeakMeter.kt` — 4x oversampled peak detection per BS.1770-4 Annex 2
  - `LoudnessAnalyzer.kt` — orchestrator that combines the three above into a single analysis pass
  - `ReplayGainResult.kt` — typed result (Either<AnalysisError, Double> for trackDb / trackPeak)

### API shape

```kotlin
interface LoudnessAnalyzer {
    /**
     * Process a single audio block. Caller streams samples in chunks (e.g.,
     * 4096 frames at a time from the decoder).
     *
     * @param samples interleaved PCM samples (channels * frames)
     * @param channels number of channels
     * @param sampleRateHz sample rate in Hz
     */
    fun processBlock(samples: FloatArray, channels: Int, sampleRateHz: Int)

    /**
     * Compute the gating-adjusted integrated LUFS over all blocks fed so far.
     * Returns Either.Left if insufficient audio was provided (BS.1770-4 requires
     * ≥3 seconds of audio for a valid integrated measurement).
     */
    fun integratedLufs(): Either<AnalysisError, Double>

    /** True peak across all blocks fed. */
    fun truePeakDbtp(): Double

    /** ReplayGain target: -18 LUFS by convention (v2). */
    fun replayGainDb(targetLufs: Double = -18.0): Either<AnalysisError, Double> =
        integratedLufs().map { lufs -> targetLufs - lufs }

    fun reset()
}
```

### Test strategy

- **Reference signals:** EBU provides BS.1770-4 reference audio files at https://tech.ebu.ch/docs/tech/tech3341.pdf — generate equivalent sine waves in test setup (known dBFS pure tones)
- **Property tests** via `io.kotest:kotest-property:6.1.11` (already on classpath per `libs.versions.toml:49`)
- **Empty/short signal handling:** integrated LUFS over <3 sec of audio → Either.Left(AnalysisError.InsufficientAudio)
- **Multi-channel:** test stereo (channels=2); deferred multi-channel (5.1, mono) to later

### Schema status

Track table already has the columns (MVP schema sketch §3.3):
- `replay_gain_track_db REAL DEFAULT NULL`
- `replay_gain_album_db REAL DEFAULT NULL`
- `replay_gain_track_peak REAL DEFAULT NULL`
- `replay_gain_album_peak REAL DEFAULT NULL`

**No schema changes needed for D-A.** D-B consumer reads these. D-C backfill writes them for existing rows.

### Settings keys (for D-B / D-C, NOT D-A scope)

When D-B/D-C land, add to `SettingsRepository` (Track A's interface):
- `replayGainMode: Flow<ReplayGainMode>` (enum: Off, Track, Album)
- `replayGainPreAmpDb: Flow<Double>` (range -12.0 to +12.0)

These are NEW keys to add to the existing `settings` table (no schema migration needed — generic key/value).

---

## Empirical lessons from Session 13 (re-cap for D's implementer)

These were discovered + captured in CLAUDE.md during Tracks A/B/C. **Read them before dispatching subagents:**

### SQLDelight (Track A discoveries)

- `.sqm` files named by **source** version, NOT target. v1→v2 = `1.sqm`. Empirical: `2.sqm` produces phantom-v2 + 3.db.
- Default sqlite-3-18 dialect rejects `ON CONFLICT(col) DO UPDATE SET ...`; use `INSERT OR REPLACE` for PK-only tables.
- `value` column → `value_` Kotlin property (reserved-word rename).
- `verifyMigrations` + `schemaOutputDirectory` are opt-in; without both, `verifyCommonMainKilnDatabaseMigration` is a silent no-op.

### Android SAF (Track B discoveries)

- SAF + MediaStore for the same physical file co-exist as duplicate rows (Track B accepts this; D-A/D-B don't touch this dedup).
- `MediaMetadataRetriever.release()` is mandatory on every code path. `try/finally` non-negotiable.
- `takePersistableUriPermission` must run synchronously inside the ActivityResult lambda.
- `ParcelFileDescriptor` from `openFileDescriptor` MUST be closed — use `.use { pfd -> ... }`.

### Compose-MP + Voyager (Track C discoveries)

- `material-icons-core` is missing many transport icons (`Pause`, `SkipNext`, `SkipPrevious`, `PlayCircle`, `LibraryMusic`). Solution: add `material-icons-extended` dep.
- `NavigationBarItem` is a `RowScope` extension function — helper composables that emit it must carry `RowScope` receiver context.
- Compose-MP M3 1.9.0 `TopAppBar` requires `@OptIn(ExperimentalMaterial3Api::class)`.
- `createComposeRule()` in Compose-MP 1.11.0 is deprecated in favor of `androidx.compose.ui.test.junit4.v2.createComposeRule`. (Already documented in CLAUDE.md.)

### Subagent-driven-development (process lessons)

- **Two-stage reviewer pair** (spec compliance + code quality) after each task implementer dispatch caught real issues across A/B/C — keep this discipline.
- **Empirical adjustments are normal**: implementers will discover plan-vs-reality gaps (icon fallbacks, transitive-dep gaps, API signature mismatches). Plan should anticipate; reviewers should accept reasonable substitutions; commit messages should document deviations.
- **Build-broken transient states** are acceptable between commits when a contract change spans two tasks (e.g., Track B's Task 4 broke the build until Task 5's DI rewire; reviewers explicitly looked at the standalone diff scope). Document the dependency in the plan.
- **TDD-failing-test gate** before implementation works well — implementers consistently caught it.
- **Quick amends** for minor reviewer nits keep the commit history clean without ballooning the dispatch count.
- **Skip the reviewer pair** ONLY when the task is mechanically identical to a prior one already approved (Session 13 skipped Track C Task 3's reviewers because Tasks 1+2 followed the exact same pattern).

---

## Suggested Track D-A plan outline (the Session 14 implementer should write a real plan via `superpowers:writing-plans`)

```
Task 1: KWeightingFilter — pre-filter (high-shelf at 1681 Hz) + RLB filter (high-pass at 38 Hz) per BS.1770-4
   ↳ 5-7 unit tests (impulse response, magnitude at known freqs, multi-channel symmetry)

Task 2: LoudnessGate — block-level mean square power, relative gating (-10 LU below ungated mean), absolute gating (-70 LUFS)
   ↳ 5-7 unit tests (known-LUFS reference signals, sub-3-sec edge case)

Task 3: TruePeakMeter — 4x oversampled peak detection per BS.1770-4 Annex 2
   ↳ 3-5 unit tests (DC peak, sine wave peak, oversampling effect)

Task 4: LoudnessAnalyzer — orchestrator combining the 3 above
   ↳ 3-5 unit tests (full pipeline against reference)

Task 5: Property-based tests via Kotest — filter stability across sample rates, scale invariance, etc.

Task 6: Final verify + plan-vs-reality CLAUDE.md gotchas (if any) + handoff doc for D-A → D-B
```

That's roughly 6 tasks at Session 13's average pace = ~6 subagent dispatches × ~80-150k tokens each = ~500-900k. Should fit in one session if Clay's budget allows.

---

## Other Track D scope to defer to later sessions

- **D-A's scanner integration** (writing RG values to DB during scan) — defer to Session 15 once the analyzer is verified isolated
- **D-A album-level aggregation** (cross-track) — defer to Session 15
- **D-B consumer-side gain** (Media3 RenderersFactory on Android; JavaSound on Desktop) — Session 16+
- **D-C backfill UI** (background re-analyze of existing tracks) — Session 17+
- **D-C performance optimization** (27k tracks × ~3-5 sec/track = ~22-37 hours of CPU; needs parallelism + resumability) — Session 17+
- **Replay Gain v2 vs v1 standard** — pick one (v2 default = -18 LUFS) and document choice; revisit if Clay wants legacy v1 support
- **Multi-channel beyond stereo** (5.1, mono, etc.) — out of scope until needed

---

## Reading order for Session 14 (cold-start)

1. **This file** — full read.
2. `CLAUDE.md` (~135 lines now, freshly updated with Track A/B gotchas; Track C gotchas pending — see Track C PR #7 plan-file appendix).
3. `docs/sessions/2026-05-22-session-13-handoff.md` — Session 12's framing of the 6-track menu (Track D is item 4).
4. `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md` §4 (audio pipeline, AudioProcessor chain), §6.2 (Phase 2a deliverables).
5. `docs/decisions/2026-05-18-library-vetting.md` Item 14 (ReplayGain hard-lock per spec).
6. (Optional) `docs/sessions/2026-05-21-session-12-handoff.md` — original Track D framing pre-pivot.
7. (Optional) `docs/superpowers/plans/2026-05-22-phase-2a-track-c-proper-ui.md` — see how Track C structured its writing-plans output.
8. (Optional) Engram lookups:
   - `mem_search "kiln/session-13"` for Session 13's records (Track A/B/C completion)
   - `mem_search "kiln/replaygain"` for any prior context
   - `mem_search "kiln/audio:dsp"` for the module's existing state

---

## Branch + PR strategy (Session 14 / D-A starter)

1. Create new branch off `main` (or off Track A/B/C's branch tip if those haven't merged yet — preferred: off `main` since D-A doesn't need A's Settings):
   ```
   git checkout main
   git pull origin main
   git checkout -b phase-2a-track-d-a-replaygain-analyzer
   ```

2. Use `superpowers:writing-plans` to draft the Track D-A plan (target: 6 tasks).

3. Present plan to Clay for confirmation per his default-ask preference.

4. Execute via `superpowers:subagent-driven-development`:
   - Fresh subagent per task
   - Two-stage reviewer pair (spec compliance + code quality)
   - Commit per logical step
   - Push at session-close

5. Open PR against `main` (or against `phase-2a-track-c-proper-ui` if Track C is still open — stacked PR style consistent with Session 13).

6. At session close, write the Session 15 handoff doc (mirror this doc's shape) covering: what was shipped, where you are, what D-A's open items are, what D-B's pre-flight will need.

---

## Things NOT to do (Session 13's learned cautions)

- **Don't ship a partial Track D-A as Track D done.** Track D is a long arc; honest scoping (D-A in Session 14, D-B in Session 15, etc.) preserves Clay's trust.
- **Don't skip the writing-plans step.** Track D-A's math is fiddly enough that a structured plan with concrete code in each task is load-bearing. The implementer subagents don't have the same context you'd accumulate during planning.
- **Don't bake LUFS computation by hand in the scanner.** Keep the analyzer pure + testable; the scanner integration is a separate task (deferred to Session 15).
- **Don't add the `replayGainMode` setting key in D-A.** That's D-B's surface. D-A only writes raw LUFS / dB values to the existing `replay_gain_*` columns.
- **Don't touch the Track A/B/C branches.** They're pending Clay's merge decisions. If you need to rebase or amend any of them, surface to Clay first.
- **Don't auto-start a sub-track without Clay's confirmation.** D-A is the recommended starting point; Clay decides.

---

## Token budget guidance

Session 13 final state: ~580k tokens consumed out of 1M (~580k remaining). Tracks A + B + C all shipped in one session via subagent-driven-development. Track D-A as scoped above (6 tasks) is similar in scope to Track B + roughly equivalent token cost.

**Anticipate:** Session 14 has fresh budget. ~120k overhead for skill loading + system prompts. ~500k+ headroom for subagent dispatches. Plenty of room for Track D-A's 6 tasks.

If Clay runs `/context` mid-session, the same headroom thresholds from Session 13 apply:
- >500k remaining: continue depth-first
- 300-500k: tighten scope or surface for decision
- <300k: wrap, write Session 15 handoff, push

---

## Copy-paste prompt for Session 14

```
Read docs/sessions/2026-05-22-session-14-track-d-handoff.md. Tracks A + B + C
shipped Session 13 as stacked PRs (#4, #5, #7). Phase 2a Track D is the
next work item; start with sub-track D-A (Kiln-internal EBU R128 / BS.1770-4
analyzer in :audio:dsp). Confirm clean baseline + check merge status of
A/B/C, surface the Track D sub-track choice to Clay (default: D-A), then
draft a Track D-A plan via superpowers:writing-plans + execute via
superpowers:subagent-driven-development per Session 13's pattern. Commit
per logical step; push at session-close.
```

---

**End of Session 14 Handoff.** Pre-flight gate is clean (subject to confirmation in the first 5 minutes of the new session). Phase 2a Track D is fully unblocked. Session 14 picks a sub-track and ships it.
