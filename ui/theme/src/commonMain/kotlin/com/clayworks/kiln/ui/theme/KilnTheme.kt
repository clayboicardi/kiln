package com.clayworks.kiln.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.clayworks.kiln.library.settings.ThemeMode

/**
 * Kiln's Material 3 theme entry point. Phase 2a Track A: ThemeMode-driven
 * Light/Dark/System dispatch with default Material 3 baseline schemes.
 * Phase 2a Flight A (kmpalette landing) replaces these defaults with the
 * Kiln Dynamic album-art-driven palette pipeline — but the contract of
 * "consumers call KilnTheme(themeMode = ...)" is stable from Track A on.
 *
 * Concentric Modules invariant: this composable lives in commonMain with
 * pure Compose-MP deps; no androidx imports. Adapters in androidMain /
 * desktopMain remain unnecessary until Phase 2a Flight A.
 */
@Composable
fun KilnTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemInDark
    }
    val colorScheme: ColorScheme = if (useDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
