// JvmFilesystemScanner — desktop LibraryScanner implementation. Walks the
// configured scan folders, reads metadata via jaudiotagger, upserts into
// the SQLDelight tables. Per scaffold prep §6.3 + Session 7 handoff H2.
//
// FTS5 strategy: per-row maintenance is skipped during the walk; the index
// is rebuilt in one pass at scan end (see [rebuildFtsIndex]). For a 40k
// library the rebuild is ~5s and avoids the LEFT-JOIN + old-value bookkeeping
// otherwise required by SQLite's contentless-FTS5 delete syntax.

package com.clayworks.kiln.library.scan

import app.cash.sqldelight.db.SqlDriver
import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.scan.internal.parseChannels
import com.clayworks.kiln.library.scan.internal.parseLeadingLong
import com.clayworks.kiln.library.scan.internal.parseReplayGainDb
import com.clayworks.kiln.library.scan.internal.rebuildFtsIndex
import com.clayworks.kiln.library.scan.internal.toSortName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

private val log = Logger.withTag("JvmFilesystemScanner")

private val AUDIO_EXTENSIONS = setOf("flac", "wav", "mp3", "alac", "ogg", "opus", "m4a")

class JvmFilesystemScanner(
    private val scanFoldersFlow: Flow<List<Path>>,
    private val db: KilnDatabase,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
    private val writeLock: LibraryWriteLock,
) : LibraryScanner {

    init {
        // jaudiotagger spews at java.util.logging INFO by default — silence it.
        java.util.logging.Logger.getLogger("org.jaudiotagger").level = Level.WARNING
    }

    // Held for the whole scan via the shared LibraryWriteLock — serializes
    // scan-vs-scan AND scan-vs-analyzer over the single SQLite connection.
    override suspend fun scanIncremental(): Either<ScanError, ScanResult> =
        withContext(ioDispatcher) { writeLock.mutex.withLock { runScan(forceFullRescan = false) } }

    override suspend fun scanFull(): Either<ScanError, ScanResult> =
        withContext(ioDispatcher) { writeLock.mutex.withLock { runScan(forceFullRescan = true) } }

    private suspend fun runScan(forceFullRescan: Boolean): Either<ScanError, ScanResult> =
        Either.catch {
            val scanFolders = scanFoldersFlow.first()
            val scanStartedMs = System.currentTimeMillis()

            // GUARD: empty scanFolders is a data-loss bomb. Without this, the
            // body below would (a) optionally bulk-reset every track's
            // last_scanned_ms = 0 on forceFullRescan, (b) walk zero files →
            // touch nothing, (c) softDeleteUnscanned(scanStartedMs) marks
            // EVERY remaining live row as deleted because nothing was touched
            // back up to scanStartedMs. Result: first-run-with-defaults or
            // misconfig soft-deletes the entire library. Bail early instead.
            if (scanFolders.isEmpty()) {
                log.w {
                    "scanIncremental/scanFull called with empty scanFolders; " +
                        "skipping to avoid soft-deleting all tracks"
                }
                return@catch ScanResult(
                    tracksAdded = 0,
                    tracksUpdated = 0,
                    tracksSoftDeleted = 0,
                    tracksUnchanged = 0,
                    durationMs = 0L,
                )
            }

            var added = 0
            var updated = 0
            var unchanged = 0
            var parseErrors = 0

            if (forceFullRescan) {
                // Reset every row's last_scanned_ms; the per-file loop will then
                // re-touch / re-upsert as if everything were new, and the post-loop
                // softDelete sweep will catch any rows whose underlying files are
                // gone. Run via raw driver — single bulk UPDATE; no per-row work.
                //
                // INTENTIONAL: this UPDATE runs OUTSIDE the loop transaction below.
                // A mid-scan crash leaves last_scanned_ms = 0 for all rows; the
                // next scan's softDeleteUnscanned(scanStartedMs) only soft-deletes
                // rows that the NEW scan loop did not touch — so the library is
                // recoverable. /ultrareview flagged this in Session 10 and the
                // refute is in docs/sessions/2026-05-19-session-10-addendum-re-review-fixes.md.
                driver.execute(
                    identifier = null,
                    sql = "UPDATE track SET last_scanned_ms = 0",
                    parameters = 0,
                )
            }

            val files = discoverAudioFiles(scanFolders)
            log.i { "discovered ${files.size} candidate file(s) across ${scanFolders.size} folder(s)" }

            // ONE transaction wrapping the whole scan loop (Session 10 Gemini G2).
            // Per-file transactions cost one disk sync each, so the original
            // code issued ~40k syncs for Clay's library and blew the 5-min
            // perf budget. SQLite handles a single long transaction over tens
            // of thousands of inserts comfortably; the trade-off is that a
            // mid-scan crash rolls back the whole pass (chunked batching is
            // a Phase 2a follow-up if real-world scans hit issues).
            db.transaction {
                for (file in files) {
                    when (val outcome = scanOneFile(file, scanStartedMs)) {
                        Outcome.Added -> added++
                        Outcome.Updated -> updated++
                        Outcome.Unchanged -> unchanged++
                        is Outcome.ParseFailed -> {
                            parseErrors++
                            log.w(outcome.error) { "skipped ${file.name}: metadata parse failed" }
                        }
                    }
                }
            }

            // Soft-delete reconciliation, but ONLY when every configured root was
            // accessible this pass. If a root is inaccessible (unmounted external
            // drive, missing D:\tiddl), its files weren't walked, so reconciling
            // would soft-delete that root's entire library — catastrophic now that
            // scan-on-launch fires automatically. Refuse to reconcile against a
            // partial view; accessible roots still get their adds/updates. (codex #4)
            val inaccessibleRoots = scanFolders.filter { !Files.exists(it) }
            val softDeleteCount = if (inaccessibleRoots.isEmpty()) {
                val count = db.trackQueries.countUnscanned(scanStartedMs).executeAsOne().toInt()
                if (count > 0) {
                    db.trackQueries.softDeleteUnscanned(
                        deletedAtMs = scanStartedMs,
                        scanStartedMs = scanStartedMs,
                    )
                }
                count
            } else {
                log.w {
                    "skipping soft-delete: ${inaccessibleRoots.size} configured root(s) inaccessible " +
                        "($inaccessibleRoots) — refusing to reconcile against a partial view"
                }
                0
            }

            rebuildFtsIndex(db, driver)

            val durationMs = System.currentTimeMillis() - scanStartedMs
            ScanResult(
                tracksAdded = added,
                tracksUpdated = updated,
                tracksSoftDeleted = softDeleteCount,
                tracksUnchanged = unchanged,
                durationMs = durationMs,
            ).also {
                log.i {
                    "scan complete: +$added ~$updated -$softDeleteCount ·$unchanged " +
                        "($parseErrors parse errors) in ${durationMs}ms"
                }
            }
        }.mapLeft(::classifyScanFailure)

    private fun classifyScanFailure(e: Throwable): ScanError = when (e) {
        is SecurityException -> ScanError.PermissionDenied(e.message ?: "Filesystem access denied")
        else -> ScanError.IoError(e)
    }

    private fun discoverAudioFiles(scanFolders: List<Path>): List<Path> = scanFolders.flatMap { root ->
        if (!Files.exists(root)) {
            log.w { "scan folder does not exist: $root" }
            return@flatMap emptyList<Path>()
        }
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { it.extension.lowercase() in AUDIO_EXTENSIONS }
                .toList()
        }
    }

    private fun scanOneFile(path: Path, scanStartedMs: Long): Outcome {
        val pathStr = path.toString()
        // Existing-row lookup hoisted above the stat so the failure paths below can
        // mark an already-tracked file as "seen" (item 1, #31).
        val existing = db.trackQueries.selectByFilePath(pathStr).executeAsOneOrNull()

        // Single Files.readAttributes call instead of separate getLastModifiedTime + size
        // — halves the syscalls per file (Gemini G5). For 40k-file libraries that's
        // 40k fewer kernel transitions on the fast-path "unchanged file" check.
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

        // Caller (runScan) holds the enclosing db.transaction — see the comment
        // above the loop in runScan for the per-file-transaction → single-transaction
        // refactor rationale.
        run {
            val artistId = upsertArtist(tags.artist, tags.artistSort, tags.musicbrainzArtistId)
            val albumArtistId = tags.albumArtist?.let { albumArtist ->
                upsertArtist(
                    albumArtist,
                    tags.albumArtistSort ?: toSortName(albumArtist),
                    tags.musicbrainzAlbumArtistId,
                )
            } ?: artistId
            val albumId = tags.album?.let { albumName ->
                upsertAlbum(
                    albumArtistId = albumArtistId,
                    albumName = albumName,
                    albumNameSort = tags.albumSort ?: toSortName(albumName),
                    year = tags.year,
                    date = tags.date,
                    musicbrainzReleaseId = tags.musicbrainzReleaseId,
                )
            }

            if (existing == null) {
                db.trackQueries.insert(
                    album_id = albumId,
                    artist_id = artistId,
                    title = tags.title,
                    title_sort = tags.titleSort,
                    duration_ms = tags.durationMs,
                    track_number = tags.trackNumber,
                    disc_number = tags.discNumber,
                    year = tags.year,
                    date = tags.date,
                    genre = tags.genre,
                    composer = tags.composer,
                    bpm = tags.bpm,
                    codec = tags.codec,
                    bitrate_kbps = tags.bitrateKbps,
                    sample_rate_hz = tags.sampleRateHz,
                    bit_depth = tags.bitDepth,
                    channels = tags.channels,
                    file_path = pathStr,
                    file_size_bytes = size,
                    file_mtime_ms = mtime,
                    // JVM Files.readAttributes() always returns a real mtime — no
                    // SAF-style "provider omits column" failure mode exists here.
                    has_known_mtime = 1L,
                    replay_gain_track_db = tags.replayGainTrackDb,
                    replay_gain_album_db = tags.replayGainAlbumDb,
                    replay_gain_track_peak = tags.replayGainTrackPeak,
                    replay_gain_album_peak = tags.replayGainAlbumPeak,
                    has_embedded_art = if (tags.hasEmbeddedArt) 1L else 0L,
                    art_path = null,
                    source = "local",
                    date_added_ms = scanStartedMs,
                    date_modified_ms = scanStartedMs,
                    last_scanned_ms = scanStartedMs,
                )
            } else {
                db.trackQueries.updateForRescan(
                    album_id = albumId,
                    artist_id = artistId,
                    title = tags.title,
                    title_sort = tags.titleSort,
                    duration_ms = tags.durationMs,
                    track_number = tags.trackNumber,
                    disc_number = tags.discNumber,
                    year = tags.year,
                    date = tags.date,
                    genre = tags.genre,
                    composer = tags.composer,
                    bpm = tags.bpm,
                    codec = tags.codec,
                    bitrate_kbps = tags.bitrateKbps,
                    sample_rate_hz = tags.sampleRateHz,
                    bit_depth = tags.bitDepth,
                    channels = tags.channels,
                    file_size_bytes = size,
                    file_mtime_ms = mtime,
                    has_known_mtime = 1L,
                    replay_gain_track_db = tags.replayGainTrackDb,
                    replay_gain_album_db = tags.replayGainAlbumDb,
                    replay_gain_track_peak = tags.replayGainTrackPeak,
                    replay_gain_album_peak = tags.replayGainAlbumPeak,
                    has_embedded_art = if (tags.hasEmbeddedArt) 1L else 0L,
                    art_path = null,
                    modifiedAtMs = scanStartedMs,
                    scannedAtMs = scanStartedMs,
                    filePath = pathStr,
                )
            }
        }

        return if (existing == null) Outcome.Added else Outcome.Updated
    }

    /** Look up an artist by (name_sort, mbid). Insert if absent. Caller MUST be in a db.transaction. */
    private fun upsertArtist(name: String, nameSort: String, mbid: String?): Long {
        val existing = db.artistQueries.selectByName(nameSort, mbid).executeAsOneOrNull()
        if (existing != null) return existing.id
        db.artistQueries.insert(name = name, name_sort = nameSort, musicbrainz_artist_id = mbid)
        return db.artistQueries.lastInsertRowId().executeAsOne()
    }

    /** Look up an album by (artist_id, name_sort). Insert if absent. Caller MUST be in a db.transaction. */
    private fun upsertAlbum(
        albumArtistId: Long,
        albumName: String,
        albumNameSort: String,
        year: Long?,
        date: String?,
        musicbrainzReleaseId: String?,
    ): Long {
        val existing = db.albumQueries.selectByArtistAndName(albumArtistId, albumNameSort)
            .executeAsOneOrNull()
        if (existing != null) return existing.id
        db.albumQueries.insert(
            artist_id = albumArtistId,
            name = albumName,
            name_sort = albumNameSort,
            year = year,
            date = date,
            musicbrainz_release_id = musicbrainzReleaseId,
            catalog_number = null,
            label = null,
            art_path = null,
            compilation = 0L,
        )
        return db.albumQueries.lastInsertRowId().executeAsOne()
    }

    private fun readTags(path: Path): TrackTags {
        val audioFile = AudioFileIO.read(path.toFile())
        val tag: Tag? = audioFile.tag
        val header = audioFile.audioHeader

        val title = tag?.getFirstOrNull(FieldKey.TITLE) ?: path.nameWithoutExtension
        val titleSort = tag?.getFirstOrNull(FieldKey.TITLE_SORT) ?: toSortName(title)

        val artist = tag?.getFirstOrNull(FieldKey.ARTIST) ?: "Unknown Artist"
        val artistSort = tag?.getFirstOrNull(FieldKey.ARTIST_SORT) ?: toSortName(artist)

        val albumArtist = tag?.getFirstOrNull(FieldKey.ALBUM_ARTIST)
        val albumArtistSort = tag?.getFirstOrNull(FieldKey.ALBUM_ARTIST_SORT)

        val album = tag?.getFirstOrNull(FieldKey.ALBUM)
        val albumSort = tag?.getFirstOrNull(FieldKey.ALBUM_SORT)

        val durationMs = (header.trackLength.toLong() * 1000L).coerceAtLeast(0L)
        val sampleRateHz = header.sampleRateAsNumber.toLong().coerceAtLeast(0L)
        val bitDepth = header.bitsPerSample.let { if (it > 0) it.toLong() else null }
        val channels = parseChannels(header.channels)
        // header.bitRateAsNumber returns long already; runCatching guards files that
        // throw NumberFormatException from older jaudiotagger code paths.
        val bitrateKbps = runCatching { header.bitRateAsNumber }.getOrNull()

        return TrackTags(
            title = title,
            titleSort = titleSort,
            artist = artist,
            artistSort = artistSort,
            musicbrainzArtistId = tag?.getFirstOrNull(FieldKey.MUSICBRAINZ_ARTISTID),
            albumArtist = albumArtist,
            albumArtistSort = albumArtistSort,
            musicbrainzAlbumArtistId = tag?.getFirstOrNull(FieldKey.MUSICBRAINZ_RELEASEARTISTID),
            album = album,
            albumSort = albumSort,
            musicbrainzReleaseId = tag?.getFirstOrNull(FieldKey.MUSICBRAINZ_RELEASEID),
            durationMs = durationMs,
            trackNumber = tag?.getFirstOrNull(FieldKey.TRACK)?.parseLeadingLong(),
            discNumber = tag?.getFirstOrNull(FieldKey.DISC_NO)?.parseLeadingLong(),
            year = tag?.getFirstOrNull(FieldKey.YEAR)?.parseLeadingLong(),
            date = tag?.getFirstOrNull(FieldKey.YEAR),  // ISO date if present; same source as year for now
            genre = tag?.getFirstOrNull(FieldKey.GENRE),
            composer = tag?.getFirstOrNull(FieldKey.COMPOSER),
            bpm = tag?.getFirstOrNull(FieldKey.BPM)?.parseLeadingLong(),
            codec = detectCodec(path),
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            channels = channels,
            replayGainTrackDb = tag?.getFreeFormOrNull("REPLAYGAIN_TRACK_GAIN")?.parseReplayGainDb(),
            replayGainAlbumDb = tag?.getFreeFormOrNull("REPLAYGAIN_ALBUM_GAIN")?.parseReplayGainDb(),
            replayGainTrackPeak = tag?.getFreeFormOrNull("REPLAYGAIN_TRACK_PEAK")?.toDoubleOrNull(),
            replayGainAlbumPeak = tag?.getFreeFormOrNull("REPLAYGAIN_ALBUM_PEAK")?.toDoubleOrNull(),
            hasEmbeddedArt = tag?.firstArtwork != null,
        )
    }
}

// ---------- desktopMain-only helpers ----------

/** Detect codec from file extension (jaudiotagger's format string is less reliable). */
internal fun detectCodec(path: Path): String = when (path.extension.lowercase()) {
    "flac" -> "FLAC"
    "wav" -> "WAV"
    "mp3" -> "MP3"
    "alac" -> "ALAC"
    "m4a" -> "AAC"  // .m4a is overwhelmingly AAC; ALAC.m4a is rare and miscategorized at MVP
    "ogg" -> "OGG_VORBIS"
    "opus" -> "OGG_OPUS"
    else -> "UNKNOWN"
}

/** Tag.getFirst returning the empty string for absent fields is annoying — normalize to null. */
private fun Tag.getFirstOrNull(key: FieldKey): String? =
    runCatching { getFirst(key) }.getOrNull()?.takeIf { it.isNotBlank() }

private fun Tag.getFreeFormOrNull(key: String): String? =
    runCatching { getFirst(key) }.getOrNull()?.takeIf { it.isNotBlank() }

// ---------- internal models ----------

private data class TrackTags(
    val title: String,
    val titleSort: String,
    val artist: String,
    val artistSort: String,
    val musicbrainzArtistId: String?,
    val albumArtist: String?,
    val albumArtistSort: String?,
    val musicbrainzAlbumArtistId: String?,
    val album: String?,
    val albumSort: String?,
    val musicbrainzReleaseId: String?,
    val durationMs: Long,
    val trackNumber: Long?,
    val discNumber: Long?,
    val year: Long?,
    val date: String?,
    val genre: String?,
    val composer: String?,
    val bpm: Long?,
    val codec: String,
    val bitrateKbps: Long?,
    val sampleRateHz: Long,
    val bitDepth: Long?,
    val channels: Long,
    val replayGainTrackDb: Double?,
    val replayGainAlbumDb: Double?,
    val replayGainTrackPeak: Double?,
    val replayGainAlbumPeak: Double?,
    val hasEmbeddedArt: Boolean,
)

private sealed interface Outcome {
    data object Added : Outcome
    data object Updated : Outcome
    data object Unchanged : Outcome
    data class ParseFailed(val error: Throwable) : Outcome
}
