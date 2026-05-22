package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TruePeakMeterTest {

    private val sr = 48_000

    // Helper: feed an interleaved stereo FloatArray.
    private fun feedStereo(meter: TruePeakMeter, interleaved: FloatArray) {
        val frames = interleaved.size / 2
        for (n in 0 until frames) {
            meter.processFrame(doubleArrayOf(interleaved[2 * n].toDouble(), interleaved[2 * n + 1].toDouble()))
        }
    }

    @Test
    fun `silent signal returns very-negative dBTP sentinel`() {
        val meter = TruePeakMeter(channels = 2)
        feedStereo(meter, TestSignals.silenceStereo(1.0, sr))
        val dbtp = meter.maxDbtp()
        assertTrue(dbtp < -100.0, "silence dBTP should be very negative; got $dbtp")
    }

    @Test
    fun `DC at amplitude 1_0 reports 0 dBTP`() {
        val meter = TruePeakMeter(channels = 2)
        // 100 ms of DC at +1.0 on both channels.
        val frames = sr / 10
        repeat(frames) {
            meter.processFrame(doubleArrayOf(1.0, 1.0))
        }
        val dbtp = meter.maxDbtp()
        assertTrue(abs(dbtp - 0.0) < 0.01, "DC peak should be 0 dBTP; got $dbtp")
    }

    // Full-scale sine at 1 kHz: discrete peak is 1.0 → 0 dBTP. Lagrange
    // overshoot should be <0.1 dBTP for a tone well below Nyquist.
    @Test
    fun `full-scale 1 kHz sine reports approximately 0 dBTP`() {
        val meter = TruePeakMeter(channels = 2)
        feedStereo(meter, TestSignals.sineStereo(1_000.0, 1.0, 0.5, sr))
        val dbtp = meter.maxDbtp()
        assertTrue(abs(dbtp) < 0.1, "1 kHz peak should be ~0 dBTP; got $dbtp")
    }

    // Mid-amplitude sine: peak 0.5 → 20*log10(0.5) = -6.02 dBTP.
    @Test
    fun `half-amplitude sine reports approximately -6_02 dBTP`() {
        val meter = TruePeakMeter(channels = 2)
        feedStereo(meter, TestSignals.sineStereo(1_000.0, 0.5, 0.5, sr))
        val dbtp = meter.maxDbtp()
        val expected = 20.0 * log10(0.5)
        assertTrue(abs(dbtp - expected) < 0.1, "should be ~$expected dBTP; got $dbtp")
    }

    // Stationary 12 kHz tone with phase offset π/4 — discrete samples land at
    // ±0.7071 (≈-3.01 dBTP) every cycle but the continuous peak is 1.0.
    // Without oversampling the meter would report -3.01 dBTP; with 4x Lagrange
    // oversampling the recovered inter-sample peak should be meaningfully closer
    // to 0 dBTP. Lagrange-4 typically recovers to ~-1 dBTP at this worst-case
    // phase (the polynomial doesn't fully resolve the peak, but the +2 dBTP
    // improvement over discrete sampling is the feature being tested).
    @Test
    fun `near-Nyquist tone — oversampling captures inter-sample peak`() {
        val sineNear = DoubleArray(sr / 10)
        val twoPi = 2.0 * kotlin.math.PI
        val freq = 12_000.0
        val phase = twoPi / 8.0  // π/4 — samples land at ±0.7071 every cycle
        for (n in sineNear.indices) {
            sineNear[n] = kotlin.math.sin(twoPi * freq * n / sr + phase)
        }

        val meter = TruePeakMeter(channels = 1)
        for (s in sineNear) {
            meter.processFrame(doubleArrayOf(s))
        }
        val dbtp = meter.maxDbtp()
        // Discrete max alone gives -3.01 dBTP. Oversampling must recover at least
        // 1 dBTP of inter-sample peak — confirming the interpolation works.
        assertTrue(
            dbtp > -2.0,
            "oversampled near-Nyquist peak too low (no recovery happening?): $dbtp",
        )
        // Upper bound: shouldn't overshoot above continuous peak.
        assertTrue(
            dbtp < 0.5,
            "oversampled peak overshoots continuous peak unrealistically: $dbtp",
        )
    }

    // Reset clears state.
    @Test
    fun `reset clears peak — subsequent silence reports very negative`() {
        val meter = TruePeakMeter(channels = 2)
        feedStereo(meter, TestSignals.sineStereo(1_000.0, 1.0, 0.5, sr))
        assertTrue(meter.maxDbtp() > -1.0)
        meter.reset()
        // Feed silence after reset; expect very low dBTP.
        feedStereo(meter, TestSignals.silenceStereo(0.5, sr))
        assertTrue(meter.maxDbtp() < -100.0, "post-reset silence dBTP too high: ${meter.maxDbtp()}")
    }
}
