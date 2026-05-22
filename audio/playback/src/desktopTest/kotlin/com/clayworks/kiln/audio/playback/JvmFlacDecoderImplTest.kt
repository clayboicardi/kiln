// End-to-end smoke for JvmFlacDecoderImpl: construct a Playable from the
// bundled fixture, open(), collect all frames via the Flow, verify total
// bytes match expected. Exercises the public-facing Decoder/DecodedStream
// contract from commonMain (Either<DecoderError, DecodedStream>, format
// metadata, frames Flow, AutoCloseable close).

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.nativeio.LibFlacLoader
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.Playable
import com.clayworks.kiln.library.source.SourceId
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmFlacDecoderImplTest {

    private fun fixturePlayable(name: String, sampleRate: Int, bitDepth: Int, channels: Int, durationMs: Long): Playable {
        val url = JvmFlacDecoderImplTest::class.java.getResource("/fixtures/$name")
            ?: error("Missing test fixture: /fixtures/$name")
        // URL.toURI() emits a properly-formed file: URI (RFC 8089). Avoid the
        // "file://$path" string concat that produces malformed URIs on Windows
        // (D:\... gets prefix-only, breaking java.net.URI parsing in the
        // consumer per Session 10 ultrareview U14 + Gemini G3).
        return Playable(
            itemId = ItemId("test-$name"),
            sourceId = SourceId("test"),
            uri = url.toURI().toString(),
            codec = AudioCodec.FLAC,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            bitrateKbps = null,
            durationMs = durationMs,
            replayGain = null,
        )
    }

    @Test
    fun `open 16-44 fixture, drain frames, total bytes match interleaved expected`() = runBlocking {
        val decoder = JvmFlacDecoderImpl(LibFlacLoader.load())
        val playable = fixturePlayable(
            name = "sine_440_stereo_16_44.flac",
            sampleRate = 44100,
            bitDepth = 16,
            channels = 2,
            durationMs = 500,
        )
        when (val result = decoder.open(playable)) {
            is Either.Left -> fail("open() returned Left: ${result.value}")
            is Either.Right -> result.value.use { stream ->
                // Format metadata reflects STREAMINFO.
                assertEquals(44100, stream.format.sampleRateHz)
                assertEquals(16, stream.format.bitDepth)
                assertEquals(2, stream.format.channels)
                assertEquals(SampleFormat.PCM_S16_LE, stream.format.sampleFormat)

                // STREAMINFO totalSamples drives durationMs (22050 / 44100 = 500 ms).
                assertEquals(500L, stream.durationMs, "durationMs computed from STREAMINFO")

                // Drain the frame flow and sum the byte total.
                val frames = stream.frames.toList()
                val totalBytes = frames.sumOf { it.byteCount.toLong() }
                val totalSamples = frames.sumOf { it.sampleCount.toLong() }
                assertEquals(22050L, totalSamples, "total sample frames")
                assertEquals(88200L, totalBytes, "interleaved bytes (22050 × 2ch × 2B)")
                assertTrue(frames.isNotEmpty(), "expected ≥1 frame")
                // First-frame timestamp is 0.
                assertEquals(0L, frames.first().timestampMs, "first frame begins at sample 0")
                // positionMs reflects the last frame's timestamp after drain.
                assertTrue(
                    stream.positionMs >= frames.last().timestampMs,
                    "positionMs (${stream.positionMs}) should be ≥ last frame timestampMs (${frames.last().timestampMs})",
                )
            }
        }
    }

    @Test
    fun `open 24-96 fixture, drain frames, 24-bit packing produces expected byte count`() = runBlocking {
        val decoder = JvmFlacDecoderImpl(LibFlacLoader.load())
        val playable = fixturePlayable(
            name = "sine_440_stereo_24_96.flac",
            sampleRate = 96000,
            bitDepth = 24,
            channels = 2,
            durationMs = 500,
        )
        when (val result = decoder.open(playable)) {
            is Either.Left -> fail("open() returned Left: ${result.value}")
            is Either.Right -> result.value.use { stream ->
                assertEquals(96000, stream.format.sampleRateHz)
                assertEquals(24, stream.format.bitDepth)
                assertEquals(SampleFormat.PCM_S24_LE, stream.format.sampleFormat)
                val totalBytes = stream.frames.toList().sumOf { it.byteCount.toLong() }
                assertEquals(288000L, totalBytes, "48000 × 2ch × 3B (24-bit packed)")
            }
        }
    }

    @Test
    fun `open of non-FLAC codec returns UnsupportedCodec error`() = runBlocking {
        val decoder = JvmFlacDecoderImpl(LibFlacLoader.load())
        val playable = fixturePlayable("sine_440_stereo_16_44.flac", 44100, 16, 2, 500)
            .copy(codec = AudioCodec.MP3)
        val result = decoder.open(playable)
        assertTrue(result is Either.Left, "non-FLAC codec should return Left")
        assertTrue(
            result.value is DecoderError.UnsupportedCodec,
            "expected UnsupportedCodec; got ${result.value}",
        )
    }

    @Test
    fun `open of non-existent file returns IoError`() = runBlocking {
        val decoder = JvmFlacDecoderImpl(LibFlacLoader.load())
        val playable = Playable(
            itemId = ItemId("test-missing"),
            sourceId = SourceId("test"),
            // Properly-formed file URI for a non-existent path on Windows; the
            // consumer should parse OK but libFLAC's init_file should report
            // INIT_STATUS_ERROR_OPENING_FILE → DecoderError.IoError.
            uri = "file:///C:/nope/does-not-exist.flac",
            codec = AudioCodec.FLAC,
            sampleRateHz = 44100,
            bitDepth = 16,
            channels = 2,
            bitrateKbps = null,
            durationMs = 0,
            replayGain = null,
        )
        val result = decoder.open(playable)
        assertTrue(result is Either.Left, "non-existent file should return Left")
        assertTrue(
            result.value is DecoderError.IoError,
            "expected IoError; got ${result.value}",
        )
    }
}
