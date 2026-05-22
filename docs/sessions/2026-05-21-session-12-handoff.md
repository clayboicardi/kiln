# Session 12 Handoff — Phases 5-8 stabilization + Track D scope decision committed

**Authored:** 2026-05-21 (end of Session 11 — stabilization sweep)
**For:** Next Claude session
**Goal:** Continue pre-Phase-2a stabilization Phases 5-8 (test infrastructure) per `docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md`. AFTER that, Track D pivots to full Kiln-internal analyzer + consumer scope (decision made by Clay at end of Session 11).

This supersedes `2026-05-21-session-11-handoff.md`. Session 11 was the stabilization sweep, not the Phase-2a-track-pick session. Session 12 finishes stabilization, then Session 13+ picks an actual Phase 2a track.

---

## What Session 11 did (this session)

**13 commits on `main` (all pushed to origin):**

| Commit | Type | Purpose |
|---|---|---|
| `4229e34` | docs | Tooling-armed project review (24 findings: 0 P0 / 6 P1 / 11 P2 / 7 P3) |
| `ccb2e26` | docs | Pre-Phase-2a stabilization plan (8 phases, 1943 lines) |
| `3842b1d` | docs | P1.5 — scanner intentional-UPDATE-outside-txn comments |
| `cb6bd8e` | docs | P1.6 — plan §4 ↔ Session 11 handoff reconciliation addendum |
| `badc888` | docs | P1.7 — Track A effort revision 6-10h → 10-16h |
| `f0886a4` | docs | P1.8 — tooling-recommendation addendum (review-surfaced gaps) |
| `ebfea37` | fix | P1.1 — parseChannels validation (340-row DB cleanup applied via MCP) |
| `df70c1e` | fix | P1.3 — kiln-verify-build multi-module test counting (41 → 75) |
| `7d3d04c` | feat | P1.4 — Phase 3 measurement-mode seam stub on PlatformPlayer |
| `134296c` | deps | P3.1 — JNA 5.14.0 → 5.17.0 (10/10 FLAC smoke preserved) |
| `d44b0c3` | build | P3.2 — Skiko version-mismatch warning silenced via `exclude` |
| `b700113` | ci | P2.1 first attempt — desktopTest on both jobs (Ubuntu broke) |
| `df75eca` | ci | P2.1 fix — `:audio:playback:desktopTest` Windows-only |

**Build state:** `kiln-verify-build` PASS, 75 tests (47 :data:library + 28 :audio:playback active + 1 GoldenCorpus skipped), CI green on both runners.

**Phases 1+2+3 of the stabilization plan are COMPLETE (11/16 tasks). Phases 4-8 pending.**

---

## Headline finding — Track D scope expanded 10× by empirical probe

Phase 4 of the stabilization plan probed Clay's `D:\tiddl` library for ReplayGain Vorbis comments via `metaflac --list`. **4 of 4 sampled FLACs had zero REPLAYGAIN_* tags.** The scanner's `getFreeFormOrNull("REPLAYGAIN_TRACK_GAIN")` returns null because there's nothing on disk to extract — not because jaudiotagger is broken (the original P1-1 hypothesis was wrong).

**Implication:** Session 11 handoff's "Track D = pure consumption work, 4-6h, data already populated by scanner" framing is empirically false by ~10×.

**Clay's decision at Session 11 close:** Pursue full Kiln-internal Track D — **scanner-side EBU R128 / BS.1770-4 analyzer + consumer-side gain application. ~30-66h.** Software-as-Self-Portrait aligned: Kiln owns the audio chain top-to-bottom, no external `rsgain` subprocess dep.

**For Track D scoping at the actual Phase 2a track-pick session:**
- Track D phases must be split:
  - **D-A: Scanner-side analyzer** — implement EBU R128/BS.1770-4 loudness measurement in `:audio:dsp` (or new `:audio:loudness` module — design call). Compute `replay_gain_track_db`, `replay_gain_album_db`, `replay_gain_track_peak`, `replay_gain_album_peak` and persist via the existing schema columns. ~20-40h.
  - **D-B: Consumer-side gain application** — original 4-6h consumer task in `JavaSoundPlayerImpl` + `Media3ExoPlayerImpl` + Settings UI toggle (Track/Album/Off mode + peak limiter). ~4-6h.
  - **D-C: Backfill** — apply analyzer over Clay's 27,766 tracks; write to DB. ~30min CPU + verification.
- Track D dependencies: needs Track A's Settings table (mode toggle) but Track D-A + D-C can ship before A.
- Reference: full discovery in engram `kiln/replaygain-probe-2026-05-21`.

This effectively makes Track D a **Phase 2a track on par with Track C in scope** — not a quick win.

---

## What Session 12 should do (in priority order)

### Primary: continue stabilization Phases 5-8

Per `docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md`. Suggested order:

**Phase 6 — LocalLibrarySource tests (6-10h, independent)**
- 14-16 tests across all 11 BrowseScope branches + search + getPlayable
- In-memory JdbcSqliteDriver in commonTest
- The plan's Task 6.1 through 6.17 has the full TDD breakdown
- Independent of Phase 5; can ship in any order

**Phase 5 — androidUnitTest source set (6-10h, structural)**
- Add to `:data:library`, `:audio:playback`, `:app-android`
- Wire `RequerySQLiteOpenHelperFactory` (already in prod for Fix J) for host-side bundled-SQLite tests
- Robolectric runner; add to libs.versions.toml
- Smoke tests verify the source set is wired
- **Verify `androidUnitTest` syntax against `slackhq/circuit` libs.versions.toml first** — KMP+AGP9 conventions differ from legacy

**Phase 8 — DI graph tests (2-3h, needs Phase 5 Android side)**
- AndroidAppGraphTest (Robolectric)
- DesktopAppGraphTest (pure JVM)
- Regression for Polish-1 (lazy-init thread safety) and Polish-3 (graph hoist)

**Phase 7 — Media3ExoPlayerImpl tests (8-12h, needs Phase 5)**
- Mirror JavaSoundPlayerImplTest's 12 cases under androidUnitTest
- Most mechanical port of the four phases — ideal for subagent-driven-development

**Total Phases 5-8: ~22-35 h.** Comfortably 2-3 fresh CC sessions.

### Secondary: skip Phase 4 (Clay decided full Track D)

Phase 4 of the stabilization plan was "ReplayGain probe + scanner fix." Probe outcome was Branch B — Clay's library has no tags. The "scanner fix" sub-task doesn't apply because there's nothing to fix (scanner correctly extracts null when no tag is present).

Phase 4 is **CLOSED, no work needed**. The follow-up is Track D itself, which is full-Phase-2a-track scope and belongs to the track-picker session (Session 13+), not the stabilization sweep.

---

## Pre-flight (first 5 minutes of Session 12)

**Read order (cold-start):**

1. This file — full read.
2. `docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md` — Phases 5-8 are your work. Phase 6 has the most explicit task breakdown.
3. `docs/reviews/2026-05-21-tooling-armed-review.md` Axis 4 + Axis 8 — context for what test coverage gaps exist.
4. `CLAUDE.md` — workflow + build commands.
5. *Optional* engram lookups:
   - `kiln/review-axis-4-checkpoint` — test coverage findings
   - `kiln/replaygain-probe-2026-05-21` — full Track D scope decision context

**Confirm clean baseline:**

```powershell
cd C:\Users\chawo\Projects\kiln
git log --oneline -5           # expect df75eca at HEAD
git status                     # expect clean tree
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
# expect: PASS, 5 targets, 75 tests / 1 skipped
```

If baseline is dirty or build red — STOP, diagnose, surface to Clay.

**Execution mode:** Clay chose subagent-driven-development at Session 11. Same protocol for Session 12. Each phase is a coherent subagent task; mechanical test-porting in Phase 7 is the highest-fan-out work for parallel subagents.

---

## In-flight items / decisions made during Session 11

- ✅ **Architectural integrity invariants hold** (spec §3): Concentric Modules + Source Protocol + Engine-Swap-Shaped Boundary all upheld in production code. Capability Flags pattern decorative today (only 1 source) — flagged as P3-6 in review, expected.
- ✅ **JNA bumped to 5.17.0** (was 5.14.0). 5.18.1 mentioned in Session 10 recap was either snapshot or pre-release; Maven Central tops at 5.17.0.
- ✅ **Skiko transitive resolution fix**: `eachDependency.useVersion()` ALONE doesn't silence the JetBrains compatibility warning — checker iterates requested vs selected per edge. Must `exclude(group = "org.jetbrains.skiko", module = "skiko")` on coil-compose. Documented in commit `d44b0c3`. See engram for the JetBrains checker source-code finding.
- ✅ **`:audio:playback:desktopTest` is Windows-only**: JNA loads vendored libFLAC.dll, no Linux .so. CI Ubuntu job runs `:data:library:desktopTest` only. If future Phase 2b adds Linux .so builds, the Ubuntu job can re-add `:audio:playback:desktopTest`.
- ✅ **Track A effort revised** to 10-16h in handoff (was 6-10h). Plan §3.2's 12-20h was the real budget.
- ✅ **Phase 3 measurement-mode seam exists** in PlatformPlayer — `enterMeasurementMode(): MeasurementSession?` returns null in both impls.
- 🛑 **Track D pivots to full Kiln-internal scope** — Clay's decision at Session 11 close. See engram + this doc's headline section.
- 🔲 **mobile-mcp install not done** — tooling addendum proposes promoting it to Tier-2-bis. Trigger fired in Session 10; future Track B (SAF) and Track E (MediaSession) sessions should install before they need Pixel UI inspection. ~5min `claude mcp add` + env var.
- 🔲 **SKILL.md drift in kiln-verify-build** — the example output in `SKILL.md` shows old `41/41` format; the actual skill now reports `75 tests, 1 skipped`. Cosmetic; refresh next time kiln-verify-build is touched.

---

## Things deferred from Session 11 (don't re-discover)

These were explicitly deferred in the review or during Session 11. Re-deferring so a fresh CC doesn't re-investigate:

- **P2-2 jaudiotagger maintenance survey** — Skipped because Phase 4 probe revealed jaudiotagger isn't the bottleneck; the tags genuinely don't exist on disk. Reconsider only if Phase 2b/3 audio work surfaces a jaudiotagger limitation.
- **P2-3 Voyager 1.1.0-beta03 19-month stale** — Revisit at Track C kickoff (UI work).
- **P2-4 kmpalette 4.0.0-beta02** — Revisit at Phase 2a Flight A (theming) or Track A (Settings UI).
- **P2-8 AudioProcessor placement** — Premature until MVP Session 16+ EQ port. When EQ lands, move AudioProcessor + AudioFrame + DecodedAudioFormat from `:audio:playback/commonMain` to `:audio:dsp/commonMain` (Concentric Modules: inner ring owns the type, outer ring depends on it).
- **P2-7 duration_ms CHECK constraint** — Bundle with Track A's Settings table migration to user_version 2; not a standalone task.
- **P3-2 + P3-3 + P3-6** — UI/UX decisions for Track C; not pre-Phase-2a scope.
- **P3-7 coverage % vs spec §8.2** — MVP-1.0 close criterion; ongoing tracking, not a single task.
- **Stale javadoc URL in LibFlacBinding.kt line 7** (still points to JNA 5.14.0). Cosmetic; non-blocking. Skip unless touching that file.

---

## Verify before starting Session 12

- [ ] `git log --oneline -5` shows `df75eca` at HEAD
- [ ] `git status` clean
- [ ] `kiln-verify-build` PASS (75 tests, 1 skipped, 5 targets)
- [ ] CI green on latest push: `gh run list --limit 3` should show recent green runs
- [ ] Read `docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md` Phases 5-8 sections in full before dispatching subagents

---

## Reference

- **Plan**: `docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md` (8 phases, 1943 lines; Phases 1-3 done, 4 closed-no-work, 5-8 pending)
- **Review**: `docs/reviews/2026-05-21-tooling-armed-review.md` (24 findings; the source spec for the plan)
- **Original Session 11 handoff**: `docs/sessions/2026-05-21-session-11-handoff.md` (superseded by this; the Track A effort revision is the most concrete change)
- **Previous session**: Session 10 closeout at `docs/sessions/2026-05-21-session-10.md` + recap at `docs/sessions/2026-05-21-session-10-recap.md`
- **Engram topics added during Session 11**:
  - `kiln/review-axis-1-checkpoint` through `kiln/review-axis-9-checkpoint` (per-axis findings)
  - `kiln/project-review-2026-05-21` (review anchor)
  - `kiln/db-cleanup-channels-2026-05-21` (DB operational record)
  - `kiln/replaygain-probe-2026-05-21` (Track D scope discovery)

---

## Copy-paste prompt for Session 12

```
Read docs/sessions/2026-05-21-session-12-handoff.md and execute its
Primary section (Phases 5-8 of the pre-Phase-2a stabilization plan).
Use subagent-driven-development; Phase 6 is independent and ideal to
start with. Pause between phases for Clay to checkpoint review.
```

---

**End of Session 12 Handoff.** Phases 1-3 of stabilization shipped this session (11 of 16 plan tasks). Phase 4 closed without work after empirical probe (Track D scope pivoted). Phases 5-8 (test infra) are next session's primary work, ~22-35h. Track D will get its own dedicated track-picker session once stabilization completes.
