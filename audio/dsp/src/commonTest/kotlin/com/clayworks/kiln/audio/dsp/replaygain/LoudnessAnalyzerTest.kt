package com.clayworks.kiln.audio.dsp.replaygain

import arrow.core.Either
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessAnalyzerTest {

    private val sr = 48_000

    @Test
    fun `factory rejects unsupported channel counts`() {
        try {
            createLoudnessAnalyzer(sr, channels = 6)
            error("expected IllegalArgumentException for 6 channels")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mono/stereo"), "expected mono/stereo message; got '${e.message}'")
        }
    }

    @Test
    fun `factory rejects non-positive sample rate`() {
        try {
            createLoudnessAnalyzer(sampleRateHz = 0, channels = 2)
            error("expected IllegalArgumentException for sr=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sample rate"), "got '${e.message}'")
        }
    }

    @Test
    fun `stereo factory exposes its config via interface getters`() {
        val a = createLoudnessAnalyzer(sr, channels = 2)
        assertEquals(sr, a.sampleRateHz)
        assertEquals(2, a.channels)
    }

    // EBU Tech 3341-style smoke: 1 kHz stereo sine at peak amplitude 0.5,
    // 5 seconds. Expected integrated LUFS ≈ -6.71 (K-weighting at 1 kHz ≈ 0 dB).
    @Test
    fun `integratedLufs on 5sec stereo 1kHz amplitude 0_5 sine matches expected`() {
        val a = createLoudnessAnalyzer(sr, channels = 2)
        val signal = TestSignals.sineStereo(1_000.0, 0.5, 5.0, sr)
        a.processSamples(signal, frames = signal.size / 2)

        val lufs = (a.integratedLufs() as Either.Right).value
        // Expected -6.71 (= -0.691 + 20*log10(0.5)); allow ±1.0 LU for
        // K-weighting drift at 1 kHz (the pre-filter contributes a small
        // fraction of dB even at frequencies well below the shelf corner).
        assertTrue(abs(lufs - (-6.71)) < 1.0, "expected ~-6.71 LUFS; got $lufs")
    }

    // True peak surface check: full-scale 1 kHz sine should report ~0 dBTP.
    @Test
    fun `truePeakDbtp on 1kHz full-scale sine is approximately 0`() {
        val a = createLoudnessAnalyzer(sr, channels = 2)
        val signal = TestSignals.sineStereo(1_000.0, 1.0, 0.5, sr)
        a.processSamples(signal, frames = signal.size / 2)

        assertTrue(abs(a.truePeakDbtp() - 0.0) < 0.5, "expected ~0 dBTP; got ${a.truePeakDbtp()}")
    }

    // ReplayGain v2 default target = -18 LUFS. For a signal measured at L
    // LUFS, replayGainDb = -18 - L. For our -6.71 LUFS test signal:
    // replayGainDb = -18 - (-6.71) = -11.29 dB (negative because the signal
    // is louder than the target, so the gain pre-amp should attenuate).
    @Test
    fun `replayGainDb returns target minus integrated`() {
        val a = createLoudnessAnalyzer(sr, channels = 2)
        val signal = TestSignals.sineStereo(1_000.0, 0.5, 5.0, sr)
        a.processSamples(signal, frames = signal.size / 2)

        val rg = (a.replayGainDb() as Either.Right).value
        // ~ -18 - (-6.71) ≈ -11.29; ±1.0 LU tolerance.
        assertTrue(abs(rg - (-11.29)) < 1.0, "expected ~-11.29 dB; got $rg")
    }

    // Custom target.
    @Test
    fun `replayGainDb with custom target shifts the result`() {
        val a = createLoudnessAnalyzer(sr, channels = 2)
        val signal = TestSignals.sineStereo(1_000.0, 0.5, 5.0, sr)
        a.processSamples(signal, frames = signal.size / 2)

        val rgDefault = (a.replayGainDb() as Either.Right).value
        val rgCustom = (a.replayGainDb(targetLufs = -23.0) as Either.Right).value
        // Lowering target by 5 dB lowers replayGainDb by 5 dB.
        assertTrue(abs((rgCustom - rgDefault) - (-5.0)) < 0.01, "delta should be -5.0; got ${rgCustom - rgDefault}")
    }

    // Mono signal works.
    @Test
    fun `mono signal produces a non-error integrated LUFS`() {
        val a = createLoudnessAnalyzer(sr, channels = 1)
        val signal = TestSignals.sineMono(1_000.0, 0.5, 5.0, sr)
        a.processSamples(signal, frames = signal.size)
        val result = a.integratedLufs()
        assertTrue(result is Either.Right, "expected Right; got $result")
    }

    // InsufficientAudio is propagated.
    @Test
    fun `short signal returns InsufficientAudio`() {
        val a = createLoudnessAnalyzer(sr, channels = 2)
        val signal = TestSignals.sineStereo(1_000.0, 0.5, 0.2, sr) // 200 ms
        a.processSamples(signal, frames = signal.size / 2)
        val result = a.integratedLufs()
        assertEquals(AnalysisError.InsufficientAudio, (result as Either.Left).value)
    }

    // Reset clears state.
    @Test
    fun `reset returns the analyzer to InsufficientAudio state`() {
        val a = createLoudnessAnalyzer(sr, channels = 2)
        val signal = TestSignals.sineStereo(1_000.0, 0.5, 5.0, sr)
        a.processSamples(signal, frames = signal.size / 2)
        assertTrue(a.integratedLufs() is Either.Right)
        a.reset()
        assertEquals(AnalysisError.InsufficientAudio, (a.integratedLufs() as Either.Left).value)
        assertEquals(TruePeakMeter.SILENCE_DBTP, a.truePeakDbtp())
    }

    // Chunked input equals single-shot input.
    @Test
    fun `chunked processSamples calls produce same LUFS as one big call`() {
        val signal = TestSignals.sineStereo(1_000.0, 0.5, 5.0, sr)
        val a = createLoudnessAnalyzer(sr, channels = 2)
        a.processSamples(signal, frames = signal.size / 2)
        val whole = (a.integratedLufs() as Either.Right).value

        val b = createLoudnessAnalyzer(sr, channels = 2)
        // Feed in chunks of 4096 frames = 8192 samples.
        val chunkSize = 4096
        var offset = 0
        while (offset < signal.size) {
            val remainingFrames = (signal.size - offset) / 2
            val take = minOf(chunkSize, remainingFrames)
            val chunk = signal.copyOfRange(offset, offset + take * 2)
            b.processSamples(chunk, frames = take)
            offset += take * 2
        }
        val chunked = (b.integratedLufs() as Either.Right).value
        assertTrue(abs(whole - chunked) < 1e-3, "whole=$whole, chunked=$chunked; should match exactly")
    }
}
