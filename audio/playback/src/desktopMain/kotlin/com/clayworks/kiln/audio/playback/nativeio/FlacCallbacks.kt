// JNA Callback interfaces matching libFLAC's stream decoder callback typedefs
// from include/FLAC/stream_decoder.h.
//
// IMPORTANT: callers MUST hold strong references to the callback instances for
// the entire lifetime of the decoder. If a callback object is garbage-collected
// while libFLAC still holds a function pointer to it, the next callback
// invocation will crash the JVM. JvmFlacDecodedStream owns the callback fields.

package com.clayworks.kiln.audio.playback.nativeio

import com.sun.jna.Callback
import com.sun.jna.Pointer

/**
 * `FLAC__StreamDecoderWriteCallback` — invoked once per decoded audio frame.
 * This is the hot path.
 *
 * Parameters:
 * - `decoder`: the FLAC__StreamDecoder handle (opaque pointer)
 * - `frame`: pointer to FLAC__Frame (header + subframes + footer). The header
 *   carries blocksize/sample_rate/channels/bits_per_sample/sample_number.
 * - `buffer`: pointer to `const FLAC__int32 * const buffer[]` — an array of
 *   `channels` pointers, each pointing to `blocksize` consecutive 32-bit
 *   signed samples (one channel's worth). Note: libFLAC always delivers
 *   samples as int32 regardless of source bit depth — callers downcast to
 *   16-bit / pack to 24-bit / sign-extend to 32-bit as appropriate.
 * - `clientData`: opaque pointer registered at init_file; typically null.
 *
 * Return: `FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE` (0) to keep decoding,
 * or `FLAC__STREAM_DECODER_WRITE_STATUS_ABORT` (1) to bail.
 */
internal interface WriteCallback : Callback {
    fun invoke(decoder: Pointer, frame: Pointer, buffer: Pointer, clientData: Pointer?): Int

    companion object {
        const val STATUS_CONTINUE = 0
        const val STATUS_ABORT = 1
    }
}

/**
 * `FLAC__StreamDecoderMetadataCallback` — invoked for each metadata block
 * during init_file's metadata sweep + during process_until_end_of_metadata().
 * STREAMINFO is always delivered; other block types (VORBIS_COMMENT, PICTURE,
 * etc.) are delivered only if the decoder is configured to respond to them
 * via set_metadata_respond / set_metadata_respond_all.
 *
 * Parameters:
 * - `decoder`: FLAC__StreamDecoder handle
 * - `metadata`: pointer to FLAC__StreamMetadata struct. First field is the
 *   block type (int) at offset 0. STREAMINFO is type 0.
 * - `clientData`: typically null.
 */
internal interface MetadataCallback : Callback {
    fun invoke(decoder: Pointer, metadata: Pointer, clientData: Pointer?)
}

/**
 * `FLAC__StreamDecoderErrorCallback` — invoked on stream errors. Decoding
 * typically continues unless the error callback's caller decides otherwise.
 *
 * Parameters:
 * - `decoder`: FLAC__StreamDecoder handle
 * - `status`: one of the ERROR_STATUS_* constants below
 * - `clientData`: typically null.
 */
internal interface ErrorCallback : Callback {
    fun invoke(decoder: Pointer, status: Int, clientData: Pointer?)

    companion object {
        const val ERROR_STATUS_LOST_SYNC = 0
        const val ERROR_STATUS_BAD_HEADER = 1
        const val ERROR_STATUS_FRAME_CRC_MISMATCH = 2
        const val ERROR_STATUS_UNPARSEABLE_STREAM = 3
        const val ERROR_STATUS_BAD_METADATA = 4
        const val ERROR_STATUS_OUT_OF_BOUNDS = 5
        const val ERROR_STATUS_MISSING_FRAME = 6
    }
}
