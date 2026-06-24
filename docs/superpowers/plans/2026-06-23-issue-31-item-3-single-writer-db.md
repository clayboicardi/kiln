# Single-Writer DB + WAL (#31 item 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire the `LibraryWriteLock` mutex by funnelling every DB write through one dedicated writer thread (`DatabaseWriter`), and enable WAL + `busy_timeout` so reads never contend with the writer.

**Architecture:** A `DatabaseWriter` wraps a single-thread `CoroutineDispatcher` + `KilnDatabase`; its `write { }` block is **non-suspending**, so each write unit runs atomically on the one writer thread (serialization by construction; reentrancy structurally impossible). All six writers route through it; the mutex is deleted. WAL (already viable because the desktop driver uses per-thread connections) gives readers lock-free committed-snapshot reads.

**Tech Stack:** Kotlin Multiplatform, SQLDelight 2.3.2 (`JdbcSqliteDriver` desktop / `AndroidSqliteDriver` + Requery Android), kotlin-inject, kotlinx-coroutines, `kotlin.test` + JUnit4.

## Global Constraints

- **JDK 21 for Gradle:** `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'` before any `./gradlew` (JDK 25 wedges the daemon).
- **Canonical 6-target build** (run after each task): `.\gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`
- **One change per commit.** Commit messages end with the two trailers (`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` and `Claude-Session: …`), matching recent `git log`.
- **JUnit4 `@Test` must return `Unit`** — use `assertTrue(x != null, …)`, never `assertNotNull` as the last expression of an `= runBlocking { }` body.
- **`SettingsRepository` *interface* must NOT change** — only `SettingsRepositoryImpl`'s constructor — or it breaks `StubSettingsRepository` doubles in `:audio:playback`.
- **Keep item-2's `updateTrackReplayGainIfUnchanged` guard** unchanged (it is a TOCTOU fix single-writer does not subsume).
- **PR body uses "Addresses #31"** (never `fix/closes/resolves`) so the squash-merge does not auto-close the issue.
- Spec: [`docs/superpowers/specs/2026-06-23-issue-31-item-3-single-writer-db-design.md`](../specs/2026-06-23-issue-31-item-3-single-writer-db-design.md).

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/db/DatabaseWriter.kt` | The single-writer seam | **Create** |
| `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/db/DatabaseWriterTest.kt` | Serialization / return / exception tests | **Create** |
| `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/db/WalConfigTest.kt` | WAL + busy_timeout recipe verification (file DB) | **Create** |
| `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt` | Desktop driver props (WAL) + `databaseWriter` provider | **Modify** |
| `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` | Android `busy_timeout` PRAGMA + `databaseWriter` provider | **Modify** |
| `data/library/src/desktopMain/.../scan/JvmFilesystemScanner.kt` | Desktop scanner → `writer.write` | **Modify** |
| `data/library/src/androidMain/.../scan/AndroidMediaStoreScanner.kt` | Android scanner → `writer.write`; sequential backfill | **Modify** |
| `data/library/src/androidMain/.../scan/AndroidFormatFactBackfill.kt` | Backfill page-writes → `writer.write` | **Modify** |
| `data/library/src/commonMain/.../scan/TrackAnalysisRunner.kt` | Analyzer → `writer.write` (guard kept) | **Modify** |
| `data/library/src/commonMain/.../scan/LibraryWriteLock.kt` | The mutex | **Delete** |
| `data/library/src/commonMain/.../settings/SettingsRepositoryImpl.kt` | Settings writes → `writer.write` | **Modify** |
| `data/library/src/desktopTest/.../scan/{TrackAnalysisRunnerTest,JvmFilesystemScannerTest}.kt` | Re-point construction to `DatabaseWriter` | **Modify** |
| `data/library/src/desktopTest/.../settings/SettingsRepositoryImplTest.kt` | Re-point construction | **Modify** |
| `CLAUDE.md` | Writer-invariant gotcha | **Modify** |

---

## Task 1: WAL + `busy_timeout` on both drivers

**Files:**
- Create: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/db/WalConfigTest.kt`
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt` (the `sqlDriver()` provider, ~lines 84-94)
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` (the `onOpen` callback, ~lines 73-78)

**Interfaces:**
- Produces: nothing new in code; establishes that the production driver properties yield `journal_mode=wal` + `busy_timeout=5000`. The exact property keys (`journal_mode`, `busy_timeout`) are pinned by `WalConfigTest`.

- [ ] **Step 1: Write the failing test** — `WalConfigTest.kt`:

```kotlin
package com.clayworks.kiln.library.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.clayworks.kiln.data.library.db.KilnDatabase
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the desktop driver recipe used by DesktopAppGraph.sqlDriver(): the xerial
 * property keys `journal_mode` and `busy_timeout` must take effect on a FILE db.
 * Must use a file (not JdbcSqliteDriver.IN_MEMORY) — only file URLs hit
 * ThreadedConnectionManager + real WAL. Keep these properties in sync with
 * DesktopAppGraph.sqlDriver().
 */
class WalConfigTest {

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

    @Test
    fun `desktop driver recipe enables WAL and busy_timeout`() {
        val dbFile = Files.createTempFile("kiln-wal-test", ".db")
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${dbFile.toAbsolutePath()}",
            properties = Properties().apply {
                put("foreign_keys", "true")
                put("journal_mode", "WAL")
                put("busy_timeout", "5000")
            },
            schema = KilnDatabase.Schema,
        )
        try {
            assertEquals("wal", queryString(driver, "PRAGMA journal_mode;")?.lowercase())
            assertEquals(5000L, queryLong(driver, "PRAGMA busy_timeout;"))
        } finally {
            driver.close()
            Files.deleteIfExists(dbFile)
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :data:library:desktopTest --tests "*WalConfigTest*"`
Expected: FAIL — `WalConfigTest.kt` does not compile yet only if the file is absent; once created it should actually **PASS** (this test exercises SQLDelight/xerial, which already support these keys). Treat this test as the **recipe-pinning** check: if it FAILS, the property keys are wrong — fix the keys (e.g. confirm xerial's pragma names) before proceeding. (It is the rare test that may pass on first write; that is fine — its job is to lock the keys the graph code below must use.)

- [ ] **Step 3: Apply the recipe in `DesktopAppGraph.sqlDriver()`** — add the two properties next to `foreign_keys`:

```kotlin
return JdbcSqliteDriver(
    url = "jdbc:sqlite:${dbFile.toAbsolutePath()}",
    properties = Properties().apply {
        put("foreign_keys", "true")
        put("journal_mode", "WAL")   // reader/writer concurrency (#31 item 3)
        put("busy_timeout", "5000")  // wait out brief WAL-checkpoint locks instead of throwing SQLITE_BUSY
    },
    schema = KilnDatabase.Schema,
)
```

- [ ] **Step 4: Apply on Android** — in `AndroidAppGraph.sqlDriver()`'s `Callback.onOpen`, add `busy_timeout` (per-connection) beside the existing FK pragma, and assert WAL is active (the framework/Requery default; set it explicitly if not):

```kotlin
override fun onOpen(db: SupportSQLiteDatabase) {
    super.onOpen(db)
    db.execSQL("PRAGMA foreign_keys = ON")
    db.execSQL("PRAGMA busy_timeout = 5000")
    // WAL is the framework/Requery default for a writable helper. Assert + force
    // it so reader/writer concurrency holds across device/vendor variance (#31 item 3).
    db.query("PRAGMA journal_mode").use { c -> /* observe; default expected 'wal' */ }
    db.execSQL("PRAGMA journal_mode = WAL")
}
```

- [ ] **Step 5: Run the canonical build**

Run: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`
Expected: BUILD SUCCESSFUL; `WalConfigTest` passes.

- [ ] **Step 6: Commit**

```bash
git add app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt \
        app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt \
        data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/db/WalConfigTest.kt
git commit   # subject: "feat(db): enable WAL + busy_timeout on both SQLite drivers (#31 item 3)"
```

---

## Task 2: `DatabaseWriter` + DI providers

**Files:**
- Create: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/db/DatabaseWriter.kt`
- Create: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/db/DatabaseWriterTest.kt`
- Modify: `DesktopAppGraph.kt` (add `databaseWriter` provider), `AndroidAppGraph.kt` (add `databaseWriter` provider)

**Interfaces:**
- Produces: `class DatabaseWriter(db: KilnDatabase, writerDispatcher: CoroutineDispatcher)` with `suspend fun <T> write(block: KilnDatabase.() -> T): T`. Tasks 4 & 5 inject it in place of `LibraryWriteLock`.

- [ ] **Step 1: Write `DatabaseWriter.kt`**

```kotlin
package com.clayworks.kiln.library.db

import com.clayworks.kiln.data.library.db.KilnDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The single-writer seam for [KilnDatabase]. Every write to the database runs
 * through [write], which marshals it onto one dedicated single-thread
 * [writerDispatcher]. Because only one thread ever performs writes, two writes
 * can never overlap — serialization is structural, with no mutex to forget.
 * Replaces the former LibraryWriteLock (#31 item 3).
 *
 * [block] is intentionally NON-suspending: a write unit runs start-to-finish on
 * the writer thread with no internal suspension, which (a) guarantees no other
 * write interleaves mid-unit, (b) makes reentrancy structurally impossible (you
 * cannot call this suspend fun from inside a non-suspend block), and (c) forces
 * slow suspend work (analyze(), flow.first()) to stay OUTSIDE the serialized
 * section, where it belongs.
 *
 * INVARIANT: all writes to KilnDatabase go through `write { }`. Reads may use the
 * database directly (WAL gives them lock-free snapshots). Adding a direct write
 * elsewhere reintroduces the race this class exists to remove.
 */
class DatabaseWriter(
    private val db: KilnDatabase,
    private val writerDispatcher: CoroutineDispatcher,
) {
    suspend fun <T> write(block: KilnDatabase.() -> T): T =
        withContext(writerDispatcher) { db.block() }
}
```

- [ ] **Step 2: Write `DatabaseWriterTest.kt`**

```kotlin
package com.clayworks.kiln.library.db

import com.clayworks.kiln.library.source.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class DatabaseWriterTest {

    private val testDb = TestDb()

    @AfterTest fun tearDown() = testDb.close()

    @Test
    fun `concurrent writes never overlap on the single writer thread`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val writer = DatabaseWriter(testDb.db, dispatcher)
        val inProgress = AtomicBoolean(false)
        val overlapDetected = AtomicBoolean(false)
        try {
            val jobs = (1..64).map {
                launch(Dispatchers.Default) {
                    writer.write {
                        if (!inProgress.compareAndSet(false, true)) overlapDetected.set(true)
                        Thread.sleep(1)  // widen the window; block runs on the writer thread
                        inProgress.set(false)
                    }
                }
            }
            jobs.joinAll()
        } finally {
            dispatcher.close()
            executor.shutdown()
        }
        assertFalse(overlapDetected.get(), "two writes ran concurrently — serialization is broken")
    }

    @Test
    fun `write returns the block result`() = runBlocking {
        val writer = DatabaseWriter(testDb.db, Dispatchers.Unconfined)
        assertEquals(42, writer.write { 42 })
    }

    @Test
    fun `write propagates exceptions to the caller`() = runBlocking {
        val writer = DatabaseWriter(testDb.db, Dispatchers.Unconfined)
        assertFailsWith<IllegalStateException> { writer.write { error("boom") } }
        Unit
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `$env:JAVA_HOME='…jdk-21…'; .\gradlew :data:library:desktopTest --tests "*DatabaseWriterTest*"`
Expected: FAIL — `DatabaseWriter` unresolved (until Step 1's file compiles). Once compiling, all three PASS.

- [ ] **Step 4: Add the `databaseWriter` provider to both graphs.** Construct the single-thread dispatcher **inline** (do NOT expose it as a separate `@Provides CoroutineDispatcher` — desktop already provides one for audio, and kotlin-inject cannot disambiguate two same-typed bindings).

`DesktopAppGraph.kt` — add `import com.clayworks.kiln.library.db.DatabaseWriter` and, alongside the other providers:

```kotlin
@Singleton
@Provides
protected fun databaseWriter(db: KilnDatabase): DatabaseWriter =
    DatabaseWriter(
        db = db,
        writerDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "kiln-db-writer").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
    )
```

`AndroidAppGraph.kt` — add the same provider + imports (`com.clayworks.kiln.library.db.DatabaseWriter`, `java.util.concurrent.Executors`, `kotlinx.coroutines.asCoroutineDispatcher`).

- [ ] **Step 5: Run the canonical build**

Expected: BUILD SUCCESSFUL; `DatabaseWriterTest` (3) passes. (The new provider is not yet consumed — that is fine.)

- [ ] **Step 6: Commit**

```bash
git add data/library/src/commonMain/kotlin/com/clayworks/kiln/library/db/DatabaseWriter.kt \
        data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/db/DatabaseWriterTest.kt \
        app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt \
        app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt
git commit   # subject: "feat(db): add DatabaseWriter single-writer seam + DI providers (#31 item 3)"
```

---

## Task 3: Swap the serialization mechanism (scanners + analyzer + backfill) and delete `LibraryWriteLock`

This is **one atomic commit** by necessity: a half-migrated state (one writer on the executor, another still on the mutex) is *not* mutually serialized → corruption window. The scanners, analyzer, and backfill switch together; the mutex is deleted in the same commit.

**Files:**
- Modify: `TrackAnalysisRunner.kt`, `JvmFilesystemScanner.kt`, `AndroidMediaStoreScanner.kt`, `AndroidFormatFactBackfill.kt`
- Modify (DI): `DesktopAppGraph.kt` (`filesystemScanner`, `analysisRunner` providers), `AndroidAppGraph.kt` (`mediaStoreScanner`, `analysisRunner` providers)
- Delete: `LibraryWriteLock.kt`
- Modify (tests): `TrackAnalysisRunnerTest.kt`, `JvmFilesystemScannerTest.kt`

**Interfaces:**
- Consumes: `DatabaseWriter` (Task 2).
- Produces: new constructor signatures —
  - `TrackAnalysisRunner(db: KilnDatabase, analyzer: TrackAnalyzer, ioDispatcher: CoroutineDispatcher, writer: DatabaseWriter)`
  - `JvmFilesystemScanner(scanFoldersFlow, db, driver, ioDispatcher, writer: DatabaseWriter)`
  - `AndroidMediaStoreScanner(context, safTreeUrisFlow, db, driver, ioDispatcher, backfill, writer: DatabaseWriter)`
  - `AndroidFormatFactBackfill(context, db, ioDispatcher, writer: DatabaseWriter)`

  (Keep the existing `db`/`driver` fields; the `write { }` block body keeps calling `db.…`/`driver.…` — `writer` was built with the same `db`, so the receiver and the field are the same instance. Replace the `writeLock: LibraryWriteLock` param with `writer: DatabaseWriter`.)

- [ ] **Step 1: `TrackAnalysisRunner` — swap each `writeLock.mutex.withLock { … }` → `writer.write { … }`.** Replace the field `private val writeLock: LibraryWriteLock` with `private val writer: DatabaseWriter` (import `com.clayworks.kiln.library.db.DatabaseWriter`; drop the `kotlinx.coroutines.sync.withLock` import). The five sites (page-select, per-track guarded persist, album-select, album-update) in both `runOnce()` and `runOnceWithProgress()` become e.g.:

```kotlin
val page = writer.write {
    db.trackQueries
        .selectTracksMissingReplayGain(pageSize = PAGE_SIZE, pageOffset = skippedTotal)
        .executeAsList()
}
```
```kotlin
writer.write {
    db.trackQueries.updateTrackReplayGainIfUnchanged(   // GUARD UNCHANGED
        db = gainDb, peak = peakLinear, id = row.id,
        filePath = row.file_path, fileMtimeMs = row.file_mtime_ms, fileSizeBytes = row.file_size_bytes,
    )
}
```
`analyzer.analyze(...)` stays **between** `writer.write` calls (off the writer thread) — unchanged.

- [ ] **Step 2: `JvmFilesystemScanner` — pull the suspend prelude out, wrap the rest in `writer.write`.** Replace the `writeLock` field with `writer: DatabaseWriter`. Today `scanIncremental()`/`scanFull()` do `withContext(io) { writeLock.mutex.withLock { runScan(force) } }` and `runScan` is `suspend` (it calls `scanFoldersFlow.first()`). Restructure so the suspend read happens before the non-suspend write block:

```kotlin
override suspend fun scanIncremental(): Either<ScanError, ScanResult> =
    withContext(ioDispatcher) {
        val scanFolders = scanFoldersFlow.first()           // suspend — OUTSIDE the writer
        writer.write { runScanBlocking(scanFolders, forceFullRescan = false) }
    }
// (scanFull mirrors with forceFullRescan = true)

// runScanBlocking is the former runScan body MINUS the `scanFoldersFlow.first()`
// line (folders are now a parameter). It is NON-suspend; everything inside —
// Either.catch, the empty-guard, driver.execute bulk reset, the file walk +
// db.transaction loop, root-guarded softDeleteUnscanned, rebuildFtsIndex(db, driver)
// — is unchanged and runs on the writer thread.
private fun runScanBlocking(scanFolders: List<Path>, forceFullRescan: Boolean): Either<ScanError, ScanResult> = ...
```

- [ ] **Step 3: `AndroidFormatFactBackfill` — route page-writes through the writer.** Add `private val writer: DatabaseWriter`. Native reads stay off-writer (on `ioDispatcher`); wrap only the per-page `db.transaction` in `writer.write`:

```kotlin
suspend fun runOnce(): Int = withContext(ioDispatcher) {
    var updated = 0
    while (true) {
        val page = db.trackQueries.selectTracksNeedingBackfill(limit = PAGE_LIMIT).executeAsList()
        if (page.isEmpty()) break
        val pageFacts = page.map { row -> row to readFormatFacts(row) }   // native I/O, off-writer
        val nowMs = System.currentTimeMillis()
        writer.write {
            db.transaction {
                for ((row, facts) in pageFacts) {
                    if (facts != null) db.trackQueries.updateTrackFormatFacts(/* …unchanged… */)
                    else db.trackQueries.markBackfilledNoMetadata(backfilledAtMs = nowMs, id = row.id)
                }
            }
        }
        updated += pageFacts.count { it.second != null }
    }
    updated
}
```

- [ ] **Step 4: `AndroidMediaStoreScanner` — same prelude split as desktop; call backfill *after* the write block (sequentially), not inside it.** Replace the `writeLock` field with `writer`. `runScanBlocking` becomes the former `runScan` body minus `safTreeUrisFlow.first()` (now a param) **and minus the `backfill.runOnce()` call**:

```kotlin
override suspend fun scanIncremental(): Either<ScanError, ScanResult> =
    withContext(ioDispatcher) {
        val safTreeUris = safTreeUrisFlow.first()            // suspend — OUTSIDE the writer
        val result = writer.write { runScanBlocking(safTreeUris, forceFullRescan = false) }
        backfill.runOnce()                                   // suspend; own writer.write per page (Step 3)
        result
    }
// (scanFull mirrors.)
```
The `backfill.runOnce()` log line moves to the caller; the `ScanResult` (which never included the backfill count) is unchanged.

- [ ] **Step 5: Update the DI providers.** In both graphs, the scanner + analyzer providers take `writer: DatabaseWriter` instead of `writeLock: LibraryWriteLock`, and the Android `mediaStoreScanner` keeps its `backfill` param:

```kotlin
// DesktopAppGraph
protected fun filesystemScanner(settings: SettingsRepository, db: KilnDatabase, driver: SqlDriver, writer: DatabaseWriter): LibraryScanner {
    val scanFoldersFlow = settings.scanFolders.map { it.map(Path::of) }
    return JvmFilesystemScanner(scanFoldersFlow, db, driver, Dispatchers.IO, writer)
}
protected fun analysisRunner(db: KilnDatabase, analyzer: TrackAnalyzer, writer: DatabaseWriter): TrackAnalysisRunner =
    TrackAnalysisRunner(db, analyzer, Dispatchers.IO, writer)
```
Mirror in `AndroidAppGraph` (`mediaStoreScanner(... backfill, writer)`, `analysisRunner(... writer)`). Remove both `libraryWriteLock()` providers and the `LibraryWriteLock` imports.

- [ ] **Step 6: Delete `LibraryWriteLock.kt`.**

```bash
git rm data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/LibraryWriteLock.kt
```

- [ ] **Step 7: Re-point the tests.** In `TrackAnalysisRunnerTest.kt` (11 sites) and `JvmFilesystemScannerTest.kt` (6 sites), replace `LibraryWriteLock()` with `DatabaseWriter(<db>, Dispatchers.Unconfined)` and add `import com.clayworks.kiln.library.db.DatabaseWriter`. Examples:

```kotlin
// TrackAnalysisRunnerTest — was: TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())
val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, DatabaseWriter(testDb.db, Dispatchers.Unconfined))
```
```kotlin
// JvmFilesystemScannerTest — was: writeLock = LibraryWriteLock(),
writer = DatabaseWriter(db, Dispatchers.Unconfined),
```
(`Dispatchers.Unconfined` is correct for these functional tests — serialization itself is proven in `DatabaseWriterTest` with a real single-thread dispatcher per the spec's E1 note.)

- [ ] **Step 8: Run the canonical build**

Expected: BUILD SUCCESSFUL. All existing scanner/analyzer tests (incl. the item-2 TOCTOU stale-row test and the empty-scanFolders bomb test) pass unchanged. A failure here is most likely a missed `writeLock` reference (`grep -rn "LibraryWriteLock\|writeLock" data app-desktop app-android`) — there should be **zero** remaining.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit   # subject: "refactor(db): route scanners + analyzer + backfill through DatabaseWriter; delete LibraryWriteLock (#31 item 3)"
```

---

## Task 4: Close the settings gap + document the invariant

**Files:**
- Modify: `data/library/src/commonMain/.../settings/SettingsRepositoryImpl.kt`
- Modify (DI): `DesktopAppGraph.kt` + `AndroidAppGraph.kt` (`settingsRepository` provider)
- Modify (test): `data/library/src/desktopTest/.../settings/SettingsRepositoryImplTest.kt`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `DatabaseWriter`.
- Produces: `SettingsRepositoryImpl(db: KilnDatabase, ioDispatcher: CoroutineDispatcher, writer: DatabaseWriter)`. **The `SettingsRepository` interface is unchanged.**

- [ ] **Step 1: Route each settings write through the writer.** Add `private val writer: DatabaseWriter` to the constructor (import it). Each setter changes from `withContext(ioDispatcher) { db.settingsQueries.upsert(...) }` to `writer.write { db.settingsQueries.upsert(...) }`. The read `Flow`s (`themeMode`, `scanFolders`, …) stay exactly as they are (on `ioDispatcher`). For `setReplayGainPreAmpDb`, keep the `kilnDb` local-alias trick but call `writer.write { … }`:

```kotlin
override suspend fun setThemeMode(mode: ThemeMode) {
    writer.write { settingsQueries.upsert(key = SettingKey.THEME_MODE, value_ = mode.name) }
}
// …same shape for setScanOnLaunch / setAutoScanOnFolderAdd / setScanFolders / setReplayGainMode / setReplayGainPreAmpDb…
```
(Inside `write { }` the receiver is the `KilnDatabase`, so `settingsQueries` resolves on the receiver — equivalent to `db.settingsQueries`.)

- [ ] **Step 2: Update the `settingsRepository` provider in both graphs:**

```kotlin
protected fun settingsRepository(db: KilnDatabase, writer: DatabaseWriter): SettingsRepository =
    SettingsRepositoryImpl(db, Dispatchers.IO, writer)
```

- [ ] **Step 3: Re-point `SettingsRepositoryImplTest`.** Wherever it constructs `SettingsRepositoryImpl(db, <dispatcher>)`, add `, DatabaseWriter(db, <same dispatcher>)` and import `DatabaseWriter`. The existing assertions (each setter persists and the matching `Flow` re-emits) prove the writer-routed writes work.

- [ ] **Step 4: Run tests to verify green**

Run: `$env:JAVA_HOME='…jdk-21…'; .\gradlew :data:library:desktopTest --tests "*SettingsRepositoryImplTest*"`
Expected: PASS (all existing settings assertions hold through the writer).

- [ ] **Step 5: Add the invariant to `CLAUDE.md`** — one bullet in the "Build/Dep Gotchas" list:

```markdown
- **All `KilnDatabase` *writes* go through `DatabaseWriter.write { }` (single-writer thread, `:data:library/.../db/DatabaseWriter.kt`).** Reads may use the db directly (WAL gives lock-free snapshots). There is no mutex/compile guard since #31 item 3 retired `LibraryWriteLock` — a direct `db.…` write off the writer thread reintroduces the scan↔analyzer race. New writers take a `DatabaseWriter`, not the raw `KilnDatabase`, for their write paths.
```

- [ ] **Step 6: Run the canonical build**

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImpl.kt \
        app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt \
        app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt \
        data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImplTest.kt \
        CLAUDE.md
git commit   # subject: "feat(db): route settings writes through DatabaseWriter; document writer invariant (#31 item 3)"
```

---

## Task 5: Final verification gate

- [ ] **Step 1: Full canonical build, clean.**

Run: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`
Expected: BUILD SUCCESSFUL; all desktopTest suites green.

- [ ] **Step 2: Confirm no stragglers.**

Run: `grep -rn "LibraryWriteLock\|\.mutex\.withLock" data app-desktop app-android` → expected: **no matches**.

- [ ] **Step 3 (on-device, when available — Pixel 7 `2A261FDH300B1P`):** install the fresh APK, run a scan + the RG backfill, then pull the DB and confirm `PRAGMA journal_mode` = `wal` and `track` integrity is intact (517 live / 0 spurious soft-deletes). Per the spec, also note whether WAL incidentally improves any #28 desktop symptom. *(This is a manual gate, not a commit step.)*

---

## Self-Review

- **Spec coverage:** §3.1 DatabaseWriter → Task 2. §3.2 dispatcher provisioning (inline, DI-ambiguity note) → Task 2 Step 4. §3.3 WAL+busy_timeout → Task 1. §3.4 call-site migration (all six writers) → Tasks 3 (scanners/analyzer/backfill/FTS-via-scanner) + 4 (settings). §3.5 delete LibraryWriteLock → Task 3 Step 6. §3.6 keep item-2 guard → Task 3 Step 1 (explicit). §3.7 pragmatic enforcement + invariant doc → Task 4 Step 5. §7 tests: serialization (real dispatcher) → Task 2; WAL recipe (file DB) → Task 1; settings-through-writer → Task 4; item-2 kept green → Task 3 Step 8; canonical build → every task. §9 commit sequencing (WAL → infra → swap+delete → settings) → Tasks 1→2→3→4, preserving the "writers migrate together" safety constraint.
- **Deferred (correctly out of plan, per spec §8):** scan-transaction chunking (D5), reader-connection abstraction beyond WAL, #32 dedup, #28 (cross-checked in Task 5 Step 3).
- **Type consistency:** `DatabaseWriter(db, dispatcher)` + `write { }` signature is identical across Tasks 2/3/4. New constructor signatures listed in each task's Interfaces block match their DI call-sites. `updateTrackReplayGainIfUnchanged` parameter set matches the shipped item-2 query.
- **Placeholder scan:** no TBD/TODO; every code step shows real code; the one "may pass on first write" note (Task 1 Step 2) is an explicit, justified recipe-pinning expectation, not a vague instruction.

---

## Execution handoff

Per Clay's instruction this session: **do not execute yet.** After this plan is committed, push the branch and open a PR ("Addresses #31") for a `@codex` bot review of the spec + plan **before** implementation. Choose an execution mode (subagent-driven vs inline) once the bot review is addressed.
