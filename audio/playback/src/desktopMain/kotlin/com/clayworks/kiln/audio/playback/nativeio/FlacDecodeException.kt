// Thrown by JvmFlacDecodedStream.frames when libFLAC enters a terminal error
// state OR when process_single returns false. JavaSoundPlayerImpl's playback
// loop catches this (along with any other Throwable from the Flow) and surfaces
// it as PlayerState.Error(PlayerError.DecodeFailed).
//
// `decoderState` is the libFLAC FLAC__StreamDecoderState integer captured at
// the point of failure — useful for diagnostics in logs and (eventually) for
// UI mapping to user-meaningful error messages.

package com.clayworks.kiln.audio.playback.nativeio

internal class FlacDecodeException(
    message: String,
    val decoderState: Int,
) : RuntimeException(message)
