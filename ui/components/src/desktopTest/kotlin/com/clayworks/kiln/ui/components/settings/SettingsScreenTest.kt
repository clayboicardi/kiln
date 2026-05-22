package com.clayworks.kiln.ui.components.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.ThemeMode
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun defaultState(
        themeMode: ThemeMode = ThemeMode.System,
        scanOnLaunch: Boolean = false,
        scanFolders: List<String> = emptyList(),
        replayGainMode: ReplayGainMode = ReplayGainMode.Off,
        replayGainPreAmpDb: Double = 0.0,
        backfill: BackfillUiState = BackfillUiState.Idle(missingCount = 0),
    ) = SettingsState(
        themeMode = themeMode,
        scanOnLaunch = scanOnLaunch,
        scanFolders = scanFolders,
        replayGainMode = replayGainMode,
        replayGainPreAmpDb = replayGainPreAmpDb,
        backfill = backfill,
    )

    private fun renderScreen(
        state: SettingsState = defaultState(),
        onThemeModeChange: (ThemeMode) -> Unit = {},
        onScanOnLaunchChange: (Boolean) -> Unit = {},
        onPickFolder: () -> Unit = {},
        onRemoveFolder: (String) -> Unit = {},
        onReplayGainModeChange: (ReplayGainMode) -> Unit = {},
        onReplayGainPreAmpDbChange: (Double) -> Unit = {},
        onTriggerBackfill: () -> Unit = {},
    ) {
        composeRule.setContent {
            SettingsScreen(
                state = state,
                onThemeModeChange = onThemeModeChange,
                onScanOnLaunchChange = onScanOnLaunchChange,
                onPickFolder = onPickFolder,
                onRemoveFolder = onRemoveFolder,
                onReplayGainModeChange = onReplayGainModeChange,
                onReplayGainPreAmpDbChange = onReplayGainPreAmpDbChange,
                onTriggerBackfill = onTriggerBackfill,
            )
        }
    }

    @Test
    fun renders_all_four_sections() {
        renderScreen()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Behavior").assertIsDisplayed()
        composeRule.onNodeWithText("Library folders").assertIsDisplayed()
        composeRule.onNodeWithText("ReplayGain").assertIsDisplayed()
    }

    @Test
    fun shows_empty_state_when_no_scan_folders() {
        renderScreen()
        composeRule.onNodeWithText("No folders configured. Click 'Add Folder' to choose your music library.")
            .assertIsDisplayed()
    }

    @Test
    fun lists_configured_scan_folders() {
        renderScreen(state = defaultState(scanFolders = listOf("D:\\tiddl", "E:\\flac-import")))
        composeRule.onNodeWithText("D:\\tiddl").assertIsDisplayed()
        composeRule.onNodeWithText("E:\\flac-import").assertIsDisplayed()
    }

    @Test
    fun theme_radio_click_invokes_callback() {
        var captured: ThemeMode? = null
        renderScreen(onThemeModeChange = { captured = it })
        composeRule.onNodeWithText("Dark").performClick()
        assertEquals(ThemeMode.Dark, captured)
    }

    @Test
    fun add_folder_button_invokes_picker_callback() {
        var clicked = false
        renderScreen(onPickFolder = { clicked = true })
        composeRule.onNodeWithText("Add Folder").performClick()
        assertTrue(clicked)
    }

    @Test
    fun backfill_idle_shows_track_count() {
        // assertExists rather than assertIsDisplayed: the ReplayGain section is below
        // the fold in a bounded test window (non-scrollable Column); the node is in the
        // composition tree but outside the visible viewport.
        renderScreen(state = defaultState(backfill = BackfillUiState.Idle(missingCount = 42)))
        composeRule.onNodeWithText("42 track(s) need analysis.").assertExists()
    }

    @Test
    fun backfill_idle_zero_shows_all_done() {
        renderScreen(state = defaultState(backfill = BackfillUiState.Idle(missingCount = 0)))
        composeRule.onNodeWithText("All tracks have ReplayGain values.").assertExists()
    }

    @Test
    fun backfill_in_progress_shows_progress_text() {
        renderScreen(
            state = defaultState(
                backfill = BackfillUiState.InProgress(analyzed = 10, skipped = 2, total = 50),
            ),
        )
        composeRule.onNodeWithText("Analyzing: 10 of 50 done, 2 skipped").assertExists()
    }

    @Test
    fun backfill_complete_shows_summary() {
        renderScreen(
            state = defaultState(
                backfill = BackfillUiState.Complete(
                    analyzed = 48,
                    skipped = 2,
                    total = 50,
                    albumsAggregated = 5,
                    durationMs = 12000L,
                ),
            ),
        )
        composeRule.onNodeWithText("Done. 48 analyzed, 2 skipped, 5 albums aggregated in 12s.")
            .assertExists()
    }

    @Test
    fun replay_gain_mode_radio_click_invokes_callback() {
        var captured: ReplayGainMode? = null
        renderScreen(onReplayGainModeChange = { captured = it })
        composeRule.onNodeWithText("Track").performClick()
        assertEquals(ReplayGainMode.Track, captured)
    }
}
