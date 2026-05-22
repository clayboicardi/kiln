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

            // PCM encoding from MediaCodec output format. ENCODING_PCM_FLOAT (4)
            // on Android M+, otherwise ENCODING_PCM_16BIT (2). The actual output
            // format isn't available until the first onOutputFormatChanged event
            // (or after dequeueOutputBuffer returns INFO_OUTPUT_FORMAT_CHANGED);
            // until then assume 16-bit and re-read on the change event.
            var pcmEncoding = 2  // ENCODING_PCM_16BIT
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
                            2  // default 16-bit
                        }
                    }
                    outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit  // no output ready
                    outputIdx >= 0 -> {
                        val outputBuffer = mediaCodec.getOutputBuffer(outputIdx)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val sampleCount = when (pcmEncoding) {
                                4 -> bufferInfo.size / 4               // float
                                2 -> bufferInfo.size / 2               // 16-bit
                                else -> bufferInfo.size / 2            // assume 16-bit fallback
                            }
                            if (floatBuf.size < sampleCount) floatBuf = FloatArray(sampleCount)
                            val orderedBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            when (pcmEncoding) {
                                4 -> {
                                    for (i in 0 until sampleCount) floatBuf[i] = orderedBuffer.float
                                }
                                else -> {
                                    for (i in 0 until sampleCount) floatBuf[i] = orderedBuffer.short / 32768f
                                }
                            }
                            val frames = sampleCount / channels
                            if (frames > 0) analyzer.processSamples(floatBuf, frames)
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
