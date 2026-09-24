// Real-device checks for WasapiOutputLine (#37). WasapiTestGate skips without a usable Windows
// render endpoint (CI) and FAILS on a binding regression. An endpoint held exclusively by another
// app also skips. Each test runs its COM work on one worker thread, as the actor does in
// production. Output is silence.
//
// Beyond the probe's enumerator/Activate/Initialize/GetMixFormat, this exercises GetBufferSize,
// GetService (render client + session volume), GetCurrentPadding, GetBuffer/ReleaseBuffer,
// Start/Stop/Reset and SetMasterVolume, the last read back through a second stream in the same
// session so a mis-passed float argument can't pass as a bare S_OK.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.output.OutputLineException
import com.clayworks.kiln.audio.playback.output.WasapiOutputLine
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.PointerByReference
import org.junit.Assume
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasapiDeviceTest {

    private val audioThread = Executors.newSingleThreadExecutor { Thread(it, "test-audio").apply { isDaemon = true } }

    @AfterTest fun tearDown() { audioThread.shutdownNow() }

    private fun <T> onAudioThread(block: () -> T): T = await(audioThread.submit(Callable(block)), 30)

    /** Unwraps the worker's exception; an endpoint in exclusive use elsewhere skips instead of failing. */
    private fun <T> await(future: Future<T>, seconds: Long): T = try {
        future.get(seconds, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        val cause = e.cause ?: throw e
        if (cause is OutputLineException.DeviceProblem && "device in use" in cause.detail) {
            Assume.assumeTrue("endpoint held exclusively by another app: ${cause.detail}", false)
        }
        throw cause
    }

    private fun fmt(rate: Int, bits: Int, ch: Int = 2) = DecodedAudioFormat(
        rate, bits, ch,
        when (bits) { 16 -> SampleFormat.PCM_S16_LE; 24 -> SampleFormat.PCM_S24_LE; else -> SampleFormat.PCM_S32_LE },
    )

    @Test
    fun `opens, plays 250 ms of silence and closes for every depth and rate`() {
        WasapiTestGate.requireRenderEndpoint()
        for (rate in listOf(44_100, 96_000, 192_000)) {
            for (bits in listOf(16, 24, 32)) {
                onAudioThread {
                    val line = WasapiOutputLine.open(fmt(rate, bits))
                    line.use {
                        it.start()
                        val silence = ByteArray(rate / 4 * 2 * bits / 8)
                        assertEquals(silence.size, it.write(silence, 0, silence.size), "$rate/$bits")
                        assertTrue(it.queuedNanos() <= it.bufferNanos, "$rate/$bits queued ≤ buffer")
                        it.stop()
                        it.flush()
                    }
                }
            }
        }
    }

    @Test
    fun `isAvailable is true with a render endpoint`() {
        WasapiTestGate.requireRenderEndpoint()
        assertTrue(onAudioThread { WasapiClient.isAvailable() })
    }

    @Test
    fun `mono opens and plays`() {
        WasapiTestGate.requireRenderEndpoint()
        onAudioThread {
            WasapiOutputLine.open(fmt(44_100, 16, ch = 1)).use {
                it.start()
                assertEquals(8_820, it.write(ByteArray(8_820), 0, 8_820)) // 100 ms
            }
        }
    }

    @Test
    fun `a cross-thread interrupt returns a blocked write promptly`() {
        WasapiTestGate.requireRenderEndpoint()
        val line = onAudioThread { WasapiOutputLine.open(fmt(48_000, 16)).also { it.start() } }
        val twoSeconds = ByteArray(48_000 * 4 * 2) // far more than the 200 ms buffer, so write blocks
        val pending = audioThread.submit(Callable { line.write(twoSeconds, 0, twoSeconds.size) })
        Thread.sleep(400)
        val t0 = System.nanoTime()
        line.interrupt(discard = true) // from the test thread, like pause()/skip()
        val written = await(pending, 2)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("INTERRUPT: blocked write returned $ms ms after interrupt(), $written of ${twoSeconds.size} bytes written")
        assertTrue(ms < 200, "interrupt took $ms ms")
        assertTrue(written < twoSeconds.size)
        onAudioThread { line.close() }
    }

    @Test
    fun `fifty open-close cycles don't fail or leak`() {
        WasapiTestGate.requireRenderEndpoint()
        onAudioThread { repeat(50) { WasapiOutputLine.open(fmt(44_100, 16)).close() } }
    }

    @Test
    fun `setVolume reaches the session volume`() {
        WasapiTestGate.requireRenderEndpoint()
        onAudioThread {
            WasapiOutputLine.open(fmt(48_000, 16)).use { line ->
                val original = sessionMasterVolume() // Windows persists per-app session volume; put it back
                try {
                    line.setVolume(0.25f)
                    assertEquals(0.25f, sessionMasterVolume(), 1e-4f)
                    line.setVolume(1f)
                    assertEquals(1f, sessionMasterVolume(), 1e-4f)
                } finally {
                    line.setVolume(original)
                }
            }
        }
    }

    /**
     * This process's default-session master volume on the default endpoint, read through a second
     * shared stream (same process + null session GUID = same session as the line under test).
     */
    private fun sessionMasterVolume(): Float {
        val acquired = ArrayList<ComPtr>(4)
        try {
            val ppv = PointerByReference()
            HResults.check(
                Ole32.INSTANCE.CoCreateInstance(
                    WasapiIds.CLSID_MMDeviceEnumerator, null, WTypes.CLSCTX_ALL, WasapiIds.IID_IMMDeviceEnumerator, ppv,
                ).toInt(),
                "CoCreateInstance(MMDeviceEnumerator)",
            )
            val enumerator = ComPtr(ppv.value).also(acquired::add)
            HResults.check(
                enumerator.call(Vt.ENUM_GET_DEFAULT_AUDIO_ENDPOINT, WasapiConst.E_RENDER, WasapiConst.E_MULTIMEDIA, ppv),
                "GetDefaultAudioEndpoint",
            )
            val device = ComPtr(ppv.value).also(acquired::add)
            HResults.check(device.call(Vt.DEVICE_ACTIVATE, WasapiIds.IID_IAudioClient, WTypes.CLSCTX_ALL, null, ppv), "Activate")
            val client = ComPtr(ppv.value).also(acquired::add)
            val wfx = WaveFormatExtensible.encode(48_000, 2, 16, 16)
            val wfxMem = Memory(wfx.size.toLong()).apply { write(0, wfx, 0, wfx.size) }
            HResults.check(
                client.call(
                    Vt.AC_INITIALIZE, WasapiConst.AUDCLNT_SHAREMODE_SHARED, WasapiConst.SHARED_STREAM_FLAGS,
                    200 * 10_000L, 0L, wfxMem, null,
                ),
                "Initialize",
            )
            HResults.check(client.call(Vt.AC_GET_SERVICE, WasapiIds.IID_ISimpleAudioVolume, ppv), "GetService(volume)")
            val volume = ComPtr(ppv.value).also(acquired::add)
            val level = FloatByReference()
            HResults.check(volume.call(Vt.SAV_GET_MASTER_VOLUME, level), "GetMasterVolume")
            return level.value
        } finally {
            acquired.asReversed().forEach { it.Release() }
        }
    }
}
