// Audio frame + format types. Lives in :audio:dsp per Concentric Modules —
// platform-free Kotlin types used by both :audio:playback and :audio:dsp.
//
// Originally lived in :audio:playback because PlatformPlayer references them;
// moved here in D-B (Session 15) so that :audio:dsp AudioProcessor impls
// (the canonical home per spec §3.4) can implement against the types without
// a dep cycle.

package com.clayworks.kiln.audio.dsp

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
