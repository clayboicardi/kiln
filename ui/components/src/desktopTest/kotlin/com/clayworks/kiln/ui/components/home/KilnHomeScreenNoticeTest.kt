// Skip notices show as an app-shell Snackbar, visible on every tab (#37, R20). Playback starts
// from Library or Search without switching tabs, so a notice collected only by Now Playing would
// be dropped (the player's notices flow has replay = 0).

package com.clayworks.kiln.ui.components.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import arrow.core.Either
import com.clayworks.kiln.audio.playback.OutputRoute
import com.clayworks.kiln.audio.playback.PlaybackDiagnostics
import com.clayworks.kiln.audio.playback.PlaybackNotice
import com.clayworks.kiln.audio.playback.PlayerError
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
import com.clayworks.kiln.ui.components.specsheet.LocalPlaybackDiagnostics
import com.clayworks.kiln.ui.components.specsheet.LocalPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class KilnHomeScreenNoticeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val track = MediaItem(
        itemId = ItemId("7593"), sourceId = SourceId("local"), kind = MediaItem.Kind.Track,
        title = "2morrow", subtitle = "Artist", durationMs = 251_000L,
    )

    /** An empty library: the Library tab renders with no rows. */
    private class EmptySource : MusicSource {
        override val id = SourceId("local")
        override val displayName = "Test"
        override val capabilities = LocalSourceCapabilities
        override suspend fun search(query: String, limit: Int): Flow<SearchResult> = emptyFlow()
        override suspend fun browse(scope: BrowseScope): Flow<MediaItem> = emptyFlow()
        override suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> =
            Either.Left(SourceError.ItemNotFound(itemId))
    }

    /** Faithful to production: replay = 0, so a notice emitted with no collector is lost. */
    private class FakeDiagnostics : PlaybackDiagnostics {
        override val outputRoute = MutableStateFlow<OutputRoute?>(null)
        override val notices = MutableSharedFlow<PlaybackNotice>(extraBufferCapacity = 8)
    }

    @Test
    fun `a skip notice shows as a snackbar on the Library tab, then clears`() {
        val diagnostics = FakeDiagnostics()
        val player = FakePlatformPlayer(initialItem = track, initialState = PlayerState.Ready(isPlaying = true))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalPlayer provides player,
                LocalPlaybackDiagnostics provides diagnostics,
            ) {
                KilnHomeScreen(
                    musicSource = EmptySource(),
                    player = player,
                    libraryRevision = MutableStateFlow(0L),
                    onOpenSettings = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Library is the default tab, and the notice fires while it's showing.
        composeRule.onNode(hasText("Library") and isSelected()).assertExists()
        // The shell's collector is subscribed, so the replay = 0 emit below can't be dropped.
        assertEquals(1, diagnostics.notices.subscriptionCount.value)

        composeRule.mainClock.autoAdvance = false
        diagnostics.notices.tryEmit(PlaybackNotice.Skipped("Hidden Track", PlayerError.FormatUnsupported("AAC")))
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithText(MESSAGE).assertIsDisplayed()

        // SnackbarDuration.Short is 4 s; past it (plus the exit animation) the snackbar is gone.
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.onNodeWithText(MESSAGE).assertDoesNotExist()
    }

    private companion object {
        const val MESSAGE = "Skipped Hidden Track: Can't play this file: AAC isn't supported yet"
    }
}
