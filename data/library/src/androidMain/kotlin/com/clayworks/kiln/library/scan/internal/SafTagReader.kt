// SafTagReader — opens a content:// URI via the system ContentResolver,
// wraps it in a MediaMetadataRetriever, and extracts a TrackTags-shaped
// metadata bundle. Best-effort; failures yield null fields rather than
// crashing the scan.
//
// MediaMetadataRetriever lifecycle: release() MUST run on every code path,
// even on exception. This file uses try/finally pattern. Failure to release
// leaks a native MediaExtractor instance per failed read; on a multi-thousand
// track library this matters.
//
// Sample-rate / bit-depth / channel-count are NOT in MediaMetadataRetriever's
// METADATA_KEY_* set. We default sample_rate to 44100 and channels to 2 —
// same defaults as AndroidMediaStoreScanner's MediaStore pass. A per-file
// MediaFormat introspection pass is Phase 2a polish.

package com.clayworks.kiln.library.scan.internal

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import co.touchlab.kermit.Logger

private val log = Logger.withTag("SafTagReader")

internal const val DEFAULT_SAMPLE_RATE_HZ = 44100L
internal const val DEFAULT_CHANNELS = 2L

data class SafTrackMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long,
    val trackNumber: Long?,
    val year: Long?,
    val genre: String?,
    val composer: String?,
    val bitrateKbps: Long?,
)

object SafTagReader {

    /**
     * Reads metadata from a SAF document URI. Returns null if the retriever
     * cannot open the file or the document has no parseable audio header.
     *
     * Caller is responsible for using the returned [SafTrackMetadata] as a
     * read-through layer over the file's [displayName] — if any field is
     * empty/null, fall back to the display name (e.g., title defaults to
     * the filename minus extension).
     */
    fun read(
        contentResolver: ContentResolver,
        documentUri: Uri,
        displayName: String,
    ): SafTrackMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            val pfd = contentResolver.openFileDescriptor(documentUri, "r")
                ?: return null
            pfd.use { fd ->
                retriever.setDataSource(fd.fileDescriptor)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: displayName.substringBeforeLast('.')
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown Artist"
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() }
                val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.takeIf { it.isNotBlank() }
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
                val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?.toIntOrNull()
                    ?.takeIf { it > 1000 }
                    ?.toLong()
                val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                    ?.takeIf { it.isNotBlank() }
                val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                    ?.takeIf { it.isNotBlank() }
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()
                val bitrateKbps = if (bitrate != null && bitrate > 0) bitrate / 1000 else null

                SafTrackMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtist = albumArtist,
                    durationMs = durationMs,
                    trackNumber = trackNumber,
                    year = year,
                    genre = genre,
                    composer = composer,
                    bitrateKbps = bitrateKbps,
                )
            }
        } catch (e: Exception) {
            log.w(e) { "SafTagReader failed for $documentUri ($displayName)" }
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                log.w(e) { "MediaMetadataRetriever.release() threw for $documentUri" }
            }
        }
    }
}
