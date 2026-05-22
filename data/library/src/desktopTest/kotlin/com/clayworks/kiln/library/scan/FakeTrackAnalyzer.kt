package com.clayworks.kiln.library.scan

import arrow.core.Either

/**
 * Test double for [TrackAnalyzer]. Maps `filePath -> result` deterministically;
 * unknown paths return [TrackAnalysisError.DecodeFailed].
 */
class FakeTrackAnalyzer(
    private val results: Map<String, Either<TrackAnalysisError, TrackLoudness>>,
) : TrackAnalyzer {
    val analyzed: MutableList<String> = mutableListOf()
    override suspend fun analyze(filePath: String, codec: String): Either<TrackAnalysisError, TrackLoudness> {
        analyzed += filePath
        return results[filePath]
            ?: Either.Left(TrackAnalysisError.DecodeFailed("no fake result for $filePath"))
    }
}
