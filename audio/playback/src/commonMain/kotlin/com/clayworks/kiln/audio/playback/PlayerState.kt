// PlayerState sealed interface — exposed via PlatformPlayer.state StateFlow.
// Measurement-mode bits (Phase 3 room correction) deferred until that phase begins.

package com.clayworks.kiln.audio.playback

sealed interface PlayerState {
    data object Idle : PlayerState
    data object Loading : PlayerState
    data class Ready(val isPlaying: Boolean) : PlayerState
    data object Buffering : PlayerState
    data class Error(val cause: PlayerError) : PlayerState
}

sealed interface PlayerError {
    data class DeviceUnavailable(val reason: String) : PlayerError  // USB DAC unplugged
    data class FormatUnsupported(val codec: String) : PlayerError
    data class DecodeFailed(val cause: Throwable) : PlayerError      // libFLAC error, etc.
    data class IoError(val cause: Throwable) : PlayerError
    data class Internal(val message: String) : PlayerError
}
