package com.clayworks.kiln.library.settings.internal

import co.touchlab.kermit.Logger
import com.clayworks.kiln.library.settings.ReplayGainMode
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
    const val AUTO_SCAN_ON_FOLDER_ADD = "auto_scan_on_folder_add"
    const val SCAN_FOLDERS = "scan_folders"
    const val REPLAY_GAIN_MODE = "replay_gain_mode"
    const val REPLAY_GAIN_PRE_AMP_DB = "replay_gain_pre_amp_db"
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

internal const val PRE_AMP_DB_MIN = -12.0
internal const val PRE_AMP_DB_MAX = 12.0

internal fun parseReplayGainMode(stored: String?): ReplayGainMode = when (stored) {
    null -> ReplayGainMode.Off
    else -> try {
        ReplayGainMode.valueOf(stored)
    } catch (e: IllegalArgumentException) {
        log.w { "Unknown ReplayGainMode value '$stored'; falling back to Off" }
        ReplayGainMode.Off
    }
}

internal fun parsePreAmpDb(stored: String?): Double {
    if (stored.isNullOrBlank()) return 0.0
    val raw = stored.toDoubleOrNull()
    if (raw == null) {
        log.w { "Unparseable pre-amp dB value '$stored'; falling back to 0.0" }
        return 0.0
    }
    return raw.coerceIn(PRE_AMP_DB_MIN, PRE_AMP_DB_MAX)
}
