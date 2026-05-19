// Empirical FLAC smoke test against Clay's actual music library at D:\tiddl
// (per vetting log Item 9 addendum gate). Local-only — skips cleanly if the
// library root isn't present (CI agents, non-Clay machines).
//
// For each FLAC found (up to MAX_FILES, preferring smaller files for runtime):
//   1. ffprobe extracts reference metadata (sample rate, bit depth, channels,
//      duration_ts = total sample count).
//   2. JvmFlacDecoderImpl.open() opens the file via JNA + libFLAC.
//   3. Drained Flow<AudioFrame> sums must equal ffprobe's duration_ts.
//   4. Decoder format metadata must equal ffprobe's reported values.
//
// Failures here mean the JNA bridge has a bug that the bundled-fixture tests
// don't catch — likely a format-matrix corner the fixtures didn't span
// (multichannel, ReplayGain, embedded art, 24/192, variable blocksize, ...).

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.playback.nativeio.LibFlacLoader
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.Playable
import com.clayworks.kiln.library.source.SourceId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class FlacDecodeSmokeTest {

    private companion object {
        // Clay's music library root (per CLAUDE.md). Skip if missing.
        val LIBRARY_ROOT: Path = Path.of("D:\\tiddl")
        const val MAX_FILES = 10
        const val MAX_BYTES_PER_FILE = 25L * 1024 * 1024  // 25 MB — bounds smoke runtime
    }

    @Test
    fun `decode N FLACs from D-tiddl — metadata + total samples must match ffprobe`() = runBlocking {
        if (!Files.isDirectory(LIBRARY_ROOT)) {
            println("FlacDecodeSmokeTest: SKIP — $LIBRARY_ROOT not present (CI agent or non-Clay machine)")
            return@runBlocking
        }
        if (!ffprobeAvailable()) {
            println("FlacDecodeSmokeTest: SKIP — ffprobe not on PATH; can't establish reference values")
            return@runBlocking
        }

        val candidates: List<Path> = Files.walk(LIBRARY_ROOT).use { stream ->
            stream.asSequence()
                .filter { it.toString().lowercase().endsWith(".flac") }
                .filter { runCatching { Files.size(it) < MAX_BYTES_PER_FILE }.getOrDefault(false) }
                .take(MAX_FILES)
                .toList()
        }

        if (candidates.isEmpty()) {
            println("FlacDecodeSmokeTest: SKIP — no .flac files found under $LIBRARY_ROOT")
            return@runBlocking
        }

        val decoder = JvmFlacDecoderImpl(LibFlacLoader.load())
        val summary = mutableListOf<String>()
        var checked = 0

        for ((idx, path) in candidates.withIndex()) {
            val probe = runCatching { ffprobeStream(path) }.getOrNull()
            if (probe == null) {
                summary += "[$idx] ${path.fileName}: SKIP (ffprobe failure)"
                continue
            }

            val playable = Playable(
                itemId = ItemId("smoke-$idx"),
                sourceId = SourceId("local-smoke"),
                uri = "file://$path",
                codec = AudioCodec.FLAC,
                sampleRateHz = probe.sampleRate,
                bitDepth = probe.bitDepth,
                channels = probe.channels,
                bitrateKbps = null,
                durationMs = probe.durationMs,
                replayGain = null,
            )

            when (val result = decoder.open(playable)) {
                is Either.Left -> fail("open() failed for ${path.fileName}: ${result.value}")
                is Either.Right -> result.value.use { stream ->
                    assertEquals(probe.sampleRate, stream.format.sampleRateHz, "sample rate for ${path.fileName}")
                    assertEquals(probe.bitDepth, stream.format.bitDepth, "bit depth for ${path.fileName}")
                    assertEquals(probe.channels, stream.format.channels, "channel count for ${path.fileName}")

                    val frames = stream.frames.toList()
                    val totalSamples = frames.sumOf { it.sampleCount.toLong() }
                    assertEquals(
                        probe.totalSamples,
                        totalSamples,
                        "total sample frames for ${path.fileName} (expected from ffprobe duration_ts)",
                    )
                    summary += "[$idx] ${path.fileName.toString().take(50)}: " +
                        "${probe.sampleRate}Hz / ${probe.bitDepth}-bit / ${probe.channels}ch / " +
                        "${totalSamples}samples / ${frames.size} frames — OK"
                    checked++
                }
            }
        }

        println("FlacDecodeSmokeTest: $checked / ${candidates.size} FLAC files decoded successfully")
        summary.forEach(::println)
        assertEquals(
            candidates.size,
            checked,
            "all candidate FLACs should have decoded; failures recorded above",
        )
    }

    // === ffprobe wiring ===

    private data class Probe(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val totalSamples: Long,
        val durationMs: Long,
    )

    private fun ffprobeAvailable(): Boolean = try {
        val proc = ProcessBuilder("ffprobe", "-version").redirectErrorStream(true).start()
        proc.waitFor(5, TimeUnit.SECONDS)
        proc.exitValue() == 0
    } catch (e: IOException) {
        false
    }

    private fun ffprobeStream(path: Path): Probe {
        // -select_streams a:0 picks only the first audio stream — many FLACs
        // carry an embedded-art picture stream as well, and without selection
        // ffprobe emits duplicate keys whose later values overwrite earlier ones.
        val proc = ProcessBuilder(
            "ffprobe",
            "-v", "error",
            "-select_streams", "a:0",
            "-show_entries",
            "stream=sample_rate,channels,duration_ts,bits_per_raw_sample,sample_fmt",
            "-of", "default=noprint_wrappers=1:nokey=0",
            path.toAbsolutePath().toString(),
        ).redirectErrorStream(true).start()

        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(15, TimeUnit.SECONDS)
        if (proc.exitValue() != 0) error("ffprobe exit=${proc.exitValue()} on $path: $out")

        val map = out.lines()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
            }
            .toMap()

        val sampleRate = map["sample_rate"]?.toIntOrNull() ?: error("ffprobe missing sample_rate for $path")
        val channels = map["channels"]?.toIntOrNull() ?: error("ffprobe missing channels for $path")
        val totalSamples = map["duration_ts"]?.toLongOrNull() ?: error("ffprobe missing duration_ts for $path")
        // bits_per_raw_sample is sometimes "N/A" → fall back to sample_fmt mapping.
        val bitDepth = map["bits_per_raw_sample"]?.toIntOrNull()
            ?: when (map["sample_fmt"]) {
                "s16", "s16p" -> 16
                "s32", "s32p" -> 24  // FLAC tells ffmpeg s32 but bits_per_raw_sample carries the real width; default 24
                else -> error("ffprobe missing bits_per_raw_sample + unknown sample_fmt=${map["sample_fmt"]} for $path")
            }
        val durationMs = (totalSamples * 1000L) / sampleRate
        return Probe(sampleRate, bitDepth, channels, totalSamples, durationMs)
    }
}
