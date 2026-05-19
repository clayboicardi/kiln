# Session 10 Handoff — Re-review pass + finish vertical slice (H7 + H8)

**Authored:** 2026-05-19 (end of Session 9 review-fix batch — supersedes the initial Session 10 handoff written before the review pass)
**For:** The next Claude session (likely a fresh cold-context session, or Clay actively pairing for the audible-verification dance)
**Goal:** Three sequential tasks: **(0) a post-fix re-review pass to confirm nothing regressed and surface any second-order issues introduced by the 8 review fixes**, then **(1) H7 the spec's vertical-slice milestone — wire a single "play first track" button in both apps**, then **(2) H8 Clay's Pixel install + screenshots**.

**Why Task 0 exists:** Session 9 closed with the spec's vertical-slice milestone *almost* reached (H6 + H5 done; H7 pending). Between the closeout and now, a multi-agent review pass (Gemini Code Assist auto-review + /ultrareview #1 of 3 against synthetic PR #1) surfaced 9 unique findings (zero overlap between reviewers). 8 fixes landed (commits `db464b6..f138b76`); the 9th was a deferred-not-worth-touching nit. The codebase is now substantially more polished, but the 8 fixes touched load-bearing concurrency + resource paths in `JavaSoundPlayerImpl` and the FTS rebuild in `ScanInternals` — a re-review pass before H7 confirms those fixes didn't introduce second-order bugs that the unit tests can't catch.

---

## 🚀 Pre-flight (first 5 minutes of the session)

**Read order (cold-start):**

1. This file (Session 10 handoff) — full read.
2. `docs/sessions/2026-05-19-session-9-addendum-review-fixes.md` — per-finding log for the 8 review fixes that landed post-Session-9-closeout.
3. `CLAUDE.md` — ~135 lines now; project orientation + cumulative gotchas (post-fix state).
4. `docs/sessions/2026-05-19-session-9.md` — original Session 9 closeout (H6 + H5; written before the review pass).
5. *Optional* `mem_search "kiln session-9 review-fixes"` to warm engram context.

**Confirm clean baseline:**

```powershell
cd C:\Users\chawo\Projects\kiln
git log --oneline -10           # expect 138efdd (addendum) at top, then the 8 fix commits
git status                      # expect clean tree
git branch -a                   # expect main + review-base-empty + remotes/origin/* mirrors
./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest
# expect: BUILD SUCCESSFUL in ~5-15s incremental, 48 tests green
# (25 :data:library:desktopTest + 23 :audio:playback:desktopTest)
```

If baseline is dirty or build red — STOP, diagnose, surface to Clay. Don't paper over.

---

## Where we are (current state, 2026-05-19 post-review-fix-batch)

**Repo:** `https://github.com/clayboicardi/kiln` (public, Apache 2.0)
**Branch:** `main` at commit `138efdd` (Session 9 addendum). Origin in sync.
**Build:** Canonical session-validation BUILD SUCCESSFUL.
**Tests:** 48 total. 25 :data:library:desktopTest + 23 :audio:playback:desktopTest. All green.
**Empirical FLAC smoke:** **10/10 of Clay's actual `D:\tiddl` FLACs decoded successfully** — identical pre- and post-fix.
**CI:** Expected green on the post-fix push.

**Synthetic review infrastructure (still in place):**
- `review-base-empty` branch at root commit `f27e176`. Reusable for future `/ultrareview` runs — just create a new PR from `main` against it.
- PR #1 closed (without merging) on 2026-05-19. Findings + commit-range documented inline + in the addendum.

**Review budget remaining:**
- `/ultrareview`: **2 of 3 credits left** (1 consumed on PR #1).
- Gemini Code Assist: unlimited via the GitHub App's auto-review on every PR.

**What's done since the original Session 10 handoff was written:**
- ✅ 8 review fixes landed (full table in the addendum doc):
  - **U1**: FTS rebuild now atomic (delete-all + bulk insert inside single `db.transaction`)
  - **U4**: Shared `teardownActivePlayback` helper closes line + stream on exception path
  - **G5**: FLAC decoder errors now thrown as typed `FlacDecodeException` (new file at `nativeio/FlacDecodeException.kt`) → propagates to `PlayerState.Error` instead of silent EOF
  - **G2**: Processor return value chained through to `line.write` (latent bug for when `:audio:dsp` processors land)
  - **G1+G3**: `MutableStateFlow<Boolean> _paused` replaces `@Volatile var pauseRequested` busy-poll; `play()/pause()/setRepeatMode/setShuffleMode` dropped `withContext(audioDispatcher)` (no more stall behind blocking `sourceLine.write`)
  - **U3**: `loadQueue` threads the pre-resolved Playable for the start item to `startPlaybackForCurrentIndex` (N+1 → N SQL queries)
  - **G4**: `selectAllForFtsRebuild` now cursor-iterated → O(1) memory regardless of library size
  - **U2**: `AudioFrame.equals/hashCode` comment honest + comparison bounded by `[0, byteCount)` for future buffer-pool safety

**What's pending (this handoff's scope):**

| Task | Item | Phase | Effort | Blocker |
|---|---|---|---|---|
| 0 | Re-review pass (ultrareview #2 + Gemini auto on new PR) | Quality gate | ~30-90 min | None |
| 1 (was H7) | End-to-end "play a FLAC" milestone | MVP S7 | ~2-4 hrs | None (code) — Clay validates audibly |
| 2 (was H8) | **First-build milestone (Pixel install + screenshots)** | Clay's interactive | ~30 min | Clay's Pixel 10 Pro XL + adb |

---

## Task 0 — Re-review pass

**Why:** The 8 fixes that landed post-Session-9-closeout touched concurrency primitives (pause signaling refactor), resource lifecycle (teardown), error propagation (silent EOF → typed exception), and data integrity (FTS transaction atomicity). All are unit-test green AND empirically validated against Clay's library, but second-order issues — race conditions, error-path resource hygiene under stress, edge cases the reviewers didn't think to test the first time — can hide behind passing tests.

A second multi-agent pass on the post-fix code is the cheapest way to flush those out. The remaining 2 /ultrareview credits make this nearly-free; it's also a natural cross-validator of Session 9's review-fix work.

### Step 0.1 — Create the synthetic PR

```powershell
# review-base-empty already exists at the root commit f27e176; reusable.
# Create a NEW synthetic PR from current main → review-base-empty.
gh pr create `
  --base review-base-empty `
  --head main `
  --title "Full-codebase re-review post-Session-9-fixes (do not merge)" `
  --body "Synthetic PR target for /ultrareview #2 + Gemini Code Assist re-review after the Session 9 post-closeout fix batch (commits db464b6..138efdd). Diff = entire current state of main. Close without merging once findings have been processed."
```

Note the new PR number from the gh output (likely PR #2 since PR #1 is closed).

### Step 0.2 — Trigger the reviews

**Gemini Code Assist will auto-review** the new PR within ~5-15 min of PR creation (assuming the GitHub App's auto-review is still on — Clay verified this is the case during PR #1).

**/ultrareview #2 of 3** — Clay runs in his terminal:

```
/ultrareview <PR# from Step 0.1>
```

Both can run in parallel.

### Step 0.3 — Triage findings

When both reports are in, Claude should:

1. **Dedupe**: scan both reports for overlapping issues (PR #1 had zero overlap; PR #2 might or might not).
2. **Severity-grade**: rank as P0 (data correctness / resource correctness / user-facing regression), P1 (correctness + UX), P2 (cleanup / polish), Nit (defer).
3. **Surface to Clay before fixing**: synthesize a triage table; ask for approval on the fix sequence (do not auto-apply — the PR #1 round established the pattern that Clay reviews triage before fixes land).
4. **Apply fixes**: one commit per logical fix, canonical validation between commits, empirical D:\tiddl smoke re-run after the batch.

**Important**: if any finding REGRESSES one of the 8 fixes already applied (e.g., "the new MutableStateFlow pause introduces race X"), surface that immediately + propose remediation; do not just stack a fix on top.

**Budget guidance**: if the new pass finds ≥3 P0/P1 issues, treat that as a sign the post-fix code needs more careful examination than a single review iteration can provide — pause + ask Clay whether to do a third /ultrareview pass (would use the last credit) OR proceed with cautious manual fixes + targeted tests.

### Step 0.4 — Close the new synthetic PR

After fixes land:

```powershell
gh pr close <PR#> --comment "Re-review pass post-Session-9-fixes. Findings processed; fixes landed via direct commits to main. See [the new fixes' commit range or addendum-2 doc]. Closing without merge."
```

### Step 0.5 — Write a second addendum (if substantive fixes landed)

Create `docs/sessions/2026-05-19-session-10-addendum-re-review-fixes.md` mirroring the Session 9 addendum's structure (per-finding log, source attribution, verification block). If the re-review produced ZERO new findings (best case), record that fact in 2-3 lines as a clean-pass confirmation.

**Success criterion for Task 0**: re-review surfaced zero P0/P1 issues OR all P0/P1 issues have been fixed + canonical validation + 10/10 D:\tiddl smoke + new addendum doc + closed PR.

---

## Task 1 — H7 end-to-end "play a FLAC" milestone

**Scope:** With H6 + H5 (+ post-fix polish) done, wire a single button in both apps that plays the first track in the database. Smoke that confirms the full stack: scan → DB → MusicSource → MediaItem → Playable → Decoder → DecodedStream → PlatformPlayer → audio out.

### Android side (`:app-android`)

**File to modify:** `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt`

Current contents: Hello Kiln. Replace with the scan + play button pattern.

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
    var scanState by remember { mutableStateOf<String>("Not scanned") }

    Column(modifier = Modifier.padding(24.dp)) {
        Text("Kiln by Clayworks", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            coroutineScope.launch {
                scanState = "Scanning..."
                val result = graph.scanner.scanIncremental()
                scanState = "Scan: ${result.javaClass.simpleName}"
            }
        }) { Text("Scan Library") }
        Spacer(modifier = Modifier.height(8.dp))
        Text(scanState)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                coroutineScope.launch {
                    val tracks = graph.musicSource
                        .browse(BrowseScope.AllTracks)
                        .take(1)
                        .toList()
                    if (tracks.isNotEmpty()) {
                        graph.player.loadQueue(tracks, startIndex = 0, autoPlay = true)
                    }
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
- `LaunchedEffect` is the canonical pattern for one-shot suspending work in Compose, BUT here the scan + load should be button-driven (not on every recomposition), so we use `coroutineScope.launch { }` inside `onClick`.
- READ_MEDIA_AUDIO permission is needed for Android 13+; check if `app-android/src/main/AndroidManifest.xml` already declares it. If not, declare + request at runtime before scan.
- The Pixel's MediaStore might not have any FLACs unless Clay side-loads some. The H8 task covers that.

### Desktop side (`:app-desktop`)

**File to modify:** `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt`

Current contents: Hello Kiln Desktop. Replace with the graph instantiation + button pattern.

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
            Surface(modifier = Modifier.fillMaxSize()) {
                PlayFirstTrackScreen(graph)
            }
        }
    }
}

@Composable
private fun PlayFirstTrackScreen(graph: DesktopAppGraph) {
    // Mirror the Android composable; differs only in graph type and that
    // graph.scanner returns the JvmFilesystemScanner instead of AndroidMediaStoreScanner.
    // ... same body as Android side ...
}
```

**Notes:**
- `D:\tiddl` is Clay's hardcoded music root (per CLAUDE.md gotcha). Future Settings UI replaces this.
- `userDataDir = ~/.kiln` puts `kiln.db` + future caches under the user home — matches Clay's Windows 11 box (`C:\Users\chawo\.kiln`).
- The composable body should be SHARED logic — consider extracting `PlayFirstTrackScreen(player, scanner, musicSource)` to `:ui:components` if it makes sense, OR inline it twice for now and refactor later.

### Compose collection pattern (the trickiest bit)

- `graph.musicSource.browse(BrowseScope.AllTracks)` returns `Flow<MediaItem>` — emits per-track. Need to either `.toList()` (collecting the cold flow once) or use a paged `LazyColumn` for the real library view (out of scope for H7's "play first track" button).
- `Flow<MediaItem>.take(1).toList()` is the minimal "give me the first track" shape. Run inside a coroutine (button onClick scope).
- `graph.player.state.collectAsState()` gives a `State<PlayerState>` that Compose recomposes on changes. Same for positionMs / queue.

### Validation (load-bearing)

**Both platforms must:**

- ✅ Launch without crashing (`./gradlew :app-android:installDebug` + `:app-desktop:run`).
- ✅ Scan + populate the DB (verifiable: subsequent runs show non-zero tracks).
- ✅ Play First Track button triggers state-flow progression: `Idle → Loading → Buffering → Ready(isPlaying=true)`. Visible in the UI.
- ✅ **Audible playback** on at least Desktop (Pixel install is H8).
- ✅ `positionMs` ticks ~every 250ms as the track plays.

**Failure modes to expect on first run:**

- Permissions error on Android (READ_MEDIA_AUDIO not granted).
- Empty queue on Android (no FLACs on the Pixel's MediaStore — sideload some at H8).
- Empty queue on Desktop (`D:\tiddl` exists but no FLACs were indexed — run the Scan button first).
- libFLAC.dll load failure on Desktop (if the JAR didn't bundle the resource correctly — but the H6 tests have already validated this; would only manifest if the resources path got accidentally broken).
- `Audio device unavailable` on Desktop (rare; usually means no default audio output device).

**This is the vertical-slice milestone defined in the spec.**

---

## Task 2 — H8 First-build milestone (Clay's interactive)

**Scope:** Install Hello Kiln APK on Pixel via adb + run desktop binary + screenshots both. ~30 minutes.

After Task 1 lands, this becomes "install the play-a-FLAC build on Pixel + actually press play." The APK from the latest CI run can be downloaded from GitHub Actions artifacts, OR built locally.

### Android side

```powershell
# Build the APK
./gradlew :app-android:assembleDebug

# Install (auto-replaces previous version)
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk

# Launch
adb shell am start -n com.clayworks.kiln/.MainActivity
```

**Permissions for FLAC playback on Pixel:**

- READ_MEDIA_AUDIO (Android 13+) — request via the runtime permission flow OR grant manually in Settings → Apps → Kiln → Permissions.
- If declared in AndroidManifest.xml + handled at runtime via `ContextCompat.checkSelfPermission` + `ActivityCompat.requestPermissions`, Task 1's MainActivity should drive the permission UX.

**Side-loading test FLACs to Pixel:**

```powershell
# Push a small subset of Clay's library to Pixel /sdcard/Music
adb push "D:\tiddl\<some-artist>\<some-album>\<track1.flac>" /sdcard/Music/
# Trigger MediaStore rescan
adb shell content call --uri content://media --method media_scanner_scan_file --arg /sdcard/Music/
```

(adb-master skill at `~/agent/.../skills/adb-master/` has thorough syntax notes — invoke if you hit any adb friction.)

### Desktop side

```powershell
# Run via Gradle
./gradlew :app-desktop:run

# OR: build + run the JAR directly
./gradlew :app-desktop:assemble
java -jar app-desktop/build/libs/app-desktop-*.jar
```

### Screenshots

Save under `docs/screenshots/2026-05-19-h8/` (create the dir):

- Android: `adb shell screencap -p /sdcard/screen.png && adb pull /sdcard/screen.png docs/screenshots/2026-05-19-h8/pixel-play-flac.png`
- Desktop: standard OS screenshot tool (Win+Shift+S on Windows 11) — save to `docs/screenshots/2026-05-19-h8/desktop-play-flac.png`

---

## Critical context for next session (cumulative gotcha list — post-fix state)

Inherits the 37 cumulative gotchas already in CLAUDE.md (covering MVP Sessions 1-7). The 8 review fixes did NOT introduce new gotchas worth pinning to CLAUDE.md — the existing entries cover the patterns. But for Task 1 specifically, here are the load-bearing things to remember:

### DI graph access

- **`(application as KilnApplication).graph`** triggers all `@Singleton @Provides` chains on first access. The Android side opens the SQLite DB + initializes the LibraryScanner; Desktop additionally triggers `LibFlacLoader.load()` (extracts libFLAC.dll to temp + sets jna.library.path). Cost: ~50-100ms on first reference. Subsequent references are O(1).
- **Don't access the graph from a freezable thread** — keep it on Main / main-thread-equivalent.

### Compose collection idioms

- `Flow<MediaItem>.take(1).toList()` is safe for collection — it terminates after 1 emission. Don't try `first()` if the flow can be empty (throws `NoSuchElementException`).
- `graph.player.state.collectAsState()` — Compose recomposes on each new state. Don't put expensive computation in the recomposition path.

### JavaSoundPlayerImpl threading model (post-G1+G3 refactor)

- `play()` / `pause()` / `setRepeatMode` / `setShuffleMode` are now **non-blocking flag-flips** — they don't marshal through `audioDispatcher` anymore. The pause signal flows via `MutableStateFlow<Boolean> _paused`; the playback loop suspends on `_paused.first { !it }`.
- `stop()` / `seekTo()` / `skipToNext/Previous/skipTo` / `loadQueue` / `release` / `setVolume` / `setMuted` STILL marshal through `audioDispatcher` (they touch line/stream lifecycle or FloatControl).
- Tests that exercise pause/play in unit tests should still work since the contract is unchanged at the API level.

### FTS rebuild atomicity (post-U1 fix)

- The CLAUDE.md gotcha was UPDATED to reflect the new atomic behavior. The prior "during-scan search returns briefly stale results" claim was wrong (pre-fix behavior was empty results, not stale).
- Post-fix: search remains consistent during rebuild; mid-scan crash rolls back to pre-scan state.

### Position tick cadence

- `_positionMs` is updated at most every 250ms (POSITION_TICK_MS in `JavaSoundPlayerImpl.kt`). UIs that animate position bars should expect that granularity (no per-millisecond updates).
- If a position bar needs smoother animation, interpolate UI-side between ticks; don't shorten the tick period (the audio loop's positionMs update is intentionally rate-limited to keep the StateFlow's recomposition cost bounded).

### Test fixture audio is silent sine

- The bundled `desktopTest/resources/fixtures/sine_440_stereo_*.flac` are 0.5-second silent sine waves — useful for unit-level decode verification but useless for audible playback tests.
- For audible verification (Task 1 + Task 2), use Clay's actual library FLACs or sideloaded known songs.

### CI runs (from build.yml)

- Ubuntu agent runs `:app-android:assembleDebug` (builds APK).
- Windows agent runs `:app-desktop:assemble` (builds JAR).
- **NEITHER agent runs desktopTest** — so Clay's empirical D:\tiddl smoke + local canonical-validation build are the actual quality gates. CI is a build-correctness gate, not a test gate.

---

## How to start the next session

1. `cd C:\Users\chawo\Projects\kiln`
2. Read this handoff doc first.
3. Read `docs/sessions/2026-05-19-session-9-addendum-review-fixes.md` for the per-finding log of the 8 fixes.
4. Read `CLAUDE.md` (~135 lines) for project orientation + cumulative gotchas.
5. *Optional* `mem_search "kiln session-9 review-fixes"` to warm engram context.
6. **Task 0**: Create the new synthetic PR + launch /ultrareview #2 + wait for Gemini auto-review.
7. **While reviews run**: cold-read the codebase + the 8 fix commits (`git show <commit>` for each in `db464b6..138efdd`) to build mental model.
8. **When reviews arrive**: triage, surface to Clay, fix per-approval, validate, commit, push.
9. **Task 1 (H7)**: Wire MainActivity.kt + Main.kt with the scan + play-first-track flow. Coordinate with Clay for audible verification on his machine.
10. **Task 2 (H8)**: Clay's interactive Pixel install + screenshots.

**Estimated total session effort:** Task 0 ~30-90 min depending on findings; Task 1 ~2-4 hrs; Task 2 ~30 min. So 3-6 hrs total for the whole vertical slice. If Task 0 surfaces a lot of issues, Tasks 1+2 may push to Session 11.

---

## ✅ Session 10 success criteria

Minimum-viable Session 10 ends with all of these true:

- [ ] **Task 0**: Re-review pass produced findings, findings were triaged + surfaced to Clay, P0/P1 fixes (if any) were applied + canonical-validated + pushed. New synthetic PR closed. Second addendum doc written (or "clean pass" note recorded).
- [ ] **Task 1 (H7)**: `MainActivity.kt` on Android + `Main.kt` on Desktop have a Scan + Play button, observe player state via Compose `collectAsState`.
- [ ] Both apps launch cleanly (no startup crash).
- [ ] Pressing "Scan Library" populates the DB (verifiable by inspecting kiln.db OR by track counts on subsequent button-clicks).
- [ ] Pressing "Play First Track" causes audible playback on at least Desktop. State-flow transitions visible in UI.
- [ ] Canonical session-validation build SUCCESSFUL.
- [ ] Session 10 closeout doc + (potentially) Session 11 handoff doc (if anything left to do).
- [ ] All commits pushed to `origin/main`; CI green.

Stretch (Task 2 lands too):
- [ ] **Task 2 (H8)**: Pixel install via adb succeeds.
- [ ] Pixel side-loaded with a FLAC; tapping Play produces sound.
- [ ] Screenshots of both apps in the playback state.

After Task 1 + Task 2 land, the spec's vertical-slice milestone is officially crossed — **Kiln plays FLACs on both platforms via its own pipeline (not a vendor SDK black-box)**. Phase 2a begins.

---

## Risk-surface notes (Dialectical Analyst format — strengths vs. weaknesses)

**Strengths of the current state:**

- 48 unit + smoke tests all green.
- 10/10 of Clay's actual D:\tiddl library decoded successfully via the H6.9 smoke.
- Two independent multi-agent reviews (Gemini + ultrareview #1) caught 9 unique issues; 8 were fixed; the codebase is meaningfully more polished than at Session 9 closeout.
- Architecture boundaries (Source Protocol, Concentric Modules, Engine-Swap-Shape) intact through the entire fix batch — none of the 8 fixes required loosening any of those invariants.
- CI build green on every push (Ubuntu Android + Windows Desktop assemble).

**Conversely — known risks/limitations:**

- **No live-stream concurrency tests for JavaSoundPlayerImpl.** The pause-signaling refactor's correctness rests on (a) StateFlow's documented thread-safety, (b) javax.sound.sampled.Line's documented thread-safety, (c) the playback loop's gate logic. All three are individually sound but their composition has not been stress-tested with a synthetic-infinite-stream + concurrent-control-method-spam harness. The empirical D:\tiddl smoke validates the happy path only.
- **No CI test gate for `:audio:playback:desktopTest`.** The CI agents only run `:app-android:assembleDebug` and `:app-desktop:assemble`. Audio-playback tests don't run on PR. Clay's local canonical-validation build is the actual quality gate. This is structural and not in scope to fix here; Phase 2a may broaden CI.
- **Single-platform native binary.** libFLAC.dll is vendored for Win-x64 only. Linux/macOS Desktop targets are explicitly out of MVP scope (per spec §2 hard locks). If those targets ever enter scope, sibling DLLs/SOs/DYLIBs need to be vendored under `audio/playback/src/desktopMain/resources/native/<platform>/` and a case added to `NativeLibraryLoader.detectPlatform()`.
- **Smoke test format-diversity gap.** The H6.9 smoke walks D:\tiddl alphabetically and picks the first 10 FLACs, which all happen to be 16/44 stereo (Clay's 2 Chainz albums at the alphabetic start). 24-bit / 24/192 / multichannel matrix coverage comes only from the bundled sine fixtures. A Phase 2a follow-up should broaden the smoke to actively pick format-diverse files.

---

## 📋 Copy-paste prompt for the next session

```
Read docs/sessions/2026-05-19-session-10-handoff.md and execute it as your prompt for this session.
```

That's it. The handoff's Pre-flight block + Read order + Task descriptions + Success criteria are self-contained.

---

**End of Session 10 Handoff (post-review-fixes revision).** Per-§11 plan protocol. Next Claude session opens this file first, executes Task 0 → Task 1 → Task 2 in order. After both Task 1 + Task 2 land, the spec's vertical-slice milestone is officially crossed and Phase 2a begins.
