# Session 27 Handoff — catch-up after the break: #31 closed, #28 merged and waiting on its smoke

**Authored:** 2026-09-23 (Session 27, first session after the ~3-month break, 2026-06-28 → 2026-09-21)
**Covers:** Session 26 (2026-06-25 → 06-28, which never got a closeout doc), the 2026-08-20 merge of PR #35, and this catch-up.
**Branch at authoring:** `main` @ `3845d1f`. This doc plus the `CLAUDE.md` refresh ship on `phase-2b/issue-28-desktop-player-concurrency`, restarted from `main`.

---

## TL;DR

Nothing is broken. The project stopped at a clean point: no open PRs, a clean tree, no stashes, CI green, and the local build is fine on JDK 21. Every #31 item and every #28 fix is on `main`. **The one real gap:** PR #35 (#28) was merged on 2026-08-20 without the manual smoke that Session 26 planned. **The Android smoke was run and passed this session. The desktop smoke is still pending** (it needs Clay at the desktop to listen), and #28 stays open until it passes. After that: #32 → then choose between the Spec Sheet redesign and Phase 2b-B B1.

## What happened while you were away

| When | What |
|---|---|
| 2026-06-25/26 | **#31 item 3 shipped.** PR #34 squash-merged as `6eaa013`: single-writer `DatabaseWriter` + WAL, `LibraryWriteLock` deleted. On-device WAL verified (Pixel 7 migrate + Moto fresh create; 517 live / 0 spurious soft-deletes). |
| 2026-06-25 → 06-28 | **#28 full arc:** triage → spec → codex falsify → plan → implement → PR #35 → **6 rounds of codex + gemini bot review.** Codex's round 6 was clean ("Didn't find any major issues", on `8128e9c`). |
| 2026-08-20 | **PR #35 squash-merged** as `3845d1f`. CI green on both legs. The branch was deliberately kept for its per-round review history. |
| 2026-09-23 | **This session:** #31 closed (all 3 items shipped, closing comment posted). `CLAUDE.md` status and stale desktop-player notes refreshed. This handoff written. Branch name reused per the harness rule (see "Branch note" below). |

## #28 — what shipped in PR #35 (for the smoke tester)

Root causes found in Session 26 triage:
1. **"Can't start a track while one is playing" and "skip is a no-op"** came from `audioDispatcher` starvation. The blocking libFLAC decode+write loop held the single audio thread, so every `withContext(audioDispatcher)` control op queued behind it until EOF.
2. **"Skip does nothing"** also had a UI cause: Library and Search loaded a **single-item queue**. They now load the whole list and start from the clicked row.
3. **"Analyzer only processes a small subset"** was a theory: the RG backfill ran on a composition-bound `rememberCoroutineScope`, so closing Settings cancelled it. It now runs on the process-lifetime `appScope`. **This is the least-proven fix; the smoke confirms it or refutes it.**

The fix is a **command/actor desktop player** (see the `CLAUDE.md` gotcha "The desktop player is a command/actor"). Review rounds also hardened **Android** `Media3ExoPlayerImpl`: `loadQueue` resolves off-Main, Loading→Idle recovery on cancel, a pause during load suppresses autoplay, and `stop()` publishes Idle. So Android needs a smoke too.

## In-flight items, in order

0. **NEW #37 (found during the smoke): desktop can't play ANY 24/32-bit FLAC.** Java Sound on Windows only exposes 8/16-bit `SourceDataLine`s. A `jshell` probe on Cortex showed 16-bit opens at 44.1–192 kHz while 24-bit and float32 never do. That's 5,760 tracks (~21%). This is pre-existing, NOT a #35 regression: the pre-actor code opened the line identically. The proposed fix is to try the source depth first and fall back to 16-bit with TPDF-dithered requantize after RG (in `:audio:dsp`), with the output format shown honestly in the UI. **Needs Clay's call before implementing.**
1. **#28 smoke, desktop** (`:app-desktop:run`, real library `D:\tiddl`). **Use 16-bit tracks** until #37 lands, because 24-bit fails at line-open no matter what. Successful plays write NOTHING to the log, so the 7 logged failures on 2026-09-23 don't mean the smoke failed:
   - [ ] Click track B while track A plays → B starts promptly
   - [ ] Next / Previous work mid-playback
   - [ ] Pause → skip → the new track loads (paused or playing, but it doesn't wedge)
   - [ ] Let a track run to its end → it advances to the next queue item without clipping the tail
   - [ ] Settings → Analyze → **close Settings** → the backfill keeps running. Objective check: `SELECT COUNT(*) FROM track WHERE replay_gain_track_db IS NOT NULL AND deleted_at_ms IS NULL` keeps rising (`kiln-db-desktop` MCP).
   - [ ] RG mode switch (Off/Track/Album) mid-playback changes loudness
2. **#28 smoke, Android: ✅ PASSED 2026-09-23** (Pixel 7 Pro, Android 17 beta, APK = `main` tree, installed over the June build with library data kept). Evidence from `dumpsys media_session`:
   - ✅ Tap a Library row → PLAYING with `active item id` = the tapped row index (6), so the full list is queued and play-from-here works
   - ✅ Tap another row mid-playback → switches (item 8) within ~2 s
   - ✅ Next → item 9, Previous → item 8
   - ✅ Pause → Next → PAUSED at 0 ms on item 9 (no wedge). Play resumes.
   - ✅ Seek near the end → the track runs out and advances from item 9 to item 10 on its own
   - ⚠️ Pause-during-load (round-6 P6-3) is not manually reproducible: the off-Main resolve window is ~tens of ms. **Test gap:** `Media3ExoPlayerImplTest` has NO coverage for the round-4/5/6 Android guards (`loadGeneration` stale-drop, `suppressAutoPlayForLoad`, cancel→Loading→Idle recovery, `stop()` publishing Idle). This is a follow-up to add Robolectric tests, not a #28 blocker.
3. When the desktop smoke passes → **close #28** with a smoke-evidence comment (the Android evidence is already on #28). If an item fails → systematic-debugging on that single symptom. Don't reopen the actor design.
3a. **Follow-up (small):** Robolectric tests for the Android `loadQueue` race guards listed in 2 ⚠️.
4. **#32** — MediaStore+SAF duplicate-row dedup. Fully specified in the issue: dedupe by `(file_size_bytes, file_mtime_ms, display_name)` after both passes, requiring `has_known_mtime = 1`. All writes go through `DatabaseWriter.write { }`. Smoke on the Pixel 7 (3 overlapping SAF folders reproduce it: "Ain't No Rest for the Wicked" ×3).
5. **Then choose one:** the Spec Sheet aesthetic redesign (a Claude Design pass first), or **Phase 2b-B B1**, the Android JNA-libFLAC port (30–50h, `docs/superpowers/plans/2026-05-23-phase-2b-plan.md` §6). B1 has been unblocked since B0 passed on 2026-06-19. B2's null-test matrix still waits on a hi-res USB DAC re-probe.

## Decisions made

- **#31 closed as completed** (items 1+2 in #33, item 3 in #34). Session 26 had left it open for Clay to close.
- **Round-5 revert (Session 26), recorded here for posterity:** round 4 moved desktop `loadQueue`'s `getPlayable` resolve OFF the actor. Round 5 reverted it. Resolving on the actor is bounded (Library/Search take ≤500 rows, ~tens of ms of indexed lookups), and FIFO command order makes stale-queue application structurally impossible. The off-actor version needed a generation token and a `superseding` flag, and those added two new edge bugs. Lesson: on an actor, a bounded synchronous step beats an async optimization that needs its own invalidation protocol.
- **Branch reuse:** the harness requires this session's work on `phase-2b/issue-28-desktop-player-concurrency`. That branch was fully merged (its tree equals `3845d1f`), so it was restarted from `main`. The six review-round commits are kept permanently at GitHub's `refs/pull/35/head` (the Commits tab on PR #35) and in a local `archive/pr-35-review-rounds` branch on Cortex.

## Gotchas discovered (promoted to `CLAUDE.md` this session)

- `SourceDataLine.isRunning()` is false until the first write after `start()`, so don't gate writes on it.
- `SourceDataLine.drain()` on a stopped line with buffered audio blocks forever, so use a bounded park.
- Java line getters override as `fun getX()` in Kotlin fakes. A reassigned loop `var` defeats sealed-`when` exhaustiveness.
- Gemini CLI retired 2026-06-18. The GitHub PR bots (`@codex review`, `/gemini review`) still work.
- (Session 25, already recorded) Trigger bot re-review as a separate step AFTER the push lands. Re-anchored inline comments keep their OLD timestamp.
- **`kiln-verify-build` wrapper hang (unresolved):** on 2026-09-23 `run-verify.ps1` produced no output after Gradle reported `BUILD SUCCESSFUL in 10s` (all tasks up-to-date), and it had to be stopped. Not yet diagnosed. It may be the `| Select-Object -Last N` pipe the invoker used rather than the script. If it recurs, run `.\gradlew` directly.

## Environment snapshot (2026-09-23)

- Build: Gradle 9.5.1 daemon on Temurin JDK 21 (`JAVA_HOME` prefix still required). Canonical targets up-to-date. The last real run's test XMLs: 388 tests, 0 failures, 0 errors across modules (`:data:library:desktopTest` 109, `:audio:playback:desktopTest` 38).
- Devices: **Pixel 7 Pro** `2A261FDH300B1P` on the Android 17 beta (`cheetah_beta`, CP41.260828.005.A6). The Kiln debug APK from 2026-06-25 and `kiln.db` survived the OS update. **Moto G Play** is on Android 16 stable but was NOT listed by `adb devices`, so check its cable and authorization. **Pixel 10 Pro XL** is available on request for stable Android 17.
- Cross-model: claude + codex only (gemini retired, cerebras blocked). No web-grounded voice in `/multi:*`.

## References

- #28 spec / plan: `docs/superpowers/specs/2026-06-25-issue-28-desktop-player-concurrency-design.md`, `docs/superpowers/plans/2026-06-25-issue-28-desktop-player-concurrency.md`
- #31 item 3 spec / plan: `docs/superpowers/{specs,plans}/2026-06-23-issue-31-item-3-single-writer-db*`
- Phase 2b plan: `docs/superpowers/plans/2026-05-23-phase-2b-plan.md`
- Prior handoff: `docs/sessions/2026-06-24-session-25-handoff.md`
- Engram: `kiln/test-device-matrix` (#1954), Session 26 summary (#1946), round-4/5 (#1947, #1948)

## 📋 Copy-paste prompt for the next session

> Pick up Kiln **Session 28**. Read `docs/sessions/2026-09-23-session-27-handoff.md` fully. Set `JAVA_HOME` → JDK 21. If #28 is still open, run the **desktop** smoke checklist first (launch `:app-desktop:run`; the Android smoke already passed 2026-09-23). Close #28 on a pass. Then take **#32** (MediaStore+SAF dedup) through brainstorm → plan → implement, with every write via `DatabaseWriter.write { }`.
