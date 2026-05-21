// Golden-corpus FLAC parity test — companion to the kiln-flac-golden skill.
//
// Enumerates every <name>.flac in the corpus directory pointed at by the
// `kiln.golden.corpus` system property (or `KILN_GOLDEN_CORPUS` env var),
// decodes each via JvmFlacDecoderImpl, and byte-compares the interleaved PCM
// against the matching <name>.pcm reference produced by `flac.exe -d
// --force-raw-format --endian=little --sign=signed`.
//
// When neither the system property nor the env var is set, the test auto-skips
// (org.junit.Assume.assumeTrue) so a normal `./gradlew :audio:playback:desktopTest`
// invocation does not regress when the corpus has not been generated.
//
// The skill's run-golden-test.ps1 sets KILN_GOLDEN_CORPUS before invoking
// Gradle so the test activates exactly through that channel.

package com.clayworks.kiln.audio.playback.flac

import arrow.core.Either
import com.clayworks.kiln.audio.playback.DecoderError
import com.clayworks.kiln.audio.playback.JvmFlacDecoderImpl
import com.clayworks.kiln.audio.playback.nativeio.LibFlacLoader
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.Playable
import com.clayworks.kiln.library.source.SourceId
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class GoldenCorpusTest {

    @Test
    fun `golden corpus FLACs decode to byte-identical PCM as flac dot exe reference`() = runBlocking {
        val corpusPathRaw = System.getProperty("kiln.golden.corpus")
            ?: System.getenv("KILN_GOLDEN_CORPUS")
        assumeTrue(
            "Set -Dkiln.golden.corpus=<dir> or KILN_GOLDEN_CORPUS env var to enable; " +
                "use the kiln-flac-golden skill for the canonical invocation.",
            corpusPathRaw != null,
        )
        val corpusDir = File(corpusPathRaw!!)
        assumeTrue(
            "Corpus dir does not exist: ${corpusDir.absolutePath}. " +
                "Run scripts/generate-reference-pcm.ps1 first.",
            corpusDir.exists() && corpusDir.isDirectory,
        )

        val flacFiles = corpusDir
            .listFiles { _, name -> name.endsWith(".flac", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        assumeTrue(
            "Corpus dir contains no .flac files: ${corpusDir.absolutePath}",
            flacFiles.isNotEmpty(),
        )

        val failures = mutableListOf<String>()
        for (flacFile in flacFiles) {
            val pcmFile = File(corpusDir, flacFile.nameWithoutExtension + ".pcm")
            if (!pcmFile.exists()) {
                failures += "${flacFile.name}: reference .pcm missing at ${pcmFile.absolutePath}"
                continue
            }
            val expected = pcmFile.readBytes()
            val decoder = JvmFlacDecoderImpl(LibFlacLoader.load())
            val playable = makePlayable(flacFile)
            when (val result = decoder.open(playable)) {
                is Either.Left -> {
                    failures += "${flacFile.name}: decoder open() returned Left: ${result.value}"
                    continue
                }
                is Either.Right -> result.value.use { stream ->
                    val baos = ByteArrayOutputStream(expected.size.coerceAtLeast(1024))
                    val frames = stream.frames.toList()
                    for (frame in frames) {
                        baos.write(frame.bytes, 0, frame.byteCount)
                    }
                    val actual = baos.toByteArray()
                    val bytesPerSample = (stream.format.bitDepth / 8) * stream.format.channels
                    if (!actual.contentEquals(expected)) {
                        val mismatchIdx = firstMismatchIndex(actual, expected)
                        val sampleOffset = if (bytesPerSample > 0) mismatchIdx / bytesPerSample else mismatchIdx
                        val channel = if (bytesPerSample > 0 && stream.format.channels > 0) {
                            ((mismatchIdx % bytesPerSample) / (stream.format.bitDepth / 8)) + 1
                        } else 0
                        val window = formatDiffWindow(actual, expected, mismatchIdx, bytesPerSample, contextSamples = 32)
                        failures += buildString {
                            appendLine("${flacFile.name}: byte mismatch")
                            appendLine("  first mismatch at byte offset $mismatchIdx (sample $sampleOffset, channel $channel)")
                            appendLine("  decoded size = ${actual.size} bytes; expected = ${expected.size} bytes")
                            appendLine("  diff window (expected then actual, hex):")
                            append(window)
                        }
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "Golden-corpus byte-parity check failed for ${failures.size} of ${flacFiles.size} files:\n" +
                failures.joinToString(separator = "\n---\n"),
        )
        // Sanity: ensure we actually exercised at least one file (else the test
        // could pass vacuously if the corpus was somehow empty after assumeTrue).
        if (flacFiles.isEmpty()) {
            fail("Reached the assertion with an empty file list — this should be impossible after assumeTrue.")
        }
    }

    private fun makePlayable(flacFile: File): Playable = Playable(
        itemId = ItemId("golden-${flacFile.name}"),
        sourceId = SourceId("golden-corpus"),
        uri = flacFile.toURI().toString(),
        codec = AudioCodec.FLAC,
        // STREAMINFO populates these post-open; the values passed here are
        // ignored by JvmFlacDecoderImpl (it reads format from libFLAC's
        // metadata callback). Pass plausible defaults so the constructor
        // contract is satisfied.
        sampleRateHz = 44100,
        bitDepth = 16,
        channels = 2,
        bitrateKbps = null,
        durationMs = 0,
        replayGain = null,
    )

    private fun firstMismatchIndex(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            if (a[i] != b[i]) return i
        }
        // Equal up to the shorter; the divergence is the length difference itself.
        return len
    }

    private fun formatDiffWindow(
        actual: ByteArray,
        expected: ByteArray,
        mismatchIdx: Int,
        bytesPerSample: Int,
        contextSamples: Int,
    ): String {
        val contextBytes = if (bytesPerSample > 0) bytesPerSample * contextSamples else 64
        val halfBefore = contextBytes / 2
        val start = (mismatchIdx - halfBefore).coerceAtLeast(0)
        val endExpected = (start + contextBytes).coerceAtMost(expected.size)
        val endActual = (start + contextBytes).coerceAtMost(actual.size)
        val expectedHex = expected.copyOfRange(start, endExpected).joinToString(" ") { "%02x".format(it) }
        val actualHex = actual.copyOfRange(start, endActual).joinToString(" ") { "%02x".format(it) }
        return buildString {
            appendLine("    expected[$start..${endExpected - 1}]:")
            appendLine("    $expectedHex")
            appendLine("    actual[$start..${endActual - 1}]:")
            append("    $actualHex")
        }
    }
}
