// Android TrackAnalyzer — uses MediaExtractor + MediaCodec synchronous-mode
// decoding to convert any device-supported audio codec to PCM, then feeds
// LoudnessAnalyzer for an EBU R128 / BS.1770-4 result.
//
// Why synchronous mode (not callback async): we want a simple end-to-end
// pump driven by a coroutine; the test surface is "give me a file, return
// the LUFS/dBTP". Real-time playback uses async (Media3 ExoPlayer); this
// analyzer is batch-style and doesn't need event-driven flow control.
//
// File-path semantics: filePath may be a filesystem path (e.g.,
// /storage/emulated/0/Music/song.flac from MediaStore.DATA) OR a content://
// URI from a SAF tree. MediaExtractor.setDataSource(Context, Uri, null) handles
// both — pass via Uri.parse(filePath) and the Android framework resolves.

package com.clayworks.kiln.audio.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.replaygain.AnalysisError
import com.clayworks.kiln.audio.dsp.replaygain.createLoudnessAnalyzer
import com.clayworks.kiln.library.scan.TrackAnalysisError
import com.clayworks.kiln.library.scan.TrackAnalyzer
import com.clayworks.kiln.library.scan.TrackLoudness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val log = Logger.withTag("AndroidMediaTrackAnalyzer")

private const val DEQUEUE_TIMEOUT_US = 10_000L  // 10 ms

/** Public factory keeping the impl class internal. */
fun createAndroidMediaTrackAnalyzer(context: Context): TrackAnalyzer =
    AndroidMediaTrackAnalyzer(context.applicationContext)

internal class AndroidMediaTrackAnalyzer(
    private val appContext: Context,
) : TrackAnalyzer {

    override suspend fun analyze(
        filePath: String,
        codec: String,
    ): Either<TrackAnalysisError, TrackLoudness> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var mediaCodec: MediaCodec? = null
        try {
            // Resolve filePath → Uri. Both filesystem paths and content:// URIs work.
            extractor.setDataSource(appContext, Uri.parse(filePath), null)

            // Find the first audio track.
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext Either.Left(
                TrackAnalysisError.DecodeFailed("no audio track found"),
            )
            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return@withContext Either.Left(TrackAnalysisError.DecodeFailed("no MIME type"))

            val sampleRateHz = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channels !in 1..2) {
                return@withContext Either.Left(
                    TrackAnalysisError.DecodeFailed("unsupported channel count: $channels (only 1 or 2 supported)"),
                )
            }

            mediaCodec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Throwable) {
                return@withContext Either.Left(
                    TrackAnalysisError.CodecUnsupported(codec),
                )
            }
            mediaCodec.configure(inputFormat, /* surface = */ null, /* crypto = */ null, /* flags = */ 0)
            mediaCodec.start()

            val analyzer = createLoudnessAnalyzer(sampleRateHz, channels)

            // PCM encoding from MediaCodec output format. Supported branches:
            //   ENCODING_PCM_16BIT          = 2  (pre-API-31 default)
            //   ENCODING_PCM_FLOAT          = 4  (API 21+)
            //   ENCODING_PCM_24BIT_PACKED   = 21 (API 31+, 3 bytes/sample LE signed)
            //   ENCODING_PCM_32BIT          = 22 (API 31+, 4 bytes/sample LE signed)
            //
            // The actual output format isn't available until the first
            // INFO_OUTPUT_FORMAT_CHANGED event. Per Android's MediaCodec
            // contract that event MUST fire before any data buffer; we
            // gate on `formatSeen` defensively against vendor codecs that
            // might violate the contract — pre-format-change data buffers
            // are dropped (would otherwise be misread as the initial 16-bit
            // default on 24/32-bit content).
            //
            // Unhandled encodings (ENCODING_PCM_8BIT, future API values,
            // manufacturer-specific) log a warning and skip the buffer
            // rather than silently corrupting the sample stream as the
            // pre-bug_002 code did.
            var pcmEncoding = 2  // initial guess; overwritten on first INFO_OUTPUT_FORMAT_CHANGED
            var formatSeen = false
            var encodingLogged = false
            var floatBuf = FloatArray(0)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIdx = mediaCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIdx >= 0) {
                        val inputBuffer = mediaCodec.getInputBuffer(inputIdx)
                            ?: error("getInputBuffer returned null for idx $inputIdx")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            mediaCodec.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            mediaCodec.queueInputBuffer(inputIdx, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIdx = mediaCodec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = mediaCodec.outputFormat
                        pcmEncoding = if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            2  // pre-API-31 default per Android docs
                        }
                        formatSeen = true
                    }
                    outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit  // no output ready
                    outputIdx >= 0 -> {
                        val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val outputBuffer = mediaCodec.getOutputBuffer(outputIdx)
                        val isUsableData = outputBuffer != null &&
                            bufferInfo.size > 0 &&
                            !isCodecConfig &&
                            formatSeen
                        if (isUsableData) {
                            val bytesPerSample = pcmEncodingToBytesPerSample(pcmEncoding)
                            if (bytesPerSample > 0) {
                                if (!encodingLogged) {
                                    log.i { "analyze: observed pcmEncoding=$pcmEncoding bytes/sample=$bytesPerSample for $filePath" }
                                    encodingLogged = true
                                }
                                outputBuffer!!.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val sampleCount = bufferInfo.size / bytesPerSample
                                if (floatBuf.size < sampleCount) floatBuf = FloatArray(sampleCount)
                                val orderedBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                decodePcmToFloat(orderedBuffer, sampleCount, pcmEncoding, floatBuf)
                                val frames = sampleCount / channels
                                if (frames > 0) analyzer.processSamples(floatBuf, frames)
                            } else {
                                log.w { "analyze: unhandled pcmEncoding=$pcmEncoding for $filePath; dropping buffer" }
                            }
                        }
                        mediaCodec.releaseOutputBuffer(outputIdx, /* render = */ false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val lufsEither = analyzer.integratedLufs()
            return@withContext when (lufsEither) {
                is Either.Left -> when (lufsEither.value) {
                    AnalysisError.InsufficientAudio -> Either.Left(
                        TrackAnalysisError.AnalysisFailed("InsufficientAudio: track < 3 s"),
                    )
                    AnalysisError.NoGatedBlocks -> Either.Left(
                        TrackAnalysisError.AnalysisFailed("NoGatedBlocks: track is silent"),
                    )
                }
                is Either.Right -> Either.Right(
                    TrackLoudness(
                        integratedLufs = lufsEither.value,
                        truePeakDbtp = analyzer.truePeakDbtp(),
                    ),
                )
            }
        } catch (e: Throwable) {
            log.w(e) { "analyze() failed for $filePath" }
            return@withContext Either.Left(TrackAnalysisError.DecodeFailed(e.message ?: "exception"))
        } finally {
            try {
                mediaCodec?.stop()
            } catch (_: Throwable) { /* swallow — release is what matters */ }
            try {
                mediaCodec?.release()
            } catch (_: Throwable) {}
            try {
                extractor.release()
            } catch (_: Throwable) {}
        }
    }
}

/**
 * Returns the bytes/sample for [pcmEncoding], or 0 for an unrecognized
 * encoding. Used as a sentinel so the caller can log + skip rather than
 * silently fall through to a wrong bytes/sample assumption.
 *
 * Reference: `android.media.AudioFormat.ENCODING_PCM_*` constants. Values
 * are hardcoded here to avoid pulling in AudioFormat just for constants;
 * SDK has had these stable since API 21 (16-bit, float) / 31 (24, 32).
 */
private fun pcmEncodingToBytesPerSample(pcmEncoding: Int): Int = when (pcmEncoding) {
    4 -> 4   // ENCODING_PCM_FLOAT
    21 -> 3  // ENCODING_PCM_24BIT_PACKED
    22 -> 4  // ENCODING_PCM_32BIT
    2 -> 2   // ENCODING_PCM_16BIT
    else -> 0
}

/**
 * Decode MediaCodec PCM bytes into normalized floats in [-1.0, 1.0).
 *
 * Endianness: MediaCodec output is documented little-endian on all
 * Android architectures (Android only ever runs on LE silicon). Caller
 * must have set ByteOrder.LITTLE_ENDIAN on [buffer] for the 16-bit /
 * 32-bit / float branches that use buffer.short/int/float accessors;
 * the 24-bit branch reads bytes individually so byte-order is explicit.
 *
 * 32-bit branch uses a Double intermediate to avoid Float-mantissa
 * rounding at Int.MAX_VALUE (`Int.MAX_VALUE.toFloat()` rounds UP to
 * exactly 2^31, which after division by 2^31f produces exactly 1.0f
 * — fabricating true-peak clipping. Caught by /multi:falsify on
 * bug_002 fix proposal Session 19, claude+gemini converged on it).
 */
private fun decodePcmToFloat(
    buffer: ByteBuffer,
    sampleCount: Int,
    pcmEncoding: Int,
    out: FloatArray,
) {
    when (pcmEncoding) {
        4 -> for (i in 0 until sampleCount) out[i] = buffer.float
        21 -> {
            // 24-bit packed little-endian, signed. Reconstruct via signed b2
            // for the sign extension; b0/b1 are unsigned bytes. Normalize by
            // 2^23 to get [-1.0, ~0.99999988].
            for (i in 0 until sampleCount) {
                val b0 = buffer.get().toInt() and 0xFF
                val b1 = buffer.get().toInt() and 0xFF
                val b2 = buffer.get().toInt()  // signed — produces sign extension
                val sample = (b2 shl 16) or (b1 shl 8) or b0
                out[i] = sample / 8_388_608f  // 2^23
            }
        }
        22 -> {
            // 32-bit signed int little-endian. Double-precision intermediate
            // avoids Float-mantissa rounding-up at 2^31 endpoint.
            for (i in 0 until sampleCount) {
                out[i] = (buffer.int.toDouble() / 2_147_483_648.0).toFloat()
            }
        }
        2 -> for (i in 0 until sampleCount) out[i] = buffer.short / 32_768f
        else -> {
            // unreachable — caller checks pcmEncodingToBytesPerSample() > 0
        }
    }
}
