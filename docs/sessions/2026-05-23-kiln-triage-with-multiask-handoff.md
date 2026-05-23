# Handoff — Kiln Track D Post-Merge Triage with Multi-Ask Toolset

**Authored:** 2026-05-23 at the close of the session that merged Phase 2a Track D-B Android (PR #14) and queued the ultrareview baseline
**For:** Fresh CC session
**Goal:** Triage the 3 ultrareview findings against Track D + ship the SettingsScreen scroll fix that's blocking the audible smoke, leveraging the newly-shipped `/multi:*` toolset for cross-model validation.

---

## TL;DR

- **Phase 2a Track D is fully merged** (5 PRs: #10-#14). Main is at `58ed829`.
- **Ultrareview ran against a synthetic Session 9-10 baseline (PR #15, still open and not for merging).** Returned 3 normal-severity bugs (`bug_002`, `bug_003`, `bug_004`). All have file:line refs + proposed fix shapes.
- **The multi-ask toolset shipped and replaced octo.** 9 `/multi:*` wrapper skills + Decision Matrix in CLAUDE.md + multi-doctor diagnostics. **Critical hazards exist — read the Multi-Ask Hazards section before invoking any wrapper.**
- **Audible end-to-end smoke is blocked** by a pre-existing `SettingsScreen.kt` vertical-scroll bug. P1 follow-up; single-line fix; unblocks the manual verification path that the ultrareview triage will ride on top of.

**Work order suggestion** (each task gates the next):
1. Read this doc + the falsify-results doc (linked below) BEFORE invoking any `/multi:*` wrapper. Sovereignty hazards are real.
2. Ship the `SettingsScreen.verticalScroll` fix as a tiny PR (one-line change in `:ui:components`). Unblocks smoke for everything that follows.
3. Triage `bug_002` (24-bit PCM analyzer) first — highest production impact (Clay's hi-res FLAC library on Pixel 10 Pro XL). Use `/multi:falsify` on the proposed int24 sign-extension fix shape.
4. Triage `bug_003` (desktop RG race) — quick harmonize-with-Android-precedent fix. Use `/multi:diff-review` on the staged patch.
5. Triage `bug_004` (SAF mtime substitution) — architecture decision worth ADR-shaping. Use `/multi:decide` for sentinel-0L vs new `has_known_mtime` column.
6. Clean up PR #15 + the `ultrareview-baseline-s10` branch.

---

## Required Reading (in order, do not skim)

1. **This doc** — full pass.
2. **Falsify results on the multi-ask migration itself** — `~/Documents/task-order-decision-communication/falsify-results-on-multi-ask-migration_2026-05-23.md`. **Critical** — contains the 4 confirmed Tier-0 hazards in the new toolset. Item #1 (synthesis exfiltration) is verified-real in code at `multi-ask.sh:320`. The Sovereignty rule is policy theater until that's fixed.
3. **CLAUDE.md** (global, at `~/.claude/CLAUDE.md`) — lines 52-122 contain the Multi-Provider Decision Matrix (15-row task→routing table + 4 pre-routing checks + Sovereignty rule + override heuristics). The system you're operating inside.
4. **CLAUDE.md** (project, at `C:\Users\chawo\Projects\kiln\CLAUDE.md`) — full pass. The ~30 Track D gotchas added across Sessions 14-17 inform the bug triage work directly (esp. the Media3 `AudioProcessor`, MediaItem.mediaId stability, race-fix gotchas).
5. **Each `/multi:*` SKILL.md you intend to invoke** — `~/.claude/skills/multi-{research,prior-art,decide,falsify,diff-review,freshness-check,brainstorm,debug,doctor}/SKILL.md`. The "When to use this skill" + "NOT for" sections are load-bearing.
6. **Ultrareview findings on PR #15** — `gh pr view 15 --comments` OR read the inline reviewer comments directly. Three bugs documented at file:line precision with reasoning + proposed fix paths.

---

## Current State

### Git

- **Main branch HEAD:** `58ed829` Phase 2a Track D-B (Android) — consumer-side ReplayGain via Media3 RenderersFactory (#14)
- **Origin/main:** in sync with local main
- **Working tree:** one modified file, `CLAUDE.md` (project) — intentional per a prior session reminder; do NOT revert without asking Clay. Inspect via `git diff CLAUDE.md` if curious about the content.
- **Open PRs:** `#15 DO NOT MERGE — ultrareview baseline diff (Sessions 11-17)` — draft, base=`ultrareview-baseline-s10`, head=`main`. **DO NOT MERGE.** It exists as the synthetic diff target for the ultrareview run; close it and delete the baseline branch as part of triage cleanup.
- **Remote branches to clean up after triage:** `ultrareview-baseline-s10` (the throwaway pointer at `709f314`).

### Build/Test State

- **Canonical 8-target build:** GREEN at last session close. Re-run before merging any triage PR via:
  ```bash
  ./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:build :audio:dsp:desktopTest :audio:playback:desktopTest --console=plain --warning-mode=none
  ```
- **Robolectric Android tests in `:audio:playback`:** 25 green (Media3=14 + MediaProcessor=8 + Renderers=3).
- **Project total tests:** ~210+ across modules.
- **APK on Pixel 7 Pro (`2A261FDH300B1P`):** installed at `lastUpdateTime=2026-05-22 17:02:14` (Track D-B Android live); D-C disclaimer text confirmed live on-device.

### Engram Memory

The session that just closed saved two relevant entries:
- `kiln/track-d-b-android-in-flight` (saved mid-session as insurance) — pre-merge state
- `kiln/track-d-b-android-shipped` — post-merge state including PR #14 and the SettingsScreen scroll bug

Run `mem_search "kiln/track-d-b"` to recall both. Use `mem_get_observation` to read the full content of either.

---

## Ultrareview Findings (the triage queue)

PR #15 is the synthetic baseline diff (everything since Session 10's last ultrareview, ~50+ commits across Sessions 11-17). Three findings, all severity=normal, all real bugs with reproductions and fix paths in the comments. Summary:

### bug_002 — `AndroidMediaTrackAnalyzer` silently misreads 24/32-bit PCM as int16

**File:** `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/AndroidMediaTrackAnalyzer.kt:120-145`

**Severity:** normal per ultrareview, but **functionally critical** for Clay's specific library shape.

**What's wrong:** The PCM-encoding `when` block only branches on `ENCODING_PCM_FLOAT` (4). Every other value — including the API-31+ `ENCODING_PCM_24BIT_PACKED` (21) and `ENCODING_PCM_32BIT` (22) — falls through the `else` to the 16-bit path (`sampleCount = size/2` + `orderedBuffer.short / 32768f`). For 24-bit FLAC on a Pixel 10 Pro XL, MediaCodec can legitimately emit `ENCODING_PCM_24BIT_PACKED`. The misaligned-garbage float samples then poison `LoudnessAnalyzer` → poison `replay_gain_track_db` → wrong RG gain applied at playback.

**Why this matters for Clay specifically:** `D:\tiddl` is curated 24-bit FLAC. Pixel 10 Pro XL is the daily driver. **Any track that has been analyzed on Android may have poisoned RG values in `track.replay_gain_track_db`.** Verification approach: SQL query the Android DB and compare `replay_gain_track_db` values against the same tracks' values from the desktop (correctly-analyzed) JVM analyzer. Large divergence = confirmed poisoning.

**Proposed fix (from the reviewer comment):**

```kotlin
val bytesPerSample = when (pcmEncoding) {
    4 -> 4              // ENCODING_PCM_FLOAT
    21 -> 3             // ENCODING_PCM_24BIT_PACKED
    22 -> 4             // ENCODING_PCM_32BIT
    else -> 2           // ENCODING_PCM_16BIT (and pre-API-31 default)
}
val sampleCount = bufferInfo.size / bytesPerSample
// ... per-encoding read loop with sign-extension for int24
```

**Recommended triage path:**
1. `/multi:falsify` on the proposed fix shape — does the int24 sign-extension actually work? Are there MediaCodec scenarios where 24-bit FLAC arrives as 32-bit or float instead of `ENCODING_PCM_24BIT_PACKED`? (Codex's code-search-bias is the right lens here.)
2. After fix lands, re-run the analyzer over a small album of Clay's hi-res FLAC + compare LUFS values against the desktop reference.
3. **The poisoned-data question:** if Android has analyzed tracks before this fix, those RG values need re-analysis. Either invalidate (`UPDATE track SET replay_gain_track_db = NULL WHERE ...`) or full re-analyze pass. Decision needed before the fix ships.

### bug_003 — Desktop `JavaSoundPlayerImpl.startStream` RG-gain race

**File:** `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt:425-433`

**What's wrong:** The fire-and-forget RG-init `scope.launch` closes over the captured `playable` parameter instead of re-reading `@Volatile currentPlayable` after the two suspending settings reads. Under rapid track-skip during the IO window where `settings.replayGainMode.first()` + `replayGainPreAmpDb.first()` are suspended on `ioDispatcher`, an older launch can resume AFTER a newer transition has started, leaving `rgProcessor` set to the previous track's gain.

**This contradicts the CLAUDE.md gloss I wrote during Session 17** which claimed the desktop side was "shielded by single-thread audioDispatcher serialization." Ultrareview's analysis: that's wrong on a critical point — a single-thread dispatcher serialises **entry** (when a runnable starts) but NOT **resumption-after-suspension** on `Dispatchers.IO`. The two `.first()` calls are genuine suspension points.

**The Android sibling (`Media3ExoPlayerImpl.kt:208-213`) already has the correct fix** (re-read inside the launch body) shipped in PR #14. The desktop just needs the same pattern applied. ~4 line change.

**Fix:**
```kotlin
scope.launch {
    val mode = settings.replayGainMode.first()
    val preAmpDb = settings.replayGainPreAmpDb.first()
    val latest = currentPlayable ?: return@launch  // ← re-read after suspends
    applyRgGain(latest, mode, preAmpDb)
}
```

**Recommended triage path:**
1. Apply the fix to `JavaSoundPlayerImpl.startStream`.
2. `/multi:diff-review` on the staged patch — claude+codex review for "is this the same shape as the Android sibling and is there any desktop-specific concern I'm missing?" Quick gate.
3. Update the relevant CLAUDE.md gotcha — the Session 17-added "the desktop precedent has the same shape but is shielded by audioDispatcher" line is wrong. Replace with the actual now-true statement: "both desktop and Android use the re-read-inside-lambda pattern."

### bug_004 — SAF tracks without mtime get fully re-processed every scan

**File:** `data/library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt:336-353`

**What's wrong:** When a SAF DocumentsProvider doesn't report `COLUMN_LAST_MODIFIED` (returns 0L from `SafTreeWalker`), the scanner substitutes `scanStartedMs` (current wall-clock) into `file_mtime_ms`. Because `scanStartedMs` differs on each scan, the unchanged-check at line 343 (`existing.file_mtime_ms == mtime`) is guaranteed to fail on subsequent scans, forcing a full `MediaMetadataRetriever` tag re-read + per-row upsert on every scan, forever, for every affected file.

**Affected providers:** Custom DocumentsProviders that omit `lastModified` (cloud-backed Drive/Dropbox/OneDrive bridges, some MTP and file-manager providers). Built-in `ExternalStorageProvider` does expose it, so locally-picked filesystem folders are unaffected.

**Impact:** Performance / DB churn / FD pressure on every scan-on-launch for affected files. No user-facing error.

**Fix options** (architecture decision):

(a) **Sentinel-0L approach** — persist `0L` verbatim when `doc.lastModified <= 0` and treat both-sides-zero as unchanged when size matches. Minimal change.

(b) **`has_known_mtime` column** — schema migration adds a boolean flag; gate the comparison on it. More explicit but requires SQLDelight migration discipline (see CLAUDE.md gotcha: `.sqm` migration files are named by SOURCE schema version, not target).

**Recommended triage path:**
1. `/multi:decide` between (a) and (b). The skill ships an ADR-shaped output with preserved dissent + a ready-to-paste engram-save block — exactly the right tool for this kind of architecture lock.
2. After ADR locks, implement + smoke against a Drive-backed SAF folder if you can configure one. Otherwise document the verification gap.

---

## Multi-Ask Toolset Hazards (READ BEFORE INVOKING)

The toolset shipped (Phase 1-4 complete: 9 wrappers + Decision Matrix + multi-doctor). However, the `/multi:falsify` adversarial pre-mortem run against the migration itself surfaced **4 confirmed Tier-0 hazards** and several Tier-1 issues. **Read the full falsify-results doc** (`~/Documents/task-order-decision-communication/falsify-results-on-multi-ask-migration_2026-05-23.md`) before using anything — the full text including the per-provider raw responses is load-bearing.

**Highlights you MUST know:**

### #1 — Synthesis exfiltration: CONFIRMED-IN-CODE

`multi-ask.sh:320` unconditionally invokes `ask-claude.sh` for the synthesis step regardless of which providers ran in the per-provider fan. If you invoke `multi-ask.sh -p ollama` for sovereignty (e.g., for any Gramophone PHI, real credentials, customer PII Clay is processing on others' behalf), **the per-provider responses are local but the synthesis bundles them and ships everything to `claude-opus-4-7` via OAuth.** The CLAUDE.md "Synthesis exception" clause is documented policy with **zero enforcement in code**.

**Operational implication for Kiln triage:** The 3 ultrareview bugs are not sovereignty-classed content. None of them involve PHI/credentials/customer data. **You can safely use `/multi:falsify`, `/multi:decide`, `/multi:diff-review` against them.** But do NOT route any sovereign content through any `/multi:*` wrapper until #1 is fixed in `multi-ask.sh`. The fix is ~15 lines per the falsify recommendations; until it lands, treat the Sovereignty rule as policy theater.

### #2 — `/multi:diff-review` auto-fans secrets

Diffs are precisely the content most likely to contain accidental secrets (staged `.env`, leftover tokens, OAuth JSON, PHI-adjacent notes). The default `/multi:diff-review` invocation fans the diff to claude+codex (gemini and ollama excluded by default — `multi-diff-review.sh` enforces that). Logged to `~/agent/logs/multi-ask.log` before any operator review.

**Operational implication for Kiln triage:** Kiln's working tree won't contain secrets (it's a music player codebase + tests). `/multi:diff-review` is safe to use here. But **be very explicit when staging the diff** — do not run `/multi:diff-review` against an unstaged working-tree that contains an untracked `.env` or any backup file. Use `git diff --staged` semantics + visually inspect what you're about to ship before invoking.

### #3 — Hindsight zombie

CLAUDE.md declares Hindsight the PRIMARY memory layer. The service is alive (`curl http://localhost:8888/health` returns `{"status":"healthy"}`) but log shows zero traffic. CC isn't writing to it. This is policy-vs-behavior drift.

**Operational implication:** When you save memory entries for the triage work, **route to Engram + Honcho** (the working layers). Don't trust `mem_search` results that depend on Hindsight content existing — it likely doesn't.

### #4 — `OCTOPUS_DISABLE_BARE` rename trap

Load-bearing for claude OAuth despite octo legacy name. Future "clean up octo env vars" would break the claude provider. Don't touch `~/.claude/settings.json:OCTOPUS_DISABLE_BARE` or `OCTOPUS_CODEX_MODEL`.

### Other Tier-1 issues worth knowing

- **Plan-skill routing references dead `/octo:plan`** — `MEMORY.md` rule still points there. If a planning task arises, use `superpowers:writing-plans` directly. No `/multi:plan` exists.
- **Backup file pollution** — multiple `CLAUDE.md.bak-*` files in `~/.claude/`. Might cause routing drift if CC's context loader globs them. Avoid edits in that directory unless intentional.
- **Per-provider timeout semantics unclear** — long stalls possible if one provider hangs. The default 240s timeout in most wrappers is a reasonable floor.

---

## Mapping Bugs → Wrappers (the triage workflow)

### bug_002 (24-bit PCM analyzer)

**Best wrapper: `/multi:falsify`** on the proposed fix shape (the int24 sign-extension code from the reviewer comment).

**Why falsify, not diff-review:** This is an *architectural* question (does MediaCodec actually emit 24-bit on the Pixel 10 Pro XL for FLAC, and does the proposed sign-extension correctly handle Android's packed encoding?) not just a code-style review. Falsify's adversarial framing will surface MediaCodec edge cases that single-model review might validate-bias away.

**Skip claude+codex unless gemini quota is exhausted:** Gemini's web-grounding can confirm current Android API behavior for `ENCODING_PCM_24BIT_PACKED`. Codex's code-search-bias is the right lens for sign-extension correctness. Use the standard 3-way fan.

**After fix:** Re-analyze a small album of Clay's hi-res FLAC. SQL-compare LUFS values against desktop's golden values to validate.

### bug_003 (desktop RG race)

**Best wrapper: `/multi:diff-review`** on the staged patch (after the fix is applied to `JavaSoundPlayerImpl.startStream`).

**Why diff-review, not falsify:** The fix shape is well-defined (mirror Android's race-fix pattern, ~4 lines). The risk is "did I miss a desktop-specific concern when copying the Android pattern" — that's review, not architecture. `/multi:diff-review`'s LGTM early-exit is ideal: most of the patch will be fine, the reviewer just needs to flag any divergence.

### bug_004 (SAF mtime substitution)

**Best wrapper: `/multi:decide`** between sentinel-0L approach and `has_known_mtime` column.

**Why decide:** This is an ADR-shaped architecture question (schema change vs in-place sentinel). The wrapper outputs an ADR with preserved dissent + an engram-save block — the dissent matters because if codex/gemini disagree on the cleaner approach, that's signal worth preserving in the audit trail. The engram block lets the decision land in memory automatically.

**Caveat:** Look at the SQLDelight schema migration gotchas in CLAUDE.md before invoking — `.sqm` file naming and migration discipline factor into the cost of option (b). Make sure the providers have that context in the prompt.

### Optional: `/multi:diff-review` on the SettingsScreen scroll fix

Before triaging the bugs, ship the `SettingsScreen.verticalScroll` fix. It's a one-line change in `:ui:components/src/commonMain/.../SettingsScreen.kt` (add `.verticalScroll(rememberScrollState())` to the Column modifier). `/multi:diff-review` is overkill for one line — solo + commit is fine. But running it once on a trivial change is a good way to dogfood the wrapper before using it for real on bug_003.

---

## Open Backlog (carry-forward from Session 17 handoff)

From `docs/sessions/2026-05-22-session-18-handoff.md` (committed in `4c10061`, squashed into `58ed829`):

### P1 — SettingsScreen vertical-scroll bug
- **Location:** `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt` (Column modifier)
- **Issue:** No `Modifier.verticalScroll(rememberScrollState())` on the parent Column → on Pixel 7 Pro viewport the "Analyze missing tracks" Backfill button (line ~180-186) is clipped.
- **Fix:** single-line change adding the modifier
- **Why this is urgent:** blocks the audible end-to-end smoke. Without reaching Analyze → no analyzed tracks → `resolveGainLinear` returns 1.0 → toggling RG modes doesn't change audio. So the audible verification of the entire Phase 2a Track D consumer-gain chain is gated on this.

### Other carry-forward items (not urgent)
- **Search tab UX polish** — Clay's 2026-05-22 demo note: "rough at best." FTS5 backend correct; UI layer needs work.
- **Phase 2b start** — Spec Sheet UI, low-latency AAudio/WASAPI engine. Per execution plan: 205-310 hrs. The next major chunk after Track D.
- **`resolveGainLinear` null-effective-db decision** — currently returns 1.0 when both trackDb and albumDb are null, silently ignoring pre-amp. Product decision pending — some players apply pre-amp solo.

---

## Cleanup Tasks (do these as part of triage closeout)

1. **Close PR #15 WITHOUT MERGING:** `gh pr close 15`. It was the synthetic ultrareview baseline; not for merging.
2. **Delete the throwaway baseline branch:** `git push origin --delete ultrareview-baseline-s10`.
3. **If you fixed `CLAUDE.md.bak-*` backups** per the falsify recommendation (move them out of `~/.claude/`), document the move and the new location.

---

## Things to NOT Do

- **Do NOT touch the `OCTOPUS_*` env vars in `~/.claude/settings.json`.** Despite the legacy name, they're load-bearing for claude OAuth and codex CLI pinning. The falsify analysis verified this in code.
- **Do NOT use `/multi:*` wrappers for sovereign content** (PHI, credentials, customer PII) until the synthesis-exfiltration bug in `multi-ask.sh:320` is fixed. The Kiln triage content is NOT sovereign; you're fine using the wrappers for the 3 bugs. But save the sovereignty-protected workflows (any Gramophone PHI) for after the fix lands.
- **Do NOT auto-spawn from this handoff.** It carries `auto_spawn: false` in the cc-comms metadata. Clay launches the fresh session manually.
- **Do NOT add features beyond the spec's anti-roadmap (§11)** — see project CLAUDE.md "Hard Rules" section.

---

## Quick-start Commands

After reading the required-reading list:

```bash
# Verify build is green from the start
cd ~/Projects/kiln
./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:build :audio:dsp:desktopTest :audio:playback:desktopTest --console=plain --warning-mode=none

# Verify the multi-ask stack is healthy before invoking any wrapper
~/.claude/scripts/multi-doctor.sh

# Read the ultrareview findings inline on PR #15
gh pr view 15 --comments

# Engram recall of the prior session
# (use mcp__plugin_engram_engram__mem_search with query "kiln/track-d-b")
```

---

## Suggested First Move

1. Run `multi-doctor` (sanity-check the stack).
2. Read the falsify-results doc fully (hazards awareness).
3. Apply the `SettingsScreen.verticalScroll` fix as a small PR. Commit, verify-build, push, merge. Single-line change. Unblocks everything else. **Optionally** run `/multi:diff-review` on it as a dogfood pass — even though it's overkill for one line, it's a low-risk way to verify the wrapper actually works on a real Kiln diff before relying on it for bug_003.
4. Triage `bug_002` next. Use `/multi:falsify` on the proposed fix shape. This is the highest-impact bug (poisons real production data on Clay's primary library).
5. Triage `bug_003`. Apply the race-fix pattern. Use `/multi:diff-review` on the staged patch.
6. Triage `bug_004`. Use `/multi:decide` for the architecture call.
7. Cleanup: close PR #15, delete the baseline branch.
8. Final canonical 8-target verify-build. Write Session 19 closeout handoff to `docs/sessions/2026-05-23-session-19-closeout-handoff.md`.

---

## References

- **Falsify results** (multi-ask migration): `~/Documents/task-order-decision-communication/falsify-results-on-multi-ask-migration_2026-05-23.md`
- **Multi-ask completion handoff:** `~/Documents/task-order-decision-communication/handoff-multi-provider-phases-1-to-4-COMPLETE_2026-05-23.md`
- **Original multi-ask work-instruction:** `~/Projects/multi-ask/docs/handoffs/handoff-multi-provider-phases-1-to-4_2026-05-22.md`
- **Kiln Session 18 handoff (just-merged):** `docs/sessions/2026-05-22-session-18-handoff.md` (in this repo, on main)
- **Phase 2a Track D-B Android plan:** `docs/superpowers/plans/2026-05-22-phase-2a-track-d-b-android.md`
- **Engram entries to recall:**
  - `kiln/track-d-b-android-shipped` (post-merge state + smoke results)
  - `kiln/track-d-b-android-in-flight` (insurance save mid-Session-17)
  - `kiln/track-d-b-desktop-shipped` (PR #13 reference for the desktop race-fix harmonization)

---

## Closing Notes

The previous session was unusually clean: 5 PRs of Phase 2a Track D shipped in <72hrs, with two-stage subagent review pair catching real bugs (race-ordering in Task 3, `@C.PcmEncoding` TYPE_USE placement in Task 1). The ultrareview baseline run validated the discipline — only 3 normal-severity findings across ~50 commits.

The multi-ask toolset is genuinely impressive (the discipline-as-code stuff: anti-pattern guards inside SKILL.md, composition patterns table, sovereignty-at-matrix). But it shipped with known sharp edges (the falsify findings). The Tier-0 hazards above are real and verified — read them before invoking.

Triage approach for the fresh session: **be surgical**. Each of the 3 bugs has a clear fix shape and a clear wrapper match. Don't over-engineer. Don't bundle the fixes into one mega-PR — three small PRs (or even three commits on a single PR) preserves the audit trail and lets each fix get its own multi-wrapper review treatment.

**End of handoff.**
