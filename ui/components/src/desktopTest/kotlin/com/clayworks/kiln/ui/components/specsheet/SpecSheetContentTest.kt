package com.clayworks.kiln.ui.components.specsheet

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.clayworks.kiln.library.source.LibraryAggregate
import com.clayworks.kiln.library.source.SpecSheetEntry
import org.junit.Rule
import kotlin.test.Test

/**
 * Rendered-layout tests for [SpecSheetContent] — locks the rendering contract
 * that A4 established (formatLine headline, the "—" null marker for absent
 * ReplayGain, the NotFound empty-state string). Renders the stateless Composable
 * directly with a hand-built [SpecSheetUiState]; no CompositionLocal, no nav
 * (that surface is covered by NowPlayingNavigationTest).
 *
 * assertExists() rather than assertIsDisplayed(): LoadedBody is verticalScroll-
 * wrapped, so lower rows can sit outside the headless test viewport; existence
 * in the composition tree is the contract being asserted. Matches the
 * below-the-fold convention SettingsScreenTest already follows.
 */
class SpecSheetContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loaded_golden_path_shows_format_line() {
        composeRule.setContent {
            SpecSheetContent(
                state = SpecSheetUiState.Loaded(
                    entry = hiresFlacEntry(),
                    aggregate = sampleAggregate(),
                ),
                onBack = {},
            )
        }
        // formatLine for a 24/96 stereo FLAC at 1411 kbps. Em-dash separator is
        // U+2014 with surrounding spaces (" — "); kHz shows no decimal for the
        // integer-kHz 96000 case. Mirrors SpecSheetFormatTest.formatLine_hires_flac.
        composeRule.onNodeWithText("FLAC — 24/96 — 2 ch — 1411 kbps").assertExists()
        composeRule.onNodeWithText("In Bloom").assertExists()
    }

    @Test
    fun loaded_all_null_replay_gain_shows_em_dash() {
        composeRule.setContent {
            SpecSheetContent(
                state = SpecSheetUiState.Loaded(
                    entry = hiresFlacEntry(
                        replayGainTrackDb = null,
                        replayGainAlbumDb = null,
                        replayGainTrackPeak = null,
                        replayGainAlbumPeak = null,
                    ),
                    aggregate = sampleAggregate(),
                ),
                onBack = {},
            )
        }
        // formatReplayGain(null, null) renders the bare em-dash "—" (U+2014). It
        // is the Track row AND the Album row value, so exactly two standalone "—"
        // text nodes exist. (The em-dash inside the formatLine headline is a
        // substring of a larger node and does not match whole-text "—".)
        composeRule.onAllNodesWithText("—").assertCountEquals(2)
    }

    @Test
    fun not_found_shows_empty_state_string() {
        composeRule.setContent {
            SpecSheetContent(
                state = SpecSheetUiState.NotFound,
                onBack = {},
            )
        }
        // Exact NotFound empty-state copy from SpecSheetContent.kt (CenteredMessage).
        composeRule.onNodeWithText("Track not found").assertExists()
    }
}

/**
 * Complete [SpecSheetEntry] fake (all 17 fields populated) for the Loaded
 * states. ReplayGain fields are overridable so the null-RG test can blank them
 * while leaving the golden-path test a fully-populated entry.
 */
private fun hiresFlacEntry(
    replayGainTrackDb: Double? = 0.3,
    replayGainAlbumDb: Double? = -1.2,
    replayGainTrackPeak: Double? = 0.998,
    replayGainAlbumPeak: Double? = 0.999,
): SpecSheetEntry = SpecSheetEntry(
    trackId = "42",
    title = "In Bloom",
    codec = "FLAC",
    sampleRateHz = 96_000,
    bitDepth = 24,
    channels = 2,
    bitrateKbps = 1411,
    durationMs = 240_000L,
    replayGainTrackDb = replayGainTrackDb,
    replayGainAlbumDb = replayGainAlbumDb,
    replayGainTrackPeak = replayGainTrackPeak,
    replayGainAlbumPeak = replayGainAlbumPeak,
    hasEmbeddedArt = true,
    filePath = "/music/nirvana/in-bloom.flac",
    fileSizeBytes = 42_000_000L,
    fileMtimeMs = 1_600_000_000_000L,
    hasKnownMtime = true,
)

/** Complete [LibraryAggregate] fake (all 5 fields) for the Loaded footer. */
private fun sampleAggregate(): LibraryAggregate = LibraryAggregate(
    totalTracks = 27_000L,
    totalBytes = 1_500_000_000_000L,
    codecCounts = mapOf("FLAC" to 26_000L, "MP3" to 1_000L),
    replayGainCoverage = 0.87,
    knownMtimeCoverage = 1.0,
)
