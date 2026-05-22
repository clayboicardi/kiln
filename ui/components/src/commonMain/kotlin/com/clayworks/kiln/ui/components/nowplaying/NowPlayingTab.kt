// NowPlayingTab — Voyager Tab wrapping NowPlayingContent. Collects
// PlatformPlayer's state / queue / positionMs flows.

package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.audio.playback.PlayerState
import kotlinx.coroutines.launch

class NowPlayingTab(
    private val player: PlatformPlayer,
) : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 1u,
            title = "Now Playing",
            icon = rememberVectorPainter(Icons.Filled.PlayCircle),
        )

    @Composable
    override fun Content() {
        val playerState by player.state.collectAsState()
        val queue by player.queue.collectAsState()
        val positionMs by player.positionMs.collectAsState()
        val coroutineScope = rememberCoroutineScope()

        val state = NowPlayingState(
            playerState = playerState,
            currentItem = queue.currentItem,
            positionMs = positionMs,
            durationMs = queue.currentItem?.durationMs ?: 0L,
        )

        NowPlayingContent(
            state = state,
            onPlayPause = {
                coroutineScope.launch {
                    val isPlaying = (playerState as? PlayerState.Ready)?.isPlaying == true
                    if (isPlaying) player.pause() else player.play()
                }
            },
            onSeek = { positionMs ->
                coroutineScope.launch { player.seekTo(positionMs) }
            },
            onSkipNext = {
                coroutineScope.launch { player.skipToNext() }
            },
            onSkipPrevious = {
                coroutineScope.launch { player.skipToPrevious() }
            },
        )
    }
}
