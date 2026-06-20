# Session 21 closeout handoff — Kiln Phase 2b: B0 gate PASS + Phase 2b-A data layer

**Authored:** 2026-06-19. **For:** Clay (next-action prompts) + the next CC session (cold-start context).
**One-line state:** B0 bit-perfect gate **PASSED** on real hardware (merged); Phase 2b-A **data layer complete + reviewed-clean** on branch `phase-2b/a-spec-sheet`; the **2b-A UI (A4–A6)** is the next chunk — Clay drives visuals via Claude Design first.

---

## TL;DR — what to do next session

1. **Set the build env FIRST:** `JAVA_HOME` must point to Temurin JDK 21 (default JDK 25 wedges the Gradle daemon — see gotcha below). Prefix gradle: `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot" ./gradlew ...`.
2. **Resume on branch `phase-2b/a-spec-sheet`** (already pushed). The data layer (A0–A3) is done + reviewed.
3. **Execute the 2b-A UI: A4 → A5 → A6** from the plan `docs/superpowers/plans/2026-06-19-phase-2b-a-spec-sheet.md` (**this plan lives on branch `phase-2b/a-spec-sheet` — it is NOT on `main` until that branch merges; check out that branch to read it**), subagent-driven (opus subagents, per Clay). SDD ledger: `/tmp/kiln-sdd/progress.md` (regenerate if /tmp was cleared — the engram resume-point memory has the full state).
4. **Before A4 code:** Clay wants to iterate the SpecSheet **visual design in Claude Design** (web-only tool → produces a *design reference*, not Compose). Translate the agreed reference into Compose in A4. Don't start A4 cold without his visual direction.
5. **A6 Pixel smoke is a REQUIRED gate** — it's the only verification that the MediaExtractor format-fact correction actually works (the happy path is not Robolectric-testable).

---

## What shipped this session

| Item | State |
|---|---|
| **B0 bit-perfect gate** | **PROBE_PASS** on Pixel 10 Pro XL. Merged → `main` as **PR #25** (squash `a1cf9f1`). |
| **Phase 2b-A plan** | Written: `docs/superpowers/plans/2026-06-19-phase-2b-a-spec-sheet.md` (7 tasks A0–A6, full TDD detail). |
| **2b-A data layer A0–A3** | Complete + reviewed-clean on `phase-2b/a-spec-sheet` (pushed, no PR yet). |
| **JDK fix** | Persistent User `JAVA_HOME` → Temurin 21 (was drifted to JDK 25). |
| **CLAUDE.md** | Refreshed (this branch `docs/session-21-closeout`). |

### B0 gate result (the headline)
Ran `BitPerfectProbeActivity` on **Pixel 10 Pro XL** (mustang, Android 17 / API 37) over **wireless adb** with an active USB-C-to-3.5mm dongle. Result: `Availability: Available`, **48 kHz, 16-bit + 24-bit-packed, 2ch**.
- **Resolves the G2 dispute in Gemini's favor** (Tensor G5 *does* expose `MIXER_BEHAVIOR_BIT_PERFECT`); Codex's "do not assume enabled" was overcautious. No more research-quota tie-break needed.
- **OPEN FOLLOW-UP:** the **48 kHz ceiling is the cheap generic dongle's DAC, not the phone.** Clay's library is mostly hi-res. **A proper USB DAC re-probe is required before locking the B2 null-test matrix** (`{44.1/16, 48/16, 96/24, 192/24}`). Clay has no hi-res DAC on hand yet — it's effectively a shopping item / prerequisite for Stream B-B2.
- Doc + screenshot: `docs/decisions/2026-06-19-phase-2b-bitperfect-probe-result.md`.

### Phase 2b-A data layer (commits on `phase-2b/a-spec-sheet`, in order)
- `581286b` — flight plan
- `693507d` — **A0**: schema v3→v4, added `track.metadata_backfilled_at_ms` (nullable, LAST column per the ALTER-appends rule)
- `c9b024e` — **A1**: read model — `SpecSheetEntry`, `LibraryAggregate`, **new `LibraryStatsSource` interface** (kept `MusicSource` un-widened — Source Protocol invariant), implemented by `LocalLibrarySource`; queries `selectSpecSheetEntry`/`aggregateTotals`/`aggregateCodecCounts`; mappers
- `bba88de` + `392e48a` — **A2**: `AndroidFormatFactBackfill` — **MediaExtractor** for sample-rate + channels, **MMR** for bitrate + embedded-art, **bit_depth left null** (needs a MediaCodec decode pass — deferred). Worklist drains via per-row stamping (no offset tracking needed).
- `1d2668a` — **A3**: backfill runs at scan-end in `AndroidMediaStoreScanner` + kotlin-inject DI provider.

All four tasks passed the full SDD gate (fresh opus implementer → opus reviewer, spec ✅ + quality Approved). Test counts green throughout (`:data:library` ~89 commonTest + androidHostTest).

---

## Remaining 2b-A work (next session)

From the plan, tasks A4–A6 + the final whole-branch review:

- **A4 — `SpecSheetContent` (stateless) + `SpecSheetState` + `formatLine`.** Pure Compose render to the "Mastering Engineer's Apartment" frame. `formatLine` → e.g. `"FLAC — 24/96 — 2 ch — 1411 kbps"` (omit bit-depth segment when null). Files: `ui/components/.../specsheet/SpecSheetContent.kt`, `SpecSheetState.kt`. **Gate: Clay's Claude Design visual direction first.**
- **A5 — `SpecSheetScreen` reads real data.** Collects `LibraryStatsSource.specSheetEntry()` + `aggregateStats()`. **⚠️ CORRECTION (gemini-code-assist, PR #26 — supersedes the plan's "thread the source through Screen constructors" approach):** Voyager's `Screen` extends `java.io.Serializable` on Android, so passing `LibraryStatsSource` (holds a `KilnDatabase` + dispatcher) into the `SpecSheetScreen` constructor throws `NotSerializableException` on process death / config change. INSTEAD: keep `SpecSheetScreen(trackId: String)` serializable-only and obtain `LibraryStatsSource` via a Compose **`CompositionLocal`** (e.g. `LocalLibraryStats`) provided once at the app root (where the kotlin-inject graph is in scope), read as `LocalLibraryStats.current` inside `Content()`. Expose `abstract val libraryStats: LibraryStatsSource` on both DI graphs (same `LocalLibrarySource` instance). **Also fix the EXISTING `NowPlayingHomeScreen(player)`** — same latent bug (non-serializable `PlatformPlayer` in a Screen ctor); migrate it to a `LocalPlayer` CompositionLocal in the same pass. **Discovery:** `grep -rn "NowPlayingTab(" app-android app-desktop ui` for the app-root construction sites. `:ui:components` consumes a `Flow` so it needs NO `libs.bundles.sqldelight.common`.
- **A6 — Compose render tests + manual smoke.** jetpack-compose-test (golden / NotFound / null-RG). **Desktop smoke** (`./gradlew :app-desktop:run`, tap now-playing title, verify real facts over the 27k-track DB). **Pixel-10 smoke (REQUIRED gate):** install debug APK, trigger a scan so the backfill runs, open Spec Sheet on a real hi-res track, confirm sample-rate/channels are corrected (NOT the 44.1k/2ch placeholders).
- **Final:** whole-branch review (most-capable model) → `superpowers:finishing-a-development-branch` → open the `phase-2b/a-spec-sheet` PR.

---

## Build environment — READ THIS

- **`JAVA_HOME` must be Temurin JDK 21.** Gradle 9.5.1 runs its daemon on `JAVA_HOME`'s JDK; **JDK 25 wedges it** (with `kotlin.daemon.useFallbackStrategy=false` the Kotlin daemon hangs forever instead of failing — a 91-min, 3 GB, zero-output hang was observed this session). The project's `jvmToolchain(21)` governs *compilation*, not the daemon JVM. JDK 21: `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`. User-scope `JAVA_HOME` is now set to it, but a session's shells may inherit the old env — **prefix every gradle command** explicitly. **PowerShell** (Cortex default): `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew ...`. **Git Bash:** `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot" ./gradlew ...`.
- Canonical validation: `JAVA_HOME=<jdk21> ./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest` (green baseline confirmed this session).
- `:data:library` android-host test task is **`testAndroidHostTest`** (NOT `testDebugUnitTest`). Robolectric there can't run native `MediaExtractor` or fts5 — the A2 test hand-rolls schema minus the FTS5 virtual table.

---

## Gotchas discovered this session (also in CLAUDE.md / engram)

- **Android scanner hard-codes placeholder format facts** (`AndroidMediaStoreScanner.kt`: `sample_rate_hz=44100`, `bit_depth=null`, `channels=2`, `has_embedded_art=0`) for EVERY track. That's why the backfill exists — and why MMR alone is insufficient (it can't read those), so A2 uses **MediaExtractor** (`KEY_SAMPLE_RATE`/`KEY_CHANNEL_COUNT`) for sample-rate + channels.
- **bit_depth on Android needs a MediaCodec decode pass** (the `KEY_PCM_ENCODING`-after-`INFO_OUTPUT_FORMAT_CHANGED` dance) — deferred. Currently null in the Spec Sheet for Android tracks.
- **Wireless adb** is the only way to test bit-perfect on the Pixel 10 (single USB-C port = DAC dongle XOR cable). Pair-with-code flow; phone must be on the same `192.168.50.x` subnet as Cortex. Pairing port ≠ connect port.
- **Claude Design is web-only** (HTML/React, exports to Figma/Vercel; no Compose/Kotlin output). Use it as a SpecSheet **design reference**, then translate to Compose. Engram: `architecture/kiln-specsheet-ui-claude-design-fit-assessment`.

---

## Git / branch state at handoff

- `main` @ `a1cf9f1` (includes b0 result via PR #25). **Local `main` may be 1 commit behind `origin/main`** — fast-forward at next session start.
- `phase-2b/a-spec-sheet` — 6 commits (plan + A0–A3), **pushed**, no PR yet (flight unfinished). **Resume here.**
- `docs/session-21-closeout` — this handoff + the CLAUDE.md refresh (its own PR).
- Cross-device sync clean (10/10 repos). Note: `suitcase-memories-com` is in the allowlist but not cloned on Cortex (`sync-projects.ps1 -Clone` if wanted).

## Engram pointers (next session: `mem_search` these)
- `decision/kiln-phase-2b-b0-real-gate-result-2026-06-19` — the gate result + hi-res-DAC follow-up
- `architecture/kiln-phase-2b-a-spec-sheet-ui-android` — the 2b-A resume point
- `architecture/kiln-specsheet-ui-claude-design-fit-assessment` — Claude Design workflow
- `bug/kiln-android-gradle-builds-wedge-hang...` — the JDK-25 gotcha

---

**End of handoff.**
