# Session 19 closeout handoff — Kiln triage of Sessions 11-17 ultrareview findings

**Authored:** 2026-05-23, post-triage of `/ultrareview`'s synthetic-baseline findings (PR #15 against `709f314` checkpoint).
**For:** Fresh CC session OR Clay direct.
**Goal recap:** Triage 3 ultrareview findings + ship the SettingsScreen scroll fix that gated audible Track D smoke. Use the newly-shipped `/multi:*` toolset for cross-model validation.

---

## TL;DR

**Status: 4 fixes committed locally. Push + PR creation deferred to session close per CLAUDE.md §4a "push at session-close not mid-session".**

| Order | Branch / commit | Bug | Multi-wrapper used | Outcome |
|---|---|---|---|---|
| 1 | `fix/settings-screen-vertical-scroll` / `4510364` | P1 carry-forward — SettingsScreen Column not scrollable, Backfill button clipped on Pixel 7 Pro | Skipped (one-line trivial) | Done |
| 2 | `fix/bug-003-desktop-rg-race` / `c34521c` | Desktop RG-gain race in `JavaSoundPlayerImpl.startStream` (mirrors Android's PR #14 fix) | Skipped — 5-line literal mirror of already-shipped Android sibling | Done |
| 3 | `fix/bug-002-24bit-pcm-analyzer` / `9021eb6` | `AndroidMediaTrackAnalyzer` silently misreads 24/32-bit PCM as int16 — poisons hi-res FLAC `replay_gain_track_db` | `/multi:falsify` (claude+gemini, codex web-search timeout) → 6 additional concerns integrated beyond handoff's base proposal | Done |
| 4 | `fix/bug-004-saf-mtime` / `e62aa33` | SAF tracks without mtime get fully re-processed every scan | `/multi:decide` (claude+codex consensus → **Option B**; Gemini dissented for Option A — preserved) | **Done** |

**Poisoned-data audit (carried forward from the bug_002 conversation):** Empirically confirmed via `kiln-db-desktop` MCP that the desktop DB has 0/27766 tracks with `replay_gain_track_db` populated. Neither connected Pixel device responded to ADB at the time of triage; Clay marked unsure on Pixel 10 Pro XL but per the prior session's engram entry, Pixel 7 Pro's Analyze button was blocked by the SettingsScreen scroll bug, suggesting no Android-side analysis has ever completed. The `analyzer_version` schema column for retroactive invalidation is **deferred** (see Follow-Ups).

---

## What's actually on disk now

- **Branches (all local, none pushed):**
  - `fix/settings-screen-vertical-scroll` (1 commit ahead of main)
  - `fix/bug-003-desktop-rg-race` (1 commit ahead of main)
  - `fix/bug-002-24bit-pcm-analyzer` (1 commit ahead of main)
  - `fix/bug-004-saf-mtime` (TBD commit ahead of main)
- **Working tree:** `CLAUDE.md` is modified (intentional carry-forward from prior session + bug_003 audioDispatcher-gotcha correction added during Session 19). **NOT committed yet** — should be consolidated into a single `docs(claudemd): Session 19 gotcha updates` commit on main at push time.
- **PR #15:** still OPEN at session start; cleanup task pending.
- **Remote `ultrareview-baseline-s10`:** still present; cleanup task pending.

---

## Multi-ask toolset observations from this session

### Hazards realized in practice

- **#2 hazard "diff-review auto-fans secrets" (Tier 0):** Not exercised this session — `/multi:diff-review` was skipped on bug_003 because the fix was a 1:1 mirror; was overkill for a 5-line change. Still unreviewed in real workflows.
- **#7 hazard "no per-provider timeout in wait" (Tier 1):** Realized. Codex stalled in web-search loop on bug_002 falsify, hit the 240s per-provider timeout cleanly. Falsify synthesized on 2/3 providers without surfacing as a failure — exit 0 with synthesis explicitly flagging "codex failed to respond, threat surface under-explored." Behavior was correct.
- **#11 hazard "OLLAMA_MODEL unset, uses script default":** Realized — multi-doctor shows it as a warning. Non-blocking.
- **#1 hazard "synthesis exfiltration" (Tier 0 verified):** Not exercised. All triage content was non-sovereign (no PHI/credentials/PII). Stayed within the Decision Matrix sovereignty rule.

### Wrapper-specific outcomes

- **`/multi:falsify` on bug_002:** 424s wall (claude + gemini returned; codex web-search-loop timed out at 240s). Synthesis surfaced 15 distinct failure modes; 6 were integrated into the fix; 9 deferred. Output preserved at `/tmp/bug-002-falsify-output/`. Adversarial framing held — no provider lapsed into validation.
- **`/multi:decide` on bug_004:** 132s fan + ~5min synthesis. 3/3 providers responded. Claude + codex converged on Option B (add `has_known_mtime` column via `migrations/2.sqm`); Gemini dissented for Option A (sentinel-0L), arguing both options collapse to size-only detection when mtime is unknown and 0L is platform-idiomatic. Synthesis preserved dissent. Decision locked Option B; engram entry saved at topic `architecture/kiln-saf-unknown-mtime-has-known-mtime`. Output preserved at `/tmp/bug-004-decide-output/`.
- **No `/multi:diff-review` run** — declined as overkill on both ship-eligible fixes.

### Recommendations carried forward to next session

1. **Apply the Tier-0 synthesis-exfiltration fix to `multi-ask.sh:320`** before any sovereign-content workflow. Until then the Sovereignty rule is paper.
2. **Decide Hindsight's fate** (decommission vs resuscitate) per the falsify findings — `mem_search` already routes to Engram on this machine but `CLAUDE.md` still calls Hindsight PRIMARY in some places. Audit.
3. **Smoke-test `/multi:brainstorm`** before it's needed in anger — was untested through Session 18 and remained untested through Session 19.

---

## Follow-ups (deferred during triage)

### Bug_002 follow-ups (from /multi:falsify findings not integrated this PR)

1. **`analyzer_version` schema column + migration** to invalidate rows analyzed by known-buggy versions. Cheap insurance against future analyzer regressions. SQLite ALTER TABLE ADD COLUMN with DEFAULT is fast even at 27k rows. Migration would be `migrations/2.sqm` per the .sqm-naming gotcha. **Scope:** ~30 LOC code change + 1 SQL ALTER + 1 SQL migration file.
2. **Golden-LUFS regression test per encoding branch** (16/24/32/float). Owner's own admission to falsify: "failure modes will be discovered by ear over months." Block analyzer-PR merges on test fixtures + golden LUFS values. The `kiln-flac-golden` skill exists but isn't yet wired to analyzer tests.
3. **Cross-platform RG agreement test** — Android MediaCodec vs desktop JNA-libFLAC produce different RG for the same file? Define tolerance (likely ±0.1 LU) and add a CI check.
4. **Vendor 24-bit-in-32-bit-slot padding defense.** Exynos/QCOM codecs have been observed padding 24-bit PCM into 4-byte slots with leading or trailing pad byte. Hardcoded `bytesPerSample = 3` breaks on those vendors. Needs per-device empirical data (run analyzer on the Pixel 10 Pro XL, log observed buffer sizes vs declared sample/channel count, probe modulus).
5. **Log observed pcmEncoding to telemetry** instead of just logcat info, so the first user with a vendor-padded 24-bit codec produces a discoverable signal. Future-Phase work.

### Bug_004 follow-ups

1. **`(file_size_bytes, file_mtime_ms, display_name)` dedupe pass.** Phase 2a Track B accepts the SAF + MediaStore duplicate-row scenario (UNIQUE on file_path doesn't catch URI vs filesystem path); the future dedupe MUST gate on `has_known_mtime = 1` to avoid sentinel-0 collisions across unrelated cloud-provider files. ADR has this captured.
2. **Hash / periodic-refresh / manual-rescan path for unknown-mtime providers.** Tag edits on cloud-bridged SAF folders that preserve file size are still missed by the new check (acknowledged residual risk; see ADR). Needs explicit refresh trigger UI + a content-hash fallback for cheap providers.
3. **Smoke against a real Drive-backed SAF folder** — the new path isn't exercised by any current test fixture. Need to configure a Drive-mounted folder in SAF and verify the unchanged-check correctly flips when the cloud file content changes vs stays.

### Generic carry-forward (from Session 18 handoff still open)

- **Search tab UX polish** — Clay's 2026-05-22 demo note: "rough at best."
- **Phase 2b start** — Spec Sheet UI + low-latency AAudio/WASAPI engine. 205-310 hrs.
- **`resolveGainLinear` null-effective-db product decision** — currently returns 1.0 when both trackDb and albumDb are null; some players apply pre-amp solo. Open.

---

## Cleanup checklist (do at push-time)

- [ ] Verify canonical 8-target build is green from each fix branch
- [ ] Push all four `fix/*` branches to origin
- [ ] Create 4 separate PRs (preferably with `/multi:diff-review` LGTM badges in the bodies)
- [ ] Single consolidated `docs(claudemd): Session 19 gotcha updates` commit on main with:
  - The audioDispatcher-shielding correction (line ~143)
  - A new gotcha line for the AndroidMediaTrackAnalyzer pcmEncoding handling (16/24-packed/32-int/float; sign-extension; Double-precision normalization for 32-bit)
  - A new gotcha line for the `2147483648f` precision trap (Int.MAX_VALUE.toFloat() rounds UP to exactly 2^31)
- [ ] `gh pr close 15` — close PR #15 WITHOUT merging
- [ ] `git push origin --delete ultrareview-baseline-s10` — drop the throwaway baseline branch
- [ ] Save `engram` entry: `kiln/session-19-triage-shipped` summarising the 4 fixes + the falsify+decide outputs + the deferred follow-ups.

---

## Important things to NOT do

- **DO NOT merge PR #15** — synthetic baseline, never for merging.
- **DO NOT touch the `OCTOPUS_*` env vars in `~/.claude/settings.json`** — load-bearing for claude OAuth despite the legacy name. Rename trap per falsify-results doc Tier 0 #4.
- **DO NOT bundle bug_004's schema migration (if option B wins) into the same PR as bug_002 or bug_003** — schema migrations get their own PR for blast-radius isolation.
- **DO NOT auto-spawn from this handoff** unless Clay says so.

---

## References

- **Inbound handoff:** `docs/sessions/2026-05-23-kiln-triage-with-multiask-handoff.md` (this session's starting point).
- **Multi-ask migration falsify:** `~/Documents/task-order-decision-communication/falsify-results-on-multi-ask-migration_2026-05-23.md`.
- **bug_002 falsify output:** `/tmp/bug-002-falsify-output/` (synthesis.txt has the full ranked failure-mode list).
- **bug_004 decide output:** `/tmp/bug-004-decide-output/`.
- **Engram entries to save at close:** `kiln/session-19-triage-shipped`.

---

**End of handoff.**
