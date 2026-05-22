package com.clayworks.kiln.library.scan

/**
 * Intermediate + terminal events emitted by [TrackAnalysisRunner.runOnceWithProgress].
 *
 * Stream shape:
 *   [Started(total=N)] → [Progress(analyzed, skipped, total)]* → [Complete(result)]
 *
 * If the runner throws mid-pass, the flow terminates with the exception
 * (not a [Failed] event — failures here are coroutine cancellation /
 * unexpected exceptions, not analyzer-Left results).
 */
sealed interface AnalysisProgress {
    /** Emitted once at the start. [total] is the worklist size from `countTracksMissingReplayGain`. */
    data class Started(val total: Int) : AnalysisProgress

    /**
     * Emitted after every page of the worklist. [analyzed] + [skipped]
     * sum to the number of tracks processed so far; [total] is the
     * snapshot taken at [Started] (may not match later if other writers
     * race — UI should treat as a stable denominator).
     */
    data class Progress(
        val analyzed: Int,
        val skipped: Int,
        val total: Int,
    ) : AnalysisProgress

    /** Terminal event: the [AnalysisPassResult] is the same shape `runOnce()` returns. */
    data class Complete(val result: AnalysisPassResult) : AnalysisProgress
}
