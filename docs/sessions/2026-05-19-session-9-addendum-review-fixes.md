# Session 9 Addendum — Post-review fix batch (Gemini + ultrareview)

**Date:** 2026-05-19 (continuous sitting following Session 9 closeout)
**Trigger:** Clay opened synthetic PR #1 (main → review-base-empty) to invite multi-agent code review across the entire post-Session-9 codebase.
**Inputs:**
- **Gemini Code Assist** auto-review on PR #1 — 5 findings (1 critical, 1 high, 3 medium).
- **/ultrareview #1 of 3** on PR #1 — 4 additional findings (2 normal, 2 nits), zero overlap with Gemini.
- Combined deduped set: **9 unique issues across 6 files**. 1 was scope-deferred (style nit on the JNA layer that wasn't worth touching). **8 fixes applied** in this batch.

**Outcome:** 8 fix commits + this addendum. Canonical session-validation build green; empirical FLAC smoke against Clay's D:\tiddl library still 10/10. All commits pushed to origin/main.

---

## Fix-by-fix log (in commit order)

| Commit | Source | Severity (theirs / mine) | Where | What |
|---|---|---|---|---|
| `e08c627` | ultrareview U1 (bug_009) | Normal / Normal | `data/library/.../scan/internal/ScanInternals.kt:65-82` | FTS `delete-all` was running OUTSIDE the bulk-insert transaction → search returned EMPTY (not stale) during every scan; crash mid-rebuild left FTS permanently empty. Wrapped `driver.execute('delete-all')` + the insert loop inside a single `db.transaction { }`. CLAUDE.md gotcha corrected: prior text claimed "briefly stale results" which was wrong — pre-fix behavior was empty results. |
| `8dfae5f` | ultrareview U4 (bug_012) | Normal / Normal | `audio/playback/.../JavaSoundPlayerImpl.kt:307-311` | Playback-loop `catch` block leaked `SourceDataLine` + `JvmFlacDecodedStream` (= OS audio device handle + libFLAC native handle) on decode exception. Extracted shared `teardownActivePlayback(stopLineFirst)` helper called from EOF / cancel / exception paths so all three converge on identical resource hygiene. |
| `343c7a7` | Gemini #5 | Medium / Medium | `audio/playback/.../JvmFlacDecodedStream.kt:116-135` | Decoder error states silently `return@flow`; the player treated the error as EOF + advanced to next track without surfacing to PlayerState. Now: only `STATE_END_OF_STREAM` (4) is a clean terminator; the five terminal error states (ABORTED / OGG_ERROR / SEEK_ERROR / MEMORY_ALLOCATION_ERROR / UNINITIALIZED) each throw a typed `FlacDecodeException(message, decoderState)` that propagates into `JavaSoundPlayerImpl`'s catch → `PlayerState.Error(PlayerError.DecodeFailed)`. New file: `nativeio/FlacDecodeException.kt`. |
| `7a97c38` | Gemini #2 | High / High (latent) | `audio/playback/.../JavaSoundPlayerImpl.kt:292` | `_processors.value.forEach { it.process(frame) }` discarded the return value. `AudioProcessor.process(frame): AudioFrame` returns a new (potentially transformed) frame — any processor that legitimately mutates would have its work silently lost. Folded: `var processedFrame = frame; ... processedFrame = processor.process(processedFrame); ... line.write(processedFrame.bytes, ...)`. Currently a no-op (no concrete processors before MVP Session 16-22), but wiring it now prevents the bug from activating the moment EQ/ReplayGain lands. |
| `6af821a` | Gemini #1 + #3 | Critical+Medium / Medium-High | `audio/playback/.../JavaSoundPlayerImpl.kt:131-184, 292-295` | **The biggest fix.** Two coupled findings: (1) `play()/pause()` marshalling through `withContext(audioDispatcher)` would queue behind a blocking `sourceLine.write()`, stalling control for ~100ms worst case (Gemini called this "permanent deadlock" — I downgraded to "stall" because the loop's `delay()` released the dispatcher between writes, but it's still bad UX). (2) The pause-wait was a busy-poll `while (pauseRequested && isActive) delay(POSITION_TICK_MS)` — wasteful + non-idiomatic. Combined refactor: replaced `@Volatile var pauseRequested` with `MutableStateFlow<Boolean> _paused`; playback loop now suspends on `_paused.first { !it }`; `play()/pause()` dropped `withContext` and flip the flow value + call `line.start()/stop()` directly (javax.sound.sampled.Line methods are documented thread-safe). `setRepeatMode/setShuffleMode` also dropped `withContext` (pure flag-flips). Line-mutating methods (stop/seekTo/skip*/loadQueue/release/setVolume/setMuted) KEEP `withContext` because they touch line lifecycle / FloatControl state. |
| `bf0c4d9` | ultrareview U3 (bug_013) | Nit / Nit | `audio/playback/.../JavaSoundPlayerImpl.kt:109-128` | `loadQueue` eagerly resolved every `Playable` (for validation) then discarded all of them; `startPlaybackForCurrentIndex` immediately re-resolved the start item → N+1 SQL queries instead of N. Threaded the start item's already-resolved Playable through via a `preResolved: Playable? = null` parameter. Skip-next/prev/skipTo/advanceOnEof still null-resolve on demand (they target different items than the originally-loaded start). |
| `709fc18` | Gemini #4 | Medium / Medium (deferred-but-real) | `data/library/.../scan/internal/ScanInternals.kt:70` | `selectAllForFtsRebuild().executeAsList()` materialized the whole library in memory before iterating — ~40 MB for Clay's 39.5k tracks (safe in absolute terms but unbounded as the library grows). Switched to SqlCursor iteration via `query.execute { cursor -> while (cursor.next().value) {...}; QueryResult.Unit }`. Memory cost is now O(1) regardless of library size. SQLite supports the nested-read-while-writing pattern when the cursor reads from `track` (+ joins) and INSERTs target `track_search` — different tables, no conflict. |
| `f138b76` | ultrareview U2 (bug_014) | Nit / Nit | `audio/playback/.../Decoder.kt:58-76` | The `AudioFrame.equals/hashCode` comment claimed to "avoid full-array compare on hot path" but the code did the opposite (`contentEquals` IS a full-array compare; `contentHashCode` IS a full-array hash). Rewrote the comment to honestly describe the actual purpose (structural equality vs Kotlin data class default reference equality on ByteArray) + added an explicit warning against using AudioFrame in StateFlow/Set/Map. Also tightened the comparison to be bounded by `[0, byteCount)` rather than the whole `bytes.size` array — currently no behavior change (no buffer pooling yet) but lands the correctness fix preemptively so future pool-reuse work doesn't break frame equality. New private helper `byteArrayPrefixEquals`. |

---

## What was NOT fixed in this batch

- **The stretch "byte-for-byte ffmpeg compare" gate for the FlacDecodeSmokeTest** — explicitly noted in the H6.9 closeout as a stretch goal. Real-world 10/10 metadata-and-sample-count match is sufficient for the vetting Item 9 addendum bar.
- **Per-frame buffer pooling for AudioFrame.bytes** — the slice-aware equals/hashCode in U2 prepares for this, but the actual pooling work (Phase 2a-ish) is a separate effort.
- **Live-stream unit tests for the new pause signaling** — would require a synthetic infinite stream + thread-safety-stress harness. The existing 8 API-level JavaSoundPlayerImpl tests + the empirical D:\tiddl smoke + the upcoming H7 audible verification cover this for MVP. Phase 2a would add proper concurrency tests.

---

## Verification

- ✅ Canonical session-validation build: `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest` — BUILD SUCCESSFUL.
- ✅ 48 unit + smoke tests still pass (25 :data:library + 23 :audio:playback).
- ✅ Empirical FLAC smoke against D:\tiddl: 10/10 Clay's actual FLACs decoded successfully — identical to pre-fix run (sample-count totals match ffprobe to the byte).
- ✅ All 8 commits pushed to `origin/main` (`db464b6..f138b76`).

---

## Review-feedback meta-notes

**Provider performance comparison** (n=1 PR, useful baseline):

| Provider | Findings | Severity skew | Strengths | Weaknesses |
|---|---|---|---|---|
| Gemini Code Assist (auto) | 5 | Heavily skewed concurrency / pipeline correctness | Caught the discarded-processor-return-value bug — a future-breaking bug that would have activated silently when :audio:dsp processors land. Also surfaced the deadlock/stall concern. | Mis-classified the "deadlock" as critical when it was technically a finite stall. Sized its findings to a Kotlin/JVM audio context that Claude could replicate from training data. |
| /ultrareview (#1 of 3) | 4 | Concrete data-correctness + resource-leak focused | Found the FTS-transaction-atomicity bug that contradicted a CLAUDE.md gotcha — that's the single highest-value finding of either review pass. Also caught the resource leak in the catch block. | Tended toward exhaustive reasoning paragraphs in each report, more than needed for triage. The two nits (loadQueue N+1, AudioFrame comment) are real but low-leverage. |

**Combined**: zero overlap, complementary coverage. Both reviews together produced **9 unique issues across 6 files**. 8 of the 9 were actionable in-session; the 9th (a JNA-layer style nit not listed above) was not worth touching.

**Strategic note**: this is precisely the "Cross-Model Validator" pattern at work — two independent LLM reviewers found different bugs in the same code, and synthesis across both was more valuable than either alone. **Recommendation**: bank the remaining 2 ultrareview credits for a post-Phase-2a re-review (when more code exists to evaluate) rather than another pass on this same code surface.

---

**End of Session 9 addendum.** The vertical-slice-milestone work is now bulletproof at the unit/smoke-test level. Session 10 (H7 + H8) remains the next session per the existing handoff doc.
