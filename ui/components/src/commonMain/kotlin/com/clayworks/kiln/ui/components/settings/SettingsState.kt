package com.clayworks.kiln.ui.components.settings

import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.ThemeMode

/**
 * Plain hoisted state for SettingsScreen — no Compose dependencies, no
 * coroutine internals. App modules collect SettingsRepository.Flow values
 * into one of these, pass into the screen, and route callbacks back to
 * setX(...) calls on the repository.
 *
 * String-typed `scanFolders` matches the cross-platform contract on the
 * repository: java.nio.file.Path is JVM-only, SAF URIs aren't paths. The
 * screen displays them as-is.
 */
data class SettingsState(
    val themeMode: ThemeMode,
    val scanOnLaunch: Boolean,
    val scanFolders: List<String>,
    val replayGainMode: ReplayGainMode,
    val replayGainPreAmpDb: Double,
    val backfill: BackfillUiState,
)
