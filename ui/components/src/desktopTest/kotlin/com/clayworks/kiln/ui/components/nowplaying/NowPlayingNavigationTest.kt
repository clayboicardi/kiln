// NowPlayingNavigationTest — Voyager Navigator scaffold inside NowPlayingTab.
// Verifies tap-title pushes SpecSheetScreen, and back-button pops to the home
// screen. Placeholder SpecSheetScreen body: "Spec sheet for <trackId>".
//
// Phase 2b-prereq mitigation for falsify F17: Stream A presumes Now Playing
// exists at fidelity required for navigation.

package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.LibraryAggregate
import com.clayworks.kiln.library.source.LibraryStatsSource
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SourceId
import com.clayworks.kiln.library.source.SpecSheetEntry
import com.clayworks.kiln.ui.components.specsheet.LocalLibraryStats
import com.clayworks.kiln.ui.components.specsheet.LocalPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import kotlin.test.Test

class NowPlayingNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleItem = MediaItem(
        itemId = ItemId("42"),
        sourceId = SourceId("local"),
        kind = MediaItem.Kind.Track,
        title = "In Bloom",
        subtitle = "Nirvana",
        durationMs = 240_000L,
    )

    @Test
    fun tap_title_pushes_spec_sheet_and_back_pops_to_home() {
        val player = FakePlatformPlayer(
            initialItem = sampleItem,
            initialState = PlayerState.Ready(isPlaying = false),
        )
        // The pushed SpecSheetScreen reads LocalLibraryStats and renders the
        // resolved entry's title via SpecSheetContent — give it a recognizable,
        // home-screen-distinct title to assert push/pop unambiguously.
        val stats = FakeLibraryStatsSource(specTitle = SPEC_TITLE)

        composeRule.setContent {
            CompositionLocalProvider(
                LocalPlayer provides player,
                LocalLibraryStats provides stats,
            ) {
                NowPlayingTab().Content()
            }
        }

        // Initial state: home screen renders the now-playing title; the spec
        // sheet (a distinct title) is absent.
        composeRule.onNodeWithText("In Bloom").assertIsDisplayed()
        composeRule.onNodeWithText(SPEC_TITLE).assertDoesNotExist()

        // Tap the title — pushes SpecSheetScreen(trackId="42"), which resolves
        // the fake entry and renders its title.
        composeRule.onNodeWithText("In Bloom").performClick()
        composeRule.onNodeWithText(SPEC_TITLE).assertIsDisplayed()

        // Press back affordance — pops to home screen.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("In Bloom").assertIsDisplayed()
        composeRule.onNodeWithText(SPEC_TITLE).assertDoesNotExist()
    }

    private companion object {
        const val SPEC_TITLE = "Spec Sheet Entry 42"
    }
}

/**
 * Minimal in-memory [LibraryStatsSource] stub. `specSheetEntry` always resolves
 * to a single fixed entry (titled [specTitle]); `aggregateStats` emits one
 * empty-ish aggregate. Enough for SpecSheetScreen to render a Loaded state in a
 * navigation-only test.
 */
private class FakeLibraryStatsSource(
    private val specTitle: String,
) : LibraryStatsSource {
    override fun specSheetEntry(trackId: String): Flow<SpecSheetEntry?> = flowOf(
        SpecSheetEntry(
            trackId = trackId,
            title = specTitle,
            codec = "FLAC",
            sampleRateHz = 44_100,
            bitDepth = 16,
            channels = 2,
            bitrateKbps = null,
            durationMs = 240_000L,
            replayGainTrackDb = null,
            replayGainAlbumDb = null,
            replayGainTrackPeak = null,
            replayGainAlbumPeak = null,
            hasEmbeddedArt = false,
            filePath = "/music/in-bloom.flac",
            fileSizeBytes = 1_024L,
            fileMtimeMs = 0L,
            hasKnownMtime = true,
        ),
    )

    override fun aggregateStats(): Flow<LibraryAggregate> = flowOf(
        LibraryAggregate(
            totalTracks = 1L,
            totalBytes = 1_024L,
            codecCounts = mapOf("FLAC" to 1L),
            replayGainCoverage = 0.0,
            knownMtimeCoverage = 1.0,
        ),
    )
}
