// Output-path facts a player publishes through the PlaybackDiagnostics side-channel (#37).
// Kept off PlatformPlayer on purpose (Engine-Swap-Shaped Boundary; Phase 2b plan §12).

package com.clayworks.kiln.audio.playback

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
