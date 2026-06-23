# Scan/Analyzer Data-Integrity Hardening (#31 items 1+2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the scanner from soft-deleting present-but-unreadable files, and stop the ReplayGain analyzer from writing a result onto a row that changed during the slow `analyze()` call.

**Architecture:** Two orthogonal correctness fixes on the existing single-SQLite-connection design (item 3, single-writer DB, is a separate spec). **Item 1 (mark-seen-on-encounter):** bump `last_scanned_ms` whenever a file is *encountered* by the walk/cursor/SAF-tree — even when its read fails — so the global soft-delete sweep deletes only genuinely-absent files. No schema change; reuses `touchLastScanned`. **Item 2 (revalidate-before-persist):** the analyzer persists via a guarded `UPDATE ... WHERE id=? AND file_path=? AND file_mtime_ms=? AND deleted_at_ms IS NULL`, so a stale result is dropped (row stays NULL → re-analyzes next pass).

**Tech Stack:** Kotlin Multiplatform, SQLDelight 2.x, kotlinx.coroutines, Arrow `Either`, kotlin.test + JUnit4 (desktopTest), jaudiotagger (desktop tag read).

**Spec:** `docs/superpowers/specs/2026-06-22-issue-31-scan-analyzer-data-integrity-design.md`

## Global Constraints

- **JDK 21 for the Gradle daemon.** Before any `./gradlew`: PowerShell `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'`. JDK 25 silently wedges the daemon.
- **One change per commit.** Item 1 = one commit, item 2 = one commit. Commit messages end with the two trailers (match `git log`): `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_011WUwRFFUftKXAWcaPF497V`.
- **No schema change** (item 1 reuses `touchLastScanned`; item 2 adds queries + one column to a SELECT projection, not the table).
- **Branch:** `phase-2b/issue-31-data-integrity` (already created off `main`). Single push at session close. PR body uses **"address #31"** — never `fix/closes/resolves #31` (a squash-merge auto-closes the issue, and items 3 + album-rollup remain open).
- **Canonical gate (run after each task):** `:app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`.
- `file_path` is `UNIQUE`; `selectByFilePath` returns a row regardless of `deleted_at_ms` (no soft-delete filter) — use it in tests to inspect soft-deleted rows.

---

## File Structure

| File | Role in this change |
|---|---|
| `data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt` | **Item 1.** `scanOneFile`: move the existing-row lookup above the stat, wrap `Files.readAttributes` so a per-file failure can't abort the scan, and `touchLastScanned` on the readAttributes + readTags failure paths when a row already exists. |
| `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt` | **Item 1.** `scanSafTrees` (`metadata == null` branch) and `scanOneTrack` (`ParseFailed` catch): `touchLastScanned` when a row already exists. Same invariant as desktop. |
| `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/track.sq` | **Item 2.** Add `updateTrackReplayGainIfUnchanged`; add `file_mtime_ms` to the `selectTracksMissingReplayGain` projection; remove the now-unused `updateTrackReplayGain`. |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt` | **Item 2.** In `runOnce` + `runOnceWithProgress`, persist via the guarded query using the worklist row's `file_path` + `file_mtime_ms`. |
| `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScannerTest.kt` | **Item 1 tests.** Present-but-unreadable file preserved + marked seen; absent file still soft-deleted (reconciliation regression guard). |
| `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt` | **Item 2 tests.** Query-level guard (writes on match; no-op on file_path / mtime mismatch) + runner-level drop when the row is soft-deleted mid-analyze. |

**Android item-1 verification:** the Android edits apply the identical invariant but are not unit-tested here (the `SafTreeWalker`/`SafTagReader` `object`s take a `ContentResolver`, which doesn't mock cleanly without instrumentation). They are gated by the canonical build (compilation) and on-device re-smoke on the Pixel 7 (an offline/unreadable SAF doc survives a scan), as #30 was. Do **not** add a brittle static-mock test just to claim coverage. An `androidHostTest` is a stretch goal only if the seam turns out to mock cleanly.

---

## Task 1: Item 1 — mark-seen-on-encounter

**Files:**
- Modify: `data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt` (`scanOneFile`, ~lines 194-316)
- Modify: `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt` (`scanSafTrees` `metadata == null` branch ~lines 407-411; `scanOneTrack` catch ~lines 219-223)
- Test: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScannerTest.kt`

**Interfaces:**
- Consumes (existing, unchanged signatures): `db.trackQueries.selectByFilePath(filePath: String)`, `db.trackQueries.touchLastScanned(scannedAtMs: Long, filePath: String)`, `db.trackQueries.softDeleteUnscanned(deletedAtMs: Long, scanStartedMs: Long)`, `db.trackQueries.selectById(id: Long)`.
- Produces: no new public API. Behavioral change only: a per-file read failure for an already-tracked file no longer leaves `last_scanned_ms` stale.

- [ ] **Step 1: Write the failing tests (desktop)**

Add to `JvmFilesystemScannerTest.kt`. First add imports near the top (after the existing imports):

```kotlin
import java.nio.file.Files
```

Add a helper inside the class (next to `insertSentinelTrack`):

```kotlin
/** Insert a live track row at an exact file_path with caller-chosen scan/mtime/size. Returns row id. */
private fun insertTrackAt(
    db: KilnDatabase,
    filePath: String,
    lastScannedMs: Long,
    fileMtimeMs: Long,
    fileSizeBytes: Long,
): Long {
    db.artistQueries.insert(name = "A", name_sort = "a", musicbrainz_artist_id = null)
    val artistId = db.artistQueries.lastInsertRowId().executeAsOne()
    db.trackQueries.insert(
        album_id = null, artist_id = artistId,
        title = "T", title_sort = "t", duration_ms = 1_000L,
        track_number = null, disc_number = null, year = null, date = null,
        genre = null, composer = null, bpm = null,
        codec = "FLAC", bitrate_kbps = null, sample_rate_hz = 44_100L, bit_depth = 16L, channels = 2L,
        file_path = filePath, file_size_bytes = fileSizeBytes, file_mtime_ms = fileMtimeMs, has_known_mtime = 1L,
        replay_gain_track_db = null, replay_gain_album_db = null,
        replay_gain_track_peak = null, replay_gain_album_peak = null,
        has_embedded_art = 0L, art_path = null, source = "local",
        date_added_ms = lastScannedMs, date_modified_ms = lastScannedMs, last_scanned_ms = lastScannedMs,
    )
    return db.trackQueries.lastInsertRowId().executeAsOne()
}
```

Add the two tests:

```kotlin
@Test
fun `present but unreadable file is marked seen, not soft-deleted`() = runBlocking {
    val (driver, db) = inMemoryDb()
    val tempDir = Files.createTempDirectory("kiln-itm1-preserve")
    try {
        // A file that EXISTS (statable) but whose tags can't be parsed → readTags throws.
        val badFile = tempDir.resolve("bad.flac")
        Files.write(badFile, byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))  // not a valid FLAC stream
        val badPath = badFile.toString()
        // Existing row with OLD last_scanned_ms and mtime/size that DIFFER from the
        // on-disk file, so the unchanged-fast-path does NOT short-circuit and the
        // readTags() path is exercised.
        insertTrackAt(db, filePath = badPath, lastScannedMs = 1_000L, fileMtimeMs = 1L, fileSizeBytes = 1L)

        val floor = System.currentTimeMillis()
        val scanner = JvmFilesystemScanner(
            scanFoldersFlow = flowOf(listOf(tempDir)),
            db = db, driver = driver, ioDispatcher = Dispatchers.Unconfined, writeLock = LibraryWriteLock(),
        )
        val result = scanner.scanIncremental()

        assertTrue(result is Either.Right, "scan must complete despite the unreadable file (no abort): $result")
        assertEquals(0, result.value.tracksSoftDeleted, "present-but-unreadable file must NOT be soft-deleted")
        val row = db.trackQueries.selectByFilePath(badPath).executeAsOne()
        assertNull(row.deleted_at_ms, "row must not be soft-deleted")
        assertTrue(row.last_scanned_ms >= floor, "last_scanned_ms must be bumped (marked seen), was ${row.last_scanned_ms}")
    } finally {
        driver.close()
        Files.deleteIfExists(tempDir.resolve("bad.flac")); Files.deleteIfExists(tempDir)
    }
}

@Test
fun `a row whose file is absent IS still soft-deleted`() = runBlocking {
    // Reconciliation regression guard: the item-1 fix must not disable legitimate soft-delete.
    val (driver, db) = inMemoryDb()
    val tempDir = Files.createTempDirectory("kiln-itm1-reconcile")
    try {
        val ghostPath = tempDir.resolve("ghost.flac").toString()  // never created on disk
        insertTrackAt(db, filePath = ghostPath, lastScannedMs = 1_000L, fileMtimeMs = 1L, fileSizeBytes = 1L)

        val scanner = JvmFilesystemScanner(
            scanFoldersFlow = flowOf(listOf(tempDir)),
            db = db, driver = driver, ioDispatcher = Dispatchers.Unconfined, writeLock = LibraryWriteLock(),
        )
        val result = scanner.scanIncremental()

        assertTrue(result is Either.Right, "expected Right, got $result")
        assertEquals(1, result.value.tracksSoftDeleted, "an absent file's row must be reconciled (soft-deleted)")
        val row = db.trackQueries.selectByFilePath(ghostPath).executeAsOne()
        assertNotNull(row.deleted_at_ms, "absent file's row must be soft-deleted")
    } finally {
        driver.close(); Files.deleteIfExists(tempDir)
    }
}
```

- [ ] **Step 2: Run the tests to verify the first fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.JvmFilesystemScannerTest"`

Expected: `present but unreadable file is marked seen, not soft-deleted` **FAILS** (current code leaves `last_scanned_ms` stale on `ParseFailed`, so `softDeleteUnscanned` sets `deleted_at_ms` → `assertNull(row.deleted_at_ms)` fails). `a row whose file is absent IS still soft-deleted` **PASSES** (guard — reconciliation already works).

- [ ] **Step 3: Implement the desktop fix**

In `JvmFilesystemScanner.kt`, replace the top of `scanOneFile` (from the method signature down to and including the `val tags = try { ... }` block) with the following. The `run { ... upsert ... }` block and `return` line below `val tags` are unchanged.

```kotlin
private fun scanOneFile(path: Path, scanStartedMs: Long): Outcome {
    val pathStr = path.toString()
    // Existing-row lookup hoisted above the stat so the failure paths below can
    // mark an already-tracked file as "seen" (item 1, #31).
    val existing = db.trackQueries.selectByFilePath(pathStr).executeAsOneOrNull()

    val attrs = try {
        Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
    } catch (e: Throwable) {
        // The file was yielded by Files.walk but can't be stat'd now (vanished
        // mid-walk, permissions). It WAS encountered — if we already track it,
        // mark it seen so the soft-delete sweep doesn't treat present-but-
        // unreadable as gone. Contained so one bad file can't abort the scan.
        if (existing != null) {
            db.trackQueries.touchLastScanned(scannedAtMs = scanStartedMs, filePath = pathStr)
        }
        return Outcome.ParseFailed(e)
    }
    val mtime = attrs.lastModifiedTime().toMillis()
    val size = attrs.size()

    // Fast path: unchanged file → minimal touch, skip tag read.
    if (existing != null &&
        existing.file_mtime_ms == mtime &&
        existing.file_size_bytes == size &&
        existing.deleted_at_ms == null
    ) {
        db.trackQueries.touchLastScanned(scannedAtMs = scanStartedMs, filePath = pathStr)
        return Outcome.Unchanged
    }

    val tags = try {
        readTags(path)
    } catch (e: Throwable) {
        // Present but tags unreadable (corrupt header, unsupported). Encountered →
        // mark seen so it isn't soft-deleted; just don't rewrite its metadata.
        if (existing != null) {
            db.trackQueries.touchLastScanned(scannedAtMs = scanStartedMs, filePath = pathStr)
        }
        return Outcome.ParseFailed(e)
    }
```

(`existing` was previously declared after `readAttributes`; this moves the single declaration up. There is now exactly one `val existing` — delete the old declaration that sat just above the unchanged-fast-path.)

- [ ] **Step 4: Run the tests to verify both pass**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.JvmFilesystemScannerTest"`

Expected: **PASS** (both new tests + the 4 existing tests).

- [ ] **Step 5: Implement the Android fix (same invariant)**

In `AndroidMediaStoreScanner.kt`, `scanSafTrees` — the `metadata == null` branch (currently `parseErrors++; log.w {...}; continue`):

```kotlin
if (metadata == null) {
    parseErrors++
    // Encountered (SafTreeWalker yielded the doc) but unreadable — mark an
    // already-tracked row as seen so the soft-delete sweep doesn't wipe a
    // SAF-only track whose provider listed it but couldn't open it (offline
    // cloud doc). Item 1, #31.
    if (existing != null) {
        db.trackQueries.touchLastScanned(scannedAtMs = scanStartedMs, filePath = filePath)
    }
    log.w { "SafTagReader returned null for ${doc.displayName} ($filePath); skipping" }
    continue
}
```

In `AndroidMediaStoreScanner.kt`, `scanOneTrack` — the `readTagsFromCursor` catch (currently `return Outcome.ParseFailed(mediaId, e)`):

```kotlin
val tags = try {
    readTagsFromCursor(cursor, cols, filePath)
} catch (e: Throwable) {
    // Encountered (cursor yielded the row) but unreadable — mark an already-
    // tracked row as seen so it isn't soft-deleted. Item 1, #31.
    if (existing != null) {
        db.trackQueries.touchLastScanned(scannedAtMs = scanStartedMs, filePath = filePath)
    }
    return Outcome.ParseFailed(mediaId, e)
}
```

(`existing` is already in scope in both methods — `scanSafTrees` declares it before the `metadata` read; `scanOneTrack` declares it before the `try`.)

- [ ] **Step 6: Run the canonical build**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`

Expected: **BUILD SUCCESSFUL** — both platforms compile, all desktop tests pass.

- [ ] **Step 7: Commit**

```bash
git add data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt \
        data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt \
        data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScannerTest.kt
git commit -F - <<'EOF'
fix(scan): mark present-but-unreadable files as seen (address #31 item 1)

A file encountered during the walk/cursor/SAF-tree but unreadable (corrupt
tags, offline-cloud SAF doc that lists but won't open) left last_scanned_ms
stale — identical to a genuinely-absent file — so the global softDeleteUnscanned
sweep deleted it. Now touchLastScanned runs on the per-file read-failure paths
when a row already exists, so reconciliation deletes only files that were never
encountered. Also wraps the desktop readAttributes call so one un-stat'able
file can no longer abort the whole scan. No schema change.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011WUwRFFUftKXAWcaPF497V
EOF
```

---

## Task 2: Item 2 — revalidate-before-persist (analyzer TOCTOU)

**Files:**
- Modify: `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/track.sq`
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt`
- Test: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt`

**Interfaces:**
- Produces (new generated query): `db.trackQueries.updateTrackReplayGainIfUnchanged(db: Double?, peak: Double?, id: Long, filePath: String, fileMtimeMs: Long)` — UPDATE that no-ops (0 rows) unless `id`, `file_path`, and `file_mtime_ms` all still match and the row is live.
- Changes (generated row type): `selectTracksMissingReplayGain` row gains `file_mtime_ms: Long`.
- Removes: `db.trackQueries.updateTrackReplayGain(...)` (only callers are the two runner sites updated here).
- Consumes (existing, for tests): `db.trackQueries.softDelete(deletedAtMs: Long?, id: Long)`, `db.trackQueries.selectByFilePath(filePath: String)`, `db.trackQueries.selectById(id: Long)`.

- [ ] **Step 1: Add the SQL (scaffolds the test + runner change)**

In `track.sq`, change `selectTracksMissingReplayGain` to add `file_mtime_ms` to the projection:

```sql
selectTracksMissingReplayGain:
SELECT id, file_path, codec, sample_rate_hz, bit_depth, channels, album_id, file_mtime_ms
FROM track
WHERE deleted_at_ms IS NULL
  AND replay_gain_track_db IS NULL
ORDER BY id
LIMIT :pageSize OFFSET :pageOffset;
```

Replace the `updateTrackReplayGain` block with the guarded query:

```sql
-- Per-track persist, guarded against the analyzer TOCTOU (#31 item 2). The
-- runner reads file_path + file_mtime_ms with the worklist, releases the lock
-- across the slow analyze(), then persists here: if a concurrent scan rewrote
-- file_path, changed file_mtime_ms, or soft-deleted the row in that gap, this
-- UPDATE matches 0 rows and the stale result is dropped (replay_gain_track_db
-- stays NULL, so the row re-analyzes on a later pass). `peak` is linear (RG v2).
updateTrackReplayGainIfUnchanged:
UPDATE track
SET replay_gain_track_db = :db,
    replay_gain_track_peak = :peak
WHERE id = :id
  AND file_path = :filePath
  AND file_mtime_ms = :fileMtimeMs
  AND deleted_at_ms IS NULL;
```

- [ ] **Step 2: Build to regenerate SQLDelight code**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :data:library:generateCommonMainKilnDatabaseInterface`

Expected: **BUILD SUCCESSFUL** — `KilnDatabase.trackQueries` now exposes `updateTrackReplayGainIfUnchanged(...)` and the `selectTracksMissingReplayGain` row type has `file_mtime_ms`. (`:data:library:compileKotlinDesktop` will now fail to compile `TrackAnalysisRunner.kt`, which still calls the removed `updateTrackReplayGain` — fixed in Step 6.)

- [ ] **Step 3: Write the query-level guard tests**

Add to `TrackAnalysisRunnerTest.kt`:

```kotlin
@Test
fun `updateTrackReplayGainIfUnchanged writes when id, file_path and mtime all match`() {
    val artistId = testDb.insertArtist("A")
    val id = testDb.insertTrack(artistId, title = "T", filePath = "/x.flac", fileMtimeMs = 111L)
    testDb.db.trackQueries.updateTrackReplayGainIfUnchanged(
        db = 5.0, peak = 0.9, id = id, filePath = "/x.flac", fileMtimeMs = 111L,
    )
    assertEquals(5.0, testDb.db.trackQueries.selectById(id).executeAsOne().replay_gain_track_db!!, 1e-9)
}

@Test
fun `updateTrackReplayGainIfUnchanged is a no-op when file_path changed`() {
    val artistId = testDb.insertArtist("A")
    val id = testDb.insertTrack(artistId, title = "T", filePath = "/x.flac", fileMtimeMs = 111L)
    testDb.db.trackQueries.updateTrackReplayGainIfUnchanged(
        db = 5.0, peak = 0.9, id = id, filePath = "/STALE.flac", fileMtimeMs = 111L,
    )
    assertNull(testDb.db.trackQueries.selectById(id).executeAsOne().replay_gain_track_db)
}

@Test
fun `updateTrackReplayGainIfUnchanged is a no-op when mtime changed`() {
    val artistId = testDb.insertArtist("A")
    val id = testDb.insertTrack(artistId, title = "T", filePath = "/x.flac", fileMtimeMs = 111L)
    testDb.db.trackQueries.updateTrackReplayGainIfUnchanged(
        db = 5.0, peak = 0.9, id = id, filePath = "/x.flac", fileMtimeMs = 999L,
    )
    assertNull(testDb.db.trackQueries.selectById(id).executeAsOne().replay_gain_track_db)
}
```

- [ ] **Step 4: Write the runner-level drop test**

Add to `TrackAnalysisRunnerTest.kt` (the analyzer closure simulates a concurrent scan soft-deleting the row mid-`analyze()`):

```kotlin
@Test
fun `analyzer result is dropped when the row is soft-deleted during analyze`() = runBlocking {
    val artistId = testDb.insertArtist("Test Artist")
    val albumId = testDb.insertAlbum(artistId, "Test Album")
    val id = testDb.insertTrack(artistId, albumId, "T", filePath = "/test/orig.flac", fileMtimeMs = 111L)

    val analyzer = object : TrackAnalyzer {
        override suspend fun analyze(filePath: String, codec: String): Either<TrackAnalysisError, TrackLoudness> {
            // Concurrent scan soft-deletes this row while we "analyze" it.
            testDb.db.trackQueries.softDelete(deletedAtMs = 9_999_999L, id = id)
            return Either.Right(TrackLoudness(integratedLufs = -20.0, truePeakDbtp = -1.0))
        }
    }
    val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())
    runner.runOnce()

    // The guarded persist must have matched 0 rows; RG stays NULL so the row
    // re-analyzes on a later pass. selectByFilePath returns soft-deleted rows.
    val row = testDb.db.trackQueries.selectByFilePath("/test/orig.flac").executeAsOne()
    assertNull(row.replay_gain_track_db, "stale RG must NOT be persisted onto the changed row")
}
```

- [ ] **Step 5: Run the new tests to verify the runner-level one fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.TrackAnalysisRunnerTest"`

Expected: compile **fails** first (the runner still references the removed `updateTrackReplayGain`). That is the signal to do Step 6. (If you prefer a green compile before this run, do Step 6 first, then run: the three query-level tests PASS, and `analyzer result is dropped...` FAILS until Step 6 switches the runner to the guarded query — pre-fix, the unguarded `WHERE id=?` write lands on the soft-deleted row.)

- [ ] **Step 6: Switch the runner to the guarded query (both methods)**

In `TrackAnalysisRunner.kt`, in **both** `runOnce()` (~line 100) and `runOnceWithProgress()` (~line 222), replace the persist call:

```kotlin
                    is Either.Right -> {
                        val gainDb = REFERENCE_LUFS - result.value.integratedLufs
                        val peakLinear = dbtpToLinear(result.value.truePeakDbtp)
                        writeLock.mutex.withLock {
                            db.trackQueries.updateTrackReplayGainIfUnchanged(
                                db = gainDb,
                                peak = peakLinear,
                                id = row.id,
                                filePath = row.file_path,
                                fileMtimeMs = row.file_mtime_ms,
                            )
                        }
                        analyzed++
                        row.album_id?.let { touchedAlbumIds.add(it) }
                    }
```

(`row.file_path` was already read; `row.file_mtime_ms` is now available from the Step-1 projection. `analyzed++` still counts attempts, not confirmed writes — acceptable per spec §4.2.)

- [ ] **Step 7: Run the analyzer tests to verify all pass**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.TrackAnalysisRunnerTest"`

Expected: **PASS** — the 4 new tests plus all pre-existing `TrackAnalysisRunnerTest` cases (the happy-path runner tests still match because the worklist `file_mtime_ms` is passed straight back to the guarded UPDATE unchanged).

- [ ] **Step 8: Run the canonical build**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`

Expected: **BUILD SUCCESSFUL** — both platforms compile (Android `Media3ExoPlayerImpl`/analyzer paths don't call the removed query), all desktop tests pass.

- [ ] **Step 9: Commit**

```bash
git add data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/track.sq \
        data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt \
        data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt
git commit -F - <<'EOF'
fix(analyzer): revalidate row before persisting ReplayGain (address #31 item 2)

The analyzer reads a worklist row, releases the write lock across the slow
(1.5-10s) analyze(), then persisted via UPDATE ... WHERE id=? only — so a
concurrent scan that rewrote file_path, changed file_mtime_ms, or soft-deleted
the row in that gap got a ReplayGain value computed for the OLD file written
onto the new row state. Persist now goes through updateTrackReplayGainIfUnchanged
(WHERE id=? AND file_path=? AND file_mtime_ms=? AND deleted_at_ms IS NULL): a
stale result matches 0 rows and is dropped, leaving replay_gain_track_db NULL so
the row re-analyzes next pass. selectTracksMissingReplayGain now also projects
file_mtime_ms; the unguarded updateTrackReplayGain is retired.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011WUwRFFUftKXAWcaPF497V
EOF
```

---

## Self-Review

**1. Spec coverage:**
- Spec §3.1 (desktop scanOneFile: wrap readAttributes + touch on failure) → Task 1 Step 3. ✓
- Spec §3.2 (Android scanSafTrees touch) → Task 1 Step 5. ✓
- Spec §3.3 (Android scanOneTrack touch) → Task 1 Step 5. ✓
- Spec §4.1 (new guarded query + worklist mtime column + retire unguarded) → Task 2 Steps 1, 6. ✓
- Spec §4.2 (runner uses guarded query in both methods; self-heal; no affected-count) → Task 2 Step 6. ✓
- Spec §4.3 (unknown-mtime SAF rows degrade to always-match) → covered by construction (file_mtime_ms=0 passed straight through); not separately tested — low value, noted. ✓
- Spec §4.4 (album rollup out of scope) → not touched. ✓
- Spec §7 testing: desktop item-1 preserve + reconcile guard (Task 1 Step 1); item-2 query-level ×3 + runner drop (Task 2 Steps 3-4). The spec's "vanished mid-walk (readAttributes throws)" case shares the identical try/catch containment as the readTags path; it has no deterministic unit trigger (genuine walk-vs-stat race), so it is covered by the Step-3 wrap + the "scan completes" assertion in the preserve test, not a separate brittle test. ✓ (gap acknowledged, not silent)
- Spec §8 scope (item 3 / album-rollup / #32 deferred) → respected. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to". Every code step shows full code. ✓

**3. Type consistency:** `updateTrackReplayGainIfUnchanged(db, peak, id, filePath, fileMtimeMs)` — defined in Task 2 Step 1, consumed with named args in Steps 3, 4, 6 (matches). `row.file_mtime_ms` produced by Step 1's projection, consumed in Step 6. `softDelete(deletedAtMs, id)`, `selectByFilePath`, `selectById`, `touchLastScanned(scannedAtMs, filePath)` — all existing signatures, used as generated. ✓

---

## Execution notes

- Task 1 and Task 2 are independent; either order works, but Task 1 first matches the spec's commit order.
- After both commits: push the branch, open the PR (body says **"address #31"**), run the bot-review loop (gemini + codex), then re-smoke on the Pixel 7 (`MSYS_NO_PATHCONV=1`; pull the DB to a cwd-relative path) to confirm the Android item-1 paths behave on-device.
