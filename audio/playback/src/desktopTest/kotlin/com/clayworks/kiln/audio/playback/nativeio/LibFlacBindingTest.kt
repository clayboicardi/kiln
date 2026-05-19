// Lifecycle + init_file + metadata-callback smoke tests for the JNA libFLAC
// binding. Skeleton checks live alongside the callback-fires checks; both run
// from the same desktopTest JVM, exercising the bridge progressively.

package com.clayworks.kiln.audio.playback.nativeio

import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibFlacBindingTest {

    private fun fixturePath(name: String): String {
        val url = LibFlacBindingTest::class.java.getResource("/fixtures/$name")
            ?: error("Missing test fixture: /fixtures/$name (expected under desktopTest/resources/fixtures/)")
        return File(url.toURI()).absolutePath
    }

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

    @Test
    fun `init_file with non-existent path returns ERROR_OPENING_FILE`() {
        val flac = LibFlacLoader.load()
        val handle = flac.FLAC__stream_decoder_new()
            ?: error("decoder_new returned null")
        val write = object : WriteCallback {
            override fun invoke(decoder: Pointer, frame: Pointer, buffer: Pointer, clientData: Pointer?): Int =
                WriteCallback.STATUS_CONTINUE
        }
        val metadata = object : MetadataCallback {
            override fun invoke(decoder: Pointer, metadata: Pointer, clientData: Pointer?) = Unit
        }
        val error = object : ErrorCallback {
            override fun invoke(decoder: Pointer, status: Int, clientData: Pointer?) = Unit
        }
        try {
            val status = flac.FLAC__stream_decoder_init_file(
                handle = handle,
                filename = "C:\\nope\\does-not-exist.flac",
                writeCallback = write,
                metadataCallback = metadata,
                errorCallback = error,
                clientData = null,
            )
            assertEquals(
                LibFlacBinding.INIT_STATUS_ERROR_OPENING_FILE,
                status,
                "init_file with non-existent path should return ERROR_OPENING_FILE (4); got $status",
            )
        } finally {
            flac.FLAC__stream_decoder_finish(handle)
            flac.FLAC__stream_decoder_delete(handle)
        }
    }

    @Test
    fun `init_file with bundled fixture initializes, metadata callback fires for STREAMINFO`() {
        val flac = LibFlacLoader.load()
        val handle = flac.FLAC__stream_decoder_new()
            ?: error("decoder_new returned null")
        val metadataFired = AtomicBoolean(false)
        val metadataCallbackCount = AtomicInteger(0)
        val errorFired = AtomicBoolean(false)

        // Hold strong references to the callbacks for the duration of the
        // decoder's lifetime — JNA hands raw function pointers to libFLAC, and
        // GC of the wrapper objects mid-decode would crash the JVM.
        val write = object : WriteCallback {
            override fun invoke(decoder: Pointer, frame: Pointer, buffer: Pointer, clientData: Pointer?): Int =
                WriteCallback.STATUS_CONTINUE
        }
        val metadata = object : MetadataCallback {
            override fun invoke(decoder: Pointer, metadata: Pointer, clientData: Pointer?) {
                metadataFired.set(true)
                metadataCallbackCount.incrementAndGet()
            }
        }
        val error = object : ErrorCallback {
            override fun invoke(decoder: Pointer, status: Int, clientData: Pointer?) {
                errorFired.set(true)
            }
        }

        try {
            val status = flac.FLAC__stream_decoder_init_file(
                handle = handle,
                filename = fixturePath("sine_440_stereo_16_44.flac"),
                writeCallback = write,
                metadataCallback = metadata,
                errorCallback = error,
                clientData = null,
            )
            assertEquals(
                LibFlacBinding.INIT_STATUS_OK,
                status,
                "init_file with bundled fixture should return OK (0); got $status",
            )
            val ok = flac.FLAC__stream_decoder_process_until_end_of_metadata(handle)
            assertTrue(ok, "process_until_end_of_metadata should return true on the fixture")
            assertTrue(
                metadataFired.get(),
                "metadata callback should fire for STREAMINFO during process_until_end_of_metadata",
            )
            assertTrue(
                metadataCallbackCount.get() >= 1,
                "expected ≥1 metadata callback invocation (STREAMINFO); got ${metadataCallbackCount.get()}",
            )
            assertEquals(false, errorFired.get(), "no error callback should fire for the clean fixture")
        } finally {
            flac.FLAC__stream_decoder_finish(handle)
            flac.FLAC__stream_decoder_delete(handle)
        }
    }
}
