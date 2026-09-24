// Typed output-line failures (#37). FormatRejected = the engine works but won't take this PCM
// layout (a per-track problem). DeviceProblem = no usable device (it would fail every track).

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat

internal sealed class OutputLineException(message: String, cause: Throwable?) : Exception(message, cause) {
    class FormatRejected(val format: DecodedAudioFormat, val detail: String, cause: Throwable? = null) :
        OutputLineException("output rejected $format: $detail", cause)

    class DeviceProblem(val detail: String, cause: Throwable? = null) :
        OutputLineException("output device problem: $detail", cause)
}
