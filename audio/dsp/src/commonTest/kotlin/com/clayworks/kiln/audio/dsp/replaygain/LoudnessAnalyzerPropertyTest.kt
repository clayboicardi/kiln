package com.clayworks.kiln.audio.dsp.replaygain

import arrow.core.Either
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class LoudnessAnalyzerPropertyTest {

    init {
        // Reduce iteration count for fast-feedback runs; CI can raise.
        PropertyTesting.defaultIterationCount = 10
    }

    private val commonSampleRates = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)

    @Test
    fun `scale invariance — doubling amplitude raises LUFS by 6_02 dB across sample rates and base amplitudes`() {
        runBlocking {
            checkAll(
                Arb.element(commonSampleRates),
                Arb.double(min = 0.01, max = 0.4),  // base amplitude ∈ [0.01, 0.4] so 2x stays ≤ 0.8
            ) { sr, baseAmp ->
                val a1 = createLoudnessAnalyzer(sr, channels = 2)
                val signal1 = TestSignals.sineStereo(1_000.0, baseAmp, 4.0, sr)
                a1.processSamples(signal1, frames = signal1.size / 2)
                val l1 = (a1.integratedLufs() as Either.Right).value

                val a2 = createLoudnessAnalyzer(sr, channels = 2)
                val signal2 = TestSignals.sineStereo(1_000.0, baseAmp * 2.0, 4.0, sr)
                a2.processSamples(signal2, frames = signal2.size / 2)
                val l2 = (a2.integratedLufs() as Either.Right).value

                val delta = l2 - l1
                assertTrue(abs(delta - 6.0206) < 0.05, "sr=$sr baseAmp=$baseAmp delta=$delta")
            }
        }
    }

    @Test
    fun `K-weighting coefficients are stable across all common sample rates`() {
        runBlocking {
            checkAll(Arb.element(commonSampleRates)) { sr ->
                val pre = KWeightingFilter.preFilterCoefficients(sr)
                val rlb = KWeightingFilter.rlbFilterCoefficients(sr)
                assertTrue(pre.b0.isFinite() && pre.b1.isFinite() && pre.b2.isFinite())
                assertTrue(pre.a1.isFinite() && pre.a2.isFinite())
                assertTrue(rlb.b0.isFinite() && rlb.b1.isFinite() && rlb.b2.isFinite())
                assertTrue(rlb.a1.isFinite() && rlb.a2.isFinite())
                // Stability: |a2| < 1 AND |a1| < 1 + a2.
                assertTrue(abs(pre.a2) < 1.0)
                assertTrue(abs(pre.a1) < 1.0 + pre.a2)
                assertTrue(abs(rlb.a2) < 1.0)
                assertTrue(abs(rlb.a1) < 1.0 + rlb.a2)
            }
        }
    }

    // Channel symmetry: a mono signal at amplitude A, vs. a stereo signal with
    // L=R=A, should differ by exactly 3.01 dB (10*log10(2)) because the stereo
    // case channel-sums two identical mean-square values.
    @Test
    fun `stereo L=R signal is 3_01 dB louder than the equivalent mono signal`() {
        runBlocking {
            checkAll(Arb.double(min = 0.05, max = 0.5)) { amp ->
                val sr = 48_000

                val mono = createLoudnessAnalyzer(sr, channels = 1)
                val monoSig = TestSignals.sineMono(1_000.0, amp, 4.0, sr)
                mono.processSamples(monoSig, frames = monoSig.size)
                val lMono = (mono.integratedLufs() as Either.Right).value

                val stereo = createLoudnessAnalyzer(sr, channels = 2)
                val stereoSig = TestSignals.sineStereo(1_000.0, amp, 4.0, sr)
                stereo.processSamples(stereoSig, frames = stereoSig.size / 2)
                val lStereo = (stereo.integratedLufs() as Either.Right).value

                val delta = lStereo - lMono
                // 10 * log10(2) = 3.0103 dB
                assertTrue(abs(delta - 3.0103) < 0.05, "amp=$amp lMono=$lMono lStereo=$lStereo delta=$delta")
            }
        }
    }

    // ReplayGain inversion: if integratedLufs is L and replayGainDb is RG,
    // then RG + L should equal the target (-18 LUFS by default).
    @Test
    fun `replayGainDb plus integratedLufs equals target across amplitudes`() {
        runBlocking {
            checkAll(Arb.double(min = 0.05, max = 0.7)) { amp ->
                val a = createLoudnessAnalyzer(48_000, channels = 2)
                val signal = TestSignals.sineStereo(1_000.0, amp, 4.0, 48_000)
                a.processSamples(signal, frames = signal.size / 2)

                val l = (a.integratedLufs() as Either.Right).value
                val rg = (a.replayGainDb() as Either.Right).value
                assertTrue(abs((rg + l) - (-18.0)) < 1e-9, "amp=$amp l=$l rg=$rg sum=${rg + l}")
            }
        }
    }
}
