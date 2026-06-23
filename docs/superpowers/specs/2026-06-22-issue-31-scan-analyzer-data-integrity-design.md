# Kiln — Scan/Analyzer Data-Integrity Hardening (#31, items 1+2)

**Date:** 2026-06-22
**Author:** Clay Haworth (clayboicardi) with Claude Opus 4.8 (1M context)
**Status:** Design approved (brainstorming, 2026-06-22). Next step: `superpowers:writing-plans`.
**Issue:** [#31 — Scan/analyzer data-integrity hardening (deferred from #30)](https://github.com/clayboicardi/kiln/issues/31)
**Scope:** Items **1** (per-source partial-scan reconciliation) and **2** (analyzer revalidate-before-persist). Item **3** (single-writer DB architecture) is explicitly **deferred to its own spec** — see §8.

---

## 1. Context

PR #30 wired the library scan trigger (scan-on-launch / "Scan now" / auto-scan-on-add) and shipped a `LibraryWriteLock` (`@Singleton` `Mutex`) to serialize the two writers of the `track` table — the `LibraryScanner` and the `TrackAnalysisRunner` — over the single shared SQLite connection. Three data-integrity edges were surfaced across the round-3 codex review and deferred to #31. This spec addresses the two that are **correctness bugs independent of the DB-concurrency architecture**:

- **Item 1** — the soft-delete sweep deletes files that are *present but unreadable*, not just files that are *gone*.
- **Item 2** — the analyzer can write a ReplayGain value onto a row that was changed during the slow `analyze()` call.

Both are fixable without touching the single-connection architecture. The `LibraryWriteLock` already neutralizes the *acute* corruption risk (`SQLITE_BUSY` / nested `BEGIN`), so item 3 is patch-retirement and is sequenced as a separate unit (§8). Items 1 and 2 are **orthogonal**: item 1 is about soft-delete decision correctness on a partial scan (no concurrency involved); item 2 is a read-then-write TOCTOU across `analyze()` that the lock deliberately does **not** cover (it is released during `analyze()` so scans can interleave). Item 2 is therefore needed regardless of item 3.

---

## 2. Item 1 — the invariant

> **`last_scanned_ms >= scanStartedMs` must mean "this file was *encountered* during this pass," not "this file was successfully *read* this pass."**

Today both scanners leave `last_scanned_ms` untouched on a per-file read failure, making a present-but-unreadable file byte-identical to a genuinely-absent one. The global reconciliation

```sql
softDeleteUnscanned:
UPDATE track SET deleted_at_ms = :deletedAtMs
WHERE last_scanned_ms < :scanStartedMs AND deleted_at_ms IS NULL;
```

then soft-deletes both. Failure paths that currently skip the touch:

- **Desktop** (`JvmFilesystemScanner.scanOneFile`): `readTags(...)` throws → `Outcome.ParseFailed` → returns without touching. Worse, `Files.readAttributes(...)` throwing mid-walk is **not** caught at the per-file level — it propagates through the loop and **aborts the entire scan**.
- **Android SAF** (`AndroidMediaStoreScanner.scanSafTrees`): `SafTagReader.read(...) == null` → `parseErrors++; continue` without touching. This is the P1 offline-cloud case: `SafTreeWalker.walk` lists the document (root is readable, so the existing `isTreeReadable` guard does not fire), but the provider fails to open the individual document → the SAF-only row is soft-deleted.
- **Android MediaStore** (`AndroidMediaStoreScanner.scanOneTrack`): `readTagsFromCursor(...)` throws → `Outcome.ParseFailed` without touching (rare for cursor reads, but the rule should be uniform).

---

## 3. Item 1 — design (mark-seen-on-encounter)

**No schema change. No new query** — reuses the existing `touchLastScanned(scannedAtMs, filePath)`.

Rule: **whenever the walk / cursor / SAF-tree yields a file, that file has been *encountered*; if a row already exists for its `file_path`, bump `last_scanned_ms` even when the (re)read fails.** New files (no existing row) that fail to read have nothing to touch and are simply not added this pass — correct, and they retry next pass.

### 3.1 Desktop — `JvmFilesystemScanner.scanOneFile`
- Wrap the per-file body so an IO/parse failure becomes an `Outcome`, never a propagated throw. This fixes the latent **whole-scan-abort** when `Files.readAttributes` throws on a file that vanished between `Files.walk` and the read.
- On any read/parse failure, **if `db.trackQueries.selectByFilePath(pathStr)` returns a row, call `touchLastScanned(scanStartedMs, pathStr)`** before returning the failure outcome. (`Files.walk` already yielded the path, so we know it was encountered.)

### 3.2 Android SAF — `AndroidMediaStoreScanner.scanSafTrees`
- On `SafTagReader.read(...) == null` (and any per-document read failure), **if the existing-row lookup for `filePath` is non-null, `touchLastScanned(scanStartedMs, filePath)`** before `continue`.

### 3.3 Android MediaStore — `AndroidMediaStoreScanner.scanOneTrack`
- On the `ParseFailed` path, apply the same touch-if-existing rule, for uniformity.

### 3.4 Failure direction (accepted)
A provider that returns a *phantom* listing (lists a file that is actually gone, and the read then fails) now **preserves** that row rather than deleting it. For a music library, "don't delete on doubt" is the correct bias; the row self-corrects on a later pass once the provider stops listing it. The existing whole-root guards (`Files.exists` on desktop, `SafTreeWalker.isTreeReadable` on Android) remain and compose with this per-file rule — root guards catch "whole source offline," this rule catches "individual item unreadable within an online source."

---

## 4. Item 2 — design (revalidate-before-persist)

The analyzer reads a worklist page under the lock, releases the lock, spends 1.5–10 s in `analyze(file_path, codec)`, then re-acquires the lock and writes `updateTrackReplayGain` keyed on `id` alone. In that gap a concurrent scan can soft-delete the row, rewrite its `file_path`, or change its `file_mtime_ms` (in-place re-tag). The persist must verify the row still represents the file that was analyzed, atomically with the write.

> Rejected alternative (from the #31 text): a **wider critical section** that holds the lock across `analyze()`. This blocks every scan for the full duration of the backfill (≈17–100 h for a 40k-track library) and defeats the lock's deliberate release-during-`analyze()` design. Non-starter.

### 4.1 `track.sq`
- **New guarded persist:**
  ```sql
  updateTrackReplayGainIfUnchanged:
  UPDATE track
  SET replay_gain_track_db = :db,
      replay_gain_track_peak = :peak
  WHERE id = :id
    AND file_path = :filePath
    AND file_mtime_ms = :fileMtimeMs
    AND file_size_bytes = :fileSizeBytes
    AND deleted_at_ms IS NULL;
  ```
- **Worklist gains `file_mtime_ms` + `file_size_bytes`:** add both to the `selectTracksMissingReplayGain` projection so the runner captures the mtime + size observed at read time. (`file_size_bytes` added per the PR #33 codex review — see §4.3.)
- Retire the now-unguarded `updateTrackReplayGain` **iff** a grep confirms `TrackAnalysisRunner` is its only caller. (Tests that referenced it switch to the guarded query.)

### 4.2 `TrackAnalysisRunner`
- Capture `row.file_path` + `row.file_mtime_ms` at worklist-read time.
- On `Either.Right`, persist via `updateTrackReplayGainIfUnchanged(id, db, peak, filePath, fileMtimeMs)`.
- Apply to **both** `runOnce()` and `runOnceWithProgress()` — they are deliberate duplicate loops (documented in the file); both change and stay in sync.
- A stale row → **0 rows affected → silent no-op → `replay_gain_track_db` stays NULL → the row re-enters the worklist and re-analyzes on a later pass.** This self-healing is the contract; no error is surfaced (the race resolution is benign). Affected-row-count logging of dropped persists is a deferred nice-to-have (§8) — SQLDelight's generated UPDATE returns `Unit`, so surfacing the count would require a raw `driver.execute`, which is not worth it for this fix.

### 4.3 Unknown-mtime SAF rows
Rows with `has_known_mtime = 0` carry `file_mtime_ms = 0`. The worklist captures `0`; the guard `file_mtime_ms = 0` always matches (until a scan changes it, which for an unknown-mtime row it would not meaningfully). The mtime guard therefore **degrades to a harmless always-match** for these rows — no false drops. The `file_path`, `file_size_bytes`, and `deleted_at_ms` guards still apply; **`file_size_bytes` (added per the PR #33 codex review) restores content-change protection for unknown-mtime rows**, where it is the only discriminator left once the mtime guard always-matches.

### 4.4 Album rollup — out of scope
The per-album rollup reads `selectTrackReplayGainForAlbum` and writes `updateAlbumReplayGainForAlbum` in two separate lock acquisitions, with only Kotlin math (no IO) in between. That window is microseconds versus the 1.5–10 s `analyze()` gap — negligible. Not hardened here; noted for completeness.

---

## 5. Data flow (shape unchanged; semantics corrected)

- **Scan:** trigger → `writeLock` held for the whole scan → walk/cursor/tree → per-file upsert / touch *(now: touch-on-encounter even when the read fails)* → root-guarded global `softDeleteUnscanned` → FTS rebuild → (Android) format-fact backfill.
- **Analyzer:** worklist page *(under lock; now also selects `file_mtime_ms`)* → `analyze()` *(no lock; slow)* → **guarded** conditional persist *(under lock)* → album rollup *(under lock)*.

---

## 6. Error handling

- **Item 1:** per-file failures are contained (no scan abort); counted in the existing `parseErrors` tally and surfaced in the existing `ScanResult` log line. Existing rows are preserved.
- **Item 2:** a stale persist is a silent 0-row no-op; the row re-analyzes next pass. No new error type.

---

## 7. Testing (TDD; all desktop-deterministic, no real threads)

- **Item 1 (`JvmFilesystemScannerTest`):**
  - Existing row whose tag-read throws → assert `deleted_at_ms` stays NULL **and** `last_scanned_ms` is bumped to the scan start.
  - A genuinely-removed file (not yielded by the walk) → assert it **is** soft-deleted (regression guard: the fix must not disable reconciliation).
  - A file that vanishes mid-walk (`readAttributes` throws) → assert the scan completes and sibling files still process (whole-scan-abort regression).
- **Item 1 (Android — `scanSafTrees` / `scanOneTrack`):** applies the *identical* invariant. The deterministic unit coverage lives on desktop (above); for Android, add an `androidHostTest` over the `scanSafTrees` read-failure path **iff** the `SafTreeWalker` / `SafTagReader` seam mocks cleanly without instrumentation (decide during planning — these are `object`s taking a `ContentResolver`). If it doesn't mock cleanly, on-device verification carries Android item-1 (re-smoke the Pixel 7: an offline/unreadable SAF doc survives a scan), as it did for #30 — do **not** add a brittle static-mock test just to claim coverage.
- **Item 2:**
  - **Query-level:** insert a row; call `updateTrackReplayGainIfUnchanged` with a mismatched `file_path` (and again with mismatched `file_mtime_ms`) → assert RG unchanged (0 rows); with matching `file_path` + `file_mtime_ms` → assert RG written.
  - **Runner-level (`TrackAnalysisRunnerTest`):** a `FakeTrackAnalyzer` that mutates the target row's `file_path`/`file_mtime_ms` between read and persist (simulating a concurrent scan) → assert the stale RG is dropped and `replay_gain_track_db` stays NULL.
- **Gate:** the canonical 6-target build —
  `:app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`.

---

## 8. Scope boundary

**In scope:** items 1 + 2 above, with tests.

**Out of scope (deferred):**
- **Item 3 — single-writer DB architecture** (single-writer executor / actor, or WAL with proper read/write connection split). Its own spec next; it retires the `LibraryWriteLock` and the ad-hoc guards by making concurrent writers safe by construction. The fixes in this spec stand on their own and remain correct after item 3 lands (item 2's TOCTOU is not a write-write race, so single-writer does not subsume it).
- Album-rollup TOCTOU (§4.4 — negligible window).
- MediaStore + SAF duplicate-row dedup ([#32](https://github.com/clayboicardi/kiln/issues/32)).
- Affected-row-count logging for dropped stale persists (§4.2).

---

## 9. Files touched + commit plan

Per the project's "one change per commit" rule:

- **Commit 1 — item 1 (mark-seen-on-encounter):**
  - `data/library/src/desktopMain/.../scan/JvmFilesystemScanner.kt`
  - `data/library/src/androidMain/.../scan/AndroidMediaStoreScanner.kt`
  - `data/library/src/desktopTest/.../scan/JvmFilesystemScannerTest.kt`
  - `data/library/src/androidHostTest/.../scan/AndroidMediaStoreScannerTest.kt` *(only if the SAF seam mocks cleanly — see §7; otherwise on-device re-smoke)*
  - (one logical change — the same invariant across both platform scanners)
- **Commit 2 — item 2 (revalidate-before-persist):**
  - `data/library/src/commonMain/sqldelight/.../track.sq`
  - `data/library/src/commonMain/.../scan/TrackAnalysisRunner.kt`
  - `data/library/src/desktopTest/.../scan/TrackAnalysisRunnerTest.kt`

Single push at session close; CI runs Android + Desktop on every push. PR body uses **"address #31"** (never "fix/closes #31") so the squash-merge does not auto-close the issue while items 3 / album-rollup remain open.
