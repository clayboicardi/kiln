// Robolectric host test for AndroidFormatFactBackfill.
//
// The single load-bearing property: the worklist DRAINS. Every row that the
// backfill processes is stamped (metadata_backfilled_at_ms set) — either via
// updateTrackFormatFacts (MMR read succeeded) or markBackfilledNoMetadata
// (file unreadable / MMR threw). A row with a non-existent file path therefore
// still leaves the IS NULL worklist, so countTracksNeedingBackfill() reaches 0
// and the LIMIT loop terminates. This is the F-pattern guard against the
// TrackAnalysisRunner infinite-loop class (skipped-but-unstamped rows).
//
// Harness note: this test creates the schema via raw driver.execute DDL that
// OMITS the FTS5 virtual table. On this Windows host neither available SQLite
// engine can create the full schema: Robolectric's bundled native runtime
// (the default AndroidSqliteDriver path) is compiled WITHOUT the fts5 module
// ("no such module: fts5"), and the requery bundled-SQLite factory ships only
// Android .so jni libs (no Windows .dll), so System.loadLibrary("sqlite3x")
// throws UnsatisfiedLinkError the moment a query opens the DB. The backfill
// touches only the track/artist tables and never the FTS index, so dropping
// the one CREATE VIRTUAL TABLE statement loses no coverage and makes the test
// deterministic regardless of class execution order. (SmokeAndroidHostTest's
// requery path "passes" only because it never actually opens the DB — it
// constructs KilnDatabase lazily and asserts non-null without running a query.)

package com.clayworks.kiln.library.scan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.clayworks.kiln.data.library.db.KilnDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AndroidFormatFactBackfillTest {

    private fun newDb(): Pair<AndroidSqliteDriver, KilnDatabase> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // name=null → in-memory; default factory → Robolectric's native SQLite.
        // We do NOT call KilnDatabase.Schema.create(driver) (it would run the
        // CREATE VIRTUAL TABLE ... fts5 statement, which Robolectric's engine
        // rejects). Instead createSchemaWithoutFts() runs the table/index DDL
        // directly via the driver. AndroidSqliteDriver opens the DB lazily on
        // the first execute, so the schema DDL below is what initializes it.
        val driver = AndroidSqliteDriver(
            schema = object : app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>> {
                override val version: Long = KilnDatabase.Schema.version
                override fun create(driver: SqlDriver) =
                    app.cash.sqldelight.db.QueryResult.Unit
                override fun migrate(
                    driver: SqlDriver,
                    oldVersion: Long,
                    newVersion: Long,
                    vararg callbacks: app.cash.sqldelight.db.AfterVersion,
                ) = app.cash.sqldelight.db.QueryResult.Unit
            },
            context = context,
            name = null,
        )
        createSchemaWithoutFts(driver)
        return driver to KilnDatabase(driver)
    }

    /**
     * Creates every Kiln table + index EXCEPT the FTS5 virtual table. Mirrors
     * KilnDatabase.Schema.create() minus the one CREATE VIRTUAL TABLE statement
     * Robolectric's SQLite can't parse. Only the track/artist DDL is load-
     * bearing for this test; the rest is included so FK references resolve.
     */
    private fun createSchemaWithoutFts(driver: SqlDriver) {
        fun exec(sql: String) = driver.execute(null, sql, 0)
        exec(
            """
            CREATE TABLE artist (
                id                       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                name                     TEXT    NOT NULL,
                name_sort                TEXT    NOT NULL,
                musicbrainz_artist_id    TEXT    DEFAULT NULL
            )
            """.trimIndent(),
        )
        exec(
            """
            CREATE TABLE album (
                id                       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                artist_id                INTEGER NOT NULL REFERENCES artist(id),
                name                     TEXT    NOT NULL,
                name_sort                TEXT    NOT NULL,
                year                     INTEGER,
                date                     TEXT,
                musicbrainz_release_id   TEXT    DEFAULT NULL,
                catalog_number           TEXT    DEFAULT NULL,
                label                    TEXT    DEFAULT NULL,
                art_path                 TEXT    DEFAULT NULL,
                compilation              INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        exec(
            """
            CREATE TABLE track (
                id                       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                album_id                 INTEGER REFERENCES album(id),
                artist_id                INTEGER NOT NULL REFERENCES artist(id),
                title                    TEXT    NOT NULL,
                title_sort               TEXT    NOT NULL,
                duration_ms              INTEGER NOT NULL,
                track_number             INTEGER,
                disc_number              INTEGER,
                year                     INTEGER,
                date                     TEXT,
                genre                    TEXT,
                composer                 TEXT,
                bpm                      INTEGER,
                codec                    TEXT    NOT NULL,
                bitrate_kbps             INTEGER,
                sample_rate_hz           INTEGER NOT NULL,
                bit_depth                INTEGER,
                channels                 INTEGER NOT NULL,
                file_path                TEXT    NOT NULL UNIQUE,
                file_size_bytes          INTEGER NOT NULL,
                file_mtime_ms            INTEGER NOT NULL,
                replay_gain_track_db     REAL    DEFAULT NULL,
                replay_gain_album_db     REAL    DEFAULT NULL,
                replay_gain_track_peak   REAL    DEFAULT NULL,
                replay_gain_album_peak   REAL    DEFAULT NULL,
                has_embedded_art         INTEGER NOT NULL DEFAULT 0,
                art_path                 TEXT    DEFAULT NULL,
                source                   TEXT    NOT NULL DEFAULT 'local',
                date_added_ms            INTEGER NOT NULL,
                date_modified_ms         INTEGER NOT NULL,
                last_scanned_ms          INTEGER NOT NULL,
                deleted_at_ms            INTEGER DEFAULT NULL,
                play_count               INTEGER NOT NULL DEFAULT 0,
                skip_count               INTEGER NOT NULL DEFAULT 0,
                last_played_ms           INTEGER DEFAULT NULL,
                has_known_mtime          INTEGER NOT NULL DEFAULT 1,
                metadata_backfilled_at_ms INTEGER DEFAULT NULL
            )
            """.trimIndent(),
        )
    }

    /** Inserts a minimal live track with metadata_backfilled_at_ms = NULL. */
    private fun KilnDatabase.insertUnbackfilledTrack(filePath: String) {
        artistQueries.insert(name = "Artist", name_sort = "artist", musicbrainz_artist_id = null)
        val artistId = artistQueries.lastInsertRowId().executeAsOne()
        trackQueries.insert(
            album_id = null,
            artist_id = artistId,
            title = "Title",
            title_sort = "title",
            duration_ms = 180_000L,
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
            file_path = filePath,
            file_size_bytes = 4_096L,
            file_mtime_ms = 1_700_000_000_000L,
            has_known_mtime = 1L,
            replay_gain_track_db = null,
            replay_gain_album_db = null,
            replay_gain_track_peak = null,
            replay_gain_album_peak = null,
            has_embedded_art = 0L,
            art_path = null,
            source = "local",
            date_added_ms = 1_700_000_000_000L,
            date_modified_ms = 1_700_000_000_000L,
            last_scanned_ms = 1_700_000_000_000L,
        )
        // metadata_backfilled_at_ms is NULL by table DEFAULT — no insert column for it.
    }

    @Test
    fun backfill_marks_unreadable_row_and_worklist_drains() {
        val (driver, db) = newDb()
        try {
            db.insertUnbackfilledTrack(filePath = "/nonexistent.flac")
            // Sanity: the worklist starts with exactly one pending row.
            assertEquals(1L, db.trackQueries.countTracksNeedingBackfill().executeAsOne())

            val backfill = AndroidFormatFactBackfill(
                context = ApplicationProvider.getApplicationContext(),
                db = db,
                ioDispatcher = Dispatchers.Unconfined,
            )
            val updated = runBlocking { backfill.runOnce() }

            // The unreadable file can't be opened by MediaExtractor/MMR, so the
            // row is stamped via markBackfilledNoMetadata — NOT updateTrackFormatFacts.
            // updated == 0 proves the row was honestly marked no-metadata, not
            // falsely "corrected" with fabricated facts.
            assertEquals(0, updated)
            // …and it drops out of the IS NULL worklist, so the loop drains.
            assertEquals(0L, db.trackQueries.countTracksNeedingBackfill().executeAsOne())
        } finally {
            driver.close()
        }
    }

    @Test
    fun backfill_is_idempotent_second_run_skips_stamped_row() {
        val (driver, db) = newDb()
        try {
            db.insertUnbackfilledTrack(filePath = "/nonexistent.flac")
            val backfill = AndroidFormatFactBackfill(
                context = ApplicationProvider.getApplicationContext(),
                db = db,
                ioDispatcher = Dispatchers.Unconfined,
            )

            // First run stamps the (unreadable) row out of the worklist.
            runBlocking { backfill.runOnce() }
            assertEquals(0L, db.trackQueries.countTracksNeedingBackfill().executeAsOne())
            val afterFirst = db.trackQueries.selectByFilePath("/nonexistent.flac").executeAsOne()

            // Second run must do nothing — the metadata_backfilled_at_ms IS NULL
            // worklist is empty, so runOnce() returns 0 without touching the row.
            val secondCount = runBlocking { backfill.runOnce() }
            assertEquals(0, secondCount)
            assertEquals(0L, db.trackQueries.countTracksNeedingBackfill().executeAsOne())

            val afterSecond = db.trackQueries.selectByFilePath("/nonexistent.flac").executeAsOne()
            assertEquals(afterFirst, afterSecond)
        } finally {
            driver.close()
        }
    }
}
