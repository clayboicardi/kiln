# Session 7 Handoff — MVP Session 4 Second Half + Vertical-Slice Payoff

**Authored:** 2026-05-19 (end of Session 6 / MVP Session 4 first half)
**For:** The next Claude session (likely a fresh cold-context session)
**Goal:** Complete MVP Session 4-7 vertical slice — real LibraryScanner, real PlatformPlayer impls, JNA libFLAC bridge, kotlin-inject DI graph, end-to-end "play a FLAC" milestone.

This handoff covers **7 pending items** carried forward from Session 6. Each item has: scope, blockers, file paths, reference design, effort estimate, validation criteria.

---

## Where we are (current state, 2026-05-19 end-of-session)

**Repo:** `https://github.com/clayboicardi/kiln` (public, Apache 2.0)
**Branch:** `main` at commit `011a707` (+ Session 6 closeout commit to be added)
**Build:** `./gradlew clean :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build` → BUILD SUCCESSFUL
**CI:** Green on every push this session (4 runs total)

**What's done:**
- ✅ Gradle 9.5.1 scaffold + build-logic + 8 modules
- ✅ Hello Kiln on Android (APK) + Desktop (jar)
- ✅ GitHub Actions CI (Android Ubuntu + Desktop Windows, both green)
- ✅ Spec amendment: minSdk 21→23 (Compose-MP 1.11 forces it)
- ✅ Source Protocol contracts: `MusicSource` + capabilities + BrowseScope + Either<SourceError>
- ✅ Engine-swap playback boundary: `PlatformPlayer` + PlayerState + Queue + Decoder + AudioProcessor
- ✅ SQLDelight schema: 6 tables + FTS5, 17 generated Kotlin files
- ✅ LocalLibrarySource skeleton: all 5 MusicSource methods + 6 of 11 BrowseScope branches wired
- ✅ FtsSanitize.kt + row mappers (Track/SelectRecentlyPlayed/SearchTracks)

**What's pending (this handoff's scope):**

| # | Item | Phase | Effort | Blocker |
|---|---|---|---|---|
| H1 | Album/Artist/Playlist row mappers | MVP S4 cont'd | ~1 hr | None — pure code |
| H2 | LibraryScanner — MediaStore (Android) + filesystem (JVM) | MVP S4 cont'd | ~4-6 hrs | None — pure code; jaudiotagger for JVM metadata |
| H3 | kotlin-inject DI graph wiring | MVP S4 cont'd | ~2-3 hrs | Needs H1 + H2 |
| H4 | Media3ExoPlayerImpl (Android) | MVP S5 | ~4-6 hrs | None — uses existing media3 deps |
| H5 | JavaSoundPlayerImpl (Desktop) | MVP S6 | ~4-6 hrs | Decoder partially wired by H6 |
| H6 | JNA libFLAC bridge (Desktop FLAC) | MVP S7 | ~10-15 hrs | Clay's WiX install for full smoke (not blocking compile) |
| H7 | End-to-end "play a FLAC" milestone | MVP S7 | ~2-4 hrs | All above |
| **H8** | **Task #11 first-build milestone (Pixel install + screenshots)** | Clay's interactive | ~30 min | Clay's Pixel 10 Pro XL + adb |

---

## Recommended execution order

The dependency graph:

```
H1 (mappers) ─┐
              ├─► H3 (DI graph) ─► H7 (end-to-end)
H2 (scanner) ─┘                       ▲
                                      │
H4 (Media3 player) ───────────────────┤
H5 (Java Sound player) ◄─── H6 (JNA libFLAC bridge) ◄┘
```

**Suggested sessions:**
- **Session 7 (this handoff target):** H1 + H2 + start of H4. Estimated 6-10 hrs.
- **Session 8:** Finish H4 + start H5. Estimated 6-8 hrs.
- **Session 9:** H6 (JNA libFLAC). Estimated 10-15 hrs (the big one).
- **Session 10:** H5 finish + H3 + H7. Estimated 6-10 hrs. **End-to-end "play a FLAC" milestone.**

H8 (Pixel install) is Clay's interactive step — can happen anytime independently.

---

## H1 — Album/Artist/Playlist row mappers

**Scope:** Extend `LocalLibrarySourceMappers.kt` with extension functions converting SQLDelight-generated `Album`, `Artist`, `Playlist` row classes to `MediaItem` (kind = Album/Artist/Playlist). Unblocks 5 currently-empty BrowseScope variants in `LocalLibrarySource.browse()`.

**Files to modify:**
- `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/source/LocalLibrarySourceMappers.kt` (add ~30 LOC)
- `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/source/LocalLibrarySource.kt` (replace 5 emptyFlow() returns with actual queries)

**SQLDelight queries to add to .sq files** (currently missing for Album/Artist/Playlist):

```sql
-- album.sq additions
selectAllOrderedByName:
SELECT * FROM album ORDER BY name_sort LIMIT :pageSize OFFSET :pageOffset;

selectAllOrderedByYearDesc:
SELECT * FROM album ORDER BY year DESC LIMIT :pageSize OFFSET :pageOffset;

-- artist.sq additions
selectAllPaged:
SELECT * FROM artist ORDER BY name_sort LIMIT :pageSize OFFSET :pageOffset;

-- playlist.sq additions
selectAllOrdered:
SELECT * FROM playlist ORDER BY name;
```

**Mapper pattern (Album example):**

```kotlin
internal fun Album.toMediaItem(): MediaItem = MediaItem(
    itemId = ItemId("album:$id"),  // namespaced — distinguish from track ids
    sourceId = SourceId("local"),
    kind = MediaItem.Kind.Album,
    title = name,
    subtitle = year?.toString(),  // or artist name via JOIN
    durationMs = null,
    artUri = art_path,
    metadata = emptyMap(),
)
```

**Note on ItemId namespacing:** Tracks use `id.toString()` directly (since Track.id is the only namespace). For Albums/Artists/Playlists, prefix with `"album:"`/`"artist:"`/`"playlist:"` to disambiguate in shared MediaItem flows. LocalLibrarySource.getPlayable() then parses the prefix to decide which table to query.

**Validation:** `./gradlew :data:library:build` after each mapper addition.

**Reference design:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §2.4.

---

## H2 — LibraryScanner (Android MediaStore + JVM filesystem)

**Scope:** Implement `LibraryScanner` interface with platform-specific scanners. Populates the SQLDelight tables. Includes incremental rescan (`file_mtime_ms` change detection per schema sketch §3.3).

**Files to create:**

**commonMain interface:**
```
data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/LibraryScanner.kt
data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/ScanError.kt
data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/ScanResult.kt
```

**Interface sketch:**
```kotlin
package com.clayworks.kiln.library.scan

import arrow.core.Either

interface LibraryScanner {
    suspend fun scanFull(): Either<ScanError, ScanResult>
    suspend fun scanIncremental(): Either<ScanError, ScanResult>
}

data class ScanResult(
    val tracksAdded: Int,
    val tracksUpdated: Int,
    val tracksSoftDeleted: Int,
    val durationMs: Long,
)

sealed interface ScanError {
    data class IoError(val cause: Throwable) : ScanError
    data class PermissionDenied(val message: String) : ScanError
    data class MetadataParseError(val path: String, val cause: Throwable) : ScanError
}
```

**androidMain — AndroidMediaStoreScanner:**

```
data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt
```

Queries `MediaStore.Audio.Media` for: `_ID, _DATA (file path), TITLE, ARTIST, ALBUM, DURATION, MIME_TYPE, BITRATE, DATE_MODIFIED, etc.` Maps to `track` table rows. Use `ContentResolver.query()` with appropriate columns + selection (`IS_MUSIC = 1 AND MIME_TYPE LIKE 'audio/%'`).

Note: ReplayGain + sample rate / bit depth are not in MediaStore — defer to a per-file jaudiotagger pass OR use Media3 MediaExtractor on the file URI for format details.

**desktopMain — JvmFilesystemScanner:**

```
data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt
```

`Files.walk(libraryRoot)` to enumerate. Filter by extension whitelist (`.flac`, `.mp3`, `.m4a`, `.ogg`, `.wav`, etc.). For each file: read metadata via `jaudiotagger` (`AudioFileIO.read(file)`). Map to `track` row. Compare `file_mtime_ms` against existing row for incremental detection.

Clay's library root is `D:\tiddl` (per engram `kiln/clay-environment/music-library-root`). Should be passed as constructor param, NOT hard-coded.

**Reference design:**
- `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §6 sketches both scanners
- `docs/decisions/2026-05-18-sqldelight-schema-sketch.md` §5-6 covers population strategy + FTS5 sync

**Critical:** FTS5 maintenance (insertSearchIndex on each track insert; raw `driver.execute("INSERT INTO track_search(track_search) VALUES('delete-all')")` for full rebuild). See `track_search.sq` comment + Session 6 discovery #2.

**Validation:** Unit tests with synthetic file lists; integration test against Clay's library on his machine. CI must NOT depend on actual files; mock the scanner via interface.

---

## H3 — kotlin-inject DI graph

**Scope:** Wire SourceId → MusicSource → PlatformPlayer → Decoder via kotlin-inject. Per Slack circuit pattern.

**Files to create:**

**`:app-android/src/main/kotlin/com/clayworks/kiln/di/AppGraph.kt`** + module bindings
**`:app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/AppGraph.kt`** + module bindings

Pattern (Slack-style):

```kotlin
@Component
@Singleton
abstract class AppGraph {
    abstract val localLibrarySource: LocalLibrarySource
    abstract val platformPlayer: PlatformPlayer

    @Provides
    @Singleton
    fun provideKilnDatabase(driverFactory: DriverFactory): KilnDatabase = ...

    @Provides
    @Singleton
    fun provideDecoderResolver(...): DecoderResolver = ...
}
```

KSP processor (`libs.kotlinInject.compiler`) generates the impl. Already wired in build.gradle.kts for both app modules.

**Reference design:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §8.

**Validation:** `./gradlew :app-android:kspDebugKotlin :app-desktop:kspKotlin` then build the apps. If kotlin-inject can't resolve a dep, fix the binding.

---

## H4 — Media3ExoPlayerImpl (Android)

**Scope:** Implement `PlatformPlayer` interface for Android. Delegates to Media3 ExoPlayer + MediaSession. Wires audio focus, BLE-disconnect (`setHandleAudioBecomingNoisy`), processor chain via custom `RenderersFactory`.

**File:** `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt`

**Reference design:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §4.4. Sketch in prep doc.

**Key things from spec/vetting:**
- Audio attributes: `USAGE_MEDIA + CONTENT_TYPE_MUSIC`
- `setHandleAudioBecomingNoisy(true)` per Item 11
- MediaSession via `androidx.media3.session.MediaSession.Builder(context, exo).build()` (already in `bundles.android-media3`)
- Position ticker: 250ms polling to update `positionMs` StateFlow

**Validation:** `./gradlew :audio:playback:assembleAndroidMain`. Unit tests via Mokkery + Turbine for state transitions.

---

## H5 — JavaSoundPlayerImpl (Desktop)

**Scope:** Implement `PlatformPlayer` interface for Desktop. Uses `javax.sound.sampled` (Java Sound) for output. Pulls PCM frames from `Decoder.open(playable)` via `DecodedStream.frames` Flow. Writes to a `SourceDataLine`.

**File:** `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt`

**Reference design:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §4.5 + §4.6.

**Critical:**
- Playback runs on a dedicated single-thread `audioDispatcher` backed by a real-time-priority thread
- Buffer underrun mitigation: decoder produces frames 100-200ms ahead of `line.write`
- `line.write` blocks until the audio mixer accepts the data — latency budget = ~30-100ms (per Item 9 decision)
- Format: `AudioFormat(PCM_SIGNED, sampleRateHz, bitDepth, channels, frameSize, frameRate, bigEndian = false)`

**Validation:** Smoke test by feeding a synthetic sine-wave PCM stream through the player.

---

## H6 — JNA libFLAC bridge

**Scope:** Vendor `libFLAC.dll` (Win-x64) under `:audio:playback/src/desktopMain/resources/native/win-x64/` and write JNA Kotlin interfaces for libFLAC's stream-decoder C API. Implements `Decoder` interface for FLAC.

**Files:**
- `audio/playback/src/desktopMain/resources/native/win-x64/libFLAC.dll` (binary; download from xiph/flac 1.5.0 release archive)
- `audio/playback/src/desktopMain/resources/native/win-x64/LICENSE-libflac.txt` (BSD-3 attribution)
- `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/native/LibFlacBinding.kt` — JNA interface declarations matching libFLAC's public C API
- `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/native/NativeLibraryLoader.kt` — extracts DLL from JAR to temp dir + `System.load()` registration
- `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacDecoderImpl.kt` + `JvmFlacDecodedStream.kt`

**Reference design:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §5.3 + scaffold prep §7 (vendoring procedure).

**libFLAC C API needed (subset):**
- `FLAC__stream_decoder_new()` → handle
- `FLAC__stream_decoder_init_file(handle, filepath, write_cb, metadata_cb, error_cb, client_data)`
- `FLAC__stream_decoder_process_until_end_of_metadata(handle)`
- `FLAC__stream_decoder_process_single(handle)` — one frame
- `FLAC__stream_decoder_get_state(handle)`
- `FLAC__stream_decoder_finish(handle)`
- `FLAC__stream_decoder_delete(handle)`

JNA registers callbacks for the write/metadata/error handlers — those run on the calling thread.

**THIRD_PARTY_LICENSES.md at repo root** with xiph/flac BSD-3 text. Phase 2a Flight A polishes this; for MVP just include the file.

**Validation:** Empirical smoke test against ≥10 of Clay's FLAC files (16/44, 24/96, 24/192) per Item 9 carry-forward. Compare PCM output bytes vs `ffmpeg -i file.flac -f f32le -` reference. CI excludes this test (requires Clay's library).

---

## H7 — End-to-end "play a FLAC" milestone

**Scope:** With H1-H6 done, wire a simple "Press button to play track ID 1" interaction in :app-android and :app-desktop. Validates the entire stack: MusicSource → getPlayable → DecoderResolver → Decoder → PlatformPlayer → AudioProcessor chain → audio output.

**Files:**
- `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt` — replace Hello Kiln Composable with a button that calls graph.platformPlayer.loadQueue + play
- `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt` — same

For both: pre-seed the database with one track from Clay's library (manual SQL insert or hardcoded scan-then-play).

**Validation:** Audible playback on Pixel 10 Pro XL (via Bluetooth or speaker) + audible playback on Clay's Windows desktop (via Java Sound output). Document the milestone in a Session 10 closeout (or whichever session lands it) with frame-time samples + audible-quality observation.

---

## H8 — Task #11 first-build milestone (Clay's interactive)

**Scope:** Clay installs current Hello Kiln APK on his Pixel 10 Pro XL via adb, launches the desktop binary via `./gradlew :app-desktop:run`, screenshots both, commits screenshots to a session closeout doc.

**Steps:**
1. Get the APK: it's already in `app-android/build/outputs/apk/debug/app-android-debug.apk` after `./gradlew :app-android:assembleDebug` (or download from latest CI run artifact `kiln-app-android-debug`).
2. `adb install app-android/build/outputs/apk/debug/app-android-debug.apk`
3. Launch the app on Pixel; verify "Hello Kiln" displays centered on screen
4. On desktop: `./gradlew :app-desktop:run` from project root
5. Verify "Hello Kiln" Compose-MP window opens with title "Kiln"
6. Screenshot both
7. Commit screenshots to `docs/sessions/2026-05-19-session-X.md` (or to the prevailing session at that time)

**Estimated time:** 30 minutes if everything works. Could take longer if the Pixel needs USB debugging enabled, if adb is finicky, etc.

**Doesn't block:** Other session work can proceed; this is purely confirmation Hello Kiln runs on actual devices.

---

## Critical context for next session (gotchas)

1. **SQLDelight type-narrows queries with `IS NOT NULL` filters on nullable columns.** When adding new queries that have such filters, expect SQLDelight to generate a custom row class instead of using the table's default class. Write a separate mapper for the narrowed type. Example in this session: `SelectRecentlyPlayed.toMediaItem()` distinct from `Track.toMediaItem()`.

2. **SQLDelight cannot parse FTS5 control commands inline.** Maintenance commands like `INSERT INTO track_search(track_search) VALUES('delete-all')` must be issued via `driver.execute(rawSql)` — not as labeled `.sq` queries.

3. **`kotlinx.coroutines.flow.transform { rows -> rows.forEach { emit(...) } }` is the idiomatic "Flow<List<T>> → Flow<T>" bridge.** Do not use custom helper functions for this (receiver-type-inference quirks) or `flatMapConcat` (opt-in stability).

4. **AGP 9.0 dropped the `org.jetbrains.kotlin.android` plugin.** Kotlin support is built-in. Do not re-add it.

5. **AGP 9.0 requires `com.android.kotlin.multiplatform.library` (not `com.android.library`) when paired with `org.jetbrains.kotlin.multiplatform`.** Existing `kiln.kmp.library` convention uses the correct plugin. Don't change to the old one.

6. **`jvm("desktop")` source set is `desktopMain` / `desktopTest` (NOT `jvmMain`).** Module build.gradle.kts files must use the correct names.

7. **Compose-MP libs aren't api-exposed by `:ui:*` modules.** App modules need `implementation(libs.bundles.compose.mp.common)` (and `:app-desktop` also needs `implementation(compose.desktop.currentOs)`).

8. **kmpalette 4.0.0-beta02 is NOT on Maven Central** — only GitHub release tag. Per Item 3 addendum, dep is commented out in `:ui:theme/build.gradle.kts`. Resolution deferred to Phase 2a Flight A (JitPack OR roll-our-own).

9. **minSdk is 23 (was 21 until 2026-05-19).** Compose-MP 1.11 components-resources-android requires 23. Don't try to lower.

10. **`upgradeUuid = "611fd94b-756e-561d-ba94-af658a225268"`** is wired in `kiln.desktop.app` convention. Never modify — future MSI upgrades depend on stability.

11. **Music library root: `D:\tiddl`** (not `%USERPROFILE%\Music`). Per Clay's environment. Engram: `kiln/clay-environment/music-library-root`.

---

## How to start the next session

1. **`cd C:\Users\chawo\Projects\kiln`** and read this handoff doc first.
2. Read `kiln/CLAUDE.md` for project orientation (~95 lines).
3. Read `docs/sessions/2026-05-19-session-6.md` for the most recent closeout.
4. Optional: `mem_search "kiln mvp-session-4"` to warm engram context.
5. Pick H1 (smallest, ~1 hr) as a warm-up, then move to H2.
6. Commit after each working change. Push at logical milestones.
7. CI is wired — every push to main triggers the green-check workflow.

**Estimated total remaining effort to "play a FLAC" milestone:** 32-46 hrs across multiple sessions.

---

**End of Session 7 Handoff.** Sequencing per-§11 plan protocol. Next Claude session opens this file first, picks H1, ships.
