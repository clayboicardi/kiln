package com.clayworks.kiln.ui.components.settings

/**
 * Coarse scan-progress state for the Settings "Scan now" affordance. The
 * LibraryScanner returns a single Either<ScanError, ScanResult> (no progress
 * Flow), so the UI shows Idle → Scanning → Done/Error rather than a live
 * per-file bar. Mirrors BackfillUiState's role for the analyzer.
 */
sealed interface ScanUiState {
    /** No scan running; show the trigger button. */
    data object Idle : ScanUiState

    /** A scan is in flight; show an indeterminate indicator. */
    data object Scanning : ScanUiState

    /** Last scan finished. Counts come straight from ScanResult. */
    data class Done(
        val added: Int,
        val updated: Int,
        val softDeleted: Int,
        val unchanged: Int,
        val durationMs: Long,
    ) : ScanUiState

    /** Last scan failed; message is a human-readable ScanError rendering. */
    data class Error(val message: String) : ScanUiState
}
