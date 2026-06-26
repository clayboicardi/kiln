# Desktop Player Concurrency — Command/Actor Model (#28 defects #1 + #2) Design

**Date:** 2026-06-25 · **Issue:** #28 (defects #1 + #2) · **Module:** `:audio:playback` (desktop) + `:ui:components` (common)
**Status:** design — pending review → implementation plan.

---

## 1. Problem

On the desktop player (`JavaSoundPlayerImpl`), two user-visible defects share one architectural root, plus one product-level root:

- **#1 — selecting a track while one is playing does nothing.**
- **#2 — skip/next is a no-op.**

**Architectural root (the starvation):** the playback loop `scope.launch { stream.frames.collect { write(...) } }` runs on ONE single-thread dispatcher (`audioDispatcher`). `stream.frames` is a plain `flow { while(!closed){ FLAC__stream_decoder_process_single(...) /* blocking JNA decode */; emit() } }` with no `flowOn`/`yield`, so the loop never relinquishes the thread between frames. Control ops (`loadQueue`, `skipToNext/Previous`, `skipTo`, `stop`, `seekTo`) all use `withContext(audioDispatcher){…}`, so they queue behind the running loop and cannot execute until the current track EOFs. `play`/`pause` respond only because a Session-9 fix made them bypass the dispatcher.

**Product root (#2a):** `LibraryTab`/`SearchTab` call `loadQueue(items = listOf(item), …)` — a **single-item queue** — so even when a skip *does* run, `nextIndexOrNull` returns null. Confirmed in `ui/components/.../library/LibraryTab.kt:56` and `.../search/SearchTab.kt:60`.

`#3` (analyzer backfill processes only a subset) is a **separate, unrelated** root (the backfill collect runs on a composition-bound `rememberCoroutineScope` instead of the process-lifetime `appScope`, so leaving Settings cancels it). It is a one-line fix landed independently of this design — see §10.

---

## 2. Goal & success criteria

Make desktop playback control responsive and correct:

1. Clicking any Library/Search track plays it **immediately, even while another track is playing**.
2. **next/previous/skip-to** work, walking the **loaded list** (the clicked list, from the clicked track — product decision locked 2026-06-25).
3. No regression to playback fidelity, ReplayGain, position reporting, or resource hygiene (no leaked `SourceDataLine` / native libFLAC handles).
4. Behaviour stays behind the `PlatformPlayer` interface — **Android/Media3 untouched**, consumers unchanged (Engine-Swap-Shaped Boundary; the desktop engine is transitional, WASAPI replaces it in Phase 2b).

Non-goal: making the *transitional* JavaSound engine bullet-proof against every adversarial timing. We fix the defects and the failure modes the falsify ranked ≥ MEDIUM×MEDIUM; inherent javax.sound limits (e.g. `write()` can block indefinitely on a hung device) are **accepted and documented**, not engineered around.

---

## 3. Scope

**In:**
- Refactor `JavaSoundPlayerImpl` to a **command/actor model** (desktop-only). [#1, #2b]
- Move libFLAC decode onto its own single-thread **decode dispatcher**. [#1]
- `LibraryTab` + `SearchTab`: load the **full visible list** as the queue, starting at the clicked index. [#2a — commonMain; also fixes Android skip, which has the same single-item-queue root.]

**Out / deferred:**
- `#3` backfill scope fix — separate one-line commit (§10).
- Shuffle *order* generation — still deferred (today it's a flag-only no-op; unchanged here).
- Library pagination beyond the current ~500-track load — Track-C2 follow-up; the queue is bounded by whatever the list view has loaded.
- WASAPI/AAudio low-latency engine — Phase 2b.
- Android `Media3ExoPlayerImpl` internals — unaffected (ExoPlayer owns its own threading/queue).

---

## 4. Architecture — "A done right": everything through the actor

The falsify (codex, repo-grounded) confirmed the actor model is correct **only if every line/stream/queue/state mutation routes through the actor** — the original sketch's "keep play/pause/volume/repeat/shuffle direct" shortcut would re-introduce the cross-thread races the actor exists to remove. So:

```
 ALL control ops (loadQueue, play, pause, stop, seekTo, skip*, setVolume,
 setMuted, setRepeat, setShuffle, add/removeProcessor, release)
        │  trySend(PlayerCommand)   [non-suspending; UNLIMITED channel]
        ▼
 ┌────────────────────┐         ┌──────────────────────────────────────┐
 │ Channel.UNLIMITED  │ ──────▶ │  PLAYER ACTOR  (audioDispatcher)       │
 │   <PlayerCommand>  │         │  • SOLE owner: SourceDataLine, queue,  │
 └────────────────────┘         │    DecodedStream handle, all 5 Flows   │
                                │  • generation: Long                    │
 ┌────────────────────┐ frames  │  • state machine: IDLE / PLAYING /     │
 │ decode producer    │ produceIn│    PAUSED — all command-responsive     │
 │  (decodeDispatcher)│ ──────▶ │  • command-biased select{}             │ ─▶ SourceDataLine.write
 └────────────────────┘         └──────────────────────────────────────┘
```

- **The actor** is one long-lived coroutine launched in `init` on `audioDispatcher`. It is the **only** code that touches the `SourceDataLine` lifecycle (open/close/start/stop-for-state), the `DecodedStream`, the queue index, and the five `MutableStateFlow`s. Single-writer by construction.
- **Every public `PlatformPlayer` method becomes a `commands.trySend(PlayerCommand.X)`** to an **`Channel.UNLIMITED`** + return. `trySend` to an unlimited channel never suspends and never drops → control ops genuinely "return immediately" and can never freeze a Compose caller (falsify finding A).
- **Decode runs on its own single-thread `decodeDispatcher`.** The actor obtains frames via `stream.frames.produceIn(decodeScope)`. The actor thread is therefore never blocked by libFLAC decode — only by the (normally buffer-bounded) `SourceDataLine.write`.
- **The actor's loop is command-biased:** it drains all pending commands *before* consuming the next frame, then `select`s frame-vs-command. This guarantees a queued command is serviced within ~one `write` even when frames are continuously available (falsify finding C — `select` is otherwise unbiased).
- **One documented exception — the "write-interrupt seam":** `pause()`/`stop()`/any track change additionally fire a **best-effort, thread-safe `line.stop()`** *before* enqueuing, purely to unblock a `write()` that is currently blocked so the actor can make progress. `Line.stop()` is documented thread-safe + idempotent; the actor remains the authority on all subsequent line state. This is the *only* cross-thread line touch, and it mutates nothing the actor reads as state — it just interrupts a stuck syscall.

---

## 5. Components

### 5.1 `PlayerCommand` (new sealed interface, desktopMain)

```kotlin
internal sealed interface PlayerCommand {
    data class LoadQueue(val items: List<MediaItem>, val startIndex: Int, val autoPlay: Boolean) : PlayerCommand
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data object Stop : PlayerCommand
    data class SeekTo(val positionMs: Long) : PlayerCommand
    data object SkipToNext : PlayerCommand
    data object SkipToPrevious : PlayerCommand
    data class SkipTo(val index: Int) : PlayerCommand
    data class SetRepeat(val mode: RepeatMode) : PlayerCommand
    data class SetShuffle(val enabled: Boolean) : PlayerCommand
    data class SetVolume(val linear: Float) : PlayerCommand
    data class SetMuted(val muted: Boolean) : PlayerCommand
    data class AddProcessor(val processor: AudioProcessor) : PlayerCommand
    data class RemoveProcessor(val processor: AudioProcessor) : PlayerCommand
    data object Release : PlayerCommand
}
```

EOF and decode-error are **not** commands — they are observed inline via the per-generation frame channel's `onReceiveCatching` close result inside the actor's `select`, so they are already serialized against commands (no separate posting race; falsify finding D is handled by serialization + generation, see §6).

### 5.2 Command channel + public methods

`private val commands = Channel<PlayerCommand>(Channel.UNLIMITED)`. Every interface method is one line, e.g.:

```kotlin
override suspend fun loadQueue(items, startIndex, autoPlay) { commands.trySend(LoadQueue(items, startIndex, autoPlay)) }
override suspend fun pause() { runCatching { line?.stop() }; commands.trySend(Pause) }   // write-interrupt seam
override suspend fun skipToNext() { commands.trySend(SkipToNext) }
// …etc. (methods keep their `suspend` signature for interface compatibility but never actually suspend.)
```

(The `suspend` modifier is retained for interface compatibility; the bodies do not suspend. The change in *observable* semantics — postcondition no longer visible synchronously after the call returns — is addressed in the test plan, §8.)

### 5.3 The actor coroutine (the heart)

```kotlin
private fun launchActor() = scope.launch(audioDispatcher) {
    while (true) {
        val target = drainAndFold()                 // pull ALL pending commands (command-biased); fold nav ops
        if (released) { teardownAll(); return@launch }
        applyImmediate(target)                       // volume/mute/repeat/shuffle/processor updates (no stream change)
        if (target.streamChange != null) openStreamForTarget(target.streamChange)  // load/skip/skipTo/stop/seek
        when (mode) {
            Mode.IDLE, Mode.PAUSED ->
                handleCommand(commands.receive())    // block awaiting next command — STILL fully responsive
            Mode.PLAYING ->
                select {
                    commands.onReceive { handleCommand(it) }                 // command wins ties via the drain above
                    frameChan!!.onReceiveCatching { writeOrAdvanceOrError(it) }
                }
        }
    }
}
```

- **`drainAndFold`** (`tryReceive` loop): consumes every queued command and folds them — the last stream-changing command (LoadQueue/SkipToNext/SkipToPrevious/SkipTo/Stop/SeekTo) wins (collapsing skip-spam into ONE stream open, falsify finding 6); Play/Pause set the target mode; Set* apply their latest value; Release is terminal. This is what makes the loop command-biased and skip-spam-cheap.
- **PAUSED is a first-class mode that blocks on `commands.receive()`** — it consumes NO frames (line stopped) yet stays fully command-responsive, so skip/stop/load work while paused (falsify finding B — never gate the loop on `_paused.first{}`).
- **`select` only runs in PLAYING**, and only after the command drain, so commands are prioritized over the always-ready frame channel (falsify finding C).

### 5.4 Generation token

`private var generation: Long = 0`, actor-owned. Incremented on every stream (re)open. Used to stamp/guard:
- The frame channel is **minted fresh per stream** and the old producer is cancelled+joined before a new one starts → stale frames cannot reach the new generation's `select` (falsify finding 3).
- `positionMs` is written only by the actor from the *current* stream → no stale-position regression (falsify finding F).
- The RG-gain application is computed + applied by the actor inline before the first frame of a generation is written → no async launch racing a newer transition (eliminates the old bug_003 class + first-frame-wrong-gain, falsify finding H).

### 5.5 Decode confinement + lifecycle (falsify findings #2, use-after-free, cancellation)

All `DecodedStream` lifecycle runs on `decodeDispatcher` (single thread; libFLAC handle is single-thread-only):

```kotlin
private suspend fun openStreamForTarget(t: StreamChange) {
    // 1. tear down current generation on the decode thread, JOINING the producer first
    withContext(decodeDispatcher) {
        producerJob?.cancelAndJoin()      // in-flight process_single completes (~1 frame), THEN producer exits
        currentStream?.close()            // libFLAC finish+delete — safe: producer joined, no callback in flight
    }
    generation++
    // 2. resolve + open the new stream (also decode-thread for open)
    val playable = resolve(t) ?: return idleOrError()
    currentStream = withContext(decodeDispatcher) { decoder.open(playable) }... // Either handling
    // 3. apply RG gain for THIS generation BEFORE producing frames
    applyRgGainInline(playable)
    // 4. start the producer on the decode dispatcher; mint a fresh channel
    frameChan = currentStream.frames.produceIn(CoroutineScope(decodeDispatcher + producerJob))
    line = openLine(currentStream.format)   // actor owns line open
    mode = if (t.autoPlay) Mode.PLAYING.also { line.start() } else Mode.PAUSED
}
```

Key guarantees: `cancelAndJoin()` before `close()` means the native delete never races an in-flight decode/callback (libFLAC `process_single` cannot be interrupted mid-call — **accepted**; we let it complete, bounded by one frame, then discard). Callback strong-refs live on the stream object until after the join + delete.

### 5.6 Frame write path

Unchanged in spirit (processors → `sourceLine.write` → position tick), but now inside `writeOrAdvanceOrError(result)`:
- `result` success → apply `_processors.value` (actor-owned), `write`, update `positionMs`.
- `result` closed, no cause → **EOF** → advance via `nextIndexOrNull` (existing logic) → `openStreamForTarget(next)` or → IDLE. `drain()` before close is made **superseded-generation-cancellable** (falsify finding E): if a command is already queued, skip the drain.
- `result` closed, with cause → **decode error** → `teardownAll()` + `_state = Error(DecodeFailed)` (distinguished from EOF by `closeCause`, falsify finding 5).

### 5.7 UI queue population (`:ui:components`, commonMain — #2a)

`LibraryTab.Content` already holds `tracks: List<MediaItem>`. Change the click handler to load the full list from the clicked index:

```kotlin
onTrackClick = { item ->
    val start = tracks.indexOf(item).coerceAtLeast(0)
    coroutineScope.launch { player.loadQueue(items = tracks, startIndex = start, autoPlay = true) }
}
```

`SearchTab` gets the analogous change (queue = the search-results list, start at the clicked result). `LibraryContent`'s `onTrackClick` may need to pass the index (or we keep `indexOf`; the list is ≤500 so `indexOf` is fine). This fix is commonMain → also repairs Android skip (same single-item-queue root).

### 5.8 Dispatchers / DI

`DesktopAppGraph` gains a **second single-thread dispatcher** for decode (daemon; NOT MAX_PRIORITY — only the audio output thread needs MAX_PRIORITY; decode at normal priority avoids starving Main/IO, falsify finding J). Built inline like `audioDispatcher`. `createJavaSoundPlayer(...)` takes the new `decodeDispatcher` param.

---

## 6. Concurrency model — the 8 falsify-defined safeguards

| # | Safeguard | Falsify finding addressed |
|---|---|---|
| 1 | `Channel.UNLIMITED` + `trySend` for command submission — never suspends, never freezes the UI caller | A (backpressure freezes UI) |
| 2 | Generation token + fresh per-stream channel + cancel-join-before-open → stale frames/EOF/error/position/gain dropped | 3, D, F, H, bug_003 |
| 3 | PAUSED is a distinct mode that blocks on `commands.receive()` — command-responsive while paused | B (pause-gate deadlock) |
| 4 | Command-biased loop: `drainAndFold()` before the frame `select` | C (`select` fairness long-tail) |
| 5 | Decode-confined lifecycle; `cancelAndJoin()` producer before `close()`; libFLAC non-interruptible accepted | #2, use-after-free, cancellation-doesn't-interrupt |
| 6 | Single-writer ALL visible state via the actor (no direct flag-op shortcut); one documented `line.stop()` write-interrupt seam | the "single-owner is a lie" cluster (play/pause/volume/repeat/shuffle direct), StateFlow non-linearizable |
| 7 | Bounded release: terminal `Release` command → close channel → cancel+join actor + decode scope → close owned dispatchers | release hang / daemon-thread leak / use-after-free |
| 8 | Tests with a fake **blocking** `SourceDataLine` + **blocking** fake decoder + deterministic dispatchers | test surface inadequacy, loadQueue async API-semantics break |

**Accepted (inherent):** `SourceDataLine.write` can block indefinitely on a hung device; `select` can't preempt a syscall-blocked actor. The write-interrupt seam (safeguard 6) mitigates the common case (pause/skip can unblock it); true device hangs are accepted on this transitional engine. EOF-vs-explicit-skip at the exact track boundary may land one item off the user's mental model — deterministic, not corrupting; documented, not fixed.

---

## 7. Error handling

- **Decode error** → channel closes with cause → `teardownAll()` + `PlayerState.Error(DecodeFailed)`. Not mistaken for EOF (`closeCause` inspected).
- **Device-unavailable** (line open throws) → `PlayerState.Error(DeviceUnavailable)`; stream closed on the decode thread.
- **EOF** → advance or → IDLE (existing `nextIndexOrNull` repeat/shuffle logic preserved).
- **Release during playback** → terminal command drains, single idempotent `teardownAll()`, scopes cancelled+joined, dispatchers closed. No use-after-free of native handles.
- **resolve (`source.getPlayable`) failure** for a queue item → log + skip-forward (existing loadQueue semantics), kept inside the actor but bounded; large-queue resolution is chunked so a long resolve can't starve other commands (falsify "eager resolution" finding).

---

## 8. Testing

Existing `JavaSoundPlayerImplTest` (Unconfined + canned-flow fake decoder, no real line) **cannot** see actor/timing/cancellation bugs. Add a deterministic harness:

- **Fake `SourceDataLine`** seam: a test double whose `write` blocks on a latch the test controls (to simulate a full/slow line) and records calls. (Requires extracting line creation behind a tiny injectable factory, or testing at the `DecodedStream`→actor seam.)
- **Blocking fake `Decoder`/`DecodedStream`**: a frame flow that blocks per-frame on a latch, can signal EOF, and can throw (decode error).
- **Injected dispatchers**: tests pass `StandardTestDispatcher`s for audio + decode; assert via virtual time.

Test cases (each maps to a safeguard / defect):
1. `loadQueue` while a track is "playing" (line latched/blocked) → the new track's stream is opened (defect #1; safeguards 1, 4, 6).
2. `skipToNext` on a multi-item queue while playing → advances (defect #2b).
3. Single-item-queue UI fix: `LibraryTab`/`SearchTab` build a multi-item queue from the clicked index (defect #2a — UI test, may assert on the `loadQueue` args via a fake player).
4. `pause()` then `skipToNext()` → skip is serviced while paused (safeguard 3).
5. Rapid `skipToNext` ×N (skip-spam) → exactly ONE final stream open (safeguard 4 fold).
6. Stale frames: producer for gen N keeps emitting after a gen N+1 switch → no gen-N frames written post-switch (safeguard 2).
7. EOF vs decode-error: channel closes normally → advance; closes with cause → Error (safeguard 2, §7).
8. `release()` mid-playback → idempotent teardown, no exceptions, scopes complete (safeguard 7).
9. Existing fidelity/RG tests (golden corpus, RG gain) stay green.

Canonical 6-target build green after each task.

---

## 9. Commit sequencing (for the plan)

1. UI queue population (`LibraryTab` + `SearchTab` + any `LibraryContent` index plumbing) — small, independent, cross-platform (#2a). Lands first; testable alone.
2. `PlayerCommand` + the actor refactor of `JavaSoundPlayerImpl` (the bulk; #1 + #2b) + decode dispatcher + DI wiring. One coherent change (the model only works whole).
3. Test harness (fake blocking line + blocking decoder) + the §8 cases.
4. Final verification (canonical build; desktop manual smoke: play → click another track → it switches; skip walks the list; pause→skip works).

(The #3 backfill-scope one-liner is a *separate* commit, landed independently — §10.)

---

## 10. Related: #3 backfill scope (separate, not this design)

`Main.kt:269` runs the RG backfill on the composition-bound `rememberCoroutineScope`; closing Settings cancels it. One-line fix: `coroutineScope.launch` → `appScope.launch(Dispatchers.Main)`, mirroring `runScanNow` (`Main.kt:203`). Distinct root → its own commit, not bundled here. Verified root cause Session 26.

---

## 11. Open questions for review

1. **Test seam for the line:** inject a `SourceDataLine` factory (cleanest for the blocking-line test) vs. test only at the decoder→actor seam (less coverage, smaller diff)? Recommend the factory.
2. **`drain()` on EOF:** keep the natural tail-drain (gapped) and make it command-cancellable, or drop `drain()` entirely (risk: clipped track tail)? Recommend command-cancellable drain.
3. **Worth it for a transitional engine?** This is real concurrency rigor for code WASAPI replaces in Phase 2b. Confirmed proceeding (Clay, 2026-06-25), but flagging once more in the spec for the record.
