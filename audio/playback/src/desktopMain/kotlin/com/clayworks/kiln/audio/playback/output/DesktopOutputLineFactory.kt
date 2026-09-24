// DesktopOutputLineFactory: the production PcmOutputLineFactory (#37). Decides ONCE, lazily on
// the first open (so on the actor thread, where COM gets initialized), whether WASAPI works on
// this machine. Endpoint presence is not part of that decision: "no device right now" is a
// transient DeviceProblem and must not downgrade the session to 16-bit Java Sound. A WASAPI
// format rejection is never retried on Java Sound, which would be strictly worse.

package com.clayworks.kiln.audio.playback.output

import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.playback.nativeio.wasapi.WasapiClient

private val log = Logger.withTag("OutputLine")

internal class DesktopOutputLineFactory(
    private val wasapiAvailable: () -> Boolean = { WasapiClient.isAvailable() },
    private val openWasapi: (DecodedAudioFormat) -> PcmOutputLine = { WasapiOutputLine.open(it) },
    private val openJavaSound: (DecodedAudioFormat) -> PcmOutputLine = { JavaSoundOutputLine.open(it) },
) : PcmOutputLineFactory {

    private val useWasapi: Boolean by lazy {
        wasapiAvailable().also { ok ->
            if (ok) log.i { "Output engine: WASAPI shared mode" }
            else log.w { "WASAPI unavailable; falling back to Java Sound (16-bit only on Windows)" }
        }
    }

    override fun open(format: DecodedAudioFormat): PcmOutputLine =
        if (useWasapi) openWasapi(format) else openJavaSound(format)
}
