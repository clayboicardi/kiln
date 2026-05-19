# Session 8 Handoff — Finish MVP Sessions 5-7 + DI Graph + End-to-End

**Authored:** 2026-05-19 (end of Session 7)
**For:** The next Claude session (likely a fresh cold-context session)
**Goal:** Complete the remaining handoff items from Session 7 — H3 (DI), finish H4 (loadQueue), H5 (Java Sound), H6 (JNA libFLAC), H7 (end-to-end "play a FLAC"), plus H8 (Clay's Pixel install milestone).

This handoff covers **6 pending items** carried forward from Session 7. Session 7 delivered the recommended H1+H2+H4-scaffold scope; this picks up where that left off.

---

## Where we are (current state, 2026-05-19 end-of-session-7)

**Repo:** `https://github.com/clayboicardi/kiln` (public, Apache 2.0)
**Branch:** `main` at commit `06ffea8` (+ Session 7 closeout commit to be added)
**Build:** `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build` → BUILD SUCCESSFUL
**Tests:** `:data:library:desktopTest` → 25 tests passing in 36ms

**What's done since Session 7 handoff was written:**
- ✅ H1 — Album/Artist/Playlist mappers + 5 BrowseScope branches wired (commit `bac8bb6`)
- ✅ H2.a — LibraryScanner contracts + scanner SQL (commit `70beffd`)
- ✅ H2.b — JvmFilesystemScanner with jaudiotagger (commit `985914d`)
- ✅ H2.c — AndroidMediaStoreScanner + shared scan internals refactor (commit `4f7734a`)
- ✅ H2.d — 25 scanner-helper unit tests (commit `ffbed5d`)
- ✅ H4 scaffold — Media3ExoPlayerImpl with full controls + state observation; only `loadQueue` is stubbed (commit `06ffea8`)

**What's pending (this handoff's scope):**

| # | Item | Phase | Effort | Blocker |
|---|---|---|---|---|
| H4-finish | Wire MusicSource into Media3ExoPlayerImpl + complete loadQueue | MVP S5 | ~2-4 hrs | None — Media3ExoPlayerImpl scaffold exists |
| H3 | kotlin-inject DI graph (both apps) | MVP S5 | ~3-4 hrs | None — all the impls exist |
| H5 | JavaSoundPlayerImpl (Desktop) | MVP S6 | ~4-6 hrs | Decoder partially wired by H6 |
| H6 | JNA libFLAC bridge | MVP S7 | ~10-15 hrs | None for compile; full smoke needs Clay's FLACs |
| H7 | End-to-end "play a FLAC" milestone | MVP S7 | ~2-4 hrs | All above |
| **H8** | **First-build milestone (Pixel install + screenshots)** | Clay's interactive | ~30 min | Clay's Pixel 10 Pro XL + adb |

---

## Recommended execution order

```
H4-finish ─┐
           ├─► H3 ─► H7 (Android end-to-end)
           │
H6 ────────┴─► H5 ─► H7 (Desktop end-to-end)
```

**Suggested sessions:**
- **Session 8 (this handoff target):** H4-finish + H3 (Android side). Estimated 5-8 hrs.
- **Session 9:** H6 (the big one). Estimated 10-15 hrs.
- **Session 10:** H5 + H7 end-to-end (both platforms). Estimated 6-10 hrs.

H8 (Pixel install) is Clay's interactive step — runs anytime independently.

---

## H4-finish — Wire MusicSource + complete loadQueue

**Scope:** `Media3ExoPlayerImpl.loadQueue()` currently just updates the QueueState StateFlow; it doesn't actually feed ExoPlayer. Inject `MusicSource` into the constructor, resolve each MediaItem to a Playable, convert to androidx.media3 MediaItems, call `exo.setMediaItems` + `exo.prepare()`.

**Files to modify:**
- `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt`

**Key changes:**

1. Add `private val source: MusicSource` constructor param.

2. Add a coroutine launch in `loadQueue` that resolves each item:
   ```kotlin
   override suspend fun loadQueue(items, startIndex, autoPlay) = withContext(Dispatchers.Main.immediate) {
       _state.value = PlayerState.Loading
       val playables = items.mapNotNull { item ->
           when (val r = source.getPlayable(item.itemId)) {
               is Either.Right -> r.value to item
               is Either.Left -> {
                   log.w { "skipping ${item.itemId}: ${r.value}" }
                   null
               }
           }
       }
       val media3Items = playables.map { (playable, _) ->
           androidx.media3.common.MediaItem.fromUri(playable.uri)
       }
       _queue.value = QueueState(items = playables.map { it.second }, currentIndex = startIndex, ...)
       exo.setMediaItems(media3Items, startIndex, /* startPositionMs */ 0L)
       exo.prepare()
       if (autoPlay) exo.play()
   }
   ```

3. The `source: MusicSource` import means commonMain `MusicSource` is referenced from androidMain — already valid since `:audio:playback` depends on `:data:library`.

**Validation:** `./gradlew :audio:playback:build` after the change. The full end-to-end smoke happens at H7.

**Reference:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §4.4 + the existing scaffold in `Media3ExoPlayerImpl.kt` lines for `loadQueue` (the TODO is there).

---

## H3 — kotlin-inject DI graph

**Scope:** Wire SqlDriver → KilnDatabase → LocalLibrarySource + LibraryScanner + PlatformPlayer (+ Decoder/DecoderResolver for Desktop) for both `:app-android` and `:app-desktop`. Per Slack circuit pattern.

**Files to create:**

**`app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt`:**
```kotlin
@Component
@Singleton
abstract class AndroidAppGraph(
    @get:Provides val context: Context,
) {
    abstract val musicSource: MusicSource
    abstract val scanner: LibraryScanner
    abstract val player: PlatformPlayer

    @Provides @Singleton
    fun sqlDriver(): SqlDriver = AndroidSqliteDriver(
        schema = KilnDatabase.Schema,
        context = context,
        name = "kiln.db",
    )

    @Provides @Singleton
    fun database(driver: SqlDriver): KilnDatabase = KilnDatabase(driver)

    @Provides
    fun localSource(db: KilnDatabase): MusicSource = LocalLibrarySource(db, Dispatchers.IO)

    @Provides
    fun mediaStoreScanner(context: Context, db: KilnDatabase, driver: SqlDriver): LibraryScanner =
        AndroidMediaStoreScanner(context, db, driver, Dispatchers.IO)

    @Provides
    fun media3Player(context: Context, source: MusicSource): PlatformPlayer =
        Media3ExoPlayerImpl(context, source)
}
```

**`app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt`:** parallel structure. Uses:
- `JdbcSqliteDriver("jdbc:sqlite:${userDataDir}/kiln.db")` as the driver
- `JvmFilesystemScanner(scanFolders, db, driver, Dispatchers.IO)` as scanner — `scanFolders` from `appdirs` resolution + Settings UI (deferred)
- `JavaSoundPlayerImpl(decoderResolver, audioDispatcher)` as player (lands at H5)

**KSP processor for kotlin-inject already wired in both app modules.**

**Validation:** `./gradlew :app-android:kspDebugKotlin :app-desktop:kspKotlin` — KSP should generate the impl classes. Then the apps need to actually call `AndroidAppGraph::class.create(application)` (or similar) and use the produced graph.

**Reference design:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §8.

---

## H5 — JavaSoundPlayerImpl (Desktop)

**Scope:** Desktop PlatformPlayer using javax.sound.sampled. Pulls PCM frames from `Decoder.open(playable).frames` Flow, writes to a `SourceDataLine`. Dedicated single-thread audio dispatcher.

**File:** `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt`

**Reference design:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §4.5 + §4.6.

**Key constraints:**
- Playback runs on `audioDispatcher` (`Executors.newSingleThreadExecutor(Thread.MAX_PRIORITY)`)
- Buffer underrun: decoder reads ahead 100-200ms of `line.write`
- AudioFormat constructed from `Decoder.open(playable).format`

**Validation:** Smoke test with synthetic sine-wave PCM (no FLAC needed yet — H6 is the FLAC pipeline).

---

## H6 — JNA libFLAC bridge

**Scope:** Per Session 7 handoff H6 (unchanged scope — see `2026-05-19-session-7-handoff.md` for full detail). Vendor `libFLAC.dll` Win-x64 under `:audio:playback/src/desktopMain/resources/native/win-x64/` and write JNA bindings for the stream-decoder C API.

**Files to create:**
- `audio/playback/src/desktopMain/resources/native/win-x64/libFLAC.dll`
- `audio/playback/src/desktopMain/resources/native/win-x64/LICENSE-libflac.txt` (BSD-3)
- `audio/playback/src/desktopMain/kotlin/.../native/LibFlacBinding.kt`
- `audio/playback/src/desktopMain/kotlin/.../native/NativeLibraryLoader.kt`
- `audio/playback/src/desktopMain/kotlin/.../JvmFlacDecoderImpl.kt` + `JvmFlacDecodedStream.kt`

**Empirical FLAC smoke:** 10 representative files from Clay's library spanning 16/44 → 24/192 + multichannel + ReplayGain + embedded art. Compare PCM output byte-for-byte against `ffmpeg -i file.flac -f s24le -acodec pcm_s24le`. Per vetting Item 9 addendum.

**Reference:** Session 7 handoff §H6.

---

## H7 — End-to-end "play a FLAC" milestone

**Scope:** With H3+H4-finish+H5+H6 done, wire a single button in both apps that plays the first track in the database. Smoke that confirms the full stack.

**Files:**
- `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt` — replace Hello Kiln with a button + LaunchedEffect that calls scanner.scanIncremental() on first run, then `graph.player.loadQueue(graph.musicSource.browse(BrowseScope.AllTracks).take(1).toList(), 0, true)`
- `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt` — same

**Validation:** Audible playback on Pixel 10 Pro XL + audible playback on Clay's Windows desktop. Frame-rate observation + audible-quality notes in the session closeout.

---

## H8 — First-build milestone (Clay's interactive)

**Scope:** Unchanged from Session 7 handoff — install Hello Kiln APK on Pixel via adb + run desktop binary + screenshots both. ~30 minutes.

The APK from the latest CI run can be downloaded from GitHub Actions artifacts, OR built locally via `./gradlew :app-android:assembleDebug` → `app-android/build/outputs/apk/debug/app-android-debug.apk`.

---

## Critical context for next session (gotchas accumulated)

From Session 6 + Session 7 combined (the live cumulative gotcha list):

1. **AGP 9.0 dropped `org.jetbrains.kotlin.android`** — don't re-add. AGP has Kotlin built in.

2. **AGP 9.0 + KMP requires `com.android.kotlin.multiplatform.library`** (not `com.android.library`) for KMP modules. Use the new DSL: `kotlin { androidLibrary { ... } }`.

3. **Gradle 9.x: `include(":foo")` requires `./foo/` to exist.** Empty dirs with `.gitkeep` work; non-existent dirs fail at settings evaluation.

4. **`jvm("desktop")` source sets are `desktopMain` / `desktopTest`** (NOT `jvmMain` / `jvmTest`).

5. **App modules need `implementation(libs.bundles.compose.mp.common)`** directly. `:ui:*` modules expose Compose deps as `implementation` not `api`.

6. **`:app-desktop` additionally needs `implementation(compose.desktop.currentOs)`** for platform-specific Skia + window toolkit jars.

7. **SQLDelight type-narrows `IS NOT NULL` queries** (e.g., `selectRecentlyPlayed` → custom `SelectRecentlyPlayed` row class). NOT for `IS NULL` filters on already-nullable columns (those return the default row class).

8. **SQLDelight can't parse FTS5 control commands** (`'delete-all'`, `'delete'`). Use raw `driver.execute(sql, parameters = N)` for these.

9. **Use `kotlinx.coroutines.flow.transform { rows -> rows.forEach { emit(...) } }`** for the `Flow<List<T>> → Flow<T>` bridge. Not `flatMapConcat` (opt-in stability) or custom helpers (receiver-inference quirks).

10. **kmpalette 4.0.0-beta02 is NOT on Maven Central** — only the GitHub release tag. Dep commented out in `:ui:theme`. Phase 2a Flight A decides JitPack vs roll-our-own.

11. **minSdk = 23** (was 21 until 2026-05-19). Compose-MP 1.11 components-resources-android requires ≥23.

12. **`upgradeUuid = "611fd94b-756e-561d-ba94-af658a225268"`** wired in `kiln.desktop.app` convention. **NEVER MODIFY** — future MSI upgrades depend on stability.

13. **Music library root: `D:\tiddl`** (NOT `%USERPROFILE%\Music`). For scan-folder default at H3 / Settings UI (MVP Session 26-28).

14. **MediaStore.Audio.Media DATE_MODIFIED is seconds-since-epoch.** Multiply by 1000L for the schema's millisecond timestamps.

15. **MediaStore TRACK column may encode disc number** as "1NNN" form. Modulo 1000 to extract just the track number.

16. **MediaStore "<unknown>" literal** is the placeholder for missing artist/album values. Filter alongside blank-string checks.

17. **jaudiotagger `header.bitRateAsNumber` returns Long** (the other format methods return Int). Avoid blanket `.toLong()` — warns.

18. **`@Suppress` cannot annotate a constructor-call argument label.** Move to the enclosing function or class.

19. **ExoPlayer is single-thread accessed.** All method calls go through `Dispatchers.Main.immediate`. Default looper = `Looper.getMainLooper()`. Constructor itself must be called on main thread.

20. **ItemId namespace contract:** tracks are bare numeric ("42"); albums/artists/playlists prefix with "album:" / "artist:" / "playlist:". `getPlayable` returns `ItemNotFound` for non-numeric (container) IDs — only tracks are playable.

21. **The scanner does scan-end FTS5 rebuild** (raw `'delete-all'` + bulk INSERT from `selectAllForFtsRebuild`) rather than per-row maintenance. Avoids the contentless-FTS5 delete-syntax's old-values requirement. Trade-off: during-scan search returns stale results briefly.

---

## How to start the next session

1. `cd C:\Users\chawo\Projects\kiln`
2. Read this handoff doc first.
3. Read `kiln/CLAUDE.md` (~100 lines) for project orientation.
4. Read `docs/sessions/2026-05-19-session-7.md` for the most-recent closeout.
5. Optional: `mem_search "kiln session-7"` to warm engram context.
6. Pick H4-finish (smallest, ~2-4 hrs) as a warm-up, then H3 (DI graph).
7. Commit after each working change. Push at logical milestones (CI runs on every push to main).

**Estimated total remaining effort to "play a FLAC" milestone:** 25-37 hrs across 3 sessions.

---

**End of Session 8 Handoff.** Per-§11 plan protocol. Next Claude session opens this file first, picks H4-finish, ships.
