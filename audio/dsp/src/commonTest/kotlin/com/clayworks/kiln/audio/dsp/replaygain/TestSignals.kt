package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.PI
import kotlin.math.sin

internal object TestSignals {

    /**
     * Stereo sine wave with identical L=R. Interleaved as [L0, R0, L1, R1, ...].
     */
    fun sineStereo(
        frequencyHz: Double,
        peakAmplitude: Double,
        durationSec: Double,
        sampleRateHz: Int,
    ): FloatArray {
        val frames = (durationSec * sampleRateHz).toInt()
        val out = FloatArray(frames * 2)
        val twoPi = 2.0 * PI
        for (n in 0 until frames) {
            val v = (peakAmplitude * sin(twoPi * frequencyHz * n / sampleRateHz)).toFloat()
            out[2 * n] = v
            out[2 * n + 1] = v
        }
        return out
    }

    /** Mono sine wave; not interleaved (channels=1). */
    fun sineMono(
        frequencyHz: Double,
        peakAmplitude: Double,
        durationSec: Double,
        sampleRateHz: Int,
    ): FloatArray {
        val frames = (durationSec * sampleRateHz).toInt()
        val out = FloatArray(frames)
        val twoPi = 2.0 * PI
        for (n in 0 until frames) {
            out[n] = (peakAmplitude * sin(twoPi * frequencyHz * n / sampleRateHz)).toFloat()
        }
        return out
    }

    /** Stereo silence. */
    fun silenceStereo(durationSec: Double, sampleRateHz: Int): FloatArray =
        FloatArray((durationSec * sampleRateHz).toInt() * 2)

    /** Concatenate two stereo signals back-to-back. */
    fun concatStereo(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(a.size + b.size)
        a.copyInto(out, 0)
        b.copyInto(out, a.size)
        return out
    }
}
