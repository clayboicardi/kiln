// LibraryTab — Voyager Tab wrapping LibraryContent. Owns state collection
// from MusicSource.browse(AllTracks). For Track C MVP, fetches the first 500
// tracks via .take(500).toList() inside a LaunchedEffect; real pagination
// lands at Track C2 along with sort/filter UI.

package com.clayworks.kiln.ui.components.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
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
import com.clayworks.kiln.library.source.BrowseScope
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

class LibraryTab(
    private val musicSource: MusicSource,
    private val player: PlatformPlayer,
) : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 0u,
            title = "Library",
            icon = rememberVectorPainter(Icons.Filled.LibraryMusic),
        )

    @Composable
    override fun Content() {
        var tracks by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            tracks = musicSource.browse(BrowseScope.AllTracks(pageSize = 500, pageOffset = 0))
                .take(500)
                .toList()
        }

        LibraryContent(
            tracks = tracks,
            onTrackClick = { item ->
                // Play-from-here: the queue is the whole loaded list, starting at the clicked
                // track, so next/skip walks the list (#28 item 2a). The single-item queue this
                // replaced made skipToNext a no-op (nextIndexOrNull returned null).
                val start = tracks.indexOf(item).coerceAtLeast(0)
                coroutineScope.launch {
                    player.loadQueue(items = tracks, startIndex = start, autoPlay = true)
                }
            },
        )
    }
}
