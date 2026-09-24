// Now Playing shows the error line and the output readout (#37). Skip notices are an app-shell
// Snackbar (R20), covered by KilnHomeScreenNoticeTest.

package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.clayworks.kiln.audio.playback.OutputEngine
import com.clayworks.kiln.audio.playback.OutputRoute
import com.clayworks.kiln.audio.playback.PlaybackDiagnostics
import com.clayworks.kiln.audio.playback.PlaybackNotice
import com.clayworks.kiln.audio.playback.PlayerError
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SourceId
import com.clayworks.kiln.ui.components.specsheet.LocalPlaybackDiagnostics
import com.clayworks.kiln.ui.components.specsheet.LocalPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import kotlin.test.Test

class NowPlayingDiagnosticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val track = MediaItem(
        itemId = ItemId("7593"), sourceId = SourceId("local"), kind = MediaItem.Kind.Track,
        title = "2morrow", subtitle = "Artist", durationMs = 251_000L,
    )

    private class FakeDiagnostics(route: OutputRoute?) : PlaybackDiagnostics {
        override val outputRoute = MutableStateFlow(route)
        override val notices = MutableSharedFlow<PlaybackNotice>()
    }

    private fun show(state: PlayerState, diagnostics: FakeDiagnostics) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalPlayer provides FakePlatformPlayer(initialItem = track, initialState = state),
                LocalPlaybackDiagnostics provides diagnostics,
            ) {
                NowPlayingTab().Content()
            }
        }
    }

    @Test
    fun `an error state shows its message`() {
        show(PlayerState.Error(PlayerError.OutputFormatUnsupported(44_100, 24, 2, "x")), FakeDiagnostics(null))
        composeRule.onNodeWithText("The output can't take 24-bit / 44.1 kHz stereo").assertIsDisplayed()
    }

    @Test
    fun `the output route is shown while playing`() {
        val route = OutputRoute(OutputEngine.WasapiShared, 48_000, "32-bit float mix", 44_100, 24)
        show(PlayerState.Ready(isPlaying = true), FakeDiagnostics(route))
        composeRule.onNodeWithText("Output · WASAPI shared · 48 kHz 32-bit float mix · resampled from 44.1 kHz").assertIsDisplayed()
    }
}
