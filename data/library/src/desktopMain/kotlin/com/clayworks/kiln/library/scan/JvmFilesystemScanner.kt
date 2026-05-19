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
import kotlinx.coroutines.CoroutineDispatcher
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
    private val scanFolders: List<Path>,
    private val db: KilnDatabase,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : LibraryScanner {

    init {
        // jaudiotagger spews at java.util.logging INFO by default — silence it.
        java.util.logging.Logger.getLogger("org.jaudiotagger").level = Level.WARNING
    }

    override suspend fun scanIncremental(): Either<ScanError, ScanResult> =
        withContext(ioDispatcher) { runScan(forceFullRescan = false) }

    override suspend fun scanFull(): Either<ScanError, ScanResult> =
        withContext(ioDispatcher) { runScan(forceFullRescan = true) }

    private fun runScan(forceFullRescan: Boolean): Either<ScanError, ScanResult> =
        Either.catch {
            val scanStartedMs = System.currentTimeMillis()
            var added = 0
            var updated = 0
            var unchanged = 0
            var parseErrors = 0

            if (forceFullRescan) {
                // Reset every row's last_scanned_ms; the per-file loop will then
                // re-touch / re-upsert as if everything were new, and the post-loop
                // softDelete sweep will catch any rows whose underlying files are
                // gone. Run via raw driver — single bulk UPDATE; no per-row work.
                driver.execute(
                    identifier = null,
                    sql = "UPDATE track SET last_scanned_ms = 0",
                    parameters = 0,
                )
            }

            val files = discoverAudioFiles()
            log.i { "discovered ${files.size} candidate file(s) across ${scanFolders.size} folder(s)" }

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

            val softDeleteCount = db.trackQueries.countUnscanned(scanStartedMs)
                .executeAsOne().toInt()
            if (softDeleteCount > 0) {
                db.trackQueries.softDeleteUnscanned(
                    deletedAtMs = scanStartedMs,
                    scanStartedMs = scanStartedMs,
                )
            }

            rebuildFtsIndex()

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

    private fun discoverAudioFiles(): List<Path> = scanFolders.flatMap { root ->
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
        val mtime = Files.getLastModifiedTime(path).toMillis()
        val size = Files.size(path)

        val existing = db.trackQueries.selectByFilePath(pathStr).executeAsOneOrNull()

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
            return Outcome.ParseFailed(e)
        }

        db.transaction {
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

    /**
     * Bulk-rebuild the FTS5 contentless index from the live track table.
     * The 'delete-all' control command can't be expressed in .sq files
     * (SQLDelight's parser rejects it); raw driver.execute is the documented
     * escape hatch per Session 6 discovery #2.
     */
    private fun rebuildFtsIndex() {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO track_search(track_search) VALUES('delete-all')",
            parameters = 0,
        )
        val rows = db.trackQueries.selectAllForFtsRebuild().executeAsList()
        db.transaction {
            rows.forEach { row ->
                db.track_searchQueries.insertSearchIndex(
                    rowid = row.track_id,
                    title = row.title,
                    album_name = row.album_name,
                    artist_name = row.artist_name,
                    album_artist_name = row.album_artist_name,
                )
            }
        }
        log.i { "rebuilt FTS5 index with ${rows.size} row(s)" }
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

// ---------- helpers (file-scope so they're testable without scanner state) ----------

internal fun toSortName(name: String): String {
    val trimmed = name.trim()
    val withoutArticle = when {
        trimmed.startsWith("The ", ignoreCase = true) -> trimmed.substring(4)
        trimmed.startsWith("An ", ignoreCase = true) -> trimmed.substring(3)
        trimmed.startsWith("A ", ignoreCase = true) -> trimmed.substring(2)
        else -> trimmed
    }
    return withoutArticle.lowercase()
}

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

internal fun parseChannels(channels: String?): Long = when {
    channels == null -> 2L
    channels.equals("Mono", ignoreCase = true) -> 1L
    channels.equals("Stereo", ignoreCase = true) -> 2L
    channels.contains("5.1") -> 6L
    channels.contains("7.1") -> 8L
    else -> channels.trim().toLongOrNull() ?: 2L
}

internal fun String.parseLeadingLong(): Long? =
    takeIf { it.isNotBlank() }?.substringBefore('/')?.trim()?.toLongOrNull()

internal fun String.parseReplayGainDb(): Double? =
    takeIf { it.isNotBlank() }
        ?.replace("dB", "", ignoreCase = true)
        ?.trim()
        ?.toDoubleOrNull()

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
