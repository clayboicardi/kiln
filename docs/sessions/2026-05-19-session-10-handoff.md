# Session 10 Handoff — Finish vertical slice: H7 + H8

**Authored:** 2026-05-19 (end of Session 9)
**For:** The next Claude session (likely a fresh cold-context session, or Clay actively pairing for the audible-verification dance)
**Goal:** Cross the spec's vertical-slice milestone — wire a single "play first track" button in both apps so Clay can hear a FLAC play through Kiln's entire pipeline end-to-end. Then install on Pixel + screenshots.

This handoff covers **2 pending items** carried forward from Session 9. Session 9 delivered H6 (full JNA libFLAC bridge) + H5 (JavaSoundPlayerImpl + DesktopAppGraph wiring) — a much larger scope than Session 9's handoff projected, leaving only the app-side UI wiring + Clay's interactive validation.

---

## 🚀 Pre-flight (first 5 minutes of the session)

**Read order (cold-start):**

1. This file (Session 10 handoff) — full read.
2. `CLAUDE.md` — ~130 lines now; project orientation + 37 cumulative gotchas.
3. `docs/sessions/2026-05-19-session-9.md` — most-recent closeout.
4. *Optional* `mem_search "kiln session-9"` to warm engram context.
5. *Optional* Scaffold prep §6 (in `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md`) for the original H7 / scan-folder design sketch.

**Confirm clean baseline:**

```powershell
cd C:\Users\chawo\Projects\kiln
git log --oneline -5           # expect 9c6477e (H5.3 test) or this handoff doc at top
git status                     # expect clean tree
./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest
# expect: BUILD SUCCESSFUL in ~5-15s incremental, 48 tests green
# (25 :data:library:desktopTest + 23 :audio:playback:desktopTest)
```

If baseline is dirty or build red — STOP, diagnose, surface to Clay. Don't paper over.

---

## Where we are (current state, 2026-05-19 end-of-session-9)

**Repo:** `https://github.com/clayboicardi/kiln` (public, Apache 2.0)
**Branch:** `main` — Session 9 commits (14) pushed at session-close.
**Build:** Canonical session-validation BUILD SUCCESSFUL.
**Tests:** 25 :data:library:desktopTest + 23 :audio:playback:desktopTest = 48 total. All green.
**CI:** Expected green on the Session 9 closeout push.

**What's done since Session 9 handoff was written:**
- ✅ H6 — JNA libFLAC bridge: vendoring + loader + binding + callbacks + STREAMINFO parser + PCM decode + 24-bit packing + seek + JvmFlacDecoderImpl + JvmFlacDecodedStream + empirical smoke against D:\tiddl (10/10)
- ✅ H5 — JavaSoundPlayerImpl + 8 unit smoke tests + DesktopAppGraph wires player + audioDispatcher

**What's pending (this handoff's scope):**

| # | Item | Phase | Effort | Blocker |
|---|---|---|---|---|
| H7 | End-to-end "play a FLAC" milestone | MVP S7 | ~2-4 hrs | None (code) — but Clay must validate audibly |
| **H8** | **First-build milestone (Pixel install + screenshots)** | Clay's interactive | ~30 min | Clay's Pixel 10 Pro XL + adb |

H7 unblocks the spec's vertical-slice milestone. H8 is Clay's manual sign-off on the deployed build.

---

## H7 — End-to-end "play a FLAC" milestone

**Scope:** With H5+H6 done, wire a single button in both apps that plays the first track in the database. Smoke that confirms the full stack: scan → DB → MusicSource → MediaItem → Playable → Decoder → DecodedStream → PlatformPlayer → audio out.

### Android side (`:app-android`)

**Files to modify:**

- `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt` — replace Hello Kiln with a button + LaunchedEffect.

**Pattern:**

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as KilnApplication).graph
        setContent {
            KilnTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayFirstTrackScreen(graph)
                }
            }
        }
    }
}

@Composable
private fun PlayFirstTrackScreen(graph: AndroidAppGraph) {
    val coroutineScope = rememberCoroutineScope()
    val playerState by graph.player.state.collectAsState()
    val positionMs by graph.player.positionMs.collectAsState()
    val currentItem = graph.player.queue.collectAsState().value.currentItem
    var scanStarted by remember { mutableStateOf(false) }
    val scanResult by remember(scanStarted) {
        if (scanStarted) flow { emit(graph.scanner.scanIncremental()) } else emptyFlow()
    }.collectAsState(initial = null)

    Column(modifier = Modifier.padding(24.dp)) {
        Text("Kiln by Clayworks")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { scanStarted = true }, enabled = !scanStarted) {
            Text(if (scanStarted) "Scanning..." else "Scan Library")
        }
        Spacer(modifier = Modifier.height(8.dp))
        scanResult?.let { result -> Text("Scanned: ${result.javaClass.simpleName}") }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                coroutineScope.launch {
                    val tracks = graph.musicSource
                        .browse(BrowseScope.AllTracks)
                        .take(1)
                        .toList()
                    graph.player.loadQueue(tracks, startIndex = 0, autoPlay = true)
                }
            },
        ) { Text("Play First Track") }
        Spacer(modifier = Modifier.height(16.dp))
        Text("State: $playerState")
        Text("Position: ${positionMs}ms")
        currentItem?.let { Text("Now: ${it.title}") }
    }
}
```

**Notes:**

- `LaunchedEffect` is the canonical pattern for one-shot suspending work in Compose.
- The scan + browse + loadQueue chain should be inside the button onClick, NOT in a LaunchedEffect at composition (we don't want it firing on every recomposition).
- READ_MEDIA_AUDIO permission is needed for Android 13+; the existing app may already request it (check AndroidManifest.xml). If not, request at runtime before `scanIncremental()`.
- Android's MediaStore scan returns track files from the device's music library; the user MAY need to side-load some FLACs onto the Pixel for the test to find anything.

### Desktop side (`:app-desktop`)

**Files to modify:**

- `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt` — instantiate DesktopAppGraph once at `application {}` top; same button pattern.

**Pattern:**

```kotlin
fun main() = application {
    val graph = remember {
        DesktopAppGraph::class.create(
            userDataDir = UserDataDir(Path.of(System.getProperty("user.home"), ".kiln")),
            scanFolders = ScanFolders(listOf(Path.of("D:\\tiddl"))),  // TODO: Settings UI at MVP Session 26-28
        )
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kiln by Clayworks",
    ) {
        KilnTheme {
            PlayFirstTrackScreen(graph)
        }
    }
}
```

(Where `PlayFirstTrackScreen(graph: DesktopAppGraph)` mirrors the Android composable above, but constructed against DesktopAppGraph's `player` / `scanner` / `musicSource` instead of AndroidAppGraph's.)

**Notes:**

- `D:\tiddl` is Clay's hardcoded music root (per CLAUDE.md gotcha). A future Settings UI lets the user choose.
- `userDataDir = ~/.kiln` puts `kiln.db` + future caches under the user home — matches Clay's hardware (Windows 11; `%USERPROFILE%\.kiln`).

### Compose collection pattern (the trickiest bit)

- `graph.musicSource.browse(BrowseScope.AllTracks)` returns `Flow<MediaItem>` — emits per-track. Need to either `.toList()` (collecting the cold flow once) or use a paged `LazyColumn` for the real library view (out of scope for H7's "play first track" button).
- `Flow<MediaItem>.take(1).toList()` is the minimal "give me the first track" shape. Run inside a coroutine (button onClick scope).

### Validation

**Both platforms must:**

- Audible playback (Clay listens — this is the load-bearing validation).
- State flow progression: `Idle → Loading → Buffering → Ready(isPlaying=true)` (visible in the UI text labels).
- positionMs ticks ~every 250ms as the track plays (the playback loop publishes positionMs as samples are written).

**Failure modes to expect on first run:**

- Permissions error on Android (READ_MEDIA_AUDIO not granted).
- Empty queue on Android (no FLACs on the Pixel's MediaStore — sideload some).
- Empty queue on Desktop (`D:\tiddl` exists but no FLACs were indexed — run the scan button first).
- libFLAC.dll load failure on Desktop (if the JAR didn't bundle the resource correctly — but the H6 tests have already validated this path).
- `Audio device unavailable` on Desktop (rare; usually means no default audio output device).

**This is the vertical-slice milestone defined in the spec.**

---

## H8 — First-build milestone (Clay's interactive)

**Scope:** Unchanged from Session 7/8/9 handoffs — install Hello Kiln APK on Pixel via adb + run desktop binary + screenshots both. ~30 minutes.

After H7 lands, this becomes "install the play-a-FLAC build on Pixel + actually press play". The APK from the latest CI run can be downloaded from GitHub Actions artifacts, OR built locally via `./gradlew :app-android:assembleDebug` → `app-android/build/outputs/apk/debug/app-android-debug.apk`.

**adb install one-liner:**

```bash
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk
```

**Permissions for FLAC playback on Pixel:**

- READ_MEDIA_AUDIO (Android 13+) — request via runtime permission flow or grant manually in Settings.

**Desktop runtime:**

```powershell
./gradlew :app-desktop:run
# Or: java -jar app-desktop/build/libs/app-desktop-*.jar
```

---

## Critical context for next session (cumulative gotcha list, post-Session-9)

Inherits all 27 gotchas from prior sessions + the 10 new ones promoted to CLAUDE.md this session. Highlights for H7:

- **kotlin-inject DI graphs are eager at first access.** `(application as KilnApplication).graph` triggers all @Singleton @Provides chains the first time it's referenced — including the SqlDriver opening the DB. Don't call from a freezable thread.
- **MediaStore scan needs READ_MEDIA_AUDIO on Android 13+.** Without it, `AndroidMediaStoreScanner.scanIncremental()` returns no tracks.
- **`Flow<MediaItem>.take(1).toList()` is safe for collection** — it terminates after 1 emission. Don't try `first()` if the flow can be empty (will throw NoSuchElementException).
- **`scanFolders = ScanFolders(listOf(Path.of("D:\\tiddl")))`** is the canonical Desktop scan-root per CLAUDE.md. Hardcode in Main.kt until Settings UI.
- **JavaSoundPlayerImpl honors the `state` StateFlow** — UI Compose surfaces should observe `player.state.collectAsState()` for live feedback.
- **The Desktop graph's player provider is non-lazy** — first reference triggers LibFlacLoader.load() which extracts the DLL + sets jna.library.path. ~50ms overhead on first reference; subsequent refs are O(1).
- **Position tick is 250ms** in JavaSoundPlayerImpl — UIs that animate position bars should expect that granularity (no per-millisecond updates).
- **Test fixture audio is silent sine waves**, which means audible verification for H7 needs Clay's actual library FLACs (or a known song he can recognize).

---

## How to start the next session

1. `cd C:\Users\chawo\Projects\kiln`
2. Read this handoff doc first.
3. Read `CLAUDE.md` (~130 lines) for project orientation + cumulative gotchas.
4. Read `docs/sessions/2026-05-19-session-9.md` for the most-recent closeout.
5. Pick H7 (the vertical-slice milestone). Wire MainActivity.kt + Main.kt with the scan + play-first-track flow. Verify locally that the desktop app at least starts + the button is clickable.
6. Coordinate with Clay for audible verification on his Pixel + Windows machine. Iterate if anything breaks.
7. Commit after each working change. Push when both platforms work.

**Estimated total remaining effort to vertical-slice milestone:** ~2-4 hrs (just H7 — H8 is Clay's 30-min interactive smoke).

---

## ✅ Session 10 success criteria (what a green session looks like)

Minimum-viable Session 10 ends with all of these true:

- [ ] `MainActivity.kt` on Android has a Scan + Play button, observes player state via Compose collectAsState.
- [ ] `Main.kt` on Desktop has the same pattern + uses `Path.of("D:\\tiddl")` as the scan root.
- [ ] Both apps launch cleanly (no startup crash).
- [ ] Pressing "Scan Library" populates the DB (state visible via existing tests' track counts on subsequent runs).
- [ ] Pressing "Play First Track" causes audible playback on at least one platform (Desktop is easier to validate first; Pixel deferred to H8).
- [ ] Canonical session-validation build SUCCESSFUL.
- [ ] Session 10 closeout doc + post-H7 handoff doc (if any further sessions are needed before Phase 2a).
- [ ] All commits pushed to `origin/main`; CI green.

Stretch (H8 lands too):
- [ ] Pixel install via adb succeeds.
- [ ] Pixel side-loaded with a FLAC; tapping Play produces sound.
- [ ] Screenshots of both apps in the playback state.

After Session 10 lands H7+H8, the spec's vertical-slice milestone is officially crossed — Kiln plays FLACs on both platforms via its own pipeline (not a vendor SDK black-box). Phase 2a begins.

---

## 📋 Copy-paste prompt for the next session

```
Read docs/sessions/2026-05-19-session-10-handoff.md and execute it as your prompt for this session.
```

That's it. The handoff's Pre-flight block + Read order + Patterns + Success criteria are self-contained.

---

**End of Session 10 Handoff.** Per-§11 plan protocol. Next Claude session opens this file first, picks H7, ships.
