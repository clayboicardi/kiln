package com.clayworks.kiln.library.scan

import arrow.core.Either

/**
 * Per-track loudness analyzer port. One platform impl decodes the file's
 * PCM samples and feeds them into the [com.clayworks.kiln.audio.dsp.replaygain.LoudnessAnalyzer]
 * from `:audio:dsp`, returning the integrated LUFS + true-peak result.
 *
 * Implementations:
 *  - Desktop: `:audio:playback/desktopMain` `JvmFlacTrackAnalyzer` — FLAC only via JNA libFLAC.
 *  - Android: `:audio:playback/androidMain` `AndroidMediaTrackAnalyzer` — MediaExtractor / MediaCodec.
 *
 * The orchestrator is [TrackAnalysisRunner]; this interface is the seam.
 */
interface TrackAnalyzer {
    /**
     * Analyze one track end-to-end. The implementation opens the file at
     * [filePath] using a codec-appropriate decoder, streams PCM into a
     * fresh `LoudnessAnalyzer`, and returns the integrated LUFS + dBTP peak.
     *
     * @param filePath platform-specific file path or URI (filesystem path on
     *   desktop, `content://` URI on Android SAF). Implementations resolve
     *   per platform.
     * @param codec the `track.codec` column value as set by the scanner
     *   ("FLAC", "MP3", "WAV", "AAC", "OGG_VORBIS", "OGG_OPUS", "ALAC",
     *   "UNKNOWN"). Implementations decide what they support; unsupported
     *   codecs return `TrackAnalysisError.CodecUnsupported`.
     *
     * @return [Either.Right] [TrackLoudness] on success;
     *   [Either.Left] [TrackAnalysisError] otherwise.
     */
    suspend fun analyze(filePath: String, codec: String): Either<TrackAnalysisError, TrackLoudness>
}

/**
 * Successful analysis result. Caller (TrackAnalysisRunner) converts to
 * ReplayGain-v2 dB before persisting:
 *   replayGainDb = -18.0 - integratedLufs   (RG v2 default reference)
 *   peakLinear   = 10^(truePeakDbtp / 20)   (RG v2 linear amplitude convention)
 */
data class TrackLoudness(
    val integratedLufs: Double,
    val truePeakDbtp: Double,
)

/** Reasons the analyzer can fail to produce a result. */
sealed interface TrackAnalysisError {
    /** Codec value not supported by this analyzer (e.g., MP3 on desktop FLAC-only impl). */
    data class CodecUnsupported(val codec: String) : TrackAnalysisError

    /** Decoder/extractor reported an I/O or format error before producing any samples. */
    data class DecodeFailed(val message: String) : TrackAnalysisError

    /** Decoder ran but the LUFS computation rejected the result (silent file, < 3s of audio). */
    data class AnalysisFailed(val reason: String) : TrackAnalysisError
}
