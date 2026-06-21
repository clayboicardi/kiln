// Spec-sheet read-model domain types + the narrow LibraryStatsSource interface
// (Phase 2b-A Task A1). Consumed by the per-track Spec Sheet UI (tasks A4/A5).
//
// Source Protocol invariant: these read methods live on a SEPARATE interface,
// NOT on MusicSource. LocalLibrarySource co-implements both. This keeps the
// browse/search/getPlayable surface stable while exposing aggregate + format
// facts the Spec Sheet needs.

package com.clayworks.kiln.library.source

import kotlinx.coroutines.flow.Flow

/**
 * Full per-track format/ReplayGain/file facts for the Spec Sheet detail view.
 * Maps 1:1 from the generated `Track` row (see LocalLibrarySourceMappers).
 */
data class SpecSheetEntry(
    val trackId: String,
    val title: String,
    val codec: String,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val channels: Int,
    val bitrateKbps: Int?,
    val durationMs: Long,
    val replayGainTrackDb: Double?,
    val replayGainAlbumDb: Double?,
    val replayGainTrackPeak: Double?,
    val replayGainAlbumPeak: Double?,
    val hasEmbeddedArt: Boolean,
    val filePath: String,
    val fileSizeBytes: Long,
    val fileMtimeMs: Long,
    val hasKnownMtime: Boolean,
)

/**
 * Library-wide aggregate stats for the Spec Sheet stats header. Coverage
 * fractions are 0.0..1.0 over live (non-soft-deleted) tracks.
 */
data class LibraryAggregate(
    val totalTracks: Long,
    val totalBytes: Long,
    val codecCounts: Map<String, Long>,
    val replayGainCoverage: Double,   // 0.0..1.0 fraction with replay_gain_track_db NOT NULL
    val knownMtimeCoverage: Double,   // fraction with has_known_mtime = 1
)

/**
 * Narrow read interface for the Spec Sheet. Deliberately NOT part of
 * MusicSource (Source Protocol invariant) — implemented by LocalLibrarySource.
 */
interface LibraryStatsSource {
    fun specSheetEntry(trackId: String): Flow<SpecSheetEntry?>
    fun aggregateStats(): Flow<LibraryAggregate>
}
