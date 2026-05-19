// Skeleton-level smoke test for the JNA libFLAC binding.
// Validates: decoder_new returns a non-null handle, fresh decoder reports
// UNINITIALIZED state (9), delete() returns cleanly. No init_file, no
// callbacks, no decode — just the lifecycle calls.

package com.clayworks.kiln.audio.playback.nativeio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LibFlacBindingTest {

    @Test
    fun `decoder lifecycle — new returns handle, fresh state is UNINITIALIZED, delete is clean`() {
        val flac = LibFlacLoader.load()
        val handle = flac.FLAC__stream_decoder_new()
        assertNotNull(handle, "FLAC__stream_decoder_new should return a non-null Pointer")
        try {
            val state = flac.FLAC__stream_decoder_get_state(handle)
            assertEquals(
                LibFlacBinding.STATE_UNINITIALIZED,
                state,
                "freshly-allocated FLAC__StreamDecoder should be in UNINITIALIZED state (9); was $state",
            )
        } finally {
            flac.FLAC__stream_decoder_delete(handle)
        }
    }

    @Test
    fun `repeat LibFlacLoader load returns the same binding instance`() {
        val first = LibFlacLoader.load()
        val second = LibFlacLoader.load()
        // Reference equality — LibFlacLoader caches the binding singleton.
        assertEquals(first, second, "LibFlacLoader.load() should return cached binding on repeat call")
    }
}
