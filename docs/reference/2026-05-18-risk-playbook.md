# Risk-Trigger Response Playbook

**Date:** 2026-05-18 (living reference; new risks appended as they're identified)
**Type:** Reference (operational synthesis)
**Source:** Consolidates risks from [plan §9](../superpowers/plans/2026-05-18-kiln-execution-plan.md) + [vetting log](../decisions/2026-05-18-library-vetting.md) + [vertical-slice prep](../scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md) into concrete operational responses.

For each tracked risk, this doc provides: **Detection** (how to notice it firing) + **Triage** (severity classification) + **Response** (ordered concrete steps) + **Recovery** (get back to a working state) + **Document** (logging/commit conventions). Operational discipline aligned with Clay's Compliance-First Architect + Algorithmic Operational Discipline traits.

---

## How to use this playbook

When a session detects a risk has materialized:
1. **Triage first** — read the entry's Triage section to classify severity. Higher severity = block-and-respond; lower = log-and-continue
2. **Run Response steps in order** — they're sequenced for minimum-damage execution
3. **Recover to a working state** before continuing other work
4. **Document** what happened — playbook conventions specify where (commit message, vetting log addendum, session closeout, engram entry)

Severity scale:
- **P0** — production-equivalent: Clay's daily-use Kiln won't play music; commit nothing else until resolved
- **P1** — feature blocker: a specific feature or flight is blocked; switch to other work, fix this within 1-2 sessions
- **P2** — degraded quality: feature works but with caveats; track in vetting log addendum, fix opportunistically
- **P3** — informational: track for trend analysis; no immediate action

---

## R1: FLAC desktop decode fails on a hi-res track

**Source:** Vetting Item 9 + addendum. The JNA + libFLAC bridge is the chosen MVP path; any decode failure threatens the audiophile-credibility commitment.

**Detection signals:**
- MVP Session 4-7 smoke test: any of the 10 reference FLAC files fails byte-equivalence against `ffmpeg -i x.flac -f s24le -` reference
- Runtime: `PlayerState.Error(PlayerError.DecodeFailed(...))` surfaces during playback on a specific track
- User-visible symptom: track stops mid-playback, skips, or produces audible glitches
- libFLAC native error code logged via `FLAC__stream_decoder_get_state` returning non-`STATE_OK`

**Triage:**
- 24-bit / 96+ kHz track fails: **P0** — hi-res support is the audiophile commitment
- Standard CD-quality (16/44) track fails: **P0** — fundamental decode regression
- Edge-case format (8-bit, 32-bit float, multichannel 5.1) fails: **P1** — these are uncommon but expected to work
- Specific corrupt file fails (verified corrupt via `metaflac`): **P3** — file is bad, not Kiln

**Response:**
1. Capture the failing file's metadata: `metaflac --show-streaminfo path/to/file.flac > failure-streaminfo.txt`
2. Capture the libFLAC error state: log `FLAC__stream_decoder_get_state` + `FLAC__stream_decoder_get_md5_checking` values at point of failure
3. Compare against `ffmpeg -i path/to/file.flac -f s24le -` byte stream — if ffmpeg also fails, file is corrupt (P3)
4. Check JNA marshalling: 24-bit PCM is 3 bytes per sample, little-endian, sign-extended. Buffer pool sizing and byte unpacking are common-bug sites
5. Check the `write_callback` registered in `LibFlacBinding`. JNA callback marshalling has subtle conventions; the ABI between Kotlin lambda and C function pointer must match exactly
6. Cross-check against the reference C example at `xiph/flac/examples/c/decode/file/main.c`. If our JNA bridge diverges from the reference flow, that's the bug
7. If JNA marshalling is the issue: fix it. If libFLAC itself misbehaves: file issue at xiph/flac (extremely unlikely — the reference impl is bulletproof)
8. Run full 10-file smoke suite again

**Recovery:**
- If JNA bridge is broken at MVP Session 4-7: scope work as needed. The vertical-slice milestone (Session 7) does NOT exit until FLAC decode works
- If a single edge-case format fails: ship MVP with the limitation documented in a vetting log addendum; revisit at Phase 2a

**Document:**
- Failing-file `streaminfo.txt` committed to `docs/decisions/2026-MM-DD-flac-decode-issue.md` (new entry)
- Engram entry under topic key `kiln/issues/flac-decode/issue-N`
- If P0: tag a known-bad commit with `v0.0.x-flac-broken` to mark the regression baseline

**Prevention next iteration:** add the failing file to the smoke test suite (currently 10 files; expand as edge cases surface).

---

## R2: libFLAC.dll fails to load on startup

**Source:** Vetting Item 9 addendum (native binary bundling pattern). DLL extraction-from-JAR + `System.load(path)` is the load path.

**Detection signals:**
- App startup: `UnsatisfiedLinkError` or JNA `UnsatisfiedLinkError` thrown
- Logged: `NativeLibraryLoader.loadLibFlac() … error: libFLAC.dll not bundled for arch=…`
- Symptom: Kiln Desktop fails to launch, or launches but can't open any FLAC file

**Triage:**
- DLL missing from JAR: **P0** — packaging regression; release is broken
- DLL extracts but `System.load` fails: **P0** — DLL is corrupt OR Windows can't satisfy its deps (e.g., MSVCR140.dll missing on a fresh Windows install)
- DLL loads but `Native.load("FLAC", ...)` fails: **P1** — JNA name resolution issue

**Response:**
1. Verify DLL presence in JAR: `jar tf app-desktop-1.0.0.jar | grep libFLAC.dll` should list the vendored binary
2. If missing: regression in `:audio:playback`'s `resources/` packaging. Check `nativeDistributions` config didn't strip resources
3. If present: extract and inspect: `jar xf app-desktop-1.0.0.jar audio/playback/jvmMain/resources/native/win-x64/libFLAC.dll` then `dumpbin /headers libFLAC.dll` to verify it's a valid PE32+ binary
4. Check the `System.load` path: log the temp-dir path where extraction landed. Permission issues on Windows can fail at `mkdirs()` time
5. Check Visual C++ Redistributable installed on target machine. xiph/flac 1.5.0 ships MSVS 2022 build; needs VC++ 2015-2022 redist (most Windows 10+ have it; verify on a clean VM if doubt)
6. Test on a fresh Windows 11 VM if Clay's machine "just works" but distribution to others fails

**Recovery:**
- Re-vendor the DLL: download fresh from xiph/flac 1.5.0 release, replace, rebuild
- If MSVC redist is the issue: jpackage can bundle the redist via `nativeDistributions { windows { … } }` — investigate
- Worst-case fallback: ship Kiln Desktop with VC++ redist installer alongside MSI

**Document:**
- Issue + fix in `docs/decisions/2026-MM-DD-native-bundling-fix.md`
- If MSVC redist is required: add to scaffold prep §7 native binary vendoring as a documented step

---

## R3: kmpalette 4.0.0 API churns during beta cycle

**Source:** Vetting Item 3. Adoption is at Phase 2a Flight A (6-13 months from session 2). The beta line has been stable from beta01 → beta02 so far; API churn during the betaN window would invalidate that.

**Detection signals:**
- New kmpalette beta release (e.g., 4.0.0-beta03+) introduces breaking API changes in:
  - `Palette.from(imageBitmap).generate()` signature
  - Swatch role field names (vibrantSwatch, mutedSwatch, etc.)
  - `kmpalette-core` module structure
- Compose-MP version compatibility regresses

**Triage:**
- Renames-only (no behavioral change): **P3** — adopt the latest beta at Flight A start
- Algorithm change (different swatch selection): **P2** — may shift Kiln Dynamic palette appearance on test album art
- Module structure break (forces dep graph rework): **P1** — slows Flight A start

**Response:**
1. At Phase 2a Flight A pre-work: query `gh api repos/jordond/kmpalette/releases?per_page=10` and check for breaking-change CHANGELOG entries
2. If breaking changes: read the upgrade notes; estimate adaptation effort
3. If estimate is >8 hrs: invoke the fallback per vetting Item 3 — evaluate (a) pinning the previous 4.0.0-betaN, (b) rolling our own pixel-sampling extractor (~16-24 hrs in Flight A)
4. If estimate is <8 hrs: absorb the migration cost

**Recovery:**
- Adapt to latest beta: usually a 2-4 hr task per minor API change
- Pin previous beta: trivial, but accept whatever Compose-MP version constraints the older beta imposes
- Roll our own: substantial Phase 2a Flight A scope addition; document the architectural switch in vetting log addendum

**Document:**
- Addendum to vetting log Item 3 capturing which path was taken and why
- If rolled our own: new module `:ui:theme/palette` becomes a candidate for Phase 2b Flight G library extraction (silver lining)

---

## R4: Coil 3.x raises minSdk above 21

**Source:** Vetting Item 2 JIT verification. Coil's 3.x line is on a fast cadence and has already raised minSdk once (21 → 23 in some releases). If 3.4.x is 23+, this implicates spec §2 hard lock (platform-target minSdk).

**Detection signals:**
- At MVP Session 4 JIT check: Coil 3.4.0 release notes mention "minSdk now 23" or build fails with manifest-merger error citing minSdk mismatch
- Gradle build error: `compileDebugKotlin` fails with "module requires minSdk 23, app has minSdk 21"

**Triage:**
- Coil 3.4.0 specifically: **P1** — confirmed Pre-MVP pin is broken; revise immediately
- A future Coil 3.x: **P2** — can pin to last 3.x version that supported minSdk 21
- Coil drops minSdk 21 entirely AND we want to support 21: **P0** — fork-or-pin scenario; spec §2 hard-lock conversation with Clay

**Response:**
1. Verify minSdk in Coil 3.4.0's POM: `gh api repos/coil-kt/coil/contents/coil/build.gradle.kts | jq -r .content | base64 -d | grep minSdk`
2. If 23: check which 3.x release was the last to support 21
3. Pin to that release in `libs.versions.toml`; commit the version downgrade
4. If we want 3.4.0+: raise the conversation with Clay — does minSdk 23 still cover Clay's Pixel 10 Pro XL? (Yes — Pixel 10 is Android 14+, so 23 is trivially satisfied for Clay's actual use)
5. If raising minSdk to 23 is acceptable: update spec §2 (this IS a hard-lock revisit triggered by a new variable, exactly the kind plan §9 calls out)

**Recovery:**
- Downgrade Coil version: 5-10 min change
- Raise minSdk to 23: requires Clay's explicit ack of spec §2 revisit; once acked, single-line change in build-logic

**Document:**
- Vetting log addendum to Item 2
- If spec §2 revisit: commit message explicitly cites "spec §2 hard-lock revisit triggered by Coil minSdk bump"

---

## R5: Compose-MP LazyColumn stutters at 40k items

**Source:** Vetting Item 12. Spike runs at MVP Session 1-3; failure scenarios drive Mitigation A (paged loading) or B (sectioned grouping) per the architectural defaults already in vertical-slice prep §3.

**Detection signals:**
- MVP Session 1-3 spike test: 95th percentile frame time during hot scroll > 16.6ms (60fps target)
- Or 99th percentile > 33ms (occasional dropped frame)
- Runtime later: visible jank during library scroll

**Triage:**
- 95p > 33ms: **P0** — must adopt Mitigation A or B before MVP Session 8 ships
- 95p 16-33ms: **P1** — paged loading is the default anyway; spike just confirms it's required
- 95p < 16.6ms but memory pressure measured: **P2** — paged loading still helps memory; cosmetic improvement

**Response:**
1. Capture the spike's frame-time histogram (already mandated in vertical-slice prep §10)
2. Document the result: new vetting log section `docs/decisions/2026-MM-DD-item-12-spike-results.md` (already mandated in scaffold prep §12 Step 14)
3. Adjust Session 8 plan if Mitigation B (sectioned grouping) is needed in addition to A
4. Update plan §3.2 Sessions 8-11 effort estimate if mitigation work expands the work

**Recovery:**
- Paged loading (Mitigation A) is the default architectural choice REGARDLESS of spike result — Item 12 entry calls this explicitly. Spike only confirms whether an un-paged "view all" fallback is also viable
- Sectioned grouping (Mitigation B) — implementing it during Session 8 adds ~4-8 hrs

**Document:**
- Spike results doc commits to `docs/decisions/`
- Plan §3.2 Sessions 8-11 effort revision committed if work expands

---

## R6: Audio device disconnects mid-playback

**Source:** Vetting Item 11 (system integration) — USB DAC unplug, headphone jack pull, Bluetooth headphone power-off, default device switch. The `PlatformPlayer.state` flow must surface a recoverable error.

**Detection signals:**
- Android: `Player.Listener.onPlayerError` fires with `ExoPlaybackException.TYPE_RENDERER` cause; OR `AudioManager` broadcast `ACTION_HEADSET_PLUG` with state=0
- Desktop: `LineUnavailableException` thrown from `SourceDataLine.write`; OR `Mixer` device-removed event
- User-visible: playback stops, no audio output

**Triage:**
- Android with Media3 `setHandleAudioBecomingNoisy(true)`: **P3** — automatically paused; just surface the state to UI
- Desktop with no listener: **P2** — track gets stuck; UI doesn't reflect device change
- Mid-playback crash (no graceful pause): **P1** — bug; player should never crash on device change

**Response:**
1. Android side (covered by Media3): verify `setHandleAudioBecomingNoisy(true)` is set on the ExoPlayer.Builder — it is per vertical-slice prep §4.4. Audio Focus + becoming-noisy should auto-pause
2. Desktop side: implement `LineListener` on the `SourceDataLine` to detect `STOP`, `CLOSE` events. On STOP/CLOSE, transition `PlayerState` to `PlayerState.Error(PlayerError.DeviceUnavailable(...))`
3. Surface a user-visible event (toast/snackbar): "Audio device disconnected; tap to resume on new device"
4. On user reconnect: re-acquire `SourceDataLine` for the new default device; resume from `positionMs` of failed playback

**Recovery:**
- Player state transitions: `Ready(playing=true) → Error → Ready(playing=false)` once new device is open
- Position-preserve: store the failure position to a session-scoped variable; on user "resume" click, seek+play

**Document:**
- Behavior tested in MVP Session 23-25 (system integration); commit the test patterns
- Add an integration test fixture that simulates device disconnect (mock `Mixer` returning failure)

---

## R7: SQLDelight migration fails between releases

**Source:** Schema sketch §6 — `verifyMigrations.set(true)` catches schema drift at CI time; runtime migration also has failure modes.

**Detection signals:**
- CI: `verifySqlDelightMigration` task fails on PR build
- Runtime: app crash on launch after upgrade, `SQLiteException` during `KilnDatabase.Schema.migrate(...)` call
- User-visible: "Kiln won't open after update"

**Triage:**
- CI catch (pre-merge): **P2** — fix migration; don't ship
- Runtime failure on Clay's dev machine: **P1** — back out the change; investigate
- Runtime failure on shipped binary (Phase 2a+): **P0** — data-loss risk; emergency patch

**Response:**
1. CI catch: read the migration verification output. SQLDelight reports which CREATE/ALTER didn't match. Rewrite the `.sqm` migration to match
2. Runtime: capture the database file BEFORE retrying — `xcopy %LOCALAPPDATA%\Kiln\kiln.db kiln-pre-migration-backup.db`
3. Inspect via `sqlite3 kiln-pre-migration-backup.db .schema` to see actual on-disk state
4. Reproduce in a test: copy the .db to `:data:library/src/jvmTest/resources/`, write a test that opens it with the new schema, observe the failure
5. Fix migration; verify against the actual .db; ship a patch release

**Recovery:**
- Pre-migration backup is the recovery seed. If migration is unfixable: instruct user to restore the backup .db, downgrade Kiln, await fix
- Schema rollback: `.sqm` migrations are versioned; rollback to N-1 is a manual `.sql` script run against the broken .db

**Document:**
- Migration-failure incident in `docs/decisions/2026-MM-DD-migration-incident-N.md`
- Engram entry under `kiln/incidents/migration/incident-N`
- If P0: post-mortem in plan §9 risk register; what early warning could have caught it in CI

---

## R8: Windows SmartScreen blocks Kiln MSI on a non-Clay machine

**Source:** Vetting Item 10. No code signing at MVP. SmartScreen reputation must build via downloads — until then, "Windows protected your PC" warning.

**Detection signals:**
- User (Clay or a non-Clay downloader) reports SmartScreen warning on first MSI install
- Telegram or GitHub issue tracker: "Windows won't let me run Kiln"

**Triage:**
- Clay on his own machine: **P3** — known accepted friction
- A non-Clay user trying to install: **P2** — friction deters adoption; revisit code-signing decision
- Multiple non-Clay users report the same: **P1** — reputation isn't building organically; explicit signing needed

**Response:**
1. Confirm the warning: which exact dialog ("Windows protected your PC" vs "Unknown publisher" vs SmartScreen network warning)
2. If "Windows protected your PC" with no "Run anyway" option: SmartScreen reputation is 0; needs a code-signing cert OR many downloads over time
3. Decision point: pay for a code-signing cert?
   - Standard cert ~$300-500/yr (DigiCert / SSL.com / Sectigo) — bypasses "Unknown publisher" but SmartScreen network warning may persist until reputation builds (typically weeks-months)
   - EV cert ~$300-700/yr — bypasses SmartScreen entirely on first run
4. If yes: purchase cert (Clay's spend decision per CLAUDE.md "no spending without approval"); set up cloud HSM for CI signing; integrate `signtool` or `jsign` into the release build
5. If no: document workaround for users — `Right-click → Properties → Unblock` or `Right-click → Run anyway`

**Recovery:**
- Without signing: user education path is the only mitigation
- With signing: each new release auto-signed in CI; users see signed MSI = SmartScreen friendly

**Document:**
- Vetting log Item 10 addendum if signing is purchased
- Plan §3.2 Sessions 26-28 effort revision if signing CI integration is added (~5-10 hrs work)

---

## R9: Pixel 10 Pro XL's SQLite is missing FTS5

**Source:** Vetting Item 6 + schema sketch §8. Sanity check at MVP Session 4. FTS5 has been bundled in Android SQLite since API 21+ but very old AOSP forks may differ.

**Detection signals:**
- MVP Session 4 sanity check: `adb shell sqlite3 :memory: 'CREATE VIRTUAL TABLE t USING fts5(x);'` fails
- Runtime: `SQLiteException: no such module: fts5` when initial `KilnDatabase.Schema.create(driver)` runs FTS5 statements

**Triage:**
- Pixel 10 Pro XL specifically: **P0** — Clay's daily-driver device; type-ahead search must work on it
- Older Android devices (API 21-25): **P2** — out of scope for Clay's daily use, but worth knowing for portability
- Custom ROM scenario: **P3** — user-specific environment

**Response:**
1. Verify the failure: `adb shell sqlite3 :memory: 'CREATE VIRTUAL TABLE t USING fts5(x);'` and observe exit code + stderr
2. Check Android version: `adb shell getprop ro.build.version.release` — Pixel 10 should be 14+; AOSP 14 bundles FTS5
3. If somehow missing: fall back to LIKE-based search (degraded but functional)
   - Schema: drop the FTS5 virtual table; add a regular `track_search` table with `(title, album_name, artist_name, album_artist_name)` columns
   - Query: `WHERE title LIKE '%query%' OR album_name LIKE '%query%' OR …`
   - Performance: ~10-50ms on 39.5k tracks vs <20ms with FTS5; acceptable but degraded
4. Document the fallback in vetting log addendum

**Recovery:**
- LIKE fallback ships at MVP-1.0 if FTS5 is unavailable; revisit at Phase 2a if it earns its keep
- Re-introduce FTS5 if a user installs a SQLite-with-FTS5 build

**Document:**
- Vetting log Item 6 addendum
- Schema sketch §4 update if fallback is shipped

---

## R10: Concurrent rescan triggers UNIQUE constraint failure

**Source:** Schema sketch §3 UNIQUE constraints on `(artist.name_sort, COALESCE(mbid, ''))`, `album(artist_id, name_sort)`, `track.file_path`. Concurrent scans (background scan + user-triggered scan) could race.

**Detection signals:**
- Logged: `SQLiteConstraintException: UNIQUE constraint failed: album.artist_id, album.name_sort`
- User-visible: scan progress UI stalls or shows error
- Library state: some new tracks indexed, others missing

**Triage:**
- Single-user race during incremental scan: **P2** — recover and retry
- Background scan + user-triggered scan running simultaneously: **P1** — should not be possible; locking is wrong

**Response:**
1. The scanner should use a mutex (single-instance lock) — verify `LibraryScanner` impls do
2. If both background and user-triggered scan can fire: route both through a single scan-coordinator that serializes
3. For the constraint failure itself: wrap each track upsert in a per-row transaction with retry; on conflict, log and proceed to next track (the conflicting row exists, that's fine)
4. If the constraint failure is on a single track (file path UNIQUE): a stale row from a previous incomplete scan exists; soft-delete it via `deleted_at_ms` then retry

**Recovery:**
- Scanner retries individual rows on transient conflicts (rare)
- For systematic conflict: run a full rescan with soft-delete-all-then-re-add (slow but clean)

**Document:**
- Test fixture: simulate concurrent scans in `:data:library/src/commonTest/`
- Architectural note in vertical-slice prep §6 about single-instance scanner locking

---

## R11: Phase 2b Flights H+I (AAudio/WASAPI) over-commit relative to actual audio benefit

**Source:** Vetting Item 13 + plan §5. Soft-lock revisit at end of Phase 2a. Flights H+I are ~160-240 hrs combined; only worth building if ExoPlayer/Java Sound produces audible problems during dogfood.

**Detection signals:**
- End of Phase 2a: Clay has dogfooded ExoPlayer/Java Sound for 3-6+ months. Survey:
  - Any audible quality complaints?
  - Latency complaints when scrubbing or skipping?
  - Bit-perfect concern (likely inaudible on USB-C-to-AUX dongle path)?
- If all answers are "no": Flights H+I are over-engineering

**Triage:**
- "No audible problems" answer set: **CUT** Flights H+I. Save 160-240 hrs. Recognize the abstraction work in MVP was still worth it (cleaner architecture)
- "Yes, audible problems" answer set: **PROCEED** with Flights H+I per plan §5 — the abstraction makes this a sane lift

**Response (when at end of Phase 2a):**
1. Conduct the dogfood survey — explicit conversation, written answers
2. If cutting: update plan §5 to mark Flights H+I as deferred/canceled
3. If proceeding: confirm AAudio MMAP API surface hasn't shifted; same for WASAPI

**Recovery:**
- This is not a recovery scenario; it's a soft-lock revisit point. No incident — just a planned decision moment

**Document:**
- Plan §5 update with revisit outcome
- Vetting log Item 13 closeout addendum
- Engram entry under `kiln/phase-2b/flights-h-i-decision`

---

## R12: Pre-MVP-spawned JIT items get forgotten

**Source:** Pre-MVP Research left 8 JIT items carried into MVP (scaffold prep §9 matrix). The risk is a Session-N session simply forgets to check one of them.

**Detection signals:**
- Session start checklist (plan §11): no item references the JIT matrix
- MVP Session 4 starts without confirming Coil minSdk
- MVP Session 4-7 ends without the FLAC empirical smoke test
- MVP Session 23 starts without the Windows SMTC binding decision

**Triage:**
- Specific JIT item missed: **P2** — backfill at the next opportunity
- Multiple JIT items missed: **P1** — Clay's "Rigorous Session Closeout" trait demands the JIT matrix be a session-start ritual

**Response:**
1. At every MVP session start: scaffold prep [§9 JIT-check matrix](../scaffold/2026-05-18-mvp-session-1-prep.md#9-jit-check-matrix-pre-mvp-follow-ups-carried-into-mvp) IS the session-start ritual
2. If a JIT item slipped past its scheduled session: insert it as the next session's Item 1
3. If multiple slipped: dedicate a "JIT-catchup session" before continuing forward

**Recovery:**
- The JIT matrix is the source of truth; never let it drift
- Each JIT resolution gets a vetting log addendum so the trail is complete

**Document:**
- Per-JIT-item closeout: vetting log addendum (e.g., "Item 2 JIT: Coil minSdk = 21 confirmed at MVP Session 4")
- Engram entry per resolution

---

## R13: Heavy session loses context mid-flight

**Source:** Long sessions (~6-8 hrs of dense synthesis work) eventually exceed context window. Pre-MVP Research session 2 ran 8+ hrs and ~40% context with 14 commits — workable but a longer session could lose coherence.

**Detection signals:**
- Context utilization > 60%
- Increasing reference back to earlier work in the same session
- Drift in writing style or invariant adherence

**Triage:**
- Approaching 60%: **P3** — schedule a session-end soon
- Approaching 80%: **P2** — wrap immediately; new session starts after engram + session summary

**Response:**
1. Call `mem_session_summary` proactively with the current session's accomplishments
2. Commit all in-progress work to a stopping point
3. Write a clear "next session starts here" marker in `docs/sessions/`
4. End session

**Recovery:**
- Next session starts with `mem_search` for the prior session's summary
- Reads the session closeout note as the first action

**Document:**
- Session closeout file at `docs/sessions/YYYY-MM-DD-session-N.md` per plan §11

---

## R14: Effort overrun on a flight (>50% over estimate)

**Source:** Plan §9 trigger ("Effort overrun on a flight (>50% over estimate) — flag and discuss adjusting scope").

**Detection signals:**
- MVP Session 4-7 burns 60+ hrs vs the 30-45 hr revised estimate
- Phase 2a Flight A burns 75+ hrs vs the 30-50 hr estimate

**Triage:**
- 50-75% over: **P2** — explicit conversation with Clay about scope adjustment
- >75% over: **P1** — pause; re-estimate; possibly defer some flight scope

**Response:**
1. Halt mid-flight; do not push to "just finish it" — that compounds the overrun
2. Categorize what's consuming time: (a) unforeseen complexity, (b) under-estimated work, (c) scope creep, (d) trap pattern (Architecture-as-Performance-Art)
3. For each category:
   - (a) unforeseen complexity → re-estimate; absorb if reasonable, defer if not
   - (b) under-estimated work → bump estimates in plan §13 for future flights
   - (c) scope creep → revert; ship MVP/flight as originally scoped
   - (d) trap pattern → switch modes; ship the feature first, polish later
4. Bring findings to Clay for the soft-lock conversation if scope adjustment is needed

**Recovery:**
- Plan §13 effort tables updated with new estimates
- If scope adjusted: explicit "what was cut and why" addendum to the flight's plan section

**Document:**
- Session closeout reflects the overrun + categorization
- Plan §13 revisions get a commit message that cites the overrun explicitly

---

## Adding new risks

When a new risk is identified mid-MVP:
1. Add an entry here following the Detection / Triage / Response / Recovery / Document format
2. Add a one-liner to plan §9 Risk-management tracked-risks table
3. Engram-save under topic key `kiln/risks/<short-name>`

---

End of playbook.
