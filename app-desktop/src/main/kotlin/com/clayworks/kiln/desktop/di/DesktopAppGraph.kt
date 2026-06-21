// DesktopAppGraph — root kotlin-inject component for :app-desktop. Wires
// the Source Protocol + LibraryScanner + PlatformPlayer + SettingsRepository
// for the Desktop app.
//
// Per Session 8 handoff H3 + Session 9 H5 + Phase 2a Track A Task 7. KSP
// generates a `DesktopAppGraph::class.create(userDataDir)` extension function
// that returns a concrete implementation. Main.kt instantiates the graph
// once and holds it for the process lifetime.
//
// PlatformPlayer is JavaSoundPlayerImpl: javax.sound.sampled SourceDataLine
// fed by Decoder→DecodedStream→AudioFrame Flow. Decoder is JvmFlacDecoderImpl
// (JNA bridge to vendored Xiph libFLAC 1.5.0 BSD-3). audioDispatcher is a
// single-thread MAX_PRIORITY executor — JavaSound's SourceDataLine isn't
// documented as thread-safe; constraining all line ops to one thread is the
// safe choice. Per spec §3.4 Concentric Modules.
//
// Track A change: scan folders are no longer a static value-class constructor
// param. They flow from SettingsRepository.scanFolders, which the scanner
// reads via .first() at scan time. Main.kt seeds D:\tiddl on first launch
// when the repo has no row; subsequent launches respect the persisted value.

package com.clayworks.kiln.desktop.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.audio.playback.Decoder
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.audio.playback.createJavaSoundPlayer
import com.clayworks.kiln.audio.playback.createJvmFlacDecoder
import com.clayworks.kiln.audio.playback.createJvmFlacTrackAnalyzer
import com.clayworks.kiln.library.scan.TrackAnalysisRunner
import com.clayworks.kiln.library.scan.TrackAnalyzer
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.scan.JvmFilesystemScanner
import com.clayworks.kiln.library.scan.LibraryScanner
import com.clayworks.kiln.library.scan.LibraryWriteLock
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.settings.SettingsRepositoryImpl
import com.clayworks.kiln.library.source.LibraryStatsSource
import com.clayworks.kiln.library.source.LocalLibrarySource
import com.clayworks.kiln.library.source.MusicSource
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

/**
 * Type-tag for the user data directory (where kiln.db lives). Kept as a
 * value class even though it's the only Path-typed constructor param now —
 * future graph additions may reintroduce ambiguity, and the tag also makes
 * the Main.kt call-site read intentionally at the seam.
 */
@JvmInline
value class UserDataDir(val path: Path)

@Singleton
@Component
abstract class DesktopAppGraph(
    @get:Provides protected val userDataDir: UserDataDir,
) {
    abstract val musicSource: MusicSource
    abstract val libraryStats: LibraryStatsSource
    abstract val scanner: LibraryScanner
    abstract val player: PlatformPlayer
    abstract val settings: SettingsRepository
    abstract val kilnDatabase: KilnDatabase
    abstract val analysisRunner: TrackAnalysisRunner

    /**
     * JdbcSqliteDriver with `schema = KilnDatabase.Schema` auto-creates the
     * schema on first connect and applies migrations via PRAGMA user_version.
     * `foreign_keys = true` in the connection properties enables FK
     * enforcement — SQLite defaults FKs OFF, and the schema's
     * track→album→artist relationships rely on FK enforcement to catch
     * scanner bugs before they pollute the index. (Schema sketch §5.)
     */
    @Singleton
    @Provides
    protected fun sqlDriver(): SqlDriver {
        val dbFile = userDataDir.path.resolve("kiln.db")
        java.nio.file.Files.createDirectories(userDataDir.path)
        return JdbcSqliteDriver(
            url = "jdbc:sqlite:${dbFile.toAbsolutePath()}",
            properties = Properties().apply { put("foreign_keys", "true") },
            schema = KilnDatabase.Schema,
        )
    }

    @Singleton
    @Provides
    protected fun database(driver: SqlDriver): KilnDatabase = KilnDatabase(driver)

    /**
     * SettingsRepository binds the impl-returning provider to the interface
     * type at the graph surface. Existing pattern mirrored from
     * localLibrarySource → MusicSource: kotlin-inject routes the interface
     * consumers (scanner provider below, future UI graph members) to this
     * single instance.
     */
    @Singleton
    @Provides
    protected fun settingsRepository(db: KilnDatabase): SettingsRepository =
        SettingsRepositoryImpl(db, Dispatchers.IO)

    /**
     * One LocalLibrarySource instance, bound to BOTH the MusicSource (browse /
     * search / getPlayable) and LibraryStatsSource (Spec Sheet entry + library
     * aggregate) surfaces below. Provided as the concrete type and re-exposed
     * under each interface so the two graph members share a single instance
     * rather than spinning up two sources over the same DB. Source Protocol
     * invariant: the read interfaces stay separate; the impl co-implements them.
     */
    @Singleton
    @Provides
    protected fun localLibrarySource(db: KilnDatabase): LocalLibrarySource =
        LocalLibrarySource(db, Dispatchers.IO)

    @Provides
    protected fun musicSource(source: LocalLibrarySource): MusicSource = source

    @Provides
    protected fun libraryStatsSource(source: LocalLibrarySource): LibraryStatsSource = source

    /**
     * Shared lock serializing the scanner and the analyzer — both write `track`
     * over the single JdbcSqliteDriver connection. See [LibraryWriteLock].
     */
    @Singleton
    @Provides
    protected fun libraryWriteLock(): LibraryWriteLock = LibraryWriteLock()

    /**
     * Track A: scan folders come from SettingsRepository.scanFolders, mapped
     * String→Path at the seam. The repo Flow emits its current value
     * immediately (defaults to empty when no row exists, otherwise the
     * persisted list); JvmFilesystemScanner reads via .first() at each scan
     * invocation so changes propagate without graph reconstruction.
     *
     * "Empty list" is honored as the user's intent — the scanner's
     * empty-guard short-circuits rather than soft-deleting the library.
     * First-launch seeding (default = D:\tiddl) lives in Main.kt, not here,
     * so the user can legitimately have zero folders post-Task-9 once they
     * clear the list via the SettingsScreen.
     */
    @Singleton
    @Provides
    protected fun filesystemScanner(
        settings: SettingsRepository,
        db: KilnDatabase,
        driver: SqlDriver,
        writeLock: LibraryWriteLock,
    ): LibraryScanner {
        val scanFoldersFlow: Flow<List<Path>> = settings.scanFolders.map { stored ->
            stored.map(Path::of)
        }
        return JvmFilesystemScanner(scanFoldersFlow, db, driver, Dispatchers.IO, writeLock)
    }

    /**
     * Single-thread MAX_PRIORITY executor backing the audio output pipeline.
     * JavaSoundPlayerImpl marshals all SourceDataLine + DecodedStream calls
     * through this dispatcher. Daemon thread so JVM exit isn't blocked by
     * audio cleanup; MAX_PRIORITY because audio underruns are user-visible
     * (clicks/pops) whereas the OS scheduler is otherwise free to deprioritize
     * us.
     */
    @Singleton
    @Provides
    protected fun audioDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kiln-audio-out").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
            }
        }.asCoroutineDispatcher()

    /**
     * Desktop FLAC decoder via JNA + vendored libFLAC 1.5.0. The factory
     * internally invokes LibFlacLoader.load() — idempotent across calls; the
     * NativeLibraryLoader extract-DLL + System.load + jna.library.path setup
     * happens once per JVM.
     */
    @Singleton
    @Provides
    protected fun decoder(): Decoder = createJvmFlacDecoder()

    @Singleton
    @Provides
    protected fun replayGainProcessor(): ReplayGainProcessor = ReplayGainProcessor()

    @Singleton
    @Provides
    protected fun player(
        audioDispatcher: CoroutineDispatcher,
        decoder: Decoder,
        source: MusicSource,
        settings: SettingsRepository,
        rgProcessor: ReplayGainProcessor,
    ): PlatformPlayer = createJavaSoundPlayer(
        audioDispatcher = audioDispatcher,
        decoder = decoder,
        source = source,
        settings = settings,
        rgProcessor = rgProcessor,
    )

    @Singleton
    @Provides
    protected fun trackAnalyzer(): TrackAnalyzer = createJvmFlacTrackAnalyzer()

    @Singleton
    @Provides
    protected fun analysisRunner(
        db: KilnDatabase,
        analyzer: TrackAnalyzer,
        writeLock: LibraryWriteLock,
    ): TrackAnalysisRunner = TrackAnalysisRunner(
        db = db,
        analyzer = analyzer,
        ioDispatcher = Dispatchers.IO,
        writeLock = writeLock,
    )
}
