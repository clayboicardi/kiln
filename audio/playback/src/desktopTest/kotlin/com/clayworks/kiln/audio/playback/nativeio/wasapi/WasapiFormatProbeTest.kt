// On-device probe (#37): which PCM layouts shared-mode IAudioClient.Initialize accepts with
// AUTOCONVERTPCM. Skips when there's no Windows audio endpoint (CI). The matrix is printed to the
// test's stdout (build/test-results/.../TEST-*WasapiFormatProbeTest.xml, <system-out>). It also
// verifies the vtable slots and GUIDs: a wrong slot crashes or returns garbage here, not in prod.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.sun.jna.Memory
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.PointerByReference
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertTrue

class WasapiFormatProbeTest {

    private fun defaultDevice(): ComPtr? = runCatching {
        ComInit.ensure()
        val ppv = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            WasapiIds.CLSID_MMDeviceEnumerator, null, WTypes.CLSCTX_ALL, WasapiIds.IID_IMMDeviceEnumerator, ppv,
        ).toInt()
        if (hr < 0) return@runCatching null
        val enumerator = ComPtr(ppv.value)
        val hr2 = enumerator.call(Vt.ENUM_GET_DEFAULT_AUDIO_ENDPOINT, WasapiConst.E_RENDER, WasapiConst.E_MULTIMEDIA, ppv)
        enumerator.Release()
        if (hr2 < 0) null else ComPtr(ppv.value)
    }.getOrNull()

    /** Activate a fresh IAudioClient, Initialize with [wfx], release; returns Initialize's HRESULT. */
    private fun tryInitialize(device: ComPtr, wfx: ByteArray): Int {
        val ppv = PointerByReference()
        HResults.check(device.call(Vt.DEVICE_ACTIVATE, WasapiIds.IID_IAudioClient, WTypes.CLSCTX_ALL, null, ppv), "Activate")
        val client = ComPtr(ppv.value)
        try {
            val mem = Memory(wfx.size.toLong()).apply { write(0, wfx, 0, wfx.size) }
            return client.call(
                Vt.AC_INITIALIZE, WasapiConst.AUDCLNT_SHAREMODE_SHARED, WasapiConst.SHARED_STREAM_FLAGS,
                200 * 10_000L, 0L, mem, null,
            )
        } finally {
            client.Release()
        }
    }

    @Test
    fun `report which layouts shared-mode AUTOCONVERTPCM accepts`() {
        Assume.assumeTrue("Windows only", System.getProperty("os.name").startsWith("Windows"))
        val device = defaultDevice()
        Assume.assumeTrue("no default audio endpoint", device != null)
        device!!
        val layouts = listOf(16 to 16, 24 to 24, 32 to 24, 32 to 32) // container to valid bits
        val accepted = mutableMapOf<Pair<Int, Int>, Boolean>()
        try {
            for (rate in listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)) {
                for (ch in listOf(1, 2)) {
                    for ((container, valid) in layouts) {
                        val hr = tryInitialize(device, WaveFormatExtensible.encode(rate, ch, container, valid))
                        println("PROBE ${rate}Hz ${ch}ch ${container}c/${valid}v -> ${HResults.hex(hr)}")
                        accepted[container to valid] = (accepted[container to valid] ?: true) && hr >= 0
                    }
                }
            }
            // Info only (not in `accepted`, not asserted): how Windows answers a multichannel layout
            // with channel mask 0 (what channelMask(6) returns), unsupported-format vs invalid-arg.
            for ((container, valid) in listOf(16 to 16, 24 to 24)) {
                val hr = tryInitialize(device, WaveFormatExtensible.encode(48_000, 6, container, valid))
                println("PROBE-INFO 48000Hz 6ch ${container}c/${valid}v -> ${HResults.hex(hr)}")
            }
        } finally {
            device.Release()
        }
        println("PROBE SUMMARY (accepted at every rate/channel count): $accepted")
        assertTrue(accepted.getValue(16 to 16), "16-bit must be accepted everywhere")
        assertTrue(accepted.getValue(32 to 32), "32-bit int must be accepted everywhere")
        assertTrue(
            accepted.getValue(24 to 24) || accepted.getValue(32 to 24),
            "some 24-bit layout must be accepted everywhere",
        )
    }
}
