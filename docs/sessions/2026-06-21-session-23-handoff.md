# Session 23 Handoff — Library scan trigger shipped (#30 merged, #27 closed)

**Authored:** 2026-06-21 (Session 23 closeout)
**For:** the next Claude session (cold-start safe)
**Branch:** `main` @ `a58a27a` — PR #30 merged; no open feature branch.

---

## 🚀 Pre-flight (first 5 minutes)

**Read order (cold-start):**
1. This file — full read.
2. `CLAUDE.md` — project orientation + cumulative gotchas.
3. `mem_search "kiln scan trigger db concurrency"` — engram keys: `bug/kiln-db-concurrency-architecture-as-of-pr-30-merge` (#1933), `bug/gotcha-kiln-github-workflow-a-squash-merge-auto-closed-issue` (#1930), the SettingsRepository stub-ripple (#1931).

**Build env FIRST:** `JAVA_HOME` → Temurin JDK 21 (`C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`). **JDK 25 wedges the Gradle daemon.** PowerShell: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'`.

**Confirm baseline:**
```
git checkout main && git pull
JAVA_HOME=<jdk21> ./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest
```

---

## Where we are

- **Phase 2b-A is SHIPPED + MERGED.** Two PRs this arc:
  - **#29** (Session 22): Spec Sheet UI + Android format-fact backfill (A0–A6).
  - **#30** (Session 23): **library scan trigger** — "Scan now" button + scan-on-launch + auto-scan-on-folder-add, each individually toggleable. **Closed #27.**
- **`main` @ `a58a27a`**, CI green.
- **Root cause of #27:** the `LibraryScanner` was fully built/tested/DI-provided but **no production code ever called `scanIncremental()`/`scanFull()`** on either platform. Desktop only *looked* fine on 27k stale rows; Android started empty so the gap was visible there. Fixed by wiring the three triggers (mirroring the proven backfill-button pattern).
- **On-device verified** (Pixel 7 Pro / Android 16, re-smoked post-merge): `track` **0→517** via scan-on-launch; `AndroidFormatFactBackfill` **517/517**; `sample_rate_hz` corrected from a uniform `44100` placeholder to real hi-res variety (192k/96k/48k/44.1k/22k); "Scan now" Done-state renders; library **intact (0 soft-deleted)** with the shared lock + SAF guard active.

## Decisions made (Session 23)

- **3-round bot-review loop → architecture-question call.** Codex (the thorough reviewer on this repo) surfaced progressively subtler data-integrity edges each round; **gemini was clean throughout**. On round 3, per the systematic-debugging "every patch reveals a new problem" signal, the data-integrity hardening was **deferred to #31** rather than patch-looping. Merged per Clay's "merge when gemini's clean" heuristic + the low practical risk for local-FLAC-over-SAF use.
- **Shared `LibraryWriteLock`** — a `@Singleton` Mutex injected into both scanners + `TrackAnalysisRunner` — serializes the two `track`-table writers over the single SQLite connection. **It is a patch** over the single-connection architecture (tracked in #31). The scanner holds it per-scan; the analyzer per-DB-op (releasing during `analyze()`).
- **Soft-delete guards:** both scanners skip `softDeleteUnscanned` when a configured root/SAF-tree is inaccessible — prevents wiping the library when a drive is unmounted / a SAF grant is lost. Desktop = `Files.exists`; Android = `SafTreeWalker.isTreeReadable` (root probe).

## Open follow-ups (next session — pick with Clay)

1. **#31 — scan/analyzer data-integrity hardening** (deferred from #30): (a) **per-source soft-delete reconciliation** (the SAF guard is root-only; per-document read failures from an offline cloud provider slip through), (b) **analyzer revalidate-before-persist** (the per-op lock releases during `analyze()`, so a concurrent scan can stale the row), (c) **single-writer DB** executor / WAL as the structural fix that retires the ad-hoc lock + guards.
2. **#32 — MediaStore+SAF duplicate-row dedup** (surfaced visibly in the re-smoke; pre-existing). Dedupe by `(file_size_bytes, file_mtime_ms, display_name)` requiring `has_known_mtime = 1`.
3. **#28 — desktop playback / queue / analyzer cluster** (open since Session 22; untouched this session). Distinct subsystems → systematic-debugging session.
4. **Spec Sheet aesthetic redesign** — Clay found the render "dull"; a Claude Design pass is pending (Clay drives the visuals → export Compose code or a reference for CC to replicate in `SpecSheetContent.kt`). The build is functional-to-spec, not pixel-locked.
5. **Next Phase 2b flight** — Spec Sheet was 2b-A; see the execution plan for 2b-B onward (libs / low-latency / AAudio-WASAPI).

## Gotchas discovered (this session)

- **A `fix #N` / `closes #N` / `resolves #N` anywhere in a squash-merge commit body auto-closes issue #N** — even as descriptive TODO text. The Session 22 handoff's "merge #29 / … / fix #27 / triage #28" option list got concatenated into the #29 squash body and **falsely closed #27** (detected: `closedAt` 1s after `mergedAt`, `stateReason COMPLETED`). Reword future "fix #N" TODOs to "address #N". (engram #1930)
- **Changing the `SettingsRepository` interface breaks the `StubSettingsRepository` test doubles** in `:audio:playback` (`JavaSoundPlayerImplTest` desktop + `Media3ExoPlayerImplTest` androidHostTest). Per-module builds miss it; only the **canonical 6-target build** catches it. Grep `: SettingsRepository` when changing the interface. (engram #1931)
- **The scanner + analyzer share ONE SQLite connection** — concurrent `db.transaction{}` corrupts it (`SQLITE_BUSY` / nested BEGIN / `SQLiteDatabaseLockedException`). The `LibraryWriteLock` serializes them. (engram #1933, #31)
- **`softDeleteUnscanned` is global** — any scan over a partial view (inaccessible root) wipes that root's tracks. Now guarded at the root level; **per-document** SAF read failures still slip through (#31).
- **Re-smoke device recipe:** `export MSYS_NO_PATHCONV=1` for any adb command; pull the DB via `adb -s 2A261FDH300B1P exec-out run-as com.clayworks.kiln cat databases/kiln.db > .smoke.db` to a **cwd-relative path** (Git Bash `/tmp` ≠ Windows Python's resolution), then query with Windows Python (on-device `sqlite3` is absent). The Pixel 7 has 3 SAF folders + 517 tracks staged. uiautomator works on the Pixel 7 (Android 16) via `android-mcp-pixel7`.

## Working tree state

- On `main` @ `a58a27a` (+ this closeout docs commit), clean. PR #30 merged + branch deleted.
- Issues: **#28, #31, #32 open; #27 closed.**
- Scan-trigger plan: `docs/superpowers/plans/2026-06-20-phase-2b-a-scan-trigger.md`.

## References

- Plan: `docs/superpowers/plans/2026-06-20-phase-2b-a-scan-trigger.md`
- Prior handoff: `docs/sessions/2026-06-20-session-22-handoff.md`
- Design contract: `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`
- PRs: #29 (Spec Sheet), #30 (scan trigger). Disposition trail: PR #30 comments (3 review rounds).

## Copy-paste prompt for the next session

> Pick up Kiln **Session 24**. Read `docs/sessions/2026-06-21-session-23-handoff.md` fully. Set `JAVA_HOME` → Temurin JDK 21 before any `./gradlew`. Phase 2b-A is shipped + merged (scan trigger #30 closed #27). Decide with Clay which to start: **(a) #31 scan/analyzer data-integrity hardening** (single-writer DB is the structural fix), **(b) #32 MediaStore+SAF dedup**, **(c) #28 desktop playback/queue/analyzer cluster**, **(d) the Spec Sheet aesthetic redesign via Claude Design**, or **(e) the next Phase 2b flight** per the execution plan.
