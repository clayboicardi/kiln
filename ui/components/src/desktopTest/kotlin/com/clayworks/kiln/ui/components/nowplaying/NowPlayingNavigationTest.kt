// NowPlayingNavigationTest — Voyager Navigator scaffold inside NowPlayingTab.
// Verifies tap-title pushes SpecSheetScreen, and back-button pops to the home
// screen. Placeholder SpecSheetScreen body: "Spec sheet for <trackId>".
//
// Phase 2b-prereq mitigation for falsify F17: Stream A presumes Now Playing
// exists at fidelity required for navigation.

package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clayworks.kiln.audio.dsp.AudioProcessor
import com.clayworks.kiln.audio.playback.MeasurementSession
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.audio.playback.QueueState
import com.clayworks.kiln.audio.playback.RepeatMode
import com.clayworks.kiln.audio.playback.VolumeState
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import kotlin.test.Test

class NowPlayingNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleItem = MediaItem(
        itemId = ItemId("42"),
        sourceId = SourceId("local"),
        kind = MediaItem.Kind.Track,
        title = "In Bloom",
        subtitle = "Nirvana",
        durationMs = 240_000L,
    )

    @Test
    fun tap_title_pushes_spec_sheet_and_back_pops_to_home() {
        val player = FakePlatformPlayer(
            initialItem = sampleItem,
            initialState = PlayerState.Ready(isPlaying = false),
        )

        composeRule.setContent {
            NowPlayingTab(player).Content()
        }

        // Initial state: home screen renders title; spec sheet placeholder absent.
        composeRule.onNodeWithText("In Bloom").assertIsDisplayed()
        composeRule.onNodeWithText("Spec sheet for 42").assertDoesNotExist()

        // Tap the title — pushes SpecSheetScreen(trackId="42").
        composeRule.onNodeWithText("In Bloom").performClick()
        composeRule.onNodeWithText("Spec sheet for 42").assertIsDisplayed()

        // Press back affordance — pops to home screen.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("In Bloom").assertIsDisplayed()
        composeRule.onNodeWithText("Spec sheet for 42").assertDoesNotExist()
    }
}

/**
 * Minimal in-memory [PlatformPlayer] stub for navigation-only assertions.
 * Only the StateFlow surface is meaningful; transport methods are no-ops.
 */
private class FakePlatformPlayer(
    initialItem: MediaItem,
    initialState: PlayerState,
) : PlatformPlayer {
    private val _state = MutableStateFlow(initialState)
    private val _queue = MutableStateFlow(
        QueueState(
            items = listOf(initialItem),
            currentIndex = 0,
            repeatMode = RepeatMode.Off,
            shuffleEnabled = false,
        ),
    )
    private val _positionMs = MutableStateFlow(0L)
    private val _volume = MutableStateFlow(VolumeState(linear = 1.0f, muted = false))
    private val _processors = MutableStateFlow<List<AudioProcessor>>(emptyList())

    override val state: StateFlow<PlayerState> = _state
    override val queue: StateFlow<QueueState> = _queue
    override val positionMs: StateFlow<Long> = _positionMs
    override val volume: StateFlow<VolumeState> = _volume
    override val processors: StateFlow<List<AudioProcessor>> = _processors

    override suspend fun loadQueue(
        items: List<MediaItem>,
        startIndex: Int,
        autoPlay: Boolean,
    ) {
    }
    override suspend fun play() {}
    override suspend fun pause() {}
    override suspend fun stop() {}
    override suspend fun seekTo(positionMs: Long) {}
    override suspend fun skipToNext() {}
    override suspend fun skipToPrevious() {}
    override suspend fun skipTo(queueIndex: Int) {}
    override suspend fun setRepeatMode(mode: RepeatMode) {}
    override suspend fun setShuffleMode(enabled: Boolean) {}
    override suspend fun setVolume(linear: Float) {}
    override suspend fun setMuted(muted: Boolean) {}
    override fun addAudioProcessor(processor: AudioProcessor) {}
    override fun removeAudioProcessor(processor: AudioProcessor) {}
    override suspend fun release() {}
    override suspend fun enterMeasurementMode(): MeasurementSession? = null
}
