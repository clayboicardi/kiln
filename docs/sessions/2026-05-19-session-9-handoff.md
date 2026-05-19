# Session 9 Handoff — Finish vertical slice: H5 + H6 + H7 + H8

**Authored:** 2026-05-19 (end of Session 8)
**For:** The next Claude session (likely a fresh cold-context session)
**Goal:** Complete the remaining handoff items from Session 8 — H5 (JavaSoundPlayerImpl Desktop), H6 (JNA libFLAC bridge), H7 (end-to-end "play a FLAC"), plus H8 (Clay's Pixel install milestone, independent).

This handoff covers **4 pending items** carried forward from Session 8. Session 8 delivered the recommended H4-finish + H3 scope.

---

## 🚀 Pre-flight (first 5 minutes of the session)

**Read order (cold-start):**

1. This file (Session 9 handoff) — full read.
2. `CLAUDE.md` — ~120 lines, project orientation + 27 cumulative gotchas.
3. `docs/sessions/2026-05-19-session-8.md` — most-recent closeout.
4. *Optional* `mem_search "kiln session-8"` to warm engram context.
5. Scaffold prep §4.5–§4.6 + §5.3–§5.4 — `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` lines 566-866. The full reference design for JavaSoundPlayerImpl + JvmFlacDecoderImpl + LibFlacBinding.

**Confirm clean baseline:**

```powershell
cd C:\Users\chawo\Projects\kiln
git log --oneline -5           # expect e8c383a at top (Session 8 closeout)
git status                     # expect clean tree
./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest
# expect: BUILD SUCCESSFUL in ~5-15s incremental, 25 tests green
```

If baseline is dirty or build red — STOP, diagnose, surface to Clay. Don't paper over.

---

## Where we are (current state, 2026-05-19 end-of-session-8)

**Repo:** `https://github.com/clayboicardi/kiln` (public, Apache 2.0)
**Branch:** `main` at commit `e8c383a` (Session 8 closeout). Origin = local (pushed).
**Build:** `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest` → BUILD SUCCESSFUL
**Tests:** `:data:library:desktopTest` → 25 tests passing in 36ms
**CI:** Run 26121124795 (Session 8 closeout push) — Ubuntu Android + Windows Desktop; expected green.

**What's done since Session 8 handoff was written:**
- ✅ H4-finish — Media3ExoPlayerImpl.loadQueue resolves via injected MusicSource (commit `7740e1c`)
- ✅ H3 — AndroidAppGraph + KilnApplication + DesktopAppGraph + Singleton annotations (commit `f918c99`)

**What's pending (this handoff's scope):**

| # | Item | Phase | Effort | Blocker |
|---|---|---|---|---|
| H5 | JavaSoundPlayerImpl (Desktop) | MVP S6 | ~4-6 hrs | Decoder partially wired by H6 |
| H6 | JNA libFLAC bridge | MVP S7 | ~10-15 hrs | None for compile; full smoke needs Clay's FLACs |
| H7 | End-to-end "play a FLAC" milestone | MVP S7 | ~2-4 hrs | All above |
| **H8** | **First-build milestone (Pixel install + screenshots)** | Clay's interactive | ~30 min | Clay's Pixel 10 Pro XL + adb |

---

## Recommended execution order

```
H6 ─► H5 ─► H7 (Desktop end-to-end)
              │
              └─► H7 (Android end-to-end — also picks up here)
```

**Suggested sessions:**
- **Session 9 (this handoff target):** H6 alone (the big one — ~10-15 hrs). Possibly H6 + H5 if H6 lands quickly.
- **Session 10:** H5 + H7 end-to-end (both platforms). Estimated 6-10 hrs.

H8 (Pixel install) is Clay's interactive step — runs anytime independently.

---

## H6 — JNA libFLAC bridge

**Scope:** Vendor Xiph libFLAC 1.5.0 (BSD-3) Win-x64 binary under `:audio:playback/src/desktopMain/resources/native/win-x64/` and write JNA bindings for the stream-decoder C API. Per vetting log Item 9 addendum (the no-Java-FLAC-lib decision).

**Files to create:**

- `audio/playback/src/desktopMain/resources/native/win-x64/libFLAC.dll` — vendored binary
- `audio/playback/src/desktopMain/resources/native/win-x64/LICENSE-libflac.txt` — Xiph BSD-3 attribution
- `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/native/LibFlacBinding.kt` — JNA interface mapping the C API
- `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/native/NativeLibraryLoader.kt` — extracts DLL from JAR to temp, System.load()s it
- `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacDecoderImpl.kt` — implements Decoder for FLAC
- `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacDecodedStream.kt` — implements DecodedStream

**Reference design:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §5.3, §5.4. Also: the C reference example at `xiph/flac/examples/c/decode/file/main.c` for the callback dance.

**Suggested sub-steps (commit between each — small, testable increments):**

1. **Vendor the binary.** Download libFLAC 1.5.0 Win-x64 from xiph.org (or build from source). Drop `libFLAC.dll` + `LICENSE-libflac.txt` (Xiph BSD-3) under `audio/playback/src/desktopMain/resources/native/win-x64/`. Verify it's loaded into the test JVM via a one-line smoke (`Native.load("FLAC", ...)`).
2. **NativeLibraryLoader.** Extracts the DLL from the JAR resource to a temp dir + `System.load()`s it. JNA's `Native.load(name, ...)` can't directly read from a JAR; the extract-to-temp pattern is the standard JNA-on-JVM solution. Cache the extracted location for the JVM lifetime.
3. **LibFlacBinding (skeleton).** JNA `Library` interface with just `FLAC__stream_decoder_new` + `FLAC__stream_decoder_delete` + state enum. Compile-only smoke — confirms JNA can find the function symbols. Don't wire callbacks yet.
4. **Callback registration.** Add `FLAC__stream_decoder_init_file` + the 3 callback typedefs (`WriteCallback`, `MetadataCallback`, `ErrorCallback`) as JNA `Callback` interfaces. The write callback is where decoded PCM frames arrive — that's the hot path.
5. **Metadata read.** Call `process_until_end_of_metadata`, parse the STREAMINFO block, expose `StreamInfo(sampleRateHz, bitDepth, channels, totalSamples)`. Verify against ffprobe output for a known FLAC.
6. **PCM decode loop.** Wire `process_single` in a Kotlin `flow {}` builder; the write callback feeds an `AudioFrame` channel that the flow reads from. **Watch the 24-bit packing**: libFLAC gives samples as `FLAC__int32` even for 16/24-bit content — pack down to little-endian 3-byte sequences for 24-bit, sign-aware.
7. **Seek + close.** `seek_absolute(sample)` + `streamDecoderFinish` + `streamDecoderDelete`. Use Kotlin's `AutoCloseable` interface; consumers do `decoder.open(playable).use { ... }`.
8. **FlacDecodeSmokeTest.** Pick 10 representative FLACs from Clay's `D:\tiddl` library spanning the format matrix (see Empirical FLAC smoke below). Compare PCM output byte-for-byte against ffmpeg reference. CI-exclude (requires Clay's local library); local-only.

**Empirical FLAC smoke (per vetting Item 9 addendum gate):** 10 representative files from Clay's library spanning 16/44 → 24/192 + multichannel + ReplayGain + embedded art. Compare PCM output byte-for-byte against `ffmpeg -i file.flac -f s24le -acodec pcm_s24le`. Per vetting Item 9 addendum + scaffold prep §10. Smoke test file: `audio/playback/src/desktopTest/kotlin/.../FlacDecodeSmokeTest.kt` (CI-excluded; runs locally).

**JNA Pointer / callback gotchas:**
- libFLAC's stream-decoder uses callback-based output (`write_callback`, `metadata_callback`, `error_callback`). JNA callbacks have specific marshalling rules — typically a Kotlin `interface` extending `com.sun.jna.Callback` with a single method.
- 24-bit PCM packing is fiddly: 3 bytes per sample, little-endian, sign-extended into a 32-bit int when libFLAC delivers samples via the write callback. Don't truncate.
- libFLAC's stream-decoder handle is opaque (`FLAC__StreamDecoder*`). Hold as `com.sun.jna.Pointer` or `Long`. Must call `streamDecoderDelete` to free — leaking it leaks native memory.

**Validation:** `./gradlew :audio:playback:desktopTest` runs the smoke test. Success gate: 10/10 files byte-identical to ffmpeg reference. 1-2 failures investigate (likely 24-bit packing); 3+ failures surface to Clay and reconsider per Item 9 addendum fallback.

---

## H5 — JavaSoundPlayerImpl (Desktop)

**Scope:** Desktop PlatformPlayer using `javax.sound.sampled`. Pulls PCM frames from `Decoder.open(playable).frames` Flow, writes to a `SourceDataLine`. Dedicated single-thread audio dispatcher.

**File:** `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt`

**Reference design:** `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` §4.5 + §4.6 + §7.

**Key constraints (per reference design):**
- Playback runs on `audioDispatcher` (`Executors.newSingleThreadExecutor(Thread.MAX_PRIORITY)`)
- Buffer underrun mitigation: decoder reads ahead 100-200ms of `line.write`
- AudioFormat constructed from `Decoder.open(playable).format` (sampleRateHz, bitDepth, channels, sampleFormat)
- `line.write` blocks until the audio mixer accepts the data — that's the latency budget

**DI graph update at this point (DesktopAppGraph.kt):**
- Add `abstract val player: PlatformPlayer`
- Add `@Singleton @Provides fun decoderResolver(libFlac: LibFlacBinding): DecoderResolver = DefaultDecoderResolver(listOf(JvmFlacDecoderImpl(libFlac)))`
- Add `@Singleton @Provides fun libFlac(): LibFlacBinding = LibFlacLoader.load()`
- Add `@Singleton @Provides fun audioDispatcher(): CoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "kiln-audio-out").apply { priority = Thread.MAX_PRIORITY; isDaemon = true } }.asCoroutineDispatcher()`
- Add `@Singleton @Provides fun javaSoundPlayer(decoderResolver: DecoderResolver, audioDispatcher: CoroutineDispatcher, source: MusicSource): PlatformPlayer = JavaSoundPlayerImpl(decoderResolver, audioDispatcher, source)`

(JavaSoundPlayerImpl will also want `source: MusicSource` injected so its `loadQueue` can resolve Playables, parallel to Media3ExoPlayerImpl's pattern landed at H4-finish.)

**Validation:** Smoke test with synthetic sine-wave PCM (no FLAC needed yet — H6 provides the FLAC pipeline; H5 validates the playback loop independently).

---

## H7 — End-to-end "play a FLAC" milestone

**Scope:** With H5+H6 done, wire a single button in both apps that plays the first track in the database. Smoke that confirms the full stack.

**Files to modify:**

- `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt` — replace Hello Kiln with a button + LaunchedEffect that calls `(application as KilnApplication).graph.scanner.scanIncremental()` on first run, then `graph.player.loadQueue(graph.musicSource.browse(BrowseScope.AllTracks).take(1).toList(), 0, true)` on button press
- `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt` — instantiate `DesktopAppGraph::class.create(userDataDir, scanFolders)` once at `application {}` top, then same button + LaunchedEffect pattern

**The Compose collection pattern (the trickiest bit):**
- `graph.musicSource.browse(BrowseScope.AllTracks)` returns `Flow<MediaItem>` — emits per-track. Need to either `.toList()` (collecting the cold flow once) or use a paged `LazyColumn` for the real library view (out of scope for H7's "play first track" button).
- `Flow<MediaItem>.take(1).toList()` is the minimal "give me the first track" shape. Run inside a `LaunchedEffect`.

**Scan folders for Desktop:** Hardcode `Path.of("D:\\tiddl")` in `Main.kt` as the initial ScanFolders. User-data-dir: `Path.of(System.getProperty("user.home"), ".kiln")`. Document both with TODO comments referencing the eventual Settings UI session.

**Validation:** Audible playback on Pixel 10 Pro XL + audible playback on Clay's Windows desktop. Frame-rate observation + audible-quality notes in the session closeout. **This is the vertical-slice milestone defined in the spec.**

---

## H8 — First-build milestone (Clay's interactive)

**Scope:** Unchanged from Session 7/8 handoffs — install Hello Kiln APK on Pixel via adb + run desktop binary + screenshots both. ~30 minutes.

After H7 lands, this becomes "install the play-a-FLAC build on Pixel + actually press play". The APK from the latest CI run can be downloaded from GitHub Actions artifacts, OR built locally via `./gradlew :app-android:assembleDebug` → `app-android/build/outputs/apk/debug/app-android-debug.apk`.

---

## Critical context for next session (cumulative gotcha list, post-Session-8)

Inherits all 21 gotchas from Session 7 handoff. New this session:

22. **kotlin-inject `@Scope` annotation needs `@Target(CLASS, FUNCTION, PROPERTY_GETTER)`.** Without all three, scoping a `@get:Provides` constructor param fails KSP validation. The Singleton.kt files in both apps follow this convention; copy when adding new scopes.

23. **JdbcSqliteDriver(schema = KilnDatabase.Schema)** auto-creates/migrates the schema via PRAGMA user_version on connect. No need for the `if (!dbFile.exists()) Schema.create(driver)` first-run guard — SQLDelight 2.x handles it. The schema param is the right idiom for persistent JDBC SQLite.

24. **AndroidSqliteDriver.Callback.onOpen runs on every connection open, not just first-time creation.** Putting `PRAGMA foreign_keys = ON` here ensures FKs stay enforced after process restarts. The Callback extends `androidx.sqlite.db.SupportSQLiteOpenHelper.Callback`; SupportSQLiteDatabase is from `androidx.sqlite.db`.

25. **Value-class type-tags `@JvmInline value class UserDataDir(val path: Path)` distinguish ambiguous JVM-type DI bindings.** When two `@get:Provides` constructor params would both be `Path`, kotlin-inject can't tell them apart. Wrap each in a distinct `@JvmInline value class` — zero runtime cost, compile-time disambiguation.

26. **`abstract val` on a kotlin-inject @Component must have a complete provider chain at KSP time.** Adding an abstract member without a provider chain reachable from constructor params + `@Provides` functions fails KSP. Intentionally omit the abstract member until the impl exists (the DesktopAppGraph omits `player` until H5 lands).

27. **`Application.onCreate` is main-thread by Android contract** → safe place for `AndroidAppGraph::class.create(applicationContext)` since the Media3ExoPlayerImpl provider eventually runs on main thread (per ExoPlayer's single-thread access rule, Session 7 discovery #9).

---

## How to start the next session

1. `cd C:\Users\chawo\Projects\kiln`
2. Read this handoff doc first.
3. Read `CLAUDE.md` (~100 lines) for project orientation.
4. Read `docs/sessions/2026-05-19-session-8.md` for the most-recent closeout.
5. Optional: `mem_search "kiln session-8"` to warm engram context.
6. Pick H6 (the big one, ~10-15 hrs). Vendor libFLAC.dll, write JNA bindings + decoder impl + smoke test. Run the empirical-FLAC gate on Clay's library.
7. Commit after each working change. Push at logical milestones (CI runs on every push to main).

**Estimated total remaining effort to "play a FLAC" milestone:** ~17-25 hrs across 2 sessions (down from Session 8 handoff's 25-37 hrs after delivering H4-finish + H3).

---

## ✅ Session 9 success criteria (what a green session looks like)

Minimum-viable Session 9 ends with all of these true:

- [ ] `libFLAC.dll` 1.5.0 + `LICENSE-libflac.txt` BSD-3 vendored at `audio/playback/src/desktopMain/resources/native/win-x64/`.
- [ ] `NativeLibraryLoader` extracts the DLL from the JAR to temp + `System.load`s it without crashing.
- [ ] `LibFlacBinding` JNA interface compiles + at minimum can call `streamDecoderNew` / `streamDecoderDelete` without segfaulting.
- [ ] `JvmFlacDecoderImpl` implements the existing `Decoder` interface from `:audio:playback/commonMain`; `JvmFlacDecodedStream` implements `DecodedStream`.
- [ ] `FlacDecodeSmokeTest` runs locally against ≥1 known FLAC; preferably the full 10-file matrix per scaffold prep §10.
- [ ] Canonical session-validation build (above) BUILD SUCCESSFUL.
- [ ] Session 9 closeout doc + Session 10 handoff doc written.
- [ ] All commits pushed to `origin/main`; CI green.

Stretch (H5 lands too):
- [ ] `JavaSoundPlayerImpl` exists at `audio/playback/src/desktopMain/...`; smoke-tested with synthetic sine PCM.
- [ ] `DesktopAppGraph` exposes `abstract val player: PlatformPlayer` with the full Java-Sound provider chain.

After Session 9 lands, **Session 10 is the H7 milestone**: a single button in both apps plays the first track from Clay's library end-to-end. That's when the spec's vertical-slice milestone is officially crossed.

---

## 📋 Copy-paste prompt for the next session

```
Read docs/sessions/2026-05-19-session-9-handoff.md and execute it as your prompt for this session.
```

That's it. The handoff's Pre-flight block + Read order + Sub-steps + Success criteria are self-contained.

---

**End of Session 9 Handoff.** Per-§11 plan protocol. Next Claude session opens this file first, picks H6, ships.
