package com.clayworks.kiln.library.settings.internal

import co.touchlab.kermit.Logger
import com.clayworks.kiln.library.settings.ThemeMode
import kotlinx.serialization.json.Json

private val log = Logger.withTag("SettingsKeys")

/**
 * Well-known setting keys. New settings add a new constant here + accessor
 * pair on SettingsRepository; no schema change needed because the table is
 * generic key/value.
 */
internal object SettingKey {
    const val THEME_MODE = "theme_mode"
    const val SCAN_ON_LAUNCH = "scan_on_launch"
    const val SCAN_FOLDERS = "scan_folders"
}

/**
 * Decode ThemeMode from stored string; unknown / null → System default.
 * Surviving a corrupt enum value is preferable to crashing on launch.
 */
internal fun parseThemeMode(stored: String?): ThemeMode = when (stored) {
    null -> ThemeMode.System
    else -> try {
        ThemeMode.valueOf(stored)
    } catch (e: IllegalArgumentException) {
        log.w { "Unknown ThemeMode value '$stored'; falling back to System" }
        ThemeMode.System
    }
}

/**
 * JSON-encode the scan-folders list. kotlinx-serialization handles escaping
 * (Windows backslashes, embedded quotes, etc.) which a manual delimiter
 * would not. Uses kotlinx.serialization's inline typed API rather than
 * ListSerializer(serializer()) — equivalent bytecode, cleaner call site
 * per Plan Task 4 implementer's-choice note.
 */
internal fun scanFoldersToJson(folders: List<String>): String =
    Json.encodeToString<List<String>>(folders)

/**
 * Decode JSON-encoded scan folders. null → empty list. Parse failure →
 * empty list + log warning (corrupt or pre-Track-A row shouldn't crash the app).
 */
internal fun scanFoldersFromJson(stored: String?): List<String> {
    if (stored.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<String>>(stored)
    } catch (e: Exception) {
        log.w(e) { "Corrupt scan_folders value '$stored'; falling back to empty list" }
        emptyList()
    }
}
