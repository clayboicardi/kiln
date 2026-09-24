// On-device probe (#37): which PCM layouts shared-mode IAudioClient.Initialize accepts with
// AUTOCONVERTPCM. WasapiTestGate skips it when there's no usable Windows render endpoint (CI) and
// fails it on a binding regression; an endpoint held exclusively by another app also skips. The
// matrix is printed to the test's stdout (build/test-results/.../TEST-*WasapiFormatProbeTest.xml,
// <system-out>). Binding coverage: only the enumerator, GetDefaultAudioEndpoint, Activate,
// Initialize and GetMixFormat (their GUIDs and vtable slots). The negative control proves
// Initialize's HRESULT distinguishes accept from reject. WasapiDeviceTest covers the rest.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.playback.output.MixFormat
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.PointerByReference
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasapiFormatProbeTest {

    /** The default render endpoint. Call after WasapiTestGate, so a failure here is a real one. */
    private fun defaultDevice(): ComPtr {
        ComInit.ensure()
        val ppv = PointerByReference()
        HResults.check(
            Ole32.INSTANCE.CoCreateInstance(
                WasapiIds.CLSID_MMDeviceEnumerator, null, WTypes.CLSCTX_ALL, WasapiIds.IID_IMMDeviceEnumerator, ppv,
            ).toInt(),
            "CoCreateInstance(MMDeviceEnumerator)",
        )
        val enumerator = ComPtr(ppv.value)
        try {
            HResults.check(
                enumerator.call(Vt.ENUM_GET_DEFAULT_AUDIO_ENDPOINT, WasapiConst.E_RENDER, WasapiConst.E_MULTIMEDIA, ppv),
                "GetDefaultAudioEndpoint",
            )
        } finally {
            enumerator.Release()
        }
        return ComPtr(ppv.value)
    }

    private fun activateClient(device: ComPtr): ComPtr {
        val ppv = PointerByReference()
        HResults.check(device.call(Vt.DEVICE_ACTIVATE, WasapiIds.IID_IAudioClient, WTypes.CLSCTX_ALL, null, ppv), "Activate")
        return ComPtr(ppv.value)
    }

    /**
     * Activate a fresh IAudioClient, Initialize with [wfx] and [flags], release; returns Initialize's
     * HRESULT. Always a fresh client: a client can't be re-Initialized (AUDCLNT_E_ALREADY_INITIALIZED),
     * and a rejected re-Initialize was seen to break its GetService(ISimpleAudioVolume) on Cortex.
     */
    private fun tryInitialize(device: ComPtr, wfx: ByteArray, flags: Int = WasapiConst.SHARED_STREAM_FLAGS): Int {
        val client = activateClient(device)
        try {
            val mem = Memory(wfx.size.toLong()).apply { write(0, wfx, 0, wfx.size) }
            return client.call(
                Vt.AC_INITIALIZE, WasapiConst.AUDCLNT_SHAREMODE_SHARED, flags,
                200 * 10_000L, 0L, mem, null,
            )
        } finally {
            client.Release()
        }
    }

    /** The endpoint's shared-mode mix format, read with GetMixFormat on a fresh IAudioClient. */
    private fun mixFormat(device: ComPtr): MixFormat {
        val client = activateClient(device)
        try {
            val ppFormat = PointerByReference()
            HResults.check(client.call(Vt.AC_GET_MIX_FORMAT, ppFormat), "GetMixFormat")
            val p = ppFormat.value
            try {
                val cbSize = p.getShort(16).toInt() and 0xFFFF
                return WaveFormatExtensible.decodeMix(p.getByteArray(0, 18 + cbSize))
            } finally {
                Ole32.INSTANCE.CoTaskMemFree(p)
            }
        } finally {
            client.Release()
        }
    }

    @Test
    fun `report which layouts shared-mode AUTOCONVERTPCM accepts`() {
        WasapiTestGate.requireRenderEndpoint()
        val device = defaultDevice()
        val layouts = listOf(16 to 16, 24 to 24, 32 to 24, 32 to 32) // container to valid bits
        val accepted = mutableMapOf<Pair<Int, Int>, Boolean>()
        var controlHr = HResults.S_OK
        try {
            for (rate in listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)) {
                for (ch in listOf(1, 2)) {
                    for ((container, valid) in layouts) {
                        val hr = tryInitialize(device, WaveFormatExtensible.encode(rate, ch, container, valid))
                        println("PROBE ${rate}Hz ${ch}ch ${container}c/${valid}v -> ${HResults.hex(hr)}")
                        Assume.assumeFalse(
                            "endpoint held exclusively by another app (${HResults.hex(hr)})",
                            hr == HResults.AUDCLNT_E_DEVICE_IN_USE,
                        )
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
            // Negative control: without AUTOCONVERTPCM, shared mode accepts only the mix format, so a
            // non-mix rate must be rejected. Proves the rows above can tell accept from reject.
            val mix = mixFormat(device)
            val controlRate = if (mix.sampleRateHz == 44_100) 48_000 else 44_100
            controlHr = tryInitialize(device, WaveFormatExtensible.encode(controlRate, 2, 24, 24), flags = 0)
            println(
                "PROBE-CONTROL ${controlRate}Hz 2ch 24c/24v flags=0 (mix: $mix) -> ${HResults.hex(controlHr)}",
            )
        } finally {
            device.Release()
        }
        println("PROBE SUMMARY (accepted at every rate/channel count): $accepted")
        assertEquals(
            HResults.hex(HResults.AUDCLNT_E_UNSUPPORTED_FORMAT), HResults.hex(controlHr),
            "a non-mix format without AUTOCONVERTPCM must be rejected; a success here means the binding " +
                "can't tell accept from reject (wrong vtable slot or GUID)",
        )
        assertTrue(accepted.getValue(16 to 16), "16-bit must be accepted everywhere")
        assertTrue(accepted.getValue(32 to 32), "32-bit int must be accepted everywhere")
        assertTrue(
            accepted.getValue(24 to 24) || accepted.getValue(32 to 24),
            "some 24-bit layout must be accepted everywhere",
        )
    }
}
