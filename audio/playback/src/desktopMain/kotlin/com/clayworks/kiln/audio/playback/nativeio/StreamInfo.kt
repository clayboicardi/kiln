// Internal Kotlin-side representation of libFLAC's STREAMINFO metadata block.
// Captured during process_until_end_of_metadata via the MetadataCallback.

package com.clayworks.kiln.audio.playback.nativeio

import com.sun.jna.Pointer

/**
 * STREAMINFO block fields extracted from a FLAC__StreamMetadata pointer.
 * `totalSamples == 0` is libFLAC's "unknown" sentinel (per spec) — callers
 * fall back to file-size estimation or skip seek bookkeeping.
 */
internal data class StreamInfo(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int,
    val totalSamples: Long,
    val minBlocksize: Int,
    val maxBlocksize: Int,
)

internal object FlacMetadata {
    const val TYPE_STREAMINFO = 0
    const val TYPE_PADDING = 1
    const val TYPE_APPLICATION = 2
    const val TYPE_SEEKTABLE = 3
    const val TYPE_VORBIS_COMMENT = 4
    const val TYPE_CUESHEET = 5
    const val TYPE_PICTURE = 6
    const val TYPE_UNDEFINED = 7

    /**
     * Offset within FLAC__StreamMetadata where the union `data` field begins.
     * Header layout: type(4) + is_last(4) + length(4) = 12, then 4 bytes
     * padding because the union's largest natural alignment is 8 (uint64
     * total_samples in stream_info). Result: data starts at offset 16.
     */
    private const val UNION_DATA_OFFSET = 16L

    /**
     * Parse a FLAC__StreamMetadata pointer into [StreamInfo] if and only if
     * the block is a STREAMINFO type. Returns null for other types
     * (PADDING / VORBIS_COMMENT / PICTURE / etc.).
     */
    fun parseStreamInfo(metadataPtr: Pointer): StreamInfo? {
        val type = metadataPtr.getInt(0L)
        if (type != TYPE_STREAMINFO) return null
        val streamInfoPtr = metadataPtr.share(UNION_DATA_OFFSET)
        val si = FlacStreamMetadataStreamInfo(streamInfoPtr)
        return StreamInfo(
            sampleRateHz = si.sample_rate,
            bitDepth = si.bits_per_sample,
            channels = si.channels,
            totalSamples = si.total_samples,
            minBlocksize = si.min_blocksize,
            maxBlocksize = si.max_blocksize,
        )
    }
}
