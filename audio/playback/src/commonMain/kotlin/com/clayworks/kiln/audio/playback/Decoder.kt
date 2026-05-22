// Decoder + DecodedStream interfaces — the FLAC/WAV/MP3 abstraction.
// Per vetting Item 9 addendum: Desktop FLAC = JNA bridge to vendored
// Xiph libFLAC 1.5.0 (lives in jvmMain at MVP Session 4-7).
// Android FLAC = Media3 ExoPlayer's internal decoder.
//
// AudioFrame, DecodedAudioFormat, SampleFormat live in :audio:dsp
// (moved D-B Session 15 — Concentric Modules invariant per spec §3.4).

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.flow.Flow

interface Decoder {
    /**
     * Open a decoder for the given playable. Returned [DecodedStream] is a
     * resource — caller must invoke [DecodedStream.close] (use .use{}).
     */
    suspend fun open(playable: Playable): Either<DecoderError, DecodedStream>

    /** Return true if this decoder can handle the given codec on the current platform. */
    fun supports(codec: AudioCodec): Boolean
}

interface DecodedStream : AutoCloseable {
    val format: DecodedAudioFormat
    val frames: Flow<AudioFrame>
    val positionMs: Long
    val durationMs: Long

    suspend fun seekTo(positionMs: Long)

    override fun close()
}

sealed interface DecoderError {
    data class UnsupportedCodec(val codec: AudioCodec) : DecoderError
    data class CorruptStream(val message: String) : DecoderError
    data class IoError(val cause: Throwable) : DecoderError
    data class NativeBindingFailed(val message: String) : DecoderError  // libFLAC load/init
    data class Internal(val message: String) : DecoderError
}
