// AndroidFormatFactBackfill — a one-shot pass that fills accurate Android
// audio-format facts for `track` rows that haven't been verified yet
// (metadata_backfilled_at_ms IS NULL), then stamps each processed row so it
// drops out of the worklist (F10: the Spec Sheet must show real MMR facts, not
// scanner placeholders).
//
// MediaMetadataRetriever lifecycle: release() MUST run on every code path,
// even on exception — failure leaks a native MediaExtractor per read, and a
// 27k-track pass would exhaust the process FD/native-handle budget. The
// ParcelFileDescriptor (content:// path) is wrapped in `.use {}`. This mirrors
// SafTagReader.kt exactly.
//
// What MMR reliably yields in this codebase (see SafTagReader): METADATA_KEY_
// BITRATE and embedded-art presence (getEmbeddedPicture). Sample-rate,
// bit-depth, and channel-count are NOT in MMR's METADATA_KEY_* set, so the
// backfill DOES NOT fabricate them — it passes the existing DB values through
// the NOT-NULL sample_rate_hz / channels columns and leaves bit_depth as-is.
//
// Worklist drain (loop-safety): EVERY processed row is stamped — either via
// updateTrackFormatFacts (read succeeded) or markBackfilledNoMetadata (file
// unreadable / MMR threw). Stamped rows leave the IS NULL worklist immediately,
// so a plain LIMIT loop (no OFFSET) terminates. This sidesteps the
// TrackAnalysisRunner infinite-loop class (skipped-but-unstamped rows).

package com.clayworks.kiln.library.scan

import android.content.Context
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
 * Format facts the backfill resolved from a single file via MMR. NOT-NULL
 * columns (sampleRateHz / channels) are seeded from the existing DB row so the
 * backfill never writes a fabricated value when MMR can't supply one.
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
     * facts were updated from a successful MMR read (excludes rows stamped via
     * markBackfilledNoMetadata).
     */
    suspend fun runOnce(): Int = withContext(ioDispatcher) {
        var updated = 0
        while (true) {
            val page = db.trackQueries
                .selectTracksNeedingBackfill(limit = PAGE_LIMIT)
                .executeAsList()
            if (page.isEmpty()) break

            for (row in page) {
                val facts = readFormatFacts(row)
                val nowMs = System.currentTimeMillis()
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
                    updated++
                } else {
                    // File missing / unsupported / MMR threw. Stamp it anyway so
                    // it leaves the IS NULL worklist (loop-safety, F-pattern).
                    db.trackQueries.markBackfilledNoMetadata(
                        backfilledAtMs = nowMs,
                        id = row.id,
                    )
                }
            }
        }
        updated
    }

    /**
     * Reads MMR-derived format facts for one worklist row. Returns null on ANY
     * failure (file missing, unreadable, no parseable header, MMR threw) so the
     * caller stamps the row via markBackfilledNoMetadata.
     *
     * MMR lifecycle mirrors SafTagReader: setDataSource via a `content://`
     * ParcelFileDescriptor wrapped in `.use {}` (closes the FD), or directly
     * from a filesystem path String; release() in `finally` on every path.
     */
    private fun readFormatFacts(row: SelectTracksNeedingBackfill): FormatFacts? {
        val path = row.file_path
        val retriever = MediaMetadataRetriever()
        return try {
            if (path.startsWith("content://")) {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                    ?: return null
                pfd.use { fd ->
                    retriever.setDataSource(fd.fileDescriptor)
                    extract(retriever, row)
                }
            } else {
                retriever.setDataSource(path)
                extract(retriever, row)
            }
        } catch (e: Exception) {
            log.w(e) { "AndroidFormatFactBackfill MMR read failed for $path" }
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                log.w(e) { "MediaMetadataRetriever.release() threw for $path" }
            }
        }
    }

    /**
     * Pulls the MMR-obtainable facts (bitrate + embedded-art presence) and
     * carries the existing sample-rate / bit-depth / channels values through —
     * MMR doesn't expose those in this codebase, so they are preserved, never
     * fabricated.
     */
    private fun extract(retriever: MediaMetadataRetriever, row: SelectTracksNeedingBackfill): FormatFacts {
        val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            ?.toLongOrNull()
        val bitrateKbps = if (bitrate != null && bitrate > 0) bitrate / 1000 else null
        val hasEmbeddedArt = retriever.embeddedPicture != null
        return FormatFacts(
            sampleRateHz = row.sample_rate_hz,
            bitDepth = row.bit_depth,
            channels = row.channels,
            bitrateKbps = bitrateKbps,
            hasEmbeddedArt = hasEmbeddedArt,
        )
    }
}
