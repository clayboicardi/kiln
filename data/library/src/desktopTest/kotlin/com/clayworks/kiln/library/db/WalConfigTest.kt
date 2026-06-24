package com.clayworks.kiln.library.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.clayworks.kiln.data.library.db.KilnDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the desktop driver recipe used by `DesktopAppGraph.sqlDriver()` (#31 item 3): the xerial
 * property keys `journal_mode` and `busy_timeout` must take effect on a FILE database. A file URL
 * (not `JdbcSqliteDriver.IN_MEMORY`) is required — only file URLs hit `ThreadedConnectionManager`
 * (per-thread connections) and real WAL. Keep these properties in sync with `DesktopAppGraph`.
 */
class WalConfigTest {

    private fun walFileDriver(dbFile: Path): JdbcSqliteDriver =
        JdbcSqliteDriver(
            url = "jdbc:sqlite:${dbFile.toAbsolutePath()}",
            properties = Properties().apply {
                put("foreign_keys", "true")
                put("journal_mode", "WAL")
                put("busy_timeout", "5000")
            },
            schema = KilnDatabase.Schema,
        )

    /** WAL leaves `-wal`/`-shm` sidecars next to the db file; delete all three. */
    private fun cleanup(dbFile: Path) {
        Files.deleteIfExists(dbFile)
        Files.deleteIfExists(dbFile.resolveSibling("${dbFile.fileName}-wal"))
        Files.deleteIfExists(dbFile.resolveSibling("${dbFile.fileName}-shm"))
    }

    private fun queryString(driver: JdbcSqliteDriver, sql: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 0,
        ).value

    private fun queryLong(driver: JdbcSqliteDriver, sql: String): Long? =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) },
            parameters = 0,
        ).value

    /** Authoritative "WAL is on" check: the recipe yields journal_mode=wal + busy_timeout=5000. */
    @Test
    fun `desktop driver recipe enables WAL and busy_timeout`() {
        val dbFile = Files.createTempFile("kiln-wal-test", ".db")
        val driver = walFileDriver(dbFile)
        try {
            assertEquals("wal", queryString(driver, "PRAGMA journal_mode;")?.lowercase())
            assertEquals(5000L, queryLong(driver, "PRAGMA busy_timeout;"))
        } finally {
            driver.close()
            cleanup(dbFile)
        }
    }

    /**
     * Multi-connection smoke (codex C4): `ThreadedConnectionManager` gives each thread its own
     * connection, so this verifies a read on a SEPARATE connection succeeds while a writer holds an
     * open transaction — i.e. the per-thread reader connection isn't starved. Deterministic via
     * latches (no `Thread.sleep`).
     *
     * NOTE (codex round-3): this is intentionally NOT a strict WAL-vs-rollback discriminator — a
     * reader succeeds during the writer's RESERVED phase under either journal mode. The
     * authoritative "WAL is configured" assertion is the PRAGMA-readback test above; the strict
     * no-`SQLITE_BUSY`-under-checkpoint-contention behavior is verified on-device.
     */
    @Test
    fun `a second thread can read during an open writer transaction`() {
        val dbFile = Files.createTempFile("kiln-wal-concurrency", ".db")
        val driver = walFileDriver(dbFile)
        val db = KilnDatabase(driver)
        val writerInTxn = java.util.concurrent.CountDownLatch(1)
        val readDone = java.util.concurrent.CountDownLatch(1)
        val readError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        try {
            val writerThread = kotlin.concurrent.thread {
                db.transaction {
                    db.artistQueries.insert(name = "W", name_sort = "w", musicbrainz_artist_id = null)
                    writerInTxn.countDown()
                    readDone.await(5, java.util.concurrent.TimeUnit.SECONDS) // hold the txn open
                }
            }
            writerInTxn.await(5, java.util.concurrent.TimeUnit.SECONDS)
            try {
                queryLong(driver, "SELECT count(*) FROM track;") // reader: this thread's own connection
            } catch (t: Throwable) {
                readError.set(t)
            } finally {
                readDone.countDown()
            }
            writerThread.join(5_000)
        } finally {
            driver.close()
            cleanup(dbFile)
        }
        assertNull(readError.get(), "a read on a second connection during an open write txn must not throw")
    }
}
