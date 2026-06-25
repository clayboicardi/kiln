// Desktop scanner regression tests. Focused on safety-net behaviors that
// would otherwise destroy user data — empty-scanFolders soft-delete bomb
// being the canonical P0 caught by Session 10 /ultrareview U2.
//
// Uses an in-memory SQLDelight database for hermetic isolation; no
// filesystem state.

package com.clayworks.kiln.library.scan

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import arrow.core.Either
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.db.DatabaseWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmFilesystemScannerTest {

    private fun inMemoryDb(): Pair<JdbcSqliteDriver, KilnDatabase> {
        val driver = JdbcSqliteDriver(
            url = JdbcSqliteDriver.IN_MEMORY,
            schema = KilnDatabase.Schema,
        )
        val db = KilnDatabase(driver)
        return driver to db
    }

    /** Insert a sentinel track via SQLDelight typed queries. Returns the row id. */
    private fun insertSentinelTrack(db: KilnDatabase, scanStartedMs: Long): Long {
        db.artistQueries.insert(
            name = "Sentinel Artist",
            name_sort = "sentinel artist",
            musicbrainz_artist_id = null,
        )
        val artistId = db.artistQueries.lastInsertRowId().executeAsOne()
        db.trackQueries.insert(
            album_id = null,
            artist_id = artistId,
            title = "Sentinel Title",
            title_sort = "sentinel title",
            duration_ms = 1_000L,
            track_number = null,
            disc_number = null,
            year = null,
            date = null,
            genre = null,
            composer = null,
            bpm = null,
            codec = "FLAC",
            bitrate_kbps = null,
            sample_rate_hz = 44_100L,
            bit_depth = 16L,
            channels = 2L,
            file_path = "/tmp/sentinel-must-not-be-deleted.flac",
            file_size_bytes = 1024L,
            file_mtime_ms = 0L,
            has_known_mtime = 1L,
            replay_gain_track_db = null,
            replay_gain_album_db = null,
            replay_gain_track_peak = null,
            replay_gain_album_peak = null,
            has_embedded_art = 0L,
            art_path = null,
            source = "local",
            date_added_ms = scanStartedMs,
            date_modified_ms = scanStartedMs,
            last_scanned_ms = scanStartedMs,
        )
        return db.trackQueries.lastInsertRowId().executeAsOne()
    }

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

    // ---------- Empty-scanFolders guard (U2) ----------

    @Test
    fun `scanIncremental with empty scanFolders does NOT soft-delete existing tracks`() = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val sentinelId = insertSentinelTrack(db, scanStartedMs = 1_000L)
            val scanner = JvmFilesystemScanner(
                scanFoldersFlow = flowOf(emptyList()),
                db = db,
                driver = driver,
                ioDispatcher = Dispatchers.Unconfined,
                writer = DatabaseWriter(db, Dispatchers.Unconfined),
            )

            val result = scanner.scanIncremental()

            assertTrue(result is Either.Right, "expected Right, got: $result")
            val scanResult = result.value
            assertEquals(0, scanResult.tracksAdded, "tracksAdded should be 0")
            assertEquals(0, scanResult.tracksUpdated, "tracksUpdated should be 0")
            assertEquals(
                0,
                scanResult.tracksSoftDeleted,
                "tracksSoftDeleted MUST be 0 — empty scanFolders is the data-loss bomb",
            )
            assertEquals(0, scanResult.tracksUnchanged, "tracksUnchanged should be 0")

            // The sentinel track MUST still be live.
            val sentinel = db.trackQueries.selectById(sentinelId).executeAsOneOrNull()
            assertNotNull(sentinel, "sentinel track row should still exist")
            assertNull(
                sentinel.deleted_at_ms,
                "sentinel.deleted_at_ms MUST be null (track must NOT be soft-deleted)",
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `scanFull with empty scanFolders also does NOT soft-delete existing tracks`() = runBlocking {
        // The forceFullRescan=true path FIRST resets last_scanned_ms = 0 for
        // every track via the bulk UPDATE — if the guard is missing this would
        // be even more catastrophic than scanIncremental because the reset is
        // unconditional. Verify the guard short-circuits BEFORE the reset.
        val (driver, db) = inMemoryDb()
        try {
            val originalScanMs = 5_000L
            val sentinelId = insertSentinelTrack(db, scanStartedMs = originalScanMs)
            val scanner = JvmFilesystemScanner(
                scanFoldersFlow = flowOf(emptyList()),
                db = db,
                driver = driver,
                ioDispatcher = Dispatchers.Unconfined,
                writer = DatabaseWriter(db, Dispatchers.Unconfined),
            )

            val result = scanner.scanFull()

            assertTrue(result is Either.Right, "expected Right, got: $result")
            assertEquals(0, result.value.tracksSoftDeleted, "no soft-deletes on empty scanFolders")

            val sentinel = db.trackQueries.selectById(sentinelId).executeAsOneOrNull()
            assertNotNull(sentinel, "sentinel track row should still exist")
            assertNull(sentinel.deleted_at_ms, "sentinel MUST NOT be soft-deleted")
            // The bulk UPDATE that resets last_scanned_ms = 0 should also have
            // been skipped — the original value persists.
            assertEquals(
                originalScanMs,
                sentinel.last_scanned_ms,
                "last_scanned_ms should be untouched (guard short-circuits before the bulk reset)",
            )
        } finally {
            driver.close()
        }
    }

    // ---------- Inaccessible-root guard (codex #4, PR #30) ----------

    @Test
    fun `scanIncremental with an inaccessible root does NOT soft-delete existing tracks`() = runBlocking {
        // A configured root that is non-empty in the LIST but inaccessible on
        // disk (unmounted external drive, missing D:\tiddl). The empty-folders
        // guard does NOT cover this — the list is non-empty — so without the
        // inaccessible-root guard the walk discovers 0 files and softDelete
        // wipes the whole library on the next automatic scan-on-launch.
        val (driver, db) = inMemoryDb()
        try {
            val sentinelId = insertSentinelTrack(db, scanStartedMs = 1_000L)
            val scanner = JvmFilesystemScanner(
                scanFoldersFlow = flowOf(listOf(Path.of("/nonexistent/unmounted-drive/music"))),
                db = db,
                driver = driver,
                ioDispatcher = Dispatchers.Unconfined,
                writer = DatabaseWriter(db, Dispatchers.Unconfined),
            )

            val result = scanner.scanIncremental()

            assertTrue(result is Either.Right, "expected Right, got: $result")
            assertEquals(
                0,
                result.value.tracksSoftDeleted,
                "tracksSoftDeleted MUST be 0 when a configured root is inaccessible — " +
                    "else an unmounted drive wipes the library on scan-on-launch",
            )
            val sentinel = db.trackQueries.selectById(sentinelId).executeAsOneOrNull()
            assertNotNull(sentinel, "sentinel track row should still exist")
            assertNull(
                sentinel.deleted_at_ms,
                "sentinel.deleted_at_ms MUST be null when a configured root is inaccessible",
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `scanIncremental with empty scanFolders + empty DB returns zero ScanResult cleanly`() = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            val scanner = JvmFilesystemScanner(
                scanFoldersFlow = flowOf(emptyList()),
                db = db,
                driver = driver,
                ioDispatcher = Dispatchers.Unconfined,
                writer = DatabaseWriter(db, Dispatchers.Unconfined),
            )

            val result = scanner.scanIncremental()

            assertTrue(result is Either.Right, "expected Right on empty DB + empty folders")
            val scanResult = result.value
            assertEquals(0, scanResult.tracksAdded)
            assertEquals(0, scanResult.tracksUpdated)
            assertEquals(0, scanResult.tracksSoftDeleted)
            assertEquals(0, scanResult.tracksUnchanged)
            assertEquals(0L, scanResult.durationMs)
        } finally {
            driver.close()
        }
    }

    // ---------- Item 1: mark-seen-on-encounter (#31) ----------

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
                db = db, driver = driver, ioDispatcher = Dispatchers.Unconfined, writer = DatabaseWriter(db, Dispatchers.Unconfined),
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
                db = db, driver = driver, ioDispatcher = Dispatchers.Unconfined, writer = DatabaseWriter(db, Dispatchers.Unconfined),
            )
            val result = scanner.scanIncremental()

            assertTrue(result is Either.Right, "expected Right, got $result")
            assertEquals(1, result.value.tracksSoftDeleted, "an absent file's row must be reconciled (soft-deleted)")
            val row = db.trackQueries.selectByFilePath(ghostPath).executeAsOne()
            assertTrue(row.deleted_at_ms != null, "absent file's row must be soft-deleted")
        } finally {
            driver.close(); Files.deleteIfExists(tempDir)
        }
    }
}
