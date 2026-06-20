package com.clayworks.kiln.ui.components.specsheet

import com.clayworks.kiln.library.source.SpecSheetEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function tests for [formatLine] — the one-line format summary rendered
 * at the top of the Spec Sheet. No Compose here; the rendered-layout tests for
 * SpecSheetContent land in task A6.
 *
 * The separator is an em-dash with surrounding spaces (" — ", U+2014). kHz is
 * shown with .1 precision ONLY when non-integer: 96000 → "96", 44100 → "44.1".
 */
class SpecSheetFormatTest {

    @Test
    fun formatLine_hires_flac() {
        val e = specSheetEntry(codec = "FLAC", sampleRateHz = 96000, bitDepth = 24, channels = 2, bitrateKbps = 1411)
        assertEquals("FLAC — 24/96 — 2 ch — 1411 kbps", formatLine(e))
    }

    @Test
    fun formatLine_lossy_omits_bitdepth() {
        val e = specSheetEntry(codec = "MP3", sampleRateHz = 44100, bitDepth = null, channels = 2, bitrateKbps = 320)
        assertEquals("MP3 — 44.1 kHz — 2 ch — 320 kbps", formatLine(e))
    }

    @Test
    fun formatLine_omits_bitrate_when_null() {
        // bitrateKbps is nullable on SpecSheetEntry; when absent, the trailing
        // " — {n} kbps" segment is dropped entirely (no "null kbps", no dangling dash).
        val e = specSheetEntry(codec = "FLAC", sampleRateHz = 48000, bitDepth = 16, channels = 2, bitrateKbps = null)
        assertEquals("FLAC — 16/48 — 2 ch", formatLine(e))
    }
}

/**
 * Factory for a [SpecSheetEntry] with format-relevant fields overridable and
 * everything else defaulted — keeps the format tests focused on the four fields
 * formatLine actually reads (codec, sampleRateHz, bitDepth, channels, bitrateKbps).
 */
private fun specSheetEntry(
    codec: String,
    sampleRateHz: Int,
    bitDepth: Int?,
    channels: Int,
    bitrateKbps: Int?,
): SpecSheetEntry = SpecSheetEntry(
    trackId = "1",
    title = "Test Track",
    codec = codec,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    channels = channels,
    bitrateKbps = bitrateKbps,
    durationMs = 0L,
    replayGainTrackDb = null,
    replayGainAlbumDb = null,
    replayGainTrackPeak = null,
    replayGainAlbumPeak = null,
    hasEmbeddedArt = false,
    filePath = "/test/track.flac",
    fileSizeBytes = 0L,
    fileMtimeMs = 0L,
    hasKnownMtime = true,
)
