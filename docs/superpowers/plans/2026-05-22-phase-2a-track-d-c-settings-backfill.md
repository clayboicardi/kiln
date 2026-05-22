# Phase 2a Track D-C: ReplayGain settings UI + backfill button

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface the Track D-A wrap-up's `TrackAnalysisRunner` to users via a SettingsScreen section. Add ReplayGain mode (Off/Track/Album) + pre-amp dB settings (no audible effect until Track D-B's consumer-side gain ships — settings are persisted ahead of time). Add an "Analyze missing tracks" backfill button with progress reporting. Address the two final-reviewer findings from PR #11 (I1 termination-guard test, I2 partial-pass album rollup).

**Architecture:**
- **Backend extensions in `:data:library`**: new `ReplayGainMode` enum + two new keys in `SettingKey` (REPLAY_GAIN_MODE, REPLAY_GAIN_PRE_AMP_DB) + matching `SettingsRepository` flow/setter pairs + parse helpers.
- **Runner extensions in `:data:library`**: (I1) add a test exercising >100-row termination guard; (I2) track touched album_ids in-memory during the per-track loop so albums always get re-aggregated when new tracks land (fixing the partial-pass finalization gap); add `AnalysisProgress` sealed interface + `runOnceWithProgress(): Flow<AnalysisProgress>` for UI progress reporting.
- **UI in `:ui:components`**: extend `SettingsState` with the three new fields (RG mode, pre-amp dB, backfill state); extend `SettingsScreen` with a "ReplayGain" section (radio group + slider + button + progress).
- **App route wiring in `app-desktop/Main.kt` and `app-android/MainActivity.kt`**: hoist the new SettingsRepository flows into Compose state; wire the backfill callback to launch `TrackAnalysisRunner.runOnceWithProgress()` and collect its emissions.

**Tech Stack:** Kotlin Multiplatform (commonMain + platform), Compose Multiplatform 1.11, kotlinx-coroutines Flow, SQLDelight 2.x (existing settings key/value table), Arrow Core, kotlin-inject DI, kotlin.test + kotest-property.

**Scope (this session):**
- ✅ Backend: `ReplayGainMode` enum + 2 SettingsRepository properties + parse helpers + 5 tests
- ✅ Runner: I1 termination-guard test + I2 touched-album tracking + 2 new tests
- ✅ Runner: `AnalysisProgress` + `runOnceWithProgress()` + 2 progress-flow tests
- ✅ UI: SettingsState + SettingsScreen "ReplayGain" section (mode radio + pre-amp slider + backfill button + progress indicator)
- ✅ App routes: DesktopSettingsRoute + AndroidSettingsRoute hoist the new state + wire backfill
- ✅ CLAUDE.md gotchas appended + Session 17 handoff + canonical verify-build + PR

**Out of scope (D-B's job):**
- ⛔ Applying the gain in the audio pipeline (Media3 AudioProcessor on Android; JavaSoundPlayerImpl multiplier on Desktop)
- ⛔ Peak limiting on positive gain
- ⛔ Resolving the "RG mode setting has no audible effect yet" UX (D-B closes this gap — the D-C UI ships a small footnote pointing at D-B)

**Branch:** `phase-2a-track-d-c-settings-backfill` (off wrap-up branch — stacks on PR #11).

---

## File Structure

| File | Role |
|---|---|
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt` | **Modify**: add `ReplayGainMode` enum + `replayGainMode`/`replayGainPreAmpDb` flows + setters. |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImpl.kt` | **Modify**: implement the new properties via existing `settings` kv table. |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/internal/Keys.kt` | **Modify**: add `REPLAY_GAIN_MODE`, `REPLAY_GAIN_PRE_AMP_DB` keys + `parseReplayGainMode`, `parsePreAmpDb` helpers. |
| `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImplTest.kt` | **Modify**: add 5 tests (round-trip mode, round-trip dB, default mode = Off, default dB = 0.0, clamped dB on read). |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt` | **Modify**: (I2) track `touchedAlbumIds: MutableSet<Long>` during per-track loop; rollup aggregates these regardless of `replay_gain_album_db` nullability. **Add**: `runOnceWithProgress(): Flow<AnalysisProgress>` returning intermediate + Complete emissions. |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/AnalysisProgress.kt` | **Create**: `sealed interface AnalysisProgress { Started, Progress, Complete, Failed }`. |
| `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt` | **Modify**: add (I1) `>100 all-skip tracks terminate without infinite loop` test + (I2) `partial-pass run 1 then run 2 finalizes album_db` test + 2 `runOnceWithProgress` tests. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt` | **Modify**: add `replayGainMode: ReplayGainMode`, `replayGainPreAmpDb: Double`, `replayGainBackfill: BackfillUiState` fields. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt` | **Modify**: add "ReplayGain" section between Library and existing sections — radio group + slider + button + progress text. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/BackfillUiState.kt` | **Create**: small data class wrapping the in-progress / idle / complete UI state for the backfill button. |
| `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt` | **Modify**: in `DesktopSettingsRoute`, collect the new SettingsRepository flows + manage `BackfillUiState` via `remember { mutableStateOf(...) }` + wire `onTriggerBackfill` to launch the runner's progress flow. |
| `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt` | **Modify**: mirror desktop's wiring in `AndroidSettingsRoute`. |
| `CLAUDE.md` | **Modify**: append D-C gotchas (Compose `Slider` value type, Flow collection in Compose, etc.). |
| `docs/sessions/2026-05-22-session-17-track-d-handoff.md` | **Create**: Session 17 handoff — D-B is the only remaining sub-track. |

---

## Reference: I1 + I2 fix logic

**I1 — Termination-guard test.** Currently `TrackAnalysisRunnerTest` does not cover the path where every row of a >PAGE_SIZE (100) batch is skipped AND there are no further rows. The guard `if (pageSkippedDelta == page.size && page.size < PAGE_SIZE.toInt()) break` exists in `runOnce()` but is unverified. The new test seeds 105 tracks, all with codec="MP3" so the FakeTrackAnalyzer returns `CodecUnsupported`, and asserts: (a) `runOnce()` returns within reasonable time (no infinite loop), (b) `tracksAnalyzed == 0`, `tracksSkipped == 105`, `albumsAggregated == 0`.

**I2 — Touched-album tracking.** Currently the rollup loop drives off `selectAlbumsForAggregation` which filters `replay_gain_album_db IS NULL`. This means if Run 1 analyzes 2 of 3 tracks of Album X (album_db gets set to a partial-3-tracks aggregate value) and Run 2 analyzes the 3rd track, `selectAlbumsForAggregation` returns 0 rows because Album X's album_db is non-null. Album X never gets the correct 3-track aggregate.

Fix: track album_ids in-memory during the per-track persist loop. After the loop, aggregate exactly those albums (regardless of current `replay_gain_album_db` value). `selectAlbumsForAggregation` is retained for a future "recover partial-pass" recovery path but is not the primary driver anymore.

Code shape:
```kotlin
val touchedAlbumIds = mutableSetOf<Long>()
// ... in the per-track loop, after a successful updateTrackReplayGain:
row.album_id?.let { touchedAlbumIds.add(it) }
// ... rollup loop:
for (albumId in touchedAlbumIds) {
    val perTrack = db.trackQueries.selectTrackReplayGainForAlbum(albumId).executeAsList()
    if (perTrack.isEmpty()) continue
    // ... (existing aggregation logic, unchanged)
}
```

---

## Task 1 — `ReplayGainMode` enum + settings keys + parse helpers + setting repo property

**Files:**
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/internal/Keys.kt`
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt`
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImpl.kt`
- Modify: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImplTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImplTest.kt`:

```kotlin
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
        // Out-of-range values clamp to the documented bounds (-12.0..+12.0).
        // We exercise this by inserting a raw out-of-range value via the repo
        // and verifying the read clamps it.
        repo.setReplayGainPreAmpDb(99.0)
        assertEquals(12.0, repo.replayGainPreAmpDb.first(), 1e-9)
        repo.setReplayGainPreAmpDb(-99.0)
        assertEquals(-12.0, repo.replayGainPreAmpDb.first(), 1e-9)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.settings.SettingsRepositoryImplTest"`
Expected: FAIL with "unresolved reference: replayGainMode" / "ReplayGainMode" / "replayGainPreAmpDb".

- [ ] **Step 3: Add `ReplayGainMode` enum + keys + parse helpers**

In `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt`, add at the top of the file (next to `ThemeMode`):

```kotlin
/**
 * ReplayGain consumer-side gain mode. Track applies the per-track gain;
 * Album applies the per-album rollup; Off bypasses RG entirely.
 *
 * The setting is persisted by Track D-C; consumer-side application lands
 * in Track D-B (Media3 AudioProcessor on Android, JavaSoundPlayerImpl
 * multiplier on Desktop). Until D-B ships, this setting has no audible
 * effect — the value is round-tripped for future use.
 */
enum class ReplayGainMode { Off, Track, Album }
```

Then add to the `SettingsRepository` interface (after `scanFolders`):

```kotlin
    /** ReplayGain mode; default Off. */
    val replayGainMode: Flow<ReplayGainMode>
    suspend fun setReplayGainMode(mode: ReplayGainMode)

    /**
     * Pre-amp dB applied on top of the ReplayGain value. Range: -12.0..+12.0.
     * Default 0.0. Reads clamp to range; writes accept any Double (downstream
     * clamping prevents future-key drift).
     */
    val replayGainPreAmpDb: Flow<Double>
    suspend fun setReplayGainPreAmpDb(db: Double)
```

In `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/internal/Keys.kt`, append to `SettingKey`:

```kotlin
    const val REPLAY_GAIN_MODE = "replay_gain_mode"
    const val REPLAY_GAIN_PRE_AMP_DB = "replay_gain_pre_amp_db"
```

And append the parse helpers (after `scanFoldersFromJson`):

```kotlin
internal const val PRE_AMP_DB_MIN = -12.0
internal const val PRE_AMP_DB_MAX = 12.0

/**
 * Decode ReplayGainMode from stored string; unknown / null → Off default.
 * Surviving a corrupt enum value is preferable to crashing on launch.
 */
internal fun parseReplayGainMode(stored: String?): ReplayGainMode = when (stored) {
    null -> ReplayGainMode.Off
    else -> try {
        ReplayGainMode.valueOf(stored)
    } catch (e: IllegalArgumentException) {
        log.w { "Unknown ReplayGainMode value '$stored'; falling back to Off" }
        ReplayGainMode.Off
    }
}

/**
 * Decode pre-amp dB from stored string, clamping to [PRE_AMP_DB_MIN, PRE_AMP_DB_MAX].
 * Unparseable / null → 0.0 default.
 */
internal fun parsePreAmpDb(stored: String?): Double {
    if (stored.isNullOrBlank()) return 0.0
    val raw = stored.toDoubleOrNull()
    if (raw == null) {
        log.w { "Unparseable pre-amp dB value '$stored'; falling back to 0.0" }
        return 0.0
    }
    return raw.coerceIn(PRE_AMP_DB_MIN, PRE_AMP_DB_MAX)
}
```

Update the existing import line at the top of `Keys.kt`:
```kotlin
import com.clayworks.kiln.library.settings.ReplayGainMode
```

- [ ] **Step 4: Implement the new repository properties**

In `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImpl.kt`, add imports:

```kotlin
import com.clayworks.kiln.library.settings.internal.parsePreAmpDb
import com.clayworks.kiln.library.settings.internal.parseReplayGainMode
```

Then add after the existing `scanFolders` property:

```kotlin
    override val replayGainMode: Flow<ReplayGainMode> =
        db.settingsQueries.selectByKey(SettingKey.REPLAY_GAIN_MODE)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> parseReplayGainMode(value) }

    override suspend fun setReplayGainMode(mode: ReplayGainMode): Unit = withContext(ioDispatcher) {
        db.settingsQueries.upsert(key = SettingKey.REPLAY_GAIN_MODE, value_ = mode.name)
    }

    override val replayGainPreAmpDb: Flow<Double> =
        db.settingsQueries.selectByKey(SettingKey.REPLAY_GAIN_PRE_AMP_DB)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> parsePreAmpDb(value) }

    override suspend fun setReplayGainPreAmpDb(db: Double): Unit = withContext(ioDispatcher) {
        // NB: name clash with the outer `db: KilnDatabase` field — we shadow it
        // here on purpose; access the database via `this.db` if needed.
        this.db.settingsQueries.upsert(
            key = SettingKey.REPLAY_GAIN_PRE_AMP_DB,
            value_ = db.toString(),
        )
    }
```

Note the parameter shadowing: `setReplayGainPreAmpDb(db: Double)` has the same name as the outer class field `private val db: KilnDatabase`. The `this.db.settingsQueries` form disambiguates.

Also add to imports at the top of `SettingsRepositoryImpl.kt`:
```kotlin
import com.clayworks.kiln.library.settings.internal.parsePreAmpDb
import com.clayworks.kiln.library.settings.internal.parseReplayGainMode
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.settings.SettingsRepositoryImplTest"`
Expected: PASS — all 5 new tests green + existing 6 tests still green = 11 of 11.

- [ ] **Step 6: Commit**

```bash
git add data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImpl.kt data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/internal/Keys.kt data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImplTest.kt
git commit -m "feat(data:library): ReplayGain settings keys (mode + pre-amp dB)

Adds ReplayGainMode enum (Off/Track/Album) and SettingsRepository properties:
  - replayGainMode: Flow<ReplayGainMode> + setReplayGainMode(mode)
  - replayGainPreAmpDb: Flow<Double> + setReplayGainPreAmpDb(db)

Reads clamp pre-amp to [-12, +12] dB; writes accept any Double (defensive
in case future setting keys drift). ReplayGainMode unknown values fall back
to Off + warning log.

No audible effect until Track D-B's consumer-side gain ships — D-C ships
the persisted settings UI ahead of the playback consumer.

Phase 2a Track D-C — Task 1."
```

---

## Task 2 — Runner I1 + I2 fixes (termination test + touched-album tracking)

**Files:**
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt`
- Modify: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt`

- [ ] **Step 1: Write the failing I1 + I2 tests**

Append to `TrackAnalysisRunnerTest.kt`:

```kotlin
    @Test
    fun `large library with all-skipping analyzer terminates without infinite loop`() = runBlocking {
        // I1 fix verification: 105 tracks (above PAGE_SIZE=100), all returning
        // Left, must terminate. Pre-fix, the runner would loop forever because
        // skipped rows stay in the worklist and naive offset-advance wouldn't
        // scroll past them across pages.
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val filePaths = (1..105).map { "/test/track-$it.mp3" }
        filePaths.forEach { path ->
            testDb.insertTrack(artistId, albumId, title = path.substringAfterLast('/'), filePath = path)
        }

        val analyzer = FakeTrackAnalyzer(
            results = filePaths.associateWith {
                Either.Left(TrackAnalysisError.CodecUnsupported("MP3"))
            },
        )
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined)

        // If the fix is missing, this call hangs and the test fails by timeout.
        // runBlocking has no per-test timeout but the test framework's default
        // timeout (10s) catches it.
        val result = runner.runOnce()
        assertEquals(0, result.tracksAnalyzed)
        assertEquals(105, result.tracksSkipped)
        assertEquals(0, result.albumsAggregated)
    }

    @Test
    fun `partial-pass run 1 then run 2 finalizes album_db with all tracks`() = runBlocking {
        // I2 fix verification: an album where run 1 analyzed 2 of 3 tracks leaves
        // album_db set to a partial aggregate. Run 2 (when the 3rd track gets
        // analyzed) must re-aggregate the album with all 3 tracks, not skip it
        // because album_db is non-null.
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val t1 = testDb.insertTrack(artistId, albumId, "T1", filePath = "/test/t1.flac")
        val t2 = testDb.insertTrack(artistId, albumId, "T2", filePath = "/test/t2.flac")
        val t3 = testDb.insertTrack(artistId, albumId, "T3", filePath = "/test/t3.flac")

        // Run 1: only t1 and t2 are analyzable.
        val analyzer1 = FakeTrackAnalyzer(mapOf(
            "/test/t1.flac" to Either.Right(TrackLoudness(-23.0, -1.0)),
            "/test/t2.flac" to Either.Right(TrackLoudness(-18.0,  0.0)),
        ))
        val runner1 = TrackAnalysisRunner(testDb.db, analyzer1, Dispatchers.Unconfined)
        val r1 = runner1.runOnce()
        assertEquals(2, r1.tracksAnalyzed)
        assertEquals(1, r1.tracksSkipped)
        assertEquals(1, r1.albumsAggregated)

        // After run 1, album_db reflects 2-track aggregate; capture it for comparison.
        val albumDbAfterRun1 = testDb.db.trackQueries.selectById(t1).executeAsOne()
            .replay_gain_album_db
        assertNotNull(albumDbAfterRun1)

        // Run 2: t3 becomes analyzable. Touched-album tracking should re-aggregate
        // the album with all 3 tracks regardless of album_db nullability.
        val analyzer2 = FakeTrackAnalyzer(mapOf(
            "/test/t3.flac" to Either.Right(TrackLoudness(-28.0, -3.0)),
        ))
        val runner2 = TrackAnalysisRunner(testDb.db, analyzer2, Dispatchers.Unconfined)
        val r2 = runner2.runOnce()
        assertEquals(1, r2.tracksAnalyzed)
        assertEquals(0, r2.tracksSkipped)
        assertEquals(1, r2.albumsAggregated)

        // The 3-track aggregate must differ from the 2-track aggregate
        // (different per-track LUFS values yield a different energy-weighted mean).
        val albumDbAfterRun2 = testDb.db.trackQueries.selectById(t1).executeAsOne()
            .replay_gain_album_db
        assertNotNull(albumDbAfterRun2)
        assertTrue(
            abs(albumDbAfterRun1!! - albumDbAfterRun2!!) > 0.1,
            "album_db should differ between 2-track ($albumDbAfterRun1) and 3-track " +
                "($albumDbAfterRun2) aggregates by at least 0.1 dB",
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.TrackAnalysisRunnerTest"`
Expected: PASS for the I1 test (the existing guard already works) but FAIL for the I2 test (the partial-pass scenario reveals the bug — album_db won't change between runs).

Actually wait — if I1 passes already, that's good (the guard works). The I1 test just gives it coverage. The I2 test is what reveals the bug.

If I1 fails with a hang/timeout, that means the guard is broken in a way the wrap-up plan's review missed — escalate as BLOCKED.

- [ ] **Step 3: Apply the I2 fix**

In `TrackAnalysisRunner.kt`, modify the `runOnce()` method's body:

Find the existing block that walks the worklist and replace it with the version that tracks `touchedAlbumIds`. The full updated method:

```kotlin
    suspend fun runOnce(): AnalysisPassResult = withContext(ioDispatcher) {
        val startedMs = System.currentTimeMillis()
        var analyzed = 0
        var skipped = 0
        var skippedTotal = 0L
        val touchedAlbumIds = mutableSetOf<Long>()

        while (true) {
            val page = db.trackQueries
                .selectTracksMissingReplayGain(pageSize = PAGE_SIZE, pageOffset = skippedTotal)
                .executeAsList()
            if (page.isEmpty()) break

            var pageSkippedDelta = 0
            for (row in page) {
                when (val result = analyzer.analyze(row.file_path, row.codec)) {
                    is Either.Left -> {
                        skipped++
                        pageSkippedDelta++
                        log.w {
                            "analyzer skipped track id=${row.id} path=${row.file_path}: ${result.value}"
                        }
                    }
                    is Either.Right -> {
                        val gainDb = REFERENCE_LUFS - result.value.integratedLufs
                        val peakLinear = dbtpToLinear(result.value.truePeakDbtp)
                        db.trackQueries.updateTrackReplayGain(
                            id = row.id,
                            db = gainDb,
                            peak = peakLinear,
                        )
                        analyzed++
                        row.album_id?.let { touchedAlbumIds.add(it) }
                    }
                }
            }
            skippedTotal += pageSkippedDelta

            if (pageSkippedDelta == page.size && page.size < PAGE_SIZE.toInt()) break
        }

        // Per-album rollup. (I2) Use touchedAlbumIds rather than
        // selectAlbumsForAggregation — the latter filters
        // `replay_gain_album_db IS NULL` which causes the partial-pass
        // finalization gap (run 1 sets album_db non-null after partial track
        // coverage; run 2's newly-analyzed tracks never re-aggregate).
        var albumsAggregated = 0
        for (albumId in touchedAlbumIds) {
            val perTrack = db.trackQueries.selectTrackReplayGainForAlbum(albumId).executeAsList()
            if (perTrack.isEmpty()) continue

            val trackLufsList = perTrack.map { row ->
                REFERENCE_LUFS - row.replay_gain_track_db
            }
            if (trackLufsList.isEmpty()) continue
            val albumLufs = when (val agg = albumIntegratedLufs(trackLufsList)) {
                is Either.Left -> {
                    log.w { "album $albumId rollup failed: ${agg.value}" }
                    continue
                }
                is Either.Right -> agg.value
            }
            val albumDb = REFERENCE_LUFS - albumLufs
            val albumPeak = perTrack.mapNotNull { it.replay_gain_track_peak }.maxOrNull() ?: 0.0

            db.transaction {
                db.trackQueries.updateAlbumReplayGainForAlbum(
                    albumId = albumId,
                    db = albumDb,
                    peak = albumPeak,
                )
            }
            albumsAggregated++
        }

        val durationMs = System.currentTimeMillis() - startedMs
        AnalysisPassResult(
            tracksAnalyzed = analyzed,
            tracksSkipped = skipped,
            albumsAggregated = albumsAggregated,
            durationMs = durationMs,
        ).also {
            log.i {
                "analysis pass complete: +${it.tracksAnalyzed} analyzed, " +
                    "${it.tracksSkipped} skipped, ${it.albumsAggregated} albums aggregated " +
                    "in ${it.durationMs}ms"
            }
        }
    }
```

Also update the class-level KDoc — replace the existing "Atomicity" paragraph with:

```kotlin
 * Touched-album tracking: the runner tracks `album_id` values of all
 * successfully-analyzed tracks in an in-memory `Set<Long>`. The per-album
 * rollup iterates this set rather than re-querying `selectAlbumsForAggregation`,
 * which would filter `replay_gain_album_db IS NULL` and miss albums that were
 * partially aggregated in a previous run. `selectAlbumsForAggregation` is
 * retained for a future "recover incomplete albums" code path but is not the
 * primary driver of rollup.
 *
 * Atomicity: each per-track persist is its own write (no enclosing
 * transaction). The per-album rollup IS wrapped in a transaction so all
 * tracks of an album receive the same album-level values atomically.
```

- [ ] **Step 4: Run all runner tests to verify both fixes**

Run: `./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.TrackAnalysisRunnerTest"`
Expected: PASS — 8 of 8 tests green (6 original + 2 new for I1 + I2).

- [ ] **Step 5: Commit**

```bash
git add data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt
git commit -m "fix(data:library): TrackAnalysisRunner I1+I2 (PR #11 review follow-ups)

I1 — Termination guard test. >100 tracks all returning Left from the
analyzer would have hung pre-wrap-up; the guard is verified in code
review but was not test-covered. Adds the missing regression test.

I2 — Touched-album tracking. The previous rollup loop drove off
selectAlbumsForAggregation, which filters replay_gain_album_db IS NULL.
This meant a partial-pass run (e.g., analyzing 2 of 3 tracks of an album)
left album_db set to a partial aggregate, and a subsequent run analyzing
the 3rd track would NOT re-aggregate (album_db is non-null). The runner
now tracks `album_id` values of successfully-analyzed tracks in-memory
and uses that set for rollup — albums always get re-aggregated when new
tracks land. selectAlbumsForAggregation is retained for a future 'recover
incomplete albums' path.

Phase 2a Track D-C — Task 2."
```

---

## Task 3 — `AnalysisProgress` + `runOnceWithProgress` flow

**Files:**
- Create: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/AnalysisProgress.kt`
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt`
- Modify: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt`

- [ ] **Step 1: Define `AnalysisProgress` and write the failing flow test**

Create `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/AnalysisProgress.kt`:

```kotlin
package com.clayworks.kiln.library.scan

/**
 * Intermediate + terminal events emitted by [TrackAnalysisRunner.runOnceWithProgress].
 *
 * Stream shape:
 *   [Started(total=N)] → [Progress(analyzed, skipped, total)]* → [Complete(result)]
 *
 * If the runner throws mid-pass, the flow terminates with the exception
 * (not a [Failed] event — failures here are coroutine cancellation /
 * unexpected exceptions, not analyzer-Left results).
 */
sealed interface AnalysisProgress {
    /** Emitted once at the start. [total] is the worklist size from `countTracksMissingReplayGain`. */
    data class Started(val total: Int) : AnalysisProgress

    /**
     * Emitted after every page of the worklist. [analyzed] + [skipped]
     * sum to the number of tracks processed so far; [total] is the
     * snapshot taken at [Started] (may not match later if other writers
     * race — UI should treat as a stable denominator).
     */
    data class Progress(
        val analyzed: Int,
        val skipped: Int,
        val total: Int,
    ) : AnalysisProgress

    /** Terminal event: the [AnalysisPassResult] is the same shape `runOnce()` returns. */
    data class Complete(val result: AnalysisPassResult) : AnalysisProgress
}
```

Append to `TrackAnalysisRunnerTest.kt`:

```kotlin
    @Test
    fun `runOnceWithProgress emits Started then Progress then Complete`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val t1 = testDb.insertTrack(artistId, albumId, "T1", filePath = "/test/t1.flac")
        val t2 = testDb.insertTrack(artistId, albumId, "T2", filePath = "/test/t2.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/t1.flac" to Either.Right(TrackLoudness(-20.0, -1.0)),
            "/test/t2.flac" to Either.Right(TrackLoudness(-18.0, -0.5)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined)

        val emissions = runner.runOnceWithProgress().toList()
        assertTrue(emissions.size >= 2, "expected ≥ 2 emissions, got ${emissions.size}")
        assertTrue(emissions.first() is AnalysisProgress.Started, "first emission must be Started, got ${emissions.first()}")
        assertEquals(2, (emissions.first() as AnalysisProgress.Started).total)
        assertTrue(emissions.last() is AnalysisProgress.Complete, "last emission must be Complete, got ${emissions.last()}")
        val finalResult = (emissions.last() as AnalysisProgress.Complete).result
        assertEquals(2, finalResult.tracksAnalyzed)
        assertEquals(0, finalResult.tracksSkipped)
        assertEquals(1, finalResult.albumsAggregated)
    }

    @Test
    fun `runOnceWithProgress on empty library emits Started 0 then Complete 0`() = runBlocking {
        val analyzer = FakeTrackAnalyzer(emptyMap())
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined)
        val emissions = runner.runOnceWithProgress().toList()
        assertTrue(emissions.first() is AnalysisProgress.Started)
        assertEquals(0, (emissions.first() as AnalysisProgress.Started).total)
        assertTrue(emissions.last() is AnalysisProgress.Complete)
        assertEquals(0, (emissions.last() as AnalysisProgress.Complete).result.tracksAnalyzed)
    }
```

Add import to the test file: `import kotlinx.coroutines.flow.toList`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.TrackAnalysisRunnerTest"`
Expected: FAIL with "unresolved reference: runOnceWithProgress".

- [ ] **Step 3: Implement `runOnceWithProgress` on the runner**

In `TrackAnalysisRunner.kt`, add the import:

```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
```

Then add the new method inside the class (after `runOnce`):

```kotlin
    /**
     * Flow-emitting variant of [runOnce]. Suitable for UI progress reporting.
     *
     * Emits exactly one [AnalysisProgress.Started] at the start, one
     * [AnalysisProgress.Progress] after every worklist page, and exactly
     * one [AnalysisProgress.Complete] at the end. Cancellation of the
     * collector cancels the analysis pass via standard coroutine cancellation
     * semantics.
     *
     * Implementation note: this is a separate flow body, not a wrapper that
     * polls [runOnce]. Sharing the per-page loop between `runOnce()` and
     * `runOnceWithProgress()` would either require a callback parameter on
     * `runOnce()` (breaking its existing test surface) or a SharedFlow with
     * back-pressure semantics that would complicate the test contract.
     * Duplicate logic is the lesser evil at this size.
     */
    fun runOnceWithProgress(): Flow<AnalysisProgress> = flow {
        val total = db.trackQueries.countTracksMissingReplayGain().executeAsOne().toInt()
        emit(AnalysisProgress.Started(total = total))

        val startedMs = System.currentTimeMillis()
        var analyzed = 0
        var skipped = 0
        var skippedTotal = 0L
        val touchedAlbumIds = mutableSetOf<Long>()

        while (true) {
            val page = db.trackQueries
                .selectTracksMissingReplayGain(pageSize = PAGE_SIZE, pageOffset = skippedTotal)
                .executeAsList()
            if (page.isEmpty()) break

            var pageSkippedDelta = 0
            for (row in page) {
                when (val result = analyzer.analyze(row.file_path, row.codec)) {
                    is Either.Left -> {
                        skipped++
                        pageSkippedDelta++
                        log.w {
                            "analyzer skipped track id=${row.id} path=${row.file_path}: ${result.value}"
                        }
                    }
                    is Either.Right -> {
                        val gainDb = REFERENCE_LUFS - result.value.integratedLufs
                        val peakLinear = dbtpToLinear(result.value.truePeakDbtp)
                        db.trackQueries.updateTrackReplayGain(
                            id = row.id,
                            db = gainDb,
                            peak = peakLinear,
                        )
                        analyzed++
                        row.album_id?.let { touchedAlbumIds.add(it) }
                    }
                }
            }
            skippedTotal += pageSkippedDelta

            emit(AnalysisProgress.Progress(analyzed = analyzed, skipped = skipped, total = total))

            if (pageSkippedDelta == page.size && page.size < PAGE_SIZE.toInt()) break
        }

        var albumsAggregated = 0
        for (albumId in touchedAlbumIds) {
            val perTrack = db.trackQueries.selectTrackReplayGainForAlbum(albumId).executeAsList()
            if (perTrack.isEmpty()) continue

            val trackLufsList = perTrack.map { row -> REFERENCE_LUFS - row.replay_gain_track_db }
            if (trackLufsList.isEmpty()) continue
            val albumLufs = when (val agg = albumIntegratedLufs(trackLufsList)) {
                is Either.Left -> {
                    log.w { "album $albumId rollup failed: ${agg.value}" }
                    continue
                }
                is Either.Right -> agg.value
            }
            val albumDb = REFERENCE_LUFS - albumLufs
            val albumPeak = perTrack.mapNotNull { it.replay_gain_track_peak }.maxOrNull() ?: 0.0

            db.transaction {
                db.trackQueries.updateAlbumReplayGainForAlbum(
                    albumId = albumId,
                    db = albumDb,
                    peak = albumPeak,
                )
            }
            albumsAggregated++
        }

        val durationMs = System.currentTimeMillis() - startedMs
        emit(
            AnalysisProgress.Complete(
                result = AnalysisPassResult(
                    tracksAnalyzed = analyzed,
                    tracksSkipped = skipped,
                    albumsAggregated = albumsAggregated,
                    durationMs = durationMs,
                ),
            ),
        )
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.TrackAnalysisRunnerTest"`
Expected: PASS — 10 of 10 (8 from Task 2 + 2 new).

- [ ] **Step 5: Commit**

```bash
git add data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/AnalysisProgress.kt data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt
git commit -m "feat(data:library): AnalysisProgress + runOnceWithProgress flow

Adds the Flow-emitting variant of runOnce for UI progress reporting:
  - AnalysisProgress sealed interface (Started, Progress, Complete)
  - runOnceWithProgress(): Flow<AnalysisProgress>

The body duplicates runOnce's logic rather than wrapping it via a callback
or SharedFlow — sharing would either break runOnce's test contract or
require complex back-pressure handling. At this size, duplication is the
lesser evil. Both methods stay in sync via the per-task review process.

Phase 2a Track D-C — Task 3."
```

---

## Task 4 — `SettingsState` extension + `BackfillUiState`

**Files:**
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/BackfillUiState.kt`
- Modify: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt`

- [ ] **Step 1: Create `BackfillUiState`**

Create `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/BackfillUiState.kt`:

```kotlin
package com.clayworks.kiln.ui.components.settings

/**
 * UI-side view of the analyzer backfill state. Mirrors the
 * [com.clayworks.kiln.library.scan.AnalysisProgress] events but kept
 * in `:ui:components` to avoid leaking flow plumbing into the screen.
 *
 * State machine:
 *   Idle(missingCount = N)            // initial; updates as scanner adds tracks
 *   InProgress(analyzed, skipped, total)
 *   Complete(analyzed, skipped, total, albumsAggregated, durationMs)
 *   InProgress / Complete persist until user clicks the button again or navigates away.
 */
sealed interface BackfillUiState {
    data class Idle(val missingCount: Int) : BackfillUiState
    data class InProgress(val analyzed: Int, val skipped: Int, val total: Int) : BackfillUiState
    data class Complete(
        val analyzed: Int,
        val skipped: Int,
        val total: Int,
        val albumsAggregated: Int,
        val durationMs: Long,
    ) : BackfillUiState
}
```

- [ ] **Step 2: Extend `SettingsState`**

Replace `data/library/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt` with:

```kotlin
package com.clayworks.kiln.ui.components.settings

import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.ThemeMode

/**
 * Plain hoisted state for SettingsScreen — no Compose dependencies, no
 * coroutine internals. App modules collect SettingsRepository.Flow values
 * into one of these, pass into the screen, and route callbacks back to
 * setX(...) calls on the repository.
 *
 * String-typed `scanFolders` matches the cross-platform contract on the
 * repository: java.nio.file.Path is JVM-only, SAF URIs aren't paths. The
 * screen displays them as-is.
 */
data class SettingsState(
    val themeMode: ThemeMode,
    val scanOnLaunch: Boolean,
    val scanFolders: List<String>,
    val replayGainMode: ReplayGainMode,
    val replayGainPreAmpDb: Double,
    val backfill: BackfillUiState,
)
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :ui:components:compileKotlinDesktop`
Expected: FAIL — `SettingsScreen.kt` still references the 3-arg `SettingsState` constructor (5 of the existing call sites). The compile failure exposes the call sites to update; Task 5 fixes them.

For now, suppress compile errors by reverting `SettingsState.kt` to the old shape temporarily? No — the right approach is to keep going. The implementer should now:

- [ ] **Step 4: Fix the `SettingsScreen` `state` usages to compile against the new constructor**

This is small enough to do alongside the SettingsState change. The screen body doesn't read the new fields yet (Task 5 will add the new section); for now, just make sure the data class signature compiles by updating any internal references.

Actually scratch that — the cleanest sequencing is: Task 4 lands the new types (`BackfillUiState` + extended `SettingsState`), Task 5 lands the screen section reading them, and Tasks 6+7 land the app-route wiring that constructs them. Between Task 4 and Task 6 there will be compile errors at the call sites in `Main.kt` (desktop) and `MainActivity.kt` (android). That's expected — the work isn't shippable until Task 6 lands.

Decision: **Don't enforce a clean intermediate compile state.** Tasks 4-6 form a logical unit; the canonical verify-build runs once at the end (Task 7). The implementer SHOULD test individual files where possible but not block on cross-file compile errors during Tasks 4-5.

Alternatively: bundle Task 4-5-6 into one commit. But the plan benefits from staged review; bundling adds churn for the reviewer.

Compromise: implementer commits Task 4 even if `Main.kt` / `MainActivity.kt` don't compile against it yet. The Task 5 commit will fix the screen body. The Task 6 commit will fix the app routes. Verify-build runs after Task 6 only.

- [ ] **Step 5: Commit**

```bash
git add ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/BackfillUiState.kt ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt
git commit -m "feat(ui:components): SettingsState extension + BackfillUiState

Adds three new fields to SettingsState — replayGainMode, replayGainPreAmpDb,
backfill — and a BackfillUiState sealed interface (Idle / InProgress /
Complete) for the analyzer backfill button's UI state.

Call sites in app-desktop/Main.kt and app-android/MainActivity.kt will
break until Task 6 wires the new fields; this is expected. Verify-build
runs at the tail of Task 7.

Phase 2a Track D-C — Task 4."
```

---

## Task 5 — `SettingsScreen` "ReplayGain" section

**Files:**
- Modify: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt`

- [ ] **Step 1: Add imports**

In `SettingsScreen.kt`, add imports:

```kotlin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.clayworks.kiln.library.settings.ReplayGainMode
```

- [ ] **Step 2: Extend the `SettingsScreen` parameter list and body**

Update the `@Composable fun SettingsScreen(...)` signature to accept three new callbacks:

```kotlin
@Composable
fun SettingsScreen(
    state: SettingsState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onScanOnLaunchChange: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onReplayGainModeChange: (ReplayGainMode) -> Unit,
    onReplayGainPreAmpDbChange: (Double) -> Unit,
    onTriggerBackfill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        // === Theme section === (unchanged)
        // ... existing code ...
        // === Behavior section === (unchanged)
        // ... existing code ...
        // === Library section === (unchanged)
        // ... existing code ...

        // === ReplayGain section (new) ===
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        Text("ReplayGain", style = MaterialTheme.typography.titleMedium)
        Text(
            "Volume-normalize tracks during playback. Note: applies once Track D-B's " +
                "consumer-side gain ships. Until then, configuring here persists for later.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Column(modifier = Modifier.selectableGroup()) {
            ReplayGainMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.replayGainMode == mode,
                            onClick = { onReplayGainModeChange(mode) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.replayGainMode == mode, onClick = null)
                    Text(
                        text = mode.name,
                        modifier = Modifier.padding(start = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Pre-amp: ${"%.1f".format(state.replayGainPreAmpDb)} dB",
            style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = state.replayGainPreAmpDb.toFloat(),
            onValueChange = { onReplayGainPreAmpDbChange(it.toDouble()) },
            valueRange = -12f..12f,
            steps = 47,  // 0.5 dB increments: (12 - -12) / 0.5 = 48 steps including endpoints
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Analyze missing tracks", style = MaterialTheme.typography.titleMedium)
        BackfillContent(
            state = state.backfill,
            onTriggerBackfill = onTriggerBackfill,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun BackfillContent(
    state: BackfillUiState,
    onTriggerBackfill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is BackfillUiState.Idle -> {
            Column(modifier = modifier) {
                Text(
                    if (state.missingCount == 0) {
                        "All tracks have ReplayGain values."
                    } else {
                        "${state.missingCount} track(s) need analysis."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onTriggerBackfill,
                    enabled = state.missingCount > 0,
                ) {
                    Text("Analyze")
                }
            }
        }
        is BackfillUiState.InProgress -> {
            Column(modifier = modifier) {
                Text(
                    "Analyzing: ${state.analyzed} of ${state.total} done, ${state.skipped} skipped",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val progressFraction = if (state.total > 0) {
                    (state.analyzed + state.skipped).toFloat() / state.total.toFloat()
                } else 0f
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is BackfillUiState.Complete -> {
            Column(modifier = modifier) {
                Text(
                    "Done. ${state.analyzed} analyzed, ${state.skipped} skipped, " +
                        "${state.albumsAggregated} albums aggregated in ${state.durationMs / 1000}s.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onTriggerBackfill) {
                    Text("Re-analyze")
                }
            }
        }
    }
}
```

NOTE: `Slider`'s `steps` parameter is the count of discrete stops BETWEEN min and max (not including endpoints). With `valueRange = -12f..12f` and `steps = 47`, the slider has 49 distinct positions in 0.5-dB increments — verify by reading the Compose-MP Slider docs if uncertain.

NOTE: `String.format` is JVM-only. The `"%.1f".format(...)` call inside `commonMain` will fail on Kotlin/JS or Native if this module ever extends beyond JVM. Since `:ui:components` already deps on Compose-MP which is JVM/Android only in this project, this is fine. If a compile error surfaces, fall back to `((state.replayGainPreAmpDb * 10).toInt() / 10.0).toString()` (rounds to 1 decimal place without locale-aware formatting).

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :ui:components:compileKotlinDesktop`
Expected: PASS — the screen compiles against the new SettingsState. The app routes will still fail (they call the old 5-callback signature); that's Task 6.

- [ ] **Step 4: Commit**

```bash
git add ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt
git commit -m "feat(ui:components): SettingsScreen ReplayGain section

Adds a new section to SettingsScreen below Library folders:
  - Mode radio group (Off/Track/Album)
  - Pre-amp dB slider (-12 to +12, 0.5 dB increments)
  - 'Analyze missing tracks' button with three-state UI (Idle/InProgress/Complete)

The screen accepts three new callbacks:
  - onReplayGainModeChange(mode)
  - onReplayGainPreAmpDbChange(db)
  - onTriggerBackfill()

A small footnote sets user expectations that the mode setting takes
audible effect once Track D-B ships consumer-side gain.

App routes (Main.kt, MainActivity.kt) will not compile until Task 6 lands.
Verify-build defers to Task 7.

Phase 2a Track D-C — Task 5."
```

---

## Task 6 — App route wiring (desktop + android)

**Files:**
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt`
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt`

**Plan note**: The exact line locations differ between platforms. The implementer should read both files end-to-end before editing, and follow the existing 3-callback wiring pattern when adding the 3 new callbacks + the new state collection.

- [ ] **Step 1: Wire `DesktopSettingsRoute` (desktop)**

In `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt`, the `DesktopSettingsRoute` composable currently collects 3 SettingsRepository flows. Add 2 more + the backfill state machine:

```kotlin
@Composable
private fun DesktopSettingsRoute(
    graph: DesktopAppGraph,
    onClose: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
    val scanOnLaunch by graph.settings.scanOnLaunch.collectAsState(initial = false)
    val scanFolders by graph.settings.scanFolders.collectAsState(initial = emptyList())
    val replayGainMode by graph.settings.replayGainMode.collectAsState(initial = ReplayGainMode.Off)
    val replayGainPreAmpDb by graph.settings.replayGainPreAmpDb.collectAsState(initial = 0.0)

    // Reactive missing-track count from the DB. SQLDelight asFlow() emits
    // updates when the underlying query result changes.
    val missingCount by remember(graph) {
        graph.kilnDatabase.trackQueries.countTracksMissingReplayGain()
            .asFlow()
            .mapToOne(Dispatchers.IO)
            .map { it.toInt() }
    }.collectAsState(initial = 0)

    var backfillState: BackfillUiState by remember { mutableStateOf<BackfillUiState>(BackfillUiState.Idle(missingCount)) }
    // Refresh Idle state's missingCount whenever the count flow emits and
    // we're not actively running.
    LaunchedEffect(missingCount) {
        if (backfillState is BackfillUiState.Idle) {
            backfillState = BackfillUiState.Idle(missingCount)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Kiln by Clayworks", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        SettingsScreen(
            state = SettingsState(
                themeMode = themeMode,
                scanOnLaunch = scanOnLaunch,
                scanFolders = scanFolders,
                replayGainMode = replayGainMode,
                replayGainPreAmpDb = replayGainPreAmpDb,
                backfill = backfillState,
            ),
            onThemeModeChange = { mode ->
                coroutineScope.launch { graph.settings.setThemeMode(mode) }
            },
            onScanOnLaunchChange = { enabled ->
                coroutineScope.launch { graph.settings.setScanOnLaunch(enabled) }
            },
            onPickFolder = {
                coroutineScope.launch {
                    val picked = pickFolderDialog()
                    if (picked != null && picked !in scanFolders) {
                        graph.settings.setScanFolders(scanFolders + picked)
                    }
                }
            },
            onRemoveFolder = { folder ->
                coroutineScope.launch {
                    graph.settings.setScanFolders(scanFolders - folder)
                }
            },
            onReplayGainModeChange = { mode ->
                coroutineScope.launch { graph.settings.setReplayGainMode(mode) }
            },
            onReplayGainPreAmpDbChange = { db ->
                coroutineScope.launch { graph.settings.setReplayGainPreAmpDb(db) }
            },
            onTriggerBackfill = {
                coroutineScope.launch {
                    graph.analysisRunner.runOnceWithProgress().collect { progress ->
                        backfillState = when (progress) {
                            is AnalysisProgress.Started ->
                                BackfillUiState.InProgress(0, 0, progress.total)
                            is AnalysisProgress.Progress ->
                                BackfillUiState.InProgress(progress.analyzed, progress.skipped, progress.total)
                            is AnalysisProgress.Complete ->
                                BackfillUiState.Complete(
                                    analyzed = progress.result.tracksAnalyzed,
                                    skipped = progress.result.tracksSkipped,
                                    total = progress.result.tracksAnalyzed + progress.result.tracksSkipped,
                                    albumsAggregated = progress.result.albumsAggregated,
                                    durationMs = progress.result.durationMs,
                                )
                        }
                    }
                }
            },
        )
    }
}
```

Add imports to `Main.kt`:
```kotlin
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import androidx.compose.runtime.LaunchedEffect
import com.clayworks.kiln.library.scan.AnalysisProgress
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.ui.components.settings.BackfillUiState
import kotlinx.coroutines.flow.map
```

**Important**: This requires `graph.kilnDatabase` and `graph.analysisRunner` accessors on `DesktopAppGraph`. If `kilnDatabase` is not currently exposed as an abstract val on the graph, expose it now — add `abstract val kilnDatabase: KilnDatabase` to the graph class. Similarly for `analysisRunner: TrackAnalysisRunner`.

Check the existing `DesktopAppGraph.kt`. If those abstract vals don't exist, add them in this commit.

- [ ] **Step 2: Wire `AndroidSettingsRoute` (android)**

In `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt`, mirror the desktop pattern in `AndroidSettingsRoute`. Add the same 5 collected states, the `missingCount` derivation, the `backfillState` machinery, and the 3 new callbacks. The folder-picker is `launchSafPicker` (already wired); leave that as-is.

Add identical imports.

Note: `graph.analysisRunner` on `AndroidAppGraph` was wired in Task 7 of the wrap-up; the abstract val should already exist or be addable the same way.

- [ ] **Step 3: Run canonical verify-build**

Run: `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:build`
Expected: BUILD SUCCESSFUL — both apps build, all tests pass.

- [ ] **Step 4: Smoke-test the desktop app launch**

Run: `./gradlew :app-desktop:run`
Verify: app launches, settings gear opens the settings route, the "ReplayGain" section appears with mode radio + slider + button. The button shows N tracks needing analysis. Click it; progress bar advances. Don't wait for the full 40k-track analysis to complete (that'd take hours) — just verify the first ~30 seconds of progress reporting works, then kill the app.

If something is broken in the UI, fix it. If the runner crashes on a real track, that's a real bug; escalate.

- [ ] **Step 5: Commit**

```bash
git add app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt
git commit -m "feat(app): wire ReplayGain settings + backfill into both routes

DesktopSettingsRoute and AndroidSettingsRoute now collect:
  - graph.settings.replayGainMode
  - graph.settings.replayGainPreAmpDb
  - graph.kilnDatabase.trackQueries.countTracksMissingReplayGain() (reactive)

And manage backfill state in a Compose remember{} cell, updated from
graph.analysisRunner.runOnceWithProgress() emissions.

Graph abstract vals added where missing (kilnDatabase, analysisRunner)
to make these accessible from the route composables.

Phase 2a Track D-C — Task 6."
```

---

## Task 7 — CLAUDE.md gotchas + Session 17 handoff + canonical verify-build + PR

**Files:**
- Modify: `CLAUDE.md`
- Create: `docs/sessions/2026-05-22-session-17-track-d-handoff.md`

- [ ] **Step 1: Append CLAUDE.md gotchas**

In `CLAUDE.md`, after the existing Track D-A wrap-up gotchas, append:

```markdown
- **Compose-MP `Slider.steps` is the count of discrete stops BETWEEN min and max**, not including the endpoints. For a 0.5 dB increment slider over [-12, +12] dB, `steps = 47` produces 49 distinct positions (24 intervals × 2 + 1 endpoint = 49). Off-by-one in steps quietly produces ugly fractional dB values like `-11.7234` instead of `-11.5`.
- **`String.format("%.1f", value)` is JVM-only and works in `:ui:components/commonMain`** because the module targets JVM+Android only (no JS/Native). If the module is ever extended to other KMP platforms, replace with `((value * 10).toInt() / 10.0).toString()` or vendor a small formatter.
- **kotlin-inject graph abstract vals are required for `graph.xxx` access from non-component callers** (e.g., from a Composable's `LaunchedEffect`). If a `@Provides fun` exists but no `abstract val xxx: T` matches, the consumer cannot reach the provider via `graph.xxx`. Adding the abstract val is a one-line declaration in the graph class body.
- **Compose `LinearProgressIndicator(progress = { fraction })`** (lambda form, Compose 1.5+) is preferred over the deprecated `progress = fraction` overload. The lambda form is recomposition-safe — the progress value is read inside the indicator's draw scope.
- **TrackAnalysisRunner partial-pass album rollup**: D-A wrap-up shipped with a known limitation that albums partially-analyzed in one run wouldn't re-aggregate in a follow-up run (because `selectAlbumsForAggregation` filters `replay_gain_album_db IS NULL`). D-C Task 2 fixes this by tracking touched album_ids in-memory during the per-track loop and rolling those up regardless of current `replay_gain_album_db` value. `selectAlbumsForAggregation` query is retained but no longer drives the rollup.
- **Backfill UI button on a 40k-track library will take ~17-100 hours**. The progress reporter shows current state so the user knows it's running; cancelling via app exit is supported (coroutine cancellation propagates through the flow). A future "pausable / resumable" backfill would need explicit state in the DB (e.g., a `last_analysis_attempt_ms` column to skip recently-failed tracks).
```

- [ ] **Step 2: Write Session 17 handoff**

Create `docs/sessions/2026-05-22-session-17-track-d-handoff.md`:

```markdown
# Session 17 Handoff — Phase 2a Track D-B (consumer-side gain)

**Authored:** 2026-05-22 at the close of Session 15 (after Track D-C settings + backfill shipped)
**For:** Fresh CC session continuing Phase 2a Track D
**Goal:** Implement Track D-B: apply persisted ReplayGain values (from D-A wrap-up + D-C) to the audio pipeline so volume normalization is actually audible.

---

## TL;DR

- Track D is one sub-track away from done. D-B is the last piece: consumer-side gain in the playback path.
- D-A wrap-up + D-C are merged (PRs #11 + #12). Settings UI exposes the mode + pre-amp; backfill button populates RG values. But playing a track still ignores those values — that's D-B's job.

## What Sessions 14 + 15 shipped

- **Session 14** (PR #10): EBU R128 / BS.1770-4 LoudnessAnalyzer in `:audio:dsp` with K-weighting, dual gating, true-peak metering.
- **Session 15 Track D-A wrap-up** (PR #11): album LUFS aggregator, SQLDelight queries, TrackAnalyzer port, TrackAnalysisRunner orchestrator, desktop FLAC + Android MediaCodec analyzer impls, DI wiring.
- **Session 15 Track D-C** (PR #12 — this branch): SettingsRepository extension with ReplayGainMode + replayGainPreAmpDb, runner I1+I2 fixes (termination guard test + touched-album tracking), AnalysisProgress + runOnceWithProgress flow, SettingsScreen "ReplayGain" section, app-route wiring on both platforms.

## What Track D-B is

The audio pipeline needs to apply the stored gain when playing back a track. Three sub-pieces:

1. **Desktop**: `JavaSoundPlayerImpl` currently writes decoded PCM directly to a `SourceDataLine`. Insert a multiplier stage between decode and write:
   - Resolve gain at track-start: read `track.replay_gain_track_db` (Track mode) or `track.replay_gain_album_db` (Album mode) from the DB, apply pre-amp dB, convert to linear (`10^((rgDb + preAmpDb)/20)`).
   - Apply the linear multiplier to every sample before writing to the line.
   - Peak limiter (or skip the multiplier) if `replay_gain_track_peak * gain_linear > 1.0` (clipping prevention).

2. **Android**: Media3 ExoPlayer's audio pipeline is built on `AudioProcessor` chain via `RenderersFactory`. Inject a custom AudioProcessor:
   - The processor reads MediaItem metadata (or a sidecar query against the DB) to get the gain for the current track.
   - Multiplies each sample.
   - Similar peak-limit guard.

3. **Settings-driven mode switch**: `replayGainMode == Off` → bypass multiplier entirely (zero overhead). `Track` → use `replay_gain_track_db`. `Album` → use `replay_gain_album_db`, falling back to track_db if album_db is null.

## Recommendation

**Recommend D-B as a single session (probably 5-8 tasks):**

1. Module test for the gain-resolution function (pure math, given track row + mode + pre-amp → linear multiplier).
2. Desktop `JavaSoundPlayerImpl` multiplier stage + test.
3. Android Media3 `AudioProcessor` impl + test.
4. Peak limiter (or "skip if clipping" guard) on both.
5. DI graph wiring updates if needed.
6. Smoke test on a real FLAC fixture (desktop) + on-device test on Pixel (android).
7. CLAUDE.md gotchas + Session 18 handoff (probably "Phase 2a done; pick from the 6-track menu what's next").

## Reference

- D-A wrap-up plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-wrap-up.md`
- D-C plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-c-settings-backfill.md`
- Engram entries: `mem_search "kiln/track-d-c"` and `mem_search "kiln/track-d-a-wrap-up"`
- CLAUDE.md gotchas: section "Build/Dep Gotchas (discovered MVP Sessions 1-7)" — last ~20 bullets added across Sessions 14-15 for Track D.

---

**End of Session 17 Handoff.**
```

- [ ] **Step 3: Run the canonical verify-build**

Run: `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:build`
Expected: BUILD SUCCESSFUL — 6 of 6 targets pass. Test counts: 18+ in `:data:library:desktopTest` (11 SettingsRepository + 10 TrackAnalysisRunner + others).

Also: `./gradlew :audio:dsp:desktopTest :audio:playback:desktopTest`
Expected: PASS.

- [ ] **Step 4: Commit + push**

```bash
git add CLAUDE.md docs/sessions/2026-05-22-session-17-track-d-handoff.md
git commit -m "docs: Track D-C — CLAUDE.md gotchas + Session 17 handoff

Captures 6 new gotchas for Compose Slider steps, JVM-only String.format,
kotlin-inject abstract vals for DI access, LinearProgressIndicator lambda
form, partial-pass album rollup fix (I2), and backfill 40k-track perf
characteristic.

Session 17 handoff covers Track D-B (consumer-side gain) as the only
remaining sub-track of Phase 2a Track D. Estimated 5-8 tasks.

Phase 2a Track D-C — Task 7."

git push -u origin phase-2a-track-d-c-settings-backfill
```

- [ ] **Step 5: Open PR**

```bash
gh pr create --title "Phase 2a Track D-C — ReplayGain settings UI + backfill button" --body "$(cat <<'EOF'
## Summary

Stacks on PR #11 (Track D-A wrap-up). Surfaces the analyzer runner to users via:

- `ReplayGainMode` enum + `replayGainPreAmpDb` setting in `SettingsRepository`
- New "ReplayGain" section in `SettingsScreen` with mode radio + pre-amp slider + backfill button + progress indicator
- `TrackAnalysisRunner.runOnceWithProgress(): Flow<AnalysisProgress>` for UI integration
- DesktopSettingsRoute + AndroidSettingsRoute hoist the new state and wire the backfill callback

## Final-review fixes from PR #11

- **I1** — Termination guard test for >100 all-skipping tracks (regression cover for the runner's worklist offset advance).
- **I2** — Touched-album tracking: the rollup loop now iterates `album_id`s of successfully-analyzed tracks (in-memory set) rather than `selectAlbumsForAggregation`'s `IS NULL` filter. Fixes the partial-pass finalization gap.

## Test plan

- [ ] CI green (Ubuntu :app-android:assembleDebug + Windows :app-desktop:assemble)
- [ ] `:data:library:desktopTest` PASS — 5 new SettingsRepository tests + 4 new TrackAnalysisRunner tests + existing tests
- [ ] `:ui:components:build` PASS
- [ ] Manual desktop smoke: open settings, see ReplayGain section, click Analyze, observe progress
- [ ] Manual Pixel 7 Pro install: same flow on device

## Architecture notes

- Mode setting is persisted by D-C but doesn't take audible effect until Track D-B ships consumer-side gain. UI footnote sets user expectations.
- Backfill is single-threaded and can take ~17-100h on a 40k library. Coroutine cancellation propagates through the flow; app exit cancels the pass.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

After all 7 tasks:

1. **Spec coverage**: SettingsRepository extension ✓, runner I1+I2 fixes ✓, AnalysisProgress flow ✓, SettingsState extension ✓, SettingsScreen section ✓, both app routes wired ✓, gotchas + handoff ✓.
2. **Placeholder scan**: every step has complete code.
3. **Type consistency**: `ReplayGainMode` declared in Task 1 used in Tasks 4-6. `AnalysisProgress` declared in Task 3 used in Tasks 4-6. `BackfillUiState` declared in Task 4 used in Tasks 5-6.

---

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-05-22-phase-2a-track-d-c-settings-backfill.md`. Execution model: subagent-driven-development per Session 14+15 pattern, Sonnet implementers + reviewer pair.
