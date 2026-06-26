package com.clayworks.kiln.library.scan

import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.replaygain.albumIntegratedLufs
import com.clayworks.kiln.data.library.db.KilnDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import com.clayworks.kiln.library.db.DatabaseWriter
import kotlinx.coroutines.withContext
import kotlin.math.pow

private val log = Logger.withTag("TrackAnalysisRunner")

private const val REFERENCE_LUFS = -18.0
private const val PAGE_SIZE = 100L

/**
 * Orchestrator for the ReplayGain analyzer pass.
 *
 * Walks `selectTracksMissingReplayGain` in pages, calls [analyzer] for each
 * track, persists per-track gain + peak, then runs the per-album rollup over
 * any album whose `replay_gain_album_db` is NULL but at least one track has
 * been freshly analyzed.
 *
 * The runner is deliberately **not** invoked automatically from
 * `LibraryScanner.scanIncremental()` / `scanFull()`. Perf math (≈1.5-10 s
 * per track × 40k tracks = 17-100 h) makes inline invocation user-hostile;
 * Track D-C's backfill UI is the user-explicit trigger.
 *
 * Concurrency: per-track `analyzer.analyze(...)` calls run sequentially on
 * [ioDispatcher]. The implementation does NOT parallelize — desktop FLAC
 * decoding via libFLAC is already CPU-bound on a single core, and the
 * runner's primary use case is background batch where a tight memory
 * footprint matters more than wall-clock throughput. A future iteration
 * can add a parallelism setting if perf requires it.
 *
 * Worklist-advance discipline: analyzed rows drop out of the worklist
 * naturally (their `replay_gain_track_db` is no longer NULL). Skipped rows
 * (analyzer returned Left) STILL appear in subsequent queries; we advance
 * `pageOffset` by the running count of skipped rows so the loop scrolls
 * past them. Without this offset advance, an all-skipping analyzer would
 * re-query page 0 forever.
 *
 * Touched-album tracking: the runner tracks `album_id` values of all
 * successfully-analyzed tracks in an in-memory `Set<Long>`. The per-album
 * rollup iterates this set rather than re-querying `selectAlbumsForAggregation`,
 * which would filter `replay_gain_album_db IS NULL` and miss albums that were
 * partially aggregated in a previous run. `selectAlbumsForAggregation` is
 * retained for a future "recover incomplete albums" code path but is not the
 * primary driver of rollup.
 *
 * Atomicity: each per-track persist is its own write (no enclosing
 * transaction). The per-album rollup IS wrapped in a transaction so all
 * tracks of an album receive the same album-level values atomically.
 */
class TrackAnalysisRunner(
    private val db: KilnDatabase,
    private val analyzer: TrackAnalyzer,
    private val ioDispatcher: CoroutineDispatcher,
    private val writer: DatabaseWriter,
) {

    /**
     * Run one full pass: analyze every track with NULL `replay_gain_track_db`,
     * then aggregate any albums that gained at least one analyzed track.
     *
     * @return [AnalysisPassResult] with per-track + per-album counts.
     */
    suspend fun runOnce(): AnalysisPassResult = withContext(ioDispatcher) {
        val startedMs = System.currentTimeMillis()
        var analyzed = 0
        var skipped = 0
        var skippedTotal = 0L
        val touchedAlbumIds = mutableSetOf<Long>()

        while (true) {
            val page = writer.write {
                db.trackQueries
                    .selectTracksMissingReplayGain(pageSize = PAGE_SIZE, pageOffset = skippedTotal)
                    .executeAsList()
            }
            if (page.isEmpty()) break

            var pageSkippedDelta = 0
            for (row in page) {
                when (val result = analyzer.analyze(row.file_path, row.codec)) {
                    is Either.Left -> {
                        skipped++
                        pageSkippedDelta++
                        log.w {
                            "analyzer skipped track id=${row.id} path=${row.file_path}: ${result.value}"
                        }
                    }
                    is Either.Right -> {
                        val gainDb = REFERENCE_LUFS - result.value.integratedLufs
                        val peakLinear = dbtpToLinear(result.value.truePeakDbtp)
                        writer.write {
                            db.trackQueries.updateTrackReplayGainIfUnchanged(
                                db = gainDb,
                                peak = peakLinear,
                                id = row.id,
                                filePath = row.file_path,
                                fileMtimeMs = row.file_mtime_ms,
                                fileSizeBytes = row.file_size_bytes,
                            )
                        }
                        analyzed++
                        row.album_id?.let { touchedAlbumIds.add(it) }
                    }
                }
            }
            skippedTotal += pageSkippedDelta

            // Termination guard: every row on the page was skipped AND we
            // got a short page (so there's nothing left in the worklist past
            // the skipped tail). Without this we'd loop forever on a fully
            // failing analyzer with > PAGE_SIZE tracks to skip.
            if (pageSkippedDelta == page.size && page.size < PAGE_SIZE.toInt()) break
        }

        // Per-album rollup. (I2 fix) Use touchedAlbumIds rather than
        // selectAlbumsForAggregation — the latter filters
        // `replay_gain_album_db IS NULL` which causes the partial-pass
        // finalization gap (run 1 sets album_db non-null after partial track
        // coverage; run 2's newly-analyzed tracks never re-aggregate).
        var albumsAggregated = 0
        for (albumId in touchedAlbumIds) {
            val perTrack = writer.write {
                db.trackQueries.selectTrackReplayGainForAlbum(albumId).executeAsList()
            }
            if (perTrack.isEmpty()) continue

            // selectTrackReplayGainForAlbum filters IS NOT NULL so replay_gain_track_db is non-null.
            val trackLufsList = perTrack.map { row -> REFERENCE_LUFS - row.replay_gain_track_db }
            val albumLufs = when (val agg = albumIntegratedLufs(trackLufsList)) {
                is Either.Left -> {
                    log.w { "album $albumId rollup failed: ${agg.value}" }
                    continue
                }
                is Either.Right -> agg.value
            }
            val albumDb = REFERENCE_LUFS - albumLufs
            val albumPeak = perTrack.mapNotNull { it.replay_gain_track_peak }.maxOrNull() ?: 0.0

            writer.write {
                db.transaction {
                    db.trackQueries.updateAlbumReplayGainForAlbum(
                        albumId = albumId,
                        db = albumDb,
                        peak = albumPeak,
                    )
                }
            }
            albumsAggregated++
        }

        val durationMs = System.currentTimeMillis() - startedMs
        AnalysisPassResult(
            tracksAnalyzed = analyzed,
            tracksSkipped = skipped,
            albumsAggregated = albumsAggregated,
            durationMs = durationMs,
        ).also {
            log.i {
                "analysis pass complete: +${it.tracksAnalyzed} analyzed, " +
                    "${it.tracksSkipped} skipped, ${it.albumsAggregated} albums aggregated " +
                    "in ${it.durationMs}ms"
            }
        }
    }

    /**
     * Flow-emitting variant of [runOnce]. Suitable for UI progress reporting.
     *
     * Emits exactly one [AnalysisProgress.Started] at the start, one
     * [AnalysisProgress.Progress] after every worklist page, and exactly
     * one [AnalysisProgress.Complete] at the end. Cancellation of the
     * collector cancels the analysis pass via standard coroutine cancellation
     * semantics.
     *
     * Implementation note: this is a separate flow body, not a wrapper that
     * polls [runOnce]. Sharing the per-page loop between `runOnce()` and
     * `runOnceWithProgress()` would either require a callback parameter on
     * `runOnce()` (breaking its existing test surface) or a SharedFlow with
     * back-pressure semantics that would complicate the test contract.
     * Duplicate logic is the lesser evil at this size — keep both methods
     * in sync via the per-task review process.
     */
    fun runOnceWithProgress(): Flow<AnalysisProgress> = flow {
        val total = writer.write {
            db.trackQueries.countTracksMissingReplayGain().executeAsOne().toInt()
        }
        emit(AnalysisProgress.Started(total = total))

        val startedMs = System.currentTimeMillis()
        var analyzed = 0
        var skipped = 0
        var skippedTotal = 0L
        val touchedAlbumIds = mutableSetOf<Long>()

        while (true) {
            val page = writer.write {
                db.trackQueries
                    .selectTracksMissingReplayGain(pageSize = PAGE_SIZE, pageOffset = skippedTotal)
                    .executeAsList()
            }
            if (page.isEmpty()) break

            var pageSkippedDelta = 0
            for (row in page) {
                when (val result = analyzer.analyze(row.file_path, row.codec)) {
                    is Either.Left -> {
                        skipped++
                        pageSkippedDelta++
                        log.w {
                            "analyzer skipped track id=${row.id} path=${row.file_path}: ${result.value}"
                        }
                    }
                    is Either.Right -> {
                        val gainDb = REFERENCE_LUFS - result.value.integratedLufs
                        val peakLinear = dbtpToLinear(result.value.truePeakDbtp)
                        writer.write {
                            db.trackQueries.updateTrackReplayGainIfUnchanged(
                                db = gainDb,
                                peak = peakLinear,
                                id = row.id,
                                filePath = row.file_path,
                                fileMtimeMs = row.file_mtime_ms,
                                fileSizeBytes = row.file_size_bytes,
                            )
                        }
                        analyzed++
                        row.album_id?.let { touchedAlbumIds.add(it) }
                    }
                }
            }
            skippedTotal += pageSkippedDelta

            emit(AnalysisProgress.Progress(analyzed = analyzed, skipped = skipped, total = total))

            if (pageSkippedDelta == page.size && page.size < PAGE_SIZE.toInt()) break
        }

        var albumsAggregated = 0
        for (albumId in touchedAlbumIds) {
            val perTrack = writer.write {
                db.trackQueries.selectTrackReplayGainForAlbum(albumId).executeAsList()
            }
            if (perTrack.isEmpty()) continue

            val trackLufsList = perTrack.map { row -> REFERENCE_LUFS - row.replay_gain_track_db }
            val albumLufs = when (val agg = albumIntegratedLufs(trackLufsList)) {
                is Either.Left -> {
                    log.w { "album $albumId rollup failed: ${agg.value}" }
                    continue
                }
                is Either.Right -> agg.value
            }
            val albumDb = REFERENCE_LUFS - albumLufs
            val albumPeak = perTrack.mapNotNull { it.replay_gain_track_peak }.maxOrNull() ?: 0.0

            writer.write {
                db.transaction {
                    db.trackQueries.updateAlbumReplayGainForAlbum(
                        albumId = albumId,
                        db = albumDb,
                        peak = albumPeak,
                    )
                }
            }
            albumsAggregated++
        }

        val durationMs = System.currentTimeMillis() - startedMs
        log.i {
            "analysis pass complete (progress flow): +$analyzed analyzed, " +
                "$skipped skipped, $albumsAggregated albums aggregated in ${durationMs}ms"
        }
        emit(
            AnalysisProgress.Complete(
                result = AnalysisPassResult(
                    tracksAnalyzed = analyzed,
                    tracksSkipped = skipped,
                    albumsAggregated = albumsAggregated,
                    durationMs = durationMs,
                ),
            ),
        )
    }.flowOn(ioDispatcher)

    private fun dbtpToLinear(dbtp: Double): Double = 10.0.pow(dbtp / 20.0)
}
