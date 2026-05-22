// Desktop FLAC TrackAnalyzer — opens the file via createJvmFlacDecoder(),
// streams interleaved PCM frames through LoudnessAnalyzer, and returns the
// integrated LUFS + true-peak result. FLAC-only for this session.
//
// Byte → Float conversion: depends on STREAMINFO bit depth (16/24/32 → signed
// little-endian; 32 float → IEEE 754). The decoder reports bit depth via
// DecodedAudioFormat.sampleFormat; we map per the SampleFormat enum.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.dsp.replaygain.AnalysisError
import com.clayworks.kiln.audio.dsp.replaygain.createLoudnessAnalyzer
import com.clayworks.kiln.library.scan.TrackAnalysisError
import com.clayworks.kiln.library.scan.TrackAnalyzer
import com.clayworks.kiln.library.scan.TrackLoudness
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.Playable
import com.clayworks.kiln.library.source.SourceId
import kotlinx.coroutines.flow.collect

private val log = Logger.withTag("JvmFlacTrackAnalyzer")

/** Public factory keeping the impl class internal. */
fun createJvmFlacTrackAnalyzer(): TrackAnalyzer = JvmFlacTrackAnalyzer(createJvmFlacDecoder())

internal class JvmFlacTrackAnalyzer(
    private val decoder: Decoder,
) : TrackAnalyzer {

    override suspend fun analyze(
        filePath: String,
        codec: String,
    ): Either<TrackAnalysisError, TrackLoudness> {
        if (!codec.equals("FLAC", ignoreCase = true)) {
            return Either.Left(TrackAnalysisError.CodecUnsupported(codec))
        }

        // Build a Playable mirror of the file. The TrackAnalyzer interface only
        // gives us filePath + codec, so we synthesize the other fields. The
        // decoder reads STREAMINFO and overrides sampleRateHz / bitDepth /
        // channels from the file itself — the values we put on Playable here
        // are advisory only.
        //
        // JvmFlacDecoderImpl expects an RFC 8089 file URI (see its URI parsing
        // logic). java.io.File.toURI() produces a properly-formed file: URI
        // that handles Windows drive letters and path encoding.
        val fileUri = try {
            java.io.File(filePath).toURI().toString()
        } catch (e: Exception) {
            return Either.Left(TrackAnalysisError.DecodeFailed("Cannot build URI for path: $filePath — ${e.message}"))
        }

        val playable = Playable(
            itemId = ItemId("analyzer-${filePath.hashCode()}"),
            sourceId = SourceId("track-analysis"),
            uri = fileUri,
            codec = AudioCodec.FLAC,
            sampleRateHz = 44_100,  // placeholder; STREAMINFO overrides
            bitDepth = 16,          // placeholder; STREAMINFO overrides
            channels = 2,           // placeholder; STREAMINFO overrides
            bitrateKbps = null,
            durationMs = 0L,
            replayGain = null,
        )

        val streamResult = decoder.open(playable)
        if (streamResult is Either.Left) {
            return Either.Left(TrackAnalysisError.DecodeFailed("decoder.open failed: ${streamResult.value}"))
        }
        val stream = (streamResult as Either.Right).value
        try {
            val fmt = stream.format
            val lufsAnalyzer = createLoudnessAnalyzer(fmt.sampleRateHz, fmt.channels)
            val converter = PcmByteToFloat(fmt.sampleFormat)

            stream.frames.collect { frame ->
                val floatBuf = converter.convert(frame.bytes, frame.byteCount)
                // AudioFrame.sampleCount is the per-channel frame count (blocksize),
                // matching LoudnessAnalyzer.processSamples's `frames` parameter.
                // Confirmed by JvmFlacDecodedStream.kt line 99: sampleCount = blocksize.
                lufsAnalyzer.processSamples(floatBuf, frame.sampleCount)
            }

            val lufsEither = lufsAnalyzer.integratedLufs()
            val lufs = when (lufsEither) {
                is Either.Left -> when (lufsEither.value) {
                    AnalysisError.InsufficientAudio -> return Either.Left(
                        TrackAnalysisError.AnalysisFailed("InsufficientAudio: track < 3 s of audio"),
                    )
                    AnalysisError.NoGatedBlocks -> return Either.Left(
                        TrackAnalysisError.AnalysisFailed("NoGatedBlocks: track is silent"),
                    )
                }
                is Either.Right -> lufsEither.value
            }
            val peakDbtp = lufsAnalyzer.truePeakDbtp()
            return Either.Right(TrackLoudness(integratedLufs = lufs, truePeakDbtp = peakDbtp))
        } catch (e: Throwable) {
            log.w(e) { "analyze() failed for $filePath" }
            return Either.Left(TrackAnalysisError.DecodeFailed(e.message ?: "decoder exception"))
        } finally {
            stream.close()
        }
    }
}

/**
 * Converts a chunk of interleaved PCM bytes to interleaved floats in the
 * approximate range [-1.0, +1.0]. Reuses an output buffer that grows as
 * needed; callers must consume the returned FloatArray before the next call
 * (no defensive copy).
 */
internal class PcmByteToFloat(private val format: SampleFormat) {
    private var buf: FloatArray = FloatArray(0)

    fun convert(bytes: ByteArray, byteCount: Int): FloatArray {
        val sampleSize = when (format) {
            SampleFormat.PCM_S16_LE -> 2
            SampleFormat.PCM_S24_LE -> 3
            SampleFormat.PCM_S32_LE -> 4
            SampleFormat.PCM_F32_LE -> 4
        }
        val sampleCount = byteCount / sampleSize
        if (buf.size < sampleCount) buf = FloatArray(sampleCount)

        when (format) {
            SampleFormat.PCM_S16_LE -> {
                var i = 0
                var bi = 0
                while (i < sampleCount) {
                    val lo = bytes[bi].toInt() and 0xFF
                    val hi = bytes[bi + 1].toInt()
                    val s = ((hi shl 8) or lo).toShort().toInt()
                    buf[i] = s / 32768f
                    i++
                    bi += 2
                }
            }
            SampleFormat.PCM_S24_LE -> {
                var i = 0
                var bi = 0
                while (i < sampleCount) {
                    val b0 = bytes[bi].toInt() and 0xFF
                    val b1 = bytes[bi + 1].toInt() and 0xFF
                    val b2 = bytes[bi + 2].toInt()  // signed
                    val s = (b2 shl 16) or (b1 shl 8) or b0
                    buf[i] = s / 8388608f
                    i++
                    bi += 3
                }
            }
            SampleFormat.PCM_S32_LE -> {
                val bb = java.nio.ByteBuffer.wrap(bytes, 0, byteCount).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i < sampleCount) {
                    val s = bb.int
                    buf[i] = s / 2147483648f
                    i++
                }
            }
            SampleFormat.PCM_F32_LE -> {
                val bb = java.nio.ByteBuffer.wrap(bytes, 0, byteCount).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i < sampleCount) {
                    buf[i] = bb.float
                    i++
                }
            }
        }
        return buf
    }
}
