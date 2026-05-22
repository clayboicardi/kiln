package com.clayworks.kiln.library.scan

/** Summary of a [TrackAnalysisRunner] pass. */
data class AnalysisPassResult(
    /** Tracks whose analyzer returned a result and got persisted. */
    val tracksAnalyzed: Int,

    /** Tracks the analyzer rejected (CodecUnsupported / DecodeFailed / AnalysisFailed).
     *  Per-track error details are logged, not surfaced here. */
    val tracksSkipped: Int,

    /** Albums that got a fresh per-album rollup this pass. */
    val albumsAggregated: Int,

    /** Wall-clock duration of the pass, in milliseconds. */
    val durationMs: Long,
)
