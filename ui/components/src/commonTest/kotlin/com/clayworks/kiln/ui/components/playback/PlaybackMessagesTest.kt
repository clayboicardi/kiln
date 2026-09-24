package com.clayworks.kiln.ui.components.playback

import com.clayworks.kiln.audio.playback.OutputEngine
import com.clayworks.kiln.audio.playback.OutputRoute
import com.clayworks.kiln.audio.playback.PlaybackNotice
import com.clayworks.kiln.audio.playback.PlayerError
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackMessagesTest {

    @Test
    fun `WASAPI route that resamples`() {
        val r = OutputRoute(OutputEngine.WasapiShared, 48_000, "32-bit float mix", 44_100, 24)
        assertEquals("Output · WASAPI shared · 48 kHz 32-bit float mix · resampled from 44.1 kHz", formatOutputRoute(r))
    }

    @Test
    fun `WASAPI route at the native rate`() {
        val r = OutputRoute(OutputEngine.WasapiShared, 48_000, "32-bit float mix", 48_000, 16)
        assertEquals("Output · WASAPI shared · 48 kHz 32-bit float mix", formatOutputRoute(r))
    }

    @Test
    fun `Java Sound fallback route`() {
        // Java Sound can't know the Windows mix rate, so its route has no device rate (R7).
        val r = OutputRoute(OutputEngine.JavaSoundFallback, null, "16-bit PCM", 44_100, 16)
        assertEquals("Output · Java Sound fallback · 16-bit PCM", formatOutputRoute(r))
    }

    @Test
    fun `rates format without trailing zeros`() {
        val r = OutputRoute(OutputEngine.WasapiShared, 176_400, "32-bit float mix", 88_200, 24)
        assertEquals("Output · WASAPI shared · 176.4 kHz 32-bit float mix · resampled from 88.2 kHz", formatOutputRoute(r))
    }

    @Test
    fun `output format rejection names the layout`() {
        assertEquals(
            "The output can't take 24-bit / 44.1 kHz stereo",
            playerErrorMessage(PlayerError.OutputFormatUnsupported(44_100, 24, 2, "x")),
        )
    }

    @Test
    fun `codec rejection names the codec`() {
        assertEquals("Can't play this file: AAC isn't supported yet", playerErrorMessage(PlayerError.FormatUnsupported("AAC")))
    }

    @Test
    fun `device error keeps its reason`() {
        assertEquals("Output device unavailable: device in use", playerErrorMessage(PlayerError.DeviceUnavailable("device in use")))
    }

    @Test
    fun `notices`() {
        assertEquals(
            "Skipped 2morrow: Can't play this file: AAC isn't supported yet",
            noticeMessage(PlaybackNotice.Skipped("2morrow", PlayerError.FormatUnsupported("AAC"))),
        )
        assertEquals("Stopped after 3 unplayable tracks in a row", noticeMessage(PlaybackNotice.GaveUp(3)))
    }
}
