# Phase 2a Track A — Settings UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Kiln's first Settings surface end-to-end on both platforms — schema migration to user_version=2 (settings table), SettingsRepository, KilnTheme/ThemeMode in `:ui:theme`, SettingsScreen in `:ui:components`, DI rewire from value-class constructor params to flow-driven providers, plus Desktop folder-picker (functional via JFileChooser) and Android folder-picker stub (Track B replaces with SAF).

**Architecture:**
- **Storage:** Generic key/value `settings` table (TEXT/TEXT) added via SQLDelight `2.sqm` migration. `verifyMigrations` infrastructure baked in this session (was missing; added so future migrations are CI-verified).
- **Repository:** `SettingsRepository` interface in `:data:library:commonMain` exposes typed `Flow<T>` for each setting + `suspend setX(...)` writers. Implementation reads SQLDelight `asFlow` mappings, encodes list values as JSON via kotlinx-serialization. Default values resolved at the repo layer when key is unset.
- **DI rewire:** `DesktopAppGraph` drops `ScanFolders` value-class constructor param; scanner takes `Flow<List<Path>>` and reads `.first()` on each scan invocation. `AndroidAppGraph` adds `SettingsRepository` provider for scan-on-launch + theme; scan folders unchanged for Track A (MediaStore is system-side, SAF picks land in Track B).
- **UI:** `KilnTheme(themeMode = ...)` wraps content in `:ui:theme:commonMain`. `SettingsScreen` composable in `:ui:components:commonMain` with state-hoisted callbacks (no Voyager/Circuit yet — those land in Track C). App modules wire SettingsScreen via a thin coroutine-scoped state holder. The H7 PlayFirstTrackScreen gets a Settings entry point (top-bar gear icon) without replacing the existing vertical-slice surface.
- **Folder picker:** Desktop ships functional (`JFileChooser` in directory-mode); Android ships stub with "Track B will add SAF here" label. Both write `scan_folders` setting via `SettingsRepository.setScanFolders(...)`.

**Tech Stack:** SQLDelight 2.3.2 migrations + `verifyMigrations`, kotlinx-serialization 1.9.0 (JSON for list values), Compose-MP 1.11.0 + Material3, kotlin-inject 0.9.0 DI, kotlinx-coroutines 1.11.0 flows.

---

## Pre-flight (must hold true at start)

- `kiln-verify-build` PASS (5 targets, 91 tests + 1 skipped) — confirmed at Session 13 start, commit `ad10838`.
- Git tree clean on `main`.
- No existing `docs/superpowers/plans/2026-05-22-phase-2a-track-a-*.md` file.

## End-state (must hold true at completion)

- `./gradlew :data:library:verifyCommonMainKilnDatabaseMigration` PASS (new task surface; was passing trivially with zero migrations before).
- All canonical verify-build targets green.
- `:ui:components:desktopTest` + `:ui:components:testAndroidHostTest` non-empty (first tests in the module).
- New tests: ≥ 8 in `:data:library:desktopTest` (SettingsRepository), ≥ 1 in `:ui:components` (SettingsScreen render smoke).
- Desktop app launches: gear icon visible → click → SettingsScreen renders with toggles + folder list (preseeded with `D:\tiddl` on first launch).
- Android app launches on Pixel 7: same gear icon → SettingsScreen renders → toggles persist across cold restart.
- Both apps: changing theme toggle updates colors immediately; changing scan-on-launch persists across restart.
- Desktop "Pick Folder" button: opens JFileChooser → selecting a folder adds path to scan_folders list.
- Android "Pick Folder" button: shows toast/snackbar with "Folder picker coming in Track B (SAF)".
- New gotchas (if any) captured in `CLAUDE.md` Build/Dep Gotchas section.

---

## File Structure

**New files:**

| Path | Responsibility |
|------|---------------|
| `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/settings.sq` | Settings table (key/value TEXT/TEXT) + CRUD queries |
| `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/migrations/1.sqm` | v1→v2 migration: CREATE TABLE settings (SQLDelight names `.sqm` by **source** version, not target) |
| `data/library/src/commonMain/sqldelight/databases/1.db` | SQLDelight v1 schema snapshot (generated, committed) |
| `data/library/src/commonMain/sqldelight/databases/2.db` | SQLDelight v2 schema snapshot (generated, committed) |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt` | Public interface + ThemeMode enum + default values |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImpl.kt` | SQLDelight-backed implementation |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/internal/Keys.kt` | Internal SettingKey constants + JSON codec helpers |
| `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImplTest.kt` | Round-trip + defaults + JSON list encoding tests |
| `ui/theme/src/commonMain/kotlin/com/clayworks/kiln/ui/theme/KilnTheme.kt` | KilnTheme composable + ThemeMode-derived ColorScheme |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt` | Stateless Material3 settings surface |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt` | Plain data class hoisted from Settings repository |
| `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreenTest.kt` | ComposeUiTest smoke render + interaction |

**Modified files:**

| Path | Change |
|------|--------|
| `data/library/build.gradle.kts` | Add `verifyMigrations.set(true)` + `schemaOutputDirectory.set(...)` + kotlinx-serialization plugin + json dep |
| `data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt` | Constructor: `scanFolders: List<Path>` → `scanFoldersFlow: Flow<List<Path>>`; read `.first()` inside `runScan()` |
| `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScannerTest.kt` | Pass `flowOf(folders)` instead of `folders` |
| `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt` | Drop `ScanFolders` value class + ctor param; add `SettingsRepository` provider; derive `Flow<List<Path>>` from settings (fallback `[Path.of("D:\\tiddl")]`); expose `settings` and `themeMode` on graph surface |
| `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt` | Drop `ScanFolders` from `::class.create(...)` call; wrap content in `KilnTheme(themeMode)`; add gear icon + SettingsScreen routing |
| `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` | Add `SettingsRepository` provider + expose `settings` and `themeMode` on graph surface |
| `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt` | Wrap content in `KilnTheme(themeMode)`; add gear icon + SettingsScreen routing |
| `app-android/src/test/kotlin/com/clayworks/kiln/di/AndroidAppGraphTest.kt` | Add settings-provider assertion |
| `app-desktop/src/test/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraphTest.kt` | Update to new ctor; assert settings exposed |
| `ui/components/build.gradle.kts` | Add `:data:library` project dependency (for SettingsRepository types) + add desktopTest + androidHostTest source sets with compose-ui-test-junit4 |
| `ui/theme/build.gradle.kts` | Stays compose-only — no new deps |
| `CLAUDE.md` | Append any new gotchas to "Build/Dep Gotchas" section (only if discovered) |
| `gradle/libs.versions.toml` | Add `kotlinx-serialization-json` to library refs if not already present (already declared, just verify) |

---

## Tasks (one subagent dispatch per task)

### Task 1: SQLDelight migration infrastructure (verifyMigrations + v1 snapshot)

**Why first:** The repo has no `.sqm` files and no `schemaOutputDirectory` configured. Adding a migration without snapshotting v1 first means `verifyMigrations` can't compare anything — any future schema drift goes silently. Baking infrastructure now is one-time work and aligns with schema sketch §6.

**Files:**
- Modify: `data/library/build.gradle.kts` — add `verifyMigrations.set(true)` + `schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))`
- Create: `data/library/src/commonMain/sqldelight/databases/1.db` (binary snapshot, generated by Gradle)

**Steps:**

- [ ] **Step 1: Modify build.gradle.kts to bake migration infra**

Edit `data/library/build.gradle.kts` lines 14-20 to add the two flags:

```kotlin
sqldelight {
    databases {
        create("KilnDatabase") {
            packageName.set("com.clayworks.kiln.data.library.db")
            // Migration infrastructure baked in Phase 2a Track A. The .db snapshots
            // committed under databases/ let CI's verifyCommonMainKilnDatabaseMigration
            // task diff target schema (current .sq files) against
            // initial-snapshot + sequential .sqm migrations. Catches drift before merge.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
```

- [ ] **Step 2: Generate the v1 snapshot**

Run from repo root:

```powershell
pwsh -c "./gradlew :data:library:generateCommonMainKilnDatabaseSchema"
```

Expected: BUILD SUCCESSFUL; new file `data/library/src/commonMain/sqldelight/databases/1.db` appears. The version is `1` because there are no `.sqm` files yet, so SQLDelight infers schema version=1.

- [ ] **Step 3: Verify the verifier passes with snapshot in place**

```powershell
pwsh -c "./gradlew :data:library:verifyCommonMainKilnDatabaseMigration"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run canonical verify-build to confirm no regression**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```

Expected: PASS, 5/5 targets, 91 tests + 1 skipped (no test count change — schema infrastructure is build-time only).

- [ ] **Step 5: Commit**

```bash
git add data/library/build.gradle.kts data/library/src/commonMain/sqldelight/databases/1.db
git commit -m "$(cat <<'EOF'
chore(data:library): bake SQLDelight migration infrastructure — v1 snapshot + verifyMigrations

Adds verifyMigrations.set(true) + schemaOutputDirectory under
data/library/src/commonMain/sqldelight/databases/. Generates the v1 schema
snapshot from the current .sq files (no .sqm migrations exist yet, so v1
is the implicit initial version). Phase 2a Track A Task 1 of 9 —
prerequisite for adding the 2.sqm settings-table migration in Task 3.

verifyCommonMainKilnDatabaseMigration now meaningfully compares target
.sq schema vs. snapshot + sequential migrations on every CI run.

Per docs/decisions/2026-05-18-sqldelight-schema-sketch.md §6.
EOF
)"
```

---

### Task 2: settings.sq — table definition + CRUD queries

**Why standalone:** Splitting schema-file creation from migration-file creation keeps each commit reviewable. This task only adds the `.sq` file (defining the v2 target schema); Task 3 adds the migration that turns v1 into v2.

**Files:**
- Create: `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/settings.sq`

**Steps:**

- [ ] **Step 1: Create settings.sq with table + queries**

Path: `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/settings.sq`

```sql
-- Settings — generic key/value store, single primary key. TEXT/TEXT keeps
-- migrations cheap: future settings need no schema change, just new
-- well-known keys at the SettingsRepository layer. JSON encoding handles
-- list/struct values; primitive values are TEXT-as-toString. Reads are
-- low-frequency (one Flow per consumer) so the lookup cost is negligible.

CREATE TABLE settings (
    key   TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);

selectByKey:
SELECT value FROM settings WHERE key = ?;

selectAll:
SELECT * FROM settings ORDER BY key;

upsert:
INSERT INTO settings(key, value) VALUES (:key, :value)
ON CONFLICT(key) DO UPDATE SET value = excluded.value;

delete:
DELETE FROM settings WHERE key = ?;

deleteAll:
DELETE FROM settings;
```

- [ ] **Step 2: Generate Kotlin interfaces to verify .sq parses cleanly**

```powershell
pwsh -c "./gradlew :data:library:generateCommonMainKilnDatabaseInterface"
```

Expected: BUILD SUCCESSFUL; new class `SettingsQueries` available in generated sources.

- [ ] **Step 3: Do NOT regenerate the schema snapshot yet** — that comes in Task 3 after adding the migration. Running `generateCommonMainKilnDatabaseSchema` here would create a 2.db that no migration produces, which would fail `verifyMigrations`.

Confirm: do not run `generateCommonMainKilnDatabaseSchema` in this task.

- [ ] **Step 4: Commit**

```bash
git add data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/settings.sq
git commit -m "$(cat <<'EOF'
feat(data:library): settings table .sq — Phase 2a Track A schema piece

Generic key/value (TEXT/TEXT) store with upsert + scan queries. Future
settings keys (theme, scan-on-launch, scan_folders, etc.) live as
well-known constants at the SettingsRepository layer rather than typed
columns — keeps migrations cheap. JSON encoding via kotlinx-serialization
handles non-primitive values; per-key reads are infrequent enough that
the encode/decode cost is irrelevant.

Phase 2a Track A Task 2 of 9. Task 3 adds the v1→v2 migration; do NOT
regenerate the schema snapshot until then or verifyMigrations will fail.
EOF
)"
```

---

### Task 3: 1.sqm migration + v2 snapshot

**SQLDelight naming gotcha (corrected post-empirical discovery 2026-05-22):** SQLDelight `.sqm` files are named by the **source** schema version they migrate FROM, not the target version they migrate TO. So the v1→v2 migration file is `1.sqm` (not `2.sqm`). Running with `2.sqm` was empirically observed to produce `databases/3.db` because SQLDelight interpreted `2.sqm` as v2→v3 against a phantom v2. The 1.sqm form produces `2.db` as expected. Source: https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/migrations.

**Why standalone:** Pairing the migration with its snapshot generation in one commit keeps the schema-version state consistent on every checkout. Splitting these would leave `main` in a state where verifyMigrations fails between commits.

**Files:**
- Create: `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/migrations/1.sqm`
- Create: `data/library/src/commonMain/sqldelight/databases/2.db` (generated, committed)

**Steps:**

- [ ] **Step 1: Create 1.sqm** (the v1→v2 migration — named by source version per SQLDelight convention)

Path: `data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/migrations/1.sqm`

```sql
-- v1 → v2: add settings table.
-- Mirrors the CREATE TABLE in settings.sq exactly. SQLDelight runs this
-- migration on existing v1 databases (e.g. Clay's 27k-track desktop DB)
-- before the app touches the new table.

CREATE TABLE settings (
    key   TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);
```

- [ ] **Step 2: Regenerate the schema snapshot — produces 2.db**

```powershell
pwsh -c "./gradlew :data:library:generateCommonMainKilnDatabaseSchema"
```

Expected: BUILD SUCCESSFUL; new file `data/library/src/commonMain/sqldelight/databases/2.db` appears. The version number `2` matches the highest-numbered `.sqm`.

- [ ] **Step 3: Verify migration**

```powershell
pwsh -c "./gradlew :data:library:verifyCommonMainKilnDatabaseMigration"
```

Expected: BUILD SUCCESSFUL. This diffs (.sq files representing v2 target) against (1.db + 2.sqm). If 2.sqm doesn't reproduce the target schema, this fails — that's the gate Track A baked in.

- [ ] **Step 4: Full canonical verify-build**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```

Expected: PASS, 5/5 targets, 91 tests + 1 skipped.

- [ ] **Step 5: Commit**

```bash
git add data/library/src/commonMain/sqldelight/com/clayworks/kiln/data/library/db/migrations/2.sqm data/library/src/commonMain/sqldelight/databases/2.db
git commit -m "$(cat <<'EOF'
feat(data:library): v1→v2 migration — settings table

2.sqm adds the settings table to existing databases at user_version=1.
New databases get the table directly from settings.sq via SQLDelight's
fresh-schema path. The 2.db snapshot proves migration parity — CI's
verifyCommonMainKilnDatabaseMigration task diffs 1.db + 2.sqm against
the .sq target and fails if they diverge.

Phase 2a Track A Task 3 of 9. Database layer is now ready for the
SettingsRepository in Task 4.
EOF
)"
```

---

### Task 4: SettingsRepository — interface + impl + tests

**Why:** This is the type-safe API the rest of Kiln will consume. Lives in `:data:library:commonMain` so both apps share. Impl uses SQLDelight + kotlinx-serialization for JSON encoding of list values.

**Files:**
- Create: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt`
- Create: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImpl.kt`
- Create: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/internal/Keys.kt`
- Create: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImplTest.kt`
- Modify: `data/library/build.gradle.kts` — add `org.jetbrains.kotlin.plugin.serialization` plugin and `libs.kotlinx.serialization.json` dependency
- Modify: `gradle/libs.versions.toml` — verify `kotlinx-serialization-json` library exists (already declared at line 92; verify only)

**Steps:**

- [ ] **Step 1: Add serialization plugin + dep to data/library/build.gradle.kts**

After `id("kiln.kmp.library")` plugin block (line 10), add:

```kotlin
plugins {
    id("kiln.kmp.library")
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.plugin.serialization)
}
```

Inside `commonMain.dependencies { ... }` block (around line 24), add:

```kotlin
implementation(libs.kotlinx.serialization.json)
```

- [ ] **Step 2: Write the failing SettingsRepository test FIRST (TDD)**

Path: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImplTest.kt`

```kotlin
package com.clayworks.kiln.library.settings

import com.clayworks.kiln.library.source.TestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsRepositoryImplTest {

    private lateinit var testDb: TestDb
    private lateinit var repo: SettingsRepository

    @BeforeTest
    fun setUp() {
        testDb = TestDb.create()
        repo = SettingsRepositoryImpl(testDb.db, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun defaults_when_unset() = runTest {
        assertEquals(ThemeMode.System, repo.themeMode.first())
        assertEquals(false, repo.scanOnLaunch.first())
        assertEquals(emptyList<String>(), repo.scanFolders.first())
    }

    @Test
    fun theme_mode_round_trip() = runTest {
        repo.setThemeMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, repo.themeMode.first())
        repo.setThemeMode(ThemeMode.Light)
        assertEquals(ThemeMode.Light, repo.themeMode.first())
        repo.setThemeMode(ThemeMode.System)
        assertEquals(ThemeMode.System, repo.themeMode.first())
    }

    @Test
    fun scan_on_launch_round_trip() = runTest {
        repo.setScanOnLaunch(true)
        assertEquals(true, repo.scanOnLaunch.first())
        repo.setScanOnLaunch(false)
        assertEquals(false, repo.scanOnLaunch.first())
    }

    @Test
    fun scan_folders_round_trip_single() = runTest {
        repo.setScanFolders(listOf("D:\\tiddl"))
        assertEquals(listOf("D:\\tiddl"), repo.scanFolders.first())
    }

    @Test
    fun scan_folders_round_trip_multiple_preserves_order() = runTest {
        val folders = listOf("D:\\tiddl", "E:\\flac-import", "F:\\loaners")
        repo.setScanFolders(folders)
        assertEquals(folders, repo.scanFolders.first())
    }

    @Test
    fun scan_folders_round_trip_empty() = runTest {
        repo.setScanFolders(listOf("X:\\seed"))
        repo.setScanFolders(emptyList())
        assertEquals(emptyList<String>(), repo.scanFolders.first())
    }

    @Test
    fun scan_folders_json_encoding_handles_special_chars() = runTest {
        val folders = listOf("D:\\Music\\Coldplay - X&Y", "/home/user/Music \"backup\"")
        repo.setScanFolders(folders)
        assertEquals(folders, repo.scanFolders.first())
    }

    @Test
    fun unknown_invalid_value_falls_back_to_default() = runTest {
        // Write a value that doesn't parse as ThemeMode — repo should
        // log + return default rather than crash.
        testDb.db.settingsQueries.upsert(key = "theme_mode", value = "FUCHSIA_NEON")
        assertEquals(ThemeMode.System, repo.themeMode.first())
    }
}
```

- [ ] **Step 3: Run tests; expect compile failure (SettingsRepository doesn't exist yet)**

```powershell
pwsh -c "./gradlew :data:library:desktopTest"
```

Expected: COMPILATION ERROR — `Unresolved reference: SettingsRepository / SettingsRepositoryImpl / ThemeMode`. This is the failing-test gate.

- [ ] **Step 4: Implement SettingsRepository interface + ThemeMode**

Path: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt`

```kotlin
package com.clayworks.kiln.library.settings

import kotlinx.coroutines.flow.Flow

/**
 * Theme selection — Light forces light, Dark forces dark, System defers to
 * platform's dark-mode signal. KilnTheme reads this and applies the matching
 * Material 3 ColorScheme.
 */
enum class ThemeMode { Light, Dark, System }

/**
 * Phase 2a Track A: persistent user preferences. Implementations back to the
 * `settings` SQLDelight table (key/value). Flows emit defaults until a value
 * is written, then emit each write. Consumers call .first() for one-shot
 * reads (scanner pulls scan_folders this way) or collect for reactive UI.
 */
interface SettingsRepository {
    /** Selected theme; default System. */
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** Whether to trigger a library scan on app launch; default false. */
    val scanOnLaunch: Flow<Boolean>
    suspend fun setScanOnLaunch(enabled: Boolean)

    /**
     * Filesystem paths (Desktop) or SAF tree URIs (Android, Track B) the
     * scanner walks. String-typed so the interface stays platform-neutral —
     * `java.nio.file.Path` is JVM-only; Android URIs aren't paths. Consumers
     * parse to their platform type at the DI seam. Default empty.
     */
    val scanFolders: Flow<List<String>>
    suspend fun setScanFolders(folders: List<String>)
}
```

- [ ] **Step 5: Implement SettingsRepositoryImpl**

Path: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepositoryImpl.kt`

```kotlin
package com.clayworks.kiln.library.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import co.touchlab.kermit.Logger
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.settings.internal.SettingKey
import com.clayworks.kiln.library.settings.internal.parseThemeMode
import com.clayworks.kiln.library.settings.internal.scanFoldersFromJson
import com.clayworks.kiln.library.settings.internal.scanFoldersToJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val log = Logger.withTag("SettingsRepository")

class SettingsRepositoryImpl(
    private val db: KilnDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : SettingsRepository {

    override val themeMode: Flow<ThemeMode> =
        db.settingsQueries.selectByKey(SettingKey.THEME_MODE)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { row -> parseThemeMode(row?.value_) }

    override suspend fun setThemeMode(mode: ThemeMode): Unit = withContext(ioDispatcher) {
        db.settingsQueries.upsert(key = SettingKey.THEME_MODE, value_ = mode.name)
    }

    override val scanOnLaunch: Flow<Boolean> =
        db.settingsQueries.selectByKey(SettingKey.SCAN_ON_LAUNCH)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { row -> row?.value_ == "true" }

    override suspend fun setScanOnLaunch(enabled: Boolean): Unit = withContext(ioDispatcher) {
        db.settingsQueries.upsert(key = SettingKey.SCAN_ON_LAUNCH, value_ = enabled.toString())
    }

    override val scanFolders: Flow<List<String>> =
        db.settingsQueries.selectByKey(SettingKey.SCAN_FOLDERS)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { row -> scanFoldersFromJson(row?.value_) }

    override suspend fun setScanFolders(folders: List<String>): Unit = withContext(ioDispatcher) {
        db.settingsQueries.upsert(
            key = SettingKey.SCAN_FOLDERS,
            value_ = scanFoldersToJson(folders),
        )
    }
}
```

Note: SQLDelight 2.x renames Kotlin reserved-word column names by suffixing `_`. The `value` column becomes `value_` in generated code. The query parameter `:value` becomes `value_` as well. The `selectByKey` query was written without a parameter name — SQLDelight uses `key` from the column. Adjust if the generated name differs once `generateCommonMainKilnDatabaseInterface` runs.

- [ ] **Step 6: Implement internal Keys.kt with codec helpers**

Path: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/internal/Keys.kt`

```kotlin
package com.clayworks.kiln.library.settings.internal

import co.touchlab.kermit.Logger
import com.clayworks.kiln.library.settings.ThemeMode
import kotlinx.serialization.json.Json

private val log = Logger.withTag("SettingsKeys")

/**
 * Well-known setting keys. New settings add a new constant here + accessor
 * pair on SettingsRepository; no schema change needed because the table is
 * generic key/value.
 */
internal object SettingKey {
    const val THEME_MODE = "theme_mode"
    const val SCAN_ON_LAUNCH = "scan_on_launch"
    const val SCAN_FOLDERS = "scan_folders"
}

/**
 * Decode ThemeMode from stored string; unknown / null → System default.
 * Surviving a corrupt enum value is preferable to crashing on launch.
 */
internal fun parseThemeMode(stored: String?): ThemeMode = when (stored) {
    null -> ThemeMode.System
    else -> try {
        ThemeMode.valueOf(stored)
    } catch (e: IllegalArgumentException) {
        log.w { "Unknown ThemeMode value '$stored'; falling back to System" }
        ThemeMode.System
    }
}

/**
 * JSON-encode the scan-folders list. kotlinx-serialization handles escaping
 * (Windows backslashes, embedded quotes, etc.) which a manual delimiter
 * would not.
 */
internal fun scanFoldersToJson(folders: List<String>): String =
    Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer()), folders)

/**
 * Decode JSON-encoded scan folders. null → empty list. Parse failure →
 * empty list + log warning (corrupt or pre-Track-A row shouldn't crash the app).
 */
internal fun scanFoldersFromJson(stored: String?): List<String> {
    if (stored.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer()),
            stored,
        )
    } catch (e: Exception) {
        log.w(e) { "Corrupt scan_folders value '$stored'; falling back to empty list" }
        emptyList()
    }
}
```

- [ ] **Step 7: Run tests and confirm green**

```powershell
pwsh -c "./gradlew :data:library:desktopTest"
```

Expected: BUILD SUCCESSFUL; 8 new tests pass in `SettingsRepositoryImplTest`. Total `:data:library:desktopTest` count: 63 + 8 = **71**.

- [ ] **Step 8: Full canonical verify-build**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```

Expected: PASS, 5/5 targets, **99 tests + 1 skipped** (was 91; +8 from SettingsRepository tests).

- [ ] **Step 9: Commit**

```bash
git add data/library/build.gradle.kts data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/ data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/settings/
git commit -m "$(cat <<'EOF'
feat(data:library): SettingsRepository — Phase 2a Track A repo layer

Public interface (themeMode / scanOnLaunch / scanFolders as Flow<T> + suspend
setters) in commonMain; SQLDelight-backed impl with kotlinx-serialization
JSON for the list-valued scan_folders setting. Defaults resolved at the
repo layer when the key is unset: System theme, scan_on_launch=false,
scan_folders=[]. Unknown stored values for ThemeMode fall back to System
with a log warning — survives a corrupt/old row without crashing.

8 tests added: defaults, theme round-trip, scan-on-launch round-trip,
scan_folders round-trip (single/multiple/empty/special-chars), corrupt
ThemeMode value fallback. Brings :data:library:desktopTest to 71 tests
(was 63). Verify-build: 99 tests + 1 skipped (was 91).

Phase 2a Track A Task 4 of 9. Next: rewire DesktopAppGraph to drop the
ScanFolders value-class param and read scan_folders from SettingsRepository.
EOF
)"
```

---

### Task 5: ThemeMode-aware KilnTheme composable in :ui:theme

**Why:** Both apps currently wrap content in bare `MaterialTheme { ... }`. To honor the Settings theme toggle, replace with `KilnTheme(themeMode = ...) { ... }`. The :ui:theme module is the natural home (matches its name) and is the first non-empty content in that module.

**Files:**
- Create: `ui/theme/src/commonMain/kotlin/com/clayworks/kiln/ui/theme/KilnTheme.kt`

**Steps:**

- [ ] **Step 1: Create KilnTheme.kt**

Path: `ui/theme/src/commonMain/kotlin/com/clayworks/kiln/ui/theme/KilnTheme.kt`

```kotlin
package com.clayworks.kiln.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.clayworks.kiln.library.settings.ThemeMode

/**
 * Kiln's Material 3 theme entry point. Phase 2a Track A: ThemeMode-driven
 * Light/Dark/System dispatch with default Material 3 baseline schemes.
 * Phase 2a Flight A (kmpalette landing) replaces these defaults with the
 * Kiln Dynamic album-art-driven palette pipeline — but the contract of
 * "consumers call KilnTheme(themeMode = ...)" is stable from Track A on.
 *
 * Concentric Modules invariant: this composable lives in commonMain with
 * pure Compose-MP deps; no androidx imports. Adapters in androidMain /
 * desktopMain remain unnecessary until Phase 2a Flight A.
 */
@Composable
fun KilnTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemInDark
    }
    val colorScheme: ColorScheme = if (useDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
```

- [ ] **Step 2: Add :data:library as dependency in ui/theme/build.gradle.kts (for ThemeMode import)**

Edit `ui/theme/build.gradle.kts` `commonMain.dependencies` block:

```kotlin
commonMain.dependencies {
    implementation(libs.bundles.compose.mp.common)
    // ThemeMode lives in :data:library:settings — keep KilnTheme's Settings
    // coupling minimal (just the enum import). Avoids inventing a redundant
    // enum in :ui:theme.
    implementation(project(":data:library"))
    implementation(libs.coil.compose.get().toString()) {
        exclude(group = "org.jetbrains.skiko", module = "skiko")
    }
}
```

- [ ] **Step 3: Build :ui:theme to verify compile**

```powershell
pwsh -c "./gradlew :ui:theme:build"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Canonical verify-build**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```

Expected: PASS, 5/5 targets, 99 tests + 1 skipped.

- [ ] **Step 5: Commit**

```bash
git add ui/theme/build.gradle.kts ui/theme/src/commonMain/kotlin/com/clayworks/kiln/ui/theme/KilnTheme.kt
git commit -m "$(cat <<'EOF'
feat(ui:theme): KilnTheme composable — ThemeMode Light/Dark/System dispatch

First non-build content in :ui:theme. Wraps Material 3 MaterialTheme with
ThemeMode-driven ColorScheme selection (default light/dark schemes for now;
kmpalette album-art palette pipeline lands at Phase 2a Flight A behind the
same KilnTheme(themeMode = ...) API). Concentric-modules invariant holds:
no androidx imports in commonMain.

Adds project(":data:library") dependency for the ThemeMode enum import.

Phase 2a Track A Task 5 of 9. Next: SettingsScreen composable in
:ui:components consumes this via the KilnTheme wrapper.
EOF
)"
```

---

### Task 6: SettingsScreen composable in :ui:components

**Why:** First Compose surface in `:ui:components`. Stateless: takes a `SettingsState` data class + callbacks. App modules own the SettingsRepository → state hoist + side-effects. Includes a Compose-UI smoke test (first test in `:ui:components`).

**Files:**
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt`
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt`
- Create: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreenTest.kt`
- Modify: `ui/components/build.gradle.kts` — add `:data:library` project dep + add desktopTest source set with `compose-ui-test-junit4`

**Steps:**

- [ ] **Step 1: Add deps to ui/components/build.gradle.kts**

Edit the file. Inside `sourceSets`, add `:data:library` to commonMain and add a desktopTest source set:

```kotlin
sourceSets {
    commonMain.dependencies {
        implementation(libs.bundles.compose.mp.common)
        implementation(libs.bundles.voyager)
        implementation(libs.bundles.circuit)
        implementation(libs.molecule.runtime)
        implementation(libs.kermit)
        implementation(project(":data:library"))      // ThemeMode + (future) shared types
        implementation(project(":ui:theme"))           // KilnTheme for previews/tests
        implementation(libs.coil.compose.get().toString()) {
            exclude(group = "org.jetbrains.skiko", module = "skiko")
        }
    }
    commonTest.dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.turbine)
    }
    val desktopTest by getting {
        dependencies {
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
        }
    }
}
```

- [ ] **Step 2: Create SettingsState data class**

Path: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsState.kt`

```kotlin
package com.clayworks.kiln.ui.components.settings

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
)
```

- [ ] **Step 3: Create SettingsScreen composable**

Path: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt`

```kotlin
package com.clayworks.kiln.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clayworks.kiln.library.settings.ThemeMode

/**
 * Stateless Phase 2a Track A settings surface. Three sections: theme
 * (radio group), behavior (scan-on-launch switch), library (scan-folder
 * list + add/remove buttons). State and callbacks are hoisted to app
 * modules where SettingsRepository writes happen.
 *
 * Folder-pick: the screen surfaces the button; the app module owns the
 * platform-specific picker (Desktop: JFileChooser; Android: SAF in
 * Track B, stub-toast for now).
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onScanOnLaunchChange: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        // === Theme section ===
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.themeMode == mode, onClick = null)
                    Spacer(modifier = Modifier.height(0.dp))
                    Text(
                        text = mode.name,
                        modifier = Modifier.padding(start = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))

        // === Behavior section ===
        Text("Behavior", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Scan library on launch", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.scanOnLaunch,
                onCheckedChange = onScanOnLaunchChange,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))

        // === Library section ===
        Text("Library folders", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (state.scanFolders.isEmpty()) {
            Text(
                "No folders configured. Click 'Add Folder' to choose your music library.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            state.scanFolders.forEach { folder ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(folder, style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { onRemoveFolder(folder) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove $folder")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFolder) { Text("Add Folder") }
    }
}
```

- [ ] **Step 4: Create SettingsScreenTest.kt — Compose UI smoke test**

Path: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreenTest.kt`

```kotlin
package com.clayworks.kiln.ui.components.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clayworks.kiln.library.settings.ThemeMode
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renders_all_three_sections() {
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = emptyList(),
                ),
                onThemeModeChange = {},
                onScanOnLaunchChange = {},
                onPickFolder = {},
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Behavior").assertIsDisplayed()
        composeRule.onNodeWithText("Library folders").assertIsDisplayed()
    }

    @Test
    fun shows_empty_state_when_no_scan_folders() {
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = emptyList(),
                ),
                onThemeModeChange = {},
                onScanOnLaunchChange = {},
                onPickFolder = {},
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("No folders configured. Click 'Add Folder' to choose your music library.")
            .assertIsDisplayed()
    }

    @Test
    fun lists_configured_scan_folders() {
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = listOf("D:\\tiddl", "E:\\flac-import"),
                ),
                onThemeModeChange = {},
                onScanOnLaunchChange = {},
                onPickFolder = {},
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("D:\\tiddl").assertIsDisplayed()
        composeRule.onNodeWithText("E:\\flac-import").assertIsDisplayed()
    }

    @Test
    fun theme_radio_click_invokes_callback() {
        var captured: ThemeMode? = null
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = emptyList(),
                ),
                onThemeModeChange = { captured = it },
                onScanOnLaunchChange = {},
                onPickFolder = {},
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("Dark").performClick()
        assertEquals(ThemeMode.Dark, captured)
    }

    @Test
    fun add_folder_button_invokes_picker_callback() {
        var clicked = false
        composeRule.setContent {
            SettingsScreen(
                state = SettingsState(
                    themeMode = ThemeMode.System,
                    scanOnLaunch = false,
                    scanFolders = emptyList(),
                ),
                onThemeModeChange = {},
                onScanOnLaunchChange = {},
                onPickFolder = { clicked = true },
                onRemoveFolder = {},
            )
        }
        composeRule.onNodeWithText("Add Folder").performClick()
        assertTrue(clicked)
    }
}
```

- [ ] **Step 5: Run :ui:components:desktopTest**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
```

Expected: BUILD SUCCESSFUL; 5 new tests pass.

- [ ] **Step 6: Full canonical verify-build**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```

Expected: PASS. Note: the canonical verify-build only runs `:data:library:desktopTest` so its total (99 tests) doesn't include the new `:ui:components:desktopTest` cases. Run `./gradlew :ui:components:desktopTest` separately to confirm those.

- [ ] **Step 7: Commit**

```bash
git add ui/components/build.gradle.kts ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/ ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/settings/
git commit -m "$(cat <<'EOF'
feat(ui:components): SettingsScreen — Phase 2a Track A's first Compose surface

Stateless Material 3 settings screen with three sections: theme (radio
group over ThemeMode.Light/Dark/System), behavior (scan-on-launch switch),
library (scan-folder list + add/remove). State + callbacks hoisted to app
modules where SettingsRepository writes happen. Folder-picker button is
neutral — the actual JFileChooser (Desktop) and SAF picker (Android,
Track B) live in their respective app modules.

First non-empty content in :ui:components; first tests in the module
(5 Compose UI tests via compose-ui-test-junit4 on desktopTest).

Adds project(":data:library") + project(":ui:theme") dependencies plus
desktopTest source set wiring. Module no longer relies on the canonical
verify-build for full coverage — run :ui:components:desktopTest explicitly.

Phase 2a Track A Task 6 of 9. Next: DI rewire so app modules can read
SettingsRepository to assemble SettingsState.
EOF
)"
```

---

### Task 7: DI rewire — DesktopAppGraph + JvmFilesystemScanner

**Why:** This is the load-bearing rewire. `DesktopAppGraph` currently takes `ScanFolders` as a value-class constructor param; `JvmFilesystemScanner` reads a static `List<Path>`. Track A replaces the static path with a `Flow<List<Path>>` derived from `SettingsRepository.scanFolders`.

**Files:**
- Modify: `data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt` — constructor `scanFolders: List<Path>` → `scanFoldersFlow: Flow<List<Path>>`; read inside `runScan()`
- Modify: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScannerTest.kt` — pass `flowOf(folders)`
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt` — drop `ScanFolders` value class + ctor param; add SettingsRepository provider; derive Flow<List<Path>> with fallback `[D:\tiddl]`; expose `settings` and `themeMode` on graph
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt` — drop `ScanFolders` from `::class.create(...)` call
- Modify: `app-desktop/src/test/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraphTest.kt` — update assertions

**Steps:**

- [ ] **Step 1: Modify JvmFilesystemScanner to take Flow<List<Path>>**

Edit `data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt`:

Replace constructor signature (around line 38):

```kotlin
class JvmFilesystemScanner(
    private val scanFoldersFlow: Flow<List<Path>>,
    private val db: KilnDatabase,
    private val driver: SqlDriver,
    private val ioDispatcher: CoroutineDispatcher,
) : LibraryScanner {
```

And replace the `runScan` method's reading-pattern: read `scanFoldersFlow.first()` before the `if (scanFolders.isEmpty())` guard. Add imports for `kotlinx.coroutines.flow.Flow` and `kotlinx.coroutines.flow.first`:

```kotlin
private fun runScan(forceFullRescan: Boolean): Either<ScanError, ScanResult> =
    Either.catch {
        val scanFolders = kotlinx.coroutines.runBlocking { scanFoldersFlow.first() }
        val scanStartedMs = System.currentTimeMillis()

        if (scanFolders.isEmpty()) {
            log.w { ... }       // existing guard unchanged
            return@catch ...    // existing early-return unchanged
        }
        ...                     // rest of existing logic referencing scanFolders unchanged
    }
```

Note: the existing function already runs under `withContext(ioDispatcher) { runScan(...) }` from `scanIncremental()/scanFull()`. The `runBlocking { scanFoldersFlow.first() }` inside `Either.catch` is acceptable because we're already on the IO dispatcher and `first()` over a Flow.map of a SQLDelight `asFlow()` is a cheap single-value read. If a subagent prefers, lift the `.first()` outside `Either.catch` so it suspends naturally — there's a stylistic call to make per Either.catch + suspend interaction.

Cleaner alternative: change `runScan` to `suspend` and remove the `runBlocking`:

```kotlin
override suspend fun scanIncremental(): Either<ScanError, ScanResult> =
    withContext(ioDispatcher) { runScan(forceFullRescan = false) }

override suspend fun scanFull(): Either<ScanError, ScanResult> =
    withContext(ioDispatcher) { runScan(forceFullRescan = true) }

private suspend fun runScan(forceFullRescan: Boolean): Either<ScanError, ScanResult> =
    Either.catch {
        val scanFolders = scanFoldersFlow.first()
        // ... rest unchanged
    }
```

Choose the cleaner alternative.

- [ ] **Step 2: Update JvmFilesystemScannerTest.kt to pass flowOf(folders)**

Edit the test file. Each constructor call to `JvmFilesystemScanner(folders, db, driver, dispatcher)` becomes `JvmFilesystemScanner(flowOf(folders), db, driver, dispatcher)`. Add `import kotlinx.coroutines.flow.flowOf`.

- [ ] **Step 3: Run :data:library:desktopTest to confirm existing scanner tests pass with new signature**

```powershell
pwsh -c "./gradlew :data:library:desktopTest"
```

Expected: BUILD SUCCESSFUL; existing scanner tests + 8 settings tests = ~71 tests.

- [ ] **Step 4: Modify DesktopAppGraph — drop ScanFolders value class + add SettingsRepository provider**

Edit `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt`:

- Remove the `ScanFolders` value class declaration entirely (around lines 51-53).
- Remove `@get:Provides protected val scanFolders: ScanFolders` constructor param (line 59).
- Add SettingsRepository provider + scan-folders flow derivation. The default-fallback for `[D:\tiddl]` lives here:

```kotlin
package com.clayworks.kiln.desktop.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.clayworks.kiln.audio.playback.Decoder
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.audio.playback.createJavaSoundPlayer
import com.clayworks.kiln.audio.playback.createJvmFlacDecoder
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.scan.JvmFilesystemScanner
import com.clayworks.kiln.library.scan.LibraryScanner
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.settings.SettingsRepositoryImpl
import com.clayworks.kiln.library.source.LocalLibrarySource
import com.clayworks.kiln.library.source.MusicSource
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@JvmInline
value class UserDataDir(val path: Path)

@Singleton
@Component
abstract class DesktopAppGraph(
    @get:Provides protected val userDataDir: UserDataDir,
) {
    abstract val musicSource: MusicSource
    abstract val scanner: LibraryScanner
    abstract val player: PlatformPlayer
    abstract val settings: SettingsRepository

    @Singleton
    @Provides
    protected fun sqlDriver(): SqlDriver {
        val dbFile = userDataDir.path.resolve("kiln.db")
        java.nio.file.Files.createDirectories(userDataDir.path)
        return JdbcSqliteDriver(
            url = "jdbc:sqlite:${dbFile.toAbsolutePath()}",
            properties = Properties().apply { put("foreign_keys", "true") },
            schema = KilnDatabase.Schema,
        )
    }

    @Singleton
    @Provides
    protected fun database(driver: SqlDriver): KilnDatabase = KilnDatabase(driver)

    @Singleton
    @Provides
    protected fun settingsRepository(db: KilnDatabase): SettingsRepository =
        SettingsRepositoryImpl(db, Dispatchers.IO)

    @Singleton
    @Provides
    protected fun localLibrarySource(db: KilnDatabase): MusicSource =
        LocalLibrarySource(db, Dispatchers.IO)

    @Singleton
    @Provides
    protected fun filesystemScanner(
        settings: SettingsRepository,
        db: KilnDatabase,
        driver: SqlDriver,
    ): LibraryScanner {
        // Phase 2a Track A: scan folders driven by SettingsRepository, with
        // a fallback default of D:\tiddl on first launch (Clay's library root
        // per CLAUDE.md). Once the user has saved scan_folders=[] explicitly,
        // an empty list is honored — the scanner's empty-guard skips work
        // rather than soft-deleting everything.
        val scanFoldersFlow: Flow<List<Path>> = settings.scanFolders.map { stored ->
            if (stored.isEmpty() && !userHasSavedEmpty(settings)) {
                listOf(Path.of("D:\\tiddl"))
            } else {
                stored.map { Path.of(it) }
            }
        }
        return JvmFilesystemScanner(scanFoldersFlow, db, driver, Dispatchers.IO)
    }

    private fun userHasSavedEmpty(@Suppress("UNUSED_PARAMETER") settings: SettingsRepository): Boolean = false
    // Phase 2a Track A simplification: first-launch (no settings row at all)
    // and "user saved empty" both yield empty list from settings.scanFolders.
    // Track A's behavior: empty list at the settings layer means the scanner
    // hits its empty-guard and skips work; the user must add a folder via
    // the picker. The "default to D:\tiddl on first launch" fallback above
    // is intentionally conservative — set only on first launch by the
    // setup-seed function below.

    @Singleton
    @Provides
    protected fun audioDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kiln-audio-out").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
            }
        }.asCoroutineDispatcher()

    @Singleton
    @Provides
    protected fun decoder(): Decoder = createJvmFlacDecoder()

    @Singleton
    @Provides
    protected fun player(
        audioDispatcher: CoroutineDispatcher,
        decoder: Decoder,
        source: MusicSource,
    ): PlatformPlayer = createJavaSoundPlayer(audioDispatcher, decoder, source)
}
```

Actually simplify — drop the `userHasSavedEmpty` placeholder helper and use a clean fallback:

```kotlin
val scanFoldersFlow: Flow<List<Path>> = settings.scanFolders.map { stored ->
    stored.map { Path.of(it) }
}
```

Then handle the "first-launch seed D:\\tiddl" in a one-shot seed call from Main.kt (Task 8). Don't bake "if empty, use D:\\tiddl" into the graph — that prevents the user from ever legitimately having zero folders.

Re-revise DesktopAppGraph to use the clean fallback (no `userHasSavedEmpty` helper).

- [ ] **Step 5: Update DesktopAppGraphTest.kt assertions**

Edit `app-desktop/src/test/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraphTest.kt` to drop the `scanFolders = ScanFolders(...)` arg from the test graph construction and to add an assertion that `graph.settings` resolves cleanly.

- [ ] **Step 6: Run :app-desktop:test to confirm**

```powershell
pwsh -c "./gradlew :app-desktop:test"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Update Main.kt to drop ScanFolders arg**

Edit `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt`:

- Remove `import com.clayworks.kiln.desktop.di.ScanFolders`.
- Remove the `scanFolders = ScanFolders(listOf(Path.of("D:\\tiddl"))),` argument from the `DesktopAppGraph::class.create(...)` call.
- Add a one-shot seed call BEFORE `application { }` block to populate scan_folders if unset (first-launch flow):

```kotlin
fun main() {
    val graph = DesktopAppGraph::class.create(
        userDataDir = UserDataDir(Path.of(System.getProperty("user.home"), ".kiln")),
    )

    // First-launch seed: if the user has never configured scan folders,
    // populate D:\tiddl as the default (Clay's library root per CLAUDE.md).
    // After the first save (including saving an empty list explicitly),
    // this no-ops — first() over the SQLDelight Flow returns the persisted
    // value on subsequent launches.
    kotlinx.coroutines.runBlocking {
        val existing = kotlinx.coroutines.flow.first(graph.settings.scanFolders)
        // First read returns empty list if no row exists (default at repo layer)
        // OR an actual empty list the user saved. Distinguish via raw query? No —
        // simpler: only seed if NULL (no row). For Track A, treat empty list as
        // "user has never touched it" — a deliberate empty save by the user is
        // a Track-B/C edge case not surfaced in Track A's UI yet.
        if (existing.isEmpty()) {
            graph.settings.setScanFolders(listOf("D:\\tiddl"))
        }
    }

    application {
        Window(...) { ... }
    }
}
```

Note: the conflation of "no row" vs "user saved empty" is a known limit of Track A. Track A's UI doesn't yet offer "remove all folders"; if a future flow does, this seed logic needs differentiation (could store a separate `bootstrap_complete=true` setting key).

- [ ] **Step 8: Wire the canonical verify-build**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```

Expected: PASS, 5/5 targets, 99 tests + 1 skipped.

- [ ] **Step 9: Smoke-launch the desktop app to confirm scan still works**

```powershell
pwsh -c "./gradlew :app-desktop:run"
```

Expected: Window opens; click "Scan Library" → existing 27k tracks rescan as incremental (no destructive change). Close window.

- [ ] **Step 10: Commit**

```bash
git add data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScannerTest.kt app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt app-desktop/src/test/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraphTest.kt
git commit -m "$(cat <<'EOF'
refactor(desktop): DI rewire scan folders from value-class param to Settings flow

Drops the ScanFolders value class entirely. DesktopAppGraph constructor
takes only UserDataDir now; scan folders come from SettingsRepository on
demand. JvmFilesystemScanner accepts a Flow<List<Path>> instead of a
static List<Path>, reads .first() inside the suspending runScan body.

Main.kt seeds D:\tiddl as scan_folders on first launch (no settings row
yet); subsequent launches respect the persisted value. Behavior change:
once the user clears all folders via the SettingsScreen (Task 8), the
seed no-ops — the empty-guard in JvmFilesystemScanner skips work rather
than soft-deleting the library. A future "first vs cleared" distinction
needs a bootstrap_complete flag; defer to Track A's known-edge list.

Phase 2a Track A Task 7 of 9. Next: AndroidAppGraph adds SettingsRepository
provider; both apps wire KilnTheme + SettingsScreen.
EOF
)"
```

---

### Task 8: AndroidAppGraph + MainActivity wiring

**Why:** Mirror the Desktop wiring on Android. AndroidAppGraph adds the SettingsRepository provider. MainActivity wraps content in `KilnTheme(themeMode = ...)` and exposes a gear icon → SettingsScreen route. Android folder-picker is a stub (Track B replaces with SAF).

**Files:**
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` — add `SettingsRepository` provider, expose on graph
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt` — wrap in `KilnTheme(themeMode)`; add gear icon; SettingsScreen route with stub folder-picker that fires a Toast
- Modify: `app-android/src/test/kotlin/com/clayworks/kiln/di/AndroidAppGraphTest.kt` — assert settings exposed
- Modify: `app-android/build.gradle.kts` — add `project(":ui:components")` and `project(":ui:theme")` deps if not present

**Steps:**

- [ ] **Step 1: Inspect current app-android build.gradle.kts and verify ui module deps**

Read `app-android/build.gradle.kts` to confirm presence of `project(":ui:components")` and `project(":ui:theme")`. If absent, add to dependencies. Likely already present (Compose surface; previous H7 PlayFirstTrackScreen consumed MaterialTheme from the bundles).

- [ ] **Step 2: Modify AndroidAppGraph.kt — add SettingsRepository provider**

Edit `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt`. Add abstract member + provider:

```kotlin
abstract val settings: SettingsRepository
```

```kotlin
@Singleton
@Provides
protected fun settingsRepository(db: KilnDatabase): SettingsRepository =
    SettingsRepositoryImpl(db, Dispatchers.IO)
```

Add imports for `SettingsRepository` + `SettingsRepositoryImpl`.

- [ ] **Step 3: Update AndroidAppGraphTest.kt**

Edit `app-android/src/test/kotlin/com/clayworks/kiln/di/AndroidAppGraphTest.kt` to add an assertion that `graph.settings` resolves cleanly without error.

- [ ] **Step 4: Run :app-android:testDebugUnitTest**

```powershell
pwsh -c "./gradlew :app-android:testDebugUnitTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Modify MainActivity.kt — KilnTheme wrap + gear icon + SettingsScreen route**

Edit `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt`. Replace the bare `MaterialTheme { ... }` with `KilnTheme(themeMode = ...)`. Hoist a `showSettings` boolean. Add a gear icon top-right that toggles to `SettingsScreen`. Stub folder-picker fires `Toast.makeText(context, "SAF picker arrives in Track B", LENGTH_LONG).show()`.

Concrete changes:

```kotlin
package com.clayworks.kiln

import ...                           // existing imports
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.clayworks.kiln.library.settings.ThemeMode
import com.clayworks.kiln.ui.components.settings.SettingsScreen
import com.clayworks.kiln.ui.components.settings.SettingsState
import com.clayworks.kiln.ui.theme.KilnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as KilnApplication).graph
        setContent {
            val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
            KilnTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        AndroidSettingsRoute(
                            graph = graph,
                            onClose = { showSettings = false },
                        )
                    } else {
                        PlayFirstTrackScreen(graph = graph, onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidSettingsRoute(
    graph: AndroidAppGraph,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
    val scanOnLaunch by graph.settings.scanOnLaunch.collectAsState(initial = false)
    val scanFolders by graph.settings.scanFolders.collectAsState(initial = emptyList())

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
            state = SettingsState(themeMode, scanOnLaunch, scanFolders),
            onThemeModeChange = { coroutineScope.launch { graph.settings.setThemeMode(it) } },
            onScanOnLaunchChange = { coroutineScope.launch { graph.settings.setScanOnLaunch(it) } },
            onPickFolder = {
                Toast.makeText(
                    context,
                    "SAF folder picker arrives in Phase 2a Track B",
                    Toast.LENGTH_LONG,
                ).show()
            },
            onRemoveFolder = { folder ->
                coroutineScope.launch {
                    graph.settings.setScanFolders(scanFolders - folder)
                }
            },
        )
    }
}
```

And add the gear-icon entry to `PlayFirstTrackScreen` by adding an `onOpenSettings: () -> Unit` parameter and inserting an `IconButton` at the top of the existing Column:

```kotlin
@Composable
private fun PlayFirstTrackScreen(graph: AndroidAppGraph, onOpenSettings: () -> Unit) {
    ...                                                                 // existing scope
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Kiln by Clayworks", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        ...                                                             // existing body
    }
}
```

- [ ] **Step 6: Build :app-android:assembleDebug to confirm**

```powershell
pwsh -c "./gradlew :app-android:assembleDebug"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Canonical verify-build**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt app-android/src/test/kotlin/com/clayworks/kiln/di/AndroidAppGraphTest.kt
git commit -m "$(cat <<'EOF'
feat(app-android): wire KilnTheme + SettingsScreen — Phase 2a Track A

AndroidAppGraph exposes SettingsRepository (singleton, DB-backed); MainActivity
wraps content in KilnTheme(themeMode = settings.themeMode.collectAsState())
and adds a gear icon at the top of PlayFirstTrackScreen that flips to the
new SettingsScreen route. Theme/scan-on-launch toggles persist via
SettingsRepository.setX() calls. Folder picker is stubbed — clicking
"Add Folder" fires a toast pointing at Phase 2a Track B; existing folder
chips show a delete icon that calls setScanFolders(scanFolders - folder).

Android side intentionally skips scan-folder seeding (MediaStore handles
that; SAF picks land in Track B).

Phase 2a Track A Task 8 of 9. Next: Desktop SettingsScreen wiring +
functional JFileChooser folder picker.
EOF
)"
```

---

### Task 9: Desktop SettingsScreen wiring + functional JFileChooser folder picker

**Why:** Desktop closes the loop — same gear-icon route as Android, but the folder picker is a real `javax.swing.JFileChooser` in directory-mode. No SAF on Desktop; the JVM file chooser is the standard pattern. The picker writes the chosen path into `scan_folders`; the JvmFilesystemScanner picks it up on the next scan.

**Files:**
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt` — add gear icon, SettingsScreen route, JFileChooser picker

**Steps:**

- [ ] **Step 1: Modify Main.kt — add gear + SettingsScreen route + picker**

Edit `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt`. Replace bare `MaterialTheme { ... }` with `KilnTheme(themeMode)`. Add `showSettings` state. The Settings route invokes a `pickFolderDialog()` helper that uses JFileChooser.

Key additions to Main.kt:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import com.clayworks.kiln.library.settings.ThemeMode
import com.clayworks.kiln.ui.components.settings.SettingsScreen
import com.clayworks.kiln.ui.components.settings.SettingsState
import com.clayworks.kiln.ui.theme.KilnTheme
import javax.swing.JFileChooser

fun main() {
    val graph = ...                                                     // existing

    // First-launch seed (unchanged from Task 7)
    kotlinx.coroutines.runBlocking { /* seed D:\tiddl logic */ }

    application {
        Window(onCloseRequest = ::exitApplication, title = "Kiln by Clayworks") {
            val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
            KilnTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        DesktopSettingsRoute(graph = graph, onClose = { showSettings = false })
                    } else {
                        PlayFirstTrackScreen(graph = graph, onOpenSettings = { showSettings = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSettingsRoute(graph: DesktopAppGraph, onClose: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
    val scanOnLaunch by graph.settings.scanOnLaunch.collectAsState(initial = false)
    val scanFolders by graph.settings.scanFolders.collectAsState(initial = emptyList())

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
            state = SettingsState(themeMode, scanOnLaunch, scanFolders),
            onThemeModeChange = { coroutineScope.launch { graph.settings.setThemeMode(it) } },
            onScanOnLaunchChange = { coroutineScope.launch { graph.settings.setScanOnLaunch(it) } },
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
        )
    }
}

/**
 * JFileChooser in directory-select mode. Returns the chosen path as a
 * String or null if the user cancelled. Runs synchronously on the Swing
 * EDT — we're called from a Compose coroutine context, so wrap in the
 * Swing dispatcher to avoid threading violations.
 */
private suspend fun pickFolderDialog(): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.swing.Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Add Kiln library folder"
        }
        when (chooser.showOpenDialog(null)) {
            JFileChooser.APPROVE_OPTION -> chooser.selectedFile.absolutePath
            else -> null
        }
    }
```

Add `PlayFirstTrackScreen(graph: DesktopAppGraph, onOpenSettings: () -> Unit)` parameter + gear icon at the top, mirroring the Android pattern.

- [ ] **Step 2: Verify kotlinx-coroutines-swing is on :app-desktop's classpath**

Check `app-desktop/build.gradle.kts`. The `kotlinx-coroutines-swing` library is declared in `libs.versions.toml` (line 90). Confirm `:app-desktop` pulls it; if not, add:

```kotlin
implementation(libs.kotlinx.coroutines.swing)
```

- [ ] **Step 3: Build :app-desktop:assemble**

```powershell
pwsh -c "./gradlew :app-desktop:assemble"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the desktop app and smoke-test the picker**

```powershell
pwsh -c "./gradlew :app-desktop:run"
```

Manual:
1. Click gear icon → SettingsScreen renders.
2. Theme radio "Dark" → background flips dark immediately.
3. Toggle "Scan library on launch" → switch state persists.
4. Click "Add Folder" → JFileChooser opens → pick `C:\Users\chawo\Music` or any test folder → folder appears in list.
5. Close + relaunch via `./gradlew :app-desktop:run` → settings persist.

Close the app when smoke-test verified.

- [ ] **Step 5: Canonical verify-build**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```

Expected: PASS, 5/5 targets, 99 tests + 1 skipped.

- [ ] **Step 6: Commit**

```bash
git add app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt
git commit -m "$(cat <<'EOF'
feat(app-desktop): wire KilnTheme + SettingsScreen + JFileChooser folder picker

Desktop closes Phase 2a Track A's loop: same gear-icon route + SettingsScreen
shape as Android, but the folder picker is a functional javax.swing.JFileChooser
in DIRECTORIES_ONLY mode. Returns the chosen absolute path as a String,
appended to scan_folders; JvmFilesystemScanner picks it up on next scan
via the Flow<List<Path>> chain wired in Task 7. Picker runs on the Swing
EDT via kotlinx-coroutines-swing dispatcher.

Theme toggle works immediately (KilnTheme recomposes on themeMode change).
Persistence: cold restart → all three settings (theme / scan-on-launch /
scan_folders) reload from SQLDelight.

Phase 2a Track A Task 9 of 9. Track A is complete pending final verify-build
+ session-close push.
EOF
)"
```

---

## Final verification (run after all 9 tasks ship)

### Full verify-build + module-specific test suites

```powershell
# Canonical 5-target gate
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1

# Full test surfaces touched by Track A
./gradlew :data:library:desktopTest :data:library:testAndroidHostTest :data:library:verifyCommonMainKilnDatabaseMigration
./gradlew :ui:components:desktopTest
./gradlew :app-android:testDebugUnitTest
./gradlew :app-desktop:test
```

**Expected counts:**
- `:data:library:desktopTest` ≥ 71 (was 63; +8 SettingsRepository).
- `:data:library:testAndroidHostTest` ≥ 47 (was 44; +3 if Android-host SettingsRepository smoke is wired — optional Track-A scope).
- `:ui:components:desktopTest` = 5 (was 0; first tests in module).
- `:app-android:testDebugUnitTest` ≥ 2 (was 2; AndroidAppGraphTest may grow by 1 assertion).
- `:app-desktop:test` ≥ 2 (was 2; DesktopAppGraphTest may grow by 1 assertion).
- Canonical verify-build: 99 tests + 1 skipped (counts only `:data:library:desktopTest`).

### Manual smoke test (Desktop)

1. `./gradlew :app-desktop:run`
2. Click gear icon → SettingsScreen renders.
3. Toggle each theme mode → colors update immediately.
4. Toggle scan-on-launch → switch state changes.
5. Click "Add Folder" → JFileChooser opens → pick a folder → appears in list.
6. Click delete icon next to a folder → folder disappears.
7. Close app → relaunch → all three settings persist.

### Manual smoke test (Android, on Pixel 7)

1. `./gradlew :app-android:installDebug` then `adb -s 2A261FDH300B1P shell monkey -p com.clayworks.kiln -c android.intent.category.LAUNCHER 1`
2. Tap gear icon → SettingsScreen renders.
3. Toggle each theme mode → colors update immediately.
4. Tap "Add Folder" → toast appears: "SAF folder picker arrives in Phase 2a Track B".
5. Cold-kill app via `adb -s 2A261FDH300B1P shell am force-stop com.clayworks.kiln`.
6. Relaunch → theme + scan-on-launch persist.

### Session-close

- Push all 9 commits in one push: `git push origin main`
- Confirm CI green
- Engram session summary + handoff doc (Track B is the natural next session)

---

## Risk register (Track A specifics)

| Risk | Mitigation |
|------|-----------|
| `verifyMigrations` fails on first commit of Task 1 because something about the existing `.sq` files generates a non-standard v1 snapshot | Run `generateCommonMainKilnDatabaseSchema` BEFORE adding 2.sqm so the v1 snapshot reflects current schema; if it still fails, the failure points to a real schema-vs-implicit-version mismatch worth fixing immediately. |
| `kotlinx-serialization-json` not available in `:data:library:commonMain` (plugin not applied module-level) | Task 4 Step 1 adds `alias(libs.plugins.kotlin.plugin.serialization)` to data/library/build.gradle.kts. |
| SettingsRepository's `parseThemeMode` swallows errors silently and old, corrupt rows go unnoticed | Logged at WARN level via Kermit (kermit is already on :data:library classpath). Future telemetry could promote these to a backstop counter; out of scope for Track A. |
| First-launch seed conflates "no row" with "user saved empty" | Documented in Task 7 Step 7 commit message + Track A's known-edge list. Track A's UI doesn't yet offer "remove all folders" so the conflation is invisible. Track B/C addressing scan-folder management will need a `bootstrap_complete` flag. |
| `kotlinx.coroutines.runBlocking` in Main.kt first-launch seed blocks the EDT briefly on every launch (until the seed runs once) | Acceptable on Desktop (cold-start adds <50ms even on slow disks). The block runs BEFORE `application { }` so Compose hasn't started. |
| The compose-ui-test-junit4 dependency may pull a different Compose runtime than `:ui:components` uses on Desktop | All Compose deps in libs.versions.toml share `jb-compose = "1.11.0"`; if there's a version skew it surfaces in Step 5's `:ui:components:desktopTest` run. |
| JFileChooser swing-dispatcher import (`kotlinx.coroutines.swing.Dispatchers.Swing`) is wrong syntax — correct is `Dispatchers.Swing` from the swing module | Task 9 Step 1 imports `kotlinx.coroutines.swing.Swing` (extension property on `Dispatchers`). Implementer subagent will discover empirically if the import path differs from the planned form. |
| AGP 9.2.1 + KMP — `:app-android` is `com.android.application` (legacy plugin), not `androidLibrary` — `testImplementation` vs `androidTestImplementation` distinction matters | The AndroidAppGraphTest is already wired via `testImplementation` + Robolectric per Phase 5; new assertions add to that surface, no plugin change needed. |
| The `kotlinx-serialization-json` library entry already exists in `libs.versions.toml` (line 92) but no `alias` is defined for the plugin in `[plugins]` block | Verify line 245-247 in libs.versions.toml — likely needs new entry `kotlin-plugin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }`. If missing, Task 4 Step 1 also adds the plugin entry. |

---

## Out of scope (deliberate for Track A)

- **Voyager navigation** — Track A uses a hoisted boolean for Settings route. Voyager lands in Track C (proper UI).
- **Circuit presenter** — same; deferred to Track C.
- **kmpalette dynamic theming** — KilnTheme uses default Material 3 schemes. Album-art palette pipeline lands at Phase 2a Flight A (after Track A).
- **Android SAF folder picker** — stub toast for Track A; functional in Track B.
- **AndroidMediaStoreScanner scan-folder injection** — Android's MediaStore is system-side; user-picked folders need SAF (Track B). Track A leaves AndroidMediaStoreScanner unchanged.
- **Scan-on-launch behavior wiring** — Track A persists the toggle but doesn't yet auto-invoke the scanner on launch. Track A's UI exposes the toggle; the launch-time hook is Track A optional / Track B reasonable scope addition.

If a subagent finishes Tasks 1-9 with capacity, the "wire scan-on-launch into Main.kt + MainActivity.onCreate" is a clean ~10-minute addition (a single `if (settings.scanOnLaunch.first()) scanner.scanIncremental()` block, fire-and-forget). Add as a Task 10 only if implementer surfaces it as wanted.

---

End of Phase 2a Track A plan. Total: 9 logical tasks, ~9 commits, ~3-6 wall-clock hours with subagent dispatch + two-stage review.
