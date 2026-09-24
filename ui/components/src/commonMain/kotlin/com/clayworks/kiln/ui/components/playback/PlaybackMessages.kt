// User-facing wording for playback errors, skip notices, and the output-path readout (#37).
// Pure functions, shared by Now Playing and the Spec Sheet.

package com.clayworks.kiln.ui.components.playback

import com.clayworks.kiln.audio.playback.OutputEngine
import com.clayworks.kiln.audio.playback.OutputRoute
import com.clayworks.kiln.audio.playback.PlaybackNotice
import com.clayworks.kiln.audio.playback.PlayerError

fun playerErrorMessage(error: PlayerError): String = when (error) {
    is PlayerError.FormatUnsupported -> "Can't play this file: ${error.codec} isn't supported yet"
    is PlayerError.OutputFormatUnsupported ->
        "The output can't take ${error.bitDepth}-bit / ${khz(error.sampleRateHz)} kHz ${channelsWord(error.channels)}"
    is PlayerError.DeviceUnavailable -> "Output device unavailable: ${error.reason}"
    is PlayerError.DecodeFailed -> "Couldn't decode this file"
    is PlayerError.IoError -> "Couldn't read this file"
    is PlayerError.Internal -> "Playback error: ${error.message}"
}

fun noticeMessage(notice: PlaybackNotice): String = when (notice) {
    is PlaybackNotice.Skipped -> "Skipped ${notice.title}: ${playerErrorMessage(notice.cause)}"
    is PlaybackNotice.GaveUp -> "Stopped after ${notice.consecutiveFailures} unplayable tracks in a row"
}

fun formatOutputRoute(route: OutputRoute): String = buildString {
    append("Output · ")
    append(
        when (route.engine) {
            OutputEngine.WasapiShared -> "WASAPI shared"
            OutputEngine.JavaSoundFallback -> "Java Sound fallback"
        },
    )
    append(" · ")
    route.deviceRateHz?.let { append("${khz(it)} kHz ") }
    append(route.deviceFormat)
    if (route.resampled) append(" · resampled from ${khz(route.sourceRateHz)} kHz")
}

/** 44100 → "44.1", 48000 → "48", 176400 → "176.4". Pure Kotlin (no String.format). */
private fun khz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else "${hz / 1000}.${(hz % 1000) / 100}"

private fun channelsWord(channels: Int): String = when (channels) {
    1 -> "mono"
    2 -> "stereo"
    else -> "$channels-channel"
}
