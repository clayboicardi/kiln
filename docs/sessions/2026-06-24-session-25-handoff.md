# Session 25 Handoff — #31 item 3 (single-writer DB + WAL): spec+plan shipped, impl Tasks 1–2 done, **Task 3 mid-flight (uncommitted WIP)**

**Authored:** 2026-06-24 (Session 25 pause)
**Branch:** `phase-2b/issue-31-item-3-single-writer-db` → PR **#34** (not merged). `#31` stays open.

---

## TL;DR
#31 item 3 = retire `LibraryWriteLock` via a single-writer `DatabaseWriter` + enable WAL. Full arc this session: brainstorm → spec → plan → **3 bot-review rounds (all addressed, converged)** → inline implementation. Impl **Tasks 1–2 committed + build-green**. **Task 3 (the atomic mechanism swap) is ~40% done and UNCOMMITTED in the working tree — finish it next.**

## Git state
- Commits on the branch (the 4 doc commits are **pushed** → PR #34; the 2 code commits are **local only**):
  - `03bcd89` spec · `0395141` plan · `6ea7aaa` round-1 review fixes · `f3b1e11` round-2 review fixes  (docs, pushed)
  - `6985a74` **Task 1** (WAL + busy_timeout) · `3f1d9de` **Task 2** (DatabaseWriter + DI)  (code, LOCAL — not pushed)
- **UNCOMMITTED Task-3 WIP in the working tree** (intentional — `git status` shows them; NOT lost):
  - `data/library/src/commonMain/sqldelight/.../track.sq` — guarded `updateTrackFormatFactsIfUnchanged` / `markBackfilledNoMetadataIfUnchanged` / `selectChanges` + `ORDER BY id`/`OFFSET` + widened projection ✅
  - `data/library/src/commonMain/.../scan/TrackAnalysisRunner.kt` — `writeLock.mutex.withLock` → `writer.write` (analyze() stays between blocks) ✅
  - `data/library/src/desktopMain/.../scan/JvmFilesystemScanner.kt` — catch-at-top + non-suspend `runScanBlocking` in `writer.write` ✅
  - **The tree does NOT compile** until the rest of Task 3 lands (DI still passes `writeLock`; Android scanner/backfill not migrated; `LibraryWriteLock` not deleted).
- **If resuming on a different machine** (WIP not present): redo those 3 files from **plan Task 3** — every edit is specified there. The plan is authoritative.

## PR #34 bot review — CONVERGED (rounds 1–3 all folded into spec + plan)
- **R1:** Android WAL via `enableWriteAheadLogging()`/`onConfigure`; **backfill TOCTOU guard** (C1, item-2 pattern); backfill inside the scanner `Either.catch` (C2); cross-thread WAL test (C4); `LockSupport.parkNanos` not `Thread.sleep`; drop `kilnDb` alias.
- **R2:** pool-wide FK via `setForeignKeyConstraintsEnabled` (not `onOpen` PRAGMA); `ORDER BY id` before `OFFSET`; desktop scanner folder-read inside the catch; delete WAL `-wal`/`-shm` sidecars in tests.
- **R3 (all implementation-level):** `formatBackfill` DI provider needs `DatabaseWriter`; WAL cross-thread test reframed (not a WAL/rollback discriminator — PRAGMA-readback is authoritative); **query-level** TOCTOU test (no private-method seam); `busy_timeout` via `query()` not `execSQL`; `MainActivity` stale `LibraryWriteLock` comment trips the final grep gate.
- **Decision:** stopped the plan-review loop at R3 — findings were implementation-level (build/tests/code-review catch them). Re-trigger after the impl lands.

## Remaining work (executing the plan inline — plan is authoritative)
**Task 3 (finish the atomic swap, then ONE commit):**
1. `AndroidMediaStoreScanner` — same catch-at-top restructure as `JvmFilesystemScanner`; **`backfill.runOnce()` INSIDE the `Either.catch`, success-only** (C2); `writeLock`→`writer` + import.
2. `AndroidFormatFactBackfill` — add `DatabaseWriter`; native reads off-writer; per-page `writer.write { db.transaction { updateTrackFormatFactsIfUnchanged / markBackfilledNoMetadataIfUnchanged } }`; `selectChanges()`-based offset-advance loop (plan Task 3 Step 3b). Constructor + import.
3. `git rm` `data/library/src/commonMain/.../scan/LibraryWriteLock.kt`.
4. DI (`writeLock`→`writer`): DesktopAppGraph `filesystemScanner` + `analysisRunner`; AndroidAppGraph `mediaStoreScanner` + `analysisRunner` + **`formatBackfill` (R3-1: add `writer`)**. Remove both `libraryWriteLock()` providers + `LibraryWriteLock` imports.
5. `app-android/.../MainActivity.kt` (~line 183) — update the stale `LibraryWriteLock` comment (R3-5).
6. Tests: re-point `TrackAnalysisRunnerTest` (11×) + `JvmFilesystemScannerTest` (6×) `LibraryWriteLock()` → `DatabaseWriter(<db>, Dispatchers.Unconfined)` + import; update `AndroidFormatFactBackfillTest` for guarded behavior; add a **query-level** guarded-backfill test in desktopTest (insert row → `updateTrackFormatFactsIfUnchanged` with mismatched mtime/size → assert 0 rows / unchanged).
7. Build-gate (canonical 6-target), then atomic commit: `refactor(db): route all writers through DatabaseWriter; delete LibraryWriteLock (#31 item 3)`.

**Task 4:** `SettingsRepositoryImpl` writes → `writer.write` (drop `kilnDb` alias); `settingsRepository` provider (both graphs) gains `writer`; re-point `SettingsRepositoryImplTest`; add the writer-invariant gotcha to CLAUDE.md. Interface unchanged → `StubSettingsRepository` doubles unaffected.

**Task 5:** full canonical build; `grep -rn "LibraryWriteLock\|\.mutex\.withLock" data app-desktop app-android` = 0; on-device Pixel 7 (`2A261FDH300B1P`) smoke (`PRAGMA journal_mode`=`wal`; `track` 517 live / 0 spurious soft-deletes). Then push + re-trigger bots.

## Build env (non-negotiable)
`$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'` before any `./gradlew` (JDK 25 wedges the daemon). Per-task gate used this session: `:app-android:assembleDebug :app-desktop:assemble :data:library:desktopTest` (fast). Final gate = canonical 6-target.

## Gotchas learned this session (also saved to engram)
- **Git Bash heredoc + chaining:** `git commit -F - <<'EOF' && \` (a `&&` after the heredoc opener) makes bash parse the first body line as a shell command → garbled commit subject. Don't chain after `<<'EOF'`; put `git push` etc. on lines AFTER the closing `EOF`.
- **MSYS path-mangling:** `gh pr comment --body "/gemini review"` — the leading `/` gets rewritten to a Windows path. Use `MSYS_NO_PATHCONV=1` (or PowerShell). `@codex review` (no leading slash) is unaffected.
- **Bot re-review:** doesn't auto-fire on a fix push — trigger AFTER the push lands (a *separate* step, not `git push && gh pr comment`). Filter reviews by `commit_id` + `created_at`; re-anchored round-N comments carry the OLD timestamp.
- **No lint gate:** no ktlint/detekt/spotless in the build — over-indentation compiles, but the PR bots flag it; keep indentation clean.

## References
- Plan (authoritative for Task 3+): `docs/superpowers/plans/2026-06-23-issue-31-item-3-single-writer-db.md`
- Spec: `docs/superpowers/specs/2026-06-23-issue-31-item-3-single-writer-db-design.md`
- PR #34. Engram: `decision/kiln-31-item-3-single-writer-db-scope` (#1938), `decision/kiln-31-item-3-correction-final` (#1939), `#1933` (DB-concurrency arch).

## Copy-paste prompt for the next session
> Pick up Kiln **Session 26**. Read `docs/sessions/2026-06-24-session-25-handoff.md` fully, then the plan `docs/superpowers/plans/2026-06-23-issue-31-item-3-single-writer-db.md`. Set `JAVA_HOME` → JDK 21. We're mid-implementation of #31 item 3 on branch `phase-2b/issue-31-item-3-single-writer-db` (PR #34): Tasks 1–2 committed + green; **Task 3 (atomic swap) is partially done as uncommitted WIP** (`git status`: track.sq + TrackAnalysisRunner + JvmFilesystemScanner). Finish Task 3 per the plan (Android scanner + backfill + delete LibraryWriteLock + DI + MainActivity comment + re-point tests + query-level TOCTOU test), build-gate, atomic-commit, then Tasks 4–5, push, re-trigger bots.
