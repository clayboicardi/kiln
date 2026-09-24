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
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A controllable [DecodedStream]. The test feeds frames via [emit], ends the stream
 * normally via [signalEof], or fails it via [signalError]. [closed] records that the
 * actor tore the stream down (so tests can assert old-stream teardown on switch/skip).
 * [frames] is re-collectable, like the real cold decode flow: the actor re-collects it after
 * a seek (a one-shot consumeAsFlow would throw there and kill the actor).
 */
internal class FakeDecodedStream(
    override val format: DecodedAudioFormat = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE),
    private val channel: Channel<AudioFrame> = Channel(Channel.UNLIMITED),
) : DecodedStream {
    @Volatile var closed: Boolean = false
        private set
    override val frames: Flow<AudioFrame> = channel.receiveAsFlow()
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
        // A torn-down stream can't be replayed (its frame channel is consumed). Model that as an
        // unreadable file, so RepeatMode.One / RepeatMode.All replays exercise the skip path.
        if (s.closed) return Either.Left(DecoderError.IoError(IllegalStateException("stream ${playable.itemId.value} already consumed")))
        return Either.Right(s)
    }
}

/**
 * No-hardware [PcmOutputLine]. Records writes, start/stop, interrupts, volume, and closes.
 * [queued] and [bufferNanos] drive the actor's EOF-tail park. [writeFailure], when set, is
 * thrown from write() (a device dying mid-stream). [startFailure], [queuedFailure],
 * [volumeFailure], and [flushFailure] likewise make start(), queuedNanos(), setVolume(), and
 * flush() throw (WASAPI's Start / GetCurrentPadding / SetMasterVolume / Stop+Reset on an
 * invalidated device).
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
    @Volatile var startFailure: Exception? = null
    @Volatile var queuedFailure: Exception? = null
    @Volatile var volumeFailure: Exception? = null
    @Volatile var flushFailure: Exception? = null
    val written = ArrayList<Byte>()

    override val isRunning: Boolean get() = running
    override fun start() {
        startFailure?.let { throw it }
        running = true
    }
    override fun stop() { running = false }
    override fun flush() {
        flushFailure?.let { throw it }
    }
    override fun write(bytes: ByteArray, off: Int, len: Int): Int {
        writeFailure?.let { throw it }
        for (i in off until off + len) written.add(bytes[i])
        return len
    }
    override fun queuedNanos(): Long {
        queuedFailure?.let { throw it }
        return queued
    }
    override fun setVolume(linear: Float) {
        volumeFailure?.let { throw it }
        lastVolume = linear
    }
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
