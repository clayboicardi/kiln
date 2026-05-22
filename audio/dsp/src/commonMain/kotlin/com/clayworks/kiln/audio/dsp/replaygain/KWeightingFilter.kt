package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Single-channel K-weighting filter per ITU-R BS.1770-4 Annex 1.
 *
 * Cascade of two biquads:
 *   1. Pre-filter — high-shelf at fc=1681.974 Hz with HF gain Vh=10^(4/20).
 *      Designed via bilinear transform of the K-domain quadratic pair
 *        N(K) = Vh + sqrt(2·Vh)·K + K²    (HF-boost numerator)
 *        D(K) = 1 + sqrt(2)·K + K²        (Butterworth denominator)
 *      with K = tan(π·fc/fs). DC gain = 1.0, HF gain = Vh = +4 dB.
 *      Approximates head-related transfer function for typical listeners.
 *   2. RLB filter — high-pass at fc=38.13547 Hz, Q=0.5003270, designed via
 *      bilinear transform of the analog HP prototype H_a(s) = s²/(s² + (1/Q)·s + 1)
 *      with K = tan(π·fc/fs). Removes sub-audible energy from contributing
 *      to loudness.
 *
 * Coefficients match BS.1770-4's documented 48 kHz table within ~5e-5.
 *
 * [BiquadCoefficients] stores pre-filter numerator coefficients in normalized
 * form (already divided by a0). RLB numerator coefficients are stored raw
 * (before /a0) to mirror BS.1770-4's table presentation; [KWeightingFilter.init]
 * normalizes them via [BiquadCoefficients.a0] when constructing the internal
 * [BiquadFilter].
 */
internal class KWeightingFilter(sampleRateHz: Int) {
    private val pre: BiquadFilter
    private val rlb: BiquadFilter

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive; got $sampleRateHz" }
        val preC = preFilterCoefficients(sampleRateHz)
        val rlbC = rlbFilterCoefficients(sampleRateHz)
        // pre-filter: a0 = 1.0 (coefficients already normalized)
        pre = BiquadFilter(preC.b0 / preC.a0, preC.b1 / preC.a0, preC.b2 / preC.a0, preC.a1, preC.a2)
        // RLB: b values are raw; divide by a0 to produce normalized numerator
        rlb = BiquadFilter(rlbC.b0 / rlbC.a0, rlbC.b1 / rlbC.a0, rlbC.b2 / rlbC.a0, rlbC.a1, rlbC.a2)
    }

    fun process(x: Double): Double = rlb.process(pre.process(x))

    fun reset() {
        pre.reset()
        rlb.reset()
    }

    companion object {
        // BS.1770-4 Annex 1 prototype parameters.
        private const val PRE_FC_HZ = 1681.974
        private const val PRE_GAIN_DB = 4.0          // dB (nominal; exact: 3.999664)
        private const val RLB_FC_HZ = 38.13547
        private const val RLB_Q = 0.5003270

        /**
         * High-shelf biquad coefficients via bilinear transform.
         *
         * K-domain pair (pre-warped at K = tan(π·fc/fs)):
         *   N(K) = Vh + sqrt(2·Vh)·K + K²    (HF-boost numerator, → Vh as K → ∞)
         *   D(K) = 1 + sqrt(2)·K + K²        (Butterworth denominator, → 1 as K → 0)
         * where Vh = 10^(G_dB/20) is the linear HF gain.
         *
         * Returns coefficients with a0 = 1.0 (fully normalized).
         */
        fun preFilterCoefficients(sampleRateHz: Int): BiquadCoefficients {
            val vh = 10.0.pow(PRE_GAIN_DB / 20.0) // linear amplitude gain at HF
            val k = tan(PI * PRE_FC_HZ / sampleRateHz)
            val sqrtVh2 = sqrt(2.0 * vh)
            val sqrt2 = sqrt(2.0)

            val b0r = vh + sqrtVh2 * k + k * k
            val b1r = 2.0 * (k * k - vh)
            val b2r = vh - sqrtVh2 * k + k * k
            val a0r = 1.0 + sqrt2 * k + k * k
            val a1r = 2.0 * (k * k - 1.0)
            val a2r = 1.0 - sqrt2 * k + k * k

            return BiquadCoefficients(
                b0 = b0r / a0r, b1 = b1r / a0r, b2 = b2r / a0r,
                a0 = 1.0,
                a1 = a1r / a0r, a2 = a2r / a0r,
            )
        }

        /**
         * High-pass biquad coefficients via bilinear transform.
         *
         * Analog prototype: H_a(s) = s^2 / (s^2 + (1/Q)*s + 1).
         * Pre-warped via K = tan(π·fc/fs).
         *
         * Returns numerator coefficients raw (b0=1, b1=-2, b2=1) and a1/a2 normalized.
         * [BiquadCoefficients.a0] carries the un-normalized denominator constant so
         * that callers can normalize b values themselves, matching BS.1770-4's table
         * presentation where the numerator is presented before dividing by a0.
         */
        fun rlbFilterCoefficients(sampleRateHz: Int): BiquadCoefficients {
            val k = tan(PI * RLB_FC_HZ / sampleRateHz)
            val kq = k / RLB_Q

            val a0r = 1.0 + kq + k * k
            val a1r = 2.0 * (k * k - 1.0)
            val a2r = 1.0 - kq + k * k

            return BiquadCoefficients(
                b0 = 1.0, b1 = -2.0, b2 = 1.0,  // raw numerator (HP prototype)
                a0 = a0r,
                a1 = a1r / a0r, a2 = a2r / a0r,
            )
        }
    }
}

/**
 * Biquad filter coefficients.
 *
 * Convention:
 * - [b0], [b1], [b2] — numerator polynomial coefficients (may be raw or normalized
 *   depending on the filter design; see the producing factory method's KDoc).
 * - [a0] — un-normalized denominator constant (= 1.0 when already normalized).
 * - [a1], [a2] — denominator coefficients, always normalized by a0.
 *
 * To obtain the [BiquadFilter]-ready normalized numerator, divide b0/b1/b2 by a0.
 */
internal data class BiquadCoefficients(
    val b0: Double, val b1: Double, val b2: Double,
    val a0: Double = 1.0,
    val a1: Double, val a2: Double,
)
