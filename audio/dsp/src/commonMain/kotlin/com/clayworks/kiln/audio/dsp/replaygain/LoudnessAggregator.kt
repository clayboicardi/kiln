package com.clayworks.kiln.audio.dsp.replaygain

import arrow.core.Either
import kotlin.math.ln
import kotlin.math.log10

/**
 * Compute album-level integrated LUFS from per-track integrated LUFS values
 * per BS.1770-4 §5.3.
 *
 * Album LUFS is the **energy-weighted** mean of the per-track integrated
 * loudness values. Each per-track LUFS is converted back to a linear "z"
 * (loudness energy) value, the z values are arithmetically averaged, and
 * the mean is converted back to LUFS. Loud tracks dominate the result, which
 * is why a 3-track album with (-23, -18, -28) LUFS aggregates to ≈ -21.26
 * (not the arithmetic mean -23.0).
 *
 * @param trackLufsValues per-track integrated LUFS, computed via
 *   [LoudnessAnalyzer.integratedLufs] on each track independently.
 * @return [Either.Right] album LUFS for a non-empty input with usable loudness data;
 *   [Either.Left] [AnalysisError.InsufficientAudio] for an empty list;
 *   [Either.Left] [AnalysisError.NoGatedBlocks] when all inputs are silent or NaN
 *   (meanZ is zero or non-finite, so log10 would yield -Infinity or NaN).
 */
fun albumIntegratedLufs(trackLufsValues: List<Double>): Either<AnalysisError, Double> {
    if (trackLufsValues.isEmpty()) return Either.Left(AnalysisError.InsufficientAudio)
    var sumZ = 0.0
    for (lufs in trackLufsValues) {
        sumZ += pow10((lufs + 0.691) / 10.0)
    }
    val meanZ = sumZ / trackLufsValues.size
    // Silent-album / NaN-input guard: log10 of zero / non-finite would
    // produce a poison Right value (-Infinity or NaN). Reject explicitly.
    if (!meanZ.isFinite() || meanZ <= 0.0) {
        return Either.Left(AnalysisError.NoGatedBlocks)
    }
    return Either.Right(-0.691 + 10.0 * log10(meanZ))
}

private fun pow10(x: Double): Double = kotlin.math.exp(x * ln(10.0))
