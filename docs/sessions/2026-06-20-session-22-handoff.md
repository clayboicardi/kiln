# Session 22 Handoff — Phase 2b-A Spec Sheet UI (A4–A6) shipped; PR #29 open

**Authored:** 2026-06-20 (Session 22 closeout)
**For:** The next Claude session (cold-start safe)
**Branch:** `phase-2b/a-spec-sheet` @ `0de3ac8` — PR #29 open vs `main`

---

## 🚀 Pre-flight (first 5 minutes)

**Read order (cold-start):**
1. This file — full read.
2. `CLAUDE.md` — project orientation + cumulative gotchas.
3. `mem_search "kiln phase 2b-a"` — engram keys: `architecture/kiln-voyager-screen-serializable-compositionlocal`, `bug/kiln-android-scan-trigger-broken`, `bug/kiln-pr29-bot-review-fixes`.

**Build env FIRST:** `JAVA_HOME` → Temurin JDK 21 (`C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`). **JDK 25 wedges the Gradle daemon** (91-min hang). PowerShell: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'`.

**Confirm baseline:**
```
git checkout phase-2b/a-spec-sheet && git pull
JAVA_HOME=<jdk21> ./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest
```
If dirty or red — STOP, diagnose, surface to Clay.

---

## Where we are

- **PR #29** open: phase-2b-a Spec Sheet (A0–A6) → `main`. **CI green.** Whole-branch review (opus): **Ready to merge.** Both bot reviews (gemini + codex) addressed — see disposition comment on the PR.
- **Branch** `phase-2b/a-spec-sheet` @ `0de3ac8`, synced with origin.
- **Spec Sheet feature: desktop-verified** on Clay's real 27,766-track `D:\tiddl` library — formatLine (`FLAC — 16/44.1 — 2 ch — 969 kbps`), ReplayGain row, file facts, aggregate footer (`27,766 tracks · 711.8 GB · FLAC 27368, AAC 398`) all render real data.
- **Build/tests:** green; `:data:library` 89/89, `:audio:playback` 31/31, `:ui:components` SpecSheet format/render tests pass; warning-clean (allWarningsAsErrors).
- **Open issues:** **#27** (Android scan trigger broken — blocks on-device backfill), **#28** (desktop playback/queue/analyzer cluster).

---

## Session 22's outputs

This session = the A4–A6 UI flight + on-device smoke + PR + bot-review fixes. (A0–A3 data layer landed in Session 21.)

| Commit | What |
|---|---|
| `6849f8a` | merge `main` (b0 probe result + refreshed CLAUDE.md) into branch |
| `3f33181` | A4 — `SpecSheetContent` stateless UI + `formatLine` |
| `42eda93` | A5 — `SpecSheetScreen` real-data wiring + **Voyager `CompositionLocal` fix** (latent `NotSerializableException` on `NowPlayingHomeScreen`) |
| `85a7d02` | A6 — compose render tests |
| `128aebb` | review: SpecSheet formatting — `Locale.US` + drop `java.time.Instant` (minSdk-23 crash) |
| `21f72ff` | review: batch `AndroidFormatFactBackfill` page writes in a transaction |
| `0de3ac8` | review: clear backfill stamp on rescan so changed Android files re-verify |

Executed subagent-driven (opus); per-task reviews clean; whole-branch review Ready to merge.

---

## In-flight items (next session — pick with Clay)

1. **Merge PR #29** — CI green, review-clean. Just needs the go.
2. **Spec Sheet aesthetic redesign via Claude Design** — Clay found the visual "dull/boring." He'll drive a Claude Design pass and export either (A) Compose-ready code to wire in, or (B) a design reference for CC to replicate in `SpecSheetContent.kt`. Current build is functional-to-spec ("Mastering Engineer's Apartment" frame), NOT pixel-locked.
3. **Fix #27 — Android scan trigger is broken.** Neither "Scan library on launch" nor "Add Folder" actually runs a scan (verified Pixel 7/A16 + Pixel 10/A17 — no scanner logs, `track` count stays 0). Blocks the A2/A3 on-device backfill. **5 format-demo files (22.05k–192k) are STAGED** at `/sdcard/Music/kiln-smoke/` on the Pixel 7 + the folder is added to kiln — validate the backfill there once the trigger is fixed.
4. **Triage #28 — desktop playback / queue / ReplayGain-analyzer defects** (Clay-reported: can't start a track while one is playing; skip is a no-op / no queue; analyze sees a small subset). Distinct subsystems — systematic-debugging session.

---

## Decisions made

- **A4 built to spec, no Claude Design pre-pass** — Clay chose functional-first + review the render; the aesthetic is a separate Claude Design iteration.
- **PR #29 bot-review dispositions:** FIXED java.time-crash + locale + transaction-batching + rescan-stamp. DECLINED the "cheaper art check" — gemini's `MediaMetadataRetriever.METADATA_KEY_HAS_ARTWORK` **does not exist** (build failed on it) and `getEmbeddedPicture()` doesn't accumulate (no OOM). DEFERRED the `LibraryTab`/`SearchTab` → CompositionLocal migration (they're `remember`-reconstructed Tabs → not serialized; out of A0–A6 scope; possible belt-and-suspenders follow-up).

---

## Gotchas discovered (this session)

- **`java.time.*` crashes on minSdk 23** (no core-library desugaring → `NoClassDefFoundError` on API 23–25). Use `java.text.SimpleDateFormat`/`java.util.Date` in `:ui:components` + Android-reachable code, and **pin `Locale.US`** on every `String.format` (comma-decimal locales otherwise render `44,1 kHz`).
- **Android scan trigger broken** (#27) — the A2/A3 backfill cannot run on-device until this is fixed.
- **Device-testing infra:** uiautomator (MCP uiautomator2 + framework `uiautomator dump`) is BROKEN on Android 17 (`ApplicationSharedMemory`) but WORKS on Android 16 → drive the **Pixel 7** via `android-mcp-pixel7`. On-device `sqlite3` is absent → pull the DB via `adb exec-out run-as com.clayworks.kiln cat databases/kiln.db` then query with Windows Python. **`export MSYS_NO_PATHCONV=1`** is MANDATORY for any `adb` command with `/sdcard` or `/data` paths in Git Bash (else mangled to `C:/Program Files/Git/...` — it truncated a device DB once). `adb shell sleep N` works.
- **`metadata_backfilled_at_ms` is Android-only-meaningful** — desktop rows stay NULL (the backfill is `androidMain`-only), so clearing it in the shared `updateForRescan` is safe both ways.

---

## Working tree state

- Branch `phase-2b/a-spec-sheet` @ `0de3ac8` — tracked tree clean, synced with origin.
- `.superpowers/sdd/` holds session scratch (git-ignored): SDD ledger + pulled device DBs + Pixel screenshots. Ignore / safe to clean.
- No stashes. PR #29 open; issues #27, #28 open.

---

## Verify-before-starting checklist (plan §11)

- [ ] `JAVA_HOME` → Temurin JDK 21 set in the shell.
- [ ] `git status` clean, on `phase-2b/a-spec-sheet`, synced with origin.
- [ ] Canonical 5-target build green.
- [ ] Confirmed with Clay which in-flight item to start (merge / design / #27 / #28).

---

## References

- Plan: `docs/superpowers/plans/2026-06-19-phase-2b-a-spec-sheet.md`
- Spec: `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`
- Prior handoff: `docs/sessions/2026-06-19-session-21-closeout-handoff.md`
- PR #29: https://github.com/clayboicardi/kiln/pull/29 · Issues: #27 (Android scan), #28 (playback cluster)

---

## Copy-paste prompt for the next session

> Pick up Kiln **Session 23**. Read `docs/sessions/2026-06-20-session-22-handoff.md` fully. Set `JAVA_HOME` → Temurin JDK 21 before any `./gradlew`. Then decide with Clay which to start: **(a) merge PR #29** (CI green, review-clean), **(b) the Claude Design aesthetic pass** on the Spec Sheet, **(c) fix the Android scan-trigger bug #27** — the Pixel 7 has 5 staged demo files in `/sdcard/Music/kiln-smoke/` ready to validate the A2/A3 backfill once a scan runs, or **(d) triage the playback/queue/analyzer cluster #28**.
