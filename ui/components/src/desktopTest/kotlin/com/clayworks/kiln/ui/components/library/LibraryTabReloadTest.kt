// LibraryTab reloads its one-shot snapshot when the library revision changes (PR #39 review P1).
// Since #38, browse() completes after one snapshot, so a scan that commits AFTER the tab's first
// load (scan-on-launch on a fresh install, or auto-scan after adding a folder) must trigger an
// explicit reload. Otherwise the tab stays empty or stale until it's recreated.

package com.clayworks.kiln.ui.components.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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

class LibraryTabReloadTest {

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

    /** A snapshot source whose rows the test swaps out, standing in for a scan committing. */
    private class SwappableSource(var rows: List<MediaItem>) : MusicSource {
        override val id = SourceId("local")
        override val displayName = "Test"
        override val capabilities = LocalSourceCapabilities
        override suspend fun search(query: String, limit: Int): Flow<SearchResult> = emptyFlow()
        override suspend fun browse(scope: BrowseScope): Flow<MediaItem> = rows.toList().asFlow()
        override suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> =
            Either.Left(SourceError.ItemNotFound(itemId))
    }

    @Test
    fun revisionBump_reloadsTheSnapshot() {
        val source = SwappableSource(rows = emptyList())
        val revision = MutableStateFlow(0L)
        val player = FakePlatformPlayer(initialItem = track("0", "Unused"), initialState = PlayerState.Idle)

        composeRule.setContent { LibraryTab(source, player, revision).Content() }
        composeRule.onNodeWithText("Comfortably Numb").assertDoesNotExist()

        // The scan commits after the first load, then bumps the revision.
        source.rows = listOf(track("1", "Comfortably Numb"))
        revision.value = 1L

        composeRule.onNodeWithText("Comfortably Numb").assertIsDisplayed()
    }
}
