# Library Scan Trigger Wiring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the already-built `LibraryScanner` to real user triggers on BOTH platforms — a "Scan now" button, a "Scan library on launch" toggle (consume the existing setting), and a new toggleable "Auto-scan when a folder is added" behavior.

**Architecture:** The scanner (`AndroidMediaStoreScanner` / `JvmFilesystemScanner`) is fully implemented, tested, and DI-provided on both graphs as `abstract val scanner: LibraryScanner`, but **no production code ever calls `scanIncremental()`/`scanFull()`** (root cause of issue #27; desktop only appears to work because `~/.kiln/kiln.db` holds 27,766 stale rows from a pre-Track-C build). This plan adds the missing trigger wiring, mirroring the proven in-repo backfill-button pattern (`onTriggerBackfill` → `coroutineScope.launch { … state machine }`). New per-trigger settings give granular control per Clay's directive.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, SQLDelight (generic key/value `settings` table), kotlin-inject, Arrow `Either`, kotlinx.coroutines, JUnit4 + Compose UI test.

## Global Constraints

- **minSdk 23 / compileSdk 36; JVM 21.** No `java.time.*` in `:ui:components` or Android-reachable code (NoClassDefFoundError on API 23–25). Pin `Locale.US` on any `String.format`.
- **`allWarningsAsErrors`** is on — the build fails on any warning.
- **No new schema migration** — new settings are new keys in the generic key/value `settings` table (`Keys.kt`: "no schema change needed because the table is generic key/value").
- **One change per commit** (CLAUDE.md hard rule). Each task = one commit.
- **`JAVA_HOME` → Temurin JDK 21** (`C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`) before any `./gradlew`. PowerShell: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'`. Git Bash: `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"`.
- **Scanner returns a single `Either<ScanError, ScanResult>`** (no progress Flow) → scan UI is coarse: `Idle → Scanning → Done(counts)/Error`.
- **`scanIncremental()` is the trigger for all three paths** (cheap idempotent re-walk; on an empty DB it is equivalent to `scanFull()`). `scanFull()` is reserved for a future explicit "Rebuild library" action.

---

### Task 1: New persisted setting `autoScanOnFolderAdd` (`:data:library`)

**Files:**
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/internal/Keys.kt`
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt`
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImpl.kt`
- Test: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImplTest.kt`

**Interfaces:**
- Produces: `SettingsRepository.autoScanOnFolderAdd: Flow<Boolean>` (default `true`) + `suspend fun setAutoScanOnFolderAdd(enabled: Boolean)`. Mirrors `scanOnLaunch` exactly except the default is `true` (adding a folder implies wanting it indexed; the toggle lets power users disable it).

- [ ] **Step 1: Write the failing test** — append to `SettingsRepositoryImplTest.kt` (mirror the existing `scan_on_launch_round_trip` at line 47):

```kotlin
    @Test
    fun auto_scan_on_folder_add_defaults_true_then_round_trips() = runTest {
        // Default before any write is true (adding a folder implies indexing it).
        assertEquals(true, repo.autoScanOnFolderAdd.first())
        repo.setAutoScanOnFolderAdd(false)
        assertEquals(false, repo.autoScanOnFolderAdd.first())
        repo.setAutoScanOnFolderAdd(true)
        assertEquals(true, repo.autoScanOnFolderAdd.first())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :data:library:desktopTest --tests "*SettingsRepositoryImplTest*"`
Expected: FAIL — `autoScanOnFolderAdd` / `setAutoScanOnFolderAdd` unresolved.

- [ ] **Step 3a: Add the key** — `Keys.kt`, inside `object SettingKey`, after `SCAN_ON_LAUNCH`:

```kotlin
    const val AUTO_SCAN_ON_FOLDER_ADD = "auto_scan_on_folder_add"
```

- [ ] **Step 3b: Add the interface members** — `SettingsRepository.kt`, after the `scanOnLaunch` setter (line 37):

```kotlin
    /**
     * Whether adding a folder via the picker immediately triggers a scan of
     * the library; default true. Toggle in Settings → Behavior.
     */
    val autoScanOnFolderAdd: Flow<Boolean>
    suspend fun setAutoScanOnFolderAdd(enabled: Boolean)
```

- [ ] **Step 3c: Implement** — `SettingsRepositoryImpl.kt`, after `setScanOnLaunch` (line 47). Default `true`: an absent row (null) maps to `true`, a stored `"false"` maps to `false`:

```kotlin
    override val autoScanOnFolderAdd: Flow<Boolean> =
        db.settingsQueries.selectByKey(SettingKey.AUTO_SCAN_ON_FOLDER_ADD)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { value -> value != "false" }

    override suspend fun setAutoScanOnFolderAdd(enabled: Boolean): Unit = withContext(ioDispatcher) {
        db.settingsQueries.upsert(key = SettingKey.AUTO_SCAN_ON_FOLDER_ADD, value_ = enabled.toString())
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :data:library:desktopTest --tests "*SettingsRepositoryImplTest*"`
Expected: PASS.

- [ ] **Step 5: Build the module + commit**

```bash
.\gradlew :data:library:build
git add data/library
git commit -m "phase-2b-a(scan): add autoScanOnFolderAdd persisted setting"
```

---

### Task 2: `ScanUiState` + Settings UI surface (`:ui:components`)

**Files:**
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/ScanUiState.kt`
- Modify: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt`
- Modify: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt`
- Test: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1 (UI is platform-neutral; app modules bridge in Tasks 3–4).
- Produces:
  - `sealed interface ScanUiState { Idle; Scanning; Done(added,updated,softDeleted,unchanged,durationMs); Error(message) }`.
  - `SettingsState` gains `autoScanOnFolderAdd: Boolean` and `scan: ScanUiState` (appended at end **with temporary defaults** so app-module call sites stay green; defaults removed in Task 5).
  - `SettingsScreen` gains `onAutoScanOnFolderAddChange: (Boolean) -> Unit` and `onTriggerScan: () -> Unit` (appended before `modifier` **with temporary `= {}` defaults**; removed in Task 5).

- [ ] **Step 1: Create `ScanUiState.kt`**

```kotlin
package com.clayworks.kiln.ui.components.settings

/**
 * Coarse scan-progress state for the Settings "Scan now" affordance. The
 * LibraryScanner returns a single Either<ScanError, ScanResult> (no progress
 * Flow), so the UI shows Idle → Scanning → Done/Error rather than a live
 * per-file bar. Mirrors BackfillUiState's role for the analyzer.
 */
sealed interface ScanUiState {
    /** No scan running; show the trigger button. */
    data object Idle : ScanUiState

    /** A scan is in flight; show an indeterminate indicator. */
    data object Scanning : ScanUiState

    /** Last scan finished. Counts come straight from ScanResult. */
    data class Done(
        val added: Int,
        val updated: Int,
        val softDeleted: Int,
        val unchanged: Int,
        val durationMs: Long,
    ) : ScanUiState

    /** Last scan failed; message is a human-readable ScanError rendering. */
    data class Error(val message: String) : ScanUiState
}
```

- [ ] **Step 2: Extend `SettingsState.kt`** — append two fields (with temporary defaults — see Task 5):

```kotlin
data class SettingsState(
    val themeMode: ThemeMode,
    val scanOnLaunch: Boolean,
    val scanFolders: List<String>,
    val replayGainMode: ReplayGainMode,
    val replayGainPreAmpDb: Double,
    val backfill: BackfillUiState,
    // Appended with temporary defaults so app-module call sites compile before
    // Tasks 3–4 wire them; defaults are removed in Task 5.
    val autoScanOnFolderAdd: Boolean = true,
    val scan: ScanUiState = ScanUiState.Idle,
)
```

- [ ] **Step 3: Write the failing UI tests** — add to `SettingsScreenTest.kt`. First extend the test helpers to thread the new state/callbacks, then add render assertions:

In `defaultState(...)` add params `autoScanOnFolderAdd: Boolean = true` and `scan: ScanUiState = ScanUiState.Idle`, and pass them into the `SettingsState(...)` constructor. In `renderScreen(...)` add params `onAutoScanOnFolderAddChange: (Boolean) -> Unit = {}` and `onTriggerScan: () -> Unit = {}`, and pass them into `SettingsScreen(...)`. Then:

```kotlin
    @Test
    fun shows_auto_scan_on_folder_add_toggle() {
        renderScreen()
        composeRule.onNodeWithText("Auto-scan when a folder is added").assertExists()
    }

    @Test
    fun scan_idle_shows_scan_now_button() {
        renderScreen(state = defaultState(scan = ScanUiState.Idle))
        composeRule.onNodeWithText("Scan now").assertExists()
    }

    @Test
    fun scan_now_button_invokes_callback() {
        var clicked = false
        renderScreen(onTriggerScan = { clicked = true })
        composeRule.onNodeWithText("Scan now").performClick()
        assertTrue(clicked)
    }

    @Test
    fun scan_in_progress_shows_scanning_text() {
        renderScreen(state = defaultState(scan = ScanUiState.Scanning))
        composeRule.onNodeWithText("Scanning library…").assertExists()
    }

    @Test
    fun scan_done_shows_summary() {
        renderScreen(
            state = defaultState(
                scan = ScanUiState.Done(added = 5, updated = 0, softDeleted = 0, unchanged = 27761, durationMs = 3000L),
            ),
        )
        composeRule.onNodeWithText("Scan complete: 5 added, 0 updated, 0 removed, 27761 unchanged in 3s.")
            .assertExists()
    }
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `.\gradlew :ui:components:desktopTest --tests "*SettingsScreenTest*"`
Expected: FAIL — `ScanUiState` / new params / new text nodes unresolved or absent.

- [ ] **Step 5: Implement `SettingsScreen.kt` changes**

(a) Add two params before `modifier` (temporary defaults — see Task 5):

```kotlin
    onTriggerBackfill: () -> Unit,
    onAutoScanOnFolderAddChange: (Boolean) -> Unit = {},
    onTriggerScan: () -> Unit = {},
    modifier: Modifier = Modifier,
```

(b) In the **Behavior** section, after the "Scan library on launch" `Row` (after line 107), add a second toggle row:

```kotlin
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Auto-scan when a folder is added", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.autoScanOnFolderAdd,
                onCheckedChange = onAutoScanOnFolderAddChange,
            )
        }
```

(c) In the **Library** section, after the `OutlinedButton(onClick = onPickFolder) { Text("Add Folder") }` (line 135), add the scan affordance:

```kotlin
        Spacer(modifier = Modifier.height(16.dp))
        ScanContent(state = state.scan, onTriggerScan = onTriggerScan)
```

(d) Add the `ScanContent` composable (mirror `BackfillContent`'s structure) below `SettingsScreen`:

```kotlin
@Composable
private fun ScanContent(
    state: ScanUiState,
    onTriggerScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ScanUiState.Idle -> Column(modifier = modifier) {
            Button(onClick = onTriggerScan) { Text("Scan now") }
        }
        is ScanUiState.Scanning -> Column(modifier = modifier) {
            Text("Scanning library…", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ScanUiState.Done -> Column(modifier = modifier) {
            Text(
                "Scan complete: ${state.added} added, ${state.updated} updated, " +
                    "${state.softDeleted} removed, ${state.unchanged} unchanged in ${state.durationMs / 1000}s.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onTriggerScan) { Text("Scan again") }
        }
        is ScanUiState.Error -> Column(modifier = modifier) {
            Text("Scan failed: ${state.message}", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onTriggerScan) { Text("Retry scan") }
        }
    }
}
```

(The indeterminate `LinearProgressIndicator(modifier = …)` overload — no `progress` lambda — is the Compose-MP indeterminate form.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\gradlew :ui:components:desktopTest --tests "*SettingsScreenTest*"`
Expected: PASS (all existing + 5 new).

- [ ] **Step 7: Build the module + commit**

```bash
.\gradlew :ui:components:build
git add ui/components
git commit -m "phase-2b-a(scan): ScanUiState + Scan-now button + auto-scan toggle in SettingsScreen"
```

---

### Task 3: Desktop trigger wiring (`:app-desktop`)

**Files:**
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt`

**Interfaces:**
- Consumes: `graph.scanner.scanIncremental(): Either<ScanError, ScanResult>`; `graph.settings.{scanOnLaunch, autoScanOnFolderAdd, setAutoScanOnFolderAdd}`; `ScanUiState`; `SettingsState.{autoScanOnFolderAdd, scan}`; `SettingsScreen.{onAutoScanOnFolderAddChange, onTriggerScan}`.

- [ ] **Step 1: Add a process-lifetime scope + scan-on-launch in `main()`**

Add imports: `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.SupervisorJob`. After the graph is created (line 79) and the seed `runBlocking` block (lines 94–99), add:

```kotlin
    // Process-lifetime scope for background scans not tied to a Composable.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Scan-on-launch: honor the persisted toggle. Async so the window shows
    // immediately — a 27k-track filesystem walk must not block process start.
    appScope.launch {
        if (graph.settings.scanOnLaunch.first()) {
            graph.scanner.scanIncremental()
        }
    }
```

- [ ] **Step 2: Wire `DesktopSettingsRoute`** — hoist the new state, build the scan lambda, pass into `SettingsScreen`, and auto-scan on folder add.

After `val scanOnLaunch by …` (line 154) add:

```kotlin
    val autoScanOnFolderAdd by graph.settings.autoScanOnFolderAdd.collectAsState(initial = true)
    var scanState: ScanUiState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }

    // One incremental scan, mapping ScanResult/ScanError into the coarse UI state.
    // Uses the route's coroutineScope (matches the backfill button); closing
    // Settings mid-scan cancels it — acceptable, the scan is short and re-triggerable.
    val runScanNow: () -> Unit = {
        coroutineScope.launch {
            scanState = ScanUiState.Scanning
            scanState = graph.scanner.scanIncremental().fold(
                { err -> ScanUiState.Error(err.toString()) },
                { res ->
                    ScanUiState.Done(
                        added = res.tracksAdded,
                        updated = res.tracksUpdated,
                        softDeleted = res.tracksSoftDeleted,
                        unchanged = res.tracksUnchanged,
                        durationMs = res.durationMs,
                    )
                },
            )
        }
    }
```

Add import `com.clayworks.kiln.ui.components.settings.ScanUiState`.

In the `SettingsState(...)` constructor (lines 184–191) add `autoScanOnFolderAdd = autoScanOnFolderAdd,` and `scan = scanState,`.

Replace `onPickFolder = { … }` (lines 198–205) so a successful add triggers a scan when enabled:

```kotlin
            onPickFolder = {
                coroutineScope.launch {
                    val picked = pickFolderDialog()
                    if (picked != null && picked !in scanFolders) {
                        graph.settings.setScanFolders(scanFolders + picked)
                        if (autoScanOnFolderAdd) runScanNow()
                    }
                }
            },
```

In the `SettingsScreen(...)` call, after `onTriggerBackfill = { … },` add:

```kotlin
            onAutoScanOnFolderAddChange = { enabled ->
                coroutineScope.launch { graph.settings.setAutoScanOnFolderAdd(enabled) }
            },
            onTriggerScan = runScanNow,
```

- [ ] **Step 3: Build + smoke**

Run: `.\gradlew :app-desktop:assemble :app-desktop:test`
Expected: PASS (compiles; `DesktopAppGraphTest` green). Manual run (`.\gradlew :app-desktop:run`) deferred to the integration checkpoint after Task 4.

- [ ] **Step 4: Commit**

```bash
git add app-desktop
git commit -m "phase-2b-a(scan): wire desktop scan triggers (launch + Scan now + auto-on-add)"
```

---

### Task 4: Android trigger wiring (`:app-android`)

**Files:**
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt`

**Interfaces:**
- Consumes: same as Task 3 but `graph: AndroidAppGraph`; SAF picker via `rememberSafFolderPicker`. Scan-on-launch must fire **post-permission** (MediaStore needs `READ_MEDIA_AUDIO`).

- [ ] **Step 1: Scan-on-launch in `MainActivity.onCreate`** — gated on permission, once per Activity creation (not per recomposition).

Add imports: `androidx.lifecycle.lifecycleScope`, `kotlinx.coroutines.flow.first`, `kotlinx.coroutines.launch` (launch may already be imported). In `onCreate`, after `val graph = …` (line 66), before `setContent {`:

```kotlin
        // Scan-on-launch: MediaStore requires READ_MEDIA_AUDIO, so only scan if
        // it is already granted (first run, pre-grant, no-ops — scan runs next
        // launch once permission + toggle are set). lifecycleScope = once per
        // Activity create, not per recomposition.
        val launchPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        lifecycleScope.launch {
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, launchPermission) ==
                PackageManager.PERMISSION_GRANTED
            if (granted && graph.settings.scanOnLaunch.first()) {
                graph.scanner.scanIncremental()
            }
        }
```

- [ ] **Step 2: Wire `AndroidSettingsRoute`** — mirror Task 3. After `val scanOnLaunch by …` (line 120) add:

```kotlin
    val autoScanOnFolderAdd by graph.settings.autoScanOnFolderAdd.collectAsState(initial = true)
    var scanState: ScanUiState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }

    val runScanNow: () -> Unit = {
        coroutineScope.launch {
            scanState = ScanUiState.Scanning
            scanState = graph.scanner.scanIncremental().fold(
                { err -> ScanUiState.Error(err.toString()) },
                { res ->
                    ScanUiState.Done(
                        added = res.tracksAdded,
                        updated = res.tracksUpdated,
                        softDeleted = res.tracksSoftDeleted,
                        unchanged = res.tracksUnchanged,
                        durationMs = res.durationMs,
                    )
                },
            )
        }
    }
```

Add import `com.clayworks.kiln.ui.components.specsheet`-sibling: `com.clayworks.kiln.ui.components.settings.ScanUiState`.

Replace the SAF picker block (lines 140–146) so a successful add scans when enabled:

```kotlin
    val launchSafPicker = rememberSafFolderPicker(onPicked = { uri ->
        if (uri !in scanFolders) {
            coroutineScope.launch {
                graph.settings.setScanFolders(scanFolders + uri)
                if (autoScanOnFolderAdd) runScanNow()
            }
        }
    })
```

In the `SettingsState(...)` constructor (lines 158–165) add `autoScanOnFolderAdd = autoScanOnFolderAdd,` and `scan = scanState,`. In the `SettingsScreen(...)` call, after `onTriggerBackfill = { … },` add:

```kotlin
            onAutoScanOnFolderAddChange = { enabled ->
                coroutineScope.launch { graph.settings.setAutoScanOnFolderAdd(enabled) }
            },
            onTriggerScan = runScanNow,
```

- [ ] **Step 3: Build**

Run: `.\gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app-android
git commit -m "phase-2b-a(scan): wire android scan triggers (launch + Scan now + auto-on-add)"
```

---

### Task 5: Restore required-callback convention (`:ui:components`)

**Files:**
- Modify: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt`
- Modify: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt`

**Interfaces:** No signature changes beyond removing defaults — all three call sites (test helper + both routes) now pass them explicitly (Tasks 2–4).

- [ ] **Step 1: Remove the temporary defaults** so a future call site can't silently drop the wiring (matches `onTriggerBackfill` being required).
  - `SettingsState.kt`: drop `= true` from `autoScanOnFolderAdd` and `= ScanUiState.Idle` from `scan`.
  - `SettingsScreen.kt`: drop `= {}` from `onAutoScanOnFolderAddChange` and `onTriggerScan`.

- [ ] **Step 2: Full canonical build (proves all call sites pass the now-required args)**

Run: `.\gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`
Expected: BUILD SUCCESSFUL; `:data:library` 90/90, `:ui:components` SettingsScreen + SpecSheet tests pass.

- [ ] **Step 3: Commit**

```bash
git add ui/components
git commit -m "phase-2b-a(scan): make scan callbacks required (drop temp defaults)"
```

---

## Integration checkpoint (after Task 5)

- [ ] **Desktop:** `.\gradlew :app-desktop:run` → Settings → "Scan now" → status flips Scanning → Done with counts; toggle "Scan library on launch" + restart proves the launch path; add a folder with auto-scan on proves the add path. (Desktop's 27k stale rows re-verify against real `D:\tiddl`.)
- [ ] **Android (Pixel 7, Android 16 — uiautomator works there):** install the debug APK; the folder `/sdcard/Music/kiln-smoke/` (5 staged demo files) is already added. Tap **Scan now** in Settings → `track` count rises from 0; the scan-end `AndroidFormatFactBackfill` runs (verify via `adb exec-out run-as com.clayworks.kiln cat databases/kiln.db` pulled to the host, then query `metadata_backfilled_at_ms IS NOT NULL`). **`export MSYS_NO_PATHCONV=1`** before any `adb` command with `/sdcard` or `/data` paths in Git Bash. This closes issue #27 + unblocks the A2/A3 on-device backfill verification.

## Self-Review

- **Spec coverage:** Scan-now button → Task 2 (UI) + Tasks 3/4 (wiring). Scan-on-launch → Tasks 3 (desktop `appScope`) + 4 (Android `lifecycleScope`, post-permission). Auto-scan-on-add toggle → Task 1 (setting) + Task 2 (toggle UI) + Tasks 3/4 (wiring). Granular toggles → `scanOnLaunch` (existing) + `autoScanOnFolderAdd` (new), both in Behavior section. Both platforms → Tasks 3 + 4. ✓
- **Type consistency:** `ScanUiState.Done(added, updated, softDeleted, unchanged, durationMs)` ← `ScanResult.{tracksAdded, tracksUpdated, tracksSoftDeleted, tracksUnchanged, durationMs}` via `.fold` in both routes. `autoScanOnFolderAdd: Flow<Boolean>` / `setAutoScanOnFolderAdd` consistent across repo, state, screen, both routes. ✓
- **No placeholders:** every step has real code + exact commands. ✓
- **Open default call (flag to Clay):** `autoScanOnFolderAdd` defaults **true** (adding a folder implies wanting it indexed). One-line flip to `false` if a never-scan-without-explicit-action default is preferred.
