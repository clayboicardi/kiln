// Validates the full PCM decode loop: init_file → process_until_end_of_metadata
// → repeated process_single → write_callback fires + FlacFrameReader extracts
// interleaved PCM. Compares the decoded byte/sample totals against ffprobe's
// reference values for both fixtures.
//
// Also verifies 24-bit packing produces the expected total byte count (24-bit
// stereo @ 96 kHz, 0.5s = 48000 samples × 2 ch × 3 bytes = 288,000 bytes).

package com.clayworks.kiln.audio.playback.nativeio

import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlacFrameReaderTest {

    private fun fixturePath(name: String): String {
        val url = FlacFrameReaderTest::class.java.getResource("/fixtures/$name")
            ?: error("Missing test fixture: /fixtures/$name")
        return File(url.toURI()).absolutePath
    }

    private data class DecodeTotals(
        val totalSampleFrames: Long,   // sample frames per channel (== total samples in STREAMINFO)
        val totalBytes: Long,          // interleaved PCM bytes
        val frameCount: Int,           // number of write_callback invocations
    )

    private fun decodeFully(filename: String): DecodeTotals {
        val flac = LibFlacLoader.load()
        val handle = flac.FLAC__stream_decoder_new() ?: error("decoder_new returned null")
        val streamInfo = AtomicReference<StreamInfo?>(null)
        val sampleFrames = AtomicLong(0L)
        val byteTotal = AtomicLong(0L)
        var frameCount = 0

        val write = object : WriteCallback {
            override fun invoke(decoder: Pointer, frame: Pointer, buffer: Pointer, clientData: Pointer?): Int {
                val si = streamInfo.get() ?: return WriteCallback.STATUS_ABORT
                val blocksize = FlacFrameReader.blocksize(frame)
                val pcm = FlacFrameReader.extractInterleavedPcm(
                    bufferPtr = buffer,
                    channels = si.channels,
                    blocksize = blocksize,
                    bitDepth = si.bitDepth,
                )
                sampleFrames.addAndGet(blocksize.toLong())
                byteTotal.addAndGet(pcm.size.toLong())
                frameCount++
                return WriteCallback.STATUS_CONTINUE
            }
        }
        val metadata = object : MetadataCallback {
            override fun invoke(decoder: Pointer, metadata: Pointer, clientData: Pointer?) {
                FlacMetadata.parseStreamInfo(metadata)?.let { streamInfo.compareAndSet(null, it) }
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
            // Pulls metadata callback for STREAMINFO before any process_single.
            assertTrue(
                flac.FLAC__stream_decoder_process_until_end_of_metadata(handle),
                "process_until_end_of_metadata failed for $filename",
            )

            // Decode loop: process_single until END_OF_STREAM.
            while (true) {
                val state = flac.FLAC__stream_decoder_get_state(handle)
                if (state == LibFlacBinding.STATE_END_OF_STREAM) break
                if (state == LibFlacBinding.STATE_ABORTED) error("decoder aborted mid-stream")
                if (state >= LibFlacBinding.STATE_OGG_ERROR && state <= LibFlacBinding.STATE_MEMORY_ALLOCATION_ERROR) {
                    error("decoder error state: $state")
                }
                val ok = flac.FLAC__stream_decoder_process_single(handle)
                assertTrue(ok, "process_single returned false (state=$state, frames=$frameCount)")
            }
        } finally {
            flac.FLAC__stream_decoder_finish(handle)
            flac.FLAC__stream_decoder_delete(handle)
        }

        return DecodeTotals(
            totalSampleFrames = sampleFrames.get(),
            totalBytes = byteTotal.get(),
            frameCount = frameCount,
        )
    }

    @Test
    fun `16-bit 44_1 kHz stereo fixture decodes to 22050 samples × 2ch × 2B = 88200 bytes`() {
        val totals = decodeFully(fixturePath("sine_440_stereo_16_44.flac"))
        assertEquals(22050L, totals.totalSampleFrames, "sample frames (0.5s × 44100)")
        assertEquals(88200L, totals.totalBytes, "interleaved bytes (22050 × 2ch × 2B)")
        assertTrue(totals.frameCount > 0, "expected ≥1 audio frame, got ${totals.frameCount}")
    }

    @Test
    fun `24-bit 96 kHz stereo fixture decodes to 48000 samples × 2ch × 3B = 288000 bytes`() {
        val totals = decodeFully(fixturePath("sine_440_stereo_24_96.flac"))
        assertEquals(48000L, totals.totalSampleFrames, "sample frames (0.5s × 96000)")
        assertEquals(288000L, totals.totalBytes, "interleaved bytes (48000 × 2ch × 3B — 24-bit packing)")
        assertTrue(totals.frameCount > 0, "expected ≥1 audio frame, got ${totals.frameCount}")
    }

    @Test
    fun `seek_absolute to mid-stream returns true and decoder continues producing frames — H6_7`() {
        val flac = LibFlacLoader.load()
        val handle = flac.FLAC__stream_decoder_new() ?: error("decoder_new returned null")
        val streamInfo = AtomicReference<StreamInfo?>(null)
        val postSeekSampleNumber = AtomicLong(-1L)
        val postSeekFrameCount = AtomicLong(0L)

        val write = object : WriteCallback {
            override fun invoke(decoder: Pointer, frame: Pointer, buffer: Pointer, clientData: Pointer?): Int {
                if (postSeekFrameCount.get() == 0L) {
                    // Capture the sample_number of the first post-seek frame.
                    postSeekSampleNumber.set(
                        FlacFrameReader.sampleNumber(frame, FlacFrameReader.blocksize(frame)),
                    )
                }
                postSeekFrameCount.incrementAndGet()
                return WriteCallback.STATUS_CONTINUE
            }
        }
        val metadata = object : MetadataCallback {
            override fun invoke(decoder: Pointer, metadata: Pointer, clientData: Pointer?) {
                FlacMetadata.parseStreamInfo(metadata)?.let { streamInfo.compareAndSet(null, it) }
            }
        }
        val error = object : ErrorCallback {
            override fun invoke(decoder: Pointer, status: Int, clientData: Pointer?) =
                error("error callback fired: status=$status")
        }

        try {
            val status = flac.FLAC__stream_decoder_init_file(
                handle, fixturePath("sine_440_stereo_16_44.flac"),
                write, metadata, error, null,
            )
            assertEquals(LibFlacBinding.INIT_STATUS_OK, status)
            assertTrue(flac.FLAC__stream_decoder_process_until_end_of_metadata(handle))

            // Seek to ~half-way (sample 11025 of 22050 in the 16/44 fixture).
            val targetSample = 11025L
            val seekOk = flac.FLAC__stream_decoder_seek_absolute(handle, targetSample)
            assertTrue(
                seekOk,
                "FLAC__stream_decoder_seek_absolute($targetSample) should return true; " +
                    "state=${flac.FLAC__stream_decoder_get_state(handle)}",
            )

            // Decode one frame post-seek to verify decoder is still functional.
            val ok = flac.FLAC__stream_decoder_process_single(handle)
            assertTrue(ok, "process_single should succeed after seek")
            assertTrue(
                postSeekFrameCount.get() > 0L,
                "expected the post-seek write_callback to fire ≥1 time; got ${postSeekFrameCount.get()}",
            )

            // Sanity check: the captured sample_number should be near the seek target.
            // libFLAC seeks to the nearest frame boundary, which may be ahead of or
            // behind the requested sample — accept ±blocksize tolerance.
            val captured = postSeekSampleNumber.get()
            val si = assertNotNull(streamInfo.get(), "STREAMINFO should be captured by the metadata callback")
            val tolerance = si.maxBlocksize.toLong().coerceAtLeast(4096L)
            assertTrue(
                kotlin.math.abs(captured - targetSample) <= tolerance,
                "post-seek frame sample_number $captured should be within ±$tolerance of target $targetSample",
            )
        } finally {
            flac.FLAC__stream_decoder_finish(handle)
            flac.FLAC__stream_decoder_delete(handle)
        }
    }
}
