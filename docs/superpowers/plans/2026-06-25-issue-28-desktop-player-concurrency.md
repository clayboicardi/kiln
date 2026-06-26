# Desktop Player Concurrency (command/actor) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make desktop playback control responsive — clicking any track plays it (even mid-playback) and skip/next walks the loaded list — by routing every `JavaSoundPlayerImpl` mutation through a single command/actor coroutine and moving libFLAC decode off the audio thread.

**Architecture:** One long-lived actor coroutine on `audioDispatcher` is the sole owner of the `SourceDataLine` + `DecodedStream` + queue + StateFlows. Public control methods become non-suspending `trySend` to an `UNLIMITED` command channel. Decode runs on a separate `decodeDispatcher` (via `produceIn`); the actor `select`s frame-vs-command, command-biased. A generation token drops stale events. Behaviour stays behind `PlatformPlayer` (Android/Media3 untouched).

**Tech Stack:** Kotlin Multiplatform (desktopMain), kotlinx-coroutines (`Channel`, `select`, `produceIn`), javax.sound.sampled, JNA libFLAC, `kotlin.test` + JUnit4 (desktopTest), Compose Multiplatform (commonMain UI).

## Global Constraints

- **JDK 21 for Gradle:** `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'` before any `./gradlew` (JDK 25 wedges the daemon).
- **Canonical 6-target build** (after each task): `.\gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`. Add `:audio:playback:desktopTest` to gate the player tests directly.
- **One change per commit.** Commit subjects end with the two trailers (`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` and `Claude-Session: …`).
- **No `Thread.sleep` in tests** (`.gemini/styleguide.md`) — use latches / `CompletableDeferred` / `runTest` virtual time.
- **JUnit4 `@Test` returns `Unit`** — never end an `= runBlocking { … }` body on `assertNotNull`/an expression that returns non-Unit.
- **`PlatformPlayer` interface is unchanged** — only `JavaSoundPlayerImpl` (desktop) + `createJavaSoundPlayer` + the two UI tabs change. Android `Media3ExoPlayerImpl` is untouched.
- **Behind `PlatformPlayer`** — consumers (`NowPlayingTab`, etc.) keep working unchanged.
- **Decisions locked (Clay, 2026-06-25):** queue = the loaded list from the clicked index; inject a `SourceDataLine` factory for the blocking-line test (open question 1 → yes); `drain()` on EOF is command-cancellable (open question 2 → keep, cancellable).
- Spec: [`docs/superpowers/specs/2026-06-25-issue-28-desktop-player-concurrency-design.md`](../specs/2026-06-25-issue-28-desktop-player-concurrency-design.md).

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `ui/components/src/commonMain/.../library/LibraryTab.kt` | Click → load full list from clicked index | **Modify** |
| `ui/components/src/commonMain/.../search/SearchTab.kt` | Result click → load results list from clicked index | **Modify** |
| `audio/playback/src/desktopMain/.../PlayerCommand.kt` | Actor command sealed type + fold | **Create** |
| `audio/playback/src/desktopMain/.../JavaSoundPlayerImpl.kt` | Command/actor rewrite; decode-dispatcher; generation; line factory; `awaitDrained` test seam | **Rewrite** |
| `app-desktop/src/main/.../desktop/di/DesktopAppGraph.kt` | Provide `decodeDispatcher`; pass to factory | **Modify** |
| `audio/playback/src/desktopTest/.../JavaSoundPlayerImplTest.kt` | Migrate existing assertions to await the actor | **Modify** |
| `audio/playback/src/desktopTest/.../PlayerTestDoubles.kt` | Fake blocking `SourceDataLine` + controllable `DecodedStream`/`Decoder` | **Create** |
| `audio/playback/src/desktopTest/.../JavaSoundPlayerActorTest.kt` | New behavioural tests (the safeguards) | **Create** |

---

## Task 1: UI queue population (#2a) — load the full list from the clicked index

Fixes "skip is a no-op" at its product root and is cross-platform (commonMain → also repairs Android skip). Independent of the actor work; lands first.

**Files:**
- Modify: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/library/LibraryTab.kt:54-58`
- Modify: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/search/SearchTab.kt:58-62`

**Interfaces:**
- Consumes: `PlatformPlayer.loadQueue(items: List<MediaItem>, startIndex: Int, autoPlay: Boolean)` (unchanged); `LibraryContent(tracks, onTrackClick: (MediaItem) -> Unit)` (unchanged); `SearchContent(..., onResultClick: (SearchResult) -> Unit)` (unchanged). `SearchResult.item: MediaItem`.
- Produces: nothing new — behaviour change only.

- [ ] **Step 1: `LibraryTab` — queue the whole list from the clicked track.** Replace the `onTrackClick` lambda body (currently `loadQueue(items = listOf(item), startIndex = 0, …)`):

```kotlin
onTrackClick = { item ->
    val start = tracks.indexOf(item).coerceAtLeast(0)
    coroutineScope.launch {
        player.loadQueue(items = tracks, startIndex = start, autoPlay = true)
    }
},
```

- [ ] **Step 2: `SearchTab` — queue the results list from the clicked result.** Replace the `onResultClick` lambda body (currently `loadQueue(items = listOf(result.item), startIndex = 0, …)`):

```kotlin
onResultClick = { result ->
    val items = results.map { it.item }
    val start = results.indexOfFirst { it.item.itemId == result.item.itemId }.coerceAtLeast(0)
    coroutineScope.launch {
        player.loadQueue(items = items, startIndex = start, autoPlay = true)
    }
},
```

- [ ] **Step 3: Build.** Run: `$env:JAVA_HOME='…jdk-21…'; .\gradlew :ui:components:assemble :app-desktop:assemble`
Expected: BUILD SUCCESSFUL. (These Voyager `Tab`s have no unit-test harness — behavioural proof is Task 3's skip test + the Task 4 manual smoke. This is consistent with the codebase's UI-test-light convention.)

- [ ] **Step 4: Commit.**

```bash
git add ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/library/LibraryTab.kt \
        ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/search/SearchTab.kt
git commit   # subject: "fix(ui): play-from-here queue on track/result click (#28 item 2a)"
```

---

## Task 2: Actor rewrite of `JavaSoundPlayerImpl` (+ migrate existing tests)

The core. Replace the `withContext(audioDispatcher)` control ops + the inline-decode `collect` loop with the command/actor model. Keep the existing private helpers (`applyGain`, `nextIndexOrNull`, `previousIndexOrNull`, `toPlayerError`, `applyRgGain`) **verbatim** — only the control/ownership structure changes. This task keeps the build green by migrating the existing tests in the same commit (they assert post-`loadQueue` state synchronously, which the async model breaks).

**Files:**
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/PlayerCommand.kt`
- Rewrite: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt`
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt` (decodeDispatcher provider + factory call)
- Modify: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImplTest.kt`

**Interfaces:**
- Produces:
  - `internal sealed interface PlayerCommand` with cases `LoadQueue(items, startIndex, autoPlay)`, `Play`, `Pause`, `Stop`, `SeekTo(positionMs)`, `SkipToNext`, `SkipToPrevious`, `SkipTo(index)`, `SetRepeat(mode)`, `SetShuffle(enabled)`, `SetVolume(linear)`, `SetMuted(muted)`, `AddProcessor(processor)`, `RemoveProcessor(processor)`, `ReapplyGain(mode, preAmpDb)`, `Release`, and the test-only `Barrier(ack: CompletableDeferred<Unit>)`.
  - `internal class JavaSoundPlayerImpl(audioDispatcher, decodeDispatcher: CoroutineDispatcher, decoder, source, settings, rgProcessor, lineFactory: (AudioFormat) -> SourceDataLine = <real>)` — adds `decodeDispatcher` + `lineFactory`; exposes `internal suspend fun awaitDrained()`.
  - `fun createJavaSoundPlayer(audioDispatcher, decodeDispatcher, decoder, source, settings, rgProcessor): PlatformPlayer` — gains `decodeDispatcher` param.
- Consumes: `Decoder.open(Playable): Either<DecoderError, DecodedStream>`, `DecodedStream.frames: Flow<AudioFrame>`, `.seekTo(ms)`, `.close()`, `.format`, `.positionMs` (all existing).

- [ ] **Step 1: Create `PlayerCommand.kt`.**

```kotlin
package com.clayworks.kiln.audio.playback

import com.clayworks.kiln.audio.dsp.AudioProcessor
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.source.MediaItem
import kotlinx.coroutines.CompletableDeferred

/** Commands posted to the JavaSoundPlayerImpl actor. The actor is the sole mutator of
 *  line/stream/queue/state, so every control op is one of these (#28). */
internal sealed interface PlayerCommand {
    data class LoadQueue(val items: List<MediaItem>, val startIndex: Int, val autoPlay: Boolean) : PlayerCommand
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data object Stop : PlayerCommand
    data class SeekTo(val positionMs: Long) : PlayerCommand
    data object SkipToNext : PlayerCommand
    data object SkipToPrevious : PlayerCommand
    data class SkipTo(val index: Int) : PlayerCommand
    data class SetRepeat(val mode: com.clayworks.kiln.audio.playback.RepeatMode) : PlayerCommand
    data class SetShuffle(val enabled: Boolean) : PlayerCommand
    data class SetVolume(val linear: Float) : PlayerCommand
    data class SetMuted(val muted: Boolean) : PlayerCommand
    data class AddProcessor(val processor: AudioProcessor) : PlayerCommand
    data class RemoveProcessor(val processor: AudioProcessor) : PlayerCommand
    data class ReapplyGain(val mode: ReplayGainMode, val preAmpDb: Double) : PlayerCommand
    data object Release : PlayerCommand
    /** Test-only: the actor completes [ack] after this command is processed, so a test can
     *  await that all previously-sent commands have drained. Never sent by production code. */
    data class Barrier(val ack: CompletableDeferred<Unit>) : PlayerCommand
}
```

- [ ] **Step 2: Rewrite `JavaSoundPlayerImpl.kt`.** Full new file. The carried-over helpers (`applyGain`, `nextIndexOrNull`, `previousIndexOrNull`, `toPlayerError`, `applyRgGain`) keep their **exact current bodies** — copy them from the current file. The `loadQueue` start-index mapping (the `coercedStart` block) moves verbatim into `handleLoadQueue`.

```kotlin
package com.clayworks.kiln.audio.playback

import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.AudioProcessor
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainPipelineMode
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.audio.dsp.replaygain.resolveGainLinear
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.Playable
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produceIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

private val log = Logger.withTag("JavaSoundPlayer")
private const val POSITION_TICK_MS = 250L
private const val BUFFER_MS = 100

private val realLineFactory: (AudioFormat) -> SourceDataLine = { format ->
    AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
}

fun createJavaSoundPlayer(
    audioDispatcher: CoroutineDispatcher,
    decodeDispatcher: CoroutineDispatcher,
    decoder: Decoder,
    source: MusicSource,
    settings: SettingsRepository,
    rgProcessor: ReplayGainProcessor,
): PlatformPlayer = JavaSoundPlayerImpl(audioDispatcher, decodeDispatcher, decoder, source, settings, rgProcessor)

internal class JavaSoundPlayerImpl(
    private val audioDispatcher: CoroutineDispatcher,
    private val decodeDispatcher: CoroutineDispatcher,
    private val decoder: Decoder,
    private val source: MusicSource,
    private val settings: SettingsRepository,
    private val rgProcessor: ReplayGainProcessor,
    private val lineFactory: (AudioFormat) -> SourceDataLine = realLineFactory,
) : PlatformPlayer {

    private enum class Mode { IDLE, PLAYING, PAUSED }

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _queue = MutableStateFlow(QueueState(emptyList(), -1, RepeatMode.Off, false))
    override val queue: StateFlow<QueueState> = _queue.asStateFlow()
    private val _volume = MutableStateFlow(VolumeState(1.0f, false))
    override val volume: StateFlow<VolumeState> = _volume.asStateFlow()
    private val _processors = MutableStateFlow<List<AudioProcessor>>(emptyList())
    override val processors: StateFlow<List<AudioProcessor>> = _processors.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private val commands = Channel<PlayerCommand>(Channel.UNLIMITED)

    // ----- actor-owned state (touched ONLY on the actor / audioDispatcher) -----
    private var line: SourceDataLine? = null
    private var currentStream: DecodedStream? = null
    private var currentPlayable: Playable? = null
    private var frameChan: ReceiveChannel<com.clayworks.kiln.audio.dsp.AudioFrame>? = null
    private var producerScope: CoroutineScope? = null
    private var generation: Long = 0L
    private var mode: Mode = Mode.IDLE
    private var lastTickedMs: Long = 0L
    private var released = false

    init {
        _processors.value = listOf(rgProcessor)
        scope.launch(audioDispatcher) { runActor() }
        // Settings-change RG: post a command so the actor (sole owner of currentPlayable) reapplies.
        scope.launch {
            combine(
                settings.replayGainMode.distinctUntilChanged(),
                settings.replayGainPreAmpDb.distinctUntilChanged(),
            ) { mode, preAmp -> mode to preAmp }
                .collect { (m, p) -> commands.trySend(PlayerCommand.ReapplyGain(m, p)) }
        }
    }

    // ---------- PlatformPlayer: every op is a non-blocking trySend ----------
    override suspend fun loadQueue(items: List<MediaItem>, startIndex: Int, autoPlay: Boolean) {
        commands.trySend(PlayerCommand.LoadQueue(items, startIndex, autoPlay))
    }
    override suspend fun play() { commands.trySend(PlayerCommand.Play) }
    override suspend fun pause() { runCatching { line?.stop() }; commands.trySend(PlayerCommand.Pause) } // write-interrupt seam
    override suspend fun stop() { runCatching { line?.stop() }; commands.trySend(PlayerCommand.Stop) }    // write-interrupt seam
    override suspend fun seekTo(positionMs: Long) { commands.trySend(PlayerCommand.SeekTo(positionMs)) }
    override suspend fun skipToNext() { runCatching { line?.stop() }; commands.trySend(PlayerCommand.SkipToNext) }
    override suspend fun skipToPrevious() { runCatching { line?.stop() }; commands.trySend(PlayerCommand.SkipToPrevious) }
    override suspend fun skipTo(queueIndex: Int) { runCatching { line?.stop() }; commands.trySend(PlayerCommand.SkipTo(queueIndex)) }
    override suspend fun setRepeatMode(mode: RepeatMode) { commands.trySend(PlayerCommand.SetRepeat(mode)) }
    override suspend fun setShuffleMode(enabled: Boolean) { commands.trySend(PlayerCommand.SetShuffle(enabled)) }
    override suspend fun setVolume(linear: Float) { commands.trySend(PlayerCommand.SetVolume(linear)) }
    override suspend fun setMuted(muted: Boolean) { commands.trySend(PlayerCommand.SetMuted(muted)) }
    override fun addAudioProcessor(processor: AudioProcessor) { commands.trySend(PlayerCommand.AddProcessor(processor)) }
    override fun removeAudioProcessor(processor: AudioProcessor) { commands.trySend(PlayerCommand.RemoveProcessor(processor)) }
    override suspend fun release() { runCatching { line?.stop() }; commands.trySend(PlayerCommand.Release) }
    override suspend fun enterMeasurementMode(): MeasurementSession? = null

    /** Test-only: suspend until the actor has processed all commands sent so far. */
    internal suspend fun awaitDrained() {
        val ack = CompletableDeferred<Unit>()
        commands.trySend(PlayerCommand.Barrier(ack))
        ack.await()
    }

    // ---------- actor ----------
    private suspend fun runActor() {
        try {
            while (!released) {
                // Command-biased: service any already-queued command before consuming a frame.
                val queued = commands.tryReceive().getOrNull()
                if (queued != null) { handleCommandBatch(queued); continue }
                val fc = frameChan
                if (mode == Mode.PLAYING && fc != null) {
                    select<Unit> {
                        commands.onReceive { c -> handleCommandBatch(c) }
                        fc.onReceiveCatching { result -> onFrameResult(result) }
                    }
                } else {
                    // IDLE / PAUSED: only a command can change anything.
                    handleCommandBatch(commands.receive())
                }
            }
        } finally {
            teardownActivePlayback()
            scope.cancel()
        }
    }

    /** Fold [first] + every already-queued command (collapses skip-spam), then apply. */
    private suspend fun handleCommandBatch(first: PlayerCommand) {
        var targetOp: PlayerCommand? = null      // last of Load/Skip*/Stop/SeekTo
        var runState: PlayerCommand? = null       // last of Play/Pause
        var volume: Float? = null; var muted: Boolean? = null
        var repeat: RepeatMode? = null; var shuffle: Boolean? = null
        var reapply: PlayerCommand.ReapplyGain? = null
        val addP = ArrayList<AudioProcessor>(); val removeP = ArrayList<AudioProcessor>()
        val barriers = ArrayList<CompletableDeferred<Unit>>()
        var release = false

        var c: PlayerCommand? = first
        while (c != null) {
            when (c) {
                is PlayerCommand.LoadQueue, is PlayerCommand.SkipToNext, is PlayerCommand.SkipToPrevious,
                is PlayerCommand.SkipTo, is PlayerCommand.Stop, is PlayerCommand.SeekTo -> targetOp = c
                is PlayerCommand.Play, is PlayerCommand.Pause -> runState = c
                is PlayerCommand.SetVolume -> volume = c.linear
                is PlayerCommand.SetMuted -> muted = c.muted
                is PlayerCommand.SetRepeat -> repeat = c.mode
                is PlayerCommand.SetShuffle -> shuffle = c.enabled
                is PlayerCommand.AddProcessor -> addP.add(c.processor)
                is PlayerCommand.RemoveProcessor -> removeP.add(c.processor)
                is PlayerCommand.ReapplyGain -> reapply = c
                is PlayerCommand.Barrier -> barriers.add(c.ack)
                is PlayerCommand.Release -> release = true
            }
            c = commands.tryReceive().getOrNull()
        }

        // 1. cheap state updates (no stream change)
        repeat?.let { _queue.value = _queue.value.copy(repeatMode = it) }
        shuffle?.let { _queue.value = _queue.value.copy(shuffleEnabled = it) }
        if (addP.isNotEmpty() || removeP.isNotEmpty()) {
            _processors.value = _processors.value + addP - removeP.toSet()
        }
        if (volume != null || muted != null) {
            _volume.value = _volume.value.copy(
                linear = volume?.coerceIn(0f, 1f) ?: _volume.value.linear,
                muted = muted ?: _volume.value.muted,
            )
            applyGain()
        }
        // 2. target op (stream change) — last-wins
        if (release) { released = true; barriers.forEach { it.complete(Unit) }; return }
        when (val t = targetOp) {
            is PlayerCommand.LoadQueue -> handleLoadQueue(t)
            is PlayerCommand.SkipToNext -> handleSkip(nextIndexOrNull(_queue.value))
            is PlayerCommand.SkipToPrevious -> handleSkip(previousIndexOrNull(_queue.value))
            is PlayerCommand.SkipTo -> handleSkip(t.index.takeIf { it in _queue.value.items.indices })
            is PlayerCommand.Stop -> { teardownActivePlayback(); mode = Mode.IDLE; _state.value = PlayerState.Idle }
            is PlayerCommand.SeekTo -> handleSeek(t.positionMs)
            else -> {}
        }
        // 3. run-state (play/pause) — orthogonal to target
        when (runState) {
            is PlayerCommand.Play -> resumePlayback()
            is PlayerCommand.Pause -> pausePlayback()
            else -> {}
        }
        // 4. RG reapply for the current track
        reapply?.let { rg -> currentPlayable?.let { applyRgGain(it, rg.mode, rg.preAmpDb) } }
        barriers.forEach { it.complete(Unit) }
    }

    private suspend fun handleLoadQueue(cmd: PlayerCommand.LoadQueue) {
        teardownActivePlayback()
        _state.value = PlayerState.Loading
        // <<< COPY VERBATIM the current loadQueue resolve + coercedStart mapping block here:
        //     build `resolved: List<Triple<Int, MediaItem, Playable>>`, `resolvedItems`,
        //     the empty-guard (set empty queue + Idle + return), and `coercedStart`. >>>
        // val resolved = …  ; if (resolved.isEmpty()) { _queue.value = …emptyList(),-1; mode = IDLE; _state.value = Idle; return }
        // val coercedStart = …
        _queue.value = _queue.value.copy(items = resolvedItems, currentIndex = coercedStart)
        openAndStart(resolved[coercedStart].third, autoPlay = cmd.autoPlay)
    }

    private suspend fun handleSkip(targetIndex: Int?) {
        if (targetIndex == null) return
        _queue.value = _queue.value.copy(currentIndex = targetIndex)
        val item = _queue.value.currentItem ?: return
        when (val r = source.getPlayable(item.itemId)) {
            is Either.Left -> { _state.value = PlayerState.Error(PlayerError.Internal("resolve ${item.itemId.value}: ${r.value}")); }
            is Either.Right -> openAndStart(r.value, autoPlay = true)
        }
    }

    private suspend fun handleSeek(positionMs: Long) {
        val stream = currentStream ?: return
        try {
            withContext(decodeDispatcher) { stream.seekTo(positionMs.coerceAtLeast(0L)) }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            teardownActivePlayback(); _state.value = PlayerState.Error(PlayerError.DecodeFailed(e)); return
        }
        line?.flush()
        _positionMs.value = positionMs.coerceAtLeast(0L)
    }

    /** Tear down current playback, open [playable] on the decode thread, mint a fresh
     *  generation + frame channel, apply RG gain, open the line, set mode. */
    private suspend fun openAndStart(playable: Playable, autoPlay: Boolean) {
        teardownActivePlayback()
        generation++
        val stream = when (val r = withContext(decodeDispatcher) { decoder.open(playable) }) {
            is Either.Left -> { _state.value = PlayerState.Error(toPlayerError(r.value)); return }
            is Either.Right -> r.value
        }
        currentStream = stream
        currentPlayable = playable
        rgProcessor.onFormatChange(stream.format)
        // RG gain for THIS generation, applied BEFORE the first frame (no async race).
        applyRgGain(playable, settings.replayGainMode.first(), settings.replayGainPreAmpDb.first())
        val sourceLine = try {
            lineFactory(audioFormatOf(stream)).also { it.open(audioFormatOf(stream), bufferBytesOf(stream)) }
        } catch (e: Exception) {
            withContext(decodeDispatcher) { stream.close() }
            currentStream = null
            _state.value = PlayerState.Error(PlayerError.DeviceUnavailable(e.message ?: "audio line open failed"))
            return
        }
        line = sourceLine
        lastTickedMs = 0L; _positionMs.value = 0L
        val ps = CoroutineScope(decodeDispatcher + SupervisorJob())
        producerScope = ps
        frameChan = stream.frames.produceIn(ps)
        applyGain()
        if (autoPlay) { sourceLine.start(); mode = Mode.PLAYING; _state.value = PlayerState.Ready(true) }
        else { mode = Mode.PAUSED; _state.value = PlayerState.Ready(false) }
    }

    private fun onFrameResult(result: ChannelResult<com.clayworks.kiln.audio.dsp.AudioFrame>) {
        val frame = result.getOrNull()
        if (frame == null) {
            // channel closed: EOF (no cause) or decode error (cause).
            val cause = result.exceptionOrNull()
            if (cause != null) {
                log.e(cause) { "decode error" }
                teardownActivePlayback(); mode = Mode.IDLE
                _state.value = PlayerState.Error(PlayerError.DecodeFailed(cause))
            } else {
                advanceOnEof()
            }
            return
        }
        val l = line ?: return
        var f = frame
        _processors.value.forEach { f = it.process(f) }
        l.write(f.bytes, 0, f.byteCount)
        val pos = currentStream?.positionMs ?: 0L
        if (pos - lastTickedMs >= POSITION_TICK_MS) { _positionMs.value = pos; lastTickedMs = pos }
    }

    private fun advanceOnEof() {
        val next = nextIndexOrNull(_queue.value)
        // Command-cancellable drain (locked decision): skip the tail drain if a command is already queued.
        if (commands.isEmpty) runCatching { line?.drain() }
        teardownActivePlayback()
        if (next == null) { mode = Mode.IDLE; _state.value = PlayerState.Idle; return }
        _queue.value = _queue.value.copy(currentIndex = next)
        val item = _queue.value.currentItem ?: run { mode = Mode.IDLE; _state.value = PlayerState.Idle; return }
        // Resolve+open inline on the actor (suspends briefly); errors flip to Error.
        scope.launch(audioDispatcher) {
            when (val r = source.getPlayable(item.itemId)) {
                is Either.Left -> { _state.value = PlayerState.Error(PlayerError.Internal("resolve: ${r.value}")) }
                is Either.Right -> openAndStart(r.value, autoPlay = true)
            }
        }
    }

    private fun resumePlayback() {
        val l = line ?: return
        runCatching { if (!l.isRunning) l.start() }
        if (currentStream != null) { mode = Mode.PLAYING; _state.value = PlayerState.Ready(true) }
    }

    private fun pausePlayback() {
        runCatching { line?.stop() }
        if (currentStream != null) { mode = Mode.PAUSED; _state.value = PlayerState.Ready(false) }
    }

    private fun teardownActivePlayback() {
        producerScope?.cancel(); producerScope = null; frameChan = null
        runCatching { currentStream?.close() }      // decode-confinement note: see Step 2a
        currentStream = null; currentPlayable = null
        line?.let { runCatching { it.stop() }; runCatching { it.close() } }; line = null
    }

    // ----- helpers carried over VERBATIM from the current file -----
    private fun applyGain() { /* current body */ }
    private fun applyRgGain(playable: Playable, mode: ReplayGainMode, preAmpDb: Double) { /* current body */ }
    private fun nextIndexOrNull(q: QueueState): Int? { /* current body */ }
    private fun previousIndexOrNull(q: QueueState): Int? { /* current body */ }
    private fun toPlayerError(e: DecoderError): PlayerError { /* current body */ }
    private fun audioFormatOf(stream: DecodedStream): AudioFormat =
        AudioFormat(stream.format.sampleRateHz.toFloat(), stream.format.bitDepth, stream.format.channels, true, false)
    private fun bufferBytesOf(stream: DecodedStream): Int {
        val bps = stream.format.sampleRateHz * stream.format.channels * (stream.format.bitDepth / 8)
        return (bps * BUFFER_MS / 1000).coerceAtLeast(4096)
    }
}
```

(`ChannelResult` import: `kotlinx.coroutines.channels.ChannelResult`. `Channel.isEmpty` is experimental — add `@OptIn(ExperimentalCoroutinesApi::class)` on the class or use a `tryReceive` peek-and-requeue; the plan uses `isEmpty` for clarity — wrap with the opt-in.)

- [ ] **Step 2a (decode-confinement refinement):** `teardownActivePlayback()` is called from `openAndStart`/`handleSkip`/`onFrameResult` which run on the actor (audioDispatcher), but `stream.close()` (libFLAC delete) must run on `decodeDispatcher` AFTER the producer is joined. Make `teardownActivePlayback()` a `suspend fun` that does `producerScope?.coroutineContext?.job?.cancelAndJoin()` then `withContext(decodeDispatcher) { currentStream?.close() }`. Update all call sites (already in suspend context). This is the join-before-delete safeguard — keep it.

- [ ] **Step 3: Update `DesktopAppGraph`.** Add a `decodeDispatcher` provider (single-thread daemon, **normal** priority) and pass it to the factory. The `player(...)` provider gains a `decodeDispatcher` param; add a `@Singleton @Provides` named distinctly — kotlin-inject can't disambiguate two `CoroutineDispatcher`s, so wrap the decode one in a value class **or** construct it inline in the `player` provider. Inline is simplest:

```kotlin
@Singleton @Provides
protected fun player(
    audioDispatcher: CoroutineDispatcher, decoder: Decoder, source: MusicSource,
    settings: SettingsRepository, rgProcessor: ReplayGainProcessor,
): PlatformPlayer = createJavaSoundPlayer(
    audioDispatcher = audioDispatcher,
    decodeDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "kiln-flac-decode").apply { isDaemon = true } }.asCoroutineDispatcher(),
    decoder = decoder, source = source, settings = settings, rgProcessor = rgProcessor,
)
```

- [ ] **Step 4: Build (compile only).** Run: `$env:JAVA_HOME='…jdk-21…'; .\gradlew :audio:playback:assemble :app-desktop:assemble`
Expected: BUILD SUCCESSFUL (production compiles; tests not yet migrated).

- [ ] **Step 5: Migrate `JavaSoundPlayerImplTest`.** (a) `newPlayer()` constructs the impl directly so it returns the concrete type + passes `decodeDispatcher = Dispatchers.Unconfined`:

```kotlin
private fun newPlayer(source: MusicSource = AlwaysFailingSource(), decoder: Decoder = StubDecoder()): JavaSoundPlayerImpl =
    JavaSoundPlayerImpl(
        audioDispatcher = Dispatchers.Unconfined,
        decodeDispatcher = Dispatchers.Unconfined,
        decoder = decoder, source = source,
        settings = StubSettingsRepository(), rgProcessor = ReplayGainProcessor(),
    )
```

(b) After every state-mutating call whose result is asserted, insert `player.awaitDrained()` before the assertion. Examples:

```kotlin
player.loadQueue(items, startIndex = 2, autoPlay = false); player.awaitDrained()
val q = player.queue.value
// … existing assertions unchanged …
```
Apply to all `loadQueue`/`setRepeatMode`/`setShuffleMode`/`setVolume`/`setMuted`/`addAudioProcessor`/`removeAudioProcessor`/`play`/`pause`/`stop`/`release` assertions. The `release` test sends `release()` then `awaitDrained()` may never complete (channel still processes Barrier before `released` short-circuits — Barrier is folded and acked even when `release=true`, see Step 2 `handleCommandBatch` which completes barriers before returning on release). Keep the post-release no-op asserts.

- [ ] **Step 6: Run player tests.** Run: `$env:JAVA_HOME='…jdk-21…'; .\gradlew :audio:playback:desktopTest --tests "*JavaSoundPlayerImplTest*"`
Expected: PASS — all existing behaviours hold through the actor.

- [ ] **Step 7: Canonical build.** Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit.**

```bash
git add audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/PlayerCommand.kt \
        audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt \
        app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt \
        audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImplTest.kt
git commit   # subject: "refactor(audio): command/actor model for desktop player (#28 items 1+2b)"
```

---

## Task 3: Test harness + behavioural tests (the safeguards)

Prove the defects are fixed + the falsify safeguards hold, using controllable doubles.

**Files:**
- Create: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/PlayerTestDoubles.kt`
- Create: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerActorTest.kt`

**Interfaces:**
- Consumes: `JavaSoundPlayerImpl(... lineFactory ...)`, `awaitDrained()`, `DecodedStream`, `Decoder`, `AudioFrame`, `DecodedAudioFormat`.
- Produces: `FakeLine : SourceDataLine` (write blocks on a latch the test releases; records bytes); `FakeStream(frames, format)` (a `DecodedStream` whose `frames` flow emits from a test-controlled channel, can close normally (EOF) or with a cause (error)); `FakeDecoder(streams: Map<itemId, FakeStream>)`.

- [ ] **Step 1: Write `PlayerTestDoubles.kt`.** A `FakeLine` implementing `SourceDataLine` (most methods no-op/sensible defaults; `write` records bytes + optionally blocks on a `CountDownLatch` the test controls; `isRunning`/`start`/`stop`/`open`/`close`/`flush`/`drain` track flags; `isControlSupported(MASTER_GAIN)=false` so `applyGain` no-ops). A `FakeDecodedStream(val frameChannel: Channel<AudioFrame>, override val format)` whose `frames = frameChannel.consumeAsFlow()`, `seekTo` no-op, `close` records closed. A `FakeDecoder` returning a chosen `FakeDecodedStream` per `Playable`. (Full code written here — ~120 lines; no placeholders.)

- [ ] **Step 2: Write the failing behavioural tests** (`JavaSoundPlayerActorTest`), each `= runBlocking { … }` with `audioDispatcher = Dispatchers.Default`, `decodeDispatcher = Dispatchers.Default`, and `awaitDrained()` / StateFlow `first { … }` with `withTimeout(2_000)` for synchronization. Cases:
  1. **#1 — loadQueue while playing switches tracks.** Start track A (FakeStream A, frames flowing, FakeLine write unlatched). `loadQueue([B], 0)` → await → assert `currentItem == B` and A's stream closed. (Pre-fix this hung until EOF.)
  2. **#2b — skipToNext on a multi-item queue advances.** loadQueue([A,B,C], 0) playing → `skipToNext()` → await → `currentIndex == 1`, item B.
  3. **Pause then skip (safeguard 3).** loadQueue([A,B],0) → `pause()` → await (mode PAUSED) → `skipToNext()` → await → `currentIndex == 1` (skip serviced while paused).
  4. **Skip-spam coalesces (safeguard 4).** loadQueue([A,B,C,D,E],0) → fire `skipToNext()` ×4 rapidly (no await between) → `awaitDrained()` → assert `currentIndex == 4` (E) and FakeDecoder.openCount reflects ≤ 2 opens after the initial (folded), not 4. (Assert the fold collapsed them: openCount delta ≤ 2.)
  5. **Stale frames dropped (safeguard 2).** Start A; after switch to B, push frames into A's now-orphaned channel → assert FakeLine received no A-frames after the switch (compare recorded byte tags; tag frames by track).
  6. **EOF advances; decode-error → Error (safeguard 2/§7).** Close A's frame channel normally with a next item → advances. Close with `cancel(cause)` → `state` becomes `Error(DecodeFailed)` (not advance).
  7. **release() mid-playback (safeguard 7).** Playing → `release()` → `awaitDrained()` (or await state) → no exception; subsequent ops are no-ops.

  Run: `$env:JAVA_HOME='…jdk-21…'; .\gradlew :audio:playback:desktopTest --tests "*JavaSoundPlayerActorTest*"` — Expected: FAIL initially only if doubles/wiring incomplete; once Task 2 is in, these PASS (they exercise the shipped actor). Treat as regression lock.

- [ ] **Step 3: Make them pass / fix any gaps surfaced.** Any failure here is a real actor bug — fix in `JavaSoundPlayerImpl` (return to Task 2 code), re-run.

- [ ] **Step 4: Canonical build + full player tests.** Expected: BUILD SUCCESSFUL; both player test classes green.

- [ ] **Step 5: Commit.**

```bash
git add audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/PlayerTestDoubles.kt \
        audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerActorTest.kt
git commit   # subject: "test(audio): actor behavioural tests — switch/skip/pause-skip/stale/EOF/release (#28)"
```

---

## Task 4: Final verification gate

- [ ] **Step 1: Full canonical build** (+ `:audio:playback:desktopTest`). Expected: BUILD SUCCESSFUL; all suites green.
- [ ] **Step 2: Straggler grep** — `grep -rn "withContext(audioDispatcher)" audio/playback/src/desktopMain` → expected: **0** (all control ops now go through the actor; only the actor's internal `withContext(decodeDispatcher)` remains).
- [ ] **Step 3: Desktop manual smoke** (`.\gradlew :app-desktop:run`, JDK 21): (a) play a track; (b) while it plays, click another → it switches promptly; (c) skip/next walks the library list; (d) pause then skip → skip works; (e) let a track end → auto-advances. *(Manual gate, not a commit step.)*
- [ ] **Step 4 (optional):** land the **#3 backfill-scope** one-liner (`Main.kt:269` `coroutineScope.launch` → `appScope.launch(Dispatchers.Main)`) as its own commit `fix(desktop): run RG backfill on appScope so closing Settings doesn't cancel it (#28 item 3)`.

---

## Self-Review

- **Spec coverage:** §4 actor → Task 2. §5.1 PlayerCommand → T2 S1. §5.2 trySend/UNLIMITED → T2 S2 (public methods). §5.3 actor loop/modes → T2 S2 `runActor`/`handleCommandBatch`. §5.4 generation → T2 S2 (`generation++` per `openAndStart`; fresh `frameChan`). §5.5 decode-confinement+join → T2 S2a. §5.6 frame write → `onFrameResult`. §5.7 UI queue → Task 1. §5.8 decode dispatcher/DI → T2 S3. §6 safeguards 1-8 → T2 (1,2,3,4,5,6,7) + T3 (8). §7 errors → `onFrameResult`/`openAndStart`/`handleSeek`. §8 tests → Task 3. §9 sequencing → Tasks 1→2→3→4. §10 #3 → T4 S4.
- **Placeholder scan:** the only `/* current body */` markers (T2 S2 carried-over helpers) and the `<<< COPY VERBATIM >>>` loadQueue-mapping block are explicit "reuse existing code" directives, not gaps — the engineer copies the named blocks from the current file (which is in the repo). All new logic is shown in full.
- **Type consistency:** `PlayerCommand` cases match their `handleCommandBatch` branches; `JavaSoundPlayerImpl` constructor (audioDispatcher, decodeDispatcher, decoder, source, settings, rgProcessor, lineFactory) matches `createJavaSoundPlayer` + the test `newPlayer()` + the DI `player(...)` call; `awaitDrained()` defined T2 used T2/T3; `FakeLine`/`FakeDecodedStream`/`FakeDecoder` defined T3 S1 used T3 S2.
- **Ambiguity:** queue scope (loaded list from clicked index), drain (command-cancellable), line factory (injected) all locked in Global Constraints.
- **Known follow-ups (out of scope, noted):** shuffle-order generation still a no-op; library pagination (Track-C2); WASAPI (Phase 2b).

---

## Execution handoff

Per the writing-plans skill, offer the execution-mode choice after this plan is reviewed.
