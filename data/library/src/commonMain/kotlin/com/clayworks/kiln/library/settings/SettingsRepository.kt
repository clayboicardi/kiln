package com.clayworks.kiln.library.settings

import kotlinx.coroutines.flow.Flow

/**
 * Theme selection — Light forces light, Dark forces dark, System defers to
 * platform's dark-mode signal. KilnTheme reads this and applies the matching
 * Material 3 ColorScheme.
 */
enum class ThemeMode { Light, Dark, System }

/**
 * ReplayGain consumer-side gain mode. Track applies the per-track gain;
 * Album applies the per-album rollup; Off bypasses RG entirely.
 *
 * The setting is persisted by Track D-C. Consumer-side application landed
 * in Track D-B: JavaSoundPlayerImpl multiplier on Desktop (PR #13) and
 * Media3 AudioProcessor via KilnRenderersFactory on Android (this branch's
 * PR). Per-track RG values come from the analyzer (Track D-A); tracks
 * without analyzed values silently fall back to gain = 1.0.
 */
enum class ReplayGainMode { Off, Track, Album }

/**
 * Phase 2a Track A: persistent user preferences. Implementations back to the
 * `settings` SQLDelight table (key/value). Flows emit defaults until a value
 * is written, then emit each write. Consumers call .first() for one-shot
 * reads (scanner pulls scan_folders this way) or collect for reactive UI.
 */
interface SettingsRepository {
    /** Selected theme; default System. */
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** Whether to trigger a library scan on app launch; default false. */
    val scanOnLaunch: Flow<Boolean>
    suspend fun setScanOnLaunch(enabled: Boolean)

    /**
     * Filesystem paths (Desktop) or SAF tree URIs (Android, Track B) the
     * scanner walks. String-typed so the interface stays platform-neutral —
     * `java.nio.file.Path` is JVM-only; Android URIs aren't paths. Consumers
     * parse to their platform type at the DI seam. Default empty.
     */
    val scanFolders: Flow<List<String>>
    suspend fun setScanFolders(folders: List<String>)

    /** ReplayGain mode; default Off. */
    val replayGainMode: Flow<ReplayGainMode>
    suspend fun setReplayGainMode(mode: ReplayGainMode)

    /**
     * Pre-amp dB applied on top of the ReplayGain value. Range: -12.0..+12.0.
     * Default 0.0. Reads clamp to range; writes accept any Double.
     */
    val replayGainPreAmpDb: Flow<Double>
    suspend fun setReplayGainPreAmpDb(db: Double)
}
