// Validates STREAMINFO parsing against bundled FLAC fixtures.
// Expected values cross-checked against ffprobe output at fixture generation.

package com.clayworks.kiln.audio.playback.nativeio

import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StreamInfoTest {

    private fun fixturePath(name: String): String {
        val url = StreamInfoTest::class.java.getResource("/fixtures/$name")
            ?: error("Missing test fixture: /fixtures/$name")
        return File(url.toURI()).absolutePath
    }

    private fun readStreamInfo(filename: String): StreamInfo {
        val flac = LibFlacLoader.load()
        val handle = flac.FLAC__stream_decoder_new() ?: error("decoder_new returned null")
        val captured = AtomicReference<StreamInfo?>(null)

        val write = object : WriteCallback {
            override fun invoke(decoder: Pointer, frame: Pointer, buffer: Pointer, clientData: Pointer?): Int =
                WriteCallback.STATUS_CONTINUE
        }
        val metadata = object : MetadataCallback {
            override fun invoke(decoder: Pointer, metadata: Pointer, clientData: Pointer?) {
                FlacMetadata.parseStreamInfo(metadata)?.let { captured.compareAndSet(null, it) }
            }
        }
        val error = object : ErrorCallback {
            override fun invoke(decoder: Pointer, status: Int, clientData: Pointer?) {
                error("error callback fired for $filename status=$status")
            }
        }

        try {
            val status = flac.FLAC__stream_decoder_init_file(
                handle, filename, write, metadata, error, null,
            )
            assertEquals(LibFlacBinding.INIT_STATUS_OK, status, "init_file should return OK for $filename")
            assertEquals(
                true,
                flac.FLAC__stream_decoder_process_until_end_of_metadata(handle),
                "process_until_end_of_metadata should succeed for $filename",
            )
        } finally {
            flac.FLAC__stream_decoder_finish(handle)
            flac.FLAC__stream_decoder_delete(handle)
        }
        return assertNotNull(captured.get(), "STREAMINFO should have been parsed for $filename")
    }

    @Test
    fun `16-bit 44_1 kHz stereo fixture — STREAMINFO matches ffprobe reference`() {
        val si = readStreamInfo(fixturePath("sine_440_stereo_16_44.flac"))
        assertEquals(44100, si.sampleRateHz, "sample rate")
        assertEquals(16, si.bitDepth, "bit depth")
        assertEquals(2, si.channels, "channel count")
        assertEquals(22050L, si.totalSamples, "total samples (= 0.5s * 44100)")
    }

    @Test
    fun `24-bit 96 kHz stereo fixture — STREAMINFO matches ffprobe reference`() {
        val si = readStreamInfo(fixturePath("sine_440_stereo_24_96.flac"))
        assertEquals(96000, si.sampleRateHz, "sample rate")
        assertEquals(24, si.bitDepth, "bit depth")
        assertEquals(2, si.channels, "channel count")
        assertEquals(48000L, si.totalSamples, "total samples (= 0.5s * 96000)")
    }
}
