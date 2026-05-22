// LibraryTab — Voyager Tab wrapping LibraryContent. Owns state collection
// from MusicSource.browse(AllTracks). For Track C MVP, fetches the first 500
// tracks via .take(500).toList() inside a LaunchedEffect; real pagination
// lands at Track C2 along with sort/filter UI.
//
// Icon note: Icons.Filled.LibraryMusic ships only in material-icons-extended.
// We depend on material-icons-core (bundled via Compose-MP bundle) — the
// extended pack is intentionally excluded for binary-size reasons. Falling
// back to Icons.AutoMirrored.Filled.List (the non-auto-mirrored Icons.Filled.List
// is deprecated under -Werror; this variant is the recommended replacement
// per the Compose deprecation message). Track C2 polish revisits iconography.

package com.clayworks.kiln.ui.components.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.List),
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
                coroutineScope.launch {
                    player.loadQueue(items = listOf(item), startIndex = 0, autoPlay = true)
                }
            },
        )
    }
}
