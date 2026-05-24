# Session 20 closeout handoff — Kiln Phase 2b kickoff + plan + first sub-flights

**Authored:** 2026-05-24 (post-midnight; session began 2026-05-23 evening).
**For:** Clay direct (next-action prompts) + future CC sessions (context).
**Goal recap:** Phase 2b kickoff per `docs/sessions/2026-05-23-session-20-kickoff-prompt-phase-2b.md`. NOT code-shipping session — plan-locking + first executable sub-flights.

---

## TL;DR

**Status: Plan locked + 4 branches committed locally + 1 device-side handoff awaiting Clay's hands. Push + PR creation deferred to push-time per CLAUDE.md §4a.**

| Order | Branch | Commits | What | Status |
|---|---|---|---|---|
| 1 | `phase-2b/prereq-nowplaying-nav` | 1 (`600803d`) | Voyager Navigator scaffold inside `NowPlayingTab` + clickable title + placeholder `SpecSheetScreen` (per plan §6.1 — F17 mitigation) | Done; subagent-driven (Implementer → Spec ✅ → Code Quality ✅) |
| 2 | `phase-2b/vetting-log-addendum` | 1 | Item 13 addendum (Phase 2b sequencing resolution) + Item 14 NEW (Android JNA-libFLAC port) + Item 15 NEW (DAC null-test rig) — append-only per Decision Log pattern | Done; controller-direct (doc-only work) |
| 3 | `phase-2b/b0-capability-probe` | 2 (`e3f99ac` + `cf1c7f4`) | `BitPerfectCapabilityProbe` + Robolectric tests + `BitPerfectProbeActivity` (debug-build-only) + AndroidManifest entry + defensive `runCatching` wrapper (per plan §7) | **Code done; device-side handoff pending Clay** |
| 4 | `phase-2b/g2-gemini-addendum` | 2 | G2 gate — TWO addendums to research doc: (i) gemini 1/3-panel findings + F24-F28; (ii) codex 1/2-panel triangulation **disagreeing** with gemini on Pixel 10 vendor support + softening F26 + adding F29-F30 | Done; controller-direct (doc-only) |

**Plan artifacts (already in main, untracked, committable separately):**

- `docs/superpowers/plans/2026-05-23-phase-2b-plan.md` — full Phase 2b plan (~640 lines, 15 sections), READ + LOCKED by Clay
- `docs/decisions/2026-05-23-phase-2b-aaudio-wasapi-research.md` — research doc (codex-heavy original; G2 gemini addendum appended on branch #4)
- `docs/sessions/2026-05-23-session-20-kickoff-prompt-phase-2b.md` — kickoff prompt (Clay-authored)

**Engram entries saved this session:**

- `architecture/kiln-phase-2b-sequencing` (Option a' lock, 2026-05-23)
- `architecture/kiln-phase-2b-falsify-integration` (23 failure modes ranked, 11 H×H=9, integration decisions)
- `architecture/kiln-phase-2b-g2-gemini-cross-check` (gemini 1/3 panel; was "Pixel 10 HAL confirmed enabled" — **codex re-fan disputes this**; see G2 codex addendum)
- `kiln/session-20-phase-2b-plan-locked` (session closeout summary)

**PRs opened to origin:**

- #20 — `phase-2b/vetting-log-addendum` — https://github.com/clayboicardi/kiln/pull/20
- #21 — `phase-2b/prereq-nowplaying-nav` — https://github.com/clayboicardi/kiln/pull/21
- #22 — `phase-2b/b0-capability-probe` — https://github.com/clayboicardi/kiln/pull/22
- #23 — `phase-2b/g2-gemini-addendum` — https://github.com/clayboicardi/kiln/pull/23
- #24 (this branch) — `phase-2b/session-20-docs` — opens after this commit

**G2 codex triangulation surprise (post-engram-save discovery):**

The G2 re-fan with claude+codex (codex succeeded; claude timed out) **disputes gemini's "Pixel 10 ships with bit-perfect HAL enabled" claim**. Codex cites primary AOSP framework source + an AF Digitale 2025-10-03 article reporting Pixel Android 14/15 BIT_PERFECT "**not fully operational in daily use**" — classifies Pixel 10 support as "**unconfirmed, do not assume enabled**". This shifts the prior on B0 probe outcome from "likely PROBE_PASS" (gemini-only) to "**genuinely uncertain — could go either way**". Run B0 with explicit awareness that PROBE_FAIL is a realistic outcome triggering the (a)-only fallback per plan §3 G1 unlock condition.

Codex also added 2 new failure modes (F29 — phone-mode rejection; F30 — alarm/ringtone preemption) and softened F26 (silent-rate regression) from "documented" to "potential — requires empirical verification via null-test rig." Plan §4 risk register amendment should integrate F24-F30 with confidence markers (both-confirmed vs single-provider-only).

---

## What just shipped (commit-by-commit)

### Branch 1: `phase-2b/prereq-nowplaying-nav` — Now Playing Navigator scaffold

Commit `600803d`. Files:
- `ui/components/.../nowplaying/NowPlayingTab.kt` — wraps `Content()` in `Voyager Navigator(NowPlayingHomeScreen(player))`
- `ui/components/.../nowplaying/NowPlayingContent.kt` — adds `onTitleClick: (trackId: String) -> Unit = {}` param; title `Text` wears `Modifier.clickable { onTitleClick(item.itemId.value) }`
- `ui/components/.../specsheet/SpecSheetScreen.kt` (new) — placeholder body: `Text("Spec sheet for $trackId")` + back IconButton. Stream A replaces wholesale.
- `ui/components/.../nowplaying/NowPlayingNavigationTest.kt` (new) — Compose UI test (1.013s) asserts title-click pushes SpecSheet, back-button pops to home
- `ui/components/build.gradle.kts` — adds `:audio:dsp` to `desktopTest` classpath (justified: `FakePlatformPlayer` fixture needs `AudioProcessor` type to compile)

Tests: 1 new, passing. Canonical 8-target build green (10s). Did NOT modify `PlatformPlayer.kt` (vetting Item 13 invariant preserved).

### Branch 2: `phase-2b/vetting-log-addendum` — Item 13 addendum + Items 14, 15

Single doc commit. Appended ~148 lines to `docs/decisions/2026-05-18-library-vetting.md`. Items locked:

- **Item 13 addendum:** Phase 2b sequencing resolved → Option (a-prime); Flights H+I deferred to Phase 2c
- **Item 14:** Android JNA-libFLAC port (Stream B-B1, conditional on G1 probe PROBE_PASS) — ~30-50hr; libFLAC 1.5.0 BSD-3 `.so` binaries for 4 ABIs; mirrors desktop `JvmFlacDecoderImpl.kt`
- **Item 15:** DAC null-test acceptance rig (Stream B-B2, conditional on G1 + Item 14) — ~10-20hr; reproducible test harness producing fixture-to-fixture byte-identity gate

Append-only discipline preserved; no prior entries edited.

### Branch 3: `phase-2b/b0-capability-probe` — BitPerfectCapabilityProbe + tests + debug activity

Two commits — initial (`e3f99ac`) + review-finding fix (`cf1c7f4`).

`e3f99ac` files:
- `audio/playback/src/androidMain/.../BitPerfectCapabilityProbe.kt` (new, 99 lines) — `BitPerfectAvailability` enum (4 cases), `BitPerfectProbeResult` data class, `BitPerfectCapabilityProbe(context)` class with `probe()` method. API-34 gate via `Build.VERSION.SDK_INT < UPSIDE_DOWN_CAKE`. API-34+ branch: `getDevices(GET_DEVICES_OUTPUTS)` filter on `TYPE_USB_*` types → `getSupportedMixerAttributes(usbDevice)` filter on `MIXER_BEHAVIOR_BIT_PERFECT`.
- `audio/playback/src/androidHostTest/.../BitPerfectCapabilityProbeTest.kt` (new, 43 lines) — Robolectric `@Config(sdk = [34])`. 2 tests, both passing.
- `app-android/src/main/.../debug/BitPerfectProbeActivity.kt` (new, 56 lines) — Compose activity, debug-build-only intent. Vertically scrollable result display.
- `app-android/src/main/AndroidManifest.xml` (+22 lines) — second `<activity>` block with `MAIN` + `LAUNCHER` intent-filter, label "Kiln Bit-Perfect Probe", TODO comment about future move to `src/debug/`.

`cf1c7f4` follow-up (code-quality review findings):
- `BitPerfectProbeActivity.kt`: `runCatching { probe.probe() }` + `fold(onSuccess, onFailure)` — failure path renders "Probe threw: ..." + message + "Screenshot this for the result-doc." prompt. Critical for B0-T4 — silent crash on real device would defeat the entire point of the activity.
- Date placeholder swap: `2026-05-XX` → `<date>` in two source-code comments (less confusable with real filename).

Tests: still 2/2 passing post-fix. Canonical 8-target build green (5s incremental). Did NOT modify `PlatformPlayer.kt`, DI graphs, or any UI component.

### Branch 4: `phase-2b/g2-gemini-addendum` — Pixel 10 BIT_PERFECT triangulation

Single doc commit. Appended ~238 lines to `docs/decisions/2026-05-23-phase-2b-aaudio-wasapi-research.md`. Findings summary:

**Pixel 10 Pro XL CONFIRMED**: Tensor G5 ships with `AUDIO_OUTPUT_FLAG_BIT_PERFECT` HAL flag ENABLED. Resolves the original (a)-only-advocate dissent risk in user's favor. Capability probe (B0) should return PROBE_PASS for at least one format.

**5 new failure modes for Stream B integration:**
- **F24** — 90-Second Disconnect Bug on Pixel hardware (DAC abruptly disconnects 30-90s into bit-perfect playback)
- **F25** — "Silent" 88.2kHz Bug (non-48kHz-multiple rates fail AAudio backend init)
- **F26** — Android 14 QPR3 silent-failure regression (falsely reports bit-perfect success at wrong rate; reinforces F1 → null-test rig essential)
- **F27** — Hot-plug requires `OnPreferredMixerAttributesChangedListener` (already in plan as B4 work; tactical addition)
- **F28** — Bluetooth 100% unsupported (A2DP enforces codec re-encoding; UI must hard-disable toggle on BT route)

**Phase 2b Option (a-prime) lock NOT changed.** Stream B-B3 plan amendments needed to handle F24-F28; effort stays in existing 40-70hr range.

**Caveat:** 1/3 panel (gemini only — claude + codex timed out at 360s). No cross-LLM triangulation on F24-F28 specifics; some details may be over-fitted to gemini's source set (XDA forum reports). Re-fan with claude+codex when timeouts permit.

---

## Clay's next actions

### Action 1 — B0 device-side execution (REQUIRES PIXEL 10 PRO XL + USB-C-to-AUX dongle)

This is the hard gate for all subsequent Phase 2b Stream B work. Per plan §7 steps B0-T4 through B0-T7.

**Prerequisites:**
- Pixel 10 Pro XL powered on, USB-debugging enabled, connected via USB cable
- Clay's USB-C-to-AUX dongle attached to phone (via USB-C OTG hub or directly — whatever the phone routing supports)
- Branch `phase-2b/b0-capability-probe` checked out

**Step 1 — Build + install the debug APK:**

```powershell
cd C:\Users\chawo\Projects\kiln
git checkout phase-2b/b0-capability-probe
./gradlew :app-android:assembleDebug
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk
```

**Step 2 — Launch the probe activity:**

```powershell
adb shell am start -n com.clayworks.kiln/.debug.BitPerfectProbeActivity
```

The activity will display either:
- The probe result (Android API + Availability + Device + Supported formats), OR
- A "Probe threw: ..." failure block if anything crashed during the probe call

**Step 3 — Screenshot the result + pull to repo:**

```powershell
adb shell screencap -p /sdcard/probe-result.png
adb pull /sdcard/probe-result.png docs/decisions/assets/2026-05-24-bitperfect-probe-pixel10pro.png
```

(Create `docs/decisions/assets/` directory if it doesn't exist.)

**Step 4 — Fill in the result document:**

Create `docs/decisions/2026-05-24-phase-2b-bitperfect-probe-result.md` with:
- Date probe was run
- Device (Pixel 10 Pro XL, Android version + build number from `adb shell getprop ro.build.fingerprint`)
- USB-DAC tested (your exact USB-C-to-AUX dongle make/model — USB Audio Class if known)
- Screenshot embedded
- Verdict: **PROBE_PASS** / **PROBE_FAIL** / **PROBE_PARTIAL** with format list
- Decision: proceed to Stream B-B1, OR drop Stream B per the (a)-only fallback
- Your sign-off

**Step 5 — Commit the result doc + screenshot:**

```powershell
git add docs/decisions/2026-05-24-phase-2b-bitperfect-probe-result.md
git add docs/decisions/assets/2026-05-24-bitperfect-probe-pixel10pro.png
git commit -m "phase-2b(b0): empirical probe result on Pixel 10 Pro XL"
```

**Step 6 — Branch on result:**

- **PROBE_PASS or PROBE_PARTIAL:** Stream B-B1 (Android JNA-libFLAC port) is greenlit. Either continue this session OR hand off to Session 21.
- **PROBE_FAIL:** STOP. Save engram entry recording the empirical failure. Revise plan to drop Phase 2b-B. Ship Phase 2b-prereq + Phase 2b-A only. Re-evaluate Stream B at Phase 2c kickoff.

**Bonus — F24 extension probe (recommended per G2 gemini findings):**

If PROBE_PASS, additionally test the **90-Second Disconnect Bug** before committing to Stream B-B3:
1. With bit-perfect engaged, play a long PCM test tone (>2 min) via any audio test app
2. Watch for the DAC disconnecting at 30-90s
3. If observed, document in result-doc as F24 confirmed → B3 needs explicit detection + auto-fallback per the G2 addendum

### Action 2 — Push + open PRs (asks below)

Four branches are local-only. Per CLAUDE.md §4a, push happens at session close. Per CLAUDE.md risk-actions, asking Clay before push/PR.

Recommended PR order (matches multi-PR discipline; smaller-blast-radius first):
1. PR #20 — `phase-2b/vetting-log-addendum` (doc-only, lowest risk)
2. PR #21 — `phase-2b/g2-gemini-addendum` (doc-only, lowest risk)
3. PR #22 — `phase-2b/prereq-nowplaying-nav` (UI scaffold; 1 new test; isolated)
4. PR #23 — `phase-2b/b0-capability-probe` (Android probe class + debug activity; gates all subsequent Stream B work)

Plus the 3 untracked planning artifacts on main itself (the kickoff prompt, plan doc, research doc) — can land as a separate session-setup commit OR bundled into PR #20.

---

## Outstanding work (deferred from this session)

### Plan §4 risk register amendment

Phase 2b plan §4 should be amended to include the 5 new G2 failure modes (F24-F28). Either:
- (a) Append a `## §4 amendment (2026-05-24)` section to the plan doc — minimal, append-only-style
- (b) Edit §4 in place — cleaner final state but breaks append-only discipline for the plan doc

Recommendation: (a) for now; (b) at Stream B start when the plan gets re-read top-to-bottom anyway.

### G2 re-fan with claude + codex (when timeouts/quotas permit)

The G2 1/3 panel is a structural weakness for the cross-LLM-consensus principle. If claude + codex quotas/timeouts recover later, re-fan the same prompt at `/tmp/phase-2b-g2-prompt.txt` to triangulate F24-F28 specifically. Tier-1 priority is "did claude/codex confirm the 90-second disconnect bug + the 88.2kHz silent failure?" — those are the highest-leverage of the 5 new modes.

### Phase 2b plan §5 file-touch surface refresh

The plan §5 file-touch surface lists `BitPerfectCapabilityProbe.kt` + `BitPerfectProbeActivity.kt` as "Create" entries. Once branch #3 lands on main, those become "Modified" (for future amendments). Minor; plan-doc updates can be append-only at next major phase boundary.

### Phase 2b plan §10 latency-budget amendment

The latency-budget table currently doesn't include explicit handling for F24/F25/F26. Should add:
- F24 detection: underrun count spike OR DAC-disconnect listener — measurable on real device
- F25 silence detection: per-rate output-validation on first frame
- F26 silent-rate-regression detection: `AudioTrack.getPlaybackRate()` vs initialized rate every N frames

These tie directly to the null-test rig (Item 15) acceptance criteria.

### Stream B-A flight detailed plan (write at flight start)

Phase 2b-A (Spec Sheet UI + format-fact backfill) — outline in plan §6.A; detailed bite-sized plan to be written at flight-start using `superpowers:writing-plans`.

---

## Cleanup checklist (do at push-time)

- [ ] **ASK Clay** before pushing — risk-actions rule
- [ ] Verify canonical 8-target build green from each of the 4 fix branches (build at `main` was the last green-baseline; each branch added incremental work; each was individually-green at commit time)
- [ ] Push all 4 `phase-2b/*` branches to origin
- [ ] Create 4 separate PRs in the order specified above
- [ ] Decide what to do with the 3 untracked planning artifacts on `main` (kickoff prompt, plan doc, research doc) — bundle into PR #20 OR commit separately on main
- [ ] After all 4 PRs land: write `kiln/session-20-phase-2b-plan-locked-and-shipped` engram entry summarizing the session

---

## Important things to NOT do

- **DO NOT push without asking Clay** — risk-actions rule applies even at session close
- **DO NOT bundle multiple branches into one PR** — multi-PR discipline (each is independently reviewable)
- **DO NOT proceed to Stream B-B1 (Android JNA-libFLAC port) UNTIL B0 probe-result-doc is committed AND PROBE_PASS** — F11 mitigation (probe-gate enforcement)
- **DO NOT modify `PlatformPlayer.kt`** — vetting Item 13 invariant; new `BitPerfectAudioTrackPlayerImpl` implements the interface verbatim
- **DO NOT add `androidx.*` to `:audio:dsp` or `:audio:visualizer` commonMain** — Concentric Modules invariant
- **DO NOT auto-spawn from this handoff** unless Clay explicitly says so
- **DO NOT execute Stream B-B3 plan sections concerning F24-F28 specifics until either**: (a) the G2 1/3-panel finding is triangulated with claude+codex, OR (b) Clay explicitly accepts the gemini-only basis for those mitigations

---

## References

- **Plan:** [`../superpowers/plans/2026-05-23-phase-2b-plan.md`](../superpowers/plans/2026-05-23-phase-2b-plan.md)
- **Research doc (with G2 addendum):** [`../decisions/2026-05-23-phase-2b-aaudio-wasapi-research.md`](../decisions/2026-05-23-phase-2b-aaudio-wasapi-research.md)
- **Vetting log (with Item 13 addendum + Items 14, 15):** [`../decisions/2026-05-18-library-vetting.md`](../decisions/2026-05-18-library-vetting.md)
- **Kickoff prompt:** [`./2026-05-23-session-20-kickoff-prompt-phase-2b.md`](./2026-05-23-session-20-kickoff-prompt-phase-2b.md)
- **Engram entries:** `architecture/kiln-phase-2b-sequencing`, `architecture/kiln-phase-2b-falsify-integration`, `architecture/kiln-phase-2b-g2-gemini-cross-check`
- **multi-ask run IDs:** research `20260523T235111Z-2448578`, decide `20260524T004623Z-2459773`, falsify `20260524T031936Z-2481880`, G2 `20260524T043141Z-2528900`

---

**End of handoff.**
