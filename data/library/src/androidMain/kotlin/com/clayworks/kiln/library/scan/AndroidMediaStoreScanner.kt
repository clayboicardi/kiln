// AndroidMediaStoreScanner — Android LibraryScanner implementation. Queries
// MediaStore.Audio.Media for all IS_MUSIC entries, upserts into the SQLDelight
// tables. Per scaffold prep §6.2 + Session 7 handoff H2.
//
// MediaStore gives us the basics (title/artist/album/duration/bitrate/path/
// mtime/size/mime) for free across all Android versions. It does NOT give us
// sample rate / bit depth / channel count / ReplayGain — those default to
// placeholder values at scan time (44.1kHz / null / 2). The player reads true
// format on file open; a per-file MediaMetadataRetriever pass to refine these
// values is Phase 2a polish work.
//
// API 30+ additions (ALBUM_ARTIST, GENRE) read conditionally — missing on
// older devices, we degrade gracefully to ARTIST + null.

package com.clayworks.kiln.library.scan

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.cash.sqldelight.db.SqlDriver
import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.scan.internal.SafTagReader
import com.clayworks.kiln.library.scan.internal.SafTreeWalker
import com.clayworks.kiln.library.scan.internal.rebuildFtsIndex
import com.clayworks.kiln.library.scan.internal.toSortName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val log = Logger.withTag("AndroidMediaStoreScanner")

private const val DEFAULT_SAMPLE_RATE_HZ = 44100L
private const val DEFAULT_CHANNELS = 2L

private const val UNKNOWN_PLACEHOLDER = "<unknown>"  // MediaStore literal

class AndroidMediaStoreScanner(
    private val context: Context,
    private val safTreeUrisFlow: Flow<List<String>>,
    private val db: KilnDatabase,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : LibraryScanner {

    override suspend fun scanIncremental(): Either<ScanError, ScanResult> =
        withContext(ioDispatcher) { runScan(forceFullRescan = false) }

    override suspend fun scanFull(): Either<ScanError, ScanResult> =
        withContext(ioDispatcher) { runScan(forceFullRescan = true) }

    private suspend fun runScan(forceFullRescan: Boolean): Either<ScanError, ScanResult> =
        Either.catch {
            val scanStartedMs = System.currentTimeMillis()
            val safTreeUris = safTreeUrisFlow.first()
            var added = 0
            var updated = 0
            var unchanged = 0
            var parseErrors = 0

            if (forceFullRescan) {
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

            val cursor = queryAudioMediaCursor()
                ?: throw IllegalStateException("MediaStore.Audio.Media query returned null cursor")

            cursor.use { c ->
                val cols = MediaCols.from(c)
                // ONE transaction wrapping the whole scan loop (Session 10 Gemini G1).
                // Per-track transactions cost one disk sync each, so the
                // original code issued one sync per MediaStore row and blew
                // the 5-min perf budget on libraries with 40k+ tracks.
                // SQLite handles a single long transaction over tens of
                // thousands of inserts comfortably; the trade-off is that a
                // mid-scan crash rolls back the whole pass (chunked batching
                // is a Phase 2a follow-up if real-world scans hit issues).
                db.transaction {
                    while (c.moveToNext()) {
                        when (val outcome = scanOneTrack(c, cols, scanStartedMs)) {
                            Outcome.Added -> added++
                            Outcome.Updated -> updated++
                            Outcome.Unchanged -> unchanged++
                            is Outcome.ParseFailed -> {
                                parseErrors++
                                log.w(outcome.error) { "skipped media id ${outcome.mediaId}: ${outcome.error.message}" }
                            }
                        }
                    }
                }
            }

            // Phase 2a Track B: walk SAF-picked tree URIs from settings.scanFolders.
            // These augment the system-wide MediaStore pass above — Android users
            // who picked a folder via the SAF picker want files MediaStore doesn't
            // know about (Downloads, sideloaded SD, etc.). Same upsert + soft-delete
            // semantics; counts accumulate.
            val (safAdded, safUpdated, safParseErrors) = scanSafTrees(safTreeUris, scanStartedMs)
            added += safAdded
            updated += safUpdated
            parseErrors += safParseErrors

            val softDeleteCount = db.trackQueries.countUnscanned(scanStartedMs)
                .executeAsOne().toInt()
            if (softDeleteCount > 0) {
                db.trackQueries.softDeleteUnscanned(
                    deletedAtMs = scanStartedMs,
                    scanStartedMs = scanStartedMs,
                )
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
        is SecurityException -> ScanError.PermissionDenied(
            e.message ?: "READ_MEDIA_AUDIO (or READ_EXTERNAL_STORAGE) not granted",
        )
        else -> ScanError.IoError(e)
    }

    private fun queryAudioMediaCursor(): Cursor? = context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        MediaCols.projection(),
        "${MediaStore.Audio.Media.IS_MUSIC} != 0",
        /* selectionArgs = */ null,
        /* sortOrder = */ null,
    )

    private fun scanOneTrack(cursor: Cursor, cols: MediaCols, scanStartedMs: Long): Outcome {
        val mediaId = cursor.getLong(cols.id)
        val data = if (cols.data >= 0) cursor.getStringOrNull(cols.data) else null
        val filePath = data?.takeIf { it.isNotBlank() }
            ?: "content://media/external/audio/media/$mediaId"

        // MediaStore reports DATE_MODIFIED in seconds-since-epoch; convert to ms.
        val mtimeSeconds = if (cols.dateModified >= 0) cursor.getLong(cols.dateModified) else 0L
        val mtime = if (mtimeSeconds > 0) mtimeSeconds * 1000L else scanStartedMs
        val size = if (cols.size >= 0) cursor.getLong(cols.size).coerceAtLeast(0L) else 0L

        val existing = db.trackQueries.selectByFilePath(filePath).executeAsOneOrNull()
        if (existing != null &&
            existing.file_mtime_ms == mtime &&
            existing.file_size_bytes == size &&
            existing.deleted_at_ms == null
        ) {
            db.trackQueries.touchLastScanned(scannedAtMs = scanStartedMs, filePath = filePath)
            return Outcome.Unchanged
        }

        val tags = try {
            readTagsFromCursor(cursor, cols, filePath)
        } catch (e: Throwable) {
            return Outcome.ParseFailed(mediaId, e)
        }

        // Caller (runScan) holds the enclosing db.transaction — see the comment
        // above the cursor loop in runScan for the per-track-transaction → single-
        // transaction refactor rationale.
        run {
            val artistId = upsertArtist(tags.artist, tags.artistSort, mbid = null)
            val albumArtistId = tags.albumArtist?.let { albumArtist ->
                upsertArtist(albumArtist, toSortName(albumArtist), mbid = null)
            } ?: artistId
            val albumId = tags.album?.let { albumName ->
                upsertAlbum(
                    albumArtistId = albumArtistId,
                    albumName = albumName,
                    albumNameSort = toSortName(albumName),
                    year = tags.year,
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
                    disc_number = null,
                    year = tags.year,
                    date = null,
                    genre = tags.genre,
                    composer = tags.composer,
                    bpm = null,
                    codec = tags.codec,
                    bitrate_kbps = tags.bitrateKbps,
                    sample_rate_hz = DEFAULT_SAMPLE_RATE_HZ,
                    bit_depth = null,
                    channels = DEFAULT_CHANNELS,
                    file_path = filePath,
                    file_size_bytes = size,
                    file_mtime_ms = mtime,
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
            } else {
                db.trackQueries.updateForRescan(
                    album_id = albumId,
                    artist_id = artistId,
                    title = tags.title,
                    title_sort = tags.titleSort,
                    duration_ms = tags.durationMs,
                    track_number = tags.trackNumber,
                    disc_number = null,
                    year = tags.year,
                    date = null,
                    genre = tags.genre,
                    composer = tags.composer,
                    bpm = null,
                    codec = tags.codec,
                    bitrate_kbps = tags.bitrateKbps,
                    sample_rate_hz = DEFAULT_SAMPLE_RATE_HZ,
                    bit_depth = null,
                    channels = DEFAULT_CHANNELS,
                    file_size_bytes = size,
                    file_mtime_ms = mtime,
                    replay_gain_track_db = null,
                    replay_gain_album_db = null,
                    replay_gain_track_peak = null,
                    replay_gain_album_peak = null,
                    has_embedded_art = 0L,
                    art_path = null,
                    modifiedAtMs = scanStartedMs,
                    scannedAtMs = scanStartedMs,
                    filePath = filePath,
                )
            }
        }

        return if (existing == null) Outcome.Added else Outcome.Updated
    }

    private fun upsertArtist(name: String, nameSort: String, mbid: String?): Long {
        val existing = db.artistQueries.selectByName(nameSort, mbid).executeAsOneOrNull()
        if (existing != null) return existing.id
        db.artistQueries.insert(name = name, name_sort = nameSort, musicbrainz_artist_id = mbid)
        return db.artistQueries.lastInsertRowId().executeAsOne()
    }

    private fun upsertAlbum(
        albumArtistId: Long,
        albumName: String,
        albumNameSort: String,
        year: Long?,
    ): Long {
        val existing = db.albumQueries.selectByArtistAndName(albumArtistId, albumNameSort)
            .executeAsOneOrNull()
        if (existing != null) return existing.id
        db.albumQueries.insert(
            artist_id = albumArtistId,
            name = albumName,
            name_sort = albumNameSort,
            year = year,
            date = null,
            musicbrainz_release_id = null,
            catalog_number = null,
            label = null,
            art_path = null,
            compilation = 0L,
        )
        return db.albumQueries.lastInsertRowId().executeAsOne()
    }

    // ---------- Phase 2a Track B: SAF tree pass ----------
    //
    // Each SAF upsert runs OUTSIDE the main db.transaction { } block — per-row
    // transactions here are acceptable because SAF scans are typically small
    // folders (Downloads, sideloaded SD), not the full MediaStore-scale 27k
    // library. Batching is a Phase 2a polish target if real-world scans hit
    // perf issues with deeply-nested user picks.
    private fun scanSafTrees(
        safTreeUris: List<String>,
        scanStartedMs: Long,
    ): Triple<Int, Int, Int> {
        if (safTreeUris.isEmpty()) return Triple(0, 0, 0)

        var added = 0
        var updated = 0
        var parseErrors = 0

        for (uriString in safTreeUris) {
            val treeUri = try {
                Uri.parse(uriString)
            } catch (e: Exception) {
                log.w(e) { "skipped malformed SAF URI: $uriString" }
                continue
            }
            // Skip non-SAF URIs (filesystem paths from Desktop wouldn't appear on
            // Android, but defensive in case a shared scan_folders setting is set
            // by some future cross-platform sync flow).
            if (treeUri.scheme != "content") continue

            for (doc in SafTreeWalker.walk(context.contentResolver, treeUri)) {
                val filePath = doc.documentUri.toString()
                val mtime = doc.lastModified.takeIf { it > 0 } ?: scanStartedMs
                val size = doc.size.coerceAtLeast(0L)

                val existing = db.trackQueries.selectByFilePath(filePath).executeAsOneOrNull()
                if (existing != null &&
                    existing.file_mtime_ms == mtime &&
                    existing.file_size_bytes == size &&
                    existing.deleted_at_ms == null
                ) {
                    db.trackQueries.touchLastScanned(scannedAtMs = scanStartedMs, filePath = filePath)
                    continue
                }

                val metadata = SafTagReader.read(context.contentResolver, doc.documentUri, doc.displayName)
                if (metadata == null) {
                    parseErrors++
                    log.w { "SafTagReader returned null for ${doc.displayName} ($filePath); skipping" }
                    continue
                }

                val codec = detectCodecFromMime(doc.mimeType)

                val artistId = upsertArtist(metadata.artist, toSortName(metadata.artist), mbid = null)
                val albumArtistId = metadata.albumArtist?.let { albumArtist ->
                    upsertArtist(albumArtist, toSortName(albumArtist), mbid = null)
                } ?: artistId
                val albumId = metadata.album?.let { albumName ->
                    upsertAlbum(
                        albumArtistId = albumArtistId,
                        albumName = albumName,
                        albumNameSort = toSortName(albumName),
                        year = metadata.year,
                    )
                }

                if (existing == null) {
                    db.trackQueries.insert(
                        album_id = albumId,
                        artist_id = artistId,
                        title = metadata.title,
                        title_sort = toSortName(metadata.title),
                        duration_ms = metadata.durationMs,
                        track_number = metadata.trackNumber,
                        disc_number = null,
                        year = metadata.year,
                        date = null,
                        genre = metadata.genre,
                        composer = metadata.composer,
                        bpm = null,
                        codec = codec,
                        bitrate_kbps = metadata.bitrateKbps,
                        sample_rate_hz = DEFAULT_SAMPLE_RATE_HZ,
                        bit_depth = null,
                        channels = DEFAULT_CHANNELS,
                        file_path = filePath,
                        file_size_bytes = size,
                        file_mtime_ms = mtime,
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
                    added++
                } else {
                    db.trackQueries.updateForRescan(
                        album_id = albumId,
                        artist_id = artistId,
                        title = metadata.title,
                        title_sort = toSortName(metadata.title),
                        duration_ms = metadata.durationMs,
                        track_number = metadata.trackNumber,
                        disc_number = null,
                        year = metadata.year,
                        date = null,
                        genre = metadata.genre,
                        composer = metadata.composer,
                        bpm = null,
                        codec = codec,
                        bitrate_kbps = metadata.bitrateKbps,
                        sample_rate_hz = DEFAULT_SAMPLE_RATE_HZ,
                        bit_depth = null,
                        channels = DEFAULT_CHANNELS,
                        file_size_bytes = size,
                        file_mtime_ms = mtime,
                        replay_gain_track_db = null,
                        replay_gain_album_db = null,
                        replay_gain_track_peak = null,
                        replay_gain_album_peak = null,
                        has_embedded_art = 0L,
                        art_path = null,
                        modifiedAtMs = scanStartedMs,
                        scannedAtMs = scanStartedMs,
                        filePath = filePath,
                    )
                    updated++
                }
            }
        }

        return Triple(added, updated, parseErrors)
    }

    private fun readTagsFromCursor(cursor: Cursor, cols: MediaCols, filePath: String): TrackTags {
        val titleRaw = if (cols.title >= 0) cursor.getStringOrNull(cols.title) else null
        val title = titleRaw?.takeIf { it.isNotBlank() }
            ?: filePath.substringAfterLast('/').substringBeforeLast('.')

        val artist = (if (cols.artist >= 0) cursor.getStringOrNull(cols.artist) else null)
            ?.takeIf { it.isNotBlank() && it != UNKNOWN_PLACEHOLDER }
            ?: "Unknown Artist"

        val album = (if (cols.album >= 0) cursor.getStringOrNull(cols.album) else null)
            ?.takeIf { it.isNotBlank() && it != UNKNOWN_PLACEHOLDER }

        val albumArtist = if (cols.albumArtist >= 0) {
            cursor.getStringOrNull(cols.albumArtist)
                ?.takeIf { it.isNotBlank() && it != UNKNOWN_PLACEHOLDER }
        } else null

        val durationMs = if (cols.duration >= 0) cursor.getLong(cols.duration).coerceAtLeast(0L) else 0L

        // MediaStore TRACK encoding: traditionally "1NNN" where 1 = disc number,
        // NNN = track. We only extract track for MVP; disc is null.
        val trackRaw = if (cols.track >= 0) cursor.getInt(cols.track) else 0
        val trackNumber = when {
            trackRaw <= 0 -> null
            trackRaw > 1000 -> (trackRaw % 1000).toLong()
            else -> trackRaw.toLong()
        }

        val year = (if (cols.year >= 0) cursor.getInt(cols.year) else 0)
            .takeIf { it > 1000 }
            ?.toLong()

        val bitrate = if (cols.bitrate >= 0) cursor.getInt(cols.bitrate) else 0
        val bitrateKbps = if (bitrate > 0) (bitrate / 1000).toLong() else null

        val genre = if (cols.genre >= 0) cursor.getStringOrNull(cols.genre) else null
        val composer = if (cols.composer >= 0) cursor.getStringOrNull(cols.composer) else null

        val mime = if (cols.mimeType >= 0) cursor.getStringOrNull(cols.mimeType) else null
        val codec = detectCodecFromMime(mime)

        return TrackTags(
            title = title,
            titleSort = toSortName(title),
            artist = artist,
            artistSort = toSortName(artist),
            album = album,
            albumArtist = albumArtist,
            durationMs = durationMs,
            trackNumber = trackNumber,
            year = year,
            genre = genre,
            composer = composer,
            codec = codec,
            bitrateKbps = bitrateKbps,
        )
    }
}

// ---------- androidMain-only helpers ----------

internal fun detectCodecFromMime(mime: String?): String = when (mime?.lowercase()) {
    "audio/flac" -> "FLAC"
    "audio/mpeg", "audio/mp3" -> "MP3"
    "audio/x-wav", "audio/wav", "audio/wave" -> "WAV"
    "audio/aac" -> "AAC"
    "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/mpeg-4" -> "AAC"
    "audio/ogg", "audio/vorbis" -> "OGG_VORBIS"
    "audio/opus" -> "OGG_OPUS"
    else -> "UNKNOWN"
}

private fun Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

// ---------- internal models ----------

private data class MediaCols(
    val id: Int,
    val data: Int,
    val title: Int,
    val artist: Int,
    val album: Int,
    val albumArtist: Int,
    val composer: Int,
    val duration: Int,
    val track: Int,
    val year: Int,
    val bitrate: Int,
    val size: Int,
    val dateModified: Int,
    val genre: Int,
    val mimeType: Int,
) {
    // MediaStore.Audio.Media.DATA (the legacy file-path column) is deprecated
    // for write access since API 29 but READ access remains supported. Phase 2a
    // Scoped Storage work will replace this with content:// URI handling end-to-end.
    @Suppress("DEPRECATION")
    companion object {
        fun projection(): Array<String> = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.COMPOSER)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.BITRATE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.GENRE)
            }
        }.toTypedArray()

        fun from(cursor: Cursor): MediaCols = MediaCols(
            id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID),
            data = cursor.getColumnIndex(MediaStore.Audio.Media.DATA),
            title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE),
            artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST),
            album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM),
            albumArtist = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST),
            composer = cursor.getColumnIndex(MediaStore.Audio.Media.COMPOSER),
            duration = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION),
            track = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK),
            year = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR),
            bitrate = cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE),
            size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE),
            dateModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED),
            genre = cursor.getColumnIndex(MediaStore.Audio.Media.GENRE),
            mimeType = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE),
        )
    }
}

private data class TrackTags(
    val title: String,
    val titleSort: String,
    val artist: String,
    val artistSort: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long,
    val trackNumber: Long?,
    val year: Long?,
    val genre: String?,
    val composer: String?,
    val codec: String,
    val bitrateKbps: Long?,
)

private sealed interface Outcome {
    data object Added : Outcome
    data object Updated : Outcome
    data object Unchanged : Outcome
    data class ParseFailed(val mediaId: Long, val error: Throwable) : Outcome
}
