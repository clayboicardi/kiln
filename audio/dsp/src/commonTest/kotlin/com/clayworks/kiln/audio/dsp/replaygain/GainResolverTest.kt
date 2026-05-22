package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GainResolverTest {

    @Test
    fun `Off mode returns 1_0 regardless of inputs`() {
        assertEquals(1.0, resolveGainLinear(
            trackDb = -6.0, trackPeak = 0.5, albumDb = -8.0, albumPeak = 0.9,
            mode = ReplayGainPipelineMode.Off, preAmpDb = 0.0,
        ), 1e-9)
    }

    @Test
    fun `Track mode applies track_db and pre-amp`() {
        // -6 dB + 3 dB pre-amp = -3 dB → linear ≈ 0.708
        val result = resolveGainLinear(
            trackDb = -6.0, trackPeak = 0.5, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Track, preAmpDb = 3.0,
        )
        assertTrue(abs(result - 0.708) < 0.01, "expected ~0.708, got $result")
    }

    @Test
    fun `Album mode prefers album_db when present`() {
        val result = resolveGainLinear(
            trackDb = -6.0, trackPeak = 0.5, albumDb = -3.0, albumPeak = 0.7,
            mode = ReplayGainPipelineMode.Album, preAmpDb = 0.0,
        )
        // -3 dB → linear ≈ 0.708 (uses album_db = -3.0, NOT track_db = -6.0)
        assertTrue(abs(result - 0.708) < 0.01, "expected ~0.708 (album path), got $result")
    }

    @Test
    fun `Album mode falls back to track_db when album_db is null`() {
        val result = resolveGainLinear(
            trackDb = -6.0, trackPeak = 0.5, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Album, preAmpDb = 0.0,
        )
        // Falls back to track -6 dB → ~0.501
        assertTrue(abs(result - 0.501) < 0.01, "expected ~0.501 (track fallback), got $result")
    }

    @Test
    fun `peak limit caps positive gain so peak times gain stays under 1`() {
        // +6 dB → linear = 2.0; track_peak = 0.7 → product = 1.4 → would clip.
        // Limit: gain = 1.0 / 0.7 ≈ 1.428.
        val result = resolveGainLinear(
            trackDb = 6.0, trackPeak = 0.7, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Track, preAmpDb = 0.0,
        )
        assertTrue(abs(result - (1.0 / 0.7)) < 0.01, "expected ~1.428 (peak-limited), got $result")
        assertTrue(result * 0.7 <= 1.0001, "peak-limited result * peak = ${result * 0.7} must be <= 1.0")
    }

    @Test
    fun `null track_db with Track mode returns 1_0 (no gain to apply)`() {
        val result = resolveGainLinear(
            trackDb = null, trackPeak = 0.5, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Track, preAmpDb = 0.0,
        )
        assertEquals(1.0, result, 1e-9)
    }

    @Test
    fun `null peak defaults conservatively to 1_0 (no peak-limit override)`() {
        // -6 dB → 0.501; peak = null → treated as 1.0 → no peak-limit triggered.
        val result = resolveGainLinear(
            trackDb = -6.0, trackPeak = null, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Track, preAmpDb = 0.0,
        )
        assertTrue(abs(result - 0.501) < 0.01, "expected ~0.501, got $result")
    }
}
