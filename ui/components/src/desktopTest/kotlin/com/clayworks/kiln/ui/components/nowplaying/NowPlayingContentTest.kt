package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SourceId
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

class NowPlayingContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shows_idle_state_when_nothing_playing() {
        composeRule.setContent {
            NowPlayingContent(
                state = NowPlayingState(
                    playerState = PlayerState.Idle,
                    currentItem = null,
                    positionMs = 0L,
                    durationMs = 0L,
                ),
                onPlayPause = {},
                onSeek = {},
                onSkipNext = {},
                onSkipPrevious = {},
            )
        }
        composeRule.onNodeWithText("Nothing playing").assertIsDisplayed()
    }

    @Test
    fun shows_current_track_when_ready() {
        val sample = MediaItem(
            itemId = ItemId("1"),
            sourceId = SourceId("local"),
            kind = MediaItem.Kind.Track,
            title = "In Bloom",
            subtitle = "Nirvana",
        )
        composeRule.setContent {
            NowPlayingContent(
                state = NowPlayingState(
                    playerState = PlayerState.Ready(isPlaying = true),
                    currentItem = sample,
                    positionMs = 30_000L,
                    durationMs = 240_000L,
                ),
                onPlayPause = {},
                onSeek = {},
                onSkipNext = {},
                onSkipPrevious = {},
            )
        }
        composeRule.onNodeWithText("In Bloom").assertIsDisplayed()
        composeRule.onNodeWithText("Nirvana").assertIsDisplayed()
    }

    @Test
    fun play_pause_button_invokes_callback() {
        var toggled = false
        composeRule.setContent {
            NowPlayingContent(
                state = NowPlayingState(
                    playerState = PlayerState.Ready(isPlaying = false),
                    currentItem = MediaItem(
                        itemId = ItemId("1"),
                        sourceId = SourceId("local"),
                        kind = MediaItem.Kind.Track,
                        title = "Foo",
                        subtitle = null,
                    ),
                    positionMs = 0L,
                    durationMs = 100_000L,
                ),
                onPlayPause = { toggled = true },
                onSeek = {},
                onSkipNext = {},
                onSkipPrevious = {},
            )
        }
        composeRule.onNodeWithContentDescription("Play").performClick()
        assertTrue(toggled)
    }
}
