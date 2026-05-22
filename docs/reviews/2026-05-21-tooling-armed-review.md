# Kiln Project Review — 2026-05-21

**Reviewer:** ClaydeClaw (Claude Code, Opus 4.7 1M)
**Scope:** End-to-end adversarial audit; **findings only, no source modifications.**
**Baseline:** `main` @ `4246ff1`, BUILD SUCCESSFUL (1932ms), 69/69 desktopTest pass (41 :data:library + 28 :audio:playback; 1 GoldenCorpusTest auto-skips by design).
**Effort spent:** ~2h 30m (this session).
**Hot bugs found:** none — no live fires.

---

## TL;DR

1. **Track D (ReplayGain) premise is empirically false.** The Session 11 handoff says ReplayGain "is ALREADY in the schema and populated by the scanner." Live DB query against `~/.kiln/kiln.db`: **0 of 27,766 tracks have any of `replay_gain_track_db / album_db / track_peak / album_peak` populated**. Scanner code paths exist (JvmFilesystemScanner.kt:370-373) but produce 0 hits across Clay's library. Track D's 4-6h estimate is wrong by ~2-3×; needs scan-side probe + fix before consumption. **P1.**
2. **`parseChannels` accepts garbage Long values** — 340 AAC tracks have `channels = -14`. ScanInternals.kt:36's fallback only catches null parses; "-14" parses as a valid Long. One-line fix; latent crash class for any future consumer that touches `track.channels` without validation. **P1.**
3. **No androidTest source set anywhere in the project** — already flagged by Session 10 recap §12 as a Phase-2a-kickoff structural priority; Phase 2a is now kicking off and it's still deferred. The next FTS5-class bug class will surface only at Pixel install. **P1.**
4. **JNA 5.14.0 is ~17 months stale** (Maven Central shows 5.17.0 / 2025-03-16; Session-10 recap referenced 5.18.1 as bump candidate). JNA sits on the libFLAC callback path which is the most fragile boundary in the codebase. **P1.**
5. **Cross-platform test asymmetry**: JavaSoundPlayerImpl has 12 tests; Media3ExoPlayerImpl has 0. LocalLibrarySource (the single MusicSource impl, 11 BrowseScope branches) has 0 tests. Both gaps are testable today. **P1.**
6. **Code hygiene is exceptional** for a project this size: 1 TODO total in 8 modules, 0 FIXME/XXX/HACK, all 12 Phase-deferred comments explicitly tagged. Architectural invariants from spec §3 are upheld in production code (Source Protocol clean, PlatformPlayer engine-agnostic, Concentric Modules gated by build config). **Strong signal.**
7. **Skiko version mismatch warning prints on every desktop build**: `io.coil-kt.coil3:coil-core-jvm:3.4.0` declares `skiko:0.9.22.2` → Compose-MP 1.11.0 force-upgrades to `0.144.6`. JetBrains note: "may lead to compilation errors or unexpected behavior at runtime." Currently noise, real risk. **P2.**

---

## Methodology

**Tools used:** Read / Glob / Grep (filesystem & code) · `kiln-verify-build` skill (baseline build) · `kiln-db-desktop` MCP (live SQL diagnostics on `~/.kiln/kiln.db`) · `gh api repos/<org>/<repo>/releases?per_page=N` (date-verified dep freshness, no training-data guesses) · Maven Central search API (JNA, jaudiotagger latest) · engram for axis checkpoints.

**Axes covered:** all 9 from the brief — architectural integrity (§3 invariants), build & dependency health, data layer integrity via MCP, test coverage gaps, spec/plan/code drift, Phase 2a track readiness, debt scan (TODO/FIXME/Phase markers), security & data-loss surface, tooling sufficiency.

**NOT reviewed (deliberate scope cut):**
- Compose UI behavior — there are no Compose surfaces beyond the H7 dev-affordance buttons in MainActivity.kt + Main.kt. UI review earns its keep at Track A/C landing.
- kotlin-lsp symbol analysis — upstream analyzer deferred (`docs/decisions/2026-05-21-tooling-recommendation.md` addendum). The brief explicitly prohibits LSP operations.
- Property-based / mutation testing review for `:audio:dsp` math — module is empty.
- Performance benchmarking — no JMH harness exists yet (MVP Session 16+ work per plan §7).
- Re-raising items in `docs/sessions/2026-05-19-session-10-addendum-re-review-fixes.md`. NEW instances of the same class ARE in scope (and U2 / G4 derivative findings are noted as such).

---

## Severity definitions

- **P0 Critical** — data loss, security exposure, build-breaking. Fix BEFORE Phase 2a starts. *(This review finds zero P0s.)*
- **P1 High** — architectural debt that compounds. Fix DURING Phase 2a, before the relevant track's final commit.
- **P2 Medium** — opportunistic. Fix when adjacent code is touched.
- **P3 Low** — nice-to-have. Backlog only.

---

## Findings by severity

### P0 (count: 0)

No P0 findings. The Session 9 + Session 10 review/fix batches did their job — every previously-known data-loss bomb (empty scanFolders → wipe library; FTS5 delete-all outside transaction; URI roundtrip producer/consumer split) is closed. No live fires.

---

### P1 (count: 6)

#### P1-1: Track D's premise is invalidated — 0% ReplayGain coverage in production scan

- **Location:** `data/library/src/desktopMain/.../scan/JvmFilesystemScanner.kt:370-373` (Desktop extraction); `data/library/src/androidMain/.../scan/AndroidMediaStoreScanner.kt:201-204, 233-236` (Android — intentionally hardcoded null); `docs/sessions/2026-05-21-session-11-handoff.md` Track D framing.
- **Evidence:**
  ```sql
  SELECT COUNT(*) AS live_tracks,
         SUM(CASE WHEN replay_gain_track_db IS NOT NULL THEN 1 ELSE 0 END) AS rg_track_db_set,
         ...
  FROM track WHERE deleted_at_ms IS NULL;
  -- live_tracks=27766, rg_track_db_set=0, rg_album_db_set=0,
  --                   rg_track_peak_set=0, rg_album_peak_set=0
  ```
  Scanner code DOES attempt extraction:
  ```kotlin
  // JvmFilesystemScanner.kt:370-373
  replayGainTrackDb = tag?.getFreeFormOrNull("REPLAYGAIN_TRACK_GAIN")?.parseReplayGainDb(),
  replayGainAlbumDb = tag?.getFreeFormOrNull("REPLAYGAIN_ALBUM_GAIN")?.parseReplayGainDb(),
  replayGainTrackPeak = tag?.getFreeFormOrNull("REPLAYGAIN_TRACK_PEAK")?.toDoubleOrNull(),
  replayGainAlbumPeak = tag?.getFreeFormOrNull("REPLAYGAIN_ALBUM_PEAK")?.toDoubleOrNull(),
  ```
  …but produces 0 hits across 27,766 scanned FLAC + AAC tracks.
- **Why P1:** Track D's session-11 handoff frames it as "pure consumption work; data ALREADY in the schema and populated by the scanner." That's empirically false. The actual scope is (a) probe a known-RG-tagged FLAC via `metaflac` / `mediainfo` to confirm tags exist in Clay's library; (b) diagnose why `getFreeFormOrNull` produces 0 hits (likely needs format-specific key lookup — VorbisComment uses the bare key while jaudiotagger may need a different accessor for it on FLAC's VorbisCommentTag class); (c) full library re-scan; (d) THEN the consumer-side gain application Track D actually plans for. Track D's 4-6h estimate is too low; realistic 8-14h.
- **Recommended action:** Before scheduling Track D, run a 30-minute probe: `metaflac --list "$KNOWN_TAGGED_FLAC"` to confirm the tags exist on disk, then a minimal Kotlin reproducer that opens the same file via jaudiotagger and tries `vorbisCommentTag.getFirst("REPLAYGAIN_TRACK_GAIN")` vs `getFreeFormOrNull(...)` vs `getFirst(FieldKey.??)`. The result of the probe determines whether Track D is "fix the lookup + re-scan + consume" (likely) or "Clay's library never had RG" (unlikely given his JAMZ history).
- **Blast radius:** Track D is one of 6 Phase 2a tracks. Mis-scoping it doesn't block other tracks, but commits effort estimates to a wrong baseline. The bigger downstream concern: if jaudiotagger's RG accessor needs replacement, that's :audio:playback-adjacent metadata work + a re-scan of 27,766 tracks before any consumer-side work earns audible value.

#### P1-2: `parseChannels` accepts negative integers from jaudiotagger

- **Location:** `data/library/src/commonMain/.../scan/internal/ScanInternals.kt:30-37`.
- **Evidence:**
  ```kotlin
  internal fun parseChannels(channels: String?): Long = when {
      channels == null -> 2L
      channels.equals("Mono", ignoreCase = true) -> 1L
      channels.equals("Stereo", ignoreCase = true) -> 2L
      channels.contains("5.1") -> 6L
      channels.contains("7.1") -> 8L
      else -> channels.trim().toLongOrNull() ?: 2L          // <— accepts "-14"
  }
  ```
  Live DB sample of `WHERE channels <= 0`:
  ```
  id=3515  D:\tiddl\Bob Marley & The Wailers\Keep On Moving Trilogy CD3\Hypocrites Dub - Original.m4a  channels=-14
  id=3516  ...Mellow Mood Dub - Original.m4a                                                          channels=-14
  ...
  ```
  340 of 27,766 live tracks (1.2%) have `channels = -14`. Every affected track is a `.m4a` (AAC) file under one Clay-curated folder. jaudiotagger's AAC header-channels accessor returns a string that parses as the bare negative integer "-14" — fallback never fires.
- **Why P1:** This is a latent crash class. `track.channels INTEGER NOT NULL` in the schema doesn't prevent negative values. Java Sound rejects an `AudioFormat` with non-positive channels with `IllegalArgumentException` from `SourceDataLine.open`, which the current play path doesn't recover from cleanly. The 340 tracks happen to be AAC (which Desktop can't decode anyway — no AAC decoder in Kiln), so the crash hasn't been observed yet. The moment Phase 2b adds AAC decode OR the moment a UI consumer surfaces "X channels" in a tracklist, the garbage propagates.
- **Recommended action:** One-line fix:
  ```kotlin
  else -> channels.trim().toLongOrNull()?.takeIf { it in 1..32 } ?: 2L
  ```
  Add a ScanInternalsTest case covering negative + zero + huge-int inputs. Re-scan to clean the existing 340 rows OR run a one-shot `UPDATE track SET channels = 2 WHERE channels NOT BETWEEN 1 AND 32` migration.
- **Blast radius:** Defensive validation at the parser boundary. No consumer currently depends on positive channels, but the boundary should hold.

#### P1-3: No androidTest source set anywhere — Session 10 anti-pattern #2 still active

- **Location:** Absent — `find . -path "*/src/*Test*"` returns only commonTest and desktopTest directories. AndroidMediaStoreScanner.kt, Media3ExoPlayerImpl.kt, KilnApplication.kt, AndroidAppGraph.kt — all untested.
- **Evidence:** Session 10 recap §12 anti-pattern #2 explicitly says:
  > "Do NOT defer 'androidTest source set' past Phase 2a kickoff. The FTS5 bug stayed latent for 5 sessions because there was no integration test running against a production-equivalent SQLite. The right time to add androidTest was Session 6 (FTS5 schema landing); the next-best time is Phase 2a Session 1. Treat as a structural prereq, not a polish item."
- **Why P1:** Recurring class of risk. The Session 10 fixes proved the cost: every Android-side P0 (FTS5 missing, MediaStore quirks, lazy-init thread safety, ARM64 osArch detect) surfaces only at Pixel install time. Once Track A, B, or E ships Android code, the next bug class lands the same way unless the source set exists.
- **Recommended action:** Add `androidUnitTest` source set (host-side JVM, faster) OR `androidTest` (instrumented, slower but full). Bundled SQLite via `RequerySQLiteOpenHelperFactory` from `:app-android`'s Fix J makes host-side test of FTS5 schemas viable. ~6-10h initial setup; ~1-2h per Android class going forward.
- **Blast radius:** Test infra is multiplicative across all Android-side P1 findings below (Media3ExoPlayerImpl untested, AndroidMediaStoreScanner untested). Closing this gap once makes the downstream coverage findings tractable.

#### P1-4: JNA 5.14.0 is 17 months stale on the load-bearing FLAC bridge

- **Location:** `gradle/libs.versions.toml:54` — `jna = "5.14.0"`.
- **Evidence:** Maven Central search confirms 5.17.0 (2025-03-16), 5.16.0 (2024-12-22), 5.15.0 (2024-09-15) all released since Kiln's 5.14.0 pin. Session 10 recap referenced 5.18.1 (2025-09-30) as a "callback thread-mapping improvements + Structure-constructor deadlock fix directly relevant to Kiln's JvmFlacDecodedStream callback-GC pattern" candidate. The 5.18.1 figure may be from a snapshot or pre-release; Maven Central tops at 5.17.0 as of the freshness check today.
- **Why P1:** JNA is on the libFLAC bridge — CLAUDE.md flags this as the most fragile boundary in the codebase ("JNA callbacks need strong references for the lifetime of the native handle"). Each 17 months of skipped patch releases adds latent regression risk and forgoes documented callback / structure fixes.
- **Recommended action:** Bump to 5.17.0 (lowest-effort safe step) before Phase 2a Track D or any other audio-path work. Verify with `kiln-flac-golden` skill against Clay's library (already 10/10 in the smoke test). If `kiln-flac-golden` confirms parity, lock to 5.17.0; the version bump is a one-line change in libs.versions.toml.
- **Blast radius:** Affects every Desktop FLAC playback. Bump only after a known-good rollback path is taped down (a tagged commit lets `git revert` undo it if a regression surfaces).

#### P1-5: Media3ExoPlayerImpl is entirely untested

- **Location:** `audio/playback/src/androidMain/.../Media3ExoPlayerImpl.kt` — no corresponding androidTest or androidUnitTest source set exists.
- **Evidence:** JavaSoundPlayerImpl has 12 tests in `audio/playback/src/desktopTest/.../JavaSoundPlayerImplTest.kt`. Its Android counterpart shares the same shape (5 StateFlows, 12 suspend methods, 4 ExoPlayer listener callbacks, released-state guard) and absorbed the same fix classes during Session 10 (U1-android port, U4 setMuted feedback, U6 released guard, U9 seekTo return-check). All eight fix sites in Media3ExoPlayerImpl are uncovered by tests.
- **Why P1:** Cross-platform asymmetry of test coverage. Every Session 10 fix on the Desktop side had a parallel Android edit; the Android edits were verified by code-review-agent and empirical H8 hardware-test only. The next regression class will repeat the cycle unless the test surface closes.
- **Recommended action:** Once P1-3 (androidUnitTest source set) is in place, port JavaSoundPlayerImplTest's 12 cases to Media3ExoPlayerImplTest with appropriate mocks/Robolectric shadows for ExoPlayer. Estimated 8-12h depending on Media3 mockability.
- **Blast radius:** Same-class regression risk on Android-side player. Failures surface only at Pixel install.

#### P1-6: LocalLibrarySource has no tests

- **Location:** `data/library/src/commonMain/.../source/LocalLibrarySource.kt` — only impl of MusicSource, ~120 lines, 11 BrowseScope branches in `browse(scope)`, FTS5 search path, getPlayable() ItemId namespace contract. Companion `LocalLibrarySourceMappers.kt` IS tested (13 mapper tests), but the source itself isn't.
- **Evidence:** `find data/library -name "LocalLibrarySource*Test*"` returns nothing. Only `LocalLibrarySourceMappersTest.kt` exists.
- **Why P1:** The 11-branch `browse(scope)` is the read API for the entire UI surface coming in Phase 2a Track C. Each branch corresponds to one SQLDelight query (`selectAll`, `selectAllOrderedByArtistThenAlbum`, `selectByAlbum`, etc.). A regression in any of them is invisible until UI surfaces start consuming. The Session 11 handoff carries forward CLAUDE.md gotchas about ItemId namespacing (numeric vs "album:"/"artist:"/"playlist:" prefix) and `getPlayable()`'s ItemNotFound fallback for container kinds — all of this is contract-only documentation today, not test-enforced.
- **Recommended action:** In-memory `JdbcSqliteDriver` + `KilnDatabase.Schema.create()` in commonTest can build the schema fresh per test. Cover all 11 BrowseScope branches + search sanitize + getPlayable's 3 outcomes (tracks → Right, container ItemId → ItemNotFound, IO error → IoError). ~6-10h.
- **Blast radius:** Multiplicative — every Phase 2a UI consumer that calls browse() relies on an unverified API. Cheapest test infra investment with the highest leverage.

---

### P2 (count: 11)

#### P2-1: Skiko version mismatch warning on every desktop build

- **Location:** `:ui:theme:checkDesktopMainComposeLibrariesCompatibility` + `:ui:components:checkDesktopMainComposeLibrariesCompatibility` tasks.
- **Evidence:** `kiln-verify-build` baseline output:
  ```
  w: Skiko dependencies' versions are incompatible.
      io.coil-kt.coil3:coil-core-jvm:3.4.0
      \--- org.jetbrains.skiko:skiko:0.9.22.2 -> 0.144.6
  This may lead to compilation errors or unexpected behavior at runtime.
  ```
  Latest Skiko is 0.148.1 (2026-05-18 per gh api). The force-upgrade resolves to 0.144.6 (Compose-MP 1.11.0's pin), not the latest.
- **Why P2:** Warning is real, not theatrical — JetBrains explicitly tells you Skiko is an implementation detail and "incompatible across versions." Currently the runtime survives because Compose-MP 1.11.0's resolution wins; the moment Coil 3.5.x bumps its Skiko pin OR Compose-MP 1.12.x changes its forced version, breakage is possible without warning.
- **Recommended action:** Either (a) suppress with an explicit `resolutionStrategy` constraint pinning Skiko to the Compose-MP version, OR (b) wait for Coil 3.5.0-beta01 (2026-05-04) to stabilize and bump (it likely aligns Skiko with current Compose-MP). Document the constraint either way.
- **Blast radius:** Cosmetic (build noise) today; latent runtime breakage on next dep bump. Easy to silence.

#### P2-2: jaudiotagger has not released since 2021-10-13 (~4.5 years)

- **Location:** `gradle/libs.versions.toml:64` — `jaudiotagger = "3.0.1"`; consumer is `data/library/src/desktopMain/.../scan/JvmFilesystemScanner.kt`.
- **Evidence:** Maven Central search for `g:net.jthink a:jaudiotagger`: latest is 3.0.1 (timestamp 1634146203000 = 2021-10-13). Three years before that to 2.0.x in 2010. No 2022+ releases.
- **Why P2:** Load-bearing dep for Desktop FLAC metadata extraction. Unmaintained ≠ broken — it has worked for 4.5 years — but no fix path if a JDK update breaks its older bytecode patterns, and the very gap that produces P1-1 (ReplayGain extraction returns 0 hits) may stem from a jaudiotagger API mismatch that an actively-maintained tag reader would have fixed.
- **Recommended action:** Survey alternatives at Phase 2a Track A or Track D scheduling time — `kannte-music/jaudiotagger-kmp` (a fork; verify maintenance), `j-faldo/jaudiotagger` (another fork), or roll the minimum-viable Vorbis-comment + ID3v2 parser in commonMain. Defer the decision; don't churn the dep on this review's word alone.
- **Blast radius:** Track D depends on Desktop scanner working correctly. Replacement is a 12-20h effort with parity testing across Clay's library.

#### P2-3: Voyager 1.1.0-beta03 is 19 months stale

- **Location:** `gradle/libs.versions.toml:27` — `voyager = "1.1.0-beta03"`.
- **Evidence:** `gh api repos/adrielcafe/voyager/releases?per_page=3`:
  - `2.0.0-alpha01` (2025-08-17, prerelease)
  - `1.0.1` (2024-12-19, stable)
  - `1.1.0-beta03` (2024-10-07, prerelease — Kiln's pin)
  No movement on the 1.1.0 beta line since October 2024.
- **Why P2:** Phase 2a Track C (Proper UI) is the first user of Voyager. A beta from 19 months ago carries unknown bug risk. The stable line is 1.0.1 — older and missing the beta's features. The next major (2.0.0) is alpha. Voyager appears to be in slow maintenance OR pre-pivot.
- **Recommended action:** Revisit at Track C kickoff: (a) evaluate whether the 1.1.0-beta03 features are load-bearing, (b) if not, downgrade to 1.0.1 stable, (c) if yes, file an upstream issue for movement OR plan a Decompose migration as fallback (vetting Item 4 mentioned Decompose as alternative).
- **Blast radius:** Navigation subsystem of `:app-android` + `:app-desktop`. Library-swap effort is meaningful (~10-20h) if Voyager proves unworkable.

#### P2-4: kmpalette is still beta-only (no 4.0.0 stable)

- **Location:** `gradle/libs.versions.toml:24` — `kmpalette = "4.0.0-beta02"`; commented-out at the dependency level in `:ui:theme/build.gradle.kts:13` per CLAUDE.md "Build/Dep Gotchas."
- **Evidence:** `gh api repos/jordond/kmpalette/releases?per_page=3`:
  - `4.0.0-beta02` (2026-03-03, prerelease — Kiln's pin)
  - `4.0.0-beta01` (2026-02-17, prerelease)
  - `3.1.0` (2024-01-30, stable — last stable, 28 months old)
- **Why P2:** Phase 2a Flight A (Kiln Dynamic theming) is gated on this. The vetting Item 3 originally identified the beta-only risk; the revisit is overdue. Either palette extraction lives on a beta-line OR Kiln rolls its own (Item 3 fallback B — "16-24h in Flight A").
- **Recommended action:** Decide at Flight A kickoff (not yet active). The 1-year-old 3.1.0 stable's algorithm is likely good-enough for Track C's UI surface; beta02 has been quiet 3 months which is consistent with "stable line in everything but tag."
- **Blast radius:** All of Phase 2a Flight A's theming work. Already acknowledged in `ui/theme/build.gradle.kts` comments.

#### P2-5: `kiln-verify-build` skill undercounts test totals

- **Location:** `.claude/skills/kiln-verify-build/scripts/parse-gradle.ps1`.
- **Evidence:** Skill reports "PASS :data:library:desktopTest (41/41 tests)" and "Errors: 0" — the verdict is correct but the test count is partial. Actual count is 69 (41 :data:library + 28 :audio:playback active + 1 skipped GoldenCorpus). Confirmed by reading 11 TEST-*.xml files:
  ```
  audio/playback: FlacDecodeSmokeTest(1) + JavaSoundPlayerImplTest(12) + JvmFlacDecoderImplTest(4)
                + GoldenCorpusTest(1, skipped) + FlacFrameReaderTest(3) + LibFlacBindingTest(4)
                + NativeLibraryLoaderTest(1) + StreamInfoTest(2) = 28 active + 1 skipped
  data/library:   ScanInternalsTest + LocalLibrarySourceMappersTest + JvmFilesystemScannerTest = 41
  ```
- **Why P2:** Skill bug. The aggregated total in human + JSON output is wrong by ~40%. A future session reading "41/41" believes the test scope is half what it actually is.
- **Recommended action:** parse-gradle.ps1 should glob `**/build/test-results/desktopTest/TEST-*.xml` across all modules, not just the target the skill happened to run. ~30 min fix.
- **Blast radius:** Misleading test reporting. No false-pass risk (`BUILD FAILED` would still surface), but inflation of confidence.

#### P2-6: `kiln-flac-golden` skill auto-skips by default — GoldenCorpusTest doesn't gate

- **Location:** `audio/playback/src/desktopTest/.../flac/GoldenCorpusTest.kt`; XML shows `tests="1" skipped="1"`.
- **Evidence:** The skill's design (SKILL.md) requires `-Pkiln.golden.corpus=<dir>` to opt in. Without it, the test is `@org.junit.Assume`-skipped. CI's `:app-desktop:assemble` doesn't run `:audio:playback:desktopTest` at all (CI's structural gap noted in P3-1), and even `just verify` doesn't pass the corpus flag.
- **Why P2:** The skill earns its keep ("catches sign-extension regressions, 24-bit packing bugs, callback-GC issues, silent libFLAC-version-bump breakage") only when invoked manually. Cycle: Clay would have to remember to run `kiln-flac-golden` before every JNA / libFLAC.dll / decoder change. Manual gates rot.
- **Recommended action:** When P3-1 (Track F) lands the CI test gate, include `:audio:playback:desktopTest -Pkiln.golden.corpus=<dir>` against a checked-in synthetic corpus. The skill's `corpus.manifest` already has 5 ffmpeg-synthesizable test files — gitignored but reproducible. Either commit the synthesis recipe + reference outputs OR generate on the fly in CI (~30s).
- **Blast radius:** Decoder-fidelity gate. Without it, the JNA bump in P1-4 has no automated parity check before merge.

#### P2-7: 1 track with `duration_ms ≤ 0` (data hygiene)

- **Location:** Single anomalous row in `track` (DB at `~/.kiln/kiln.db`).
- **Evidence:** `SELECT COUNT(*) FROM track WHERE duration_ms <= 0 AND deleted_at_ms IS NULL` returned 1. 0.004% of library.
- **Why P2:** Schema enforces `duration_ms NOT NULL` but not positivity. The 0/negative case breaks `PlayerState.positionMs / duration_ms` progress UI and seek-bar percent. Low frequency; high consumer count (every position indicator).
- **Recommended action:** Either (a) schema check `CHECK (duration_ms > 0)` in next migration, OR (b) scanner-side validation rejecting tracks with bad duration. Option (b) more honest — the corrupt track shouldn't be indexed at all.
- **Blast radius:** UI progress display on that one track.

#### P2-8: `AudioProcessor` interface placement creates a `:audio:dsp` ↔ `:audio:playback` dep seam

- **Location:** `audio/playback/src/commonMain/.../AudioProcessor.kt` (interface lives here); comment says "actual processor implementations live in `:audio:dsp`."
- **Evidence:** `audio/dsp/build.gradle.kts` has commonMain dep on `libs.arrow.core` only — no `project(":audio:playback")` dep. When the EQ port lands at MVP Session 16+, a `ParametricEqProcessor : AudioProcessor` in `:audio:dsp` can't reference an interface it has no dep on.
- **Why P2:** Design seam not yet exercised. Per Concentric Modules invariant (`:audio:dsp` is INNER ring), the dependency direction must be `playback → dsp`, NOT the other way. So `AudioProcessor` should move to `:audio:dsp/commonMain` (which then becomes a dep of `:audio:playback`), OR be duplicated, OR the architecture needs explicit reconciliation.
- **Recommended action:** At MVP Session 16 kickoff (EQ port), move `AudioProcessor` + `AudioFrame` + `DecodedAudioFormat` + `SampleFormat` to `:audio:dsp/commonMain`, then `:audio:playback` adds `implementation(project(":audio:dsp"))`. 1-2h refactor.
- **Blast radius:** Pre-EQ-port refactor. Cheap if caught now; gets bigger if the interface accumulates platform-specific extensions before being moved.

#### P2-9: Phase 3 measurement-mode stub is missing from `PlatformPlayer`

- **Location:** `audio/playback/src/commonMain/.../PlatformPlayer.kt`. Spec §13's interface sketch includes `fun enterMeasurementMode(): MeasurementSession`. Spec §6.1 architectural-seam list says "Mic-capture path exists in `:audio:playback` (stubbed; for phase-3 room correction)."
- **Evidence:** Code search across `:audio:playback` for `Measurement`, `Capture`, `enterMeasurement` returns no matches. The seam the spec called for as an MVP commitment isn't stubbed.
- **Why P2:** Software-as-Self-Portrait commitment. The mic-capture stub was an explicit MVP-1.0 deliverable per spec §6.1 ("Architectural seams built but stubbed for phase 2"). Phase 3 is so distant (~150-250h after Phase 2a + 2b) that the stub is low-value today, but the spec promised it.
- **Recommended action:** Either (a) add the stub — `interface MeasurementSession` + `fun enterMeasurementMode(): MeasurementSession = throw NotImplementedError(...)` default — ~30-60min, OR (b) edit spec §6.1 to mark the stub deferred to Phase 3 itself. Option (a) preserves the portfolio narrative; (b) is honest.
- **Blast radius:** None functional. Drift between spec and code on a single architectural seam.

#### P2-10: DI graphs are untested

- **Location:** `app-android/src/main/kotlin/.../di/AndroidAppGraph.kt`, `app-desktop/src/main/kotlin/.../desktop/di/DesktopAppGraph.kt`.
- **Evidence:** Session 10 Polish-1 (KilnApplication lazy-init thread safety) and Polish-3 (DesktopAppGraph hoist out of `application{}`) were caught only by post-milestone code review. No tests exist for either graph.
- **Why P2:** kotlin-inject's generated graph code is deterministic at JVM level. A simple JVM test that calls `AndroidAppGraph::class.create(mockContext)` + asserts the returned instance's properties resolve without exceptions would have caught Polish-1 (the Media3ExoPlayer provider would lazy-init off the test thread). Same applies to DesktopAppGraph's value-class type tags.
- **Recommended action:** Once P1-3 (androidUnitTest) is in place: add `AndroidAppGraphTest` and `DesktopAppGraphTest`. ~3-5h combined.
- **Blast radius:** Same-class lazy-init regression risk for any future DI binding.

#### P2-11: Track A handoff under-estimates effort vs. plan §3.2

- **Location:** `docs/sessions/2026-05-21-session-11-handoff.md` Track A; `docs/superpowers/plans/2026-05-18-kiln-execution-plan.md` §3.2 Sessions 26-28.
- **Evidence:** Handoff says "~6-10 hrs." Plan §3.2 says "Sessions 26-28: Settings, preferences, polish (~12-20 hrs)." The Plan's number is ~2× the handoff's. The Plan accounts for "scan-folder settings + EQ preset management UI + typography + app icon assets" — Track A subsumes the scan-folder part, but also (the handoff acknowledges) needs Settings table schema migration + UI components scaffolding + DI graph rewire. Empty `:ui:components` + `:ui:theme` modules confirm: first UI work in the project.
- **Why P2:** Budget planning mismatch. A 50% effort under-estimate compounds when Track A blocks B + C. Track A is also the first UI Compose work — typically the "I haven't done this in this codebase before" tax applies.
- **Recommended action:** Update handoff to acknowledge plan §3.2's 12-20h or split Track A into A1 (schema + repository + DI plumbing) + A2 (UI surface). Don't commit to "Track A in one session" without buffer.
- **Blast radius:** Schedule planning only.

---

### P3 (count: 7)

#### P3-1: CI doesn't run any tests (Track F's premise)

- **Location:** `.github/workflows/build.yml`.
- **Evidence:** Lines 28-29 + 54-55 run only `:app-android:assembleDebug` and `:app-desktop:assemble`. No test target, despite 69 tests passing locally.
- **Why P3:** Acknowledged structural gap — explicitly scoped as Track F in the Session 11 handoff. Listing here only as a verification-of-premise (Track F is real, 1-2h work, no blockers).
- **Recommended action:** Track F adds `:data:library:desktopTest :audio:playback:desktopTest` to both job steps. The skill's parser bug from P2-5 doesn't matter for CI since `BUILD FAILED` is enough.
- **Blast radius:** Confirmed scope of Track F.

#### P3-2: 7 tracks with `album_id IS NULL` (legit untagged-album tracks)

- **Location:** Live DB.
- **Evidence:** `SELECT COUNT(*) FROM track WHERE album_id IS NULL` returned 7. The schema permits this (album_id is nullable per `album_id INTEGER REFERENCES album(id)` without NOT NULL).
- **Why P3:** Data hygiene observation. These are singles or untagged-album tracks. UI consumers (Phase 2a Track C) need a "Songs without album" bucket OR the import should be flagged for tagging cleanup.
- **Recommended action:** Track C UI design must handle the NULL case. No code change today.
- **Blast radius:** UI surface.

#### P3-3: 398 AAC tracks indexed but undeliverable on Desktop

- **Location:** Scanner accepts `.m4a` extension at `JvmFilesystemScanner.kt:36` (`AUDIO_EXTENSIONS`); no AAC decoder exists in `:audio:playback` (only `JvmFlacDecoderImpl`).
- **Evidence:** Live DB groups: 27,368 FLAC + 398 AAC. Decoder.supports() returns true only for FLAC on Desktop. Tracks appear in browse, would fail at queue-resolution time.
- **Why P3:** Out-of-scope codec for the audiophile MVP. AAC is in Clay's library (Bob Marley Trilogy CD3 bootleg compilation) but the spec privileges FLAC. Either Decoder.supports() gates at queue-time (silent skip) or the scanner filters m4a out (cleaner — they don't appear in browse).
- **Recommended action:** Decide at Track C UI design: are non-decodable tracks shown? If yes, browse should label them; if no, scanner should filter. Either way, a Capability Flags consumer (P3-7) is the natural decision point.
- **Blast radius:** Tracklist UX.

#### P3-4: Plan §4 (5 flights) vs. Session 11 handoff (6 tracks) Phase 2a structure mismatch

- **Location:** `docs/superpowers/plans/2026-05-18-kiln-execution-plan.md` §4; `docs/sessions/2026-05-21-session-11-handoff.md`.
- **Evidence:** Plan §4 lists Phase 2a as 5 flights: Flight A theming, B blurred art, C EQ refinements, D search (formerly E after Tidal cut), E FFT visualizer. Session 11 handoff lists 6 different tracks: A Settings UI, B SAF picker, C Proper UI, D ReplayGain, E MediaSession, F CI test gate. No 1:1 mapping — Settings/SAF/MediaSession/CI gate are net-new framings; ReplayGain is implicit in MVP per spec §6.1; EQ refinements / FFT visualizer / theming aren't called out in the handoff.
- **Why P3:** Documentation drift. The plan is canonical for effort budgeting; the handoff supersedes naturally as session-level guidance. A cold reader of the plan sees one structure and a cold reader of the handoff sees another.
- **Recommended action:** Add a plan §4 addendum reconciling the two — "Session 11 handoff's tracks A-F derive from plan §4 flights as follows…" — preserving append-only decision-log discipline.
- **Blast radius:** Documentation only.

#### P3-5: Scanner forceFullRescan UPDATE runs outside the loop transaction (intentional, undocumented in code)

- **Location:** `JvmFilesystemScanner.kt:91` + `AndroidMediaStoreScanner.kt:59`.
- **Evidence:** `driver.execute("UPDATE track SET last_scanned_ms = 0", ...)` runs BEFORE the `db.transaction { for (file in files) { ... } }` at line 108. Session 10 /ultrareview flagged this; the refute lives in the addendum text ("refuted; the UPDATE intentionally runs OUTSIDE the inner transaction in the original design").
- **Why P3:** Rationale isn't in code. Next reader of the scanner sees an UPDATE outside a transaction adjacent to scan work and re-investigates.
- **Recommended action:** One-line comment: `// INTENTIONAL: outside the loop transaction so a mid-scan crash leaves a recoverable state via softDeleteUnscanned on next run. See 2026-05-19-session-10-addendum-re-review-fixes.md.`
- **Blast radius:** Future-investigator hours.

#### P3-6: Capability Flags pattern is decorative today — no consumer dispatches on flags

- **Location:** `data/library/src/commonMain/.../source/SourceCapabilities.kt` (16-flag data class); `MusicSource.kt` (capabilities is a contract field); `LocalLibrarySource.kt:22` (assigns `LocalSourceCapabilities`).
- **Evidence:** Grep for `capabilities\.` in production code returns only the assignments + the MusicSource interface declaration. Decoder.supports(codec) is the only capability-style query actually used in dispatch.
- **Why P3:** Pattern is correctly declared but not exercised. With only LocalLibrarySource in scope, that's expected — the value materializes when a second source (Subsonic / Navidrome) shows up. Worth noting so the pattern isn't accidentally removed as "dead code" before that day.
- **Recommended action:** Document in spec §3.3 that capabilities are intentionally decorative until a second source exists. Phase 2a Track C UI can opportunistically branch on flags (e.g., `if (source.capabilities.canBrowseByGenre)` to show/hide genre tab) — that's the natural first consumer.
- **Blast radius:** Pattern continuity.

#### P3-7: Coverage % vs. spec §8.2 targets is below plan (expected per phase progression)

- **Location:** spec §8.2 coverage targets vs. actual test surface.
- **Evidence:**
  - :audio:dsp target 95%, actual 0% (no code — MVP Session 16+)
  - :audio:visualizer target 90%, actual 0% (no code — Phase 2a Flight E)
  - :audio:playback target 80%, estimated ~40-50% (Desktop covered, Android not)
  - :data:library target 75%, estimated ~50% (Desktop scanner + mappers covered; Android scanner + LocalLibrarySource not — see P1-6)
- **Why P3:** Phase-progression drift, not regression. The plan front-loads "first builds early" and accepts test gaps until features land. Worth tracking as an explicit phase-exit criterion for MVP-1.0 close.
- **Recommended action:** None today; revisit at MVP-1.0 close.
- **Blast radius:** Plan §1.3 ship readiness criteria.

---

## Architectural integrity check (spec §3 invariants)

| Invariant | Status | Evidence |
|---|---|---|
| **Concentric Modules** (§3.4) | ✅ Upheld (vacuously) | `audio/dsp/build.gradle.kts` + `audio/visualizer/build.gradle.kts` declare commonMain deps only on `libs.arrow.core` / `libs.kotlin.test` — no androidx. **Both modules have no `src/` yet** so the invariant has nothing to violate. Gate is correct; will earn its keep at MVP Session 16+. |
| **The Source Protocol** (§3.3) | ✅ Upheld | grep for `is\s+[A-Z]\w*Source\b` across production .kt files: 0 violations. Only matches are in MusicSource.kt's own header comment (the canonical rule reminder). No `if (source is XxxSource)` branches. |
| **Capability Flags** | ⚠️ Decorative | `SourceCapabilities` data class declared with 16 flags; assigned in LocalLibrarySource; no production code branches on `capabilities.X`. Decoder.supports(codec) is the only active capability-style dispatch. P3-6 finding. |
| **Engine-Swap-Shaped Boundary** (§13) | ✅ Upheld | `PlatformPlayer.kt` interface is engine-agnostic. No ExoPlayer or Java Sound types leak through the boundary. 18 abstract methods, all generic. Phase 2b AAudio/WASAPI swap would land behind this interface without consumer churn. |

---

## Data layer health (kiln-db-desktop MCP)

**Live DB:** `~/.kiln/kiln.db`, schema_version=1, 15.26 MB.

| Metric | Value | Note |
|---|---|---|
| Tables | 11 | 6 core + 5 FTS5 shadows (contentless FTS5 design) |
| Live tracks | 27,766 | Matches Session 10 Desktop scan empirical figure |
| Soft-deleted tracks | 0 | Clean state |
| Albums / artists / playlists | 2,986 / 7,715 / 0 | Playlists not exposed in UI yet (Phase 2a Track C) |
| listening_history rows | 0 | UI for play tracking not shipped yet |
| `track_search` (FTS5) row count | 27,766 | **Exact match to live tracks — FTS5 in sync** |
| `INSERT INTO track_search(track_search) VALUES('integrity-check')` | No error | Index internally consistent |
| `PRAGMA foreign_key_check` | No rows | No FK violations |
| `PRAGMA integrity_check` | No rows | No DB corruption |
| **Orphan tracks (album_id missing)** | **0** | Clean |
| **Orphan tracks (artist_id missing)** | **0** | Clean |
| Orphan playlist_track / listening_history | 0 / 0 | (Tables empty) |
| Tracks with album_id IS NULL | 7 | Singles or untagged-album (P3-2) |
| **Tracks with channels ≤ 0** | **340** | All 340 are AAC m4a; channels=-14 (P1-2) |
| Tracks with duration_ms ≤ 0 | 1 | Single anomalous row (P2-7) |
| Tracks with mtime pre-2020 | 0 | Clean |
| **ReplayGain coverage (track_db / album_db / track_peak / album_peak)** | **0 / 0 / 0 / 0** | **P1-1 — Track D premise invalidated** |
| Indexes (excluding sqlite_*) | 15 | Sensible coverage of hot paths |
| Codec distribution | 27,368 FLAC + 398 AAC | 16-32 bit; 22050-192000 Hz |
| Track duration range | min 4s, avg 203s (3:23), max 2230s (37min) | Reasonable |

---

## Phase 2a track readiness

Risk rank is review-uncovered risk only — independent of effort estimate and logical dependencies. Track scheduling should still honor dependencies (A blocks B+C).

| Track | Spec ref valid? | Prereqs met? | Blocker found? | Risk rank |
|---|---|---|---|---|
| **A. Settings UI** | ✅ schema sketch §6 (Settings table not in live schema) | ⚠️ first UI work in project; `:ui:components` + `:ui:theme` empty | Handoff estimates 6-10h; plan §3.2 says 12-20h. Same scope, ~2× estimate. P2-11. | **5** (medium-high) |
| **B. SAF folder-picker** | ✅ engram + AOSP docs | ❌ depends on Track A's Settings table | Clean Android API work; needs A first | **3** (medium-low) |
| **C. Proper UI** | ✅ spec §6 + scaffold prep §8 | ❌ depends on Track A; Voyager 19mo stale (P2-3); kmpalette beta-only (P2-4); Roborazzi unwired | First UI surface; biggest scope (12-20h). No screenshot test infra. | **6** (highest) |
| **D. ReplayGain** | ✅ spec §4 + vetting Item 14 | ❌ **0% RG coverage in DB** despite scanner code (P1-1) | "Pure consumption" framing is empirically false | **4** (medium-high) |
| **E. MediaSession** | ✅ vetting Item 11 | ✅ MediaSession instance already constructed (Media3ExoPlayerImpl.kt:97-101) | None | **2** (low) |
| **F. CI desktopTest gate** | ✅ .github/workflows/build.yml | ✅ all 69 tests pass locally | None | **1** (lowest) |

**Recommended order (honoring dependencies + risk):**
1. **Track F** (1-2h, zero risk, CI hygiene)
2. **Track E** (3-5h, low risk, MediaSession service binding)
3. **Probe Track D's premise** (30 min — `metaflac --list` on a known-tagged FLAC + jaudiotagger reproducer)
4. **Track A** (split into A1 schema/repo + A2 UI; budget 10-16h, not 6-10h)
5. **Track B** (4-6h, after A1 lands)
6. **Track D** (after probe; real scope likely 8-14h)
7. **Track C** (12-20h, last because biggest)

The Session 11 handoff's **F → A → C** sequence is sound; this review adds E and the D probe as cheap interleaves.

---

## Tooling gaps + proposed addendum

Things that still hurt after installing Tier 0 + 1 + 2 (3 custom skills + dbhub MCP):

1. **`kiln-verify-build` skill undercounts tests** (P2-5) — skill bug, ~30 min fix.
2. **`kiln-flac-golden` skill is no-op in CI** (P2-6) — GoldenCorpusTest auto-skips without `-Pkiln.golden.corpus=...`. Track F's CI work should pass the flag.
3. **No androidTest source set** (P1-3) — recurring Session 10 anti-pattern. Bundled-SQLite host-side via `RequerySQLiteOpenHelperFactory` is viable.
4. **No scanner-output validation skill** — I caught P1-1 (RG coverage) and P1-2 (negative channels) only via dbhub MCP queries. A `kiln-scan-validate` skill that compares scanner output against `metaflac` / `mediainfo` ground truth on a small known-tagged corpus would catch these before H8.
5. **No dep-freshness skill** — `gh api repos/X/releases?per_page=N` × 8 in parallel did the job today; a `kiln-dep-freshness` skill periodically auditing `libs.versions.toml` against latest releases would automate kmpalette/Voyager/JNA monitoring.
6. **mobile-mcp install was deferred under Tier 3** — trigger ("first H7/H8 verification on Pixel") fired during Session 10. Track B (SAF) and Track E (MediaSession) both need Pixel UI inspection. Promote to Tier-2-bis.
7. **No spec → code drift detector** — P2-9 (missing measurement-mode stub) and P3-4 (plan/handoff Phase 2a structure mismatch) wouldn't be caught by tests. A periodic manual check (or skill) comparing spec/§3 invariants to code symbols catches drift before reviews like this.

### Proposed addendum draft for `docs/decisions/2026-05-21-tooling-recommendation.md`

If Clay accepts, append this addendum to the locked tooling doc:

> ### Addendum 2026-05-21 (later): tooling gaps surfaced by tooling-armed project review
>
> Authored during the holistic project review at `docs/reviews/2026-05-21-tooling-armed-review.md`. Two skill bugs and four new tools/skills proposed.
>
> #### Skill bug fixes (P0 — fix in next skill-author session)
> - **`kiln-verify-build` test counting**: `parse-gradle.ps1` should glob `**/build/test-results/desktopTest/TEST-*.xml` across all modules, not just the single target it last ran. ~30 min.
> - **`kiln-flac-golden` CI integration**: pass `-Pkiln.golden.corpus=<dir>` against a checked-in synthetic corpus when Track F adds the test step to `.github/workflows/build.yml`. The corpus.manifest can synthesize via ffmpeg in CI in ~30s.
>
> #### Tier-2-bis: promote from Tier 3 (install when Track B or E scheduled)
> - **mobile-mcp** v0.0.55 — H7/H8 trigger fired in Session 10; future Pixel UI inspection cycles will use it.
>
> #### Tier 3 additions (skill candidates — author when use case arrives)
> - **`kiln-scan-validate` skill**: probe a known-RG-tagged FLAC + diff scanner output against `metaflac` / `mediainfo` ground truth. Would have caught Track D's 0% RG coverage gap.
> - **`kiln-dep-freshness` skill**: walk `libs.versions.toml`, query `gh api repos/<owner>/<repo>/releases?per_page=N` per dependency, surface stale pins. Runs in <30s for ~20 deps.
> - **androidTest source set + bundled SQLite test fixture** (NOT a skill — structural test-infra investment). Session 10 recap §12 anti-pattern #2 still active. ~6-10h initial setup.

---

## Out of scope (deliberately not reviewed)

- **Compose UI behavior** — there are no Compose surfaces beyond the H7 dev-affordance buttons. UI review earns its keep when Track A/C ship.
- **kotlin-lsp symbol analysis** — upstream analyzer deferred per tooling-recommendation.md addendum. Brief explicitly prohibits LSP ops this session.
- **Property-based / mutation testing review of `:audio:dsp`** — module is empty.
- **Performance benchmarking** — no JMH harness yet (MVP Session 16+ work per plan §7).
- **Item-by-item re-raise of `2026-05-19-session-10-addendum-re-review-fixes.md`** — covered work, off-budget per brief.
- **Compose-MP 1.11.0 vs. 1.12.0-alpha01 evaluation** — 1.11.0 stable was JIT-verified during axis 2; 1.12.0-alpha01 (2026-05-19) is too fresh to evaluate.

---

## Engram memory pointers

Saved during this review (read with `mem_search "<topic_key>"`):

- `kiln/review-axis-1-checkpoint` — architectural integrity
- `kiln/review-axis-2-checkpoint` — build & dep health
- `kiln/review-axis-3-checkpoint` — data layer integrity (MCP queries)
- `kiln/review-axis-4-checkpoint` — test coverage gaps
- `kiln/review-axis-5-checkpoint` — spec/plan/code drift
- `kiln/review-axis-6-checkpoint` — Phase 2a track readiness
- `kiln/review-axis-9-checkpoint` — debt scan + tooling sufficiency (combined)
- `kiln/project-review-2026-05-21` — anchor for this whole review (saved at session close)

---

**End of review.** No source modifications were made during this session. Findings: 0 P0 / 6 P1 / 11 P2 / 7 P3. Total: 24.
