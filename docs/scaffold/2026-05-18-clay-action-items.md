# Clay Action Items — Before MVP Session 1 Starts

**Date:** 2026-05-18 (end of Pre-MVP Research; pre-scaffold gate)
**Owner:** Clay Haworth (clayboicardi)

Pre-MVP Research is complete: all 12 vetting items decided, two scaffold-prep docs delivered, plan and spec updated. Per plan §2.2 exit criteria, the **next gate is your review + acknowledgment** before MVP Session 1 scaffold starts.

This file is the concise pre-scaffold TODO list. None of these are urgent — "no rush" is the operating constraint — but each unblocks MVP Session 1.

---

## 1. Decisions only you can make

### 1a. GitHub repo name

The scaffold prep doc (§12 Step 1) places the first action of MVP Session 1 as "create GitHub repo." Naming and visibility are your calls.

- **Working assumption:** `clayboicardi/kiln`. Override if you want something different.
- **Public vs private:** scaffold prep recommends public-from-day-one (Software-as-Self-Portrait pattern, plan §3.1). Override if you want to develop privately and flip public later.
- **Org placement:** under your personal `clayboicardi` account vs a future `clayworks` org. Recommendation: `clayboicardi` for now; transfer later if a Clayworks org appears.

**Decision needed:** repo name + visibility before Session 1.

### 1b. Final acknowledgment of Pre-MVP decisions

Per plan §2.2 exit criteria, the gate is "Clay reviews + acknowledges decisions before scaffolding starts." Suggested review path:

1. Read [vetting log Session 2 summary](../decisions/2026-05-18-library-vetting.md#session-2-summary) — bottom of the file; covers all 12 items at a glance
2. Read [scaffold prep §1 one-page decisions summary](./2026-05-18-mvp-session-1-prep.md#1-one-page-decision-summary)
3. Spot-check any decision that looks suspect by jumping to its Item section in the vetting log
4. Flag anything you want to revisit BEFORE scaffold starts (vs. mid-MVP)

The two things in the vetting log that have **soft-lock revisit triggers** queued for after MVP:
- kmpalette 4.0.0 stability story (revisit before Phase 2a Flight A)
- AAudio/WASAPI commitment (revisit at end of Phase 2a)

Both are downstream; not blocking now.

**Decision needed:** explicit go-ahead on the Pre-MVP-Research decision set, OR specific overrides.

---

## 2. Tooling to install before MVP Session 1

### 2a. WiX Toolset 3.x (for MSI builds on Windows)

Vetting log Item 10 + scaffold prep §9 require WiX for `:app-desktop:packageMsi`. Without it, the desktop MSI build won't work (AppImage builds still work, but MSI ships at MVP-1.0).

Install:
```powershell
choco install wixtoolset
# OR: download from https://wixtoolset.org/releases/
```

Set `WIX_HOME` env var if jpackage can't find the install path. WiX 3.x specifically — 4.x has different conventions and jpackage isn't fully compatible yet.

**Action needed:** `choco install wixtoolset` (or manual install). Smoke test deferred to MVP Session 3.

### 2b. JDK 21 Temurin (confirm not JBR)

CLAUDE.md notes: "Temurin JDK 21 (NOT JBR — JBR causes TLS/SSL issues with Gradle, per JAMZ-learned lesson)."

Verify your active JDK:
```powershell
java -version    # should report "Temurin-21..."
```

If JBR is currently active, install Temurin 21 from adoptium.net and update `JAVA_HOME` + `PATH`. Reuse the same install for `JAVA_HOME` that Gradle picks up.

**Action needed:** confirm Temurin 21 is the active JDK before Session 1.

### 2c. Android SDK alignment

CLAUDE.md notes Android SDK at `C:\Users\chawo\AppData\Local\Android\Sdk`. Verify the SDK has:
- `platforms/android-36` (Kiln's compileSdk; spec §2)
- `platforms/android-21` (Kiln's minSdk; spec §2)
- `build-tools/36.x.y` (latest)
- `platform-tools` (for `adb`)

Open Android Studio (if installed) → SDK Manager, or use `sdkmanager` CLI:
```powershell
sdkmanager "platforms;android-36" "platforms;android-21" "build-tools;36.0.0" "platform-tools"
```

**Action needed:** verify SDK has android-36 and android-21 + recent build-tools before MVP Session 1 first APK build (Session 2 step 9).

### 2d. `gh` CLI authenticated (for repo creation)

Likely already done given the rest of Clay's stack. Verify:
```powershell
gh auth status
```

If not authed: `gh auth login`.

**Action needed:** verify `gh` is auth'd before Session 1 repo-creation step.

---

## 3. Decisions that can wait until Session 1 day but worth thinking about

### 3a. The `upgradeUuid` constant

Scaffold prep §9 + §12 Step 13 require a stable UUIDv5 for jpackage's MSI upgrade detection. **Generate once, never change.** A UUIDv5 from namespace `kiln-windows-upgrade` and name `kiln-msi` gives deterministic output:

```powershell
# Conceptual — exact tool varies; pick whichever is on Clay's path:
python -c "import uuid; print(uuid.uuid5(uuid.uuid5(uuid.NAMESPACE_DNS, 'clayworks.com'), 'kiln-msi-upgrade'))"
```

The output is one string like `cdb5d8e5-ce0d-5b4f-92e9-cad5e7c6a1c1` — commit it as a constant in `:app-desktop`. Once set, never modify. Future MSI versions detect installed-Kiln-to-be-replaced via this UUID.

**Action needed at Session 1-3:** generate it, commit the constant, move on.

### 3b. App icon (`docs/assets/kiln.ico`)

Scaffold prep §5.4 references `iconFile.set(rootProject.file("docs/assets/kiln.ico"))`. The icon doesn't have to exist at MVP Session 1 — jpackage will use a default. **Polish at MVP Session 26-28** (settings + icon + polish session). For now: omit the `iconFile.set(...)` line OR commit a placeholder.

**No action needed pre-Session-1.**

### 3c. Where to keep Clay's music library scan-folder list

Desktop side needs to know where to scan. Defaults to consider:
- `${USERPROFILE}\Music` — Windows convention
- Clay's actual library root if it lives elsewhere (e.g., a dedicated music drive)

Stored in a settings file at `${user.home}/.kiln/settings.json` (via `appdirs` lib per Slack's stack pattern). Initial value: empty list; user adds folders via Settings UI at MVP Session 26-28.

**No action needed pre-Session-1.** UI lands later.

---

## 4. Honest review prompts (one per major decision area)

The vetting log is dense. If you want a faster pass, here's "the single sentence per item that, if you disagree with it, blocks MVP Session 1":

| # | Sentence to confirm or push back on |
|---|---|
| 1 | Compose Multiplatform stable line confirmed; LazyColumn 40k spike runs at MVP Session 3 |
| 2 | Coil 3.4.0 pinned; no network engine at MVP (local files only) |
| 3 | kmpalette 4.0.0-beta02 will land in Phase 2a Flight A; revisit beta-vs-stable then |
| 4 | Voyager stays as the nav library (spec didn't shift after deep-dive) |
| 5 | Circuit 0.33.1 + Molecule 2.2.0; Slack's own stack mirrors ours exactly — strong signal |
| 6 | SQLDelight 2.3.2 with 6-table schema + FTS5 unicode61 remove_diacritics; schema sketch is canonical |
| 7 | Roborazzi 1.61.0 for screenshot testing; first actual use at Phase 2a Flight A |
| 9 | Java Sound for MVP audio output (~30-100ms latency, acceptable); FLAC decoder = JNA + vendored libFLAC 1.5.0 BSD-3 DLL |
| 10 | Compose-MP `nativeDistributions` for jpackage; AppImage + MSI; no code signing at MVP |
| 11 | Media3 `MediaSessionService` for Android system integration; Windows SMTC is JIT before MVP Session 23 |
| 12 | Paged loading via SQLDelight `LIMIT/OFFSET` is the LazyColumn 40k default regardless of spike result |
| 13 | `:audio:playback` ships an engine-swap-shaped `PlatformPlayer` boundary at MVP (~10-15 hrs cost); Phase 2b H+I revisit at end of Phase 2a |

If you can read those 12 sentences and say "yes, accurate" for all, the Pre-MVP gate is clear.

---

## 5. Pre-MVP Research files for reference

In order of usefulness for review:

| Doc | Purpose |
|---|---|
| [vetting log Session 2 summary](../decisions/2026-05-18-library-vetting.md#session-2-summary) | Quick-tally view of all 12 items + JIT carry-forward matrix |
| [scaffold prep §1](./2026-05-18-mvp-session-1-prep.md#1-one-page-decision-summary) | Pinned versions in one table |
| [vetting log Item 9 + addendum](../decisions/2026-05-18-library-vetting.md#item-9-java-sound-capability-survey-on-windows) | FLAC decoder story — most material change since session 1 |
| [vertical-slice prep](./2026-05-18-mvp-session-4-vertical-slice-prep.md) | What MVP Session 4-7 will actually build — read if you want to validate the interface shapes |
| [schema sketch](../decisions/2026-05-18-sqldelight-schema-sketch.md) | The 6 tables + FTS5 strategy — read if you want to validate the data model |
| [plan §2.3 Pre-MVP outcomes](../superpowers/plans/2026-05-18-kiln-execution-plan.md#23-pre-mvp-outcomes-added-2026-05-18-post-completion) | Effort tally + soft-lock revisit calendar |

---

## 6. After Clay green-lights — what happens next

MVP Session 1 first action: open a new Claude session in `C:\Users\chawo\Projects\kiln\`, point it at this doc + scaffold prep §12. Concretely:

1. Claude reads `kiln/CLAUDE.md` (project orientation)
2. Claude reads `docs/scaffold/2026-05-18-mvp-session-1-prep.md` (the actionable scaffold doc)
3. Claude reads `docs/scaffold/2026-05-18-clay-action-items.md` (this file, to confirm Clay's pre-work is done)
4. Claude opens a TaskCreate list for the 16 numbered steps in scaffold prep §12
5. Work begins

Estimated calendar time for Session 1-3 at 4-8 hrs each: **1-3 sittings.**

---

End of pre-MVP action items.
