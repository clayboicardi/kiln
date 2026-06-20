# Phase 2b-A — Spec Sheet UI + Android Format-Fact Backfill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a per-track Spec Sheet screen (audio format facts + ReplayGain + file facts) plus library aggregate stats, routable from the now-playing title tap, with accurate Android format facts guaranteed by a `MediaMetadataRetriever` backfill scanner pass.

**Architecture:** Read path — a new `LibraryStatsSource` interface (implemented by `LocalLibrarySource`) exposes `specSheetEntry(trackId): Flow<SpecSheetEntry?>` and `aggregateStats(): Flow<LibraryAggregate>`, both backed by SQLDelight `Query.asFlow()`. The `SpecSheetScreen` (already navigation-wired as a placeholder in Phase 2b-prereq) collects those flows and renders a stateless `SpecSheetContent`. Write path — `AndroidFormatFactBackfill` fills `sample_rate_hz`/`bit_depth`/`channels`/`bitrate_kbps`/`has_embedded_art` for rows where a new `metadata_backfilled_at_ms` column is NULL, hooked at scan-end (F10 mitigation). All data types live in `:data:library` commonMain so `:ui:components` consumes a `Flow` without an SQLDelight dependency.

**Tech Stack:** Kotlin 2.3.20 KMP, Compose Multiplatform, Voyager navigation, SQLDelight 2.3.2 (`coroutines-extensions`: `asFlow`/`mapToOne`/`mapToList`), kotlin-inject DI, Robolectric (androidHostTest), jetpack-compose-test, kotest-property.

## Global Constraints

- **minSdk = 23, compileSdk = 36.** (CLAUDE.md.)
- **Concentric Modules:** no `androidx.*` in `:audio:dsp`/`:audio:visualizer` commonMain. (Not touched here, but `:data:library` androidMain may use `android.media.*`.)
- **Source Protocol invariant:** no `if (source is XxxSource)` branching. Spec-sheet/aggregate methods go on a NEW narrow interface `LibraryStatsSource`, NOT on `MusicSource` — do not widen `MusicSource`.
- **SQLDelight migration naming:** migration file is named by SOURCE version. v3→v4 = `3.sqm`. (CLAUDE.md.)
- **`ALTER TABLE ADD COLUMN` always appends to END:** the new column MUST be declared LAST in `track.sq`'s `CREATE TABLE` (after `has_known_mtime`), or `verifyMigrations` fails with ordinalPosition diffs. (CLAUDE.md.)
- **`value`-style reserved-word renames:** N/A here (no `value` columns added).
- **One change per commit. Build + test after every change.** Canonical validation: `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest` (prefix with `JAVA_HOME` → Temurin 21 this session).
- **JUnit-4 `@Test` methods return Unit** — never `fun f() = runBlocking { assertNotNull(x) }`.
- **Don't modify `PlatformPlayer.kt`** (vetting Item 13).

## Package map (note the split)

- SQLDelight DB package: `com.clayworks.kiln.data.library.db` (generated `KilnDatabase`, `Track` row, `*Queries`).
- Kotlin source package: `com.clayworks.kiln.library.*` (source, scan, mappers).
- Domain/source-protocol types (`Playable`, `ReplayGain`, `MusicSource`): `com.clayworks.kiln.library.source` (`MediaItem.kt`).
- UI: `com.clayworks.kiln.ui.components.specsheet`.

## File structure

| Action | Path | Responsibility |
|---|---|---|
| Create | `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/migrations/3.sqm` | v3→v4: add `metadata_backfilled_at_ms` |
| Modify | `…/db/track.sq` | Add column (LAST); add `selectSpecSheetEntry`, `aggregateTotals`, `aggregateCodecCounts`, backfill queries |
| Create | `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/source/SpecSheetModels.kt` | `SpecSheetEntry`, `LibraryAggregate`, `LibraryStatsSource` |
| Modify | `…/source/LocalLibrarySource.kt` | Implement `LibraryStatsSource` |
| Modify | `…/source/LocalLibrarySourceMappers.kt` | `toSpecSheetEntry`, `toLibraryAggregate` mappers |
| Create | `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidFormatFactBackfill.kt` | `MediaMetadataRetriever` backfill pass |
| Modify | `…/scan/AndroidMediaStoreScanner.kt` | Call backfill at scan-end |
| Modify | `…/scan/internal/SafTagReader.kt` interaction — backfill is separate; scanner triggers it | (hook in scanner, not reader) |
| Modify | `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` | Provide `AndroidFormatFactBackfill`; thread into scanner |
| Create | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetState.kt` | `SpecSheetUiState` |
| Create | `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetContent.kt` | Stateless render + format helpers |
| Modify | `…/specsheet/SpecSheetScreen.kt` | Collect flows from `LibraryStatsSource`, render `SpecSheetContent` |
| Modify | `…/nowplaying/NowPlayingTab.kt` | Thread `LibraryStatsSource` → `NowPlayingHomeScreen` → `SpecSheetScreen` |
| Modify | app root where `NowPlayingTab(player)` is constructed (both apps) | Pass the new `statsSource` arg |

---

## Task A0: Schema migration — `metadata_backfilled_at_ms`

**Files:**
- Create: `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/migrations/3.sqm`
- Modify: `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/track.sq` (CREATE TABLE — append column LAST)

**Interfaces:**
- Produces: `track.metadata_backfilled_at_ms INTEGER` (nullable; NULL = not yet backfilled). Generated `Track.metadata_backfilled_at_ms: Long?`.

- [ ] **Step 1: Add the column LAST in `track.sq` CREATE TABLE** (immediately after `has_known_mtime`):

```sql
    has_known_mtime          INTEGER NOT NULL DEFAULT 1,

    -- NULL = Android format facts not yet verified via MediaMetadataRetriever
    -- backfill (F10). Set to System.currentTimeMillis() once AndroidFormatFactBackfill
    -- has populated sample_rate_hz/bit_depth/channels/bitrate_kbps/has_embedded_art.
    -- Desktop scans set this at insert (JVM tag-read already authoritative).
    -- Added v3→v4 via migrations/3.sqm; MUST stay last (ALTER ADD COLUMN appends).
    metadata_backfilled_at_ms INTEGER DEFAULT NULL
```

- [ ] **Step 2: Create `migrations/3.sqm`:**

```sql
-- v3 → v4: add metadata_backfilled_at_ms to track. NULL on existing rows =
-- "Android format facts unverified" → AndroidFormatFactBackfill will fill them
-- on the next scan and stamp this column. Desktop rows are stamped at insert.
-- File named 3.sqm (SOURCE version v3), per SQLDelight convention.
ALTER TABLE track ADD COLUMN metadata_backfilled_at_ms INTEGER DEFAULT NULL;
```

- [ ] **Step 3: Run migration verification (must regenerate the snapshot + pass):**

```
JAVA_HOME=<jdk21> ./gradlew :data:library:verifyCommonMainKilnDatabaseMigration :data:library:generateCommonMainKilnDatabaseSchema
```
Expected: BUILD SUCCESSFUL. A new `4.db` snapshot appears under `src/commonMain/sqldelight/databases/`. If it fails with `ordinalPosition CHANGED`, the column is not last — fix Step 1.

- [ ] **Step 4: Build `:data:library`:**

```
JAVA_HOME=<jdk21> ./gradlew :data:library:build
```
Expected: BUILD SUCCESSFUL; generated `Track` now has `metadata_backfilled_at_ms: Long?`.

- [ ] **Step 5: Commit**

```
git add data/library/src/commonMain/sqldelight
git commit -m "phase-2b-a(A0): schema v3→v4 — track.metadata_backfilled_at_ms"
```

---

## Task A1: Spec-sheet read model — types, queries, source methods, mappers

**Files:**
- Create: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/source/SpecSheetModels.kt`
- Modify: `…/db/track.sq` (add queries)
- Modify: `…/source/LocalLibrarySource.kt` (implement `LibraryStatsSource`)
- Modify: `…/source/LocalLibrarySourceMappers.kt` (add mappers)
- Test: `data/library/src/commonTest/kotlin/com/clayworks/kiln/library/source/SpecSheetMapperTest.kt`

**Interfaces:**
- Produces:
  - `data class SpecSheetEntry(trackId: String, title: String, codec: String, sampleRateHz: Int, bitDepth: Int?, channels: Int, bitrateKbps: Int?, durationMs: Long, replayGainTrackDb: Double?, replayGainAlbumDb: Double?, replayGainTrackPeak: Double?, replayGainAlbumPeak: Double?, hasEmbeddedArt: Boolean, filePath: String, fileSizeBytes: Long, fileMtimeMs: Long, hasKnownMtime: Boolean)`
  - `data class LibraryAggregate(totalTracks: Long, totalBytes: Long, codecCounts: Map<String, Long>, replayGainCoverage: Double, knownMtimeCoverage: Double)`
  - `interface LibraryStatsSource { fun specSheetEntry(trackId: String): Flow<SpecSheetEntry?>; fun aggregateStats(): Flow<LibraryAggregate> }`
- Consumes: SQLDelight `Track` row (from A0), `db.trackQueries`.

- [ ] **Step 1: Write the failing mapper test** (`SpecSheetMapperTest.kt`). Uses an in-memory DB inserting one track, then asserts the mapper. Mirror existing `:data:library` commonTest DB-fixture pattern (JdbcSqliteDriver in-memory). Minimal first assertion:

```kotlin
package com.clayworks.kiln.library.source

import com.clayworks.kiln.library.testdb.inMemoryKilnDatabase   // existing test helper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SpecSheetMapperTest {
    @Test
    fun specSheetEntry_maps_format_facts() = runTest {
        val db = inMemoryKilnDatabase()
        insertFlacFixture(db, id = 1, codec = "FLAC", sampleRate = 96000, bitDepth = 24, channels = 2)
        val source = LocalLibrarySource(db, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)

        val entry = source.specSheetEntry("1").first()

        assertNotNull(entry)
        assertEquals("FLAC", entry.codec)
        assertEquals(96000, entry.sampleRateHz)
        assertEquals(24, entry.bitDepth)
        assertEquals(2, entry.channels)
    }
}
```
> If no `inMemoryKilnDatabase`/`insertFlacFixture` helper exists in commonTest, the first sub-step is to add it (copy the driver-setup from an existing `:data:library` commonTest). Fold that into this task.

- [ ] **Step 2: Run the test — expect FAIL** ("Unresolved reference: specSheetEntry").

```
JAVA_HOME=<jdk21> ./gradlew :data:library:desktopTest --tests "*SpecSheetMapperTest*"
```

- [ ] **Step 3: Add queries to `track.sq`:**

```sql
selectSpecSheetEntry:
SELECT * FROM track WHERE id = ? AND deleted_at_ms IS NULL;

aggregateTotals:
SELECT
    COUNT(*)                                                         AS total_tracks,
    COALESCE(SUM(file_size_bytes), 0)                                AS total_bytes,
    SUM(CASE WHEN replay_gain_track_db IS NOT NULL THEN 1 ELSE 0 END) AS rg_covered,
    SUM(CASE WHEN has_known_mtime = 1        THEN 1 ELSE 0 END)        AS known_mtime
FROM track
WHERE deleted_at_ms IS NULL;

aggregateCodecCounts:
SELECT codec, COUNT(*) AS cnt
FROM track
WHERE deleted_at_ms IS NULL
GROUP BY codec
ORDER BY cnt DESC;
```

- [ ] **Step 4: Define types + interface in `SpecSheetModels.kt`** (exact shapes from the Interfaces block above). Put `SpecSheetEntry`, `LibraryAggregate`, and `interface LibraryStatsSource` here.

- [ ] **Step 5: Add mappers to `LocalLibrarySourceMappers.kt`** (mirror `Track.toPlayable` style — extension on the generated `Track` row):

```kotlin
internal fun Track.toSpecSheetEntry(): SpecSheetEntry = SpecSheetEntry(
    trackId = id.toString(),
    title = title,
    codec = codec,
    sampleRateHz = sample_rate_hz.toInt(),
    bitDepth = bit_depth?.toInt(),
    channels = channels.toInt(),
    bitrateKbps = bitrate_kbps?.toInt(),
    durationMs = duration_ms,
    replayGainTrackDb = replay_gain_track_db,
    replayGainAlbumDb = replay_gain_album_db,
    replayGainTrackPeak = replay_gain_track_peak,
    replayGainAlbumPeak = replay_gain_album_peak,
    hasEmbeddedArt = has_embedded_art != 0L,
    filePath = file_path,
    fileSizeBytes = file_size_bytes,
    fileMtimeMs = file_mtime_ms,
    hasKnownMtime = has_known_mtime != 0L,
)

// AggregateTotals is the SQLDelight-generated result type for aggregateTotals.
internal fun AggregateTotals.toLibraryAggregate(
    codecCounts: Map<String, Long>,
): LibraryAggregate {
    val total = total_tracks
    return LibraryAggregate(
        totalTracks = total,
        totalBytes = total_bytes,
        codecCounts = codecCounts,
        replayGainCoverage = if (total == 0L) 0.0 else (rg_covered ?: 0L).toDouble() / total,
        knownMtimeCoverage = if (total == 0L) 0.0 else (known_mtime ?: 0L).toDouble() / total,
    )
}
```

- [ ] **Step 6: Implement `LibraryStatsSource` on `LocalLibrarySource`** (add `, LibraryStatsSource` to the class header; add methods). Single-row uses `mapToOne`; combine the two aggregate flows:

```kotlin
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

override fun specSheetEntry(trackId: String): Flow<SpecSheetEntry?> {
    val id = trackId.toLongOrNull() ?: return kotlinx.coroutines.flow.flowOf(null)
    return db.trackQueries.selectSpecSheetEntry(id)
        .asFlow()
        .mapToOneOrNull(ioDispatcher)        // import app.cash.sqldelight.coroutines.mapToOneOrNull
        .map { it?.toSpecSheetEntry() }
}

override fun aggregateStats(): Flow<LibraryAggregate> {
    val totals = db.trackQueries.aggregateTotals().asFlow().mapToOne(ioDispatcher)
    val codecs = db.trackQueries.aggregateCodecCounts().asFlow().mapToList(ioDispatcher)
    return combine(totals, codecs) { t, rows ->
        t.toLibraryAggregate(rows.associate { it.codec to it.cnt })
    }
}
```

- [ ] **Step 7: Run the test — expect PASS.** Add a second test asserting `aggregateStats()` math (insert 2 tracks, 1 with RG → `replayGainCoverage == 0.5`).

```
JAVA_HOME=<jdk21> ./gradlew :data:library:desktopTest --tests "*SpecSheetMapperTest*"
```

- [ ] **Step 8: Commit**

```
git add data/library/src/commonMain data/library/src/commonTest
git commit -m "phase-2b-a(A1): SpecSheetEntry + LibraryAggregate read model + LibraryStatsSource"
```

---

## Task A2: Android format-fact backfill

**Files:**
- Create: `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidFormatFactBackfill.kt`
- Modify: `…/db/track.sq` (backfill select + update queries)
- Test: `data/library/src/androidHostTest/kotlin/com/clayworks/kiln/library/scan/AndroidFormatFactBackfillTest.kt`

**Interfaces:**
- Produces: `class AndroidFormatFactBackfill(context: Context, db: KilnDatabase, driver: SqlDriver, ioDispatcher: CoroutineDispatcher) { suspend fun runOnce(): Int /* rows updated */ }`
- Consumes: A0 column; `MediaMetadataRetriever` pattern from `SafTagReader` (try `pfd.use{}` / `finally release()`).

- [ ] **Step 1: Add queries to `track.sq`:**

```sql
selectTracksNeedingBackfill:
SELECT id, file_path FROM track
WHERE metadata_backfilled_at_ms IS NULL
  AND deleted_at_ms IS NULL
LIMIT :limit OFFSET :offset;

updateTrackFormatFacts:
UPDATE track
SET sample_rate_hz = :sampleRateHz,
    bit_depth      = :bitDepth,
    channels       = :channels,
    bitrate_kbps   = :bitrateKbps,
    has_embedded_art = :hasEmbeddedArt,
    metadata_backfilled_at_ms = :backfilledAtMs
WHERE id = :id;

markBackfilledNoMetadata:
UPDATE track SET metadata_backfilled_at_ms = :backfilledAtMs WHERE id = :id;
```

- [ ] **Step 2: Write the failing test** (`AndroidFormatFactBackfillTest.kt`, Robolectric `@Config(sdk=[34])`). Assert that a row with `metadata_backfilled_at_ms IS NULL` is no longer selected after `runOnce()` (use a non-existent file path → backfill marks it via `markBackfilledNoMetadata`, proving the worklist drains without infinite loop — the F-pattern from `TrackAnalysisRunner`). Reference: CLAUDE.md "worklist loop must advance offset by skipped-row count".

```kotlin
@Test
fun backfill_marks_rows_and_worklist_drains() = runBlocking {
    val db = robolectricInMemoryDb()
    insertTrack(db, id = 1, filePath = "/nonexistent.flac", metadataBackfilledAtMs = null)
    val backfill = AndroidFormatFactBackfill(ctx, db, driver, Dispatchers.Unconfined)
    backfill.runOnce()
    val remaining = db.trackQueries.countTracksNeedingBackfill().executeAsOne()
    assertEquals(0L, remaining)
    Unit
}
```
> Add `countTracksNeedingBackfill: SELECT COUNT(*) FROM track WHERE metadata_backfilled_at_ms IS NULL AND deleted_at_ms IS NULL;` to track.sq for the assertion + progress reporting.

- [ ] **Step 3: Run test — expect FAIL** (Unresolved reference).

```
JAVA_HOME=<jdk21> ./gradlew :data:library:testDebugUnitTest --tests "*AndroidFormatFactBackfillTest*"
```
(Android-host test task name; confirm via `./gradlew :data:library:tasks --all | grep -i hostTest` if it differs — the b0 probe used `:audio:playback:androidHostTest`.)

- [ ] **Step 4: Implement `AndroidFormatFactBackfill`.** Page through `selectTracksNeedingBackfill` (page size 200); per row, open via `MediaMetadataRetriever` (file path → `setDataSource(String)` for filesystem, or resolve `content://` via `ContentResolver.openFileDescriptor(...).use{}` when the path is a SAF URI — branch on `file_path.startsWith("content://")`). Extract `METADATA_KEY_SAMPLERATE`, `METADATA_KEY_BITS_PER_RAW_SAMPLE`, channel count, `METADATA_KEY_BITRATE`, `getEmbeddedPicture() != null`. Always `release()` in `finally`. On any failure → `markBackfilledNoMetadata` (don't retry forever). **Advance offset by skipped-row count** (CLAUDE.md infinite-loop gotcha); terminate when a short page is fully consumed.

- [ ] **Step 5: Run test — expect PASS.**

- [ ] **Step 6: Commit**

```
git add data/library/src/androidMain data/library/src/androidHostTest data/library/src/commonMain/sqldelight
git commit -m "phase-2b-a(A2): AndroidFormatFactBackfill — MediaMetadataRetriever format-fact pass"
```

---

## Task A3: Wire backfill into scan-end + DI

**Files:**
- Modify: `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt` (call backfill after `rebuildFtsIndex`, before `ScanResult` return — scanner gets `AndroidFormatFactBackfill` via constructor)
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` (provide `AndroidFormatFactBackfill`; pass into `mediaStoreScanner` provider; `abstract val formatBackfill`)
- Test: extend `AndroidMediaStoreScannerTest` (if present) or add a focused idempotency test

**Interfaces:**
- Consumes: A2 `AndroidFormatFactBackfill.runOnce()`. Existing `mediaStoreScanner` provider (AndroidAppGraph.kt:97-110).

- [ ] **Step 1: Add `backfill: AndroidFormatFactBackfill` to `AndroidMediaStoreScanner`'s constructor**, and call `backfill.runOnce()` after `rebuildFtsIndex(db, driver)` (AndroidMediaStoreScanner.kt:127), before building `ScanResult`. Idempotent by the `metadata_backfilled_at_ms IS NULL` filter — a second scan over already-backfilled rows is a near-zero-cost COUNT.
- [ ] **Step 2: Update the `mediaStoreScanner` `@Provides`** (AndroidAppGraph.kt:97) to build + pass `AndroidFormatFactBackfill`. Add a `@Singleton @Provides protected fun formatBackfill(context, db, driver): AndroidFormatFactBackfill` and `abstract val formatBackfill: AndroidFormatFactBackfill` (mirror analyzer pattern at AndroidAppGraph.kt:144-147).
- [ ] **Step 3: Build both Android targets:**

```
JAVA_HOME=<jdk21> ./gradlew :data:library:build :app-android:assembleDebug
```
- [ ] **Step 4: Commit**

```
git add data/library/src/androidMain app-android/src/main
git commit -m "phase-2b-a(A3): trigger format-fact backfill at scan-end + DI wiring"
```

---

## Task A4: Spec Sheet stateless UI + state + format helpers

**Files:**
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetState.kt`
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetContent.kt`
- Test: `ui/components/src/commonTest/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetFormatTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface SpecSheetUiState { object Loading; object NotFound; data class Loaded(val entry: SpecSheetEntry, val aggregate: LibraryAggregate?) }`
  - `@Composable fun SpecSheetContent(state: SpecSheetUiState, onBack: () -> Unit, modifier: Modifier = Modifier)`
  - `internal fun formatLine(entry: SpecSheetEntry): String` — e.g. `"FLAC — 24/96 — 2 ch — 1411 kbps"` (omit bit-depth segment when null → `"MP3 — 44.1 kHz — 2 ch — 320 kbps"`).
- Consumes: `SpecSheetEntry`, `LibraryAggregate` from `:data:library`.

- [ ] **Step 1: Write the failing format-helper test** (pure function, no Compose):

```kotlin
@Test
fun formatLine_hires_flac() {
    val e = specSheetEntry(codec = "FLAC", sampleRateHz = 96000, bitDepth = 24, channels = 2, bitrateKbps = 1411)
    assertEquals("FLAC — 24/96 — 2 ch — 1411 kbps", formatLine(e))
}
@Test
fun formatLine_lossy_omits_bitdepth() {
    val e = specSheetEntry(codec = "MP3", sampleRateHz = 44100, bitDepth = null, channels = 2, bitrateKbps = 320)
    assertEquals("MP3 — 44.1 kHz — 2 ch — 320 kbps", formatLine(e))
}
```
> `24/96` uses kHz with `.1` precision only when non-integer (96000→`96`, 44100→`44.1`). Use the `String.format` JVM-only note from CLAUDE.md (`:ui:components` is JVM+Android only, so `String.format` is fine).

- [ ] **Step 2: Run — expect FAIL.** `JAVA_HOME=<jdk21> ./gradlew :ui:components:desktopTest --tests "*SpecSheetFormatTest*"`
- [ ] **Step 3: Implement `SpecSheetState.kt` + `SpecSheetContent.kt`** + `formatLine`. Render: a top back `IconButton` (reuse the placeholder's `Icons.AutoMirrored.Filled.ArrowBack`), the `formatLine`, a ReplayGain row (`+0.3 dB track / +0.1 dB album`, "—" when null), file facts (path, size humanized, mtime, `has_known_mtime`-driven "mtime: unknown" when false), and an aggregate footer (total tracks, total bytes, per-codec chips, RG-coverage %). Aesthetic frame: **Mastering Engineer's Apartment** — labeled monospace-ish rows, calm spacing.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit**

```
git add ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetState.kt ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetContent.kt ui/components/src/commonTest
git commit -m "phase-2b-a(A4): SpecSheetContent stateless UI + format helpers"
```

---

## Task A5: SpecSheetScreen data wiring + thread the stats source through nav

> **⚠️ Voyager `Screen : Serializable` constraint (gemini-code-assist review, PR #26 — this SUPERSEDES the earlier "thread the source through Screen constructors" design).** Voyager's `Screen` extends `java.io.Serializable` on Android; the navigator saves its stack across process death / config change by serializing the Screen objects. A `Screen` that holds a non-serializable dependency (`LibraryStatsSource` → `KilnDatabase` + `CoroutineDispatcher`) throws `NotSerializableException`. So `SpecSheetScreen` must stay **serializable-only (`trackId: String`)** and obtain `LibraryStatsSource` from a Compose `CompositionLocal`. **The existing `NowPlayingHomeScreen(player: PlatformPlayer)` has the SAME latent bug** — it never crashed in `NowPlayingNavigationTest` because a Compose UI test doesn't trigger process-death serialization. Migrate it to a `LocalPlayer` CompositionLocal in this task.

**Files:**
- Create: `…/specsheet/LocalLibraryStats.kt` (+ a `LocalPlayer` CompositionLocal, here or in `nowplaying/`)
- Modify: `…/specsheet/SpecSheetScreen.kt` (hold only `trackId`; read `LocalLibraryStats.current`; collect flows; render `SpecSheetContent`)
- Modify: `…/nowplaying/NowPlayingTab.kt` + `NowPlayingHomeScreen` (read `LocalPlayer.current` instead of constructor `player`)
- Modify: app-root construction in BOTH apps (discover: `grep -rn "NowPlayingTab(" app-android app-desktop ui`)

**Interfaces:**
- Consumes: A1 `LibraryStatsSource`. `SpecSheetScreen` constructor STAYS **`SpecSheetScreen(trackId: String)`** (serializable-only).

- [ ] **Step 1: Define the CompositionLocals** (`…/specsheet/LocalLibraryStats.kt`):

```kotlin
val LocalLibraryStats = staticCompositionLocalOf<LibraryStatsSource> { error("LocalLibraryStats not provided") }
val LocalPlayer = staticCompositionLocalOf<PlatformPlayer> { error("LocalPlayer not provided") }
```

- [ ] **Step 2: Rewrite `SpecSheetScreen`** — hold only `trackId`, read the source from the CompositionLocal:

```kotlin
class SpecSheetScreen(
    private val trackId: String,   // serializable-only — NO dependency in the ctor (Voyager Screen : Serializable)
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val statsSource = LocalLibraryStats.current
        val entry by statsSource.specSheetEntry(trackId).collectAsState(initial = null)
        val aggregate by statsSource.aggregateStats().collectAsState(initial = null)
        val state = when {
            entry == null -> SpecSheetUiState.Loading   // distinguish NotFound after first emission if desired
            else -> SpecSheetUiState.Loaded(entry!!, aggregate)
        }
        SpecSheetContent(state = state, onBack = { navigator.pop() })
    }
}
```
Push it dependency-free: `navigator.push(SpecSheetScreen(trackId))`. `:ui:components` already deps `:data:library` and consumes a `Flow`, so **no `libs.bundles.sqldelight.common` add is needed**.

- [ ] **Step 3: Provide the CompositionLocals at the app root + migrate NowPlaying.** Where `NowPlayingTab(...)` is constructed (both apps), wrap the navigator content in `CompositionLocalProvider(LocalLibraryStats provides graph.libraryStats, LocalPlayer provides graph.player) { ... }`. Add `abstract val libraryStats: LibraryStatsSource` to both DI graphs (same `LocalLibrarySource` instance). Change `NowPlayingTab`/`NowPlayingHomeScreen` to read `LocalPlayer.current` instead of taking `player` in their constructors — so both Screens become serializable-safe. Update `NowPlayingNavigationTest` to provide the CompositionLocals.
- [ ] **Step 4: Build both apps:**

```
JAVA_HOME=<jdk21> ./gradlew :app-android:assembleDebug :app-desktop:assemble :ui:components:build
```
- [ ] **Step 5: Commit**

```
git add ui/components app-android app-desktop
git commit -m "phase-2b-a(A5): SpecSheetScreen reads real data; thread LibraryStatsSource through nav"
```

---

## Task A6: Compose navigation/render tests + manual smoke

**Files:**
- Create: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/specsheet/SpecSheetContentTest.kt` (jetpack-compose-test)

**Interfaces:** Consumes A4 `SpecSheetContent`, A1 types (build fakes).

- [ ] **Step 1: Write Compose render tests** — (a) `Loaded` golden path shows `formatLine` text; (b) `Loaded` with all-null RG shows "—" for ReplayGain; (c) `NotFound` shows an empty-state string. Use `createComposeRule()` + `onNodeWithText(...).assertExists()`. (Reference the Phase 2b-prereq `NowPlayingNavigationTest` for the compose-test harness setup.)
- [ ] **Step 2: Run — iterate to green.** `JAVA_HOME=<jdk21> ./gradlew :ui:components:desktopTest --tests "*SpecSheetContentTest*"`
- [ ] **Step 3: Canonical build:**

```
JAVA_HOME=<jdk21> ./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest
```
- [ ] **Step 4: Manual smoke (desktop):** `JAVA_HOME=<jdk21> ./gradlew :app-desktop:run`; play a track; tap the now-playing title; confirm the Spec Sheet shows real codec/rate/bit-depth/RG and an aggregate footer over Clay's 27k-track desktop DB; back-button pops.
- [ ] **Step 5: Manual smoke (Pixel 10, wireless adb):** reinstall debug APK; trigger a scan so the backfill runs; open Spec Sheet on a real track; confirm Android format facts are populated (not placeholders).
- [ ] **Step 6: Commit**

```
git add ui/components/src/desktopTest
git commit -m "phase-2b-a(A6): SpecSheet compose render tests"
```

---

## Self-review

**Spec coverage (top-level plan §6.A A0-A6):** A0 schema migration ✓ (Task A0). A1 backfill ✓ (Task A2). A2 scanner wiring ✓ (Task A3). A3 `aggregateStats()` ✓ (Task A1). A4 `SpecSheetState`+screen ✓ (Tasks A4/A5). A5 wire real data ✓ (Task A5). A6 compose tests ✓ (Task A6). F10 (no placeholder Android facts) ✓ (Tasks A2/A3). F17 (Now Playing routability) — already shipped in 2b-prereq; consumed here.

**Note on task renumbering:** this plan's Task A2 = top-level A1 (backfill); Task A3 = top-level A2 (scanner wiring); Task A1 = top-level A3 (aggregate) folded with the read model. Net coverage identical.

**Placeholder scan:** no "TBD"/"add error handling"-style placeholders; all queries, types, and mapper bodies are concrete. Two explicit discovery steps remain (commonTest in-memory-DB helper existence in A1-Step1; `NowPlayingTab(` construction sites in A5-Step3) — these are `grep`-resolvable, not design gaps.

**Type consistency:** `LibraryStatsSource.specSheetEntry`/`aggregateStats` signatures identical across A1 (def), A5 (consume). `SpecSheetEntry`/`LibraryAggregate` field names identical across A1, A4, A6. `AggregateTotals` is the generated type for `aggregateTotals` (column aliases `total_tracks`/`total_bytes`/`rg_covered`/`known_mtime` → generated property names; verify exact generated names at A1-Step5 and adjust the mapper if SQLDelight camelCases differently).

**Open design point for Clay (not a blocker):** SpecSheet visual layout + which facts are most prominent is specified to the "Mastering Engineer's Apartment" frame but not pixel-locked — worth a glance at the A4 result.

---

## Execution handoff

Plan saved to `docs/superpowers/plans/2026-06-19-phase-2b-a-spec-sheet.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task (A0→A6), two-stage review between tasks. Best fit for Bus-Factor-of-One discipline.
2. **Inline Execution** — execute tasks in this session with checkpoints between tasks.

A0 (schema migration) is the execution-ready first task and has no upstream dependency.
