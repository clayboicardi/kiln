// SearchTab — Voyager Tab wrapping SearchContent. Debounces query input
// (300ms) before calling MusicSource.search; results bounded to 50.

package com.clayworks.kiln.ui.components.search

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.SearchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

class SearchTab(
    private val musicSource: MusicSource,
    private val player: PlatformPlayer,
) : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 2u,
            title = "Search",
            icon = rememberVectorPainter(Icons.Filled.Search),
        )

    @Composable
    override fun Content() {
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(query) {
            if (query.isBlank()) {
                results = emptyList()
                return@LaunchedEffect
            }
            delay(300) // debounce
            results = musicSource.search(query, limit = 50).take(50).toList()
        }

        SearchContent(
            query = query,
            onQueryChange = { query = it },
            results = results,
            onResultClick = { result ->
                // Play-from-here: queue the whole results list, starting at the clicked result,
                // so next/skip walks the results (#28 item 2a). Mirrors LibraryTab.
                val items = results.map { it.item }
                val start = results.indexOfFirst { it.item.itemId == result.item.itemId }.coerceAtLeast(0)
                coroutineScope.launch {
                    player.loadQueue(items = items, startIndex = start, autoPlay = true)
                }
            },
        )
    }
}
