// SearchTab re-runs an active query when the library revision changes (PR #39 review round 2).
// search() is a one-shot snapshot (#38), so a query typed while a scan is still running must be
// re-run once the scan commits. Otherwise it keeps showing pre-scan results until the query is edited.

package com.clayworks.kiln.ui.components.search

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import arrow.core.Either
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.library.source.BrowseScope
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.LocalSourceCapabilities
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.Playable
import com.clayworks.kiln.library.source.SearchResult
import com.clayworks.kiln.library.source.SourceError
import com.clayworks.kiln.library.source.SourceId
import com.clayworks.kiln.ui.components.nowplaying.FakePlatformPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import kotlin.test.Test

class SearchTabReloadTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun track(id: String, title: String) = MediaItem(
        itemId = ItemId(id),
        sourceId = SourceId("local"),
        kind = MediaItem.Kind.Track,
        title = title,
        subtitle = "Artist",
        durationMs = 180_000L,
    )

    /** A snapshot source whose matches the test swaps out, standing in for a scan committing. */
    private class SwappableSource(@Volatile var matches: List<MediaItem>) : MusicSource {
        @Volatile var searches = 0
        override val id = SourceId("local")
        override val displayName = "Test"
        override val capabilities = LocalSourceCapabilities
        override suspend fun search(query: String, limit: Int): Flow<SearchResult> {
            searches++
            return matches.map { SearchResult(item = it) }.asFlow()
        }
        override suspend fun browse(scope: BrowseScope): Flow<MediaItem> = emptyFlow()
        override suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> =
            Either.Left(SourceError.ItemNotFound(itemId))
    }

    private fun shown(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun revisionBump_rerunsTheActiveQuery() {
        val source = SwappableSource(matches = emptyList())
        val revision = MutableStateFlow(0L)
        val player = FakePlatformPlayer(initialItem = track("0", "Unused"), initialState = PlayerState.Idle)

        composeRule.setContent { SearchTab(source, player, revision).Content() }
        composeRule.onNode(hasSetTextAction()).performTextInput("Drive")
        // Wait until the debounced search has actually RUN against the pre-scan snapshot. Otherwise
        // the first search could run after the swap below and pass without any re-run.
        composeRule.waitUntil(timeoutMillis = 5_000) { source.searches == 1 }
        composeRule.waitForIdle()
        check(!shown("Drive Slow")) { "precondition: no match before the scan commits" }

        // The scan commits after the query ran, then bumps the revision.
        source.matches = listOf(track("1", "Drive Slow"))
        revision.value = 1L

        composeRule.waitUntil(timeoutMillis = 5_000) { shown("Drive Slow") }
    }
}
