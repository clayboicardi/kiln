package com.clayworks.kiln.library.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.db.DatabaseWriter
import com.clayworks.kiln.library.settings.internal.SettingKey
import com.clayworks.kiln.library.settings.internal.parsePreAmpDb
import com.clayworks.kiln.library.settings.internal.parseReplayGainMode
import com.clayworks.kiln.library.settings.internal.parseThemeMode
import com.clayworks.kiln.library.settings.internal.scanFoldersFromJson
import com.clayworks.kiln.library.settings.internal.scanFoldersToJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * SQLDelight-backed SettingsRepository. The generated SettingsQueries.selectByKey
 * returns Query<String> (asserts non-null via getString(0)!!), so combined with
 * sqldelight-coroutines' mapToOneOrNull this yields Flow<String?> — null when
 * the row is absent, the stored value when one exists. Defaulting then happens
 * at the parse layer (parseThemeMode, scanFoldersFromJson) without a row wrapper.
 */
class SettingsRepositoryImpl(
    private val db: KilnDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val writer: DatabaseWriter,
) : SettingsRepository {

    override val themeMode: Flow<ThemeMode> =
        db.settingsQueries.selectByKey(SettingKey.THEME_MODE)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> parseThemeMode(value) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        writer.write { settingsQueries.upsert(key = SettingKey.THEME_MODE, value_ = mode.name) }
    }

    override val scanOnLaunch: Flow<Boolean> =
        db.settingsQueries.selectByKey(SettingKey.SCAN_ON_LAUNCH)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> value == "true" }

    override suspend fun setScanOnLaunch(enabled: Boolean) {
        writer.write { settingsQueries.upsert(key = SettingKey.SCAN_ON_LAUNCH, value_ = enabled.toString()) }
    }

    override val autoScanOnFolderAdd: Flow<Boolean> =
        db.settingsQueries.selectByKey(SettingKey.AUTO_SCAN_ON_FOLDER_ADD)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> value != "false" }

    override suspend fun setAutoScanOnFolderAdd(enabled: Boolean) {
        writer.write { settingsQueries.upsert(key = SettingKey.AUTO_SCAN_ON_FOLDER_ADD, value_ = enabled.toString()) }
    }

    override val scanFolders: Flow<List<String>> =
        db.settingsQueries.selectByKey(SettingKey.SCAN_FOLDERS)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> scanFoldersFromJson(value) }

    override suspend fun setScanFolders(folders: List<String>) {
        writer.write {
            settingsQueries.upsert(
                key = SettingKey.SCAN_FOLDERS,
                value_ = scanFoldersToJson(folders),
            )
        }
    }

    override val replayGainMode: Flow<ReplayGainMode> =
        db.settingsQueries.selectByKey(SettingKey.REPLAY_GAIN_MODE)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> parseReplayGainMode(value) }

    override suspend fun setReplayGainMode(mode: ReplayGainMode) {
        writer.write { settingsQueries.upsert(key = SettingKey.REPLAY_GAIN_MODE, value_ = mode.name) }
    }

    override val replayGainPreAmpDb: Flow<Double> =
        db.settingsQueries.selectByKey(SettingKey.REPLAY_GAIN_PRE_AMP_DB)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> parsePreAmpDb(value) }

    override suspend fun setReplayGainPreAmpDb(db: Double) {
        // The `db: Double` param no longer collides with the field: inside write { } the
        // receiver IS the KilnDatabase, so `settingsQueries` resolves on it while `db` is the
        // Double pre-amp value. (Drops the former kilnDb alias — gemini G3.)
        writer.write {
            settingsQueries.upsert(
                key = SettingKey.REPLAY_GAIN_PRE_AMP_DB,
                value_ = db.toString(),
            )
        }
    }
}
