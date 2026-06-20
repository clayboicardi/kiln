// AndroidFormatFactBackfill — a one-shot pass that fills accurate Android
// audio-format facts for `track` rows that haven't been verified yet
// (metadata_backfilled_at_ms IS NULL), then stamps each processed row so it
// drops out of the worklist (F10: the Spec Sheet must show real facts, not
// scanner placeholders).
//
// WHY THIS EXISTS: AndroidMediaStoreScanner hard-codes sample_rate_hz = 44100,
// channels = 2, bit_depth = null for EVERY track at scan time (MediaStore
// doesn't surface these per-row cheaply). So the placeholder is wrong for any
// hi-res / mono / surround content. This backfill corrects the headline facts.
//
// EXTRACTION SOURCES (this round):
//   sample_rate_hz ← MediaExtractor audio-track KEY_SAMPLE_RATE  (corrects placeholder)
//   channels       ← MediaExtractor audio-track KEY_CHANNEL_COUNT (corrects placeholder)
//   bitrate_kbps   ← MediaMetadataRetriever METADATA_KEY_BITRATE
//   has_embedded_art ← MediaMetadataRetriever getEmbeddedPicture() != null
//   bit_depth      ← left NULL. MediaExtractor/MMR don't expose decoded bit
//                    depth reliably; correcting it needs a MediaCodec decode
//                    pass (it surfaces only after INFO_OUTPUT_FORMAT_CHANGED via
//                    KEY_PCM_ENCODING). Explicitly out of scope this round.
//
// MediaExtractor lifecycle: release() MUST run on every code path, even on
// exception — failure leaks a native MediaExtractor per read, and a 27k-track
// pass would exhaust the process FD/native-handle budget. For content:// paths
// the ParcelFileDescriptor is wrapped in `.use {}` (closes the FD). This mirrors
// AndroidMediaTrackAnalyzer's extractor usage + SafTagReader's FD discipline.
//
// Worklist drain (loop-safety): EVERY processed row is stamped — either via
// updateTrackFormatFacts (read succeeded) or markBackfilledNoMetadata (file
// unreadable / no audio track / extractor threw). Stamped rows leave the IS NULL
// worklist immediately, so a plain LIMIT loop (no OFFSET) terminates. This
// sidesteps the TrackAnalysisRunner infinite-loop class (skipped-but-unstamped
// rows).

package com.clayworks.kiln.library.scan

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import co.touchlab.kermit.Logger
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.data.library.db.SelectTracksNeedingBackfill
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private val log = Logger.withTag("AndroidFormatFactBackfill")

private const val PAGE_LIMIT = 200L

/**
 * Format facts the backfill resolved from a single file. sample-rate + channels
 * come from MediaExtractor (correcting the scanner placeholders); bitrate + art
 * from MediaMetadataRetriever; bit-depth is left NULL this round.
 */
private data class FormatFacts(
    val sampleRateHz: Long,
    val bitDepth: Long?,
    val channels: Long,
    val bitrateKbps: Long?,
    val hasEmbeddedArt: Boolean,
)

class AndroidFormatFactBackfill(
    private val context: Context,
    private val db: KilnDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * Drains the backfill worklist. Returns the number of rows whose format
     * facts were updated from a successful read (excludes rows stamped via
     * markBackfilledNoMetadata).
     */
    suspend fun runOnce(): Int = withContext(ioDispatcher) {
        var updated = 0
        while (true) {
            val page = db.trackQueries
                .selectTracksNeedingBackfill(limit = PAGE_LIMIT)
                .executeAsList()
            if (page.isEmpty()) break

            // Read native format facts OUTSIDE any transaction — MediaExtractor /
            // MMR I/O must not hold the SQLite write lock. Then batch the whole
            // page's writes into ONE db.transaction so SQLite fsyncs once per page
            // (200 rows) instead of once per row; a large-library backfill would
            // otherwise spend minutes on per-row autocommit overhead.
            val pageFacts = page.map { row -> row to readFormatFacts(row) }
            val nowMs = System.currentTimeMillis()
            db.transaction {
                for ((row, facts) in pageFacts) {
                    if (facts != null) {
                        db.trackQueries.updateTrackFormatFacts(
                            sampleRateHz = facts.sampleRateHz,
                            bitDepth = facts.bitDepth,
                            channels = facts.channels,
                            bitrateKbps = facts.bitrateKbps,
                            hasEmbeddedArt = if (facts.hasEmbeddedArt) 1L else 0L,
                            backfilledAtMs = nowMs,
                            id = row.id,
                        )
                    } else {
                        // File missing / unsupported / no audio track / extractor
                        // threw. Stamp it anyway so it leaves the IS NULL worklist
                        // (loop-safety, F-pattern).
                        db.trackQueries.markBackfilledNoMetadata(
                            backfilledAtMs = nowMs,
                            id = row.id,
                        )
                    }
                }
            }
            updated += pageFacts.count { it.second != null }
        }
        updated
    }

    /**
     * Reads format facts for one worklist row. Returns null on ANY failure
     * (file missing, unreadable, no audio track, missing sample-rate/channel
     * keys, extractor/MMR threw) so the caller stamps the row via
     * markBackfilledNoMetadata.
     *
     * Two native readers, each released in `finally` on every path:
     *   1. MediaExtractor — authoritative sample-rate + channel-count (corrects
     *      the scanner's 44100/2 placeholders). If this fails to yield BOTH, the
     *      whole read fails (we don't want a half-corrected row).
     *   2. MediaMetadataRetriever — bitrate + embedded-art presence.
     */
    private fun readFormatFacts(row: SelectTracksNeedingBackfill): FormatFacts? {
        val path = row.file_path
        val rates = readSampleRateAndChannels(path) ?: return null
        val (bitrateKbps, hasEmbeddedArt) = readBitrateAndArt(path)
        return FormatFacts(
            sampleRateHz = rates.first,
            // bit_depth left NULL this round — needs a MediaCodec decode pass.
            bitDepth = null,
            channels = rates.second,
            bitrateKbps = bitrateKbps,
            hasEmbeddedArt = hasEmbeddedArt,
        )
    }

    /**
     * MediaExtractor pass: opens [path], selects the first track whose MIME
     * starts with "audio/", and reads KEY_SAMPLE_RATE + KEY_CHANNEL_COUNT.
     * Returns (sampleRateHz, channels) or null if the file can't be opened, has
     * no audio track, or omits either key. release() runs in `finally` on every
     * path; content:// paths open via a ParcelFileDescriptor wrapped in `.use{}`.
     */
    private fun readSampleRateAndChannels(path: String): Pair<Long, Long>? {
        val extractor = MediaExtractor()
        return try {
            if (path.startsWith("content://")) {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                    ?: return null
                pfd.use { fd -> extractor.setDataSource(fd.fileDescriptor) }
            } else {
                extractor.setDataSource(path)
            }

            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            val format = extractor.getTrackFormat(audioTrackIndex)
            if (!format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ||
                !format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
            ) {
                return null
            }
            val sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toLong()
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toLong()
            sampleRateHz to channels
        } catch (e: Exception) {
            log.w(e) { "AndroidFormatFactBackfill MediaExtractor read failed for $path" }
            null
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
                log.w(e) { "MediaExtractor.release() threw for $path" }
            }
        }
    }

    /**
     * MediaMetadataRetriever pass: bitrate (kbps) + embedded-art presence.
     * Returns (null, false) on any failure — bitrate/art are non-NOT-NULL
     * columns, so a failure here doesn't invalidate the sample-rate/channel
     * correction; it just leaves those two facts unfilled. MMR lifecycle mirrors
     * SafTagReader: PFD via `.use{}`, release() in `finally` on every path.
     */
    private fun readBitrateAndArt(path: String): Pair<Long?, Boolean> {
        val retriever = MediaMetadataRetriever()
        return try {
            if (path.startsWith("content://")) {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                    ?: return null to false
                pfd.use { fd -> retriever.setDataSource(fd.fileDescriptor) }
            } else {
                retriever.setDataSource(path)
            }
            val bitrate = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
            val bitrateKbps = if (bitrate != null && bitrate > 0) bitrate / 1000 else null
            // embeddedPicture loads the full artwork byte[] per track. There is no
            // cheaper "has embedded album art" metadata key on MediaMetadataRetriever
            // (METADATA_KEY_HAS_IMAGE is for still-image tracks, not cover art). The
            // allocation is transient per row — freed before the next via the
            // per-row retriever.release() in finally — so it does not accumulate.
            val hasEmbeddedArt = retriever.embeddedPicture != null
            bitrateKbps to hasEmbeddedArt
        } catch (e: Exception) {
            log.w(e) { "AndroidFormatFactBackfill MMR read failed for $path" }
            null to false
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                log.w(e) { "MediaMetadataRetriever.release() threw for $path" }
            }
        }
    }
}
