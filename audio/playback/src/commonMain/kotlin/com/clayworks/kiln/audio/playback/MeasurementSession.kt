// Measurement-mode marker for Phase 3 (REW-style room correction). Spec §6.1
// architectural-seam list commits MVP-1.0 to expose the seam even though the
// implementation lands at Phase 3. Today this interface is a stub; concrete
// impls will own the sample-accurate capture + sweep playback + FFT response
// analysis pipeline per spec §7.4.

package com.clayworks.kiln.audio.playback

/**
 * A measurement-mode session opened via [PlatformPlayer.enterMeasurementMode].
 * MVP scope is the architectural seam only — concrete capture + analysis methods
 * arrive at Phase 3.
 *
 * Implementations MUST be closeable; consumers should use `.use { }` to release
 * the underlying capture device.
 */
interface MeasurementSession : AutoCloseable {
    override fun close()
}
