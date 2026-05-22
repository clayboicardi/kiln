package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.abs
import kotlin.math.log10

/**
 * Inter-sample (true) peak meter per ITU-R BS.1770-4 Annex 2, simplified.
 *
 * Implementation: 4× oversampling via 4-point Lagrange interpolation. For
 * each input frame we maintain a 4-sample look-back/look-ahead window per
 * channel and emit one original sample + three interpolated samples
 * (at fractional positions 0.25, 0.5, 0.75) for peak tracking.
 *
 * Coefficient derivation: a, b, c, d for fractional offset f are
 *   a = -f*(f-1)*(f-2)/6
 *   b = (f+1)*(f-1)*(f-2)/2
 *   c = -(f+1)*f*(f-2)/2
 *   d = (f+1)*f*(f-1)/6
 * Precomputed values (constants below) cover the standard 4× phase set.
 *
 * Accuracy: Lagrange-4 has ~0.2 dBTP error near Nyquist for arbitrary phase;
 * meets BS.1770-4's ±0.5 dBTP tolerance for typical music content.
 *
 * Returns the dBTP convention: 0 dBTP corresponds to peak amplitude 1.0.
 * Fully silent signals return [SILENCE_DBTP] (-200 dBTP sentinel).
 */
internal class TruePeakMeter(private val channels: Int) {
    init {
        require(channels in 1..2) { "channels must be 1 or 2; got $channels" }
    }

    // 4-sample look-back per channel: [y[n-3], y[n-2], y[n-1], y[n]] after
    // each push, indexed mod 4 via head pointer.
    private val window: Array<DoubleArray> = Array(channels) { DoubleArray(WINDOW_SIZE) }
    private var headIdx: Int = 0
    private var primed: Int = 0  // count of samples fed so far (caps at WINDOW_SIZE)

    private var maxAbs: Double = 0.0

    /**
     * Feed one frame (one sample per channel). For each frame, the meter
     * interpolates *between the previous frame and the second-previous frame*
     * (since 4-point Lagrange needs both look-ahead and look-back).
     */
    fun processFrame(samples: DoubleArray) {
        require(samples.size == channels)

        for (c in 0 until channels) {
            window[c][headIdx] = samples[c]
        }
        headIdx = (headIdx + 1) % WINDOW_SIZE
        if (primed < WINDOW_SIZE) primed++

        if (primed < WINDOW_SIZE) return  // wait until 4-sample window is full

        // After full priming, the window indexed by (headIdx + 0..3) % 4 holds
        // [y_-1, y_0, y_+1, y_+2] in temporal order — i.e., the window currently
        // straddles a sample gap. Interpolated samples are between y_0 and y_+1.
        for (c in 0 until channels) {
            val buf = window[c]
            val yM1 = buf[(headIdx) % WINDOW_SIZE]
            val y0  = buf[(headIdx + 1) % WINDOW_SIZE]
            val yP1 = buf[(headIdx + 2) % WINDOW_SIZE]
            val yP2 = buf[(headIdx + 3) % WINDOW_SIZE]

            // Original sample (the center sample we're computing peaks around).
            updateMax(abs(y0))

            // 3 interpolated samples at f = 0.25, 0.5, 0.75.
            updateMax(abs(LAG_025_A * yM1 + LAG_025_B * y0 + LAG_025_C * yP1 + LAG_025_D * yP2))
            updateMax(abs(LAG_050_A * yM1 + LAG_050_B * y0 + LAG_050_C * yP1 + LAG_050_D * yP2))
            updateMax(abs(LAG_075_A * yM1 + LAG_075_B * y0 + LAG_075_C * yP1 + LAG_075_D * yP2))
        }
    }

    private fun updateMax(v: Double) {
        if (v > maxAbs) maxAbs = v
    }

    /** Maximum true-peak in dBTP. Returns [SILENCE_DBTP] for a fully silent signal. */
    fun maxDbtp(): Double =
        if (maxAbs <= 0.0) SILENCE_DBTP else 20.0 * log10(maxAbs)

    fun reset() {
        for (c in 0 until channels) window[c].fill(0.0)
        headIdx = 0
        primed = 0
        maxAbs = 0.0
    }

    companion object {
        const val SILENCE_DBTP: Double = -200.0
        private const val WINDOW_SIZE = 4

        // Precomputed 4-point Lagrange coefficients per fractional offset.
        // Computed from a = -f*(f-1)*(f-2)/6, b = (f+1)*(f-1)*(f-2)/2,
        // c = -(f+1)*f*(f-2)/2, d = (f+1)*f*(f-1)/6.
        private const val LAG_025_A = -0.0546875
        private const val LAG_025_B =  0.8203125
        private const val LAG_025_C =  0.2734375
        private const val LAG_025_D = -0.0390625

        private const val LAG_050_A = -0.0625
        private const val LAG_050_B =  0.5625
        private const val LAG_050_C =  0.5625
        private const val LAG_050_D = -0.0625

        private const val LAG_075_A = -0.0390625
        private const val LAG_075_B =  0.2734375
        private const val LAG_075_C =  0.8203125
        private const val LAG_075_D = -0.0546875
    }
}
