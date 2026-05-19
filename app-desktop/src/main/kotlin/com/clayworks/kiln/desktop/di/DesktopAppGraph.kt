// DesktopAppGraph — root kotlin-inject component for :app-desktop. Wires
// the Source Protocol + LibraryScanner for the Desktop app.
//
// Per Session 8 handoff H3 + scaffold prep §8. KSP generates a
// `DesktopAppGraph::class.create(userDataDir, scanFolders)` extension function
// that returns a concrete implementation. Main.kt (at H7) instantiates the
// graph once and holds it for the process lifetime.
//
// What's NOT yet wired: PlatformPlayer. JavaSoundPlayerImpl + the JNA libFLAC
// decoder land at H5 + H6 (Session 9-10). Adding `abstract val player:
// PlatformPlayer` here without a `@Provides` would fail KSP generation; the
// member is intentionally omitted until the impls exist. When they do, add
// the abstract member + a @Provides function returning JavaSoundPlayerImpl.

package com.clayworks.kiln.desktop.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.scan.JvmFilesystemScanner
import com.clayworks.kiln.library.scan.LibraryScanner
import com.clayworks.kiln.library.source.LocalLibrarySource
import com.clayworks.kiln.library.source.MusicSource
import kotlinx.coroutines.Dispatchers
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import java.nio.file.Path
import java.util.Properties

/**
 * Type-tag for the user data directory (where kiln.db lives). Distinguishes
 * from the music scan folders below — both are `Path`-typed, kotlin-inject
 * needs them as distinct bindings.
 */
@JvmInline
value class UserDataDir(val path: Path)

/**
 * Type-tag for the list of music-library scan folders. Default is Clay's
 * D:\tiddl per the project gotcha; a future Settings UI replaces this at
 * runtime by constructing a new graph (or by reading from a Settings table
 * inside the existing DB before instantiating downstream consumers).
 */
@JvmInline
value class ScanFolders(val paths: List<Path>)

@Singleton
@Component
abstract class DesktopAppGraph(
    @get:Provides protected val userDataDir: UserDataDir,
    @get:Provides protected val scanFolders: ScanFolders,
) {
    abstract val musicSource: MusicSource
    abstract val scanner: LibraryScanner

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

    @Singleton
    @Provides
    protected fun localLibrarySource(db: KilnDatabase): MusicSource =
        LocalLibrarySource(db, Dispatchers.IO)

    @Singleton
    @Provides
    protected fun filesystemScanner(
        scanFolders: ScanFolders,
        db: KilnDatabase,
        driver: SqlDriver,
    ): LibraryScanner = JvmFilesystemScanner(scanFolders.paths, db, driver, Dispatchers.IO)
}
