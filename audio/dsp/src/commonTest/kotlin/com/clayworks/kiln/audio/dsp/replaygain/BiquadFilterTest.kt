package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiquadFilterTest {

    // Identity biquad: b0=1, b1=b2=a1=a2=0 → output equals input.
    @Test
    fun `identity biquad passes samples through unchanged`() {
        val f = BiquadFilter(b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0)
        val inputs = doubleArrayOf(0.5, -0.25, 0.75, -1.0, 0.0)
        for (x in inputs) {
            assertEquals(x, f.process(x), 1e-12)
        }
    }

    // 3-tap FIR (b0=0.5, b1=0.25, b2=0.125, a1=0, a2=0): output =
    // 0.5*x[n] + 0.25*x[n-1] + 0.125*x[n-2]. Exercises b2 → s2 → s1 path.
    @Test
    fun `3-tap FIR biquad combines current and two past inputs`() {
        val f = BiquadFilter(b0 = 0.5, b1 = 0.25, b2 = 0.125, a1 = 0.0, a2 = 0.0)
        // n=0: x[0]=1.0; x[-1]=x[-2]=0 → 0.5*1 + 0 + 0 = 0.5
        assertEquals(0.5, f.process(1.0), 1e-12)
        // n=1: x[1]=0.0; x[0]=1, x[-1]=0 → 0 + 0.25*1 + 0 = 0.25
        assertEquals(0.25, f.process(0.0), 1e-12)
        // n=2: x[2]=0.0; x[1]=0, x[0]=1 → 0 + 0 + 0.125*1 = 0.125
        assertEquals(0.125, f.process(0.0), 1e-12)
        // n=3: x[3]=0.0; x[2]=0, x[1]=0 → 0 (impulse fully shifted out)
        assertEquals(0.0, f.process(0.0), 1e-12)
    }

    // One-sample delay: b0=0, b1=1 → output is the previous input.
    @Test
    fun `delay biquad outputs previous input on next sample`() {
        val f = BiquadFilter(b0 = 0.0, b1 = 1.0, b2 = 0.0, a1 = 0.0, a2 = 0.0)
        assertEquals(0.0, f.process(0.5), 1e-12)  // first call: previous = 0
        assertEquals(0.5, f.process(0.25), 1e-12) // second call: previous = 0.5
        assertEquals(0.25, f.process(-1.0), 1e-12)
    }

    // Reset clears BOTH state variables. Use a 2-sample-delay biquad (b2=1)
    // so s2 is non-trivially populated by the time reset() is called.
    @Test
    fun `reset clears state — 2-sample-delay biquad emits zero after reset`() {
        val f = BiquadFilter(b0 = 0.0, b1 = 0.0, b2 = 1.0, a1 = 0.0, a2 = 0.0)
        f.process(0.5)   // x[0]=0.5; output=0, but s2 now holds x[0]
        f.process(0.25)  // x[1]=0.25; output=0, s1 now holds x[0]
        f.reset()
        // If reset only cleared s1, next call would still return 0.5 (the buffered x[0]).
        // With both state vars cleared, output is 0.
        assertEquals(0.0, f.process(0.75), 1e-12)
    }

    // First-order high-pass (a1 = -0.5, b0 = 1, b1 = -1): DC settles to zero.
    @Test
    fun `simple high-pass settles to zero under DC input`() {
        val f = BiquadFilter(b0 = 1.0, b1 = -1.0, b2 = 0.0, a1 = -0.5, a2 = 0.0)
        var last = 1.0
        repeat(1000) { last = f.process(1.0) }
        assertTrue(abs(last) < 1e-6, "high-pass should settle to ~0 on DC; got $last")
    }
}
