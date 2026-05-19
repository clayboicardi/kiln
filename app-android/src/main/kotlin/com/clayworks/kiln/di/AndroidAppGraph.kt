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
import com.clayworks.kiln.audio.playback.Media3ExoPlayerImpl
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.scan.AndroidMediaStoreScanner
import com.clayworks.kiln.library.scan.LibraryScanner
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

    /**
     * AndroidSqliteDriver auto-creates/migrates the schema via PRAGMA
     * user_version. The Callback hook enables foreign-key enforcement on
     * every connection open — SQLite defaults FKs OFF, and the schema's
     * track→album→artist relationships rely on FK enforcement to catch
     * scanner bugs before they pollute the index. (Schema sketch §5.)
     */
    @Singleton
    @Provides
    protected fun sqlDriver(): SqlDriver = AndroidSqliteDriver(
        schema = KilnDatabase.Schema,
        context = context,
        name = "kiln.db",
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

    @Singleton
    @Provides
    protected fun localLibrarySource(db: KilnDatabase): MusicSource =
        LocalLibrarySource(db, Dispatchers.IO)

    @Singleton
    @Provides
    protected fun mediaStoreScanner(
        context: Context,
        db: KilnDatabase,
        driver: SqlDriver,
    ): LibraryScanner = AndroidMediaStoreScanner(context, db, driver, Dispatchers.IO)

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
    ): PlatformPlayer = Media3ExoPlayerImpl(context, source)
}
