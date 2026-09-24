// COM plumbing for WASAPI over jna-platform (#37). ComPtr wraps an interface pointer and calls
// vtable slots by index (COMInvoker._invokeNativeInt is protected, hence the subclass; the
// `this` pointer is passed explicitly as the first argument, as jna-platform's own wrappers do).
// Slot indices follow each interface's declaration order after IUnknown's 0-2 (QueryInterface,
// AddRef, Release). Slot indices, GUIDs, HRESULTs and flags verified against Windows SDK 10.0.22621
// headers; the enumerator/Activate/Initialize path exercised on Cortex by WasapiFormatProbeTest (2026-09-24).

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.playback.output.OutputLineException
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32

internal class ComPtr(p: Pointer) : Unknown(p) {
    fun call(vtableIndex: Int, vararg args: Any?): Int = _invokeNativeInt(vtableIndex, arrayOf<Any?>(pointer, *args))
}

internal object WasapiIds {
    val CLSID_MMDeviceEnumerator = Guid.GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    val IID_IMMDeviceEnumerator = Guid.GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    val IID_IAudioClient = Guid.GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")
    val IID_IAudioRenderClient = Guid.GUID("{F294ACFC-3146-4483-A7BF-ADDCA7C260E2}")
    val IID_ISimpleAudioVolume = Guid.GUID("{87CE5498-68D6-44E5-9215-6DA47EF883D8}")
}

/** vtable slots. */
internal object Vt {
    // IMMDeviceEnumerator (mmdeviceapi.h): EnumAudioEndpoints=3, GetDefaultAudioEndpoint=4
    const val ENUM_GET_DEFAULT_AUDIO_ENDPOINT = 4
    // IMMDevice (mmdeviceapi.h): Activate=3
    const val DEVICE_ACTIVATE = 3
    // IAudioClient (audioclient.h): Initialize=3, GetBufferSize=4, GetStreamLatency=5,
    // GetCurrentPadding=6, IsFormatSupported=7, GetMixFormat=8, GetDevicePeriod=9, Start=10,
    // Stop=11, Reset=12, SetEventHandle=13, GetService=14
    const val AC_INITIALIZE = 3
    const val AC_GET_BUFFER_SIZE = 4
    const val AC_GET_CURRENT_PADDING = 6
    const val AC_GET_MIX_FORMAT = 8
    const val AC_START = 10
    const val AC_STOP = 11
    const val AC_RESET = 12
    const val AC_GET_SERVICE = 14
    // IAudioRenderClient (audioclient.h): GetBuffer=3, ReleaseBuffer=4
    const val RC_GET_BUFFER = 3
    const val RC_RELEASE_BUFFER = 4
    // ISimpleAudioVolume (audioclient.h): SetMasterVolume=3
    const val SAV_SET_MASTER_VOLUME = 3
}

internal object WasapiConst {
    const val E_RENDER = 0
    const val E_MULTIMEDIA = 1
    const val AUDCLNT_SHAREMODE_SHARED = 0
    private val AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000L.toInt()
    private const val AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000
    /** Let Windows convert any PCM to its float mix, resampling (default quality) if needed. */
    val SHARED_STREAM_FLAGS = AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM or AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY
}

/** COM is initialized once per thread (MTA). A thread that's already STA (RPC_E_CHANGED_MODE) still works. */
internal object ComInit {
    private val initialized = ThreadLocal.withInitial { false }

    fun ensure() {
        if (initialized.get()) return
        val hr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED).toInt()
        if (hr < 0 && hr != HResults.RPC_E_CHANGED_MODE) {
            throw OutputLineException.DeviceProblem("CoInitializeEx failed: ${HResults.hex(hr)}")
        }
        initialized.set(true)
    }
}
