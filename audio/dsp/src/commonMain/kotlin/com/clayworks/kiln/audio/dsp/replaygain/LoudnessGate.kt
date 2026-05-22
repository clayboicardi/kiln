package com.clayworks.kiln.audio.dsp.replaygain

import arrow.core.Either
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Errors the loudness analyzer can produce.
 *
 * - [InsufficientAudio]: fewer than 3 seconds of audio fed. BS.1770-4 integrated
 *   loudness requires a minimum measurement window; below 3 seconds the result
 *   is not meaningful.
 * - [NoGatedBlocks]: all blocks were absolute-gated (every block fell below
 *   -70 LUFS — typically a silent stream).
 */
sealed interface AnalysisError {
    data object InsufficientAudio : AnalysisError
    data object NoGatedBlocks : AnalysisError
}

/**
 * EBU R128 / BS.1770-4 block-loudness gate.
 *
 * Streams in already-K-weighted frames (one frame = one sample per channel),
 * maintains a per-channel mean-square accumulator over a 400 ms sliding window
 * with 100 ms stride (75% overlap), emits a block's channel-weighted z-value
 * every stride, and on [integratedLufs] applies dual gating:
 *
 *   1. Absolute gate: discard blocks with `Lk < -70 LUFS`.
 *   2. Relative gate: keep blocks with `Lk >= -10 LU below ungated mean LUFS`.
 *
 * Returns the gated mean as integrated LUFS.
 *
 * The gate accepts both mono (channels=1) and stereo (channels=2) — multi-channel
 * is out of scope per the Session 14 handoff.
 *
 * Channel weighting per BS.1770-4 §5.2.1: G_L = G_R = 1.0 (stereo). Mono is
 * treated as a single channel with G = 1.0 (BS.1770-4 doesn't formally specify
 * mono; the unit-weight convention is universal across reference implementations).
 */
internal class LoudnessGate(
    private val sampleRateHz: Int,
    private val channels: Int,
) {
    init {
        require(channels in 1..2) { "channels must be 1 or 2; got $channels" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive; got $sampleRateHz" }
    }

    private val blockFrames: Int = (sampleRateHz * 0.4).roundToInt()  // 400 ms
    private val stride: Int = (sampleRateHz * 0.1).roundToInt()       // 100 ms

    // Per-channel circular sample buffer (Doubles for precision).
    private val ring: Array<DoubleArray> = Array(channels) { DoubleArray(blockFrames) }
    private var ringIdx: Int = 0
    private var framesFed: Int = 0
    private var framesUntilNextBlock: Int = 1  // first block at frame `blockFrames` (400 ms)

    // Block-level channel-weighted z values: z = sum_i G_i * mean_square_i.
    // For both mono and stereo, G_i = 1.0 → z = sum_i mean_square_i.
    private val blockZ: MutableList<Double> = mutableListOf()

    /**
     * Feed one frame (one sample per channel). For stereo, `samples` must have
     * length 2; for mono, length 1. Caller is expected to feed already-K-weighted
     * samples (the [LoudnessAnalyzer] orchestrator applies K-weighting upstream).
     */
    fun processFrame(samples: DoubleArray) {
        require(samples.size == channels) {
            "frame must have $channels samples; got ${samples.size}"
        }
        for (c in 0 until channels) {
            ring[c][ringIdx] = samples[c]
        }
        ringIdx = (ringIdx + 1) % blockFrames
        framesFed++

        if (framesFed >= blockFrames) {
            framesUntilNextBlock--
            if (framesUntilNextBlock <= 0) {
                emitBlock()
                framesUntilNextBlock = stride
            }
        }
    }

    private fun emitBlock() {
        // Channel-summed mean square (G_i = 1.0 for L, R, and mono).
        var z = 0.0
        for (c in 0 until channels) {
            var ss = 0.0
            val buf = ring[c]
            for (i in 0 until blockFrames) {
                ss += buf[i] * buf[i]
            }
            z += ss / blockFrames
        }
        blockZ.add(z)
    }

    /**
     * Compute integrated LUFS over all blocks accumulated so far.
     *
     * Returns:
     * - [Either.Left] [AnalysisError.InsufficientAudio] if fewer than ~3 seconds of audio
     *   have been fed. BS.1770-4 integrated loudness requires a minimum measurement window;
     *   below 3 seconds the result is not meaningful.
     * - [Either.Left] [AnalysisError.NoGatedBlocks] if all blocks were absolute-gated
     *   (every block below -70 LUFS, e.g., a silent stream).
     * - [Either.Right] the integrated LUFS value.
     */
    fun integratedLufs(): Either<AnalysisError, Double> {
        // Require at least 3 seconds of audio (EBU R128 integrated loudness minimum).
        val minFrames = (sampleRateHz * 3.0).roundToInt()
        if (framesFed < minFrames) return Either.Left(AnalysisError.InsufficientAudio)
        if (blockZ.isEmpty()) return Either.Left(AnalysisError.InsufficientAudio)

        // Absolute gate at -70 LUFS. Convert threshold to z: z_abs = 10^((-70 + 0.691)/10).
        val absZ = pow10((-70.0 + 0.691) / 10.0)
        val afterAbs = blockZ.filter { it >= absZ }
        if (afterAbs.isEmpty()) return Either.Left(AnalysisError.NoGatedBlocks)

        // Relative gate at -10 LU below ungated mean.
        val ungatedMeanZ = afterAbs.average()
        val ungatedLufs = -0.691 + 10.0 * log10(ungatedMeanZ)
        val relThresholdLufs = ungatedLufs - 10.0
        val relZ = pow10((relThresholdLufs + 0.691) / 10.0)
        val afterRel = afterAbs.filter { it >= relZ }
        if (afterRel.isEmpty()) return Either.Left(AnalysisError.NoGatedBlocks)

        val gatedMeanZ = afterRel.average()
        return Either.Right(-0.691 + 10.0 * log10(gatedMeanZ))
    }

    fun reset() {
        for (c in 0 until channels) {
            ring[c].fill(0.0)
        }
        ringIdx = 0
        framesFed = 0
        framesUntilNextBlock = 1
        blockZ.clear()
    }

    private fun pow10(x: Double): Double = kotlin.math.exp(x * ln(10.0))
}
