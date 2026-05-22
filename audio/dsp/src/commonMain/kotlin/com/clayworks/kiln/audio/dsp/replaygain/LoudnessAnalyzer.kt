package com.clayworks.kiln.audio.dsp.replaygain

import arrow.core.Either

/**
 * EBU R128 / BS.1770-4 loudness analyzer.
 *
 * Stream samples in via [processSamples]; query [integratedLufs] and
 * [truePeakDbtp] at end-of-track. [replayGainDb] returns the target-minus-
 * integrated convenience value for ReplayGain v2 tag generation.
 *
 * The analyzer is stateful: hold one instance per track, feed all samples,
 * then read the results. [reset] clears all state so the same instance can
 * be reused for a new track of the same format.
 *
 * Sample format: PCM interleaved across channels, as `FloatArray`. Caller
 * is responsible for decoding compressed audio (FLAC, etc.) into float
 * samples first; the analyzer is format-agnostic.
 */
interface LoudnessAnalyzer {
    val sampleRateHz: Int
    val channels: Int

    /**
     * Process a chunk of audio samples (interleaved).
     *
     * @param interleaved sample array; layout is `[L0, R0, L1, R1, ...]` for
     *   stereo or `[s0, s1, s2, ...]` for mono. Must contain at least
     *   `frames * channels` valid samples (excess is ignored).
     * @param frames number of complete frames in the buffer.
     */
    fun processSamples(interleaved: FloatArray, frames: Int)

    /** Integrated LUFS over all processed samples per EBU R128 / BS.1770-4. */
    fun integratedLufs(): Either<AnalysisError, Double>

    /** Maximum true-peak across all processed samples, in dBTP. */
    fun truePeakDbtp(): Double

    /**
     * ReplayGain target adjustment: `targetLufs - integratedLufs`.
     *
     * The result is what a player should ADD to the signal gain to reach the
     * target loudness. A negative value means the signal is louder than the
     * target and should be attenuated; positive means the signal is quieter
     * than the target and should be boosted.
     *
     * @param targetLufs the reference loudness target. Default = -18.0 LUFS
     *   per ReplayGain v2 (`REPLAYGAIN_REFERENCE_LOUDNESS`).
     */
    fun replayGainDb(targetLufs: Double = -18.0): Either<AnalysisError, Double>

    /** Clear all accumulated state — same as a fresh analyzer of identical format. */
    fun reset()
}

/**
 * Create a [LoudnessAnalyzer] for the given audio format.
 *
 * @param sampleRateHz e.g., 44_100, 48_000, 96_000, 192_000. Must be > 0.
 * @param channels 1 (mono) or 2 (stereo). Multi-channel is out of scope.
 *
 * @throws IllegalArgumentException if [channels] is outside `1..2` or
 *   [sampleRateHz] is non-positive.
 */
fun createLoudnessAnalyzer(sampleRateHz: Int, channels: Int): LoudnessAnalyzer {
    require(channels in 1..2) {
        "Only mono/stereo supported in D-A; multi-channel deferred per handoff (got channels=$channels)"
    }
    require(sampleRateHz > 0) { "sample rate must be positive; got $sampleRateHz" }
    return LoudnessAnalyzerImpl(sampleRateHz, channels)
}

private class LoudnessAnalyzerImpl(
    override val sampleRateHz: Int,
    override val channels: Int,
) : LoudnessAnalyzer {

    // One K-weighting filter per channel — each maintains independent state.
    private val kWeighting: Array<KWeightingFilter> = Array(channels) { KWeightingFilter(sampleRateHz) }
    private val gate: LoudnessGate = LoudnessGate(sampleRateHz, channels)
    private val peak: TruePeakMeter = TruePeakMeter(channels)

    // Scratch frame buffers to avoid per-frame DoubleArray allocation.
    private val frameRaw: DoubleArray = DoubleArray(channels)
    private val frameWeighted: DoubleArray = DoubleArray(channels)

    override fun processSamples(interleaved: FloatArray, frames: Int) {
        require(frames >= 0) { "frames must be non-negative; got $frames" }
        require(frames * channels <= interleaved.size) {
            "interleaved buffer (${interleaved.size}) too small for $frames frames x $channels channels"
        }
        for (n in 0 until frames) {
            val base = n * channels
            for (c in 0 until channels) {
                frameRaw[c] = interleaved[base + c].toDouble()
                frameWeighted[c] = kWeighting[c].process(frameRaw[c])
            }
            // True peak operates on raw (un-weighted) samples per BS.1770-4 Annex 2.
            peak.processFrame(frameRaw)
            // Loudness gate operates on K-weighted samples.
            gate.processFrame(frameWeighted)
        }
    }

    override fun integratedLufs(): Either<AnalysisError, Double> = gate.integratedLufs()

    override fun truePeakDbtp(): Double = peak.maxDbtp()

    override fun replayGainDb(targetLufs: Double): Either<AnalysisError, Double> =
        integratedLufs().map { lufs -> targetLufs - lufs }

    override fun reset() {
        for (c in 0 until channels) kWeighting[c].reset()
        gate.reset()
        peak.reset()
    }
}
