// NowPlayingTab — Voyager Tab wrapping the now-playing UI. Hosts an embedded
// Voyager Navigator whose initial screen is NowPlayingHomeScreen (collects
// PlatformPlayer's state / queue / positionMs flows + renders NowPlayingContent).
// Tapping the track title pushes SpecSheetScreen(trackId) onto the inner
// Navigator stack.
//
// Phase 2b-prereq: introduces the inner Navigator so Stream A can routably
// land SpecSheetScreen without rebuilding NowPlayingTab plumbing.
// See docs/superpowers/plans/2026-05-23-phase-2b-plan.md §6.1.

package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.ui.components.specsheet.SpecSheetScreen
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
        Navigator(NowPlayingHomeScreen(player))
    }
}

/**
 * Root screen of the NowPlayingTab's inner Navigator. Owns the player-flow
 * collection + transport wiring. Tap-title pushes [SpecSheetScreen] onto
 * the parent [Navigator] stack via [LocalNavigator].
 */
class NowPlayingHomeScreen(
    private val player: PlatformPlayer,
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
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
            onTitleClick = { trackId ->
                navigator.push(SpecSheetScreen(trackId))
            },
        )
    }
}
