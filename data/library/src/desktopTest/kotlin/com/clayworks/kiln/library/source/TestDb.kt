// Test fixture: in-memory KilnDatabase via JdbcSqliteDriver. Used by
// LocalLibrarySourceTest (review P1-6). Each test gets a clean DB.
//
// Location note: plan §6.1 sketches this under commonTest/, but
// JdbcSqliteDriver lives in `app.cash.sqldelight:sqlite-driver` which is
// JVM-only and wired into `desktopMain`/`desktopTest` source sets (see
// :data:library/build.gradle.kts). Hosting it in commonTest would fail
// to resolve the import — the existing JvmFilesystemScannerTest follows
// the same desktopTest convention. The upcoming LocalLibrarySourceTest
// will join it here for the same reason.
//
// Mirrors the deterministic-timestamp idiom from the plan (nowMs = 1.7e12)
// so seeded rows have stable values when test assertions read them back.

package com.clayworks.kiln.library.source

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.clayworks.kiln.data.library.db.KilnDatabase

/**
 * In-memory KilnDatabase wrapper with typed insert helpers. Each test
 * should `use { db -> ... }` for guaranteed close.
 *
 * Inserts use `lastInsertRowId()` (defined per-table in the .sq files) to
 * return the new row id — SQLite scopes `last_insert_rowid()` to the
 * connection, and JdbcSqliteDriver(IN_MEMORY) is a single in-process
 * connection, so the id is correct without a wrapping transaction.
 */
class TestDb : AutoCloseable {
    val driver: SqlDriver = JdbcSqliteDriver(
        url = JdbcSqliteDriver.IN_MEMORY,
        schema = KilnDatabase.Schema,   // SQLDelight 2.x auto-creates on connect
    )
    val db: KilnDatabase = KilnDatabase(driver)

    override fun close() = driver.close()

    // ---------- artist ----------

    fun insertArtist(
        name: String,
        sortName: String = name.lowercase(),
        musicbrainzId: String? = null,
    ): Long {
        db.artistQueries.insert(
            name = name,
            name_sort = sortName,
            musicbrainz_artist_id = musicbrainzId,
        )
        return db.artistQueries.lastInsertRowId().executeAsOne()
    }

    // ---------- album ----------

    fun insertAlbum(
        artistId: Long,
        name: String,
        sortName: String = name.lowercase(),
        year: Long? = null,
        compilation: Boolean = false,
    ): Long {
        db.albumQueries.insert(
            artist_id = artistId,
            name = name,
            name_sort = sortName,
            year = year,
            date = null,
            musicbrainz_release_id = null,
            catalog_number = null,
            label = null,
            art_path = null,
            compilation = if (compilation) 1L else 0L,
        )
        return db.albumQueries.lastInsertRowId().executeAsOne()
    }

    // ---------- track ----------

    /**
     * Inserts a "live" track row (deleted_at_ms NULL) with sane FLAC defaults.
     * Timestamps deterministic so tests can assert stable equality.
     */
    fun insertTrack(
        artistId: Long,
        albumId: Long? = null,
        title: String,
        sortTitle: String = title.lowercase(),
        durationMs: Long = 180_000L,
        trackNumber: Long? = null,
        discNumber: Long? = null,
        year: Long? = null,
        genre: String? = null,
        codec: String = "FLAC",
        sampleRateHz: Long = 44_100L,
        bitDepth: Long? = 16L,
        channels: Long = 2L,
        filePath: String = "/test/${title.lowercase()}.flac",
        fileSizeBytes: Long = 4_096L,
        fileMtimeMs: Long = NOW_MS,
        hasEmbeddedArt: Boolean = false,
        artPath: String? = null,
        playCount: Long = 0L,
        lastPlayedMs: Long? = null,
        dateAddedMs: Long = NOW_MS,
        replayGainTrackDb: Double? = null,
        replayGainTrackPeak: Double? = null,
    ): Long {
        db.trackQueries.insert(
            album_id = albumId,
            artist_id = artistId,
            title = title,
            title_sort = sortTitle,
            duration_ms = durationMs,
            track_number = trackNumber,
            disc_number = discNumber,
            year = year,
            date = null,
            genre = genre,
            composer = null,
            bpm = null,
            codec = codec,
            bitrate_kbps = null,
            sample_rate_hz = sampleRateHz,
            bit_depth = bitDepth,
            channels = channels,
            file_path = filePath,
            file_size_bytes = fileSizeBytes,
            file_mtime_ms = fileMtimeMs,
            replay_gain_track_db = replayGainTrackDb,
            replay_gain_album_db = null,
            replay_gain_track_peak = replayGainTrackPeak,
            replay_gain_album_peak = null,
            has_embedded_art = if (hasEmbeddedArt) 1L else 0L,
            art_path = artPath,
            source = "local",
            date_added_ms = dateAddedMs,
            date_modified_ms = dateAddedMs,
            last_scanned_ms = dateAddedMs,
        )
        val id = db.trackQueries.lastInsertRowId().executeAsOne()
        // Apply lazy-stats overrides via the deterministic setPlayStats query.
        // The insert statement intentionally hard-codes play_count = 0 /
        // last_played_ms = NULL (table DEFAULTs); call setPlayStats only when
        // the caller deviated from those defaults. This avoids markPlayed's
        // increment-by-1 semantics (which would silently set play_count to 1
        // when the caller passed 0 with a non-null lastPlayedMs).
        if (playCount > 0L || lastPlayedMs != null) {
            db.trackQueries.setPlayStats(
                playCount = playCount,
                lastPlayedMs = lastPlayedMs,
                id = id,
            )
        }
        return id
    }

    // ---------- playlist ----------

    fun insertPlaylist(
        name: String,
        description: String? = null,
        sortOrder: String = "manual",
        dateCreatedMs: Long = NOW_MS,
    ): Long {
        db.playlistQueries.insert(
            name = name,
            description = description,
            date_created_ms = dateCreatedMs,
            date_modified_ms = dateCreatedMs,
            sort_order = sortOrder,
        )
        return db.playlistQueries.lastInsertRowId().executeAsOne()
    }

    /**
     * Append a track to the end of a playlist (position = maxPosition + 1).
     * Returns Unit — playlist_track has no own primary-key sequence to expose.
     */
    fun insertPlaylistTrack(
        playlistId: Long,
        trackId: Long,
        position: Long? = null,
        dateAddedMs: Long = NOW_MS,
    ) {
        val resolvedPosition = position
            ?: (db.playlist_trackQueries.maxPositionInPlaylist(playlistId).executeAsOne() + 1L)
        db.playlist_trackQueries.insert(
            playlist_id = playlistId,
            track_id = trackId,
            position = resolvedPosition,
            date_added_ms = dateAddedMs,
        )
    }

    companion object {
        /**
         * Deterministic "now" for seeded rows. 2023-11-14T22:13:20Z. Use this
         * (or a deliberately-different offset) when tests need to assert
         * specific timestamp values. Matches the plan's nowMs convention.
         */
        const val NOW_MS: Long = 1_700_000_000_000L
    }
}
