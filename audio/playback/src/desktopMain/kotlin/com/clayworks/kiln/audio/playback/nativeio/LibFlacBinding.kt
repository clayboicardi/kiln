// JNA bridge to vendored Xiph libFLAC 1.5.0 — stream decoder C API.
//
// The Kotlin method names below MUST match the C export symbols in libFLAC.dll
// exactly (case-sensitive, double-underscore included), because JNA resolves
// bindings by reflection on the function name. Adding a function: copy the C
// signature from FLAC/stream_decoder.h and translate types per the JNA mapping
// table (https://java-native-access.github.io/jna/5.14.0/javadoc/overview-summary.html#marshalling).
//
// This file is the **skeleton** landed at H6.3 — just decoder lifecycle and
// state query. Callbacks (write/metadata/error) and init_file land at H6.4.
// Process functions land at H6.5/H6.6. Seek + finish at H6.7.

package com.clayworks.kiln.audio.playback.nativeio

import com.sun.jna.Library
import com.sun.jna.Pointer

internal interface LibFlacBinding : Library {

    /**
     * Allocate a new FLAC stream decoder. Returns null on allocation failure.
     * The returned handle must be released via [FLAC__stream_decoder_delete].
     * A fresh decoder is in state [STATE_UNINITIALIZED] until init_file is called.
     */
    fun FLAC__stream_decoder_new(): Pointer?

    /**
     * Free the stream decoder. Idempotent + null-safe in libFLAC's C API. After
     * delete the handle is invalid — do not pass it to any other libFLAC function.
     */
    fun FLAC__stream_decoder_delete(handle: Pointer)

    /**
     * Return the current decoder state (one of the STATE_* constants below).
     * For a freshly-allocated decoder this returns [STATE_UNINITIALIZED] (9).
     */
    fun FLAC__stream_decoder_get_state(handle: Pointer): Int

    /**
     * Initialize the decoder for a FLAC file at [filename]. Callers must hold
     * strong references to the three callbacks for the decoder's lifetime —
     * libFLAC stores them as raw function pointers; GC of the callback objects
     * while a callback is in flight will crash the JVM.
     *
     * Returns one of the INIT_STATUS_* constants. 0 = OK; non-zero = init failure.
     */
    fun FLAC__stream_decoder_init_file(
        handle: Pointer,
        filename: String,
        writeCallback: WriteCallback,
        metadataCallback: MetadataCallback,
        errorCallback: ErrorCallback,
        clientData: Pointer?,
    ): Int

    /**
     * Process all metadata blocks (firing the metadata callback for STREAMINFO
     * + any other configured types) until the first audio frame. Returns true
     * on success; false if the decoder aborted.
     */
    fun FLAC__stream_decoder_process_until_end_of_metadata(handle: Pointer): Boolean

    /**
     * Process one metadata block OR one audio frame. Returns true on success;
     * false on error. Use the get_state value afterward to detect EOS. The
     * write/metadata/error callbacks fire from inside this call on the calling
     * thread — single-thread invariant.
     */
    fun FLAC__stream_decoder_process_single(handle: Pointer): Boolean

    /**
     * Seek to absolute sample position [sample] (0-based, per-channel sample
     * frame count). Valid only in states SEARCH_FOR_FRAME_SYNC and READ_FRAME
     * (i.e., after process_until_end_of_metadata). Returns true on success;
     * false if the seek failed (state may flip to SEEK_ERROR).
     */
    fun FLAC__stream_decoder_seek_absolute(handle: Pointer, sample: Long): Boolean

    /**
     * Reset the decoder for re-use (close the file, free internal buffers).
     * After finish() the decoder can be re-init'd with another file. Returns
     * true on success.
     */
    fun FLAC__stream_decoder_finish(handle: Pointer): Boolean

    companion object {
        // FLAC__StreamDecoderState enum from include/FLAC/stream_decoder.h.
        // libFLAC 1.5.0 (Feb 2025) — values stable across the 1.x series.
        const val STATE_SEARCH_FOR_METADATA = 0
        const val STATE_READ_METADATA = 1
        const val STATE_SEARCH_FOR_FRAME_SYNC = 2
        const val STATE_READ_FRAME = 3
        const val STATE_END_OF_STREAM = 4
        const val STATE_OGG_ERROR = 5
        const val STATE_SEEK_ERROR = 6
        const val STATE_ABORTED = 7
        const val STATE_MEMORY_ALLOCATION_ERROR = 8
        const val STATE_UNINITIALIZED = 9

        // FLAC__StreamDecoderInitStatus enum from stream_decoder.h.
        const val INIT_STATUS_OK = 0
        const val INIT_STATUS_UNSUPPORTED_CONTAINER = 1
        const val INIT_STATUS_INVALID_CALLBACKS = 2
        const val INIT_STATUS_MEMORY_ALLOCATION_ERROR = 3
        const val INIT_STATUS_ERROR_OPENING_FILE = 4
        const val INIT_STATUS_ALREADY_INITIALIZED = 5
    }
}
