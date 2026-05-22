package com.clayworks.kiln.audio.dsp.replaygain

/**
 * Single-channel biquad filter, Direct Form II Transposed.
 *
 * Numerically stable for `Double` floating-point under streaming audio;
 * the transposed form distributes round-off across two state variables
 * rather than accumulating it in a single feedback path.
 *
 * Coefficients use the standard convention where a0 is normalized to 1
 * (the caller pre-divides numerator and denominator by a0_raw).
 *
 * y[n] = b0*x[n] + s1[n-1]
 * s1[n] = b1*x[n] - a1*y[n] + s2[n-1]
 * s2[n] = b2*x[n] - a2*y[n]
 */
internal class BiquadFilter(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var s1: Double = 0.0
    private var s2: Double = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        s1 = 0.0
        s2 = 0.0
    }
}
