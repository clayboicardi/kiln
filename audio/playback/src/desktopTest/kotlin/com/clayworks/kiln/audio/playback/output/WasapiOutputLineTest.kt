// WasapiOutputLine's write/interrupt/flush logic against a fake RenderPort. No device needed.

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.OutputEngine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasapiOutputLineTest {

    private val fmt = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE) // 4-byte frames

    /** After close() every method throws, like a port whose device was invalidated. */
    private class FakePort(
        override val bufferFrames: Int = 1_000,
        override val clientRateHz: Int = 44_100,
        override val mix: MixFormat = MixFormat(48_000, 32, isFloat = true),
    ) : RenderPort {
        @Volatile var padding = 0
        @Volatile var closed = false
        @Volatile var failReset = false
        @Volatile var failStop = false
        val calls = CopyOnWriteArrayList<String>()
        val framesWritten = AtomicInteger()
        private fun live() = check(!closed) { "port closed" }
        override fun paddingFrames(): Int { live(); return padding }
        override fun writeFrames(src: ByteArray, off: Int, frames: Int) { live(); framesWritten.addAndGet(frames); padding += frames }
        override fun start() { live(); calls += "start" }
        override fun stop() { live(); if (failStop) error("stop failed"); calls += "stop" }
        override fun reset() { live(); if (failReset) error("reset failed"); calls += "reset"; padding = 0 }
        override fun setVolume(level: Float) { live(); calls += "volume:$level" }
        override fun close() { calls += "close"; closed = true }
    }

    @Test
    fun `write fills free space and returns the full length`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        assertEquals(1_600, line.write(ByteArray(1_600), 0, 1_600)) // 400 frames
        assertEquals(400, port.framesWritten.get())
    }

    @Test
    fun `write waits on a full buffer until space frees up`() {
        val port = FakePort().apply { padding = 1_000 }
        val line = WasapiOutputLine(port, fmt)
        var result = -1
        val t = thread { result = line.write(ByteArray(1_600), 0, 1_600) }
        Thread.sleep(60)
        assertTrue(t.isAlive, "must block while the buffer is full")
        port.padding = 0
        t.join(2_000)
        assertFalse(t.isAlive)
        assertEquals(1_600, result)
    }

    @Test
    fun `a cross-thread interrupt makes a blocked write return promptly`() {
        val port = FakePort().apply { padding = 1_000 } // full forever
        val line = WasapiOutputLine(port, fmt)
        var result = -1
        val t = thread { result = line.write(ByteArray(1_600), 0, 1_600) }
        Thread.sleep(60)
        val t0 = System.nanoTime()
        line.interrupt(discard = false)
        t.join(2_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertFalse(t.isAlive)
        assertTrue(ms < 200, "interrupt took $ms ms")
        assertEquals(0, result)
        assertTrue(port.calls.none { it == "stop" }, "interrupt must not touch the device (no COM off the actor)")
    }

    @Test
    fun `start after an interrupt re-arms writes`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.interrupt(discard = true)
        assertEquals(0, line.write(ByteArray(40), 0, 40))
        line.start()
        assertEquals(40, line.write(ByteArray(40), 0, 40))
    }

    @Test
    fun `flush after an interrupt re-arms writes`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.interrupt(discard = true)
        assertEquals(0, line.write(ByteArray(40), 0, 40))
        line.flush()
        assertEquals(40, line.write(ByteArray(40), 0, 40))
    }

    @Test
    fun `start is idempotent while running`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.start(); line.start()
        assertEquals(listOf("start"), port.calls)
        assertTrue(line.isRunning)
    }

    @Test
    fun `flush while running stops, resets and restarts`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.start()
        line.flush()
        assertEquals(listOf("start", "stop", "reset", "start"), port.calls)
        assertTrue(line.isRunning)
    }

    @Test
    fun `a running flush whose reset fails leaves the line reporting stopped`() {
        val port = FakePort().apply { failReset = true }
        val line = WasapiOutputLine(port, fmt)
        line.start()
        assertFailsWith<IllegalStateException> { line.flush() } // the failure propagates
        assertEquals(listOf("start", "stop"), port.calls)
        assertFalse(line.isRunning, "the stream was stopped before reset failed")
        line.start() // so a later start() really restarts the stream
        assertEquals(listOf("start", "stop", "start"), port.calls)
    }

    @Test
    fun `open rejects a float source before any device call`() {
        val f32 = DecodedAudioFormat(48_000, 32, 2, SampleFormat.PCM_F32_LE)
        val e = assertFailsWith<OutputLineException.FormatRejected> { WasapiOutputLine.open(f32) }
        assertEquals(f32, e.format)
    }

    @Test
    fun `flush while stopped only resets`() {
        val port = FakePort()
        WasapiOutputLine(port, fmt).flush()
        assertEquals(listOf("reset"), port.calls)
    }

    @Test
    fun `stop keeps buffered audio and is idempotent`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.start(); line.stop(); line.stop()
        assertEquals(listOf("start", "stop"), port.calls)
        assertFalse(line.isRunning)
    }

    @Test
    fun `a stop whose port stop fails propagates and leaves the line reporting stopped`() {
        // Final review M1: with running left true, the next start() skipped the device call, so a
        // dead device played silence instead of surfacing its error.
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.start()
        port.failStop = true
        assertFailsWith<IllegalStateException> { line.stop() } // the failure propagates
        assertFalse(line.isRunning, "a failed stop must not leave the line reporting running")
        port.failStop = false
        line.start() // so a later start() really goes back to the device
        assertEquals(listOf("start", "start"), port.calls)
    }

    @Test
    fun `bufferNanos and queuedNanos are durations at the client rate`() {
        val port = FakePort(bufferFrames = 8_820).apply { padding = 4_410 }
        val line = WasapiOutputLine(port, fmt)
        assertEquals(200_000_000L, line.bufferNanos)
        assertEquals(100_000_000L, line.queuedNanos())
    }

    @Test
    fun `route reports the device mix and flags resampling`() {
        val route = WasapiOutputLine(FakePort(), fmt).route()
        assertEquals(OutputEngine.WasapiShared, route.engine)
        assertEquals(48_000, route.deviceRateHz)
        assertEquals("32-bit float mix", route.deviceFormat)
        assertTrue(route.resampled)
    }

    @Test
    fun `route and isRunning make no device calls, so they don't throw once the port is dead`() {
        // The actor reads both outside its error guards (PcmOutputLine contract).
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.start()
        port.close()
        assertFailsWith<IllegalStateException> { port.paddingFrames() } // the fake really is dead
        assertTrue(line.isRunning)
        assertEquals(OutputEngine.WasapiShared, line.route().engine)
        assertEquals(48_000, line.route().deviceRateHz)
    }

    @Test
    fun `write rejects a partial frame`() {
        assertFailsWith<IllegalArgumentException> { WasapiOutputLine(FakePort(), fmt).write(ByteArray(6), 0, 6) }
    }

    @Test
    fun `setVolume clamps to the 0-1 range`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.setVolume(1.5f); line.setVolume(-1f)
        assertEquals(listOf("volume:1.0", "volume:0.0"), port.calls)
    }
}
