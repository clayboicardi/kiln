// Test doubles for the JavaSoundPlayerImpl actor (#28). A controllable DecodedStream/Decoder
// (feed frames, signal EOF, signal decode error) + a no-hardware SourceDataLine, so the
// behavioural tests in JavaSoundPlayerActorTest exercise switch/skip/pause/EOF/error/release
// without an audio device.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine

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
 * No-hardware [SourceDataLine]. Records bytes written + start/stop/open/close flags.
 * `isControlSupported` returns false so the player's applyGain() short-circuits (never calls
 * getControl). write() is non-blocking (the behavioural tests drive frames explicitly and
 * mostly leave the frame channel empty, so write is rarely invoked).
 */
internal class FakeLine : SourceDataLine {
    @Volatile var opened: Boolean = false
        private set
    @Volatile var running: Boolean = false
        private set
    @Volatile var closedCount: Int = 0
        private set
    val written = ArrayList<Byte>()
    private var openedFormat: AudioFormat? = null

    // ----- SourceDataLine -----
    override fun open(format: AudioFormat, bufferSize: Int) { openedFormat = format; opened = true }
    override fun open(format: AudioFormat) { openedFormat = format; opened = true }
    override fun write(b: ByteArray, off: Int, len: Int): Int {
        for (i in off until off + len) written.add(b[i])
        return len
    }

    // ----- DataLine -----
    override fun open() { opened = true }
    override fun drain() { /* no-op */ }
    override fun flush() { /* no-op */ }
    override fun start() { running = true }
    override fun stop() { running = false }
    override fun available(): Int = 0
    override fun isRunning(): Boolean = running
    override fun isActive(): Boolean = running
    override fun getFormat(): AudioFormat = openedFormat ?: AudioFormat(44_100f, 16, 2, true, false)
    override fun getBufferSize(): Int = 4096
    override fun getFramePosition(): Int = 0
    override fun getLongFramePosition(): Long = 0L
    override fun getMicrosecondPosition(): Long = 0L
    override fun getLevel(): Float = -1.0f  // AudioSystem.NOT_SPECIFIED

    // ----- Line -----
    override fun close() { closedCount++; opened = false; running = false }
    override fun isOpen(): Boolean = opened
    override fun getLineInfo(): Line.Info = Line.Info(SourceDataLine::class.java)
    override fun getControls(): Array<Control> = arrayOf()
    override fun isControlSupported(control: Control.Type): Boolean = false
    override fun getControl(control: Control.Type): Control =
        throw IllegalArgumentException("unsupported control: $control")
    override fun addLineListener(listener: LineListener) { /* no-op */ }
    override fun removeLineListener(listener: LineListener) { /* no-op */ }
}
