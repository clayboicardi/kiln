// Test doubles for the JavaSoundPlayerImpl actor (#28). A controllable DecodedStream/Decoder
// (feed frames, signal EOF, signal decode error) + a no-hardware PcmOutputLine, so the
// behavioural tests in JavaSoundPlayerActorTest exercise switch/skip/pause/EOF/error/release
// without an audio device.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.output.PcmOutputLine
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * A controllable [DecodedStream]. The test feeds frames via [emit], ends the stream
 * normally via [signalEof], or fails it via [signalError]. [closed] records that the
 * actor tore the stream down (so tests can assert old-stream teardown on switch/skip).
 */
internal class FakeDecodedStream(
    override val format: DecodedAudioFormat = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE),
    private val channel: Channel<AudioFrame> = Channel(Channel.UNLIMITED),
) : DecodedStream {
    @Volatile var closed: Boolean = false
        private set
    override val frames: Flow<AudioFrame> = channel.consumeAsFlow()
    override val positionMs: Long = 0L
    override val durationMs: Long = 0L
    override suspend fun seekTo(positionMs: Long) { /* no-op */ }
    override fun close() {
        closed = true
        channel.close()
    }

    fun emit(frame: AudioFrame) { channel.trySend(frame) }
    fun signalEof() { channel.close() }
    fun signalError(cause: Throwable) { channel.close(cause) }
}

/** Resolves each [Playable] (by itemId) to a pre-built [FakeDecodedStream]. */
internal class FakeDecoder(private val streams: Map<String, FakeDecodedStream>) : Decoder {
    @Volatile var openCount: Int = 0
        private set

    override fun supports(codec: AudioCodec): Boolean = true

    override suspend fun open(playable: Playable): Either<DecoderError, DecodedStream> {
        openCount++
        val s = streams[playable.itemId.value]
            ?: return Either.Left(DecoderError.Internal("no fake stream for ${playable.itemId.value}"))
        return Either.Right(s)
    }
}

/**
 * No-hardware [PcmOutputLine]. Records writes, start/stop, interrupts, volume, and closes.
 * [queued] and [bufferNanos] drive the actor's EOF-tail park. [writeFailure], when set, is
 * thrown from write() (a device dying mid-stream).
 */
internal class FakeOutputLine(
    override val sourceFormat: DecodedAudioFormat = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE),
    override val bufferNanos: Long = 100_000_000L,
    @Volatile var queued: Long = 0L,
) : PcmOutputLine {
    @Volatile var running: Boolean = false
        private set
    @Volatile var closedCount: Int = 0
        private set
    @Volatile var interrupts: Int = 0
        private set
    @Volatile var lastVolume: Float? = null
        private set
    @Volatile var writeFailure: Exception? = null
    val written = ArrayList<Byte>()

    override val isRunning: Boolean get() = running
    override fun start() { running = true }
    override fun stop() { running = false }
    override fun flush() = Unit
    override fun write(bytes: ByteArray, off: Int, len: Int): Int {
        writeFailure?.let { throw it }
        for (i in off until off + len) written.add(bytes[i])
        return len
    }
    override fun queuedNanos(): Long = queued
    override fun setVolume(linear: Float) { lastVolume = linear }
    override fun route(): OutputRoute = OutputRoute(
        engine = OutputEngine.WasapiShared,
        deviceRateHz = 48_000,
        deviceFormat = "32-bit float mix",
        sourceRateHz = sourceFormat.sampleRateHz,
        sourceBitDepth = sourceFormat.bitDepth,
    )
    override fun interrupt(discard: Boolean) { interrupts++ }
    override fun close() { closedCount++; running = false }
}
