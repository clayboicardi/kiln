package com.clayworks.kiln.ui.components.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SearchResult
import com.clayworks.kiln.library.source.SourceId
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shows_prompt_when_query_empty() {
        composeRule.setContent {
            SearchContent(
                query = "",
                onQueryChange = {},
                results = emptyList(),
                onResultClick = {},
            )
        }
        composeRule.onNodeWithText("Search your library").assertIsDisplayed()
    }

    @Test
    fun typing_invokes_onQueryChange() {
        var captured = ""
        composeRule.setContent {
            SearchContent(
                query = captured,
                onQueryChange = { captured = it },
                results = emptyList(),
                onResultClick = {},
            )
        }
        composeRule.onNodeWithText("Search your library").performClick()
        composeRule.onNodeWithText("Search your library").performTextInput("nirvana")
        assertEquals("nirvana", captured)
    }

    @Test
    fun renders_search_results() {
        composeRule.setContent {
            SearchContent(
                query = "nirvana",
                onQueryChange = {},
                results = listOf(
                    SearchResult(
                        item = MediaItem(
                            itemId = ItemId("1"),
                            sourceId = SourceId("local"),
                            kind = MediaItem.Kind.Track,
                            title = "Smells Like Teen Spirit",
                            subtitle = "Nirvana",
                        ),
                    ),
                    SearchResult(
                        item = MediaItem(
                            itemId = ItemId("2"),
                            sourceId = SourceId("local"),
                            kind = MediaItem.Kind.Track,
                            title = "Come As You Are",
                            subtitle = "Nirvana",
                        ),
                    ),
                ),
                onResultClick = {},
            )
        }
        composeRule.onNodeWithText("Smells Like Teen Spirit").assertIsDisplayed()
        composeRule.onNodeWithText("Come As You Are").assertIsDisplayed()
    }
}
