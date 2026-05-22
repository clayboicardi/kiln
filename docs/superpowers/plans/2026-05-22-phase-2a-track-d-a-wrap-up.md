# Phase 2a Track D-A wrap-up: Album aggregation + analyzer runner + scanner-side wiring

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the Session 14 `LoudnessAnalyzer` (already in `:audio:dsp/commonMain`) into a per-track analysis pipeline that populates `track.replay_gain_track_db`, `track.replay_gain_track_peak`, `track.replay_gain_album_db`, `track.replay_gain_album_peak`. Expose a `TrackAnalysisRunner` orchestrator via DI so Session 16 (Track D-C settings + backfill UI) can trigger it from a button.

**Architecture:**
- **Pure math (no project deps)** in `:audio:dsp/commonMain`: new `LoudnessAggregator` adds `albumIntegratedLufs(trackLufsValues)` per BS.1770-4 §5.3 (energy-weighted mean over per-track integrated LUFS).
- **Persistence layer (commonMain)**: new SQLDelight queries on `track.sq` for the worklist (`selectTracksMissingReplayGain`, `countTracksMissingReplayGain`), per-track persist (`updateTrackReplayGain`), per-album rollup (`selectAlbumsForAggregation`, `selectTrackLufsForAlbum`, `updateAlbumReplayGainForAlbum`).
- **Port + orchestrator (commonMain in `:data:library`)**: `TrackAnalyzer` interface + `TrackLoudness` DTO + `TrackAnalysisError` sealed errors. `TrackAnalysisRunner` walks the worklist, calls the analyzer, persists per-track, rolls up per-album.
- **Platform analyzers (in `:audio:playback`)**: `JvmFlacTrackAnalyzer` (desktopMain) wraps `createJvmFlacDecoder()` + `createLoudnessAnalyzer(...)`; non-FLAC codecs return `TrackAnalysisError.CodecUnsupported` for this session. `AndroidMediaTrackAnalyzer` (androidMain) uses `MediaExtractor` + `MediaCodec` to decode any device-supported codec to PCM and feed the analyzer.
- **DI graph wiring**: `DesktopAppGraph` and `AndroidAppGraph` expose a `TrackAnalysisRunner`. **No auto-invocation** during `scanIncremental()` / `scanFull()` — perf math (≈1.5–10 s per track × 40 k tracks ≥ ~17–100 h) makes inline invocation user-hostile. The Session 16 backfill UI is the explicit trigger.

**Tech Stack:** Kotlin Multiplatform (commonMain + desktopMain + androidMain), Arrow Core (`Either`), SQLDelight 2.x, kotlin.test (unit tests), kotlinx-coroutines-test (test scope), Android MediaExtractor / MediaCodec (androidMain), JNA libFLAC bridge via existing `createJvmFlacDecoder()` (desktopMain).

**Scope (this session):**
- ✅ Album-level integrated LUFS aggregation (BS.1770-4 §5.3)
- ✅ SQLDelight queries for the analyzer worklist + per-track + per-album persist
- ✅ `TrackAnalyzer` interface + DTOs + sealed errors
- ✅ `TrackAnalysisRunner` orchestrator with paginated walk + per-album rollup
- ✅ Desktop FLAC-only `TrackAnalyzer` impl with unit test against bundled fixtures
- ✅ Android `MediaExtractor`/`MediaCodec`-based `TrackAnalyzer` impl (no Robolectric coverage — MediaCodec doesn't mock well; smoke-test deferred to manual device session)
- ✅ DI graph wiring for both platforms (`DesktopAppGraph`, `AndroidAppGraph`)
- ✅ CLAUDE.md gotchas appended
- ✅ Session 16 handoff doc
- ⛔ Auto-invocation of the runner during `scanIncremental()` / `scanFull()` (deferred per architecture note above — D-C settings can add a `analyzeOnScan: Boolean` flag later)
- ⛔ Consumer-side gain application (Track D-B)
- ⛔ Settings UI + backfill button (Track D-C)
- ⛔ Per-track timing/perf benchmarking against the full 40 k library (manual smoke after Track D-C lands; the runner already logs per-track timing for ad-hoc observation)

**Branch:** `phase-2a-track-d-a-wrap-up` (off `main` at 82b337e — Track D-A merged via PR #10).

---

## File Structure

| File | Role |
|---|---|
| `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/LoudnessAggregator.kt` | Public top-level `albumIntegratedLufs(trackLufsValues): Either<AnalysisError, Double>` per BS.1770-4 §5.3. Energy-weighted mean over per-track integrated LUFS values. |
| `audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/LoudnessAggregatorTest.kt` | Aggregator unit tests: known reference vectors, empty list = `InsufficientAudio`, single-track passthrough, mixed loud/quiet. |
| `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/track.sq` | **Modify**: add `selectTracksMissingReplayGain`, `countTracksMissingReplayGain`, `updateTrackReplayGain`, `selectAlbumsForAggregation`, `selectTrackReplayGainForAlbum`, `updateAlbumReplayGainForAlbum`. |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalyzer.kt` | `TrackAnalyzer` interface, `TrackLoudness` data class, `TrackAnalysisError` sealed interface. |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt` | Orchestrator. Walks `selectTracksMissingReplayGain` in pages, calls `analyzer.analyze(...)`, persists via `updateTrackReplayGain`. After the per-track loop, walks `selectAlbumsForAggregation` and persists album-level via `updateAlbumReplayGainForAlbum`. |
| `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt` | Runner tests against TestDb + `FakeTrackAnalyzer`. Cases: empty library, three tracks one album, two albums, analyzer Left for one track, runner is interruptible. |
| `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacTrackAnalyzer.kt` | Desktop FLAC `TrackAnalyzer` impl. Public factory `createJvmFlacTrackAnalyzer(): TrackAnalyzer` keeps impl class internal. |
| `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JvmFlacTrackAnalyzerTest.kt` | Integration test: analyze the bundled `sine_440_stereo_16_44.flac` fixture, assert integratedLufs and truePeakDbtp finite and within sane ranges; non-FLAC codec returns `CodecUnsupported`. |
| `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/AndroidMediaTrackAnalyzer.kt` | Android `TrackAnalyzer` impl. Public factory `createAndroidMediaTrackAnalyzer(context): TrackAnalyzer`. Uses `MediaExtractor` + `MediaCodec` synchronous-mode decode → 16-bit/float PCM → `LoudnessAnalyzer`. |
| `audio/dsp/build.gradle.kts` | No change. |
| `audio/playback/build.gradle.kts` | **Modify**: add `implementation(project(":audio:dsp"))` to commonMain deps so platform analyzer impls can construct `LoudnessAnalyzer`. |
| `data/library/build.gradle.kts` | **Modify**: add `implementation(project(":audio:dsp"))` to commonMain deps so `TrackAnalysisRunner` can use `albumIntegratedLufs(...)` from the aggregator. |
| `app-desktop/src/jvmMain/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt` | **Modify**: add `analyzer: TrackAnalyzer = createJvmFlacTrackAnalyzer()` and `analysisRunner: TrackAnalysisRunner` provider. |
| `app-android/src/main/kotlin/com/clayworks/kiln/android/di/AndroidAppGraph.kt` | **Modify**: add `analyzer: TrackAnalyzer = createAndroidMediaTrackAnalyzer(context)` and `analysisRunner: TrackAnalysisRunner` provider. |
| `CLAUDE.md` | **Modify**: append Track D-A wrap-up gotchas (PCM byte-order conversion, MediaCodec synchronous-mode end-of-stream signaling, per-album rollup transaction discipline, RG dB ↔ LUFS conversion convention). |
| `docs/sessions/2026-05-22-session-16-track-d-handoff.md` | **Create**: Session 16 handoff for Track D-B (consumer-side gain) and/or D-C (settings UI + backfill). |

**Package convention:** existing `:audio:dsp` module uses `com.clayworks.kiln.audio.dsp.replaygain.*`; `:data:library` uses `com.clayworks.kiln.library.scan.*`; `:audio:playback` uses `com.clayworks.kiln.audio.playback.*`. New files follow these.

---

## Reference math — read before starting

### BS.1770-4 §5.3 album integrated LUFS

Given a set of per-track integrated LUFS values `L_1, L_2, ..., L_N` (each computed independently with the same EBU R128 dual-gated method), the album integrated LUFS is the **energy-weighted mean** of the per-track loudnesses (not arithmetic mean). The formula:

```
album_lufs = -0.691 + 10 * log10( mean_i ( 10^((L_i + 0.691) / 10) ) )
```

Interpretation: convert each per-track LUFS back to a linear loudness (`z`) value, average those `z` values arithmetically, convert the mean back to LUFS. The `-0.691` constant matches the BS.1770-4 LUFS scaling that `LoudnessGate.integratedLufs()` already uses (see `LoudnessGate.kt:126`).

Worked example (3 tracks at -23, -18, -28 LUFS):
```
z_1 = 10^((-23 + 0.691)/10) = 10^(-2.2309) ≈ 0.005876
z_2 = 10^((-18 + 0.691)/10) = 10^(-1.7309) ≈ 0.018580
z_3 = 10^((-28 + 0.691)/10) = 10^(-2.7309) ≈ 0.001859
mean_z = (0.005876 + 0.018580 + 0.001859) / 3 ≈ 0.008772
album_lufs = -0.691 + 10*log10(0.008772) = -0.691 + (-20.569) ≈ -21.260
```

So a 3-track album with track loudnesses (-23, -18, -28) has album loudness ≈ -21.26 LUFS — pulled toward the loudest (-18) because energy-weighted means dominate at the high end.

### ReplayGain v2 dB ↔ LUFS conversion

The `track.replay_gain_track_db` column stores ReplayGain-v2 dB (the **adjustment** value, not the loudness). Convention:

```
replay_gain_db = REFERENCE_LUFS - integrated_lufs
                = -18.0 - integrated_lufs    // RG v2 default reference
```

So integrated loudness -23 LUFS → gain +5.0 dB (boost the signal by 5 dB to reach -18); integrated loudness -14 LUFS → gain -4.0 dB (attenuate by 4 dB). `LoudnessAnalyzer.replayGainDb(targetLufs)` already returns this directly (`LoudnessAnalyzer.kt:51`).

For the album rollup we have to invert the per-track gains back to LUFS, aggregate, then invert again:
```
track_lufs_i = -18.0 - track_db_i              // per stored row
album_lufs   = albumIntegratedLufs(track_lufs_list)
album_db     = -18.0 - album_lufs              // store back to DB
```

### Peak storage convention

`replay_gain_track_peak` and `replay_gain_album_peak` are stored as **linear** sample amplitudes per ReplayGain v2 (e.g., `0.999847`). Our `LoudnessAnalyzer.truePeakDbtp()` returns **dBTP**. Conversion at persist time:

```
linear_peak = 10^(dBTP / 20)
```

So `dBTP = 0` → `linear = 1.0`; `dBTP = -0.5` → `linear ≈ 0.9441`; `dBTP = +1.0` → `linear ≈ 1.122` (inter-sample peaks legitimately exceed 1.0 on resampled / clipped content). For album peak:

```
album_peak_linear = max(track_peak_linear values for that album)
```

This is a one-liner — no separate aggregator function needed. The album-rollup query (`selectTrackReplayGainForAlbum`) returns linear peaks; the runner takes `maxOf { it }` over the list.

### PCM byte → Float conversion

The analyzer accepts `FloatArray` (samples in roughly `-1.0..+1.0` range). Decoded PCM lands as bytes whose layout depends on `SampleFormat`:

```
PCM_S16_LE → 2 bytes/sample, signed little-endian; sample_float = sample_int16 / 32768f
PCM_S24_LE → 3 bytes/sample, signed little-endian (sign-extended into the high byte slot); sample_float = sample_int24 / 8388608f
PCM_S32_LE → 4 bytes/sample, signed little-endian; sample_float = sample_int32 / 2147483648f
PCM_F32_LE → 4 bytes/sample, IEEE 754 float, ByteBuffer.getFloat(...)  (used directly)
```

Reading interleaved bytes into an interleaved float array preserves channel order. The `LoudnessAnalyzer.processSamples(interleaved, frames)` contract matches this shape directly (see `LoudnessAnalyzer.kt:24-32`).

---

## Task 1 — Album LUFS aggregator in `:audio:dsp`

**Goal:** Pure-math function exposing the BS.1770-4 §5.3 energy-weighted mean over per-track integrated LUFS. Lives next to `LoudnessAnalyzer` so it can reuse `AnalysisError` for the empty-input case.

**Files:**
- Create: `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/LoudnessAggregator.kt`
- Create: `audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/LoudnessAggregatorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/LoudnessAggregatorTest.kt`:

```kotlin
package com.clayworks.kiln.audio.dsp.replaygain

import arrow.core.Either
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LoudnessAggregatorTest {

    @Test
    fun `empty list returns InsufficientAudio`() {
        when (val result = albumIntegratedLufs(emptyList())) {
            is Either.Left -> assertEquals(AnalysisError.InsufficientAudio, result.value)
            is Either.Right -> fail("expected Left, got ${result.value}")
        }
    }

    @Test
    fun `single-track input passes through unchanged`() {
        when (val result = albumIntegratedLufs(listOf(-23.0))) {
            is Either.Left -> fail("expected Right, got ${result.value}")
            is Either.Right -> assertEquals(-23.0, result.value, 1e-9)
        }
    }

    @Test
    fun `three identical tracks return same LUFS`() {
        when (val result = albumIntegratedLufs(listOf(-18.0, -18.0, -18.0))) {
            is Either.Left -> fail("expected Right, got ${result.value}")
            is Either.Right -> assertEquals(-18.0, result.value, 1e-9)
        }
    }

    @Test
    fun `BS_1770-4 reference vector -23, -18, -28 yields ~ -21_26 LUFS`() {
        // Hand-computed in the plan's reference-math section.
        when (val result = albumIntegratedLufs(listOf(-23.0, -18.0, -28.0))) {
            is Either.Left -> fail("expected Right, got ${result.value}")
            is Either.Right -> {
                val expected = -21.26
                assertTrue(
                    abs(result.value - expected) < 0.01,
                    "expected ~$expected, got ${result.value}",
                )
            }
        }
    }

    @Test
    fun `energy-weighted mean is biased toward louder tracks`() {
        // One loud track (-12) and one quiet track (-30) should land closer to
        // the loud value than the arithmetic mean (-21) would predict.
        when (val result = albumIntegratedLufs(listOf(-12.0, -30.0))) {
            is Either.Left -> fail("expected Right, got ${result.value}")
            is Either.Right -> {
                val arithmeticMean = -21.0
                assertTrue(
                    result.value > arithmeticMean,
                    "energy-weighted result ${result.value} should be > arithmetic mean $arithmeticMean " +
                        "(closer to the louder track at -12 LUFS)",
                )
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:dsp:desktopTest --tests "com.clayworks.kiln.audio.dsp.replaygain.LoudnessAggregatorTest"`
Expected: FAIL with "unresolved reference: albumIntegratedLufs".

- [ ] **Step 3: Write minimal implementation**

Create `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/LoudnessAggregator.kt`:

```kotlin
package com.clayworks.kiln.audio.dsp.replaygain

import arrow.core.Either
import kotlin.math.ln
import kotlin.math.log10

/**
 * Compute album-level integrated LUFS from per-track integrated LUFS values
 * per BS.1770-4 §5.3.
 *
 * Album LUFS is the **energy-weighted** mean of the per-track integrated
 * loudness values. Each per-track LUFS is converted back to a linear "z"
 * (loudness energy) value, the z values are arithmetically averaged, and
 * the mean is converted back to LUFS. Loud tracks dominate the result, which
 * is why a 3-track album with (-23, -18, -28) LUFS aggregates to ≈ -21.26
 * (not the arithmetic mean -23.0).
 *
 * @param trackLufsValues per-track integrated LUFS, computed via
 *   [LoudnessAnalyzer.integratedLufs] on each track independently.
 * @return [Either.Right] album LUFS for a non-empty input;
 *   [Either.Left] [AnalysisError.InsufficientAudio] for an empty list.
 */
fun albumIntegratedLufs(trackLufsValues: List<Double>): Either<AnalysisError, Double> {
    if (trackLufsValues.isEmpty()) return Either.Left(AnalysisError.InsufficientAudio)
    var sumZ = 0.0
    for (lufs in trackLufsValues) {
        sumZ += pow10((lufs + 0.691) / 10.0)
    }
    val meanZ = sumZ / trackLufsValues.size
    return Either.Right(-0.691 + 10.0 * log10(meanZ))
}

private fun pow10(x: Double): Double = kotlin.math.exp(x * ln(10.0))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :audio:dsp:desktopTest --tests "com.clayworks.kiln.audio.dsp.replaygain.LoudnessAggregatorTest"`
Expected: PASS — 5 of 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/LoudnessAggregator.kt audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/LoudnessAggregatorTest.kt
git commit -m "feat(audio:dsp): album-level integrated LUFS aggregator (BS.1770-4 §5.3)

Adds albumIntegratedLufs(trackLufsValues): Either<AnalysisError, Double>
implementing the energy-weighted mean over per-track integrated LUFS values.
Reuses the existing AnalysisError.InsufficientAudio for the empty-input case.

Tests cover: empty input, single-track passthrough, three identical tracks,
the hand-computed BS.1770-4 reference vector (-23, -18, -28 → -21.26), and
the energy-bias invariant (result > arithmetic mean for mixed loud/quiet).

Phase 2a Track D-A wrap-up — Task 1."
```

---

## Task 2 — SQLDelight queries for analyzer pass

**Goal:** Add the queries the runner needs: worklist enumeration, per-track persist, per-album rollup. All on the existing `track` table — no schema change. (The RG columns already exist; see `track.sq:34-37`.)

**Files:**
- Modify: `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/track.sq`

- [ ] **Step 1: Read current `track.sq` to anchor insertion**

The file exists at `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/track.sq`. Inspect it once so you know the existing query ordering (helpful for diff readability). The new queries go after `softDelete:` (around line 230) and before any imports (there are none — just labeled queries).

- [ ] **Step 2: Append queries**

At the end of `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/track.sq` (after `reactivate:` is the natural anchor), append:

```sql
-- Phase 2a Track D-A wrap-up: ReplayGain analyzer worklist + persistence.
-- The analyzer runs out-of-band of the main scanner (perf math says ≥17 h
-- for 40k tracks first-pass; a separate trigger from settings UI / background
-- job is the right surface). All queries assume `track.deleted_at_ms IS NULL`
-- — analyzer should not touch soft-deleted rows.

-- Worklist enumeration. Paginated so the runner can yield + report progress.
-- Includes album_id so the runner can group for per-album rollup later.
selectTracksMissingReplayGain:
SELECT id, file_path, codec, sample_rate_hz, bit_depth, channels, album_id
FROM track
WHERE deleted_at_ms IS NULL
  AND replay_gain_track_db IS NULL
ORDER BY id
LIMIT :pageSize OFFSET :pageOffset;

-- Count for the worklist — used for progress reporting in TrackAnalysisRunner.
countTracksMissingReplayGain:
SELECT COUNT(*) FROM track
WHERE deleted_at_ms IS NULL
  AND replay_gain_track_db IS NULL;

-- Per-track persist. `peak` is a linear sample amplitude (RG v2 convention);
-- caller converts from dBTP → linear before invoking.
updateTrackReplayGain:
UPDATE track
SET replay_gain_track_db = :db,
    replay_gain_track_peak = :peak
WHERE id = :id;

-- Distinct album_ids with at least one analyzed track but no album-level
-- aggregate yet. Used at the end of an analyzer pass to drive the rollup.
selectAlbumsForAggregation:
SELECT DISTINCT album_id
FROM track
WHERE album_id IS NOT NULL
  AND deleted_at_ms IS NULL
  AND replay_gain_track_db IS NOT NULL
  AND replay_gain_album_db IS NULL;

-- Per-album rollup input: track-level gains + peaks for one album.
-- The rollup math (energy-weighted mean of LUFS, max of linear peaks)
-- happens in Kotlin in TrackAnalysisRunner.
selectTrackReplayGainForAlbum:
SELECT replay_gain_track_db, replay_gain_track_peak
FROM track
WHERE album_id = :albumId
  AND deleted_at_ms IS NULL
  AND replay_gain_track_db IS NOT NULL;

-- Apply the computed album-level values to all live tracks of the album.
-- `peak` is a linear sample amplitude per RG v2.
updateAlbumReplayGainForAlbum:
UPDATE track
SET replay_gain_album_db = :db,
    replay_gain_album_peak = :peak
WHERE album_id = :albumId
  AND deleted_at_ms IS NULL;
```

- [ ] **Step 3: Generate SQLDelight code and verify compilation**

Run: `./gradlew :data:library:generateCommonMainKilnDatabaseInterface :data:library:compileKotlinDesktop`
Expected: PASS — SQLDelight parses the new queries and regenerates the typed interfaces. New methods on `KilnDatabase.trackQueries` include `selectTracksMissingReplayGain`, `countTracksMissingReplayGain`, `updateTrackReplayGain`, `selectAlbumsForAggregation`, `selectTrackReplayGainForAlbum`, `updateAlbumReplayGainForAlbum`.

If `verifyCommonMainKilnDatabaseMigration` runs as part of build and complains: these are query additions, not schema changes, so no `.sqm` migration file is needed. The current schema `.db` snapshot is unchanged.

- [ ] **Step 4: Run the canonical desktop test suite to confirm no regression**

Run: `./gradlew :data:library:desktopTest`
Expected: PASS — existing 30+ tests in `:data:library:desktopTest` all green. New queries are not yet exercised; they'll get coverage in Task 4.

- [ ] **Step 5: Commit**

```bash
git add data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/track.sq
git commit -m "feat(data:library): SQLDelight queries for ReplayGain analyzer runner

Adds 6 new queries on the track table for the analyzer worklist + per-track
+ per-album persist:

  - selectTracksMissingReplayGain (paginated worklist)
  - countTracksMissingReplayGain (progress reporting)
  - updateTrackReplayGain (per-track persist; peak is linear per RG v2)
  - selectAlbumsForAggregation (distinct album_ids needing rollup)
  - selectTrackReplayGainForAlbum (rollup input)
  - updateAlbumReplayGainForAlbum (rollup persist)

No schema change — replay_gain_* columns already exist. No .sqm migration
required.

Phase 2a Track D-A wrap-up — Task 2."
```

---

## Task 3 — `TrackAnalyzer` port + DTOs + sealed errors

**Goal:** Define the small commonMain surface that the runner consumes and platform implementations satisfy. No tests yet — types alone; coverage comes from Task 4 (runner tests with a fake analyzer).

**Files:**
- Create: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalyzer.kt`
- Modify: `data/library/build.gradle.kts` (add `:audio:dsp` to commonMain deps so the runner can import `AnalysisError`)

- [ ] **Step 1: Add module dep**

Modify `data/library/build.gradle.kts`. Find the `commonMain.dependencies { ... }` block (around lines 31-42) and add `implementation(project(":audio:dsp"))` next to the other `implementation` lines:

```kotlin
        commonMain.dependencies {
            implementation(libs.bundles.sqldelight.common)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
            implementation(project(":audio:dsp"))  // LoudnessAggregator + AnalysisError for TrackAnalysisRunner
            // arrow.core is `api` because Either<SourceError, X> + Either<ScanError, X>
            // are part of the public surface of MusicSource and LibraryScanner.
            // App-module consumers need to be able to pattern-match Either.Right /
            // Either.Left when invoking these. Was implementation; H7 surfaced the
            // gap when MainActivity / Main.kt tried to consume scanIncremental().
            api(libs.arrow.core)
        }
```

- [ ] **Step 2: Create `TrackAnalyzer.kt`**

Create `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalyzer.kt`:

```kotlin
package com.clayworks.kiln.library.scan

import arrow.core.Either

/**
 * Per-track loudness analyzer port. One platform impl decodes the file's
 * PCM samples and feeds them into the [com.clayworks.kiln.audio.dsp.replaygain.LoudnessAnalyzer]
 * from `:audio:dsp`, returning the integrated LUFS + true-peak result.
 *
 * Implementations:
 *  - Desktop: `:audio:playback/desktopMain` `JvmFlacTrackAnalyzer` — FLAC only via JNA libFLAC.
 *  - Android: `:audio:playback/androidMain` `AndroidMediaTrackAnalyzer` — MediaExtractor / MediaCodec.
 *
 * The orchestrator is [TrackAnalysisRunner]; this interface is the seam.
 */
interface TrackAnalyzer {
    /**
     * Analyze one track end-to-end. The implementation opens the file at
     * [filePath] using a codec-appropriate decoder, streams PCM into a
     * fresh `LoudnessAnalyzer`, and returns the integrated LUFS + dBTP peak.
     *
     * @param filePath platform-specific file path or URI (filesystem path on
     *   desktop, `content://` URI on Android SAF). Implementations resolve
     *   per platform.
     * @param codec the `track.codec` column value as set by the scanner
     *   ("FLAC", "MP3", "WAV", "AAC", "OGG_VORBIS", "OGG_OPUS", "ALAC",
     *   "UNKNOWN"). Implementations decide what they support; unsupported
     *   codecs return `TrackAnalysisError.CodecUnsupported`.
     *
     * @return [Either.Right] [TrackLoudness] on success;
     *   [Either.Left] [TrackAnalysisError] otherwise.
     */
    suspend fun analyze(filePath: String, codec: String): Either<TrackAnalysisError, TrackLoudness>
}

/**
 * Successful analysis result. Caller (TrackAnalysisRunner) converts to
 * ReplayGain-v2 dB before persisting:
 *   replayGainDb = -18.0 - integratedLufs   (RG v2 default reference)
 *   peakLinear   = 10^(truePeakDbtp / 20)   (RG v2 linear amplitude convention)
 */
data class TrackLoudness(
    val integratedLufs: Double,
    val truePeakDbtp: Double,
)

/** Reasons the analyzer can fail to produce a result. */
sealed interface TrackAnalysisError {
    /** Codec value not supported by this analyzer (e.g., MP3 on desktop FLAC-only impl). */
    data class CodecUnsupported(val codec: String) : TrackAnalysisError

    /** Decoder/extractor reported an I/O or format error before producing any samples. */
    data class DecodeFailed(val message: String) : TrackAnalysisError

    /** Decoder ran but the LUFS computation rejected the result (silent file, < 3s of audio). */
    data class AnalysisFailed(val reason: String) : TrackAnalysisError
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :data:library:compileKotlinDesktop`
Expected: PASS — file compiles; `AnalysisError` import from `:audio:dsp` resolves.

- [ ] **Step 4: Commit**

```bash
git add data/library/build.gradle.kts data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalyzer.kt
git commit -m "feat(data:library): TrackAnalyzer port + DTOs + sealed errors

Defines the seam between TrackAnalysisRunner (orchestrator, lands Task 4)
and platform analyzer impls (JvmFlacTrackAnalyzer on desktop, Task 5; and
AndroidMediaTrackAnalyzer, Task 6).

  - TrackAnalyzer interface: suspend analyze(filePath, codec) -> Either
  - TrackLoudness data class: integratedLufs + truePeakDbtp
  - TrackAnalysisError sealed interface: CodecUnsupported, DecodeFailed, AnalysisFailed

Adds :audio:dsp to :data:library commonMain deps so the runner (Task 4) can
import LoudnessAggregator + AnalysisError.

Phase 2a Track D-A wrap-up — Task 3."
```

---

## Task 4 — `TrackAnalysisRunner` orchestrator

**Goal:** Implement the runner that walks the worklist, calls the analyzer for each track, persists per-track gains and peaks, then rolls up per-album. Tests use TestDb + a `FakeTrackAnalyzer` returning deterministic values.

**Files:**
- Create: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt`
- Create: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/AnalysisPassResult.kt`
- Create: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt`
- Create: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/FakeTrackAnalyzer.kt`

- [ ] **Step 1: Write the result DTO**

Create `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/AnalysisPassResult.kt`:

```kotlin
package com.clayworks.kiln.library.scan

/** Summary of a [TrackAnalysisRunner] pass. */
data class AnalysisPassResult(
    /** Tracks whose analyzer returned a result and got persisted. */
    val tracksAnalyzed: Int,

    /** Tracks the analyzer rejected (CodecUnsupported / DecodeFailed / AnalysisFailed).
     *  Per-track error details are logged, not surfaced here. */
    val tracksSkipped: Int,

    /** Albums that got a fresh per-album rollup this pass. */
    val albumsAggregated: Int,

    /** Wall-clock duration of the pass, in milliseconds. */
    val durationMs: Long,
)
```

- [ ] **Step 2: Write the failing runner test**

Create `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/FakeTrackAnalyzer.kt`:

```kotlin
package com.clayworks.kiln.library.scan

import arrow.core.Either

/**
 * Test double for [TrackAnalyzer]. Maps `filePath -> result` deterministically;
 * unknown paths return [TrackAnalysisError.DecodeFailed].
 */
class FakeTrackAnalyzer(
    private val results: Map<String, Either<TrackAnalysisError, TrackLoudness>>,
) : TrackAnalyzer {
    val analyzed: MutableList<String> = mutableListOf()
    override suspend fun analyze(filePath: String, codec: String): Either<TrackAnalysisError, TrackLoudness> {
        analyzed += filePath
        return results[filePath]
            ?: Either.Left(TrackAnalysisError.DecodeFailed("no fake result for $filePath"))
    }
}
```

Create `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt`:

```kotlin
package com.clayworks.kiln.library.scan

import arrow.core.Either
import com.clayworks.kiln.library.source.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackAnalysisRunnerTest {

    private val testDb = TestDb()

    @AfterTest fun tearDown() = testDb.close()

    @Test
    fun `empty library returns zero counts`() = runBlocking {
        val analyzer = FakeTrackAnalyzer(emptyMap())
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined)
        val result = runner.runOnce()
        assertEquals(0, result.tracksAnalyzed)
        assertEquals(0, result.tracksSkipped)
        assertEquals(0, result.albumsAggregated)
        assertTrue(result.durationMs >= 0L)
    }

    @Test
    fun `three tracks one album persists per-track and per-album values`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val t1 = testDb.insertTrack(artistId, albumId, "T1", filePath = "/test/t1.flac")
        val t2 = testDb.insertTrack(artistId, albumId, "T2", filePath = "/test/t2.flac")
        val t3 = testDb.insertTrack(artistId, albumId, "T3", filePath = "/test/t3.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/t1.flac" to Either.Right(TrackLoudness(integratedLufs = -23.0, truePeakDbtp = -1.0)),
            "/test/t2.flac" to Either.Right(TrackLoudness(integratedLufs = -18.0, truePeakDbtp =  0.0)),
            "/test/t3.flac" to Either.Right(TrackLoudness(integratedLufs = -28.0, truePeakDbtp = -3.0)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined)

        val result = runner.runOnce()
        assertEquals(3, result.tracksAnalyzed)
        assertEquals(0, result.tracksSkipped)
        assertEquals(1, result.albumsAggregated)

        // Per-track: replay_gain_track_db = -18.0 - integratedLufs.
        val row1 = testDb.db.trackQueries.selectById(t1).executeAsOne()
        assertNotNull(row1.replay_gain_track_db)
        assertEquals(5.0, row1.replay_gain_track_db!!, 1e-9)        // -18 - (-23) = +5
        assertEquals(0.0, row1.replay_gain_album_db!! - row1.replay_gain_album_db!!, 0.0) // sanity
        // Peak linear: 10^(-1/20) ≈ 0.8913
        assertNotNull(row1.replay_gain_track_peak)
        assertTrue(abs(row1.replay_gain_track_peak!! - 0.8913) < 1e-3)

        // Per-album: album LUFS for (-23,-18,-28) ≈ -21.26 → album_db = -18 - (-21.26) = +3.26.
        val row2 = testDb.db.trackQueries.selectById(t2).executeAsOne()
        assertNotNull(row2.replay_gain_album_db)
        assertTrue(
            abs(row2.replay_gain_album_db!! - 3.26) < 0.05,
            "expected ~3.26, got ${row2.replay_gain_album_db}",
        )
        // Album peak linear = max of track peak linears.
        // t2 had dBTP = 0 → linear = 1.0; biggest of the three. Album peak = 1.0.
        assertNotNull(row2.replay_gain_album_peak)
        assertTrue(abs(row2.replay_gain_album_peak!! - 1.0) < 1e-6)
    }

    @Test
    fun `two albums each get an independent rollup`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumA = testDb.insertAlbum(artistId, "Album A")
        val albumB = testDb.insertAlbum(artistId, "Album B")
        val ta = testDb.insertTrack(artistId, albumA, "TA", filePath = "/test/a.flac")
        val tb = testDb.insertTrack(artistId, albumB, "TB", filePath = "/test/b.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/a.flac" to Either.Right(TrackLoudness(-23.0, -2.0)),
            "/test/b.flac" to Either.Right(TrackLoudness(-15.0, +1.0)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined)
        val result = runner.runOnce()

        assertEquals(2, result.tracksAnalyzed)
        assertEquals(2, result.albumsAggregated)

        // Single-track albums: album_db == track_db.
        val rowA = testDb.db.trackQueries.selectById(ta).executeAsOne()
        assertEquals(rowA.replay_gain_track_db, rowA.replay_gain_album_db)
        val rowB = testDb.db.trackQueries.selectById(tb).executeAsOne()
        assertEquals(rowB.replay_gain_track_db, rowB.replay_gain_album_db)

        // Different albums got different aggregates (sanity).
        assertTrue(rowA.replay_gain_album_db != rowB.replay_gain_album_db)
    }

    @Test
    fun `analyzer Left for one track skips that row but persists others`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val good = testDb.insertTrack(artistId, albumId, "Good", filePath = "/test/good.flac")
        val bad  = testDb.insertTrack(artistId, albumId, "Bad",  filePath = "/test/bad.mp3")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/good.flac" to Either.Right(TrackLoudness(-18.0, -0.5)),
            "/test/bad.mp3"   to Either.Left(TrackAnalysisError.CodecUnsupported("MP3")),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined)
        val result = runner.runOnce()

        assertEquals(1, result.tracksAnalyzed)
        assertEquals(1, result.tracksSkipped)
        assertEquals(1, result.albumsAggregated)  // good's album still aggregates (one track is enough)

        // Good was persisted.
        val rowGood = testDb.db.trackQueries.selectById(good).executeAsOne()
        assertNotNull(rowGood.replay_gain_track_db)
        // Bad was not.
        val rowBad = testDb.db.trackQueries.selectById(bad).executeAsOne()
        assertNull(rowBad.replay_gain_track_db)
    }

    @Test
    fun `tracks without an album_id are analyzed but skip rollup`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val orphan = testDb.insertTrack(artistId, albumId = null, title = "Orphan", filePath = "/test/orph.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/orph.flac" to Either.Right(TrackLoudness(-20.0, -1.5)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined)
        val result = runner.runOnce()

        assertEquals(1, result.tracksAnalyzed)
        assertEquals(0, result.albumsAggregated)

        val row = testDb.db.trackQueries.selectById(orphan).executeAsOne()
        assertNotNull(row.replay_gain_track_db)
        assertNull(row.replay_gain_album_db)  // no album → no rollup
    }

    @Test
    fun `already-populated tracks are not re-analyzed`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val preExisting = testDb.insertTrack(
            artistId, albumId, "Pre",
            filePath = "/test/pre.flac",
            replayGainTrackDb = 4.2,
            replayGainTrackPeak = 0.95,
        )
        val fresh = testDb.insertTrack(artistId, albumId, "Fresh", filePath = "/test/fresh.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/fresh.flac" to Either.Right(TrackLoudness(-22.0, -1.0)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined)
        val result = runner.runOnce()

        assertEquals(1, result.tracksAnalyzed)
        assertEquals(listOf("/test/fresh.flac"), analyzer.analyzed)

        // Pre-existing track's stored values are untouched.
        val rowPre = testDb.db.trackQueries.selectById(preExisting).executeAsOne()
        assertEquals(4.2, rowPre.replay_gain_track_db!!, 1e-9)
        assertEquals(0.95, rowPre.replay_gain_track_peak!!, 1e-9)
    }
}
```

NOTE: `TestDb.insertTrack` already accepts a `replayGainTrackDb`/`replayGainTrackPeak` parameter pair via its existing helper signature. If it does NOT (verify in `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/source/TestDb.kt`), extend the helper to accept them with `null` defaults — this is a one-line widening of an existing helper, not a new feature. The widening commit message should be folded into Task 4.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.TrackAnalysisRunnerTest"`
Expected: FAIL with "unresolved reference: TrackAnalysisRunner".

- [ ] **Step 4: Write the runner**

Create `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt`:

```kotlin
package com.clayworks.kiln.library.scan

import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.replaygain.albumIntegratedLufs
import com.clayworks.kiln.data.library.db.KilnDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.math.pow

private val log = Logger.withTag("TrackAnalysisRunner")

private const val REFERENCE_LUFS = -18.0
private const val PAGE_SIZE = 100L

/**
 * Orchestrator for the ReplayGain analyzer pass.
 *
 * Walks `selectTracksMissingReplayGain` in pages, calls [analyzer] for each
 * track, persists per-track gain + peak, then runs the per-album rollup over
 * any album whose `replay_gain_album_db` is NULL but at least one track has
 * been freshly analyzed.
 *
 * The runner is deliberately **not** invoked automatically from
 * `LibraryScanner.scanIncremental()` / `scanFull()`. Perf math (≈1.5-10 s
 * per track × 40k tracks = 17-100 h) makes inline invocation user-hostile;
 * Track D-C's backfill UI is the user-explicit trigger.
 *
 * Concurrency: per-track `analyzer.analyze(...)` calls run sequentially on
 * [ioDispatcher]. The implementation does NOT parallelize — desktop FLAC
 * decoding via libFLAC is already CPU-bound on a single core, and the
 * runner's primary use case is background batch where a tight memory
 * footprint matters more than wall-clock throughput. A future iteration
 * can add a parallelism setting if perf requires it.
 *
 * Worklist-advance discipline: analyzed rows drop out of the worklist
 * naturally (their `replay_gain_track_db` is no longer NULL). Skipped rows
 * (analyzer returned Left) STILL appear in subsequent queries; we advance
 * `pageOffset` by the running count of skipped rows so the loop scrolls
 * past them. Without this offset advance, an all-skipping analyzer would
 * re-query page 0 forever.
 *
 * Atomicity: each per-track persist is its own write (no enclosing
 * transaction). The per-album rollup IS wrapped in a transaction so all
 * tracks of an album receive the same album-level values atomically.
 */
class TrackAnalysisRunner(
    private val db: KilnDatabase,
    private val analyzer: TrackAnalyzer,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Run one full pass: analyze every track with NULL `replay_gain_track_db`,
     * then aggregate any albums that gained at least one analyzed track.
     *
     * @return [AnalysisPassResult] with per-track + per-album counts.
     */
    suspend fun runOnce(): AnalysisPassResult = withContext(ioDispatcher) {
        val startedMs = System.currentTimeMillis()
        var analyzed = 0
        var skipped = 0
        var skippedTotal = 0L

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
                    }
                }
            }
            skippedTotal += pageSkippedDelta

            // Termination guard: every row on the page was skipped AND we
            // got a short page (so there's nothing left in the worklist past
            // the skipped tail). Without this we'd loop forever on a fully
            // failing analyzer with > PAGE_SIZE tracks to skip.
            if (pageSkippedDelta == page.size && page.size < PAGE_SIZE.toInt()) break
        }

        // Per-album rollup. selectAlbumsForAggregation only returns album_ids
        // whose `replay_gain_album_db IS NULL` AND at least one track was
        // analyzed — so it's idempotent for albums that already aggregated.
        val albumIds = db.trackQueries.selectAlbumsForAggregation().executeAsList()
        var albumsAggregated = 0
        for (albumId in albumIds) {
            if (albumId == null) continue  // safety: query already filters IS NOT NULL
            val perTrack = db.trackQueries.selectTrackReplayGainForAlbum(albumId).executeAsList()
            if (perTrack.isEmpty()) continue

            val trackLufsList = perTrack.mapNotNull { row ->
                row.replay_gain_track_db?.let { REFERENCE_LUFS - it }
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

    private fun dbtpToLinear(dbtp: Double): Double = 10.0.pow(dbtp / 20.0)
}
```

Notes on the SQLDelight-generated row types:
- `selectTracksMissingReplayGain` returns a custom row class with `id: Long`, `file_path: String`, `codec: String`, `sample_rate_hz: Long`, `bit_depth: Long?`, `channels: Long`, `album_id: Long?` — drawn from the SELECT projection.
- `selectAlbumsForAggregation` returns `Long?` (the bare `album_id` column with nullability preserved by SQLDelight — the WHERE filter doesn't narrow). The null guard at loop top is defensive.
- `selectTrackReplayGainForAlbum` returns a custom row class. The `replay_gain_track_db` column may or may not be narrowed to non-null depending on SQLDelight version — `mapNotNull { it.replay_gain_track_db?.let { ... } }` accepts both cases.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.TrackAnalysisRunnerTest"`
Expected: PASS — 6 of 6 tests green.

If `TestDb.insertTrack` doesn't currently accept `replayGainTrackDb` / `replayGainTrackPeak`, widen the helper to accept them with `null` defaults; the widening lives in `TestDb.kt`, added in the same commit as Task 4. Test #6 (`already-populated tracks are not re-analyzed`) depends on this.

- [ ] **Step 6: Commit**

```bash
git add data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/AnalysisPassResult.kt data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunner.kt data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/TrackAnalysisRunnerTest.kt data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/FakeTrackAnalyzer.kt data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/source/TestDb.kt
git commit -m "feat(data:library): TrackAnalysisRunner orchestrator + tests

Walks selectTracksMissingReplayGain in pages, calls TrackAnalyzer per row,
persists track-level gain + linear peak, then walks selectAlbumsForAggregation
to apply per-album rollup (energy-weighted mean LUFS via albumIntegratedLufs,
max of linear track peaks).

Storage convention:
  - replay_gain_track_db = -18.0 - integratedLufs (RG v2 reference)
  - replay_gain_track_peak = 10^(dBTP / 20) (linear, RG v2 convention)

Concurrency: sequential per-track on ioDispatcher; per-album rollup wrapped
in db.transaction for atomicity.

Tests cover: empty library, single-album 3-track rollup with hand-computed
target gains/peaks, two-album independent rollups, analyzer Left for one
track skips that row, tracks without album_id skip rollup, already-populated
tracks are not re-analyzed.

Phase 2a Track D-A wrap-up — Task 4."
```

---

## Task 5 — Desktop FLAC `TrackAnalyzer` impl

**Goal:** Wire the existing `:audio:playback/desktopMain` FLAC decoder (`createJvmFlacDecoder()`) to `:audio:dsp`'s `LoudnessAnalyzer` via a small adapter. Non-FLAC codecs return `TrackAnalysisError.CodecUnsupported` for this session (Phase 2a polish work will add MP3/WAV/etc).

**Files:**
- Modify: `audio/playback/build.gradle.kts` (add `:audio:dsp` to commonMain deps so the impl can `import com.clayworks.kiln.audio.dsp.replaygain.*`)
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacTrackAnalyzer.kt`
- Create: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JvmFlacTrackAnalyzerTest.kt`

- [ ] **Step 1: Add module dep**

Modify `audio/playback/build.gradle.kts`. Find the `commonMain.dependencies { ... }` block (around lines 15-20) and add `implementation(project(":audio:dsp"))`:

```kotlin
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.arrow.core)  // Either<DecoderError, DecodedStream> in Decoder
            implementation(project(":data:library"))  // MediaItem type used in Queue + PlatformPlayer
            implementation(project(":audio:dsp"))  // LoudnessAnalyzer for TrackAnalyzer impls
        }
```

- [ ] **Step 2: Write the failing test**

Create `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JvmFlacTrackAnalyzerTest.kt`:

```kotlin
package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.library.scan.TrackAnalysisError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmFlacTrackAnalyzerTest {

    private fun fixturePath(name: String): String {
        val url = JvmFlacTrackAnalyzerTest::class.java.getResource("/fixtures/$name")
            ?: error("Missing test fixture: /fixtures/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    @Test
    fun `non-FLAC codec returns CodecUnsupported`() = runBlocking {
        val analyzer = createJvmFlacTrackAnalyzer()
        when (val result = analyzer.analyze("/fake/path.mp3", codec = "MP3")) {
            is Either.Left -> {
                val err = result.value
                assertTrue(err is TrackAnalysisError.CodecUnsupported, "expected CodecUnsupported, got $err")
                assertEquals("MP3", (err as TrackAnalysisError.CodecUnsupported).codec)
            }
            is Either.Right -> fail("expected Left, got ${result.value}")
        }
    }

    @Test
    fun `analyze 16-bit 44_1kHz FLAC fixture returns either InsufficientAudio or a sane LUFS value`() = runBlocking {
        // The bundled fixture is 500 ms — below the 3-second EBU R128
        // minimum. The analyzer is correct to refuse it. We accept either:
        //   (a) Left InsufficientAudio (current LoudnessAnalyzer behavior), or
        //   (b) Right with a sane LUFS — would imply the analyzer was relaxed.
        // What we DO NOT want is DecodeFailed (decoder bug) or a NaN result.
        val analyzer = createJvmFlacTrackAnalyzer()
        val path = fixturePath("sine_440_stereo_16_44.flac")
        when (val result = analyzer.analyze(path, codec = "FLAC")) {
            is Either.Left -> {
                val err = result.value
                assertTrue(
                    err is TrackAnalysisError.AnalysisFailed,
                    "expected AnalysisFailed for short fixture, got $err",
                )
            }
            is Either.Right -> {
                val (lufs, dbtp) = result.value
                assertTrue(lufs.isFinite(), "LUFS must be finite, got $lufs")
                assertTrue(lufs < 0.0 && lufs > -80.0, "LUFS in plausible range, got $lufs")
                assertTrue(dbtp.isFinite(), "dBTP must be finite, got $dbtp")
                assertTrue(dbtp <= 6.0, "dBTP must be ≤ +6 for a 0 dBFS sine, got $dbtp")
            }
        }
    }

    @Test
    fun `analyze 24-bit 96kHz FLAC fixture returns finite results or AnalysisFailed`() = runBlocking {
        val analyzer = createJvmFlacTrackAnalyzer()
        val path = fixturePath("sine_440_stereo_24_96.flac")
        when (val result = analyzer.analyze(path, codec = "FLAC")) {
            is Either.Left -> {
                assertTrue(
                    result.value is TrackAnalysisError.AnalysisFailed,
                    "expected AnalysisFailed for short fixture, got ${result.value}",
                )
            }
            is Either.Right -> {
                assertTrue(result.value.integratedLufs.isFinite())
                assertTrue(result.value.truePeakDbtp.isFinite())
            }
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :audio:playback:desktopTest --tests "com.clayworks.kiln.audio.playback.JvmFlacTrackAnalyzerTest"`
Expected: FAIL with "unresolved reference: createJvmFlacTrackAnalyzer".

- [ ] **Step 4: Write the analyzer impl**

Create `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacTrackAnalyzer.kt`:

```kotlin
// Desktop FLAC TrackAnalyzer — opens the file via createJvmFlacDecoder(),
// streams interleaved PCM frames through LoudnessAnalyzer, and returns the
// integrated LUFS + true-peak result. FLAC-only for this session.
//
// Byte → Float conversion: depends on STREAMINFO bit depth (16/24/32 → signed
// little-endian; 32 float → IEEE 754). The decoder reports bit depth via
// DecodedAudioFormat.sampleFormat; we map per the SampleFormat enum.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.replaygain.AnalysisError
import com.clayworks.kiln.audio.dsp.replaygain.createLoudnessAnalyzer
import com.clayworks.kiln.library.scan.TrackAnalysisError
import com.clayworks.kiln.library.scan.TrackAnalyzer
import com.clayworks.kiln.library.scan.TrackLoudness
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.Playable
import com.clayworks.kiln.library.source.SourceId
import kotlinx.coroutines.flow.collect

private val log = Logger.withTag("JvmFlacTrackAnalyzer")

/** Public factory keeping the impl class internal. */
fun createJvmFlacTrackAnalyzer(): TrackAnalyzer = JvmFlacTrackAnalyzer(createJvmFlacDecoder())

internal class JvmFlacTrackAnalyzer(
    private val decoder: Decoder,
) : TrackAnalyzer {

    override suspend fun analyze(
        filePath: String,
        codec: String,
    ): Either<TrackAnalysisError, TrackLoudness> {
        if (codec.equals("FLAC", ignoreCase = true).not()) {
            return Either.Left(TrackAnalysisError.CodecUnsupported(codec))
        }

        // Build a Playable mirror of the file. The TrackAnalyzer interface only
        // gives us filePath + codec, so we synthesize the other fields. The
        // decoder reads STREAMINFO and overrides sampleRateHz / bitDepth /
        // channels from the file itself — the values we put on Playable here
        // are advisory only.
        val playable = Playable(
            itemId = ItemId("analyzer-${filePath.hashCode()}"),
            sourceId = SourceId("track-analysis"),
            uri = java.io.File(filePath).toURI().toString(),
            codec = AudioCodec.FLAC,
            sampleRateHz = 44_100,  // placeholder; STREAMINFO overrides
            bitDepth = 16,           // placeholder; STREAMINFO overrides
            channels = 2,            // placeholder; STREAMINFO overrides
            bitrateKbps = null,
            durationMs = 0L,
            replayGain = null,
        )

        val streamResult = decoder.open(playable)
        if (streamResult is Either.Left) {
            return Either.Left(TrackAnalysisError.DecodeFailed("decoder.open failed: ${streamResult.value}"))
        }
        val stream = (streamResult as Either.Right).value
        try {
            val fmt = stream.format
            val lufsAnalyzer = createLoudnessAnalyzer(fmt.sampleRateHz, fmt.channels)
            val converter = PcmByteToFloat(fmt.sampleFormat)

            stream.frames.collect { frame ->
                val floatBuf = converter.convert(frame.bytes, frame.byteCount)
                val frameCount = frame.sampleCount  // frame.sampleCount is per-channel frames already
                lufsAnalyzer.processSamples(floatBuf, frameCount)
            }

            val lufsEither = lufsAnalyzer.integratedLufs()
            val lufs = when (lufsEither) {
                is Either.Left -> when (lufsEither.value) {
                    AnalysisError.InsufficientAudio -> return Either.Left(
                        TrackAnalysisError.AnalysisFailed("InsufficientAudio: track < 3 s of audio"),
                    )
                    AnalysisError.NoGatedBlocks -> return Either.Left(
                        TrackAnalysisError.AnalysisFailed("NoGatedBlocks: track is silent"),
                    )
                }
                is Either.Right -> lufsEither.value
            }
            val peakDbtp = lufsAnalyzer.truePeakDbtp()
            return Either.Right(TrackLoudness(integratedLufs = lufs, truePeakDbtp = peakDbtp))
        } catch (e: Throwable) {
            log.w(e) { "analyze() failed for $filePath" }
            return Either.Left(TrackAnalysisError.DecodeFailed(e.message ?: "decoder exception"))
        } finally {
            stream.close()
        }
    }
}

/**
 * Converts a chunk of interleaved PCM bytes to interleaved floats in the
 * approximate range [-1.0, +1.0]. Reuses an output buffer that grows as
 * needed; callers must consume the returned FloatArray before the next call
 * (no defensive copy).
 */
internal class PcmByteToFloat(private val format: SampleFormat) {
    private var buf: FloatArray = FloatArray(0)

    fun convert(bytes: ByteArray, byteCount: Int): FloatArray {
        val sampleSize = when (format) {
            SampleFormat.PCM_S16_LE -> 2
            SampleFormat.PCM_S24_LE -> 3
            SampleFormat.PCM_S32_LE -> 4
            SampleFormat.PCM_F32_LE -> 4
        }
        val sampleCount = byteCount / sampleSize
        if (buf.size < sampleCount) buf = FloatArray(sampleCount)

        when (format) {
            SampleFormat.PCM_S16_LE -> {
                var i = 0
                var bi = 0
                while (i < sampleCount) {
                    val lo = bytes[bi].toInt() and 0xFF
                    val hi = bytes[bi + 1].toInt()
                    val s = ((hi shl 8) or lo).toShort().toInt()
                    buf[i] = s / 32768f
                    i++
                    bi += 2
                }
            }
            SampleFormat.PCM_S24_LE -> {
                var i = 0
                var bi = 0
                while (i < sampleCount) {
                    val b0 = bytes[bi].toInt() and 0xFF
                    val b1 = bytes[bi + 1].toInt() and 0xFF
                    val b2 = bytes[bi + 2].toInt()  // signed
                    val s = (b2 shl 16) or (b1 shl 8) or b0
                    buf[i] = s / 8388608f
                    i++
                    bi += 3
                }
            }
            SampleFormat.PCM_S32_LE -> {
                val bb = java.nio.ByteBuffer.wrap(bytes, 0, byteCount).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i < sampleCount) {
                    val s = bb.int
                    buf[i] = s / 2147483648f
                    i++
                }
            }
            SampleFormat.PCM_F32_LE -> {
                val bb = java.nio.ByteBuffer.wrap(bytes, 0, byteCount).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i < sampleCount) {
                    buf[i] = bb.float
                    i++
                }
            }
        }
        return buf
    }
}
```

NOTE on `AudioFrame.sampleCount`: read the existing definition at `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/Decoder.kt:53-95`. The field is documented in the source as the per-channel frame count (matching `LoudnessAnalyzer.processSamples`'s `frames` parameter). If implementation reveals it's actually total interleaved samples (per-channel × channels), the runner above needs `frame.sampleCount / fmt.channels`. Verify with a quick log and adjust the one call site.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :audio:playback:desktopTest --tests "com.clayworks.kiln.audio.playback.JvmFlacTrackAnalyzerTest"`
Expected: PASS — 3 of 3 tests green. (The fixtures are 500 ms each, below the 3-s minimum, so the LUFS analyzer correctly returns `InsufficientAudio` for those tracks — the test accepts both that outcome and a successful result with finite LUFS/dBTP.)

- [ ] **Step 6: Run the canonical build to catch any cross-module breakage**

Run: `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest`
Expected: BUILD SUCCESSFUL — both apps + load-bearing libs build; existing tests stay green.

- [ ] **Step 7: Commit**

```bash
git add audio/playback/build.gradle.kts audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacTrackAnalyzer.kt audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JvmFlacTrackAnalyzerTest.kt
git commit -m "feat(audio:playback): desktop FLAC TrackAnalyzer impl

Wraps createJvmFlacDecoder() + createLoudnessAnalyzer() into a TrackAnalyzer
that decodes a FLAC file end-to-end, converts bytes → FloatArray per
SampleFormat (S16/S24/S32 little-endian, F32 IEEE 754), feeds the analyzer,
and returns the integrated LUFS + dBTP peak.

Non-FLAC codecs return TrackAnalysisError.CodecUnsupported — MP3/WAV/etc.
defer to a Phase 2a polish session.

Adds :audio:dsp dep to :audio:playback commonMain so platform impls can
import LoudnessAnalyzer + createLoudnessAnalyzer + AnalysisError.

Phase 2a Track D-A wrap-up — Task 5."
```

---

## Task 6 — Android `TrackAnalyzer` impl (MediaExtractor + MediaCodec)

**Goal:** Android counterpart to Task 5 — uses framework APIs to decode any device-supported codec to PCM, then feeds the analyzer. No unit-test coverage (MediaCodec doesn't mock cleanly under Robolectric); validation is deferred to a manual Pixel smoke test after Track D-C lands its trigger UI.

**Files:**
- Create: `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/AndroidMediaTrackAnalyzer.kt`

- [ ] **Step 1: Write the analyzer impl**

Create `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/AndroidMediaTrackAnalyzer.kt`:

```kotlin
// Android TrackAnalyzer — uses MediaExtractor + MediaCodec synchronous-mode
// decoding to convert any device-supported audio codec to PCM, then feeds
// LoudnessAnalyzer for an EBU R128 / BS.1770-4 result.
//
// Why synchronous mode (not callback async): we want a simple end-to-end
// pump driven by a coroutine; the test surface is "give me a file, return
// the LUFS/dBTP". Real-time playback uses async (Media3 ExoPlayer); this
// analyzer is batch-style and doesn't need event-driven flow control.
//
// File-path semantics: filePath may be a filesystem path (e.g.,
// /storage/emulated/0/Music/song.flac from MediaStore.DATA) OR a content://
// URI from a SAF tree. MediaExtractor.setDataSource(Context, Uri, null) handles
// both — pass via Uri.parse(filePath) and the Android framework resolves.

package com.clayworks.kiln.audio.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.replaygain.AnalysisError
import com.clayworks.kiln.audio.dsp.replaygain.createLoudnessAnalyzer
import com.clayworks.kiln.library.scan.TrackAnalysisError
import com.clayworks.kiln.library.scan.TrackAnalyzer
import com.clayworks.kiln.library.scan.TrackLoudness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val log = Logger.withTag("AndroidMediaTrackAnalyzer")

private const val DEQUEUE_TIMEOUT_US = 10_000L  // 10 ms

/** Public factory keeping the impl class internal. */
fun createAndroidMediaTrackAnalyzer(context: Context): TrackAnalyzer =
    AndroidMediaTrackAnalyzer(context.applicationContext)

internal class AndroidMediaTrackAnalyzer(
    private val appContext: Context,
) : TrackAnalyzer {

    override suspend fun analyze(
        filePath: String,
        codec: String,
    ): Either<TrackAnalysisError, TrackLoudness> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var mediaCodec: MediaCodec? = null
        try {
            // Resolve filePath → Uri. Both filesystem paths and content:// URIs work.
            extractor.setDataSource(appContext, Uri.parse(filePath), null)

            // Find the first audio track.
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext Either.Left(
                TrackAnalysisError.DecodeFailed("no audio track found"),
            )
            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return@withContext Either.Left(TrackAnalysisError.DecodeFailed("no MIME type"))

            val sampleRateHz = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channels !in 1..2) {
                return@withContext Either.Left(
                    TrackAnalysisError.DecodeFailed("unsupported channel count: $channels (only 1 or 2 supported)"),
                )
            }

            mediaCodec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Throwable) {
                return@withContext Either.Left(
                    TrackAnalysisError.CodecUnsupported(codec),
                )
            }
            mediaCodec.configure(inputFormat, /* surface = */ null, /* crypto = */ null, /* flags = */ 0)
            mediaCodec.start()

            val analyzer = createLoudnessAnalyzer(sampleRateHz, channels)

            // PCM encoding from MediaCodec output format. ENCODING_PCM_FLOAT (4)
            // on Android M+, otherwise ENCODING_PCM_16BIT (2). The actual output
            // format isn't available until the first onOutputFormatChanged event
            // (or after dequeueOutputBuffer returns INFO_OUTPUT_FORMAT_CHANGED);
            // until then assume 16-bit and re-read on the change event.
            var pcmEncoding = 2  // ENCODING_PCM_16BIT
            var floatBuf = FloatArray(0)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIdx = mediaCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIdx >= 0) {
                        val inputBuffer = mediaCodec.getInputBuffer(inputIdx)
                            ?: error("getInputBuffer returned null for idx $inputIdx")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            mediaCodec.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            mediaCodec.queueInputBuffer(inputIdx, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIdx = mediaCodec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = mediaCodec.outputFormat
                        pcmEncoding = if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            2  // default 16-bit
                        }
                    }
                    outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit  // no output ready
                    outputIdx >= 0 -> {
                        val outputBuffer = mediaCodec.getOutputBuffer(outputIdx)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val sampleCount = when (pcmEncoding) {
                                4 -> bufferInfo.size / 4               // float
                                2 -> bufferInfo.size / 2               // 16-bit
                                else -> bufferInfo.size / 2            // assume 16-bit fallback
                            }
                            if (floatBuf.size < sampleCount) floatBuf = FloatArray(sampleCount)
                            val orderedBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            when (pcmEncoding) {
                                4 -> {
                                    for (i in 0 until sampleCount) floatBuf[i] = orderedBuffer.float
                                }
                                else -> {
                                    for (i in 0 until sampleCount) floatBuf[i] = orderedBuffer.short / 32768f
                                }
                            }
                            val frames = sampleCount / channels
                            if (frames > 0) analyzer.processSamples(floatBuf, frames)
                        }
                        mediaCodec.releaseOutputBuffer(outputIdx, /* render = */ false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val lufsEither = analyzer.integratedLufs()
            return@withContext when (lufsEither) {
                is Either.Left -> when (lufsEither.value) {
                    AnalysisError.InsufficientAudio -> Either.Left(
                        TrackAnalysisError.AnalysisFailed("InsufficientAudio: track < 3 s"),
                    )
                    AnalysisError.NoGatedBlocks -> Either.Left(
                        TrackAnalysisError.AnalysisFailed("NoGatedBlocks: track is silent"),
                    )
                }
                is Either.Right -> Either.Right(
                    TrackLoudness(
                        integratedLufs = lufsEither.value,
                        truePeakDbtp = analyzer.truePeakDbtp(),
                    ),
                )
            }
        } catch (e: Throwable) {
            log.w(e) { "analyze() failed for $filePath" }
            return@withContext Either.Left(TrackAnalysisError.DecodeFailed(e.message ?: "exception"))
        } finally {
            try {
                mediaCodec?.stop()
            } catch (_: Throwable) { /* swallow — release is what matters */ }
            try {
                mediaCodec?.release()
            } catch (_: Throwable) {}
            try {
                extractor.release()
            } catch (_: Throwable) {}
        }
    }
}
```

- [ ] **Step 2: Verify Android compilation**

Run: `./gradlew :audio:playback:assembleAndroidMain`
Expected: PASS — file compiles against Android SDK. No Robolectric test is added — MediaCodec/Extractor depend on native codec libs that aren't available under the JVM-only test runtime. The Android Pixel device test is the validation surface (manual, post-D-C).

Also run: `./gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/AndroidMediaTrackAnalyzer.kt
git commit -m "feat(audio:playback): Android MediaExtractor + MediaCodec TrackAnalyzer

Synchronous-mode decode loop:
  - MediaExtractor.setDataSource(context, Uri, null) handles both filesystem
    paths and SAF content:// URIs
  - MediaCodec.createDecoderByType(mime) for the audio MIME from the input
    track format; CodecUnsupported if no decoder available
  - Reads MediaFormat.KEY_PCM_ENCODING from outputFormat (ENCODING_PCM_FLOAT
    or ENCODING_PCM_16BIT) to drive the bytes → FloatArray conversion
  - Feeds the analyzer per dequeued output buffer; ends on BUFFER_FLAG_END_OF_STREAM
  - Cleans up MediaCodec.stop+release and MediaExtractor.release in finally

No unit tests (MediaCodec doesn't mock under Robolectric); manual Pixel
smoke test is deferred to Session 16 after Track D-C wires the backfill
trigger UI.

Phase 2a Track D-A wrap-up — Task 6."
```

---

## Task 7 — DI graph wiring (desktop + android)

**Goal:** Expose `TrackAnalysisRunner` from both `DesktopAppGraph` and `AndroidAppGraph` so Session 16's UI can construct a Compose component that invokes it via DI.

**Files:**
- Modify: `app-desktop/src/jvmMain/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt`
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/android/di/AndroidAppGraph.kt`

- [ ] **Step 1: Inspect both graphs**

Read both files end-to-end before editing. Look for: imports section, the `@Component abstract class XxxAppGraph` declaration, existing `@get:Provides` constructor params, and abstract members. The TrackAnalysisRunner provider should slot near the other domain-service providers (LibraryScanner, MusicSource, PlatformPlayer).

- [ ] **Step 2: Edit `DesktopAppGraph.kt`**

In `app-desktop/src/jvmMain/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt`, add the following:

1. Imports (near the other `com.clayworks.kiln.*` imports):
   ```kotlin
   import com.clayworks.kiln.audio.playback.createJvmFlacTrackAnalyzer
   import com.clayworks.kiln.library.scan.TrackAnalysisRunner
   import com.clayworks.kiln.library.scan.TrackAnalyzer
   ```

2. New `@get:Provides` constructor param (alongside the existing player / scanner providers in the constructor argument list):
   ```kotlin
   @get:Provides val analyzer: TrackAnalyzer = createJvmFlacTrackAnalyzer(),
   ```

3. New `@Provides` factory method inside the graph body (alongside existing factory methods like `libraryScanner` / `localLibrarySource`):
   ```kotlin
   @Provides
   fun analysisRunner(
       db: com.clayworks.kiln.data.library.db.KilnDatabase,
       analyzer: TrackAnalyzer,
   ): TrackAnalysisRunner = TrackAnalysisRunner(
       db = db,
       analyzer = analyzer,
       ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
   )
   ```

4. (Optional this session — only if Session 16 needs a graph-exposed handle.) Add an abstract member exposing the runner:
   ```kotlin
   abstract val analysisRunner: TrackAnalysisRunner
   ```
   Skip this if it requires reshaping unrelated parts of the graph. The `@Provides` chain alone is enough for downstream consumers to inject the runner through their own components.

- [ ] **Step 3: Edit `AndroidAppGraph.kt`**

In `app-android/src/main/kotlin/com/clayworks/kiln/android/di/AndroidAppGraph.kt`, mirror the desktop changes:

1. Imports:
   ```kotlin
   import com.clayworks.kiln.audio.playback.createAndroidMediaTrackAnalyzer
   import com.clayworks.kiln.library.scan.TrackAnalysisRunner
   import com.clayworks.kiln.library.scan.TrackAnalyzer
   ```

2. Provider in the constructor arg list:
   ```kotlin
   @get:Provides val analyzer: TrackAnalyzer = createAndroidMediaTrackAnalyzer(context),
   ```
   `context` is the Application context already injected via the graph's constructor.

3. Factory method:
   ```kotlin
   @Provides
   fun analysisRunner(
       db: com.clayworks.kiln.data.library.db.KilnDatabase,
       analyzer: TrackAnalyzer,
   ): TrackAnalysisRunner = TrackAnalysisRunner(
       db = db,
       analyzer = analyzer,
       ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
   )
   ```

4. Same optional abstract member if it integrates cleanly.

- [ ] **Step 4: Verify both apps build**

Run: `./gradlew :app-android:assembleDebug :app-desktop:assemble`
Expected: BUILD SUCCESSFUL — kotlin-inject KSP validates the graph chains (db, analyzer, runner) compile.

- [ ] **Step 5: Run the canonical session-validation build**

Run: `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest`
Expected: BUILD SUCCESSFUL — 5 of 5 canonical targets pass; existing 153+ tests across all modules stay green.

- [ ] **Step 6: Commit**

```bash
git add app-desktop/src/jvmMain/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt app-android/src/main/kotlin/com/clayworks/kiln/android/di/AndroidAppGraph.kt
git commit -m "feat(app): wire TrackAnalysisRunner into DI graphs

DesktopAppGraph: createJvmFlacTrackAnalyzer() + TrackAnalysisRunner provider.
AndroidAppGraph: createAndroidMediaTrackAnalyzer(context) + same provider.

Both graphs expose the runner via kotlin-inject @Provides so Session 16's
Track D-C backfill UI can inject it directly via a Compose component graph.

No auto-invocation from scanner — D-C is the explicit user trigger.

Phase 2a Track D-A wrap-up — Task 7."
```

---

## Task 8 — CLAUDE.md gotchas + Session 16 handoff + canonical verify-build + branch push

**Goal:** Capture session-empirical gotchas in `CLAUDE.md`, write the Session 16 handoff doc, run the canonical verify-build one final time, and push the branch.

**Files:**
- Modify: `CLAUDE.md`
- Create: `docs/sessions/2026-05-22-session-16-track-d-handoff.md`

- [ ] **Step 1: Append CLAUDE.md gotchas**

In `CLAUDE.md`, after the existing Track D-A gotchas (the section ending with "kotest-property `checkAll` is a suspend function..."), append the Track D-A wrap-up gotchas. The exact set of gotchas depends on what the implementer hit; minimum candidates to include:

```markdown
- **`TrackAnalysisRunner.runOnce()`'s worklist loop must advance offset by skipped-row count, not full page size**, otherwise an all-skipping analyzer (e.g., MP3-only library on a FLAC-only desktop impl) loops forever. The successful rows drop out of the worklist naturally because the WHERE clause filters on `replay_gain_track_db IS NULL`; skipped rows persist as worklist hits, so offset must accumulate them to scroll past.
- **ReplayGain v2 stores `track.replay_gain_track_db` as adjustment dB** (target -18 LUFS − integrated LUFS), NOT as the raw integrated LUFS value. Album rollup inverts: `lufs_i = -18.0 - track_db_i`, then `albumIntegratedLufs(...)`, then `album_db = -18.0 - album_lufs`. Storing raw LUFS would change the column semantics and break the consumer (Track D-B's gain-applier).
- **`track.replay_gain_track_peak` / `album_peak` are linear amplitudes** per RG v2 (e.g., `0.999847`). `LoudnessAnalyzer.truePeakDbtp()` returns dBTP, so persist requires `10^(dBTP / 20)`. Album peak is `max(linear_track_peaks)` — no separate aggregator function (just `maxOrNull()`).
- **MediaCodec PCM encoding is unknown until INFO_OUTPUT_FORMAT_CHANGED fires.** Initial `outputFormat.getInteger(KEY_PCM_ENCODING)` throws — the format isn't available until after the first dequeueOutputBuffer returns INFO_OUTPUT_FORMAT_CHANGED. Default-assume `ENCODING_PCM_16BIT` (=2) and re-read on the change event. `KEY_PCM_ENCODING` itself may also be absent on older devices — wrap with `if (outFmt.containsKey(...))`.
- **MediaExtractor + MediaCodec do not parallelize across tracks safely** — each instance holds a native handle that's single-thread. For the analyzer runner the sequential loop is the right shape; future "fast scan" parallelism needs one extractor+codec pair per worker coroutine, not shared resources.
```

- [ ] **Step 2: Write Session 16 handoff**

Create `docs/sessions/2026-05-22-session-16-track-d-handoff.md`:

```markdown
# Session 16 Handoff — Phase 2a Track D continuing (D-B or D-C choice)

**Authored:** 2026-05-22 at the close of Session 15 (after Track D-A wrap-up shipped)
**For:** Fresh CC session continuing Phase 2a Track D
**Goal:** Pick between Track D-B (consumer-side gain application) or Track D-C (settings UI + backfill button) and execute.

---

## TL;DR

- **Track D-A wrap-up shipped Session 15.** Album-level LUFS aggregation, SQLDelight queries, TrackAnalyzer port, TrackAnalysisRunner orchestrator, desktop FLAC analyzer impl, Android MediaCodec analyzer impl, DI wiring on both platforms.
- All builds green; canonical verify-build PASS.
- **Two follow-on sub-tracks remain for Track D.** D-B (consumer-side gain) is the playback-side complement; D-C (settings UI + backfill) is the user-trigger UI.

## What Session 15 shipped

- `albumIntegratedLufs(trackLufsValues): Either<AnalysisError, Double>` in `:audio:dsp/commonMain/.../replaygain/`
- 6 new SQLDelight queries on `track.sq` for the analyzer worklist + per-track + per-album persist
- `TrackAnalyzer` interface + `TrackLoudness` + `TrackAnalysisError` in `:data:library/commonMain/.../scan/`
- `TrackAnalysisRunner` orchestrator with paginated worklist walk + per-album rollup
- `JvmFlacTrackAnalyzer` (desktop, FLAC-only) + `AndroidMediaTrackAnalyzer` (Android, codec-agnostic via MediaCodec)
- `DesktopAppGraph` / `AndroidAppGraph` expose `TrackAnalysisRunner` via DI
- 6+ runner tests + 3 desktop analyzer tests; canonical verify-build PASS

## Pending Track D sub-tracks

### Track D-B — Consumer-side gain (~10-20h)

- Apply `replay_gain_track_db` / `replay_gain_album_db` to the audio pipeline as a linear pre-line-write gain.
- Android: Media3 AudioProcessor via custom RenderersFactory.
- Desktop: JavaSoundPlayerImpl multiplier.
- New setting key: `replayGainMode` (Off / Track / Album).
- New setting key: `replayGainPreAmpDb` (default 0.0, range -12.0 to +12.0).
- Peak limiting to prevent clipping when applying positive gain.

### Track D-C — Settings UI + backfill (~8-21h)

- Settings screen: ReplayGain mode radio group (Off/Track/Album) + pre-amp slider.
- Backfill UI: button that triggers `TrackAnalysisRunner.runOnce()` (already wired). Progress notification (long-running for large libraries — perf math says ~17-100 h for 40k tracks).

## Recommendation

D-C is the user-visible value. Without it, the analyzer pass shipped in Session 15 has no trigger surface (developers can only invoke via tests). D-B requires D-C to validate (you need RG values populated before consumer-side gain has anything to apply).

D-B is the playback-side polish — necessary for ReplayGain to actually do anything audible, but landable after D-C.

Suggested order: **D-C first, then D-B.**

Alternative: D-B + D-C in parallel if effort budget allows; they don't share files.

## Reference

- Track D-A wrap-up plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-wrap-up.md`
- Track D-A original plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-replaygain-analyzer.md`
- Engram entries: `mem_search "kiln/track-d-a-wrap-up"`
- CLAUDE.md gotchas: section "Build/Dep Gotchas (discovered MVP Sessions 1-7)" — last 5 bullets added in Session 15 for Track D-A wrap-up.

---

**End of Session 16 Handoff.**
```

- [ ] **Step 3: Run the canonical session-validation build**

Run: `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest`
Expected: BUILD SUCCESSFUL — 5 of 5 canonical targets pass.

Also run the full audio:dsp + audio:playback test suites:
Run: `./gradlew :audio:dsp:desktopTest :audio:playback:desktopTest`
Expected: PASS — all 40+ new analyzer tests and all 8 runner/aggregator tests green.

- [ ] **Step 4: Commit + push**

```bash
git add CLAUDE.md docs/sessions/2026-05-22-session-16-track-d-handoff.md
git commit -m "docs: Track D-A wrap-up — CLAUDE.md gotchas + Session 16 handoff

Captures 5 new gotchas (worklist offset advance, RG dB ↔ LUFS conversion,
linear peak storage, MediaCodec PCM encoding read timing, MediaExtractor
single-thread invariant).

Session 16 handoff doc covers Track D-B (consumer-side gain) and D-C
(settings UI + backfill) — D-C recommended as the next sub-track since it
exposes the analyzer runner's trigger surface to users.

Phase 2a Track D-A wrap-up — Task 8."

git push -u origin phase-2a-track-d-a-wrap-up
```

- [ ] **Step 5: Open PR**

```bash
gh pr create --title "Phase 2a Track D-A wrap-up — album aggregation + analyzer runner + scanner-side wiring" --body "$(cat <<'EOF'
## Summary

- Adds album-level LUFS aggregation (BS.1770-4 §5.3) to `:audio:dsp`
- Adds `TrackAnalyzer` port + `TrackAnalysisRunner` orchestrator to `:data:library`
- Adds desktop FLAC + Android MediaCodec `TrackAnalyzer` impls to `:audio:playback`
- Wires the runner into `DesktopAppGraph` and `AndroidAppGraph` via DI
- 11 new tests (5 aggregator + 6 runner + 3 desktop analyzer); canonical verify-build PASS

## Architecture notes

- `TrackAnalysisRunner` is **not** auto-invoked from `scanIncremental()` / `scanFull()` — perf math says ≥17 h for a first pass over Clay's 40 k track library. Track D-C will wire the explicit user trigger.
- Storage convention: per-track gain as RG-v2 dB (`-18.0 - integrated_lufs`); per-track peak as linear (`10^(dBTP/20)`); album peak as `max(linear_track_peaks)`.

## Test plan

- [ ] CI green (Ubuntu :app-android:assembleDebug + Windows :app-desktop:assemble)
- [ ] :data:library:desktopTest PASS (6 new TrackAnalysisRunner tests)
- [ ] :audio:dsp:desktopTest PASS (5 new LoudnessAggregator tests)
- [ ] :audio:playback:desktopTest PASS (3 new JvmFlacTrackAnalyzer tests)
- [ ] Manual Pixel smoke test (deferred to Session 16 once D-C wires a UI trigger)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

After all 8 tasks complete:

1. **Spec coverage**: every D-A wrap-up handoff bullet has a task — album aggregation (Task 1), scanner integration (the analyzer runner + DI wiring) (Tasks 3-7), perf profiling (deferred per architecture note in the plan header; documented in Session 16 handoff).
2. **Placeholder scan**: no TBD / TODO / "implement later" in the plan body. Every step contains complete code.
3. **Type consistency**: `TrackAnalyzer.analyze(filePath, codec)` signature is the same in Tasks 3, 4, 5, 6. `TrackLoudness(integratedLufs, truePeakDbtp)` is the same in Tasks 3, 4, 5, 6. `TrackAnalysisError` variants (`CodecUnsupported`, `DecodeFailed`, `AnalysisFailed`) are referenced consistently. SQLDelight query names (`selectTracksMissingReplayGain` etc.) match between Task 2 and Task 4.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-wrap-up.md`. Per Session 14's pattern (Sonnet was sufficient for all 12 dispatches), the recommended execution model is **Subagent-Driven Development** with Sonnet implementers + a Sonnet pair of reviewers per task (spec compliance + code quality).
