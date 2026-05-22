package com.clayworks.kiln.ui.components.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SourceId
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shows_empty_state_when_no_tracks() {
        composeRule.setContent {
            LibraryContent(
                tracks = emptyList(),
                onTrackClick = {},
            )
        }
        composeRule.onNodeWithText("No tracks. Run a Library scan from Settings.")
            .assertIsDisplayed()
    }

    @Test
    fun renders_track_titles_and_subtitles() {
        composeRule.setContent {
            LibraryContent(
                tracks = listOf(
                    MediaItem(
                        itemId = ItemId("1"),
                        sourceId = SourceId("local"),
                        kind = MediaItem.Kind.Track,
                        title = "Smells Like Teen Spirit",
                        subtitle = "Nirvana — Nevermind",
                    ),
                    MediaItem(
                        itemId = ItemId("2"),
                        sourceId = SourceId("local"),
                        kind = MediaItem.Kind.Track,
                        title = "Come As You Are",
                        subtitle = "Nirvana — Nevermind",
                    ),
                ),
                onTrackClick = {},
            )
        }
        composeRule.onNodeWithText("Smells Like Teen Spirit").assertIsDisplayed()
        composeRule.onNodeWithText("Come As You Are").assertIsDisplayed()
    }

    @Test
    fun track_click_invokes_callback() {
        var clicked: MediaItem? = null
        val sample = MediaItem(
            itemId = ItemId("42"),
            sourceId = SourceId("local"),
            kind = MediaItem.Kind.Track,
            title = "Clicked Track",
            subtitle = "Artist",
        )
        composeRule.setContent {
            LibraryContent(
                tracks = listOf(sample),
                onTrackClick = { clicked = it },
            )
        }
        composeRule.onNodeWithText("Clicked Track").performClick()
        assertEquals(sample, clicked)
    }
}
