// Named HRESULTs Kiln acts on, and HRESULT → OutputLineException (#37). AUDCLNT_E_* codes are
// MAKE_HRESULT(SEVERITY_ERROR, FACILITY_AUDCLNT = 0x889, n) from audioclient.h.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.playback.output.OutputLineException

internal object HResults {
    const val S_OK = 0
    const val S_FALSE = 1
    val RPC_E_CHANGED_MODE = 0x80010106L.toInt()
    val E_NOTFOUND = 0x80070490L.toInt()                    // HRESULT_FROM_WIN32(ERROR_NOT_FOUND)
    val AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004L.toInt()
    val AUDCLNT_E_UNSUPPORTED_FORMAT = 0x88890008L.toInt()
    val AUDCLNT_E_DEVICE_IN_USE = 0x8889000AL.toInt()
    val AUDCLNT_E_SERVICE_NOT_RUNNING = 0x88890010L.toInt()

    fun hex(hr: Int): String = "0x%08X".format(hr)

    fun toException(hr: Int, what: String, format: DecodedAudioFormat?): OutputLineException = when (hr) {
        AUDCLNT_E_UNSUPPORTED_FORMAT ->
            if (format != null) OutputLineException.FormatRejected(format, "$what: unsupported format (${hex(hr)})")
            else OutputLineException.DeviceProblem("$what: unsupported format (${hex(hr)})")
        AUDCLNT_E_DEVICE_INVALIDATED -> OutputLineException.DeviceProblem("$what: device invalidated (${hex(hr)})")
        AUDCLNT_E_DEVICE_IN_USE -> OutputLineException.DeviceProblem("$what: device in use (${hex(hr)})")
        E_NOTFOUND -> OutputLineException.DeviceProblem("$what: no audio output device (${hex(hr)})")
        AUDCLNT_E_SERVICE_NOT_RUNNING ->
            OutputLineException.DeviceProblem("$what: Windows audio service not running (${hex(hr)})")
        else -> OutputLineException.DeviceProblem("$what failed: ${hex(hr)}")
    }

    /** Throws for a failed HRESULT (negative). S_OK and S_FALSE pass. */
    fun check(hr: Int, what: String, format: DecodedAudioFormat? = null) {
        if (hr < 0) throw toException(hr, what, format)
    }
}
