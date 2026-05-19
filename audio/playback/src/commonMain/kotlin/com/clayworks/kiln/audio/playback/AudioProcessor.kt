// AudioProcessor — in-pipeline frame transformer. Inserts into the audio chain
// to apply EQ, ReplayGain, visualizer fan-out, room-correction convolution.
//
// Per spec §3.4 Concentric Modules: actual processor implementations live in
// :audio:dsp (platform-free Kotlin). PlatformPlayer adapters in :audio:playback
// own the chain ordering and dispatcher binding. The interface itself is here
// because PlatformPlayer references it.

package com.clayworks.kiln.audio.playback

/**
 * Audio-pipeline processor. Called from the audio thread; MUST be
 * non-blocking and bounded-latency. Sample-rate-aware via [onFormatChange].
 *
 * Concrete impls live in `:audio:dsp` per Concentric Modules.
 */
interface AudioProcessor {
    val id: String

    /**
     * Called when the audio format changes (queue start, sample-rate switch).
     * Implementations should size internal buffers based on this format.
     */
    fun onFormatChange(format: DecodedAudioFormat)

    /**
     * Transform the frame in-place. Frame bytes may be rewritten; byteCount
     * may change (e.g., a future resampler). Return the processed frame
     * (typically the same instance with mutated bytes).
     */
    fun process(frame: AudioFrame): AudioFrame
}
