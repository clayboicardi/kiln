package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KWeightingFilterTest {

    // BS.1770-4's documented 48 kHz pre-filter coefficients should match
    // RBJ-cookbook-derived values within numerical tolerance.
    @Test
    fun `pre-filter coefficients at 48 kHz match BS_1770-4 reference within 5e-3`() {
        val c = KWeightingFilter.preFilterCoefficients(48_000)
        assertEquals( 1.53512485958697, c.b0, 5e-3)
        assertEquals(-2.69169618940638, c.b1, 5e-3)
        assertEquals( 1.19839281085285, c.b2, 5e-3)
        assertEquals(-1.69065929318241, c.a1, 5e-3)
        assertEquals( 0.73248077421585, c.a2, 5e-3)
    }

    // BS.1770-4's documented 48 kHz RLB filter coefficients.
    @Test
    fun `RLB filter coefficients at 48 kHz match BS_1770-4 reference within 5e-3`() {
        val c = KWeightingFilter.rlbFilterCoefficients(48_000)
        assertEquals( 1.0, c.b0, 5e-3)
        assertEquals(-2.0, c.b1, 5e-3)
        assertEquals( 1.0, c.b2, 5e-3)
        assertEquals(-1.99004745483398, c.a1, 5e-3)
        assertEquals( 0.99007225036621, c.a2, 5e-3)
    }

    // K-weighting at 1 kHz is approximately 0 dB (well below the pre-filter
    // shelf corner at 1681 Hz, well above the RLB high-pass corner at 38 Hz).
    // After warmup, the RMS of a unit-amplitude sine should be within ~1.5 dB
    // of 1/sqrt(2) ≈ 0.7071.
    @Test
    fun `K-weighting at 1 kHz passes a sine wave with near-unity magnitude`() {
        val sampleRate = 48_000
        val freq = 1000.0
        val amp = 1.0
        val warmupFrames = 1000
        val measureFrames = sampleRate     // 1 second after warmup
        val totalFrames = warmupFrames + measureFrames

        val f = KWeightingFilter(sampleRate)
        var ss = 0.0
        for (n in 0 until totalFrames) {
            val x = amp * sin(2.0 * PI * freq * n / sampleRate)
            val y = f.process(x)
            if (n >= warmupFrames) ss += y * y
        }
        val rms = sqrt(ss / measureFrames)
        val expectedRms = amp / sqrt(2.0)
        val ratioDb = 20.0 * kotlin.math.log10(rms / expectedRms)
        assertTrue(
            abs(ratioDb) < 1.5,
            "K-weighted 1 kHz sine should be within 1.5 dB of input; got $ratioDb dB",
        )
    }

    // DC input → output settles to ~0 (RLB high-pass rejects DC).
    @Test
    fun `K-weighting rejects DC — settles to near zero after warmup`() {
        val f = KWeightingFilter(48_000)
        var last = 0.0
        repeat(5_000) { last = f.process(1.0) }
        assertTrue(abs(last) < 1e-3, "K-weighted DC should be ~0; got $last")
    }

    // Reset clears state.
    @Test
    fun `reset clears K-weighting state`() {
        val f = KWeightingFilter(48_000)
        repeat(1_000) { f.process(1.0) }
        f.reset()
        val firstAfterReset = f.process(0.0)
        assertEquals(0.0, firstAfterReset, 1e-12)
    }

    // Coefficient derivation works at non-48k sample rates without producing
    // NaN, Inf, or unstable poles (|root| < 1 of the denominator polynomial).
    @Test
    fun `coefficients are stable at 44_1k, 96k, 192k`() {
        for (sr in intArrayOf(44_100, 88_200, 96_000, 176_400, 192_000)) {
            val pre = KWeightingFilter.preFilterCoefficients(sr)
            val rlb = KWeightingFilter.rlbFilterCoefficients(sr)
            for (c in arrayOf(pre, rlb)) {
                assertTrue(c.b0.isFinite() && c.b1.isFinite() && c.b2.isFinite(), "non-finite numerator at $sr Hz")
                assertTrue(c.a1.isFinite() && c.a2.isFinite(), "non-finite denominator at $sr Hz")
                // Stability: roots of z^2 + a1*z + a2 must have |root| < 1.
                // Equivalently, |a2| < 1 AND |a1| < 1 + a2.
                assertTrue(abs(c.a2) < 1.0, "unstable a2 at $sr Hz: ${c.a2}")
                assertTrue(abs(c.a1) < 1.0 + c.a2, "unstable a1 at $sr Hz: a1=${c.a1}, a2=${c.a2}")
            }
        }
    }
}
