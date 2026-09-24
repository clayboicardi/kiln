# Session 27 Handoff — catch-up after the break: #31 closed, #28 smoked (1 check left), #37 + #38 found

**Authored:** 2026-09-23 (Session 27, first session after the ~3-month break, 2026-06-28 → 2026-09-21)
**Covers:** Session 26 (2026-06-25 → 06-28, which never got a closeout doc), the 2026-08-20 merge of PR #35, and this catch-up.
**Branch at authoring:** `main` @ `3845d1f`. This doc plus the `CLAUDE.md` refresh ship on `phase-2b/issue-28-desktop-player-concurrency`, restarted from `main`.

---

## TL;DR

The repo was left in a clean state: no open PRs, a clean tree, CI green, the JDK-21 build OK, and every #31 item plus every #28 fix on `main`. PR #35 (#28) was merged 2026-08-20 without its planned manual smoke, so this session ran that smoke. **#28 itself holds up:** Android passed fully, and desktop passed every check it could reach. **The smoke also exposed two older bugs, both filed:**
- **#38 (P0, crash):** Search results never appear, or duplicate and crash the app (LazyColumn duplicate key) while anything writes `track`. The root cause is fully diagnosed and the fix is small.
- **#37 (P1):** the desktop can't play any 24/32-bit FLAC (~21% of the library), because Java Sound on Windows only offers 16-bit output. The fix approach needs Clay's call.

**#28 is one 1-minute check from closing** (see in-flight item 1).

## What happened while you were away

| When | What |
|---|---|
| 2026-06-25/26 | **#31 item 3 shipped.** PR #34 squash-merged as `6eaa013`: single-writer `DatabaseWriter` + WAL, `LibraryWriteLock` deleted. On-device WAL verified (Pixel 7 migrate + Moto fresh create; 517 live / 0 spurious soft-deletes). |
| 2026-06-25 → 06-28 | **#28 full arc:** triage → spec → codex falsify → plan → implement → PR #35 → **6 rounds of codex + gemini bot review.** Codex's round 6 was clean ("Didn't find any major issues", on `8128e9c`). |
| 2026-08-20 | **PR #35 squash-merged** as `3845d1f`. CI green on both legs. The branch was deliberately kept for its per-round review history. |
| 2026-09-23 | **This session:** #31 closed. `CLAUDE.md` refreshed. #28 smoke run on Android (PASS) and desktop (PASS on every reachable check, one 16-bit check left). The smoke surfaced **#37** (desktop 24-bit) and **#38** (search crash), both filed with root causes. PR #36 carries this doc. The branch name was reused per the harness rule (see Decisions). |

## #28 — what shipped in PR #35 (for the smoke tester)

Root causes found in Session 26 triage:
1. **"Can't start a track while one is playing" and "skip is a no-op"** came from `audioDispatcher` starvation. The blocking libFLAC decode+write loop held the single audio thread, so every `withContext(audioDispatcher)` control op queued behind it until EOF.
2. **"Skip does nothing"** also had a UI cause: Library and Search loaded a **single-item queue**. They now load the whole list and start from the clicked row.
3. **"Analyzer only processes a small subset"** was a theory: the RG backfill ran on a composition-bound `rememberCoroutineScope`, so closing Settings cancelled it. It now runs on the process-lifetime `appScope`. **This is the least-proven fix; the smoke confirms it or refutes it.**

The fix is a **command/actor desktop player** (see the `CLAUDE.md` gotcha "The desktop player is a command/actor"). Review rounds also hardened **Android** `Media3ExoPlayerImpl`: `loadQueue` resolves off-Main, Loading→Idle recovery on cancel, a pause during load suppresses autoplay, and `stop()` publishes Idle. So Android needs a smoke too.

## In-flight items, in order

1. **#28 desktop smoke (Clay, 2026-09-23). ONE check left:**
   - ✅ Next / Previous / restart track
   - ✅ End of track → advances to the next queue item. It advanced into a 24-bit track that then failed, but that failure is #37, not #28.
   - ✅ **Item 3 fixed:** Settings → Analyze → close Settings → the backfill kept running. DB count went 31 → 129 (at close) → 163 → 204 → … → 715 over 6 min with Settings closed, then 9,467 by the time the app crashed (#38). **Measured ~2 tracks/s on Cortex**, so a full library takes ~4 h, not the 17–100 h estimate. RG values are sane (track gain −12.5…+10.7 dB, 24-bit files analyze fine). Album RG appears only at the END of a pass (by design). **399 skipped = 398 AAC + 1 32-bit**, which the FLAC-only desktop analyzer can't decode (expected).
   - ⏳ **Click B while A plays: STILL NEEDS a 16-bit pair.** Clay's "B never plays" was track id 7593 (*2morrow*, **24-bit**), i.e. #37: it also failed from idle, so it's a per-file problem, not concurrency. Search is broken (#38), so pick both from the **Library tab's first 500**: **"The 27 Club"** (row 373) and **"The 3 R's"** (row 409), both 16-bit/44.1. Play one, click the other mid-song → it must start promptly from 0:00. **Pass → close #28.**
   - ⏳ RG mode switch mid-playback (Off/Track/Album): nice to have, not a #28 item.
   - The **backfill stopped at 9,467** when the app crashed. Click Analyze to resume (worklist = rows with NULL RG; ~18k left).
1a. **#38 (P0): Search crash + "spotty search".** `LocalLibrarySource.search()`/`browse()` return a LIVE query flattened to `Flow<T>` (`asFlow().mapToList().transform{ forEach emit }`), and it never completes. `SearchTab` does `search(q, 50).take(50).toList()`. With fewer than 50 matches, results NEVER appear (no writes) or get re-appended on every `track` write (duplicates → LazyColumn `Key "19871" was already used` → crash). Pre-existing since `2b4c461` (2026-05-22). `LibraryTab` has the same latent bug for libraries under 500 tracks. **Fix:** make the flattened streams one-shot snapshots that complete (`flow { query.executeAsList().forEach { emit } }.flowOn(io)`). No consumer relies on live-ness. Tests + plan are in the issue.
1b. **#37 (P1): desktop 24/32-bit playback.** Java Sound on Windows only exposes 8/16-bit `SourceDataLine`s. A `jshell` probe on Cortex showed 16-bit opens at 44.1–192 kHz while 24-bit and float32 never do. That's 5,760 tracks (~21%). Pre-existing, NOT a #35 regression. **Recommended:** try the source depth first, fall back to 16-bit with TPDF-dithered requantize AFTER RG (a pure-Kotlin `:audio:dsp` processor), and show "output 16-bit, dithered" in the UI. The issue comment also lists three failure-path UX bugs to fix alongside: stale position, silent error, and auto-advance halting on an unplayable track. **Needs Clay's call on the approach before implementing.**
1c. **Small follow-ups found in the smoke:** (a) backfill progress only updates per 100-track page (~50–75 s), so it looks frozen. Emit per track or on a timer. (b) Library UX: Clay's pain points are that the Library is **songs-only, alphabetical, and only the first 500 titles** (the MVP `.take(500)`), with no album/artist browse. That's why he fell back on Search. Worth an issue plus a prioritization conversation; album/artist browse queries already exist (`selectAllOrderedByArtistThenAlbum`).
2. **#28 smoke, Android: ✅ PASSED 2026-09-23** (Pixel 7 Pro, Android 17 beta, APK = `main` tree, installed over the June build with library data kept). Evidence from `dumpsys media_session`:
   - ✅ Tap a Library row → PLAYING with `active item id` = the tapped row index (6), so the full list is queued and play-from-here works
   - ✅ Tap another row mid-playback → switches (item 8) within ~2 s
   - ✅ Next → item 9, Previous → item 8
   - ✅ Pause → Next → PAUSED at 0 ms on item 9 (no wedge). Play resumes.
   - ✅ Seek near the end → the track runs out and advances from item 9 to item 10 on its own
   - ⚠️ Pause-during-load (round-6 P6-3) is not manually reproducible: the off-Main resolve window is ~tens of ms. **Test gap:** `Media3ExoPlayerImplTest` has NO coverage for the round-4/5/6 Android guards (`loadGeneration` stale-drop, `suppressAutoPlayForLoad`, cancel→Loading→Idle recovery, `stop()` publishing Idle). This is a follow-up to add Robolectric tests, not a #28 blocker.
3. When the 16-bit switch check passes → **close #28**, pointing at the smoke-evidence comments already on #28. If it fails → systematic-debugging on that single symptom. Don't reopen the actor design.
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
- Java Sound on Windows is 16-bit-only (#37). Always test desktop playback with BOTH 16-bit and 24-bit files.
- **Live query → flattened `Flow<T>` never completes** (#38). `take(N).toList()` on it hangs when results < N and duplicates on every table write. Snapshot flows must complete.
- **Successful desktop plays log NOTHING.** A log that shows only failures can't tell you a smoke result. Relaunch with a file log if logs are needed.
- **PowerShell `| Select-Object -First N` on `gradlew :app-desktop:run` ends the pipeline after N lines.** The app keeps running, but its stdout is lost from then on (seen 2026-09-23). The same pipe (`-Last N`) is the likely cause of the `kiln-verify-build` "hang" earlier that day: Gradle had already reported `BUILD SUCCESSFUL in 10s`. Don't pipe long-running Gradle through `Select-Object`; redirect to a file instead.
- `kiln-db-desktop` MCP = `C:\Users\chawo\.kiln\kiln.db` (WAL). Read-only polling from Python while the app writes: `sqlite3.connect('file:...kiln.db?mode=ro', uri=True)`.

## Environment snapshot (2026-09-23)

- Build: Gradle 9.5.1 daemon on Temurin JDK 21 (`JAVA_HOME` prefix still required). Canonical targets up-to-date. The last real run's test XMLs: 388 tests, 0 failures, 0 errors across modules (`:data:library:desktopTest` 109, `:audio:playback:desktopTest` 38).
- Devices: **Pixel 7 Pro** `2A261FDH300B1P` on the Android 17 beta (`cheetah_beta`, CP41.260828.005.A6). The Kiln debug APK from 2026-06-25 and `kiln.db` survived the OS update. **Moto G Play** is on Android 16 stable but was NOT listed by `adb devices`. Clay found the cause: Cortex needed a **restart to finish device setup** (done after this session), so re-check `adb devices` first thing. **Pixel 10 Pro XL** is available on request for stable Android 17.
- Cross-model: claude + codex only (gemini retired, cerebras blocked). No web-grounded voice in `/multi:*`.

## References

- #28 spec / plan: `docs/superpowers/specs/2026-06-25-issue-28-desktop-player-concurrency-design.md`, `docs/superpowers/plans/2026-06-25-issue-28-desktop-player-concurrency.md`
- #31 item 3 spec / plan: `docs/superpowers/{specs,plans}/2026-06-23-issue-31-item-3-single-writer-db*`
- Phase 2b plan: `docs/superpowers/plans/2026-05-23-phase-2b-plan.md`
- Prior handoff: `docs/sessions/2026-06-24-session-25-handoff.md`
- Engram: `kiln/test-device-matrix` (#1954), Session 26 summary (#1946), round-4/5 (#1947, #1948)

## 📋 Copy-paste prompt for the next session

> Pick up Kiln **Session 28**. Read `docs/sessions/2026-09-23-session-27-handoff.md` fully. Set `JAVA_HOME` → JDK 21. If PR #36 is unmerged, ask Clay to merge it. Then, in order:
> 1. **Close out #28.** Launch `:app-desktop:run`, logging to a file with no `Select-Object` pipe. Clay plays **"The 27 Club"** then clicks **"The 3 R's"** mid-song, both 16-bit and in the Library's first 500 → it must start promptly from 0:00. Pass → close #28, citing the evidence comments already on it.
> 2. **Fix #38 (P0 search crash)** with TDD: make `LocalLibrarySource.search()`/`browse()` one-shot snapshot flows that complete, add the desktopTest cases from the issue, run the canonical build, then a desktop smoke with the backfill running.
> 3. **#37 (24-bit desktop playback):** get Clay's call on the recommended 16-bit TPDF-dither fallback, then brainstorm → plan → implement.
> Then the Android guard tests, backfill progress granularity, Library browse UX, and #32.
