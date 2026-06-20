// SpecSheetScreen — per-track Spec Sheet detail. Pushed onto the Now Playing
// Tab's inner Voyager Navigator when the user taps the now-playing title. Holds
// ONLY the trackId (serializable — see below); pulls its LibraryStatsSource
// from the LocalLibraryStats CompositionLocal, collects the per-track entry +
// library aggregate flows, and renders the stateless SpecSheetContent.
//
// Voyager `Screen : Serializable` constraint: the Navigator serializes its
// back-stack across process death / config change. A Screen holding a
// non-serializable dependency (LibraryStatsSource → KilnDatabase) would throw
// NotSerializableException, so the dependency is obtained from a CompositionLocal
// instead of a constructor param. See LocalLibraryStats.kt.

package com.clayworks.kiln.ui.components.specsheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.clayworks.kiln.library.source.LibraryAggregate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

class SpecSheetScreen(
    private val trackId: String,
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val statsSource = LocalLibraryStats.current

        // Combine the per-track entry with the library aggregate into a single
        // UiState flow. The aggregate flow is seeded with a leading `null` so
        // the sheet can render the entry the moment it resolves — the footer
        // fills in once the (potentially heavier) aggregate query emits.
        //
        // `combine` withholds its first value until BOTH upstreams have emitted;
        // because the aggregate emits `null` immediately, the gate reduces to
        // "has the entry flow emitted yet?". That gives us a clean three-state
        // machine: pre-emission → Loading (the collectAsState seed), null entry
        // after emission → NotFound, non-null entry → Loaded.
        val stateFlow = remember(statsSource, trackId) {
            // Widen the aggregate flow to nullable FIRST (Flow is covariant in
            // its element), so `onStart`'s element type is LibraryAggregate? and
            // the leading `emit(null)` seed type-checks — aggregateStats() itself
            // has a non-null element type.
            val aggregateUpstream: Flow<LibraryAggregate?> = statsSource.aggregateStats()
            val aggregateOrNull: Flow<LibraryAggregate?> =
                aggregateUpstream.onStart { emit(null) }
            combine(
                statsSource.specSheetEntry(trackId),
                aggregateOrNull,
            ) { entry, aggregate ->
                if (entry == null) {
                    SpecSheetUiState.NotFound
                } else {
                    SpecSheetUiState.Loaded(entry, aggregate)
                }
            }
        }
        val state by stateFlow.collectAsState(initial = SpecSheetUiState.Loading)

        SpecSheetContent(state = state, onBack = { navigator.pop() })
    }
}
