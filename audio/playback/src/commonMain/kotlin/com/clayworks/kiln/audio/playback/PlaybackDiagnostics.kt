// Output-path facts a player publishes through the PlaybackDiagnostics side-channel (#37).
// Kept off PlatformPlayer on purpose (Engine-Swap-Shaped Boundary; Phase 2b plan §12).

package com.clayworks.kiln.audio.playback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class OutputEngine { WasapiShared, JavaSoundFallback }

/** The negotiated output for the current track, as opposed to the file's own format. */
data class OutputRoute(
    val engine: OutputEngine,
    val deviceRateHz: Int?,
    val deviceFormat: String,
    val sourceRateHz: Int,
    val sourceBitDepth: Int,
) {
    val resampled: Boolean get() = deviceRateHz != null && deviceRateHz != sourceRateHz
}

/** Transient playback events worth telling the listener about (shown briefly, never persisted). */
sealed interface PlaybackNotice {
    /** [title] couldn't be played for a per-track reason, so playback moved on. */
    data class Skipped(val title: String, val cause: PlayerError) : PlaybackNotice

    /** Playback stopped after this many unplayable tracks in a row. */
    data class GaveUp(val consecutiveFailures: Int) : PlaybackNotice
}

/** Side-channel for output-path facts and notices. Not part of PlatformPlayer (plan §12). */
interface PlaybackDiagnostics {
    val outputRoute: StateFlow<OutputRoute?>
    val notices: SharedFlow<PlaybackNotice>
}

/** For platforms (Android) and previews with nothing to report. */
object NoPlaybackDiagnostics : PlaybackDiagnostics {
    override val outputRoute: StateFlow<OutputRoute?> = MutableStateFlow(null)
    override val notices: SharedFlow<PlaybackNotice> = MutableSharedFlow()
}
