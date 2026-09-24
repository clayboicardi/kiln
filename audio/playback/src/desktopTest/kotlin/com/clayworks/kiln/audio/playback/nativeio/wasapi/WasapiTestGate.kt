// Strict gate for the on-device WASAPI tests (#37). Skips ONLY when the machine has no usable
// render endpoint (not Windows, no device, audio service stopped: a CI runner). Anything else
// that goes wrong while reaching the endpoint is a real failure, because on a Windows box with
// audio it means the COM binding regressed (wrong CLSID/IID/vtable slot), and swallowing that
// into a skip would show a broken binding as green.
//
// Call it on the test thread, not inside an executor: an Assume thrown on another thread would
// arrive wrapped in an ExecutionException and fail the test instead of skipping it.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.PointerByReference
import org.junit.Assume
import kotlin.test.fail

internal object WasapiTestGate {

    fun requireRenderEndpoint() {
        Assume.assumeTrue("Windows only", System.getProperty("os.name").startsWith("Windows"))
        ComInit.ensure()
        val ppv = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            WasapiIds.CLSID_MMDeviceEnumerator, null, WTypes.CLSCTX_ALL, WasapiIds.IID_IMMDeviceEnumerator, ppv,
        ).toInt()
        if (hr < 0) fail("CoCreateInstance(MMDeviceEnumerator) failed: ${HResults.hex(hr)} (binding regression?)")
        val enumerator = ComPtr(ppv.value)
        try {
            val hr2 = enumerator.call(
                Vt.ENUM_GET_DEFAULT_AUDIO_ENDPOINT, WasapiConst.E_RENDER, WasapiConst.E_MULTIMEDIA, ppv,
            )
            Assume.assumeFalse(
                "no usable default render endpoint (${HResults.hex(hr2)})",
                hr2 == HResults.E_NOTFOUND || hr2 == HResults.AUDCLNT_E_SERVICE_NOT_RUNNING,
            )
            if (hr2 < 0) fail("GetDefaultAudioEndpoint failed: ${HResults.hex(hr2)} (binding regression?)")
            ComPtr(ppv.value).Release()
        } finally {
            enumerator.Release()
        }
    }
}
