package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.library.scan.TrackAnalysisError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmFlacTrackAnalyzerTest {

    private fun fixturePath(name: String): String {
        val url = JvmFlacTrackAnalyzerTest::class.java.getResource("/fixtures/$name")
            ?: error("Missing test fixture: /fixtures/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    @Test
    fun `non-FLAC codec returns CodecUnsupported`() = runBlocking {
        val analyzer = createJvmFlacTrackAnalyzer()
        when (val result = analyzer.analyze("/fake/path.mp3", codec = "MP3")) {
            is Either.Left -> {
                val err = result.value
                assertTrue(err is TrackAnalysisError.CodecUnsupported, "expected CodecUnsupported, got $err")
                assertEquals("MP3", err.codec)
            }
            is Either.Right -> fail("expected Left, got ${result.value}")
        }
    }

    @Test
    fun `analyze 16-bit 44_1kHz FLAC fixture returns either InsufficientAudio or a sane LUFS value`() = runBlocking {
        // The bundled fixture is 500 ms — below the 3-second EBU R128
        // minimum. The analyzer is correct to refuse it. We accept either:
        //   (a) Left AnalysisFailed (current LoudnessAnalyzer behavior), or
        //   (b) Right with a sane LUFS — would imply the analyzer was relaxed.
        // What we DO NOT want is DecodeFailed (decoder bug) or a NaN result.
        val analyzer = createJvmFlacTrackAnalyzer()
        val path = fixturePath("sine_440_stereo_16_44.flac")
        when (val result = analyzer.analyze(path, codec = "FLAC")) {
            is Either.Left -> {
                assertTrue(
                    result.value is TrackAnalysisError.AnalysisFailed,
                    "expected AnalysisFailed for short fixture, got ${result.value}",
                )
            }
            is Either.Right -> {
                val lufs = result.value.integratedLufs
                val dbtp = result.value.truePeakDbtp
                assertTrue(lufs.isFinite(), "LUFS must be finite, got $lufs")
                assertTrue(lufs < 0.0, "LUFS must be negative, got $lufs")
                assertTrue(lufs > -80.0, "LUFS must be > -80, got $lufs")
                assertTrue(dbtp.isFinite(), "dBTP must be finite, got $dbtp")
                assertTrue(dbtp <= 6.0, "dBTP must be <= +6 for a 0 dBFS sine, got $dbtp")
            }
        }
    }

    @Test
    fun `analyze 24-bit 96kHz FLAC fixture returns finite results or AnalysisFailed`() = runBlocking {
        val analyzer = createJvmFlacTrackAnalyzer()
        val path = fixturePath("sine_440_stereo_24_96.flac")
        when (val result = analyzer.analyze(path, codec = "FLAC")) {
            is Either.Left -> {
                assertTrue(
                    result.value is TrackAnalysisError.AnalysisFailed,
                    "expected AnalysisFailed for short fixture, got ${result.value}",
                )
            }
            is Either.Right -> {
                assertTrue(result.value.integratedLufs.isFinite())
                assertTrue(result.value.truePeakDbtp.isFinite())
            }
        }
    }
}
