// JavaSoundPlayerImpl — Desktop PlatformPlayer: WASAPI shared-mode output (Java Sound fallback)
// + the JNA libFLAC Decoder pipeline. (The class name predates #37; the output engine is chosen
// by DesktopOutputLineFactory behind the PcmOutputLine seam.)
//
// Concurrency model (#28 — command/actor): ONE long-lived actor coroutine on
// [audioDispatcher] is the SOLE owner/mutator of the output line (PcmOutputLine) + DecodedStream +
// queue index + the five PlatformPlayer StateFlows plus `outputRoute`, and the `notices`
// SharedFlow. Every control op is a non-suspending `commands.trySend(PlayerCommand.X)` to an
// UNLIMITED channel — so control ops can never be starved by, or race, the playback loop.
// Decode runs on its own [decodeDispatcher] (frames arrive via produceIn), so the actor thread
// is never blocked by libFLAC decode — only by the bounded PcmOutputLine.write. The actor
// `select`s frame-vs-command, command-biased (it drains queued commands before consuming a
// frame), so a skip/track-change is serviced within ~one write even mid-playback.
//
// This retired the prior design where loadQueue/skip*/stop/seekTo used
// withContext(audioDispatcher) and queued behind the blocking decode+write loop (they only
// ran once the track hit EOF). See docs/superpowers/specs/2026-06-25-issue-28-*.
//
// The ONLY cross-thread touch of the line is `PcmOutputLine.interrupt()` in loadQueue/pause/
// stop/skip/release (the write-interrupt seam). It unblocks a stuck write() so the actor can
// make progress; the actor remains the authority on all subsequent line state.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.AudioProcessor
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainPipelineMode
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.audio.dsp.replaygain.resolveGainLinear
import com.clayworks.kiln.audio.playback.output.DesktopOutputLineFactory
import com.clayworks.kiln.audio.playback.output.OutputLineException
import com.clayworks.kiln.audio.playback.output.PcmOutputLine
import com.clayworks.kiln.audio.playback.output.PcmOutputLineFactory
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

private val log = Logger.withTag("JavaSoundPlayer")

private const val POSITION_TICK_MS = 250L

internal const val MAX_CONSECUTIVE_FAILURES = 3

/**
 * Construct the desktop player. Keeps [JavaSoundPlayerImpl] internal — consumers receive only
 * the [DesktopPlayer] interface ([PlatformPlayer] + [PlaybackDiagnostics]). Caller supplies the
 * single-thread [audioDispatcher] (MAX_PRIORITY recommended) for the actor + every output-line
 * call (WASAPI's COM objects stay on the thread that opened them), a single-thread
 * [decodeDispatcher] for libFLAC decode, the [decoder], the [source] for resolving
 * [MediaItem]s, [settings], and the shared [rgProcessor].
 */
fun createJavaSoundPlayer(
    audioDispatcher: CoroutineDispatcher,
    decodeDispatcher: CoroutineDispatcher,
    decoder: Decoder,
    source: MusicSource,
    settings: SettingsRepository,
    rgProcessor: ReplayGainProcessor,
): DesktopPlayer = JavaSoundPlayerImpl(audioDispatcher, decodeDispatcher, decoder, source, settings, rgProcessor)

internal class JavaSoundPlayerImpl(
    private val audioDispatcher: CoroutineDispatcher,
    private val decodeDispatcher: CoroutineDispatcher,
    private val decoder: Decoder,
    private val source: MusicSource,
    private val settings: SettingsRepository,
    private val rgProcessor: ReplayGainProcessor,
    private val lineFactory: PcmOutputLineFactory = DesktopOutputLineFactory(),
) : DesktopPlayer {

    private enum class Mode { IDLE, PLAYING, PAUSED }

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _queue = MutableStateFlow(
        QueueState(items = emptyList(), currentIndex = -1, repeatMode = RepeatMode.Off, shuffleEnabled = false),
    )
    override val queue: StateFlow<QueueState> = _queue.asStateFlow()

    private val _volume = MutableStateFlow(VolumeState(linear = 1.0f, muted = false))
    override val volume: StateFlow<VolumeState> = _volume.asStateFlow()

    private val _processors = MutableStateFlow<List<AudioProcessor>>(emptyList())
    override val processors: StateFlow<List<AudioProcessor>> = _processors.asStateFlow()

    private val _outputRoute = MutableStateFlow<OutputRoute?>(null)
    override val outputRoute: StateFlow<OutputRoute?> = _outputRoute.asStateFlow()

    private val _notices = MutableSharedFlow<PlaybackNotice>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val notices: SharedFlow<PlaybackNotice> = _notices.asSharedFlow()

    private sealed interface OpenResult {
        data object Opened : OpenResult
        data class TrackFailed(val error: PlayerError) : OpenResult
        data class DeviceFailed(val error: PlayerError) : OpenResult
    }

    private enum class Direction { FORWARD, BACKWARD }

    private val scope = CoroutineScope(SupervisorJob())
    private val commands = Channel<PlayerCommand>(Channel.UNLIMITED)

    // ----- actor-owned state — mutated ONLY on the actor (audioDispatcher). -----
    // `line` + `released` are @Volatile because the write-interrupt seam (pause/stop/skip/
    // release) and awaitDrained() read them from other threads.
    @Volatile private var line: PcmOutputLine? = null
    @Volatile private var released: Boolean = false
    private var currentStream: DecodedStream? = null
    private var currentPlayable: Playable? = null
    private var frameChan: ReceiveChannel<AudioFrame>? = null
    private var producerScope: CoroutineScope? = null
    private var generation: Long = 0L
    private var mode: Mode = Mode.IDLE
    private var lastTickedMs: Long = 0L
    // Public release single-flight: the first caller flips releaseRequested + sends Release; ALL
    // callers await the shared releaseComplete (codex round 2 — the old `if (released) return` guard
    // let two concurrent callers both enqueue Release, but the actor acks only the first, so the
    // second awaited forever).
    private val releaseRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val releaseComplete = CompletableDeferred<Unit>()

    init {
        _processors.value = listOf(rgProcessor)
        scope.launch(audioDispatcher) { runActor() }
        // Settings-change RG: post a command so the actor (sole owner of currentPlayable)
        // recomputes + applies gain. No direct cross-thread mutation.
        scope.launch {
            combine(
                settings.replayGainMode.distinctUntilChanged(),
                settings.replayGainPreAmpDb.distinctUntilChanged(),
            ) { mode, preAmp -> mode to preAmp }
                .collect { (m, p) -> commands.trySend(PlayerCommand.ReapplyGain(m, p)) }
        }
    }

    // Write-interrupt seam for track-changing / teardown ops: `interrupt(discard = true)` makes a
    // blocked write return and lets the line drop buffered audio. `pause()` uses `discard = false`
    // to keep the buffer for a gapless resume.
    private fun interruptAndFlushLine() {
        runCatching { line?.interrupt(discard = true) }
    }

    // ---------- PlatformPlayer: every op posts a command (non-blocking) ----------
    override suspend fun loadQueue(items: List<MediaItem>, startIndex: Int, autoPlay: Boolean) {
        interruptAndFlushLine()
        commands.trySend(PlayerCommand.LoadQueue(items, startIndex, autoPlay))
    }
    override suspend fun play() { commands.trySend(PlayerCommand.Play) }
    override suspend fun pause() { runCatching { line?.interrupt(discard = false) }; commands.trySend(PlayerCommand.Pause) }
    override suspend fun stop() { interruptAndFlushLine(); commands.trySend(PlayerCommand.Stop) }
    override suspend fun seekTo(positionMs: Long) { commands.trySend(PlayerCommand.SeekTo(positionMs)) }
    override suspend fun skipToNext() { interruptAndFlushLine(); commands.trySend(PlayerCommand.SkipToNext) }
    override suspend fun skipToPrevious() { interruptAndFlushLine(); commands.trySend(PlayerCommand.SkipToPrevious) }
    override suspend fun skipTo(queueIndex: Int) { interruptAndFlushLine(); commands.trySend(PlayerCommand.SkipTo(queueIndex)) }
    override suspend fun setRepeatMode(mode: RepeatMode) { commands.trySend(PlayerCommand.SetRepeat(mode)) }
    override suspend fun setShuffleMode(enabled: Boolean) { commands.trySend(PlayerCommand.SetShuffle(enabled)) }
    override suspend fun setVolume(linear: Float) { commands.trySend(PlayerCommand.SetVolume(linear)) }
    override suspend fun setMuted(muted: Boolean) { commands.trySend(PlayerCommand.SetMuted(muted)) }
    override fun addAudioProcessor(processor: AudioProcessor) { commands.trySend(PlayerCommand.AddProcessor(processor)) }
    override fun removeAudioProcessor(processor: AudioProcessor) { commands.trySend(PlayerCommand.RemoveProcessor(processor)) }
    override suspend fun release() {
        if (releaseRequested.compareAndSet(false, true)) {
            // Interrupt WITH discard (not the pause variant): unblock an in-flight write() so the actor
            // can reach the Release command and tear down instead of hanging (gemini round 4).
            interruptAndFlushLine()
            commands.trySend(PlayerCommand.Release)
        }
        releaseComplete.await()  // every caller returns only after the actor has torn down (codex)
    }
    override suspend fun enterMeasurementMode(): MeasurementSession? = null

    /** Test-only: suspend until the actor has processed all commands sent so far. */
    internal suspend fun awaitDrained() {
        if (released) return
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
                if (queued != null) {
                    handleCommandBatch(queued)
                    continue
                }
                val fc = frameChan
                if (mode == Mode.PLAYING && fc != null) {
                    select {
                        commands.onReceive { c -> handleCommandBatch(c) }
                        fc.onReceiveCatching { result -> onFrameResult(result) }
                    }
                } else {
                    // IDLE / PAUSED: only a command can change anything; block (zero CPU).
                    handleCommandBatch(commands.receive())
                }
            }
        } finally {
            teardownActivePlayback()
            releaseComplete.complete(Unit)  // unblock all release() callers — teardown is done
            scope.cancel()
        }
    }

    /**
     * Process [first] + every already-queued command in FIFO order (command-biased drain). We do
     * NOT fold/collapse: relative skips must accumulate (four queued Next = advance four — codex),
     * and play/pause vs load must preserve send order (a Pause before a later autoplay LoadQueue
     * must not pause the new track — codex). Skip-spam therefore does N opens — bounded and correct.
     */
    private suspend fun handleCommandBatch(first: PlayerCommand) {
        var c: PlayerCommand? = first
        while (c != null) {
            applyCommand(c)
            if (released) return  // Release seen → stop draining; runActor's finally tears down + acks
            c = commands.tryReceive().getOrNull()
        }
    }

    private suspend fun applyCommand(cmd: PlayerCommand) {
        when (cmd) {
            is PlayerCommand.LoadQueue -> handleLoadQueue(cmd)
            is PlayerCommand.SkipToNext -> handleSkip(nextIndexOrNull(_queue.value), Direction.FORWARD)
            is PlayerCommand.SkipToPrevious -> handleSkip(previousIndexOrNull(_queue.value), Direction.BACKWARD)
            is PlayerCommand.SkipTo -> handleSkip(cmd.index.takeIf { it in _queue.value.items.indices }, direction = null)
            is PlayerCommand.Stop -> { teardownActivePlayback(); mode = Mode.IDLE; _state.value = PlayerState.Idle }
            is PlayerCommand.SeekTo -> handleSeek(cmd.positionMs)
            is PlayerCommand.Play -> resumePlayback()
            is PlayerCommand.Pause -> pausePlayback()
            is PlayerCommand.SetVolume -> {
                _volume.value = _volume.value.copy(linear = cmd.linear.coerceIn(0.0f, 1.0f)); applyGain()
            }
            is PlayerCommand.SetMuted -> { _volume.value = _volume.value.copy(muted = cmd.muted); applyGain() }
            is PlayerCommand.SetRepeat -> _queue.value = _queue.value.copy(repeatMode = cmd.mode)
            is PlayerCommand.SetShuffle -> _queue.value = _queue.value.copy(shuffleEnabled = cmd.enabled)
            is PlayerCommand.AddProcessor -> _processors.value = _processors.value + cmd.processor
            is PlayerCommand.RemoveProcessor -> _processors.value = _processors.value - cmd.processor
            is PlayerCommand.ReapplyGain -> currentPlayable?.let { applyRgGain(it, cmd.mode, cmd.preAmpDb) }
            is PlayerCommand.Barrier -> cmd.ack.complete(Unit)
            is PlayerCommand.Release -> released = true
        }
    }

    private suspend fun handleLoadQueue(cmd: PlayerCommand.LoadQueue) {
        _state.value = PlayerState.Loading
        // Resolve ON the actor: FIFO command ordering keeps load/stop strictly sequenced, so a stale
        // result can never apply over a newer click or a stop. A play-from-here click passes the bounded
        // <=500-item page (LibraryTab .take(500)); ~500 indexed getPlayable point-lookups is ~tens of ms
        // with no audio playing during the load. The round-4 off-actor resolve traded those tens of ms
        // for a stale-application race + a closed-channel spin in its supersede window (codex round 5
        // C1/C3) — not worth it. Lazy/progressive resolution is the Track-C2 pagination work.
        // Triple(originalIndex, item, playable) preserves the user's pre-filter index mapping (so "play
        // track 5" doesn't drift if track 2 failed to resolve).
        val resolved: List<Triple<Int, MediaItem, Playable>> = cmd.items.mapIndexedNotNull { idx, item ->
            when (val r = source.getPlayable(item.itemId)) {
                is Either.Right -> Triple(idx, item, r.value)
                is Either.Left -> {
                    log.w { "loadQueue: skipping ${item.itemId.value}: ${r.value}" }
                    null
                }
            }
        }
        val resolvedItems = resolved.map { it.second }
        if (resolved.isEmpty()) {
            teardownActivePlayback()
            _queue.value = _queue.value.copy(items = emptyList(), currentIndex = -1)
            mode = Mode.IDLE
            _state.value = PlayerState.Idle
            return
        }
        val coercedStart = if (cmd.startIndex <= 0) {
            0
        } else {
            val matchOrSuccessor = resolved.indexOfFirst { (originalIdx, _, _) -> originalIdx >= cmd.startIndex }
            if (matchOrSuccessor != -1) matchOrSuccessor else resolvedItems.lastIndex
        }
        _queue.value = _queue.value.copy(items = resolvedItems, currentIndex = coercedStart)
        openAndStart(resolved[coercedStart].third, autoPlay = cmd.autoPlay)
    }

    private suspend fun handleSkip(targetIndex: Int?, direction: Direction?) {
        if (targetIndex == null) {
            // No target (Next on last track / Prev on first / invalid skipTo): the public skip method
            // already fired the write-interrupt seam (line.interrupt), so re-arm the current track with
            // start() instead of leaving the line interrupted while Ready(playing), silently dropping
            // every frame (codex round 3).
            if (mode == Mode.PLAYING) runCatching { line?.start() }
            return
        }
        if (direction != null) {
            openWithSkip(targetIndex, direction)
            return
        }
        // Explicit SkipTo: open exactly this item and surface its error, never skipping.
        _queue.value = _queue.value.copy(currentIndex = targetIndex)
        val item = _queue.value.currentItem ?: return
        resolveAndOpen(item)
    }

    private suspend fun handleSeek(positionMs: Long) {
        val stream = currentStream ?: return
        // Cancel + JOIN the producer BEFORE seeking, then recreate it after. A plain channel drain
        // misses a send already suspended mid-rendezvous, which would resume after seekTo and emit one
        // pre-seek frame (codex round 2). Cancelling the producer + minting a fresh channel guarantees
        // no pre-seek frame survives; re-collecting stream.frames resumes from the post-seek position.
        producerScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        producerScope = null
        frameChan = null
        try {
            withContext(decodeDispatcher) { stream.seekTo(positionMs.coerceAtLeast(0L)) }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            teardownActivePlayback(); mode = Mode.IDLE
            _state.value = PlayerState.Error(PlayerError.DecodeFailed(e))
            return
        }
        // A failed flush must surface, not be swallowed (R19): on WASAPI it can leave the line
        // stopped with a full buffer, so the next write would block until an interrupt and playback
        // would silently halt. Treat it like a failed start(): a device failure, never a skip.
        try {
            line?.flush()
        } catch (e: Exception) {
            val format = stream.format // captured before teardown nulls currentStream
            teardownActivePlayback()
            mode = Mode.IDLE
            log.e(e) { "Output line flush() failed on seek" }
            _state.value = PlayerState.Error(outputError(e, format))
            return
        }
        val ps = CoroutineScope(decodeDispatcher + SupervisorJob())
        producerScope = ps
        frameChan = stream.frames.produceIn(ps)
        _positionMs.value = positionMs.coerceAtLeast(0L)
        lastTickedMs = positionMs.coerceAtLeast(0L)
    }

    /**
     * Tear down current playback, open [playable] on the decode thread, mint a fresh
     * generation + frame channel, apply RG gain for THIS generation before the first frame,
     * open the line, and set the mode. Sole stream-start path.
     */
    private suspend fun openAndStart(playable: Playable, autoPlay: Boolean): OpenResult {
        teardownActivePlayback()
        generation++
        // ACCEPTED (codex): a slow/hung decoder.open (bad or network-backed file) suspends the actor
        // here, so commands queued during Loading wait until it returns. libFLAC open isn't
        // cancellable; on this transitional engine that's accepted (the WASAPI engine can revisit).
        val stream = when (val r = withContext(decodeDispatcher) { decoder.open(playable) }) {
            is Either.Left -> {
                log.w { "openAndStart: decoder.open failed for ${playable.itemId.value}: ${r.value}" }
                mode = Mode.IDLE
                val error = toPlayerError(r.value)
                _state.value = PlayerState.Error(error)
                return OpenResult.TrackFailed(error)
            }
            is Either.Right -> r.value
        }
        currentStream = stream
        currentPlayable = playable
        rgProcessor.onFormatChange(stream.format)
        // RG gain for THIS generation, applied BEFORE the first frame is written (no async
        // launch racing a newer transition — retires the bug_003 class).
        applyRgGain(playable, settings.replayGainMode.first(), settings.replayGainPreAmpDb.first())

        val sourceLine = try {
            lineFactory.open(stream.format)
        } catch (e: Exception) {
            withContext(decodeDispatcher) { runCatching { stream.close() } }
            currentStream = null
            currentPlayable = null
            mode = Mode.IDLE
            log.e(e) { "Could not open output line for ${stream.format}" }
            val error = outputError(e, stream.format)
            _state.value = PlayerState.Error(error)
            return if (e is OutputLineException.FormatRejected) OpenResult.TrackFailed(error) else OpenResult.DeviceFailed(error)
        }
        line = sourceLine
        _outputRoute.value = sourceLine.route()
        val ps = CoroutineScope(decodeDispatcher + SupervisorJob())
        producerScope = ps
        frameChan = stream.frames.produceIn(ps)
        // Re-apply the player volume only when it's non-default (final review I1). Under WASAPI the
        // line volume is Kiln's per-app session volume (its Volume Mixer slider, which Windows keeps),
        // so applying the default on every open would undo the user's mixer level. A fresh Java Sound
        // line already starts at unity, so skipping the default changes nothing there.
        val v = _volume.value
        if (v.linear != 1.0f || v.muted) applyGain()
        if (autoPlay) {
            // A failed start() (output device vanished, line refuses to start) must NOT be swallowed
            // into Ready(isPlaying=true) on a dead line (codex round 4) — tear down + surface Error.
            try {
                sourceLine.start()
            } catch (e: Exception) {
                teardownActivePlayback()
                mode = Mode.IDLE
                log.e(e) { "Output line start() failed for ${stream.format}" }
                // Mapped like an open failure (so DeviceProblem shows its bare detail), but a failed
                // start is always a device failure, never a per-track skip.
                val error = outputError(e, stream.format)
                _state.value = PlayerState.Error(error)
                return OpenResult.DeviceFailed(error)
            }
            mode = Mode.PLAYING
            _state.value = PlayerState.Ready(isPlaying = true)
        } else {
            mode = Mode.PAUSED
            _state.value = PlayerState.Ready(isPlaying = false)
        }
        return OpenResult.Opened
    }

    /** Resolve [item] and open it. A resolve failure is a per-track failure. */
    private suspend fun resolveAndOpen(item: MediaItem): OpenResult =
        when (val r = source.getPlayable(item.itemId)) {
            is Either.Left -> {
                teardownActivePlayback()
                mode = Mode.IDLE
                val error = PlayerError.Internal("resolve ${item.itemId.value}: ${r.value}")
                _state.value = PlayerState.Error(error)
                OpenResult.TrackFailed(error)
            }
            is Either.Right -> openAndStart(r.value, autoPlay = true)
        }

    /**
     * Open the item at [startIndex]. On a per-track failure (unsupported format, decode/IO error,
     * unresolvable item), step one item in [direction] and try again, up to
     * MAX_CONSECUTIVE_FAILURES opens in total. Stepping follows queue order and RepeatMode.All
     * wrap-around but ignores RepeatMode.One, and it stops (on the last failure's error) when the
     * next step would land on the current or the start index, so a broken track is never retried
     * (e.g. a two-item RepeatAll queue of failures). A device failure stops at once, since it
     * would fail every track. Only auto-advance and Next/Previous use this. An explicit pick
     * (LoadQueue / SkipTo) opens exactly that item and shows its error.
     */
    private suspend fun openWithSkip(startIndex: Int, direction: Direction) {
        var index = startIndex
        var attempts = 0
        while (true) {
            _queue.value = _queue.value.copy(currentIndex = index)
            val item = _queue.value.currentItem ?: return
            attempts++
            val result = resolveAndOpen(item)
            if (result !is OpenResult.TrackFailed) return
            if (attempts >= MAX_CONSECUTIVE_FAILURES) {
                _notices.tryEmit(PlaybackNotice.GaveUp(attempts))
                return
            }
            val next = stepIndex(index, direction)
            if (next == null || next == index || next == startIndex) return
            _notices.tryEmit(PlaybackNotice.Skipped(item.title, result.error))
            index = next
        }
    }

    /** One step through the queue for skip-ahead: RepeatMode.All wraps; RepeatMode.One is ignored. */
    private fun stepIndex(index: Int, direction: Direction): Int? {
        val q = _queue.value
        if (q.items.isEmpty()) return null
        return when (direction) {
            Direction.FORWARD -> when {
                index < q.items.lastIndex -> index + 1
                q.repeatMode == RepeatMode.All -> 0
                else -> null
            }
            Direction.BACKWARD -> when {
                index > 0 -> index - 1
                q.repeatMode == RepeatMode.All -> q.items.lastIndex
                else -> null
            }
        }
    }

    private suspend fun onFrameResult(result: ChannelResult<AudioFrame>) {
        val frame = result.getOrNull()
        if (frame == null) {
            // Channel closed: a cause means decode error; no cause means EOF.
            val cause = result.exceptionOrNull()
            if (cause != null) {
                log.e(cause) { "playback decode error" }
                teardownActivePlayback()
                mode = Mode.IDLE
                _state.value = PlayerState.Error(PlayerError.DecodeFailed(cause))
            } else {
                advanceOnEof()
            }
            return
        }
        val l = line ?: return
        // Process + write inside a try: the actor is single-threaded and owns line lifecycle, so a
        // throw here is a GENUINE fatal error (dead output line, or a misbehaving processor) — NOT a
        // benign concurrent-close. Swallowing it would spin through the whole queue silently at 100%
        // CPU (gemini-critical) or, for a processor throw, escape onFrameResult and kill the actor
        // (codex). Mirror the pre-actor loop: tear down + surface PlayerState.Error.
        try {
            var processed: AudioFrame = frame
            for (processor in _processors.value) {
                processed = processor.process(processed)
            }
            l.write(processed.bytes, 0, processed.byteCount)
            // Position from the frame just WRITTEN, not the decoder's prefetched stream.positionMs
            // (which runs ahead of audible playback through the produceIn channel) — codex.
            val pos = frame.timestampMs
            if (pos - lastTickedMs >= POSITION_TICK_MS) {
                _positionMs.value = pos
                lastTickedMs = pos
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.e(e) { "playback write/process error" }
            teardownActivePlayback()
            mode = Mode.IDLE
            _state.value = PlayerState.Error(
                if (e is OutputLineException.DeviceProblem) PlayerError.DeviceUnavailable(e.detail) else PlayerError.DecodeFailed(e),
            )
        }
    }

    private suspend fun advanceOnEof() {
        val next = nextIndexOrNull(_queue.value)
        // Play out the line's buffered tail (its queued audio) so the track end isn't clipped — via a
        // BOUNDED park, NEVER a drain-until-empty. A drain never returns if a concurrent pause()
        // interrupts the line with audio still queued (pause keeps the buffer); since control ops only
        // enqueue commands, the actor would wedge with no way to recover (codex round 6 — an isRunning
        // guard isn't enough, the pause can land after the check). A bounded park can't wedge, but it
        // isn't cut short either: a Pause arriving mid-tail is serviced only after the deadline
        // (≤ 2 × the line buffer). Whether the tail stays audible until then is up to the line's
        // interrupt(discard = false), which need not touch the device.
        val l = line
        // A line call here can throw (WASAPI GetCurrentPadding on an invalidated device). runActor
        // has no catch, so an escape would kill the actor. Skip the tail instead: a dead device then
        // surfaces at the next open as DeviceFailed.
        try {
            if (l != null && l.isRunning) {
                // Durations come from the line (it owns output byte-rate math, #37). Capped at twice
                // the line buffer, which also bounds how long a Pause landing mid-tail waits.
                val tailNanos = l.queuedNanos().coerceAtMost(2 * l.bufferNanos)
                // A loop to a deadline: parkNanos can return early (spurious wakeup, a pending permit,
                // or Windows timer granularity), and a short park clips the end of the track.
                val deadline = System.nanoTime() + tailNanos
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) break
                    java.util.concurrent.locks.LockSupport.parkNanos(remaining)
                }
            }
        } catch (e: Exception) {
            log.w(e) { "EOF tail skipped: output line query failed" }
        }
        teardownActivePlayback()
        if (next == null) {
            mode = Mode.IDLE
            _state.value = PlayerState.Idle
            return
        }
        openWithSkip(next, Direction.FORWARD)
    }

    private suspend fun resumePlayback() {
        val stream = currentStream ?: return
        val l = line ?: return
        if (!l.isRunning) {
            try {
                l.start()
            } catch (e: Exception) {
                // Mirror openAndStart: a failed resume (output device gone) must NOT report
                // Ready(isPlaying=true) on a line that never restarted (codex round 5 C2). Tear down
                // + surface Error rather than writing subsequent frames to a non-running line.
                val format = stream.format // captured before teardown nulls currentStream
                teardownActivePlayback()
                mode = Mode.IDLE
                log.e(e) { "Output line start() failed on resume" }
                _state.value = PlayerState.Error(outputError(e, format))
                return
            }
        }
        mode = Mode.PLAYING
        _state.value = PlayerState.Ready(isPlaying = true)
    }

    private fun pausePlayback() {
        if (currentStream == null) return
        runCatching { line?.stop() }
        mode = Mode.PAUSED
        _state.value = PlayerState.Ready(isPlaying = false)
    }

    /**
     * Idempotent teardown. Cancels + JOINS the decode producer before closing the stream
     * (libFLAC delete must not race an in-flight decode — process_single can't be interrupted
     * mid-call, so we let it finish, bounded by one frame, then close on the decode thread).
     */
    private suspend fun teardownActivePlayback() = withContext(kotlinx.coroutines.NonCancellable) {
        // NonCancellable: teardown runs from runActor's finally, which may execute in a cancelled
        // context (scope cancellation / app shutdown). cancelAndJoin is suspending and would throw
        // CancellationException immediately, aborting before close() runs → leaked native libFLAC
        // handles + audio lines (gemini round 3). Force the whole cleanup to completion.
        producerScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        producerScope = null
        frameChan = null
        val s = currentStream
        if (s != null) {
            withContext(decodeDispatcher) { runCatching { s.close() } }
        }
        currentStream = null
        currentPlayable = null
        line?.let { l -> runCatching { l.close() } }
        line = null
        _outputRoute.value = null
        // No active track → position 0. openAndStart tears down first, so every failed open
        // (decoder, line open, line start) shows 0:00 instead of the previous track's position.
        lastTickedMs = 0L
        _positionMs.value = 0L
    }

    // ----- helpers carried over from the pre-actor impl (unchanged behaviour) -----

    private fun applyGain() {
        val l = line ?: return
        val v = _volume.value
        // WASAPI SetMasterVolume can throw on an invalidated device. Don't let that kill the actor;
        // a dead device surfaces on the next write or open.
        try {
            l.setVolume(if (v.muted) 0f else v.linear)
        } catch (e: Exception) {
            log.w(e) { "setVolume failed on the output line" }
        }
    }

    private fun applyRgGain(playable: Playable, mode: ReplayGainMode, preAmpDb: Double) {
        val rg = playable.replayGain
        val pipelineMode = when (mode) {
            ReplayGainMode.Off -> ReplayGainPipelineMode.Off
            ReplayGainMode.Track -> ReplayGainPipelineMode.Track
            ReplayGainMode.Album -> ReplayGainPipelineMode.Album
        }
        val gain = resolveGainLinear(
            trackDb = rg?.trackDb,
            trackPeak = rg?.trackPeak,
            albumDb = rg?.albumDb,
            albumPeak = rg?.albumPeak,
            mode = pipelineMode,
            preAmpDb = preAmpDb,
        )
        rgProcessor.setLinearGain(gain)
    }

    private fun nextIndexOrNull(q: QueueState): Int? = when {
        q.items.isEmpty() -> null
        q.repeatMode == RepeatMode.One -> q.currentIndex.coerceAtLeast(0)
        q.currentIndex < q.items.lastIndex -> q.currentIndex + 1
        q.repeatMode == RepeatMode.All -> 0
        else -> null
    }

    private fun previousIndexOrNull(q: QueueState): Int? = when {
        q.items.isEmpty() -> null
        q.currentIndex > 0 -> q.currentIndex - 1
        q.repeatMode == RepeatMode.All -> q.items.lastIndex
        else -> null
    }

    private fun outputError(e: Exception, format: com.clayworks.kiln.audio.dsp.DecodedAudioFormat): PlayerError =
        when (e) {
            is OutputLineException.FormatRejected ->
                PlayerError.OutputFormatUnsupported(format.sampleRateHz, format.bitDepth, format.channels, e.detail)
            is OutputLineException.DeviceProblem -> PlayerError.DeviceUnavailable(e.detail)
            else -> PlayerError.DeviceUnavailable(e.message ?: "audio line open failed")
        }

    private fun toPlayerError(decoderError: DecoderError): PlayerError = when (decoderError) {
        is DecoderError.UnsupportedCodec -> PlayerError.FormatUnsupported(decoderError.codec.name)
        is DecoderError.CorruptStream -> PlayerError.DecodeFailed(IllegalStateException(decoderError.message))
        is DecoderError.IoError -> PlayerError.IoError(decoderError.cause)
        is DecoderError.NativeBindingFailed -> PlayerError.DecodeFailed(IllegalStateException(decoderError.message))
        is DecoderError.Internal -> PlayerError.Internal(decoderError.message)
    }
}
