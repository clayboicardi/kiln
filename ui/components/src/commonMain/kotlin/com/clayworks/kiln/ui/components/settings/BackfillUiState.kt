package com.clayworks.kiln.ui.components.settings

/**
 * UI-side view of the analyzer backfill state. Mirrors the
 * [com.clayworks.kiln.library.scan.AnalysisProgress] events but kept
 * in `:ui:components` to avoid leaking flow plumbing into the screen.
 *
 * State machine:
 *   Idle(missingCount = N)            // initial; updates as scanner adds tracks
 *   InProgress(analyzed, skipped, total)
 *   Complete(analyzed, skipped, total, albumsAggregated, durationMs)
 *   InProgress / Complete persist until user clicks the button again or navigates away.
 */
sealed interface BackfillUiState {
    data class Idle(val missingCount: Int) : BackfillUiState
    data class InProgress(val analyzed: Int, val skipped: Int, val total: Int) : BackfillUiState
    data class Complete(
        val analyzed: Int,
        val skipped: Int,
        val total: Int,
        val albumsAggregated: Int,
        val durationMs: Long,
    ) : BackfillUiState
}
