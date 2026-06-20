// AndroidAppGraph — root kotlin-inject component for :app-android. Wires
// the Source Protocol + LibraryScanner + PlatformPlayer for the Android app.
//
// Per Session 8 handoff H3 + scaffold prep §8. KSP generates an
// `AndroidAppGraph::class.create(applicationContext)` extension function that
// returns a concrete implementation. KilnApplication holds the singleton
// instance for the process lifetime.
//
// The graph is intentionally narrow: scanner + source + player exposed as
// `abstract val`. Consumers (MainActivity at H7) read from these.

package com.clayworks.kiln.di

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.audio.playback.Media3ExoPlayerImpl
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.audio.playback.createAndroidMediaTrackAnalyzer
import com.clayworks.kiln.library.scan.TrackAnalysisRunner
import com.clayworks.kiln.library.scan.TrackAnalyzer
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.scan.AndroidFormatFactBackfill
import com.clayworks.kiln.library.scan.AndroidMediaStoreScanner
import com.clayworks.kiln.library.scan.LibraryScanner
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.settings.SettingsRepositoryImpl
import com.clayworks.kiln.library.source.LocalLibrarySource
import com.clayworks.kiln.library.source.MusicSource
import kotlinx.coroutines.Dispatchers
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Singleton
@Component
abstract class AndroidAppGraph(
    @get:Provides protected val context: Context,
) {
    abstract val musicSource: MusicSource
    abstract val scanner: LibraryScanner
    abstract val player: PlatformPlayer
    abstract val settings: SettingsRepository
    abstract val kilnDatabase: KilnDatabase
    abstract val analysisRunner: TrackAnalysisRunner

    /**
     * AndroidSqliteDriver auto-creates/migrates the schema via PRAGMA
     * user_version. The Callback hook enables foreign-key enforcement on
     * every connection open — SQLite defaults FKs OFF, and the schema's
     * track→album→artist relationships rely on FK enforcement to catch
     * scanner bugs before they pollute the index. (Schema sketch §5.)
     *
     * `factory = RequerySQLiteOpenHelperFactory()` switches the underlying
     * SQLite from Android's system one to a bundled SQLite 3.49.x. Required
     * because Session 10 H8 on Pixel 10 / Android 16 surfaced
     * "no such module: fts5" at schema-creation time — vendor builds
     * don't always expose the FTS5 module to user-space queries despite
     * AOSP's defaults. Bundled SQLite guarantees FTS5 across device variance.
     */
    @Singleton
    @Provides
    protected fun sqlDriver(): SqlDriver = AndroidSqliteDriver(
        schema = KilnDatabase.Schema,
        context = context,
        name = "kiln.db",
        factory = RequerySQLiteOpenHelperFactory(),
        callback = object : AndroidSqliteDriver.Callback(KilnDatabase.Schema) {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        },
    )

    @Singleton
    @Provides
    protected fun database(driver: SqlDriver): KilnDatabase = KilnDatabase(driver)

    /**
     * SettingsRepository binds the impl-returning provider to the interface
     * type at the graph surface. Mirrors DesktopAppGraph's pattern: kotlin-inject
     * routes the interface consumers (MainActivity's KilnTheme wrap +
     * AndroidSettingsRoute composable) to this single instance.
     */
    @Singleton
    @Provides
    protected fun settingsRepository(db: KilnDatabase): SettingsRepository =
        SettingsRepositoryImpl(db, Dispatchers.IO)

    @Singleton
    @Provides
    protected fun localLibrarySource(db: KilnDatabase): MusicSource =
        LocalLibrarySource(db, Dispatchers.IO)

    @Singleton
    @Provides
    protected fun formatBackfill(context: Context, db: KilnDatabase): AndroidFormatFactBackfill =
        AndroidFormatFactBackfill(context, db, Dispatchers.IO)

    @Singleton
    @Provides
    protected fun mediaStoreScanner(
        context: Context,
        settings: SettingsRepository,
        db: KilnDatabase,
        driver: SqlDriver,
        backfill: AndroidFormatFactBackfill,
    ): LibraryScanner = AndroidMediaStoreScanner(
        context = context,
        safTreeUrisFlow = settings.scanFolders,
        db = db,
        driver = driver,
        ioDispatcher = Dispatchers.IO,
        backfill = backfill,
    )

    /**
     * ReplayGainProcessor is the single per-process audio processor wired into
     * the Media3 pipeline via KilnRenderersFactory + MediaProcessorAdapter.
     * Singleton so the settings-flow collector in Media3ExoPlayerImpl can
     * mutate gain on the same instance the audio pipeline reads.
     *
     * Mirrors DesktopAppGraph.replayGainProcessor() from PR #13.
     */
    @Singleton
    @Provides
    protected fun replayGainProcessor(): ReplayGainProcessor = ReplayGainProcessor()

    /**
     * Media3ExoPlayerImpl owns native resources (ExoPlayer + MediaSession);
     * one instance per process. Its constructor must run on the main thread
     * (ExoPlayer single-thread-access rule). KilnApplication.onCreate is the
     * right place — that's main-thread by Android contract.
     */
    @Singleton
    @Provides
    protected fun media3Player(
        context: Context,
        source: MusicSource,
        settings: SettingsRepository,
        rgProcessor: ReplayGainProcessor,
    ): PlatformPlayer = Media3ExoPlayerImpl(
        context = context,
        source = source,
        settings = settings,
        rgProcessor = rgProcessor,
    )

    @Singleton
    @Provides
    protected fun trackAnalyzer(context: Context): TrackAnalyzer =
        createAndroidMediaTrackAnalyzer(context)

    @Singleton
    @Provides
    protected fun analysisRunner(
        db: KilnDatabase,
        analyzer: TrackAnalyzer,
    ): TrackAnalysisRunner = TrackAnalysisRunner(
        db = db,
        analyzer = analyzer,
        ioDispatcher = Dispatchers.IO,
    )
}
