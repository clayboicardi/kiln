// Plain hoisted UI state for the Spec Sheet — no Compose dependencies, no
// coroutine internals. App modules (task A5) collect LibraryStatsSource flows
// into one of these and pass it into SpecSheetContent. The screen is fully
// stateless: every case it can render is enumerated here.

package com.clayworks.kiln.ui.components.specsheet

import com.clayworks.kiln.library.source.LibraryAggregate
import com.clayworks.kiln.library.source.SpecSheetEntry

/**
 * The three states the Spec Sheet detail view can be in.
 *
 * `data object` (rather than bare `object`) gives the singletons a readable
 * `toString()` for test diagnostics and logging.
 */
sealed interface SpecSheetUiState {
    /** Lookup in flight — the entry hasn't resolved yet. */
    data object Loading : SpecSheetUiState

    /** The requested trackId resolved to null (deleted / never existed). */
    data object NotFound : SpecSheetUiState

    /**
     * The entry resolved. [aggregate] is the library-wide footer stats; it may
     * be null if aggregate collection hasn't completed (the entry renders
     * without the footer in that window).
     */
    data class Loaded(
        val entry: SpecSheetEntry,
        val aggregate: LibraryAggregate?,
    ) : SpecSheetUiState
}
