// FLAC__Frame + buffer parsing — hot-path for PCM decode.
//
// Two responsibilities:
// 1. Read selected fields (blocksize, sample_number) from the FLAC__FrameHeader
//    at known offsets in the FLAC__Frame struct.
// 2. Extract interleaved PCM from libFLAC's per-channel int32 buffer arrays,
//    packing samples to little-endian bytes sized by streamInfo.bitDepth.
//
// 24-bit packing note: libFLAC delivers each sample as FLAC__int32 (4 bytes
// signed). For 24-bit FLACs only bits [0..23] are valid; bits [24..31] are
// sign-extended from bit 23. Taking the low 3 bytes (LE) preserves the sign
// because the high byte mirrors the sign of bit 23.

package com.clayworks.kiln.audio.playback.nativeio

import com.sun.jna.Native
import com.sun.jna.Pointer

internal object FlacFrameReader {

    // FLAC__FrameHeader field offsets (FLAC/format.h):
    //   0  uint32 blocksize
    //   4  uint32 sample_rate
    //   8  uint32 channels
    //   12 enum   channel_assignment
    //   16 uint32 bits_per_sample
    //   20 enum   number_type (0 = frame_number, 1 = sample_number)
    //   24 union  number (uint32 frame_number OR uint64 sample_number, 8-byte aligned)
    //   32 uint8  crc
    private const val OFFSET_BLOCKSIZE = 0L
    private const val OFFSET_NUMBER_TYPE = 20L
    private const val OFFSET_NUMBER_UNION = 24L

    private const val NUMBER_TYPE_FRAME = 0
    private const val NUMBER_TYPE_SAMPLE = 1

    /**
     * Blocksize of the audio frame at [framePtr] (i.e., the count of samples
     * per channel in this frame). Read from FLAC__FrameHeader.blocksize.
     */
    fun blocksize(framePtr: Pointer): Int = framePtr.getInt(OFFSET_BLOCKSIZE)

    /**
     * Absolute sample number where this frame begins. Resolves the
     * FLAC__FrameHeader.number union per FLAC__FrameHeader.number_type.
     *
     * Fixed-blocksize streams (number_type == 0) carry a frame counter; we
     * multiply by blocksize to derive sample_number. Variable-blocksize streams
     * carry sample_number directly. Returns 0 if number_type is unrecognized.
     */
    fun sampleNumber(framePtr: Pointer, blocksize: Int): Long {
        return when (framePtr.getInt(OFFSET_NUMBER_TYPE)) {
            NUMBER_TYPE_FRAME -> framePtr.getInt(OFFSET_NUMBER_UNION).toLong() * blocksize
            NUMBER_TYPE_SAMPLE -> framePtr.getLong(OFFSET_NUMBER_UNION)
            else -> 0L
        }
    }

    /**
     * Extract interleaved PCM bytes from libFLAC's per-channel int32 buffer.
     *
     * [bufferPtr] is a pointer to `const FLAC__int32 * const buffer[channels]`
     * — an array of [channels] pointers, each addressing [blocksize]
     * consecutive int32 samples for one channel.
     *
     * Output is interleaved: [L0, R0, L1, R1, ...] for stereo, sized by
     * [bitDepth] (16 → 2B per sample, 24 → 3B, 32 → 4B), little-endian, signed.
     */
    fun extractInterleavedPcm(
        bufferPtr: Pointer,
        channels: Int,
        blocksize: Int,
        bitDepth: Int,
    ): ByteArray {
        val bytesPerSample = when (bitDepth) {
            16 -> 2
            24 -> 3
            32 -> 4
            else -> error("Unsupported FLAC bit depth: $bitDepth (expected 16/24/32)")
        }
        val totalBytes = blocksize * channels * bytesPerSample
        val out = ByteArray(totalBytes)

        // Hoist per-channel int32 arrays up-front — one bulk copy from native
        // memory per channel beats per-sample getInt() calls by ~10x at typical
        // blocksizes (4096 samples).
        val pointerSize = Native.POINTER_SIZE.toLong()
        val channelSamples = Array(channels) { ch ->
            val chPtr = bufferPtr.getPointer(ch * pointerSize)
            chPtr.getIntArray(0L, blocksize)
        }

        var outIdx = 0
        when (bytesPerSample) {
            2 -> {
                for (i in 0 until blocksize) {
                    for (ch in 0 until channels) {
                        val s = channelSamples[ch][i]
                        out[outIdx++] = (s and 0xFF).toByte()
                        out[outIdx++] = ((s shr 8) and 0xFF).toByte()
                    }
                }
            }
            3 -> {
                // 24-bit: pack low 3 bytes; sign is preserved because libFLAC
                // sign-extends bit 23 into bits 24..31, so the upper byte is
                // redundant signed information we discard.
                for (i in 0 until blocksize) {
                    for (ch in 0 until channels) {
                        val s = channelSamples[ch][i]
                        out[outIdx++] = (s and 0xFF).toByte()
                        out[outIdx++] = ((s shr 8) and 0xFF).toByte()
                        out[outIdx++] = ((s shr 16) and 0xFF).toByte()
                    }
                }
            }
            4 -> {
                for (i in 0 until blocksize) {
                    for (ch in 0 until channels) {
                        val s = channelSamples[ch][i]
                        out[outIdx++] = (s and 0xFF).toByte()
                        out[outIdx++] = ((s shr 8) and 0xFF).toByte()
                        out[outIdx++] = ((s shr 16) and 0xFF).toByte()
                        out[outIdx++] = ((s shr 24) and 0xFF).toByte()
                    }
                }
            }
        }
        return out
    }
}
