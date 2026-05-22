# Session 13 Handoff — Pre-Phase-2a stabilization complete; Phase 2a track-picker session

**Authored:** 2026-05-22 (end of Session 12 — Phases 5-8 stabilization sweep)
**For:** Next Claude session
**Goal:** Pick an actual Phase 2a track to ship. Stabilization is done — every P1 + cheap P2/P3 finding from `docs/reviews/2026-05-21-tooling-armed-review.md` is closed. The pre-flight gate is clean.

This supersedes `2026-05-21-session-12-handoff.md`. Session 12 was the final stabilization session, not a Phase-2a-track-pick session. Session 13+ picks an actual track and ships it.

---

## What Session 12 did (this session)

**28 commits on `main`** (all pushed to origin via single end-of-session push):

| Phase | Scope | Commits | New tests |
|---|---|---|---|
| **6** — LocalLibrarySource tests (closes P1-6) | TestDb fixture + setPlayStats query in track.sq + 16 LocalLibrarySource tests across 11 BrowseScope + 2 search + 3 getPlayable | 16 | +16 |
| **5** — androidHostTest infrastructure (closes P1-3) | `withHostTest { }` convention-plugin opt-in + 3 module smoke tests + Robolectric 4.16.1 + CI integration | 4 | +3 |
| **8** — DI graph tests (closes P2-10) | DesktopAppGraphTest (2 tests) + AndroidAppGraphTest (1 test) | 2 | +3 |
| **7** — Media3ExoPlayerImpl tests (closes P1-5) | 12 cases 1:1 port of JavaSoundPlayerImplTest into androidHostTest, deleted Phase 5 placeholder | 12 | +12 (−1 placeholder = net +11) |

**Build state:** `kiln-verify-build` PASS, 5/5 canonical targets, 91 tests + 1 skipped (the canonical `:data:library:desktopTest` count went 47 → 63). The new `:data:library:testAndroidHostTest` + `:audio:playback:testAndroidHostTest` + `:app-android:testDebugUnitTest` tasks run via each module's `check` and contribute the +33 new tests not counted in the canonical desktopTest scope. CI green on push.

**Phases 1-8 of the stabilization plan are COMPLETE. Phase 4 closed without work earlier this session (Track D scope pivoted to full Kiln-internal analyzer; documented in Session 12 handoff).**

---

## Closed review findings (all Phase-2a-blocking items resolved)

From `docs/reviews/2026-05-21-tooling-armed-review.md`:

- **P0**: (none originally)
- **P1**: 6/6 closed — P1-1 (ReplayGain) closed-no-work + scope pivot; P1-2 (parseChannels) fixed; P1-3 (androidTest infra) shipped; P1-4 (JNA bump) shipped; P1-5 (Media3 tests) shipped; P1-6 (LocalLibrarySource tests) shipped
- **P2**: 7/11 closed in stabilization plan (P2-1 Skiko, P2-5 verify-build skill, P2-6 golden corpus CI, P2-9 measurement stub, P2-10 DI graph tests, P2-11 Track A effort revision); 4 explicitly deferred (P2-2 jaudiotagger survey, P2-3 Voyager, P2-4 kmpalette, P2-7 duration_ms CHECK constraint, P2-8 AudioProcessor placement)
- **P3**: 3/7 closed (P3-1 CI test gate, P3-4 plan §4 reconciliation, P3-5 intentional UPDATE comment); 4 deferred per scope (P3-2/P3-3/P3-6 UI/UX, P3-7 MVP-1.0 close criterion)

---

## Plan-text follow-ups (cosmetic, not blocking)

Two plan/doc drift items surfaced during Session 12 execution. Fold into Track A's plan-touch commit OR a Sunday-WR doc sweep:

1. **`docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md` §Phase 6 sketch + `docs/reference/2026-05-18-test-infrastructure-cookbook.md` line 124** — both use `.toList()` on SQLDelight asFlow flows, which never terminates. Update to reference the `snapshot()` helper pattern.

2. **Plan §Phase 5 sketch** — uses AGP 8.x `androidUnitTest` source-set name. Update to `androidHostTest` + `withHostTest { }` opt-in for KMP modules; legacy `androidUnitTest`/`src/test/kotlin` retained for `:app-android`.

Both are pure doc fixes; no code action.

---

## What Session 13 should do

**Pick a Phase 2a track from the 6-track menu** (per `docs/sessions/2026-05-21-session-11-handoff.md` + the plan §4 reconciliation addendum):

| Track | Scope | Effort | Notes |
|---|---|---|---|
| **A — Settings UI** | Settings table + repository + Material3 screen + folder-picker + theming toggle | 10-16 h (revised) | First Compose surface in `:ui:components`. Gates Track B + Track C. |
| **B — SAF folder-picker** | Android SAF folder selection + scan-folder injection via DI | 6-10 h | Surfaced from Session 10 H8 Pixel discovery. Requires Track A's Settings table. |
| **C — Proper UI** | Voyager nav + main 3-tab UI (Library/Now Playing/Search) + Fluid Canvas FFT visualizer subset | 30-60 h | Largest track. Replaces the H7 PlayFirstTrack proof-of-concept. |
| **D — Track D (FULL Kiln-internal ReplayGain)** | EBU R128 / BS.1770-4 analyzer (scanner-side) + consumer-side gain application + Settings toggle + backfill | ~30-66 h | Pivoted scope (see Session 12 handoff). Track D-A (analyzer) is independent; Track D-B (consumer) needs Track A Settings table. |
| **E — MediaSession** | Service binding + lockscreen/notification controls + media-button intent routing | 8-15 h | Media3 instance already constructed (Phase 7 tests confirm); Service binding is the work. |
| **F — CI test gate** | DONE in stabilization (Phase 2). Already running. | — | Covered by Phase 2.1's CI step + Phase 5.4's androidHostTest step + Phase 5 follow-up's report upload. |

Track A is the natural starting point (lowest risk, gates B + C). Track D-A is the most architecturally interesting (Kiln owns the full audio chain). Track E is the smallest user-visible win.

**Suggested Session 13 prompt:**

```
Read docs/sessions/2026-05-22-session-13-handoff.md. Stabilization
is complete — pick a Phase 2a track from the 6-track menu and ship
it via subagent-driven-development. Track A is the natural starting
point. Pause for Clay to confirm track choice before dispatching.
```

---

## Pre-flight (first 5 minutes of Session 13)

**Verify clean baseline:**

```powershell
cd C:\Users\chawo\Projects\kiln
git log --oneline -5           # expect 28 new commits since 5272fcb; CI green
git status                     # expect clean tree
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
# expect: PASS, 5 targets, 91 tests / 1 skipped
gh run list --limit 3          # expect recent green runs incl. the new androidHostTest steps
```

If baseline is dirty or build red — STOP, diagnose, surface to Clay.

**Read order (cold-start):**

1. This file — full read.
2. `docs/sessions/2026-05-21-session-12-handoff.md` — Session 11's framing of the 6-track menu + Track D scope decision (still load-bearing context).
3. `CLAUDE.md` — workflow + build commands.
4. `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md` §4 (Phase 2a flight breakdown) for the chosen track's spec.
5. *Optional* engram lookups:
   - `kiln/session-12-stabilization-complete-2026-05-22` — full Session 12 record
   - `kiln/review-axis-6-checkpoint` — per-track validation context
   - `kiln/replaygain-probe-2026-05-21` — full Track D scope decision (relevant only if Clay picks Track D)

---

## What Session 13 has access to that wasn't there before

- **`androidHostTest` source set** on `:data:library` + `:audio:playback` (Robolectric + bundled SQLite). Real Android-side tests can land trivially now.
- **`testImplementation` block** on `:app-android` (Robolectric). KilnApplication / activity-level testing possible.
- **`:app-desktop` test source set** (pure JVM). Desktop UI / graph tests can land trivially.
- **TestDb fixture** in `:data:library:desktopTest` for in-memory KilnDatabase setup — reuse it across Phase 2a test classes.
- **`snapshot()` helper pattern** for testing SQLDelight asFlow flows (currently inlined in LocalLibrarySourceTest; consider promoting to a shared test utility if reused twice more).
- **`setPlayStats` query** in track.sq (test-only) — let's test authors deterministically set play_count + last_played_ms.
- **CI uploads test reports on Android-job failure** — debugging CI failures no longer requires local repro.
- **Plan + cookbook drift on `.toList()` and `androidUnitTest` are known** — next plan-touching session can land the corrections in passing.
- **kotlin-lsp is OPERATIONAL** (Session 12 resolution). The 2026-05-21 deferral lifted — `LSP documentSymbol/hover/findReferences/goToDefinition/workspaceSymbol` all work against the existing v262.4739.0 install (heap-bumped 6144MB, hard-linked `kotlin-lsp.exe`). Use `LSP` tool calls instead of grep for symbol-level navigation:
  - "What's in this file?" → `LSP documentSymbol filePath=... line=1 character=1`
  - "Where is X used?" → `LSP findReferences` from a use-site (NOT from a declaration — see decisions doc addendum)
  - "What's X's signature?" → `LSP hover` on the identifier
  - "Where is X defined?" → `LSP goToDefinition` from a use-site
  - "Find symbol matching X anywhere in the 8-module workspace" → `LSP workspaceSymbol` (warning: returns large output)
  
  See `docs/decisions/2026-05-21-tooling-recommendation.md` "Addendum 2026-05-22" for the full empirical-verification table + re-test playbook if LSP starts failing.

---

## Reference

- **Plan**: `docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md` (8 phases, all complete or closed-no-work)
- **Review**: `docs/reviews/2026-05-21-tooling-armed-review.md` (24 findings; closed map above)
- **Original Session 11 handoff**: `docs/sessions/2026-05-21-session-11-handoff.md` (6-track menu source)
- **Session 12 handoff (this session's predecessor, now obsolete)**: `docs/sessions/2026-05-21-session-12-handoff.md`
- **Engram topics added Session 12:**
  - `kiln/phase-6-stabilization-complete-2026-05-22`
  - `kiln/session-12-stabilization-complete-2026-05-22`

---

## Copy-paste prompt for Session 13

```
Read docs/sessions/2026-05-22-session-13-handoff.md. Stabilization
is complete — pick a Phase 2a track from the 6-track menu and ship
it via subagent-driven-development. Track A is the natural starting
point. Pause for Clay to confirm track choice before dispatching.
```

---

**End of Session 13 Handoff.** All four remaining stabilization phases (5+6+7+8) shipped in Session 12. The pre-flight gate is clean for the first time since Session 10's H7/H8 vertical-slice surprise. Phase 2a can finally start.
