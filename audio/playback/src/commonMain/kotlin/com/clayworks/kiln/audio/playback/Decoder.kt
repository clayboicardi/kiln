// Decoder + DecodedStream interfaces — the FLAC/WAV/MP3 abstraction.
// Per vetting Item 9 addendum: Desktop FLAC = JNA bridge to vendored
// Xiph libFLAC 1.5.0 (lives in jvmMain at MVP Session 4-7).
// Android FLAC = Media3 ExoPlayer's internal decoder.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
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

data class DecodedAudioFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int,
    val sampleFormat: SampleFormat,
)

enum class SampleFormat {
    PCM_S16_LE,
    PCM_S24_LE,
    PCM_S32_LE,
    PCM_F32_LE,
}

/**
 * One chunk of decoded audio. `bytes` is interleaved PCM; `byteCount` is the
 * valid byte count (≤ bytes.size — reusing a buffer pool is common).
 */
data class AudioFrame(
    val bytes: ByteArray,
    val byteCount: Int,
    val sampleCount: Int,
    val timestampMs: Long,
) {
    /**
     * Custom equals + hashCode required because the Kotlin data class default
     * uses REFERENCE equality on the ByteArray field — two AudioFrames with
     * logically-identical bytes would otherwise compare unequal.
     *
     * The byte comparison is bounded by [byteCount] rather than `bytes.size`
     * so future buffer-pool reuse (where pooled slots may carry stale trailing
     * bytes past byteCount) does not produce false negatives between two
     * frames that are logically equal.
     *
     * Performance characteristic: equals is O(byteCount); hashCode is also
     * O(byteCount). Typical frame is ~16 KB (24-bit stereo @ 4096 blocksize).
     * **DO NOT use AudioFrame as a StateFlow value, HashSet element, or Map
     * key.** Equality / hashing fires at decode rate (~40-50 Hz) — placing
     * AudioFrame in any container that dedup-checks via equals will produce
     * per-frame CPU cost that compounds on a hot audio path.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return byteCount == other.byteCount &&
            sampleCount == other.sampleCount &&
            timestampMs == other.timestampMs &&
            byteArrayPrefixEquals(bytes, other.bytes, byteCount)
    }

    override fun hashCode(): Int {
        var result = 1
        for (i in 0 until byteCount) {
            result = 31 * result + bytes[i].toInt()
        }
        result = 31 * result + byteCount
        result = 31 * result + sampleCount
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/**
 * Compare the first [length] bytes of two ByteArrays. Returns true iff both
 * arrays contain at least [length] bytes AND those bytes are byte-for-byte
 * equal. Used by [AudioFrame.equals] to bound the comparison to the valid
 * `byteCount` prefix.
 */
private fun byteArrayPrefixEquals(a: ByteArray, b: ByteArray, length: Int): Boolean {
    if (length < 0 || a.size < length || b.size < length) return false
    for (i in 0 until length) {
        if (a[i] != b[i]) return false
    }
    return true
}

sealed interface DecoderError {
    data class UnsupportedCodec(val codec: AudioCodec) : DecoderError
    data class CorruptStream(val message: String) : DecoderError
    data class IoError(val cause: Throwable) : DecoderError
    data class NativeBindingFailed(val message: String) : DecoderError  // libFLAC load/init
    data class Internal(val message: String) : DecoderError
}
