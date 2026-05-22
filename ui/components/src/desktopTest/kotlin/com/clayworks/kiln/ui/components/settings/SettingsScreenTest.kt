package com.clayworks.kiln.ui.components.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clayworks.kiln.library.settings.ThemeMode
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renders_all_three_sections() {
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = emptyList(),
                ),
                onThemeModeChange = {},
                onScanOnLaunchChange = {},
                onPickFolder = {},
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Behavior").assertIsDisplayed()
        composeRule.onNodeWithText("Library folders").assertIsDisplayed()
    }

    @Test
    fun shows_empty_state_when_no_scan_folders() {
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = emptyList(),
                ),
                onThemeModeChange = {},
                onScanOnLaunchChange = {},
                onPickFolder = {},
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("No folders configured. Click 'Add Folder' to choose your music library.")
            .assertIsDisplayed()
    }

    @Test
    fun lists_configured_scan_folders() {
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = listOf("D:\\tiddl", "E:\\flac-import"),
                ),
                onThemeModeChange = {},
                onScanOnLaunchChange = {},
                onPickFolder = {},
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("D:\\tiddl").assertIsDisplayed()
        composeRule.onNodeWithText("E:\\flac-import").assertIsDisplayed()
    }

    @Test
    fun theme_radio_click_invokes_callback() {
        var captured: ThemeMode? = null
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = emptyList(),
                ),
                onThemeModeChange = { captured = it },
                onScanOnLaunchChange = {},
                onPickFolder = {},
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("Dark").performClick()
        assertEquals(ThemeMode.Dark, captured)
    }

    @Test
    fun add_folder_button_invokes_picker_callback() {
        var clicked = false
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = emptyList(),
                ),
                onThemeModeChange = {},
                onScanOnLaunchChange = {},
                onPickFolder = { clicked = true },
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("Add Folder").performClick()
        assertTrue(clicked)
    }
}
