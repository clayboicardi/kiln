package com.clayworks.kiln.library.settings

import com.clayworks.kiln.library.db.DatabaseWriter
import com.clayworks.kiln.library.settings.internal.SettingKey
import com.clayworks.kiln.library.source.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRepositoryImplTest {

    private lateinit var testDb: TestDb
    private lateinit var repo: SettingsRepository

    @BeforeTest
    fun setUp() {
        testDb = TestDb()
        repo = SettingsRepositoryImpl(
            testDb.db,
            ioDispatcher = Dispatchers.Unconfined,
            writer = DatabaseWriter(testDb.db, Dispatchers.Unconfined),
        )
    }

    @AfterTest
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun defaults_when_unset() = runTest {
        assertEquals(ThemeMode.System, repo.themeMode.first())
        assertEquals(false, repo.scanOnLaunch.first())
        assertEquals(emptyList<String>(), repo.scanFolders.first())
    }

    @Test
    fun theme_mode_round_trip() = runTest {
        repo.setThemeMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, repo.themeMode.first())
        repo.setThemeMode(ThemeMode.Light)
        assertEquals(ThemeMode.Light, repo.themeMode.first())
        repo.setThemeMode(ThemeMode.System)
        assertEquals(ThemeMode.System, repo.themeMode.first())
    }

    @Test
    fun scan_on_launch_round_trip() = runTest {
        repo.setScanOnLaunch(true)
        assertEquals(true, repo.scanOnLaunch.first())
        repo.setScanOnLaunch(false)
        assertEquals(false, repo.scanOnLaunch.first())
    }

    @Test
    fun auto_scan_on_folder_add_defaults_true_then_round_trips() = runTest {
        // Default before any write is true (adding a folder implies indexing it).
        assertEquals(true, repo.autoScanOnFolderAdd.first())
        repo.setAutoScanOnFolderAdd(false)
        assertEquals(false, repo.autoScanOnFolderAdd.first())
        repo.setAutoScanOnFolderAdd(true)
        assertEquals(true, repo.autoScanOnFolderAdd.first())
    }

    @Test
    fun scan_folders_round_trip_single() = runTest {
        repo.setScanFolders(listOf("D:\\tiddl"))
        assertEquals(listOf("D:\\tiddl"), repo.scanFolders.first())
    }

    @Test
    fun scan_folders_round_trip_multiple_preserves_order() = runTest {
        val folders = listOf("D:\\tiddl", "E:\\flac-import", "F:\\loaners")
        repo.setScanFolders(folders)
        assertEquals(folders, repo.scanFolders.first())
    }

    @Test
    fun scan_folders_round_trip_empty() = runTest {
        repo.setScanFolders(listOf("X:\\seed"))
        repo.setScanFolders(emptyList())
        assertEquals(emptyList<String>(), repo.scanFolders.first())
    }

    @Test
    fun scan_folders_json_encoding_handles_special_chars() = runTest {
        val folders = listOf("D:\\Music\\Coldplay - X&Y", "/home/user/Music \"backup\"")
        repo.setScanFolders(folders)
        assertEquals(folders, repo.scanFolders.first())
    }

    @Test
    fun unknown_invalid_value_falls_back_to_default() = runTest {
        // Write a value that doesn't parse as ThemeMode — repo should
        // log + return default rather than crash.
        testDb.db.settingsQueries.upsert(key = SettingKey.THEME_MODE, value_ = "FUCHSIA_NEON")
        assertEquals(ThemeMode.System, repo.themeMode.first())
    }

    @Test
    fun replay_gain_mode_default_is_off() = runTest {
        assertEquals(ReplayGainMode.Off, repo.replayGainMode.first())
    }

    @Test
    fun replay_gain_mode_round_trip() = runTest {
        repo.setReplayGainMode(ReplayGainMode.Track)
        assertEquals(ReplayGainMode.Track, repo.replayGainMode.first())
        repo.setReplayGainMode(ReplayGainMode.Album)
        assertEquals(ReplayGainMode.Album, repo.replayGainMode.first())
        repo.setReplayGainMode(ReplayGainMode.Off)
        assertEquals(ReplayGainMode.Off, repo.replayGainMode.first())
    }

    @Test
    fun replay_gain_pre_amp_db_default_is_zero() = runTest {
        assertEquals(0.0, repo.replayGainPreAmpDb.first(), 1e-9)
    }

    @Test
    fun replay_gain_pre_amp_db_round_trip() = runTest {
        repo.setReplayGainPreAmpDb(-3.5)
        assertEquals(-3.5, repo.replayGainPreAmpDb.first(), 1e-9)
        repo.setReplayGainPreAmpDb(6.0)
        assertEquals(6.0, repo.replayGainPreAmpDb.first(), 1e-9)
    }

    @Test
    fun replay_gain_pre_amp_db_clamped_on_read() = runTest {
        repo.setReplayGainPreAmpDb(99.0)
        assertEquals(12.0, repo.replayGainPreAmpDb.first(), 1e-9)
        repo.setReplayGainPreAmpDb(-99.0)
        assertEquals(-12.0, repo.replayGainPreAmpDb.first(), 1e-9)
    }
}
