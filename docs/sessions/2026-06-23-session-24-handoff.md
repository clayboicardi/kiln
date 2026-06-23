# Session 24 Handoff — #31 items 1+2 shipped (PR #33 merged; #31 stays open for item 3)

**Authored:** 2026-06-23 (Session 24 closeout)
**For:** the next Claude session (cold-start safe)
**Branch:** `main` @ `ec5e4e6` — PR #33 squash-merged; no open feature branch.

---

## 🚀 Pre-flight (first 5 minutes)

**Read order (cold-start):**
1. This file — full read.
2. `CLAUDE.md` — project orientation + cumulative gotchas.
3. `mem_search "kiln 31 data integrity"` — engram keys: `decision/kiln-31-scan-analyzer-data-integrity-scoped-planned` (#1935, scope + approach) and the session summary (this session). `#1933` is the DB-concurrency architecture (still load-bearing for item 3).

**Build env FIRST:** `JAVA_HOME` → Temurin JDK 21 (`C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`). **JDK 25 wedges the Gradle daemon.** PowerShell: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'`.

**Confirm baseline:**
```
git checkout main && git pull
$env:JAVA_HOME = '<jdk21>'; .\gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest
```

---

## Where we are

- **Phase 2b-A is shipped + merged; #31 items 1+2 are now also merged.** Arc of PRs: **#29** (Spec Sheet UI + Android format-fact backfill) → **#30** (library scan trigger, closed #27) → **#33** (this session: scan/analyzer data-integrity hardening, items 1+2).
- **`main` @ `ec5e4e6`**, CI green, working tree clean.
- **PR #33 shipped #31 items 1 + 2:**
  - **Item 1 — mark-seen-on-encounter.** `last_scanned_ms` now means *"encountered this pass,"* not *"successfully read this pass."* A file the walk/cursor/SAF-tree yields but can't read (corrupt tags; an offline-cloud SAF doc that lists but won't open) is now `touchLastScanned`'d when a row already exists, so the global `softDeleteUnscanned` deletes only genuinely-absent files. Also wrapped desktop `Files.readAttributes` so one un-stat'able file can't abort a whole scan. **No schema change.**
  - **Item 2 — revalidate-before-persist.** The analyzer persists via `updateTrackReplayGainIfUnchanged` (`WHERE id=? AND file_path=? AND file_mtime_ms=? AND file_size_bytes=? AND deleted_at_ms IS NULL`). A result computed against a row a concurrent scan changed during the 1.5–10 s `analyze()` gap matches 0 rows and is dropped (`replay_gain_track_db` stays NULL → re-analyzes next pass). `selectTracksMissingReplayGain` now projects `file_mtime_ms` + `file_size_bytes`; the unguarded `updateTrackReplayGain` is retired.
- **On-device verified** (Pixel 7 Pro / Android 16, new APK installed `-r`): `track` **517 live / 0 soft-deleted / 517 freshly-scanned** post-scan — no regression, full mark-seen coverage. (The *specific* read-failure→preserve branch wasn't triggered on-device — 0 parse errors, all files readable — but it's deterministically unit-proven on desktop and is the identical `touchLastScanned` one-liner on Android.)
- **`#31` is intentionally STILL OPEN** for item 3. The squash-merge used a controlled `--subject`/`--body` with **"Addresses #31"** (never `closes/fixes/resolves`) so the issue survived the merge.

## Decisions made (Session 24)

- **Scoped #31 to items 1+2; item 3 deferred to its own spec.** The three items are largely independent. Decisive insight: **item 2 is a read-then-write TOCTOU across `analyze()` (the lock is deliberately released there), NOT a write-write race — so a single-writer DB would NOT subsume it.** The `LibraryWriteLock` already neutralizes the acute corruption risk, so item 3 is patch-retirement, not an active-bug fix.
- **Item 1 approach: mark-seen-on-encounter** (over codex's literal "per-root completeness gate") — precise, zero schema change, composes with the existing root guards, avoids fragile file_path→root attribution.
- **Item 2 approach: file_path + mtime + size guard.** Started at file_path+mtime; **`file_size_bytes` added in the PR #33 codex review** because size-only content changes (same path+mtime, realistic with coarse/SAF-preserved mtimes) slip the mtime guard — and for unknown-mtime SAF rows (`file_mtime_ms = 0` always-matches) size is the *only* discriminator left.
- **Bot-review round 1 (both findings real, applied + verified):** gemini ×2 — rethrow `CancellationException` in the per-file `catch (Throwable)` blocks (defensive / `.gemini/styleguide.md`; not currently triggerable since `scanOneFile`/`scanOneTrack` have no suspension points). codex P2 — the `file_size_bytes` guard above.

## Open follow-ups (next session — pick with Clay)

### ⭐ #31 item 3 — single-writer DB architecture (the deferred structural fix; cold-start-ready)
**Why:** retires the ad-hoc `LibraryWriteLock` + the soft-delete root guards by making concurrent writers safe *by construction*. Strongest portfolio-narrative beat; the natural close-out of the scan/analyzer arc.

**The current architecture (read `#1933`):** one `SqlDriver` singleton → one `KilnDatabase`, shared by **both** writers — the `LibraryScanner` (holds the `LibraryWriteLock` Mutex for a whole scan) and the `TrackAnalysisRunner` (acquires it per DB-op, releases during `analyze()`). See `app-desktop/.../DesktopAppGraph.kt` (`JdbcSqliteDriver`, `foreign_keys=true`) and `app-android/.../AndroidAppGraph.kt` (`AndroidSqliteDriver` + `RequerySQLiteOpenHelperFactory` for bundled FTS5).

**Two candidate shapes to brainstorm:**
- **(A) Single-writer executor / actor** — marshal *all* writes onto one dispatcher (single-thread executor or a `Channel`-fed actor coroutine). Writes serialize by construction; the Mutex retires. Touches every write path + the DI graphs.
- **(B) WAL + read/write connection split** — `PRAGMA journal_mode=WAL` lets readers run concurrently with the single writer (better UI responsiveness during scans), but its benefit needs *separate* reader/writer connections — right now there's ONE shared connection, so WAL alone buys little. The real work is the connection split.

**Hard constraint to carry forward:** **item 2's `updateTrackReplayGainIfUnchanged` guard STAYS even after item 3** — single-writer serializes *writes*, but the analyzer's read→analyze(1.5–10s)→write is a TOCTOU, not a write-write race. Don't delete the guard when retiring the lock.

**Suggested path:** `brainstorming` (A vs B vs hybrid) → spec → `writing-plans` → `executing-plans`, same as this session.

### Other open issues
- **#32** — MediaStore + SAF duplicate-row dedup (dedupe by `(file_size_bytes, file_mtime_ms, display_name)` requiring `has_known_mtime = 1`). Smaller, user-visible.
- **#28** — desktop playback / queue / ReplayGain-analyzer defect cluster (open since Session 22, untouched). Distinct subsystems → `systematic-debugging`.
- **Spec Sheet aesthetic redesign** — Clay found the render "dull"; a Claude Design pass is pending (Clay drives the visuals → exports Compose/reference for `SpecSheetContent.kt`).

## Gotchas discovered (this session)

- **JUnit 4 `@Test` must return `Unit`.** `assertNotNull(x, msg)` returns `T` (the non-null value), so `fun test() = runBlocking { … assertNotNull(…) }` infers a non-`Unit` return type → `InvalidTestClassError` on the whole class. Use `assertTrue(x != null, …)` (returns `Unit`) or a trailing `Unit`. (Already in CLAUDE.md; bit a new test this session.)
- **The PR bots do NOT auto-re-review a fix push.** After pushing review fixes, gemini + codex did not re-review on their own — needed explicit **`/gemini review`** and **`@codex review`** PR comments to trigger round 2. (And on a re-review, GitHub re-anchors round-1 inline comments to shifted line numbers — `line:null` means *outdated*, not a new finding.)
- **Squash-merge auto-close guard:** used `gh pr merge --squash --subject … --body …` with **"Addresses #31"** (no `closes/fixes/resolves`) so `--subject`/`--body` fully replace the message and no closing keyword reaches `main`. `#31` stayed open. (This is the #27 false-close gotcha's counter-measure.)
- **Re-smoke recipe (unchanged, works):** `export MSYS_NO_PATHCONV=1` for adb; `adb -s 2A261FDH300B1P install -r app-android/build/outputs/apk/debug/app-android-debug.apk` keeps the 517-track library + SAF grants; pull the DB via `adb -s 2A261FDH300B1P exec-out run-as com.clayworks.kiln cat databases/kiln.db > .smoke.db` to a **cwd-relative** path; query with Windows `python` (on-device `sqlite3` absent). Two devices are attached — always pin `-s 2A261FDH300B1P` (Pixel 7). Delete the pulled `.smoke*.db` after (it's the library DB; don't commit).

## Working tree state

- On `main` @ `ec5e4e6`, clean. PR #33 merged + branch deleted.
- Issues: **#28, #31 (items 1+2 done, item 3 open), #32 open.**
- Spec: `docs/superpowers/specs/2026-06-22-issue-31-scan-analyzer-data-integrity-design.md`. Plan: `docs/superpowers/plans/2026-06-22-issue-31-scan-analyzer-data-integrity.md`.

## References

- Design contract: `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`
- Execution plan: `docs/superpowers/plans/2026-05-18-kiln-execution-plan.md`
- Prior handoff: `docs/sessions/2026-06-21-session-23-handoff.md`
- PRs: #29 (Spec Sheet), #30 (scan trigger), **#33 (this session — #31 items 1+2)**. Disposition trail: PR #33 comments (bot-review round 1).

## Copy-paste prompt for the next session

> Pick up Kiln **Session 25**. Read `docs/sessions/2026-06-23-session-24-handoff.md` fully. Set `JAVA_HOME` → Temurin JDK 21 before any `./gradlew`. #31 items 1+2 are shipped + merged (PR #33); **#31 is still open for item 3 — the single-writer DB structural fix**. Decide with Clay which to start: **(a) #31 item 3 — single-writer DB executor / WAL** (retires `LibraryWriteLock`; keep item 2's `updateTrackReplayGainIfUnchanged` guard — it's a TOCTOU fix single-writer doesn't subsume; brainstorm shapes A/B in the handoff), **(b) #32 MediaStore+SAF dedup**, **(c) #28 desktop playback/queue/analyzer cluster**, or **(d) the Spec Sheet aesthetic redesign via Claude Design**.
