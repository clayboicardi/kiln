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
    }
}
