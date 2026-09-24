// JavaSoundOutputLine: the Java Sound fallback line. Durations come from the line's own frame
// size and rate; interrupt() keeps the pre-#37 stop()+flush() seam.

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaSoundOutputLineTest {

    private val fmt = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE)

    @Test
    fun `open sizes the line buffer to 100 ms of the source format`() {
        val sdl = FakeSourceDataLine()
        JavaSoundOutputLine.open(fmt) { sdl }
        assertEquals(17_640, sdl.openedBufferSize) // 44100 * 2 ch * 2 B / 10
    }

    @Test
    fun `bufferNanos and queuedNanos are durations in the line's own frames`() {
        val sdl = FakeSourceDataLine(bufferSize = 17_640, available = 8_820)
        val line = JavaSoundOutputLine(sdl, fmt)
        assertEquals(100_000_000L, line.bufferNanos)
        assertEquals(50_000_000L, line.queuedNanos()) // 8820 queued bytes = 2205 frames = 50 ms
    }

    @Test
    fun `interrupt with discard stops and flushes, without discard only stops`() {
        val sdl = FakeSourceDataLine()
        val line = JavaSoundOutputLine(sdl, fmt)
        line.interrupt(discard = false)
        assertEquals(listOf("stop"), sdl.calls)
        line.interrupt(discard = true)
        assertEquals(listOf("stop", "stop", "flush"), sdl.calls)
    }

    @Test
    fun `setVolume without a gain control is a no-op`() {
        JavaSoundOutputLine(FakeSourceDataLine(), fmt).setVolume(0.5f) // must not throw
    }

    /** No-hardware SourceDataLine. Records calls; no gain control, so setVolume short-circuits. */
    private class FakeSourceDataLine(
        private val bufferSize: Int = 4096,
        private val available: Int = 0,
    ) : SourceDataLine {
        val calls = mutableListOf<String>()
        var openedBufferSize: Int = -1
        override fun open(format: AudioFormat, bufferSize: Int) { openedBufferSize = bufferSize }
        override fun open(format: AudioFormat) = Unit
        override fun write(b: ByteArray, off: Int, len: Int): Int = len
        override fun open() = Unit
        override fun drain() = Unit
        override fun flush() { calls += "flush" }
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun available(): Int = available
        override fun isRunning(): Boolean = false
        override fun isActive(): Boolean = false
        override fun getFormat(): AudioFormat = AudioFormat(44_100f, 16, 2, true, false)
        override fun getBufferSize(): Int = bufferSize
        override fun getFramePosition(): Int = 0
        override fun getLongFramePosition(): Long = 0L
        override fun getMicrosecondPosition(): Long = 0L
        override fun getLevel(): Float = -1.0f
        override fun close() { calls += "close" }
        override fun isOpen(): Boolean = true
        override fun getLineInfo(): Line.Info = Line.Info(SourceDataLine::class.java)
        override fun getControls(): Array<Control> = arrayOf()
        override fun isControlSupported(control: Control.Type): Boolean = false
        override fun getControl(control: Control.Type): Control = throw IllegalArgumentException("no $control")
        override fun addLineListener(listener: LineListener) = Unit
        override fun removeLineListener(listener: LineListener) = Unit
    }
}
