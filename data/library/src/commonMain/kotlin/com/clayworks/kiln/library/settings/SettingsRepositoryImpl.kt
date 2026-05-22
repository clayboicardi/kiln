package com.clayworks.kiln.library.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.settings.internal.SettingKey
import com.clayworks.kiln.library.settings.internal.parseThemeMode
import com.clayworks.kiln.library.settings.internal.scanFoldersFromJson
import com.clayworks.kiln.library.settings.internal.scanFoldersToJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
) : SettingsRepository {

    override val themeMode: Flow<ThemeMode> =
        db.settingsQueries.selectByKey(SettingKey.THEME_MODE)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> parseThemeMode(value) }

    override suspend fun setThemeMode(mode: ThemeMode): Unit = withContext(ioDispatcher) {
        db.settingsQueries.upsert(key = SettingKey.THEME_MODE, value_ = mode.name)
    }

    override val scanOnLaunch: Flow<Boolean> =
        db.settingsQueries.selectByKey(SettingKey.SCAN_ON_LAUNCH)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> value == "true" }

    override suspend fun setScanOnLaunch(enabled: Boolean): Unit = withContext(ioDispatcher) {
        db.settingsQueries.upsert(key = SettingKey.SCAN_ON_LAUNCH, value_ = enabled.toString())
    }

    override val scanFolders: Flow<List<String>> =
        db.settingsQueries.selectByKey(SettingKey.SCAN_FOLDERS)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> scanFoldersFromJson(value) }

    override suspend fun setScanFolders(folders: List<String>): Unit = withContext(ioDispatcher) {
        db.settingsQueries.upsert(
            key = SettingKey.SCAN_FOLDERS,
            value_ = scanFoldersToJson(folders),
        )
    }
}
