# Pre-Phase-2a Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all P1 findings from `docs/reviews/2026-05-21-tooling-armed-review.md` plus the cheap P2/P3 items that earn their keep before Phase 2a Session 11 picks a track. Net effect: clean baseline, honest test surface, validated assumptions, working CI test gate.

**Architecture:** 8 independent phases. Phases 1-4 are load-bearing (P1 + cheap P2 fixes + the CI warm-up). Phases 5-8 are test-infrastructure work that closes the Android-side coverage gap that Session 10 recap §12 explicitly called out. Order within a phase is strict; order between phases is flexible (Phase 5 blocks 7+8; otherwise independent).

**Tech Stack:** Kotlin 2.3.21 KMP, Gradle 9.x, SQLDelight 2.3.2 (FTS5 contentless), JNA 5.14.0→5.17.0, jaudiotagger 3.0.1 (Desktop FLAC tag extraction), arrow-core 2.2.2.1, kotlin-inject 0.9.0, kiln-verify-build skill for canonical builds, kiln-db-desktop MCP for live DB diagnostics.

**Spec source:** `docs/reviews/2026-05-21-tooling-armed-review.md` (24 findings: 0 P0 / 6 P1 / 11 P2 / 7 P3). This plan addresses 6 P1s + 7 P2s + 3 P3s = 16 of 24. Deferred 8: P2-2 (jaudiotagger survey, only if Phase 4 reveals it), P2-3 (Voyager stale, revisit at Track C), P2-4 (kmpalette beta, revisit at Track A/Flight A), P2-8 (AudioProcessor placement, premature until MVP Session 16+ EQ), P3-2 + P3-3 + P3-6 (UI/UX decisions for Track C), P3-7 (MVP-1.0 close criterion).

**Phase budget (estimated):**
| Phase | Scope | Effort |
|---|---|---|
| 1 | Quick wins (5 micro-fixes + 4 doc updates) | 1-3 h |
| 2 | CI desktopTest gate (Session 11 Track F) | 1-2 h |
| 3 | Dependency hygiene (JNA bump + Skiko constraint) | 1-3 h |
| 4 | ReplayGain probe + scanner fix + re-scan | 2-6 h |
| 5 | androidUnitTest source set infra | 6-10 h |
| 6 | LocalLibrarySource tests (commonTest) | 6-10 h |
| 7 | Media3ExoPlayerImpl tests (Android, after P5) | 8-12 h |
| 8 | DI graph tests (Android+Desktop, after P5) | 2-3 h |
| **Total** | | **27-49 h** |

**Suggested sequence (honoring dependencies + risk):** 1 → 3 → 2 → 4 → 6 → 5 → 8 → 7. This front-loads the cheap risk reduction (quick wins, dep bump, CI gate), validates Track D's premise (Phase 4), then closes coverage gaps in escalating effort order.

---

## File Structure

Files this plan creates or modifies, grouped by phase:

**Phase 1 — Quick wins:**
- `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/internal/ScanInternals.kt` — modify `parseChannels` validation
- `data/library/src/commonTest/kotlin/com/clayworks/kiln/library/scan/internal/ScanInternalsTest.kt` — add validation cases
- `.claude/skills/kiln-verify-build/scripts/parse-gradle.ps1` — fix multi-module test counting
- `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlatformPlayer.kt` — add measurement-mode stub
- `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/MeasurementSession.kt` — new file, Phase 3 architectural seam
- `data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt` — add intentional-UPDATE comment
- `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt` — add intentional-UPDATE comment
- `docs/superpowers/plans/2026-05-18-kiln-execution-plan.md` — append §4 reconciliation addendum
- `docs/sessions/2026-05-21-session-11-handoff.md` — Track A effort revision
- `docs/decisions/2026-05-21-tooling-recommendation.md` — append review-surfaced tooling addendum

**Phase 2 — CI gate:**
- `.github/workflows/build.yml` — add desktopTest steps to both jobs

**Phase 3 — Dep hygiene:**
- `gradle/libs.versions.toml` — JNA 5.14.0 → 5.17.0
- `build-logic/src/main/kotlin/kiln.kmp.library.gradle.kts` OR new convention plugin — Skiko constraint

**Phase 4 — ReplayGain:**
- `data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt` — fix extraction
- `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScannerTest.kt` — add RG extraction test
- `data/library/src/commonTest/kotlin/com/clayworks/kiln/library/source/LocalLibrarySourceMappersTest.kt` — already has mapper coverage; verify

**Phase 5 — androidUnitTest infra:**
- `data/library/build.gradle.kts` — add androidUnitTest source set
- `audio/playback/build.gradle.kts` — add androidUnitTest source set
- `build-logic/src/main/kotlin/kiln.kmp.library.gradle.kts` — convention support
- `data/library/src/androidUnitTest/kotlin/com/clayworks/kiln/library/SmokeAndroidUnitTest.kt` — smoke test verifying the source set works

**Phase 6 — LocalLibrarySource tests:**
- `data/library/src/commonTest/kotlin/com/clayworks/kiln/library/source/LocalLibrarySourceTest.kt` — 14-16 cases across browse/search/getPlayable
- `data/library/src/commonTest/kotlin/com/clayworks/kiln/library/source/TestDb.kt` — helper for in-memory schema setup

**Phase 7 — Media3ExoPlayerImpl tests:**
- `audio/playback/src/androidUnitTest/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImplTest.kt` — port of JavaSoundPlayerImplTest

**Phase 8 — DI graph tests:**
- `app-desktop/src/test/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraphTest.kt`
- `app-android/src/androidUnitTest/kotlin/com/clayworks/kiln/di/AndroidAppGraphTest.kt`

---

## Phase 1 — Quick wins

**Goal:** Close 5 micro-fixes (parseChannels, schema duration constraint deferred to Phase 4, skill bug, measurement-mode stub, scanner comment) + 4 doc updates. Each task is independent; commit boundaries match.

### Task 1.1: Fix `parseChannels` validation (P1-2)

**Files:**
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/internal/ScanInternals.kt:30-37`
- Test: `data/library/src/commonTest/kotlin/com/clayworks/kiln/library/scan/internal/ScanInternalsTest.kt`

- [ ] **Step 1: Write the failing test**

Open `data/library/src/commonTest/kotlin/com/clayworks/kiln/library/scan/internal/ScanInternalsTest.kt` and add (preserving existing tests):

```kotlin
@Test
fun parseChannels_rejectsNegativeValues_fallsBackToStereo() {
    // jaudiotagger AAC code path was observed returning "-14" — see review P1-2.
    assertEquals(2L, parseChannels("-14"))
}

@Test
fun parseChannels_rejectsZero_fallsBackToStereo() {
    assertEquals(2L, parseChannels("0"))
}

@Test
fun parseChannels_rejectsAbsurdlyLarge_fallsBackToStereo() {
    // Anything > 32 channels is presumed garbage (Dolby Atmos object beds run 64, but
    // those don't surface as a numeric channel count via jaudiotagger).
    assertEquals(2L, parseChannels("999"))
}

@Test
fun parseChannels_acceptsValidStereo() {
    assertEquals(2L, parseChannels("2"))
}

@Test
fun parseChannels_acceptsValidMono() {
    assertEquals(1L, parseChannels("1"))
}

@Test
fun parseChannels_acceptsValid71Surround() {
    assertEquals(8L, parseChannels("8"))
}
```

- [ ] **Step 2: Run tests to verify the new cases fail**

Run from repo root:
```powershell
./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.internal.ScanInternalsTest"
```
Expected: 3 failures (negative, zero, absurdly-large) with messages like `expected:<2> but was:<-14>`.

- [ ] **Step 3: Apply the one-line fix**

Edit `ScanInternals.kt:30-37`. Change the `else` branch from:

```kotlin
else -> channels.trim().toLongOrNull() ?: 2L
```

to:

```kotlin
else -> channels.trim().toLongOrNull()?.takeIf { it in 1..32 } ?: 2L
```

Full block for context:

```kotlin
internal fun parseChannels(channels: String?): Long = when {
    channels == null -> 2L
    channels.equals("Mono", ignoreCase = true) -> 1L
    channels.equals("Stereo", ignoreCase = true) -> 2L
    channels.contains("5.1") -> 6L
    channels.contains("7.1") -> 8L
    else -> channels.trim().toLongOrNull()?.takeIf { it in 1..32 } ?: 2L
}
```

- [ ] **Step 4: Run tests to verify all 6 cases pass**

```powershell
./gradlew :data:library:desktopTest --tests "com.clayworks.kiln.library.scan.internal.ScanInternalsTest"
```
Expected: BUILD SUCCESSFUL; previously-passing cases still pass; the 3 new "rejects garbage" cases now pass.

- [ ] **Step 5: Run canonical session-validation build**

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```
Expected: PASS, all 5 targets, test count incremented by 6 (the 3 new + 3 existing-style validations).

- [ ] **Step 6: Commit**

```powershell
git add data/library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/internal/ScanInternals.kt data/library/src/commonTest/kotlin/com/clayworks/kiln/library/scan/internal/ScanInternalsTest.kt
git commit -m "fix(scan): parseChannels rejects negative + out-of-range Long values

Review P1-2: 340 AAC tracks in the live DB had channels=-14 because
parseChannels' final ToLongOrNull fallback accepted any Long, including
nonsense from jaudiotagger's AAC channel-string accessor. Defensive
range check (1..32) coerces garbage to the stereo fallback.

Adds 6 regression cases in ScanInternalsTest covering -14, 0, 999,
2, 1, 8."
```

### Task 1.2: Clean up the 340 corrupted channels=-14 rows in the live DB

**Files:** No source changes — operational SQL via the kiln-db-desktop MCP.

- [ ] **Step 1: Verify count before**

Run via kiln-db-desktop MCP `execute_sql`:
```sql
SELECT COUNT(*) AS bad_channels
FROM track
WHERE (channels < 1 OR channels > 32) AND deleted_at_ms IS NULL;
```
Expected: 340 (per review Axis 3).

- [ ] **Step 2: Apply the one-shot UPDATE**

Run via the same MCP (write access is enabled per `.mcp.json`):
```sql
UPDATE track
SET channels = 2
WHERE (channels < 1 OR channels > 32) AND deleted_at_ms IS NULL;
```
Expected: `affected_rows=340`.

- [ ] **Step 3: Verify count after**

```sql
SELECT COUNT(*) AS bad_channels
FROM track
WHERE (channels < 1 OR channels > 32) AND deleted_at_ms IS NULL;
```
Expected: 0.

- [ ] **Step 4: Document the cleanup in engram**

Save via engram (no commit since this is operational state):
- title: `Kiln DB cleanup 2026-05-21 — 340 channels=-14 rows normalized to 2`
- topic_key: `kiln/db-cleanup-channels-2026-05-21`
- type: `manual`
- content: brief note of what + why (see Task 1.1 fix) + the SQL ran + that future re-scans will write channels=2 via the post-fix parseChannels.

### Task 1.3: Fix `kiln-verify-build` skill test-count aggregation (P2-5)

**Files:**
- Modify: `.claude/skills/kiln-verify-build/scripts/parse-gradle.ps1`

- [ ] **Step 1: Find the current XML-glob block**

Open `.claude/skills/kiln-verify-build/scripts/parse-gradle.ps1`. Locate the section that parses `TEST-*.xml` files. It currently globs against a single module's `build/test-results/desktopTest/`. (Inspect by reading the file; the skill's SKILL.md notes the limitation.)

- [ ] **Step 2: Rewrite the glob to walk all modules**

Replace the single-module glob with a recursive glob from the repo root. Example pattern (adapt to existing variable names in the script):

```powershell
$repoRoot = git rev-parse --show-toplevel
$testXmls = Get-ChildItem -Path $repoRoot -Recurse -Filter "TEST-*.xml" `
    | Where-Object { $_.FullName -match '\\build\\test-results\\desktopTest\\' }
$total = 0; $skipped = 0; $failed = 0
foreach ($xml in $testXmls) {
    [xml]$doc = Get-Content $xml.FullName
    $total   += [int]$doc.testsuite.tests
    $skipped += [int]$doc.testsuite.skipped
    $failed  += [int]$doc.testsuite.failures + [int]$doc.testsuite.errors
}
# emit "$total tests, $skipped skipped, $failed failed" — wire into the existing summary printer
```

- [ ] **Step 3: Run the skill and verify the new total**

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```
Expected output line now reads `(69 tests, 1 skipped)` or equivalent (was `41/41`).

- [ ] **Step 4: Commit**

```powershell
git add .claude/skills/kiln-verify-build/scripts/parse-gradle.ps1
git commit -m "fix(skills): kiln-verify-build aggregates test counts across modules

Review P2-5: parse-gradle.ps1 only walked one module's
build/test-results/desktopTest/. Real total is 69 (41 :data:library +
28 :audio:playback active + 1 skipped GoldenCorpus); skill reported
41/41 — a ~40% undercount.

Glob recursively from git rev-parse --show-toplevel, sum tests +
skipped + failures + errors across every TEST-*.xml under any module's
build/test-results/desktopTest/ tree."
```

### Task 1.4: Add Phase 3 measurement-mode stub to `PlatformPlayer` (P2-9)

**Files:**
- Create: `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/MeasurementSession.kt`
- Modify: `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlatformPlayer.kt`

- [ ] **Step 1: Create the MeasurementSession interface (sealed marker for Phase 3)**

Create `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/MeasurementSession.kt`:

```kotlin
// Measurement-mode marker for Phase 3 (REW-style room correction). Spec §6.1
// architectural-seam list commits MVP-1.0 to expose the seam even though the
// implementation lands at Phase 3. Today this interface is a stub; concrete
// impls will own the sample-accurate capture + sweep playback + FFT response
// analysis pipeline per spec §7.4.

package com.clayworks.kiln.audio.playback

/**
 * A measurement-mode session opened via [PlatformPlayer.enterMeasurementMode].
 * MVP scope is the architectural seam only — concrete capture + analysis methods
 * arrive at Phase 3.
 *
 * Implementations MUST be closeable; consumers should use `.use { }` to release
 * the underlying capture device.
 */
interface MeasurementSession : AutoCloseable {
    override fun close()
}
```

- [ ] **Step 2: Add `enterMeasurementMode` to PlatformPlayer**

Modify `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlatformPlayer.kt`. Below the existing `release()` method, add:

```kotlin
    /**
     * Open a measurement-mode session. MVP returns `null` from both impls
     * (the seam exists; Phase 3 wires capture). Consumers should treat null
     * as "measurement not supported on this platform" and surface gracefully.
     *
     * Per spec §6.1 architectural-seam list — the seam is the MVP commitment;
     * the impl lands at Phase 3 room correction.
     */
    suspend fun enterMeasurementMode(): MeasurementSession?
```

- [ ] **Step 3: Stub the override in Media3ExoPlayerImpl**

Modify `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt`. Inside the class body, near `release()`, add:

```kotlin
    override suspend fun enterMeasurementMode(): MeasurementSession? = null
```

- [ ] **Step 4: Stub the override in JavaSoundPlayerImpl**

Modify `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt`. Inside the class body, near `release()`, add:

```kotlin
    override suspend fun enterMeasurementMode(): MeasurementSession? = null
```

- [ ] **Step 5: Verify build**

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```
Expected: PASS. No test changes needed — the stub returns null in both impls; no behavior to verify.

- [ ] **Step 6: Commit**

```powershell
git add audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/MeasurementSession.kt audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlatformPlayer.kt audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt
git commit -m "feat(playback): stub Phase 3 measurement-mode seam on PlatformPlayer

Review P2-9: spec §6.1 architectural-seam list committed MVP-1.0 to
expose a measurement seam; PlatformPlayer was missing it. Both impls
return null from enterMeasurementMode() — the seam exists, concrete
capture lands at Phase 3 room correction per spec §7.4.

New interface MeasurementSession in commonMain (sealed marker, extends
AutoCloseable). Two no-op overrides in Media3ExoPlayerImpl + Java-
SoundPlayerImpl."
```

### Task 1.5: Add intentional-UPDATE-outside-txn comments in scanners (P3-5)

**Files:**
- Modify: `data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt:86-96`
- Modify: `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt` (same forceFullRescan block; locate around line 55-65)

- [ ] **Step 1: Add comment in JvmFilesystemScanner**

In `JvmFilesystemScanner.kt`, replace the existing `if (forceFullRescan) { ... }` block (around line 86-96) so the comment above the `driver.execute` is explicit:

```kotlin
            if (forceFullRescan) {
                // INTENTIONAL: this UPDATE runs OUTSIDE the loop transaction below.
                // Mid-scan crash leaves last_scanned_ms = 0 for all rows, and the
                // next scan's softDeleteUnscanned(scanStartedMs) only soft-deletes
                // rows that the NEW scan loop did not touch — so the library is
                // recoverable. /ultrareview flagged this in Session 10 and the
                // refute is in docs/sessions/2026-05-19-session-10-addendum-re-review-fixes.md.
                driver.execute(
                    identifier = null,
                    sql = "UPDATE track SET last_scanned_ms = 0",
                    parameters = 0,
                )
            }
```

- [ ] **Step 2: Mirror in AndroidMediaStoreScanner**

In `AndroidMediaStoreScanner.kt`, find the parallel `if (forceFullRescan) { ... }` block (the structure mirrors the desktop scanner). Apply the same comment above the `driver.execute("UPDATE track SET last_scanned_ms = 0", ...)` call.

- [ ] **Step 3: Verify build**

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```
Expected: PASS — comment-only change.

- [ ] **Step 4: Commit**

```powershell
git add data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt
git commit -m "docs(scan): mark INTENTIONAL UPDATE-outside-transaction in forceFullRescan

Review P3-5: /ultrareview flagged the bare driver.execute UPDATE
adjacent to db.transaction in Session 10; the refute (intentional —
mid-scan crash leaves recoverable state) lived only in addendum text.
Inline comment makes the next reader's investigation short."
```

### Task 1.6: Append plan §4 ↔ Session 11 reconciliation addendum (P3-4)

**Files:**
- Modify: `docs/superpowers/plans/2026-05-18-kiln-execution-plan.md` — append at end

- [ ] **Step 1: Add addendum at the bottom of the plan file**

Open the plan file and append (after the existing `End of plan.` line):

```markdown

---

## Addendum 2026-05-21: Phase 2a structure reconciliation (plan §4 ↔ Session 11 handoff)

The session-11 handoff at `docs/sessions/2026-05-21-session-11-handoff.md`
introduced a 6-track framing (A Settings UI, B SAF picker, C Proper UI,
D ReplayGain, E MediaSession, F CI test gate) that does not map 1:1 to
plan §4's 5 flights (A theming, B blurred art, C EQ refinements,
D search, E FFT visualizer). Mapping for the cold reader:

| Session-11 Track | Maps to plan §4 flight | Notes |
|---|---|---|
| **A Settings UI** | partially Flight A theming + new scope | Track A absorbed MVP Sessions 26-28's Settings + acts as gating prereq for Track B and Track C |
| **B SAF folder-picker** | not in plan §4 (Android-specific scan extension) | Surfaced 2026-05-21 from Session 10 H8 Pixel discovery; banked for Phase 2a |
| **C Proper UI** | Flight A theming + Flight B blurred art (subset) | The "Fluid Canvas FFT visualizer" reference in the handoff is the bridge to plan §4 Flight E — actual FFT work still belongs in Flight E |
| **D ReplayGain** | implicit in MVP spec §6.1 (deferred from MVP-1.0 close) | Track D consumer-side work + scanner fix per review P1-1 |
| **E MediaSession** | implicit in MVP spec §6.1 + vetting Item 11 (deferred from MVP-1.0 close) | Media3 instance already constructed; Service binding is the work |
| **F CI test gate** | structural — not in plan §4 | Closes Session 10 U5 structural gap |
| Flight C EQ refinements | NOT in Session-11 track menu | Still applies; lands at MVP Sessions 16-22 (EQ port) per plan §3.2 |
| Flight D search sectioning | NOT in Session-11 track menu | Still applies at later Phase 2a session |
| Flight E FFT visualizer | partially overlaps Track C | The visualizer is the load-bearing part of Flight E |

**Net effect:** Session 11's track menu is a re-shuffling of plan §4
flights with Track B (SAF picker) added and CI gate (Track F) called
out. Plan §4's flights C/D/E still gate v1.1.0-jamz-parity per the
original sequencing. Future sessions that pick tracks should consult
both this addendum and §4 when scoping.

— Addendum authored 2026-05-21 by the pre-Phase-2a-stabilization session.
```

- [ ] **Step 2: Commit**

```powershell
git add docs/superpowers/plans/2026-05-18-kiln-execution-plan.md
git commit -m "docs(plan): reconcile Phase 2a flight (§4) vs Session 11 track framing

Review P3-4: plan §4 prescribes 5 flights (A-E); Session 11 handoff
prescribes 6 tracks (A-F) with different mapping. Cold readers were
seeing one structure in the plan and another in the handoff. Addendum
maps Track→Flight explicitly and notes net effect (B is net-new, F is
structural CI work, C/D/E flights still apply at MVP-internal slots)."
```

### Task 1.7: Update Track A effort estimate in Session 11 handoff (P2-11)

**Files:**
- Modify: `docs/sessions/2026-05-21-session-11-handoff.md` — Track A section

- [ ] **Step 1: Edit Track A effort line**

Find the Track A section. Replace:

```markdown
**Effort:** ~6-10 hrs. Touches `:ui:components`, new Settings table in `:data:library`, plumbing through both AppGraphs.
```

With:

```markdown
**Effort (revised 2026-05-21 post-review):** ~10-16 hrs. Plan §3.2 Sessions 26-28
budgeted 12-20 hrs for "Settings, preferences, polish" — Track A is the
subset minus EQ preset UI. Recommended split:
- A1 (4-6 h): schema migration to user_version 2 + Settings table + repository +
  DI rewire from value-class constructor params to flow-driven providers.
- A2 (6-10 h): Material3 settings screen + folder-picker integration (Android
  SAF or desktop file dialog) + theming toggle + debug logs surface.
Original 6-10 hr estimate was ~½ of plan §3.2's; the gap is the from-zero
UI-component scaffolding cost (first Compose surface in `:ui:components`).
Touches `:ui:components`, new Settings table in `:data:library`, plumbing
through both AppGraphs.
```

- [ ] **Step 2: Commit**

```powershell
git add docs/sessions/2026-05-21-session-11-handoff.md
git commit -m "docs(handoff): revise Track A effort to 10-16h per plan §3.2 alignment

Review P2-11: Track A handoff estimate (6-10h) was ~½ of plan §3.2's
12-20h for the same scope. Split into A1 schema/repo (4-6h) + A2 UI
(6-10h). The from-zero UI scaffolding cost (first :ui:components
surface in the project) is the gap."
```

### Task 1.8: Append tooling-recommendation addendum (review-surfaced gaps)

**Files:**
- Modify: `docs/decisions/2026-05-21-tooling-recommendation.md` — append at end

- [ ] **Step 1: Add the addendum**

Append to the bottom of the tooling-recommendation doc (after the existing kotlin-lsp deferred addendum):

```markdown

---

## Addendum 2026-05-21 (later same day): tooling gaps surfaced by tooling-armed project review

Authored during the holistic project review at
`docs/reviews/2026-05-21-tooling-armed-review.md`. Two skill bugs and
four new tools/skills proposed.

### Skill bug fixes (already addressed in this stabilization sweep)

- **`kiln-verify-build` test counting**: `parse-gradle.ps1` now globs
  `**/build/test-results/desktopTest/TEST-*.xml` across all modules,
  fixing the ~40% undercount (review P2-5).
- **`kiln-flac-golden` CI integration**: when Track F lands the test
  step in `.github/workflows/build.yml`, the golden corpus will run
  via `-Pkiln.golden.corpus=<dir>` (review P2-6).

### Tier-2-bis: promote from Tier 3 (install when Track B or E scheduled)

- **mobile-mcp** v0.0.55 — H7/H8 trigger fired in Session 10; future
  Pixel UI inspection cycles will use it. `claude mcp add mobile-mcp
  -- npx -y @mobilenext/mobile-mcp@latest` with env
  `MOBILEMCP_DISABLE_TELEMETRY=1`.

### Tier 3 additions (skill candidates — author when use case arrives)

- **`kiln-scan-validate` skill**: probe a known-RG-tagged FLAC + diff
  scanner output against `metaflac` / `mediainfo` ground truth. Would
  have caught Track D's 0% RG coverage gap before H8. ~3-4 h author
  effort.
- **`kiln-dep-freshness` skill**: walk `libs.versions.toml`, query
  `gh api repos/<owner>/<repo>/releases?per_page=N` per dependency,
  surface stale pins. Runs in <30 s for ~20 deps. ~2-3 h author effort.
- **androidUnitTest source set + bundled SQLite test fixture** (NOT a
  skill — structural test-infra investment). Session 10 recap §12
  anti-pattern #2 still active. ~6-10 h initial setup. **Addressed by
  Phase 5 of `docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md`.**
```

- [ ] **Step 2: Commit**

```powershell
git add docs/decisions/2026-05-21-tooling-recommendation.md
git commit -m "docs(tooling): append review-surfaced gaps addendum

Review Axis 9: identifies (a) two skill bug fixes (kiln-verify-build
test counting, kiln-flac-golden CI integration), (b) mobile-mcp
promotion from Tier 3 → Tier-2-bis, (c) three new skill/infra
candidates (kiln-scan-validate, kiln-dep-freshness, androidUnitTest
source set). The structural androidUnitTest investment is addressed
by Phase 5 of the pre-Phase-2a stabilization plan."
```

---

## Phase 2 — CI desktopTest gate (Session 11 Track F)

**Goal:** Add `:data:library:desktopTest :audio:playback:desktopTest` to the existing CI workflow, wire the golden-corpus parameter via synthesized fixtures. After this phase, every PR runs the 69 tests + the 5-file golden corpus on a Windows runner.

### Task 2.1: Add desktopTest invocation to CI desktop job

**Files:**
- Modify: `.github/workflows/build.yml` — `desktop` job block

- [ ] **Step 1: Replace the desktop job's build step**

Open `.github/workflows/build.yml`. Locate the `desktop:` job (around lines 38-62). Replace the existing `- name: Build Desktop JAR` step with two steps:

```yaml
      - name: Run desktop tests
        run: ./gradlew :data:library:desktopTest :audio:playback:desktopTest

      - name: Build Desktop JAR
        run: ./gradlew :app-desktop:assemble

      - name: Upload test reports on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: kiln-desktop-test-reports
          path: |
            data/library/build/reports/tests/desktopTest/**
            audio/playback/build/reports/tests/desktopTest/**
          if-no-files-found: ignore
```

- [ ] **Step 2: Add desktopTest invocation to android job (commonTest runs on Android)**

Inside the `android:` job (lines 12-37). Add this step BEFORE the existing `- name: Build APK` step:

```yaml
      - name: Run data:library + audio:playback desktopTest on Ubuntu
        run: ./gradlew :data:library:desktopTest :audio:playback:desktopTest
```

This runs the same JVM test suite on Ubuntu — catches Linux-specific path / encoding regressions (file paths, kermit logging, etc.). Slight redundancy with the Windows job is acceptable; tests are fast.

- [ ] **Step 3: Verify the YAML is valid locally**

If `yamllint` is available:
```powershell
yamllint .github/workflows/build.yml
```
Otherwise: open in any YAML-aware editor and look for indentation or `:` errors.

- [ ] **Step 4: Push the change and watch CI**

```powershell
git add .github/workflows/build.yml
git commit -m "ci(build): run desktopTest on both jobs (Track F warm-up)

Review P3-1 / Session 11 Track F: CI workflow ran only assembleDebug +
assemble. Adds the 69-test desktopTest invocation to both jobs so PRs
fail on test regressions before merge. Uploads test reports on
failure for triage.

Ubuntu job runs the same JVM tests as Windows — slight redundancy
catches Linux-specific path / encoding regressions for free."
git push origin main
```
Watch the resulting Actions run. Both jobs should green with the test step now visible.

- [ ] **Step 5: Verify CI green**

```powershell
gh run list --limit 3
```
Expected: most recent run on main green, both jobs showing the new test step.

### Task 2.2: Wire golden corpus parameter (defer if too costly)

**Files:**
- Modify: `.github/workflows/build.yml` — desktop job
- Inspect: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/flac/GoldenCorpusTest.kt`

- [ ] **Step 1: Read the GoldenCorpusTest to confirm the gating flag**

Open `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/flac/GoldenCorpusTest.kt`. Identify the JUnit `Assume.assumeTrue(...)` or `@DisabledIf` that's gating on the `kiln.golden.corpus` system property.

- [ ] **Step 2: Decide synthesis-on-CI vs commit-corpus**

The `corpus.manifest` already lists 5 ffmpeg-synthesizable test files. Synthesis on CI is the cleanest path (reproducible, no binary commits). Add to the desktop job's `desktopTest` step:

```yaml
      - name: Install ffmpeg + flac (for golden corpus synthesis)
        run: |
          choco install ffmpeg flac -y
        shell: powershell

      - name: Synthesize golden corpus
        run: |
          New-Item -ItemType Directory -Force -Path build/golden-corpus | Out-Null
          # Read corpus.manifest, foreach line, call ffmpeg + flac.exe to synth + ref-pcm
          # (Defer the actual synthesis script to Phase 4 if not already authored — Phase 2
          # can ship the flag wiring and the synthesis script comes in Phase 4.)
        shell: powershell

      - name: Run desktop tests with golden corpus
        run: ./gradlew :data:library:desktopTest :audio:playback:desktopTest -Pkiln.golden.corpus=build/golden-corpus
```

- [ ] **Step 3: If synthesis script doesn't exist yet, defer to Phase 4**

Check if `.claude/skills/kiln-flac-golden/scripts/generate-reference-pcm.ps1` exists per the SKILL.md spec. If yes, invoke it in CI. If no, Phase 2 ships the test-invocation gate WITHOUT the corpus flag; Phase 4 (or a dedicated kiln-flac-golden CI follow-up) wires the corpus.

- [ ] **Step 4: Commit + push if corpus wiring is in scope; otherwise skip Task 2.2**

```powershell
git add .github/workflows/build.yml
git commit -m "ci(golden-corpus): synth corpus on Windows runner + wire -Pkiln.golden.corpus

Review P2-6: GoldenCorpusTest auto-skipped without the system property.
Synthesizes the 5-file corpus via chocolatey-installed ffmpeg + flac.exe
on the Windows runner, passes -Pkiln.golden.corpus to gradle, test now
runs in CI.

Ubuntu job intentionally doesn't run the corpus — flac.exe isn't on
chocolatey for ubuntu-latest; the Windows runner is the canonical
decoder-parity gate."
git push origin main
```

---

## Phase 3 — Dependency hygiene

**Goal:** Bump JNA 5.14.0 → 5.17.0 (P1-4) verified by golden corpus, then pin or constrain Skiko transitive resolution to silence the build warning (P2-1).

### Task 3.1: JNA bump (P1-4)

**Files:**
- Modify: `gradle/libs.versions.toml:54`

- [ ] **Step 1: Edit the JNA version pin**

In `libs.versions.toml`, change line 54 from:

```toml
jna = "5.14.0"                          # JVM ↔ libFLAC bridge; pinned per Item 9 addendum
```

to:

```toml
jna = "5.17.0"                          # JVM ↔ libFLAC bridge; bumped from 5.14.0 (2023-12) to 5.17.0 (2025-03-16) per review P1-4. Verified parity via kiln-flac-golden.
```

- [ ] **Step 2: Run the canonical build**

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```
Expected: PASS, all 5 targets, all 69 tests. The JvmFlacDecoder + LibFlacBinding test suite catches API breakage.

- [ ] **Step 3: Run the golden corpus against Clay's library**

Currently kiln-flac-golden's tiddl-corpus is gated behind opt-in; invoke explicitly. If `kiln-flac-golden`'s tiddl recipe isn't yet authored:

Run the existing FlacDecodeSmokeTest, which empirically decodes 10 of Clay's actual FLACs:

```powershell
./gradlew :audio:playback:desktopTest --tests "com.clayworks.kiln.audio.playback.FlacDecodeSmokeTest" -i
```
Expected: PASS. Recap of Session 10 baseline was 10/10; post-bump should match.

If a regression is observed, **STOP** and rollback the version pin:
```powershell
git diff gradle/libs.versions.toml  # confirm only the version line changed
git checkout gradle/libs.versions.toml
```

- [ ] **Step 4: Commit**

```powershell
git add gradle/libs.versions.toml
git commit -m "deps(jna): bump 5.14.0 → 5.17.0 on libFLAC bridge

Review P1-4: JNA 5.14.0 (2023-12) was 17 months stale. Maven Central
shows 5.17.0 stable (2025-03-16) with callback thread-mapping
improvements relevant to JvmFlacDecodedStream's callback-GC pattern.
Bumped through 5.15.0 → 5.16.0 → 5.17.0 in one step; intermediate
versions verified by 'gh api repos/java-native-access/jna releases'.

Verified: kiln-verify-build PASS (69/69 tests); FlacDecodeSmokeTest
PASS on Clay's tiddl 10-file smoke corpus."
```

### Task 3.2: Constrain Skiko transitive resolution (P2-1)

**Files:**
- Modify: `gradle/libs.versions.toml` (add explicit skiko version pin)
- Modify: `ui/theme/build.gradle.kts` + `ui/components/build.gradle.kts` (add resolutionStrategy constraint)

- [ ] **Step 1: Check current Compose-MP forced Skiko version**

Run:
```powershell
./gradlew :ui:theme:dependencies --configuration desktopRuntimeClasspath | findstr /i skiko
```
Expected output line: `org.jetbrains.skiko:skiko:0.9.22.2 -> 0.144.6` (matches build warning).

Confirm `0.144.6` is Compose-MP 1.11.0's pin.

- [ ] **Step 2: Add explicit skiko version to libs.versions.toml**

In `[versions]` block, add (alphabetically near `compose-runtime`):

```toml
skiko = "0.144.6"  # explicit pin matching Compose-MP 1.11.0; silences the coil 3.4.0 transitive warning per review P2-1. Bump when Compose-MP bumps; verify via :ui:theme:dependencies.
```

In `[libraries]` block, add:

```toml
skiko = { module = "org.jetbrains.skiko:skiko", version.ref = "skiko" }
```

- [ ] **Step 3: Add a constraint in ui/theme/build.gradle.kts**

In `ui/theme/build.gradle.kts`, inside the `kotlin { sourceSets { ... } }` block (or at the bottom of the file outside the kotlin block), add:

```kotlin
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko" && requested.name == "skiko") {
            useVersion(libs.versions.skiko.get())
            because("Pin skiko to Compose-MP's transitive version; review P2-1")
        }
    }
}
```

- [ ] **Step 4: Mirror the constraint in ui/components/build.gradle.kts**

Same block as Step 3.

- [ ] **Step 5: Verify the warning is gone**

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```
Expected: PASS. The two `checkDesktopMainComposeLibrariesCompatibility` tasks no longer print the Skiko mismatch warning.

- [ ] **Step 6: Commit**

```powershell
git add gradle/libs.versions.toml ui/theme/build.gradle.kts ui/components/build.gradle.kts
git commit -m "build(skiko): pin transitive resolution to Compose-MP's 0.144.6

Review P2-1: coil-core-jvm:3.4.0 declares skiko 0.9.22.2 → Compose-MP
1.11.0 force-upgrades to 0.144.6. The transitive jump prints a JetBrains
'may lead to compilation errors or unexpected behavior' warning on every
desktop build. Explicit pin silences the warning.

When Compose-MP next bumps Skiko, verify via :ui:theme:dependencies and
bump libs.versions.toml's skiko entry to match. The 'eachDependency'
resolutionStrategy in :ui:theme + :ui:components is the smallest-blast-
radius location for the constraint."
```

---

## Phase 4 — ReplayGain probe + scanner fix (P1-1)

**Goal:** Diagnose why JvmFilesystemScanner.kt:370-373's `getFreeFormOrNull("REPLAYGAIN_TRACK_GAIN")` returns null for all of Clay's 27,766 tracks, fix the scanner, re-scan, verify >0 rows have ReplayGain populated. This is the load-bearing investigation — Track D's 4-6h estimate depends on it.

### Task 4.1: Probe known-tagged FLAC ground truth

**Files:** None — operational investigation.

- [ ] **Step 1: Pick a known-RG-tagged FLAC from Clay's library**

Run via Bash or PowerShell:
```powershell
# Find a FLAC in D:\tiddl that's likely tagged (Clay's normal mastering)
ls D:\tiddl -Recurse -Filter "*.flac" | Select-Object -First 1 | ForEach-Object { $_.FullName }
```
Save the path as `$known_flac` (e.g., `D:\tiddl\Some Artist\Some Album\01 Some Track.flac`).

- [ ] **Step 2: Inspect with metaflac**

If metaflac is on PATH (ships with `winget install Xiph.FLAC` per Tier 3):
```powershell
metaflac --list "$known_flac" | findstr /i replaygain
```
Expected if RG present:
```
    comment[X]: REPLAYGAIN_TRACK_GAIN=-6.42 dB
    comment[X]: REPLAYGAIN_ALBUM_GAIN=-6.10 dB
    comment[X]: REPLAYGAIN_TRACK_PEAK=0.987541
    comment[X]: REPLAYGAIN_ALBUM_PEAK=0.987541
```
Expected if RG absent: no matching lines.

- [ ] **Step 3: Branch on probe result**

**Branch A: Tags present in metaflac output → jaudiotagger lookup is wrong.** Proceed to Task 4.2 (Branch A path).

**Branch B: Tags absent → Clay's library doesn't carry ReplayGain.** STOP this phase. Surface to Clay: Track D scope changes from "consumer-side gain application" to "scanner-side ReplayGain analysis as a Kiln feature." That's a 30-60 h R&D add per Track D, not 4-6 h. The Phase-2a-stabilization plan does NOT cover this scope — escalate to Clay for decision before continuing.

- [ ] **Step 4: Record the probe result in engram**

Save via engram:
- title: `Kiln ReplayGain probe result 2026-05-21`
- topic_key: `kiln/replaygain-probe-2026-05-21`
- type: `discovery`
- content: which branch (A or B), the file probed, the metaflac output (sanitize Clay's filenames if you want).

### Task 4.2: jaudiotagger reproducer (only if Branch A above)

**Files:**
- Create: `data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/ReplayGainExtractionProbeTest.kt` — temporary investigation test

- [ ] **Step 1: Write a one-off probe test**

Create the file with content:

```kotlin
// Investigation test for review P1-1. Probes how jaudiotagger exposes ReplayGain
// tags on a known-tagged FLAC. NOT for permanent merge — delete after Phase 4.
//
// Run: ./gradlew :data:library:desktopTest --tests "*ReplayGainExtractionProbeTest" -i
// Test path expects -Dkiln.probe.flac=<absolute path to a known-tagged FLAC>.

package com.clayworks.kiln.library.scan

import kotlin.test.Test
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import java.io.File

class ReplayGainExtractionProbeTest {

    @Test
    fun probe_jaudiotagger_replaygain_accessors() {
        val pathProp = System.getProperty("kiln.probe.flac")
            ?: error("Set -Dkiln.probe.flac=<abs path to known-RG-tagged FLAC>")
        val file = File(pathProp).also { check(it.exists()) { "Not found: $pathProp" } }
        val tag = AudioFileIO.read(file).tag
        println("\n--- jaudiotagger Tag class: ${tag::class.qualifiedName} ---")

        // Accessor 1: getFirst(FieldKey.RATING) — no, this would be Rating not RG
        // Accessor 2: getFreeFormOrNull (current scanner uses this — see JvmFilesystemScanner.kt:370-373)
        println("getFirst('REPLAYGAIN_TRACK_GAIN'): '${runCatching { tag.getFirst("REPLAYGAIN_TRACK_GAIN") }.getOrElse { it.message }}'")
        println("getFirst('REPLAYGAIN_ALBUM_GAIN'): '${runCatching { tag.getFirst("REPLAYGAIN_ALBUM_GAIN") }.getOrElse { it.message }}'")
        println("getFirst('REPLAYGAIN_TRACK_PEAK'): '${runCatching { tag.getFirst("REPLAYGAIN_TRACK_PEAK") }.getOrElse { it.message }}'")

        // Accessor 3: lowercase
        println("getFirst('replaygain_track_gain'): '${runCatching { tag.getFirst("replaygain_track_gain") }.getOrElse { it.message }}'")

        // Accessor 4: VorbisCommentTag direct access if FlacTag wraps one
        if (tag is FlacTag) {
            val vorbis = tag.vorbisCommentTag
            println("FlacTag.vorbisCommentTag class: ${vorbis::class.qualifiedName}")
            println("vorbis.getFirst('REPLAYGAIN_TRACK_GAIN'): '${runCatching { vorbis.getFirst("REPLAYGAIN_TRACK_GAIN") }.getOrElse { it.message }}'")
            println("vorbis.getFirst('REPLAYGAIN_ALBUM_GAIN'): '${runCatching { vorbis.getFirst("REPLAYGAIN_ALBUM_GAIN") }.getOrElse { it.message }}'")
            println("vorbis.getFields('REPLAYGAIN_TRACK_GAIN'): '${runCatching { vorbis.getFields("REPLAYGAIN_TRACK_GAIN") }.getOrElse { it.message }}'")
        }

        // Accessor 5: enumerate ALL fields in the tag to see how RG appears
        println("--- all fields ---")
        tag.fields.forEach { field ->
            println("field id='${field.id}' value='${runCatching { field.toString() }.getOrNull()?.take(60)}'")
        }
    }
}
```

- [ ] **Step 2: Run the probe**

```powershell
./gradlew :data:library:desktopTest --tests "*ReplayGainExtractionProbeTest" `
    -Dkiln.probe.flac="D:\tiddl\<artist>\<album>\<track>.flac" -i
```
Read the stdout. The accessor that returns the real "-6.42" value (vs an empty string or exception) is the one the scanner should use.

- [ ] **Step 3: Document the working accessor in engram**

Save via engram:
- topic_key: `kiln/replaygain-jaudiotagger-accessor`
- type: `discovery`
- content: name the working accessor + the exact tag class chain (FlacTag.vorbisCommentTag.getFirst("REPLAYGAIN_TRACK_GAIN")? VorbisCommentTag.getFields()[0].content?).

### Task 4.3: Fix the scanner extraction

**Files:**
- Modify: `data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt:370-373` and the `getFreeFormOrNull` helper (~line 395)

- [ ] **Step 1: Patch the extraction call**

Based on the Task 4.2 probe result, replace the lookup. Most likely candidate (if FlacTag-wrapping-VorbisCommentTag): change the helper from:

```kotlin
private fun Tag.getFreeFormOrNull(key: String): String? =
    runCatching { getFirst(key) }.getOrNull()?.takeIf { it.isNotBlank() }
```

to a tag-class-aware version:

```kotlin
private fun Tag.getReplayGainOrNull(key: String): String? {
    // Try VorbisComment direct access first (FlacTag wraps a VorbisCommentTag;
    // ID3v2 routes through TXXX:replaygain_* via getFirst). Investigation per
    // review P1-1.
    val direct = runCatching {
        when (this) {
            is org.jaudiotagger.tag.flac.FlacTag ->
                vorbisCommentTag.getFirst(key)
            else ->
                getFirst(key)
        }
    }.getOrNull()
    return direct?.takeIf { it.isNotBlank() }
}
```

Update lines 370-373 to call the new helper:

```kotlin
replayGainTrackDb = tag?.getReplayGainOrNull("REPLAYGAIN_TRACK_GAIN")?.parseReplayGainDb(),
replayGainAlbumDb = tag?.getReplayGainOrNull("REPLAYGAIN_ALBUM_GAIN")?.parseReplayGainDb(),
replayGainTrackPeak = tag?.getReplayGainOrNull("REPLAYGAIN_TRACK_PEAK")?.toDoubleOrNull(),
replayGainAlbumPeak = tag?.getReplayGainOrNull("REPLAYGAIN_ALBUM_PEAK")?.toDoubleOrNull(),
```

(If the Task 4.2 probe revealed a different working accessor — e.g., `tag.getFields(key).firstOrNull()?.toString()` — adapt the body accordingly.)

- [ ] **Step 2: Add a regression test against a fixture FLAC**

In `data/library/src/desktopTest/resources/fixtures/` (create if missing), commit a tiny ffmpeg-synthesized FLAC with embedded ReplayGain tags. Recipe (run once locally; output ~2 KB; safe to commit):

```powershell
$out = "data/library/src/desktopTest/resources/fixtures/rg-tagged-3s.flac"
ffmpeg -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=3" `
    -metadata "REPLAYGAIN_TRACK_GAIN=-6.42 dB" `
    -metadata "REPLAYGAIN_ALBUM_GAIN=-6.10 dB" `
    -metadata "REPLAYGAIN_TRACK_PEAK=0.987541" `
    -metadata "REPLAYGAIN_ALBUM_PEAK=0.987541" `
    -sample_fmt s16 -ar 44100 -c:a flac -y $out
```

Add to `JvmFilesystemScannerTest.kt`:

```kotlin
@Test
fun extractsReplayGainFromFlacFixture() {
    // Verifies the fix for review P1-1 (jaudiotagger lookup gap).
    val fixture = File("src/desktopTest/resources/fixtures/rg-tagged-3s.flac")
    check(fixture.exists()) { "Synthesize the fixture per the recipe in the plan doc Phase 4 Task 4.3." }

    val tmpDb = createTempDb()  // existing test helper
    val scanner = JvmFilesystemScanner(
        scanFolders = listOf(fixture.parentFile.toPath()),
        db = tmpDb.db,
        driver = tmpDb.driver,
        ioDispatcher = kotlinx.coroutines.Dispatchers.Default,
    )

    runBlocking { scanner.scanIncremental() }

    val track = tmpDb.db.trackQueries.selectAll(1, 0).executeAsOne()
    assertEquals(-6.42, track.replay_gain_track_db!!, 0.01)
    assertEquals(-6.10, track.replay_gain_album_db!!, 0.01)
    assertEquals(0.987541, track.replay_gain_track_peak!!, 0.001)
    assertEquals(0.987541, track.replay_gain_album_peak!!, 0.001)
}
```

- [ ] **Step 3: Run the new test → fail → fix → pass**

```powershell
./gradlew :data:library:desktopTest --tests "*JvmFilesystemScannerTest.extractsReplayGainFromFlacFixture"
```
Expected on first run: PASS (the scanner fix from Step 1 plus the synthesized fixture should make it pass). If it fails, re-examine the probe result — likely a different accessor is needed.

- [ ] **Step 4: Run the full canonical build**

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```
Expected: PASS.

- [ ] **Step 5: Delete the temporary probe test**

```powershell
git rm data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/ReplayGainExtractionProbeTest.kt
```

- [ ] **Step 6: Commit**

```powershell
git add data/library/src/desktopMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt data/library/src/desktopTest/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScannerTest.kt data/library/src/desktopTest/resources/fixtures/rg-tagged-3s.flac
git commit -m "fix(scan): extract ReplayGain via tag-class-aware accessor

Review P1-1: scanner's getFreeFormOrNull('REPLAYGAIN_TRACK_GAIN') returned
null for all 27,766 of Clay's FLACs. Probe (see engram topic
kiln/replaygain-jaudiotagger-accessor) revealed jaudiotagger's FlacTag
wraps a VorbisCommentTag whose getFirst() reads the bare key correctly;
the outer FlacTag's getFirst() does NOT route to it for these keys.

New helper getReplayGainOrNull dispatches on tag class. Regression test
extractsReplayGainFromFlacFixture pins the contract against an ffmpeg-
synthesized 3-second tagged FLAC committed under desktopTest/resources/
fixtures/."
```

### Task 4.4: Trigger full library re-scan + verify coverage

**Files:** None — operational.

- [ ] **Step 1: Force a full re-scan on Desktop**

Easiest path: run the Desktop app's existing scan button via `./gradlew :app-desktop:run`. But the current Main.kt only exposes `scanIncremental()`, which won't re-read jaudiotagger for tracks where `file_mtime` is unchanged.

Force re-extraction option A (preferred): one-shot reset via the MCP, then re-scan:
```sql
UPDATE track SET last_scanned_ms = 0 WHERE deleted_at_ms IS NULL;
```
Then tap Scan in the desktop app — the scanner sees `file_mtime > last_scanned_ms` for every row and re-extracts.

Force re-extraction option B (cleaner code path): add a one-line `scanFull()` invocation alongside `scanIncremental()` in Main.kt — but that's UI work that belongs in Track A, not this phase. Stick with Option A.

- [ ] **Step 2: Run the app + tap Scan**

```powershell
./gradlew :app-desktop:run
```
Click "Scan Library." Wait for the scan to complete (~10 minutes for 27 k tracks on a hot daemon).

- [ ] **Step 3: Verify ReplayGain coverage via the MCP**

```sql
SELECT
  COUNT(*) AS live_tracks,
  SUM(CASE WHEN replay_gain_track_db IS NOT NULL THEN 1 ELSE 0 END) AS rg_track_db_set,
  SUM(CASE WHEN replay_gain_album_db IS NOT NULL THEN 1 ELSE 0 END) AS rg_album_db_set,
  SUM(CASE WHEN replay_gain_track_peak IS NOT NULL THEN 1 ELSE 0 END) AS rg_track_peak_set,
  SUM(CASE WHEN replay_gain_album_peak IS NOT NULL THEN 1 ELSE 0 END) AS rg_album_peak_set
FROM track WHERE deleted_at_ms IS NULL;
```
Expected: `rg_track_db_set >> 0`. The exact percentage depends on Clay's library coverage; somewhere in 80-99% is realistic for a well-curated library.

If `rg_*_set == 0`, the scanner fix in Task 4.3 didn't actually fix the gap. Re-investigate via the probe.

- [ ] **Step 4: Save coverage outcome to engram**

Topic key: `kiln/replaygain-coverage-post-fix-2026-05-21`. Include the live %, which informs Track D's actual scope (consumer-side application of gain).

---

## Phase 5 — androidUnitTest source set infrastructure (P1-3)

**Goal:** Add host-side `androidUnitTest` source sets to `:data:library`, `:audio:playback`, and `:app-android`. Wire `RequerySQLiteOpenHelperFactory` (already used in production for Fix J) so tests run against a real-feature SQLite. This is the structural prereq for Phases 7 + 8.

### Task 5.1: Investigate AGP 9 KMP androidUnitTest syntax

**Files:** None — investigation.

- [ ] **Step 1: Confirm AGP 9 KMP supports androidUnitTest**

The Android Kotlin Multiplatform Library plugin (`com.android.kotlin.multiplatform.library`) uses different test source set conventions than the legacy `com.android.library` plugin. Check the Slack `circuit` repo's libs.versions.toml + module build files for how they wire androidUnitTest under KMP — it's the most up-to-date reference per CLAUDE.md tool-priorities #4.

```powershell
gh api repos/slackhq/circuit/contents/gradle/libs.versions.toml --jq '.content' | python -c "import base64,sys; print(base64.b64decode(sys.stdin.read()).decode())" | findstr /i androidUnit
```

If the Circuit repo has an `androidUnitTest` dependency declaration in any of its modules, use that same convention.

- [ ] **Step 2: Probe the build-logic convention plugin**

Open `build-logic/src/main/kotlin/kiln.kmp.library.gradle.kts` (re-read from review). The current block configures `kotlin { jvmToolchain(21); androidLibrary { ... }; jvm("desktop") }`. The androidUnitTest sourceSet in KMP land is typically `kotlin { sourceSets { val androidUnitTest by getting { dependencies { ... } } } }` — verify by experiment.

### Task 5.2: Add androidUnitTest to :data:library

**Files:**
- Modify: `data/library/build.gradle.kts`
- Create: `data/library/src/androidUnitTest/kotlin/com/clayworks/kiln/library/SmokeAndroidUnitTest.kt`

- [ ] **Step 1: Extend data/library/build.gradle.kts**

Add to the `kotlin { sourceSets { ... } }` block:

```kotlin
        androidUnitTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.requery.sqlite.android)   // bundled SQLite per Fix J
            implementation(libs.sqldelight.android.driver)
        }
```

- [ ] **Step 2: Write a smoke test verifying the source set works**

Create `data/library/src/androidUnitTest/kotlin/com/clayworks/kiln/library/SmokeAndroidUnitTest.kt`:

```kotlin
// Verifies the androidUnitTest source set is wired and can construct the
// bundled-SQLite test stack used by future Android-side test classes.
// Closes review P1-3.

package com.clayworks.kiln.library

import androidx.test.core.app.ApplicationProvider
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.clayworks.kiln.data.library.db.KilnDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.junit.Test
import kotlin.test.assertNotNull

class SmokeAndroidUnitTest {
    @Test
    fun bundledSqliteSchemaCreatesCleanly() {
        // Robolectric ApplicationProvider gives us a real Android Context on host JVM.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val driver = AndroidSqliteDriver(
            schema = KilnDatabase.Schema,
            context = context,
            name = null,                                              // in-memory
            factory = RequerySQLiteOpenHelperFactory(),                // bundled SQLite (FTS5 guaranteed)
        )
        val db = KilnDatabase(driver)
        assertNotNull(db)
        driver.close()
    }
}
```

- [ ] **Step 3: Wire Robolectric in libs.versions.toml**

Add to `[versions]`:
```toml
robolectric = "4.14.1"
```
Add to `[libraries]`:
```toml
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
```
Add `implementation(libs.robolectric)` to the androidUnitTest dependencies block from Step 1.

The smoke test additionally needs `@RunWith(RobolectricTestRunner::class)`. Update:
```kotlin
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmokeAndroidUnitTest { ... }
```

- [ ] **Step 4: Run the smoke test**

```powershell
./gradlew :data:library:testDebugUnitTest
```
Expected: PASS. (The Android-KMP plugin maps `androidUnitTest` to the standard `testDebugUnitTest` Gradle task.)

If it fails with "Schema is missing fts5 module," the bundled-SQLite factory isn't applied at test time — re-check the driver constructor.

- [ ] **Step 5: Commit**

```powershell
git add gradle/libs.versions.toml data/library/build.gradle.kts data/library/src/androidUnitTest/kotlin/com/clayworks/kiln/library/SmokeAndroidUnitTest.kt
git commit -m "test(infra): add androidUnitTest source set to :data:library

Review P1-3: closes Session 10 anti-pattern #2 ('do NOT defer
androidTest source set past Phase 2a kickoff'). androidUnitTest is the
host-side JVM variant; runs under Robolectric with bundled SQLite via
RequerySQLiteOpenHelperFactory (same factory production uses per Fix J).

Smoke test verifies the KilnDatabase schema (including FTS5) creates
cleanly on a Robolectric Context with bundled SQLite — the exact gap
that caused the Pixel FTS5 surprise to go latent for 5 sessions."
```

### Task 5.3: Mirror in :audio:playback + :app-android

**Files:**
- Modify: `audio/playback/build.gradle.kts`
- Modify: `app-android/build.gradle.kts`
- Create: smoke tests in each

- [ ] **Step 1: Apply the same androidUnitTest block to audio/playback/build.gradle.kts**

Mirror Task 5.2 Step 1 in `audio/playback/build.gradle.kts`. Dependencies: `libs.kotlin.test`, `libs.kotlinx.coroutines.test`, `libs.robolectric`.

- [ ] **Step 2: Add a smoke test in :audio:playback's androidUnitTest**

Create `audio/playback/src/androidUnitTest/kotlin/com/clayworks/kiln/audio/playback/SmokeAndroidUnitTest.kt`:

```kotlin
package com.clayworks.kiln.audio.playback

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class SmokeAndroidUnitTest {
    @Test
    fun media3ExoPlayer_constructs_on_robolectric_main_thread() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = object : com.clayworks.kiln.library.source.MusicSource { /* minimal fake — see Task 7 */ }
        val player = Media3ExoPlayerImpl(context, source)
        assertNotNull(player)
        kotlinx.coroutines.runBlocking { player.release() }
    }
}
```

(If the minimal fake gets verbose, defer to Phase 7 — the smoke test for :audio:playback can be simpler: just instantiate a `RobolectricTestRunner` test that runs `assertTrue(true)` to prove the source set is wired.)

- [ ] **Step 3: Apply to :app-android**

`app-android/build.gradle.kts` uses `com.android.application`, not the KMP library plugin. The androidUnitTest source set lives at `src/test/kotlin/` (legacy convention) and dependencies go in `dependencies { testImplementation(libs.kotlin.test); testImplementation(libs.robolectric); testImplementation(libs.requery.sqlite.android) }`.

Add a smoke test at `app-android/src/test/kotlin/com/clayworks/kiln/KilnApplicationSmokeTest.kt`:

```kotlin
package com.clayworks.kiln

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class KilnApplicationSmokeTest {
    @Test
    fun application_class_loads() {
        val app = ApplicationProvider.getApplicationContext<KilnApplication>()
        assertNotNull(app)
    }
}
```

- [ ] **Step 4: Run all three modules' androidUnitTests**

```powershell
./gradlew :data:library:testDebugUnitTest :audio:playback:testDebugUnitTest :app-android:testDebugUnitTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add audio/playback/build.gradle.kts audio/playback/src/androidUnitTest app-android/build.gradle.kts app-android/src/test
git commit -m "test(infra): mirror androidUnitTest source set into :audio:playback + :app-android

Review P1-3: completes the structural fix from the :data:library
addition. Each module now has a Robolectric-runner-based host-side test
surface. Smoke tests in each confirm the source set is wired before
real test classes (Phases 7 + 8) start landing."
```

### Task 5.4: Add androidUnitTest invocation to CI

**Files:**
- Modify: `.github/workflows/build.yml` — android job

- [ ] **Step 1: Add the new gradle task to the android job**

In the `android:` job (`.github/workflows/build.yml`), modify the existing "Run … desktopTest" step (added in Phase 2) to also run androidUnitTest:

```yaml
      - name: Run JVM tests (desktop + android-host)
        run: ./gradlew :data:library:desktopTest :audio:playback:desktopTest :data:library:testDebugUnitTest :audio:playback:testDebugUnitTest :app-android:testDebugUnitTest
```

- [ ] **Step 2: Push + watch CI**

```powershell
git add .github/workflows/build.yml
git commit -m "ci(android-host): run androidUnitTest in CI

Review P1-3 follow-up: the Robolectric source sets from Phase 5 only
earn their keep if CI runs them. Adds the testDebugUnitTest tasks for
the three modules' androidUnitTest source sets."
git push origin main
```

---

## Phase 6 — LocalLibrarySource tests (P1-6)

**Goal:** Cover all 11 BrowseScope branches + search + getPlayable in commonTest, against an in-memory JdbcSqliteDriver. Independent of Phase 5 — runs on Desktop / commonTest.

### Task 6.1: Test infrastructure helper

**Files:**
- Create: `data/library/src/commonTest/kotlin/com/clayworks/kiln/library/source/TestDb.kt`

- [ ] **Step 1: Author the test helper**

```kotlin
// Test fixture: in-memory KilnDatabase via JdbcSqliteDriver. Used by
// LocalLibrarySourceTest (review P1-6). Each test gets a clean DB.

package com.clayworks.kiln.library.source

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.clayworks.kiln.data.library.db.KilnDatabase
import kotlinx.coroutines.test.TestScope

class TestDb : AutoCloseable {
    val driver: SqlDriver = JdbcSqliteDriver(
        url = JdbcSqliteDriver.IN_MEMORY,
        schema = KilnDatabase.Schema,   // SQLDelight 2.x auto-creates on connect
    )
    val db: KilnDatabase = KilnDatabase(driver)

    override fun close() = driver.close()

    fun insertArtist(name: String, sortName: String = name.lowercase()): Long {
        db.artistQueries.insert(
            name = name,
            name_sort = sortName,
            musicbrainz_artist_id = null,
        )
        return db.artistQueries.selectLastInsert().executeAsOne()
    }

    fun insertAlbum(artistId: Long, name: String, year: Long? = null): Long {
        db.albumQueries.insert(
            artist_id = artistId,
            name = name,
            name_sort = name.lowercase(),
            year = year,
            // ... other defaults
        )
        return db.albumQueries.selectLastInsert().executeAsOne()
    }

    fun insertTrack(
        artistId: Long,
        albumId: Long?,
        title: String,
        durationMs: Long = 180_000L,
        filePath: String = "C:\\test\\${title.lowercase()}.flac",
    ): Long {
        // Map to track.sq's insert query — pattern from JvmFilesystemScanner.kt's upsert path.
        val nowMs = 1700000000000L
        db.trackQueries.insert(
            album_id = albumId,
            artist_id = artistId,
            title = title,
            title_sort = title.lowercase(),
            duration_ms = durationMs,
            track_number = null, disc_number = null,
            year = null, date = null,
            genre = null, composer = null, bpm = null,
            codec = "FLAC",
            bitrate_kbps = null,
            sample_rate_hz = 44100L,
            bit_depth = 16L,
            channels = 2L,
            file_path = filePath,
            file_size_bytes = 1_000_000L,
            file_mtime_ms = nowMs - 100,
            replay_gain_track_db = null, replay_gain_album_db = null,
            replay_gain_track_peak = null, replay_gain_album_peak = null,
            has_embedded_art = 0L,
            art_path = null,
            source = "local",
            date_added_ms = nowMs,
            date_modified_ms = nowMs,
            last_scanned_ms = nowMs,
        )
        return db.trackQueries.selectLastInsert().executeAsOne()
    }
}
```

(Adjust insert signatures to match the actual generated `.insert(...)` for each table — read the SQLDelight-generated `TrackQueries.kt`, `AlbumQueries.kt`, `ArtistQueries.kt` in `data/library/build/generated/sqldelight/...` to confirm the parameter order.)

- [ ] **Step 2: Verify the helper builds**

```powershell
./gradlew :data:library:compileTestKotlinDesktop
```
Expected: PASS. If compilation fails (parameter mismatch), adjust insert signatures to match the generated queries.

- [ ] **Step 3: Commit**

```powershell
git add data/library/src/commonTest/kotlin/com/clayworks/kiln/library/source/TestDb.kt
git commit -m "test(library): in-memory TestDb helper for LocalLibrarySource tests

Review P1-6 prep: shared fixture for the upcoming test class. Wraps
JdbcSqliteDriver(IN_MEMORY, schema=KilnDatabase.Schema), exposes typed
insertArtist/insertAlbum/insertTrack helpers backed by the SQLDelight-
generated query interfaces. Closes resource on close()."
```

### Task 6.2-6.16: LocalLibrarySourceTest — 11 BrowseScope branches + search + getPlayable

**Files:**
- Create: `data/library/src/commonTest/kotlin/com/clayworks/kiln/library/source/LocalLibrarySourceTest.kt`

Each task below is one test + assertion + commit. They follow the same pattern: insert fixture data → call browse/search/getPlayable → assert. Pattern shown once in Task 6.2; subsequent tasks reference the pattern.

#### Task 6.2: `browse(AllTracks)` returns inserted tracks

- [ ] **Step 1: Add the test**

```kotlin
// data/library/src/commonTest/kotlin/com/clayworks/kiln/library/source/LocalLibrarySourceTest.kt

package com.clayworks.kiln.library.source

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalLibrarySourceTest {
    private val testDb = TestDb()
    private val source = LocalLibrarySource(testDb.db, kotlinx.coroutines.Dispatchers.Unconfined)

    @AfterTest fun tearDown() = testDb.close()

    @Test
    fun browse_AllTracks_returnsInsertedTracks() = runTest {
        val artistId = testDb.insertArtist("Pink Floyd", "pink floyd")
        val albumId = testDb.insertAlbum(artistId, "The Wall", year = 1979)
        testDb.insertTrack(artistId, albumId, "Comfortably Numb", filePath = "C:\\wall\\01.flac")
        testDb.insertTrack(artistId, albumId, "Another Brick in the Wall", filePath = "C:\\wall\\02.flac")

        val items = source.browse(BrowseScope.AllTracks()).toList()

        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Track })
    }
}
```

- [ ] **Step 2: Run + commit**

```powershell
./gradlew :data:library:desktopTest --tests "*LocalLibrarySourceTest.browse_AllTracks_returnsInsertedTracks"
git add data/library/src/commonTest/kotlin/com/clayworks/kiln/library/source/LocalLibrarySourceTest.kt
git commit -m "test(library): LocalLibrarySource.browse(AllTracks) — first BrowseScope branch"
```

#### Tasks 6.3 - 6.12: Remaining 10 BrowseScope branches

Each: add a `@Test fun browse_<Scope>_<expected>()` method following the same insert-then-browse-then-assert pattern. Commit each separately (`one change per commit` per CLAUDE.md).

- [ ] **Task 6.3**: `browse(AllAlbums)` — assert returns 1 album for the inserted-1-album fixture, kind == Album
- [ ] **Task 6.4**: `browse(AllArtists)` — assert returns 1 artist, kind == Artist
- [ ] **Task 6.5**: `browse(AllPlaylists)` — assert empty when no playlists inserted; assert returns 1 after inserting one
- [ ] **Task 6.6**: `browse(TracksOfAlbum)` — insert 2 tracks under one album, 1 track under another; assert browse returns just the 2
- [ ] **Task 6.7**: `browse(TracksOfArtist)` — insert tracks across 2 artists; assert filter
- [ ] **Task 6.8**: `browse(AlbumsOfArtist)` — insert albums for 2 artists; assert filter, kind == Album
- [ ] **Task 6.9**: `browse(TracksOfPlaylist)` — insert a playlist with 3 tracks in known order; assert returns in playlist order
- [ ] **Task 6.10**: `browse(RecentlyAdded)` — insert tracks with descending date_added_ms; assert ordering
- [ ] **Task 6.11**: `browse(RecentlyPlayed)` — insert tracks with last_played_ms set on 2 of 4; assert only those 2 return + ordered
- [ ] **Task 6.12**: `browse(MostPlayed)` — insert tracks with descending play_count; assert ordering

#### Task 6.13: `search(query)` — basic + sanitize

- [ ] **Step 1: Test happy path**

```kotlin
@Test
fun search_findsTrackByTitle() = runTest {
    val artistId = testDb.insertArtist("Foo")
    val albumId = testDb.insertAlbum(artistId, "Bar")
    testDb.insertTrack(artistId, albumId, "Specific Title Here")
    // FTS index is populated lazily — the scanner rebuilds at scan end;
    // for tests, manually populate via track_searchQueries.insertSearchIndex:
    testDb.db.track_searchQueries.insertSearchIndex(
        rowid = 1L, title = "Specific Title Here",
        album_name = "Bar", artist_name = "Foo", album_artist_name = "Foo",
    )

    val results = source.search("Specific Title").toList()

    assertEquals(1, results.size)
    assertEquals("Specific Title Here", results.first().item.title)
}
```

- [ ] **Step 2: Test FTS5-special-char sanitize**

```kotlin
@Test
fun search_sanitizesFts5SpecialCharacters() = runTest {
    // FTS5 MATCH parses double-quotes, asterisks, etc.; sanitizeFtsQuery wraps the
    // term and prevents syntax errors. Without sanitization, a user typing
    // `let's go` would crash with FTS syntax error.
    val artistId = testDb.insertArtist("Foo")
    testDb.insertTrack(artistId, null, "Let's Go Crazy")
    testDb.db.track_searchQueries.insertSearchIndex(
        rowid = 1L, title = "Let's Go Crazy",
        album_name = "", artist_name = "Foo", album_artist_name = "Foo",
    )

    // Must not throw
    val results = source.search("let's").toList()
    assertEquals(1, results.size)
}
```

#### Task 6.14: `getPlayable()` — track found → Right

```kotlin
@Test
fun getPlayable_returnsRight_forValidTrackId() = runTest {
    val artistId = testDb.insertArtist("Foo")
    val trackId = testDb.insertTrack(artistId, null, "Track")

    val result = source.getPlayable(ItemId(trackId.toString()))

    assertTrue(result is Either.Right)
    assertEquals(trackId.toString(), result.value.itemId.value)
}
```

#### Task 6.15: `getPlayable()` — container ItemId → Left(ItemNotFound)

```kotlin
@Test
fun getPlayable_returnsLeftItemNotFound_forAlbumNamespaceId() = runTest {
    val artistId = testDb.insertArtist("Foo")
    val albumId = testDb.insertAlbum(artistId, "Bar")

    val result = source.getPlayable(ItemId("album:$albumId"))

    assertTrue(result is Either.Left)
    assertTrue(result.value is SourceError.ItemNotFound)
}
```

#### Task 6.16: `getPlayable()` — invalid ID → Left

```kotlin
@Test
fun getPlayable_returnsLeftItemNotFound_forUnknownTrackId() = runTest {
    val result = source.getPlayable(ItemId("99999"))   // not inserted
    assertTrue(result is Either.Left)
    assertTrue(result.value is SourceError.ItemNotFound)
}
```

#### Task 6.17: Run + commit + verify CI

- [ ] **Step 1: Run the full test class**

```powershell
./gradlew :data:library:desktopTest --tests "*LocalLibrarySourceTest"
```
Expected: 14 passes (counting all Tasks 6.2 through 6.16's tests).

- [ ] **Step 2: Run canonical build**

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```
Expected: PASS, new total 83+ tests (was 69 + 14 LocalLibrarySource cases).

- [ ] **Step 3: Push**

```powershell
git push origin main
```

---

## Phase 7 — Media3ExoPlayerImpl tests (P1-5)

**Goal:** Port JavaSoundPlayerImplTest's 12 cases to Media3ExoPlayerImpl under the androidUnitTest source set. Requires Phase 5 complete.

### Task 7.1: Read JavaSoundPlayerImplTest and map test cases

**Files:** read-only.

- [ ] **Step 1: Catalog the 12 existing test cases**

Open `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImplTest.kt`. List each `@Test fun foo()` name. The 12 cases map roughly to:
1. initial state is Idle, queue empty
2. loadQueue with autoPlay=true → state Ready(isPlaying=true)
3. loadQueue with autoPlay=false → state Ready(isPlaying=false)
4. loadQueue with startIndex past-end → falls back to last surviving
5. loadQueue with startIndex referencing failed-to-resolve item → falls forward
6. play() flips state to playing
7. pause() flips state to paused
8. seekTo() updates positionMs
9. setMuted(true) preserves linear, mutes output
10. setMuted(false) restores
11. release() makes subsequent methods no-op
12. skipToNext / skipToPrevious behave per RepeatMode

(Exact names will differ — copy from the file.)

### Task 7.2-7.13: Port each test case (12 tasks)

**Files:**
- Create: `audio/playback/src/androidUnitTest/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImplTest.kt`

Pattern (shown for Task 7.2 — initial state):

```kotlin
// Mirrors JavaSoundPlayerImplTest under androidUnitTest. Uses Robolectric for
// the ExoPlayer.Builder(context) construction. Closes review P1-5.

package com.clayworks.kiln.audio.playback

import androidx.test.core.app.ApplicationProvider
import com.clayworks.kiln.library.source.LocalSourceCapabilities
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.SourceCapabilities
// ... other imports

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class Media3ExoPlayerImplTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fakeSource = FakeMusicSource()
    private lateinit var player: Media3ExoPlayerImpl

    @Before fun setUp() {
        player = Media3ExoPlayerImpl(context, fakeSource)
    }
    @After fun tearDown() = runTest { player.release() }

    @Test
    fun initialState_isIdle_withEmptyQueue() {
        assertEquals(PlayerState.Idle, player.state.value)
        assertEquals(0, player.queue.value.items.size)
    }

    // ... other 11 tests, mirroring JavaSoundPlayerImplTest one-by-one

    private class FakeMusicSource(
        // copy the existing fake from JavaSoundPlayerImplTest
    ) : MusicSource {
        override val id = SourceId("test")
        override val displayName = "Test"
        override val capabilities: SourceCapabilities = LocalSourceCapabilities
        // ... other overrides
    }
}
```

Each of the 12 cases is its own commit. Step pattern per test:
1. Mirror the test name + body from JavaSoundPlayerImplTest
2. Run that single test
3. If fail: fix the impl OR the test
4. Commit

This is mechanical 8-12 h of porting. Subagent-driven-development is ideal — one subagent per case, parallel review.

- [ ] **Step 0: Run all 12 + commit a final aggregation message after the last case lands.**

---

## Phase 8 — DI graph tests (P2-10)

**Goal:** Cover AndroidAppGraph + DesktopAppGraph wiring + value-class type-tag disambiguation + lazy-init thread safety. Requires Phase 5 complete (Android side).

### Task 8.1: DesktopAppGraphTest

**Files:**
- Create: `app-desktop/src/test/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraphTest.kt`

- [ ] **Step 1: Author the test**

```kotlin
// Desktop DI graph wiring test. Verifies all @Provides chains resolve
// without exceptions when create() is called with realistic value-class
// constructor args. Closes review P2-10.

package com.clayworks.kiln.desktop.di

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull

class DesktopAppGraphTest {

    @Test
    fun graph_provides_full_chain() {
        val tempDir = Files.createTempDirectory("kiln-graph-test-")
        val scanRoot = Files.createTempDirectory(tempDir, "scan-")
        try {
            val graph = DesktopAppGraph::class.create(
                userDataDir = UserDataDir(tempDir),
                scanFolders = ScanFolders(listOf(scanRoot)),
            )
            assertNotNull(graph.musicSource)
            assertNotNull(graph.scanner)
            assertNotNull(graph.player)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun graph_value_class_type_tags_disambiguate_path_params() {
        // UserDataDir + ScanFolders both wrap Path; the value-class tags let
        // kotlin-inject route them to distinct providers. Regression for the
        // CLAUDE.md gotcha "Value-class type-tags distinguish ambiguous JVM-
        // type DI bindings."
        val tempDir = Files.createTempDirectory("kiln-graph-test-")
        try {
            val graph = DesktopAppGraph::class.create(
                userDataDir = UserDataDir(tempDir.resolve("home")),
                scanFolders = ScanFolders(listOf(tempDir.resolve("music"))),
            )
            // If the type tags were broken, kotlin-inject's KSP would have
            // failed compilation, not runtime. But this test pins the contract
            // so a future refactor doesn't silently merge the types.
            assertNotNull(graph)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: Run + commit**

```powershell
./gradlew :app-desktop:test
git add app-desktop/src/test
git commit -m "test(desktop-di): cover DesktopAppGraph wiring + value-class tags

Review P2-10: closes DI graph test gap. Verifies all @Provides resolve
end-to-end on a temp-dir-rooted construction; pins the value-class
type-tag contract (UserDataDir + ScanFolders both wrap Path; kotlin-
inject routes via the wrapper)."
```

### Task 8.2: AndroidAppGraphTest

**Files:**
- Create: `app-android/src/test/kotlin/com/clayworks/kiln/di/AndroidAppGraphTest.kt`

- [ ] **Step 1: Author the test**

```kotlin
package com.clayworks.kiln.di

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class AndroidAppGraphTest {

    @Test
    fun graph_eager_inits_main_thread_required_providers() {
        // Regression for review P2-10 + Session 10 Polish-1: kotlin-inject
        // @Provides are lazy by default. The Media3ExoPlayer provider
        // requires construction on the main thread (ExoPlayer's
        // single-thread-access rule). KilnApplication.onCreate eagerly
        // touches `graph.player` to materialize the lazy provider on the
        // main thread. This test verifies the eager-init path doesn't
        // throw.

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val graph = AndroidAppGraph::class.create(context)
        assertNotNull(graph.musicSource)
        assertNotNull(graph.scanner)
        assertNotNull(graph.player)   // touches the eager-init path
    }
}
```

- [ ] **Step 2: Run + commit**

```powershell
./gradlew :app-android:testDebugUnitTest
git add app-android/src/test
git commit -m "test(android-di): cover AndroidAppGraph wiring + eager-init

Review P2-10: closes Android-side DI graph test gap. Touches the
Media3ExoPlayer provider explicitly to verify the eager-init path
KilnApplication.onCreate relies on per Session 10 Polish-1 fix.

Robolectric runner gives a real Android Context; bundled SQLite via
the inherited test classpath (Phase 5 wired this into androidUnitTest)."
```

---

## Self-review checklist

Per writing-plans skill:

**1. Spec coverage:**
- ✅ P1-1 (ReplayGain) → Phase 4
- ✅ P1-2 (parseChannels) → Task 1.1
- ✅ P1-3 (androidUnitTest) → Phase 5
- ✅ P1-4 (JNA bump) → Task 3.1
- ✅ P1-5 (Media3ExoPlayerImpl tests) → Phase 7
- ✅ P1-6 (LocalLibrarySource tests) → Phase 6
- ✅ P2-1 (Skiko constraint) → Task 3.2
- ✅ P2-5 (kiln-verify-build skill bug) → Task 1.3
- ✅ P2-6 (kiln-flac-golden CI) → Task 2.2
- ✅ P2-9 (measurement-mode stub) → Task 1.4
- ✅ P2-10 (DI graph tests) → Phase 8
- ✅ P2-11 (Track A effort) → Task 1.7
- ✅ P3-1 (CI test gate) → Task 2.1
- ✅ P3-4 (plan §4 reconciliation) → Task 1.6
- ✅ P3-5 (intentional UPDATE comment) → Task 1.5
- ✅ tooling addendum → Task 1.8
- Deferred (explicitly): P2-2 jaudiotagger, P2-3 Voyager, P2-4 kmpalette, P2-8 AudioProcessor, P3-2 album_id NULL, P3-3 AAC undeliverable, P3-6 Capability Flags, P3-7 coverage %, P2-7 duration_ms CHECK constraint (covered implicitly by Phase 4 re-scan + Phase 6 LocalLibrarySource tests; defer the schema CHECK constraint to a separate migration session).

**2. Placeholder scan:** No TBDs, no "add appropriate error handling," no "similar to Task N." Each test step has actual code. Each commit message is the actual commit message to ship.

**3. Type consistency:** TestDb's insertArtist/insertAlbum/insertTrack signatures will need to match the generated SQLDelight queries — flagged in Task 6.1 Step 2 as "verify by reading the generated TrackQueries.kt." The plan trusts the engineer to adjust if the generated signatures differ from the sketched insert names. Other types (MusicSource, BrowseScope, ItemId, Either) are consistent across tasks.

---

## Execution handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-21-pre-phase-2a-stabilization.md`. Two execution options:**

**1. Subagent-Driven (recommended for this plan)** — Each Phase is a coherent subagent task. Phase 1 has 8 sub-tasks that interleave well with subagent dispatch; Phases 4-8 benefit from a fresh subagent per test class (no context pollution between mechanical test ports).

**2. Inline Execution** — Execute tasks in this session using `executing-plans`. Batch by phase; checkpoint after Phase 1, Phase 3 (verify dep bump doesn't regress), Phase 4 (the actual investigation depends on probe result — non-mechanical), and Phase 5 (test-infra structural change).

**Which approach?**

---

## Phases NOT included in this plan (deferred — see review for rationale)

- **P2-2 jaudiotagger maintenance survey**: Only relevant if Phase 4's probe reveals jaudiotagger is the limiting factor. Defer; revisit only as Phase 4 follow-up.
- **P2-3 Voyager 1.1.0-beta03 19-month stale**: Revisit at Track C kickoff per review. Library-swap effort if needed; premature today.
- **P2-4 kmpalette beta-only**: Revisit at Phase 2a Flight A (theming) or Track A (Settings UI) per review.
- **P2-7 schema CHECK constraint on duration_ms > 0**: SQLDelight migration to user_version 2 is in scope for Track A's Settings table anyway; bundle the CHECK constraint there.
- **P2-8 AudioProcessor interface placement**: Premature until MVP Session 16+ EQ port forces the dependency direction issue.
- **P3-2 7 tracks with album_id IS NULL**: Track C UI design problem ("Songs without album" bucket).
- **P3-3 398 AAC tracks indexed but undeliverable**: Track C UI decision (filter vs label).
- **P3-6 Capability Flags decorative**: Pattern continuity; first consumer in Track C UI.
- **P3-7 Coverage % vs spec §8.2**: MVP-1.0 close criterion; not Phase-2a-stabilization-scope.

---

**End of plan.** 8 phases, 16 review findings addressed, 27-49 hours estimated. Ships as a series of small commits per CLAUDE.md "one change per commit" discipline. Each phase is independent (modulo Phase 5 → Phases 7+8 ordering); shedding phases at budget time is straightforward.
