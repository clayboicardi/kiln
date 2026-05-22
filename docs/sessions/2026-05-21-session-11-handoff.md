# Session 11 Handoff — Phase 2a kickoff (pick a track, build)

**Authored:** 2026-05-21 (end of Session 10 — vertical-slice milestone crossed)
**For:** The next Claude session (Clay-driven; can be a fresh cold-context session)
**Goal:** Move from MVP-1.0 (which closed at vertical-slice) into Phase 2a (JAMZ-parity-minus-Tidal). This doc is **track-agnostic** — six candidate Phase 2a tracks are scoped here; Clay picks the lead.

**Why "pick a track":** Session 10 ended at the spec's vertical-slice milestone (FLACs play end-to-end on both platforms via Kiln's own pipeline). The execution plan's Phase 2a has six first-class concerns and they're parallel-friendly — there's no canonical "next." Clay's archetypal framing ("Strategic Decision-Locker") suggests we decide the baseline before starting, so this doc lays out each track at the depth a fresh CC needs to start tomorrow.

---

## 🚀 Pre-flight (first 5 minutes of the session)

**Read order (cold-start):**

1. This file — full read.
2. `docs/sessions/2026-05-21-session-10.md` — Session 10 closeout (covers Task 0 + Task 1 + Task 2 + MILESTONE CROSSED block + Phase 2a kickoff table).
3. `docs/sessions/2026-05-19-session-10-addendum-re-review-fixes.md` — per-finding log of the 12 review fixes from Task 0; useful background but skip on second-read.
4. `CLAUDE.md` — ~135 lines now; project orientation + cumulative gotchas. Note: was NOT modified at Session 10 close (per the "never modify CLAUDE.md without explicit revise-claude-md skill invocation" rule). Several gotchas relevant to Session 11 are in the engram memory layer instead — `mem_search "kiln"` to surface them.
5. *Optional* `mem_search "kiln milestone-crossed"` to warm engram context on what just happened.

**Confirm clean baseline:**

```powershell
cd C:\Users\chawo\Projects\kiln
git log --oneline -5           # expect 3dd2228 (polish commit) at HEAD
git status                     # expect clean tree
./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest
# expect: BUILD SUCCESSFUL in ~5-15s incremental, 68 desktopTest green
# (41 :data:library + 27 :audio:playback)
```

If baseline is dirty or build red — STOP, diagnose, surface to Clay.

**To re-verify the milestone empirically before starting new work:**

```powershell
./gradlew :app-desktop:run
# Click Scan Library → click Play First Track → verify audible playback.
```

The desktop run takes ~10 sec to launch + a 542-second initial scan (27k tracks); subsequent runs reuse the cached `~/.kiln/kiln.db` and scan in seconds.

---

## Where we are (current state, 2026-05-21 PM)

**Repo:** `https://github.com/clayboicardi/kiln` (public, Apache 2.0)
**Branch:** `main` at commit `3dd2228` (post-milestone polish: 4 code-review findings applied). Origin in sync. CI green on all session-10 pushes.
**Build:** Canonical session-validation BUILD SUCCESSFUL with --rerun-tasks.
**Tests:** 68 desktopTest green (41 :data:library + 27 :audio:playback). Was 48 at Session 10 start; +20 regression tests.
**Empirical:** Desktop 27,766 tracks scanned + playing FLACs end-to-end. Pixel 10 Pro XL 11,278 tracks scanned in 2.2s + playing FLACs end-to-end. Both audibly verified.

**Spec checkpoint:** Phase 1 (MVP-1.0) closed. Phase 2a unlocks.

**Session 10's outputs (16 commits total, bf5ddec..3dd2228 — all on main):**

| Commit | Type | What |
|---|---|---|
| 72b18ad..c811b57 | 12 fixes | Task 0 review-fix batch (post-Session-9-review): A=URI roundtrip, B=empty-scanFolders, C=loadQueue mismap, D+E=scanner txn batching, F=Media3 mute, G=Media3 released-guard, H=seekTo return, I=play/pause race, U7/U8/G5 P2 cherry-picks |
| 709f314 | docs | Session 10 addendum + Task 0 closeout |
| c501ff0 | feat | H7 vertical-slice play buttons (MainActivity + Main.kt + arrow.core → api) |
| 91cd89c | fix | Fix J — bundled SQLite via requery for Android FTS5 (Pixel-hardware-only discovery) |
| 9d7d0ec | docs | Session 10 closeout + milestone-crossed update + screenshots |
| 3dd2228 | polish | 4 post-milestone code-review fixes (lazy-init thread safety, PermissionDenied flow, graph hoist, style) |

**Review budget remaining:**
- `/ultrareview`: **1 of 3 credits left**. Recommended use: spend on the LARGEST post-Phase-2a code-surface change rather than the immediate next one — credit ROI is proportional to volume of new code.
- Gemini Code Assist: unlimited via the GitHub App's auto-review on every PR.

---

## Six Phase 2a tracks — pick one to lead with

The execution plan (`docs/superpowers/plans/2026-05-18-kiln-execution-plan.md`) frames Phase 2a as "JAMZ-parity-minus-Tidal." These six are the first-class concerns. They're roughly parallel; lead with whatever drives the most user-facing value or unblocks the most downstream.

### Track A — Settings UI (MVP Sessions 26-28 per plan)

**Scope:** Compose-Material3 settings screen. Editable scan folders (replaces hardcoded `D:\tiddl` + `~/.kiln`), default output device, theming toggle (light/dark/system), debug logs surface, "Rebuild library" button.

**Why now:** Gates Track B (the SAF picker needs a place to live). Also gates real-user testing — without it, every fresh-clone machine and every non-Clay user is stuck with `D:\tiddl`.

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

**Spec / scaffold pointers:**
- `docs/superpowers/plans/2026-05-18-kiln-execution-plan.md` §3 (MVP Sessions 26-28)
- `docs/decisions/2026-05-18-sqldelight-schema-sketch.md` §6 (Settings table sketch — unbuilt)
- `app-android/.../AndroidAppGraph.kt` + `app-desktop/.../DesktopAppGraph.kt` — provider hardcodes to replace with Settings-read paths

**Done means:** Both apps can pick their scan folder via a UI control; the choice persists across restarts via the new Settings table; existing `D:\tiddl` becomes a default only when no setting is stored.

### Track B — SAF folder-picker (Clay's H8 request, banked in Phase 2a)

**Scope:** Android-only. `ACTION_OPEN_DOCUMENT_TREE` intent → user picks a folder in the system file picker → `contentResolver.takePersistableUriPermission` to preserve grant across process restarts → extend `AndroidMediaStoreScanner` to enumerate via `DocumentsContract.buildChildDocumentsUriUsingTree` for the user-picked roots alongside the canonical MediaStore query.

**Why now:** Clay's slimmed-down Pixel library happened to live in MediaStore-canonical paths, but the moment a user has music in a non-canonical location (Download/, sideloaded SD card, Termux mount, etc.), MediaStore-only misses it. Future-proofs Android scanning.

**Effort:** ~4-6 hrs. Touches `:app-android` + `:data:library/androidMain/.../scan/`. Includes adding the Settings entry to track picked-folder URIs.

**Spec / scaffold pointers:**
- `engram` topic `kiln/saf-folder-picker-phase-2a` — full proposal context Clay stated at H8 time
- AOSP docs: `ACTION_OPEN_DOCUMENT_TREE` + persistent URI grants
- `data/library/src/androidMain/kotlin/.../scan/AndroidMediaStoreScanner.kt:117` (`queryAudioMediaCursor`) — needs extension to also walk SAF document trees

**Done means:** Settings UI has "Add Music Folder" button on Android; tapping it opens the document picker; selected folder's tracks show up in the next scan alongside MediaStore.

### Track C — Proper UI: browse / now-playing / queue (the visual milestone)

**Scope:** Replace the dev-affordance Scan + Play buttons with the actual app UI:
- **Library browse**: LazyColumn of tracks with album art (Coil), swappable views (album/artist/genre via Voyager Navigator)
- **Now-playing**: the spec's Fluid Canvas FFT visualizer (per Clay's engram-noted vision)
- **Queue**: re-orderable list with current track highlighted
- Tie all three into a coherent Voyager/Circuit navigation tree

**Why now:** Largest visual payoff. Sets the brand. Demonstrates the architecture's UI-readiness. Most aligned with Clay's "Brand-Anchored Development" preference.

**Effort:** ~12-20 hrs. Significant. Touches `:ui:components`, `:ui:theme`, `:app-*`, possibly Circuit codegen wiring.

**Spec / scaffold pointers:**
- `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md` §6 (UI architecture)
- `docs/scaffold/2026-05-18-mvp-session-1-prep.md` §8 (UI module skeletons — exist but empty)
- `mem_search "kiln fluid canvas"` for the visualizer vision

**Done means:** Tapping Kiln opens a real browse view (no Scan/Play buttons visible by default); tracks show with art; tapping plays + opens Now-Playing; queue is visible + manipulable.

### Track D — ReplayGain on the playback path

**Scope:** Apply scanner-captured `replay_gain_track_db` / `replay_gain_album_db` / `replay_gain_track_peak` / `replay_gain_album_peak` values as a linear gain pre-line-write. Mode toggle (track vs album vs off) in Settings. Peak-limiting on top.

**Why now:** Audiophile-credibility quick win. The data is ALREADY in the schema and populated by the scanner (per `LocalLibrarySourceMappers.toPlayable`'s ReplayGain mapping). Pure consumption work; no new pipeline.

**Effort:** ~4-6 hrs. Touches `:audio:playback` (both platforms — JavaSoundPlayerImpl's `applyGain`/`startStream` path on Desktop; ExoPlayer's volume + a custom RenderersFactory on Android) + Settings UI for the mode toggle.

**Spec / scaffold pointers:**
- `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md` §4 (audio pipeline — ReplayGain in the AudioProcessor chain)
- `data/library/.../LocalLibrarySourceMappers.kt:81-94` — ReplayGain already populated on Playable
- Vetting log Item 14 (ReplayGain hard-lock per spec)

**Done means:** Playing the same album with ReplayGain enabled vs disabled produces audibly-different perceived loudness (and matches reference player behavior). Settings exposes the mode.

### Track E — MediaSessionService binding on Android

**Scope:** Wire `Media3ExoPlayerImpl`'s existing `MediaSession` (already constructed at line 101) into a proper `MediaSessionService` so the OS sees Kiln as a media app (lockscreen controls, BT headset, Android Auto Future, headphone gestures).

**Why now:** Sets the bar that "Kiln is a proper Android music player," not a toy app. Required for the buyer-grade brand.

**Effort:** ~3-5 hrs. New Service class in `:app-android`, manifest declaration, lifecycle binding to the existing graph.

**Spec / scaffold pointers:**
- Vetting log Item 11 (Media3 hard-lock; MediaSession constructed but service binding deferred)
- AndroidX Media3 docs: `MediaSessionService` + `MediaLibraryService` examples
- `audio/playback/src/androidMain/kotlin/.../Media3ExoPlayerImpl.kt:97-101` — MediaSession instance already created

**Done means:** Pixel lockscreen shows current Kiln track + play/pause controls. BT headphone play/pause buttons work. Notification surface for foreground playback.

### Track F — CI desktopTest gate

**Scope:** Promote `:data:library:desktopTest` + `:audio:playback:desktopTest` into `.github/workflows/build.yml` as a CI job that runs on every PR. Currently CI only runs the assemble tasks; the 68 tests we wrote in Session 10 don't gate merges.

**Why now:** Smallest scope-to-value of any track. Closes the "structural CI gap" noted in the Session 10 handoff. Catches regressions before they land. Probably bisects future bugs faster.

**Effort:** ~1-2 hrs. Pure CI YAML work + maybe a small fixture-resource handling tweak if Gradle test reports don't surface cleanly in GH Actions.

**Spec / scaffold pointers:**
- `.github/workflows/build.yml:28` — current CI shape; runs `:app-android:assembleDebug` + `:app-desktop:assemble` only
- /ultrareview U5 (PR #2) flagged this as structural; doc-deferred at Session 10 with this exact note

**Done means:** Every push to main triggers the same canonical-validation build that Clay runs locally. PRs with broken tests fail CI before they merge.

---

## Recommendation matrix

| Track | Effort | User-facing payoff | Unblocks | Risk |
|---|---|---|---|---|
| A. Settings UI | 6-10h | Medium (replaces hardcoded paths) | B, C (both need Settings) | Medium — touches storage layer |
| B. SAF picker | 4-6h | Low-Medium (Android-specific) | (needs A first) | Low |
| C. Proper UI | 12-20h | **HIGH** (the visual milestone) | UX validation, brand | High — biggest scope |
| D. ReplayGain | 4-6h | Medium-High (audiophile-credible) | (independent) | Low — data already in schema |
| E. MediaSession | 3-5h | High (proper Android app) | (independent) | Low-Medium |
| F. CI test gate | 1-2h | Low (developer hygiene) | (independent) | None |

**My read for Clay:** **Track F first as a 1-2 hr warm-up** (zero risk, immediate hygiene win, sets up CI for everything that follows). Then **Track A** (gates B and C, unlocks per-machine usability). Then **Track C** (the visual milestone — best brand fit). D + E parallelize well with C.

If you want to feel the win quickly: **Track D (ReplayGain)** — the data's already there, the change is targeted, and the audible difference is immediate.

---

## Open diagnostic carried forward from Session 10

**Desktop "track repeated" observation** (1-line; investigate if it recurs):

When Clay played a single track on Desktop at Session 10's H7 audible verification, he reported it "played through then repeated." The current code's `nextIndexOrNull` returns null for `items.size == 1 + RepeatMode.Off + currentIndex == 0`, so `advanceOnEof` SHOULD set `_state.value = PlayerState.Idle`. Code review (mine) didn't find a path that loops.

**Most likely:** Clay re-clicked Play First Track without noticing, or perceived the JavaSound drain-tail-then-silence as "restart." Not a confirmed bug.

**To trace if it recurs:** enable trace logging on the `JavaSoundPlayer` Kermit tag and watch for the sequence `playback loop → frames.collect ends → drain → advanceOnEof → nextIndexOrNull → null → state Idle`. If state actually does flip to Idle, the observation was a user-perception thing.

Priority: low. Don't chase unless it surfaces again with reproducible steps.

---

## Things deferred from Session 10 (don't re-discover)

These were explicitly deferred or chosen-not-to-fix in Session 10. Re-deferring them here so a fresh CC doesn't re-investigate.

- **P2-1**: Duplicated `scanLibrary` / `playFirstTrackFromBrowse` helpers between MainActivity.kt and Main.kt. ~10 lines each. Honest copy at MVP scale. Extract when Track C lands and a shared `:ui:components` Compose surface absorbs both.
- **U3** (FlacFrameReader.sampleNumber wrong for last frame of fixed-blocksize streams) — affects positionMs accuracy on the terminal frame (250ms window). Real but low-leverage; needs careful FLAC spec interpretation. Phase 2a-or-later.
- **U13** (FlacFrameReader supports only 16/24/32-bit; FLAC spec allows 4-32) — no real-world FLAC < 16-bit. Spec-compliance polish.
- **G4** (NativeLibraryLoader createTempDirectory + deleteOnExit clutter) — Phase 2a alongside Settings UI's app-data-dir convention work.
- **withHostTest warning** — Android KMP plugin warns commonTest doesn't run on Android host tests. Currently only desktopTest runs commonTest. Phase 2a could enable hostTest for full multiplatform coverage, but the warning is benign.
- **Hardcoded D:\\tiddl in Main.kt** — explicitly the canonical placeholder until Track A lands. CLAUDE.md gotcha documents this.

---

## How to start the next session

1. `cd C:\Users\chawo\Projects\kiln`
2. Read this handoff doc first.
3. Read `docs/sessions/2026-05-21-session-10.md` for the milestone-crossed context.
4. *Optional* `mem_search "kiln"` to surface engram context for any topic.
5. Pick a Track (F → A → C is the recommended order; D for a fast audible win; B/E independent).
6. For the chosen Track, scope it via TodoWrite-style task breakdown, then implement.
7. Push at session-close per CLAUDE.md. Single coherent commit batch.
8. Write a Session 12 handoff doc if the Track isn't done in this session.

**Estimated total Session 11 effort:** depends on the picked Track. F = 1-2h ("today's warm-up"). A or D = ~half a day. C = a multi-session arc.

---

## ✅ Session 11 success criteria (track-agnostic)

Whichever Track is picked, the session ends with all of these true:

- [ ] Canonical session-validation build SUCCESSFUL.
- [ ] All existing 68 desktopTest tests still pass (no regression).
- [ ] New code has its own test coverage (commonTest preferred when target-agnostic, target-specific test set otherwise).
- [ ] D:\tiddl smoke 10/10 still decodes (regression-check).
- [ ] All commits pushed to `origin/main`; CI green.
- [ ] Session 11 closeout doc OR Session 12 handoff doc written.
- [ ] Engram memory updated for any new gotcha / decision / pattern.

---

## 📋 Copy-paste prompt for the next session

```
Read docs/sessions/2026-05-21-session-11-handoff.md and execute it as your prompt
for this session. Pick one Phase 2a Track to lead with (F → A → C is the
recommended order if undecided), then scope + implement.
```

That's it. The handoff's Pre-flight + Read order + Track descriptions + Success criteria are self-contained.

---

**End of Session 11 Handoff.** Phase 1 closed at vertical-slice milestone. Phase 2a's six tracks are scoped and parallel-friendly. Clay picks; next CC builds.
