package com.clayworks.kiln.audio.dsp.replaygain

import arrow.core.Either
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessGateTest {

    private val sr = 48_000

    // Helper: feed an interleaved stereo FloatArray into the gate.
    private fun feedStereo(gate: LoudnessGate, interleaved: FloatArray) {
        val frames = interleaved.size / 2
        for (n in 0 until frames) {
            gate.processFrame(doubleArrayOf(interleaved[2 * n].toDouble(), interleaved[2 * n + 1].toDouble()))
        }
    }

    @Test
    fun `under 3 seconds of audio returns InsufficientAudio`() {
        val gate = LoudnessGate(sr, channels = 2)
        // Feed 2 seconds of audio (< 3 sec gate minimum).
        feedStereo(gate, TestSignals.sineStereo(1_000.0, 0.5, 2.0, sr))
        val result = gate.integratedLufs()
        assertTrue(result is Either.Left, "expected Left; got $result")
        assertEquals(AnalysisError.InsufficientAudio, result.value)
    }

    @Test
    fun `silence over 5 seconds returns NoGatedBlocks`() {
        val gate = LoudnessGate(sr, channels = 2)
        feedStereo(gate, TestSignals.silenceStereo(5.0, sr))
        val result = gate.integratedLufs()
        assertTrue(result is Either.Left, "expected Left; got $result")
        assertEquals(AnalysisError.NoGatedBlocks, result.value)
    }

    // For a stereo unit-amplitude pure tone (already K-weighted upstream, so
    // here we feed it directly), mean-square per channel = 0.5; channel-summed
    // = 1.0; block loudness Lk = -0.691 + 10*log10(1) = -0.691 LUFS.
    // Halving amplitude to 0.5 reduces by 6.0206 dB → expected ~-6.71 LUFS.
    @Test
    fun `stereo amplitude 0_5 pure tone over 5 seconds yields about -6_71 LUFS`() {
        val gate = LoudnessGate(sr, channels = 2)
        // NOTE: we feed RAW samples directly; this test bypasses K-weighting
        // because the gate's input expectation is already-K-weighted samples.
        // The test signal is white-noise-flat enough that K-weighting drift
        // doesn't apply — we're testing the gating math, not filtering.
        feedStereo(gate, TestSignals.sineStereo(1_000.0, 0.5, 5.0, sr))
        val result = gate.integratedLufs()
        assertTrue(result is Either.Right, "expected Right; got $result")
        val expected = -0.691 + 20.0 * log10(0.5)
        assertTrue(
            abs(result.value - expected) < 0.5,
            "LUFS should be ~$expected; got ${result.value}",
        )
    }

    // Scale invariance: doubling input amplitude raises LUFS by exactly 20*log10(2) = 6.0206 dB.
    @Test
    fun `doubling amplitude raises LUFS by 6_02 dB`() {
        val gateA = LoudnessGate(sr, channels = 2)
        feedStereo(gateA, TestSignals.sineStereo(1_000.0, 0.25, 5.0, sr))
        val lufsA = (gateA.integratedLufs() as Either.Right).value

        val gateB = LoudnessGate(sr, channels = 2)
        feedStereo(gateB, TestSignals.sineStereo(1_000.0, 0.5, 5.0, sr))
        val lufsB = (gateB.integratedLufs() as Either.Right).value

        assertTrue(
            abs((lufsB - lufsA) - 6.0206) < 0.05,
            "LUFS delta expected 6.0206, got ${lufsB - lufsA}",
        )
    }

    // Relative gating: a signal that's loud for the first half and 30 dB
    // quieter for the second half should integrate close to the loud half's
    // LUFS (the quiet half falls below -10 LU and is gated out).
    @Test
    fun `quiet tail falls below relative gate — integrated approaches loud-half value`() {
        val loud = TestSignals.sineStereo(1_000.0, 0.5, 5.0, sr)
        val quiet = TestSignals.sineStereo(1_000.0, 0.5 / 31.6, 5.0, sr) // ~-30 dB

        val gate = LoudnessGate(sr, channels = 2)
        feedStereo(gate, TestSignals.concatStereo(loud, quiet))
        val integrated = (gate.integratedLufs() as Either.Right).value

        // Loud half alone would be ~-6.71. After gating out the quiet half,
        // integrated should be within 1 LU of that.
        val expected = -0.691 + 20.0 * log10(0.5)
        assertTrue(
            abs(integrated - expected) < 1.0,
            "expected ~$expected (loud-half), got $integrated",
        )
    }

    // Reset clears accumulated blocks.
    @Test
    fun `reset clears block list — subsequent integratedLufs returns InsufficientAudio`() {
        val gate = LoudnessGate(sr, channels = 2)
        feedStereo(gate, TestSignals.sineStereo(1_000.0, 0.5, 5.0, sr))
        assertTrue(gate.integratedLufs() is Either.Right)
        gate.reset()
        assertEquals(AnalysisError.InsufficientAudio, (gate.integratedLufs() as Either.Left).value)
    }

    // Mono code path coverage. For a mono 1 kHz sine at amp 0.5:
    //   mean_square = 0.5^2 / 2 = 0.125
    //   z = mean_square (G=1.0)
    //   Lk = -0.691 + 10*log10(0.125) = -0.691 - 9.031 = -9.72 LUFS
    @Test
    fun `mono 1kHz amplitude 0_5 sine yields about -9_72 LUFS`() {
        val gate = LoudnessGate(sr, channels = 1)
        val signal = TestSignals.sineMono(1_000.0, 0.5, 5.0, sr)
        for (s in signal) {
            gate.processFrame(doubleArrayOf(s.toDouble()))
        }
        val result = gate.integratedLufs()
        assertTrue(result is Either.Right, "expected Right; got $result")
        val expected = -0.691 + 10.0 * log10(0.125)  // ≈ -9.722
        assertTrue(
            abs(result.value - expected) < 0.5,
            "mono LUFS should be ~$expected; got ${result.value}",
        )
    }

    // Block emission must start at the 400 ms mark (BS.1770-4 §3.1.1).
    // Feed exactly 400 ms of loud audio, then 2.6 s of silence to reach the 3-s
    // minimum. If the first block emits at frame `blockFrames` (correct), it
    // captures the loud window and contributes to the gated mean → Right.
    // If the first block emits at frame `2*blockFrames` (off-by-one bug), all
    // blocks see only silence → NoGatedBlocks.
    @Test
    fun `first block emits at 400ms mark — loud prelude is captured`() {
        val gate = LoudnessGate(sr, channels = 2)
        val loudPrelude = TestSignals.sineStereo(1_000.0, 0.5, 0.4, sr)   // 19200 frames
        val tailSilence = TestSignals.silenceStereo(2.6, sr)              // 124800 frames
        feedStereo(gate, TestSignals.concatStereo(loudPrelude, tailSilence))
        val result = gate.integratedLufs()
        assertTrue(
            result is Either.Right,
            "first block must capture the 400 ms loud prelude; got $result",
        )
    }
}
