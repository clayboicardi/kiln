// Desktop FLAC DecodedStream — wires the libFLAC stream-decoder callbacks +
// process_single Flow loop into the commonMain DecodedStream contract.
//
// Single-thread invariant: the Flow body runs on whatever dispatcher the
// caller collects with (JavaSoundPlayerImpl at H5 will collect on an audio
// dispatcher backed by a Thread.MAX_PRIORITY single-thread executor). libFLAC
// requires single-threaded access to a given decoder handle; this is preserved
// because process_single + the callbacks all run on the same thread.
//
// Callback lifetime: the three callback objects are stored as fields here so
// they outlive the decoder handle. libFLAC stores raw function pointers; if
// GC collected the wrappers mid-decode, the next callback invocation would
// crash the JVM.

package com.clayworks.kiln.audio.playback

import com.clayworks.kiln.audio.playback.nativeio.ErrorCallback
import com.clayworks.kiln.audio.playback.nativeio.FlacFrameReader
import com.clayworks.kiln.audio.playback.nativeio.FlacMetadata
import com.clayworks.kiln.audio.playback.nativeio.LibFlacBinding
import com.clayworks.kiln.audio.playback.nativeio.MetadataCallback
import com.clayworks.kiln.audio.playback.nativeio.StreamInfo
import com.clayworks.kiln.audio.playback.nativeio.WriteCallback
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class JvmFlacDecodedStream(
    private val libFlac: LibFlacBinding,
    private val handle: Pointer,
    playableDurationMs: Long,
) : DecodedStream {

    @Volatile
    private var streamInfoCaptured: StreamInfo? = null

    @Volatile
    private var pendingFrame: AudioFrame? = null

    @Volatile
    private var closed = false

    @Volatile
    override var positionMs: Long = 0L
        private set

    // Set from STREAMINFO's totalSamples when available; falls back to the
    // playable's reported duration (from the LibraryScanner) otherwise.
    @Volatile
    override var durationMs: Long = playableDurationMs
        private set

    override val format: DecodedAudioFormat
        get() {
            val si = streamInfoCaptured
                ?: error("STREAMINFO not yet captured — open() must run process_until_end_of_metadata before format is read")
            return DecodedAudioFormat(
                sampleRateHz = si.sampleRateHz,
                bitDepth = si.bitDepth,
                channels = si.channels,
                sampleFormat = when (si.bitDepth) {
                    16 -> SampleFormat.PCM_S16_LE
                    24 -> SampleFormat.PCM_S24_LE
                    32 -> SampleFormat.PCM_S32_LE
                    else -> error("Unexpected FLAC bit depth: ${si.bitDepth}")
                },
            )
        }

    // === JNA callbacks — keep strong refs ===

    internal val metadataCallback: MetadataCallback = object : MetadataCallback {
        override fun invoke(decoder: Pointer, metadata: Pointer, clientData: Pointer?) {
            FlacMetadata.parseStreamInfo(metadata)?.let { si ->
                streamInfoCaptured = si
                if (si.totalSamples > 0) {
                    durationMs = (si.totalSamples * 1000L) / si.sampleRateHz
                }
            }
        }
    }

    internal val writeCallback: WriteCallback = object : WriteCallback {
        override fun invoke(decoder: Pointer, frame: Pointer, buffer: Pointer, clientData: Pointer?): Int {
            val si = streamInfoCaptured ?: return WriteCallback.STATUS_ABORT
            val blocksize = FlacFrameReader.blocksize(frame)
            val sampleNumber = FlacFrameReader.sampleNumber(frame, blocksize)
            val pcm = FlacFrameReader.extractInterleavedPcm(
                bufferPtr = buffer,
                channels = si.channels,
                blocksize = blocksize,
                bitDepth = si.bitDepth,
            )
            val timestampMs = (sampleNumber * 1000L) / si.sampleRateHz
            pendingFrame = AudioFrame(
                bytes = pcm,
                byteCount = pcm.size,
                sampleCount = blocksize,
                timestampMs = timestampMs,
            )
            positionMs = timestampMs
            return WriteCallback.STATUS_CONTINUE
        }
    }

    internal val errorCallback: ErrorCallback = object : ErrorCallback {
        override fun invoke(decoder: Pointer, status: Int, clientData: Pointer?) {
            // No-op for now; decoder state will reflect the error. Future
            // enhancement: surface to a StateFlow<DecoderError?> for the
            // consumer to observe via a dedicated channel.
        }
    }

    // === DecodedStream contract ===

    override val frames: Flow<AudioFrame> = flow {
        while (!closed) {
            val state = libFlac.FLAC__stream_decoder_get_state(handle)
            when (state) {
                LibFlacBinding.STATE_END_OF_STREAM,
                LibFlacBinding.STATE_ABORTED,
                LibFlacBinding.STATE_OGG_ERROR,
                LibFlacBinding.STATE_SEEK_ERROR,
                LibFlacBinding.STATE_MEMORY_ALLOCATION_ERROR,
                LibFlacBinding.STATE_UNINITIALIZED,
                -> return@flow
            }
            val ok = libFlac.FLAC__stream_decoder_process_single(handle)
            if (!ok) return@flow
            pendingFrame?.let { frame ->
                pendingFrame = null
                emit(frame)
            }
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        val si = streamInfoCaptured ?: return
        val sample = (positionMs * si.sampleRateHz) / 1000L
        libFlac.FLAC__stream_decoder_seek_absolute(handle, sample)
        this.positionMs = positionMs
        // Drop any frame in flight — caller will receive the post-seek frame next.
        pendingFrame = null
    }

    override fun close() {
        if (closed) return
        closed = true
        libFlac.FLAC__stream_decoder_finish(handle)
        libFlac.FLAC__stream_decoder_delete(handle)
    }
}
