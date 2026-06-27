// JavaSoundPlayerImpl — Desktop PlatformPlayer backed by javax.sound.sampled +
// the JNA libFLAC Decoder pipeline.
//
// Concurrency model (#28 — command/actor): ONE long-lived actor coroutine on
// [audioDispatcher] is the SOLE owner/mutator of the SourceDataLine + DecodedStream +
// queue index + the five StateFlows. Every control op is a non-suspending
// `commands.trySend(PlayerCommand.X)` to an UNLIMITED channel — so control ops can never
// be starved by, or race, the playback loop. Decode runs on its own [decodeDispatcher]
// (frames arrive via produceIn), so the actor thread is never blocked by libFLAC decode —
// only by the bounded SourceDataLine.write. The actor `select`s frame-vs-command,
// command-biased (it drains queued commands before consuming a frame), so a skip/track-
// change is serviced within ~one write even mid-playback.
//
// This retired the prior design where loadQueue/skip*/stop/seekTo used
// withContext(audioDispatcher) and queued behind the blocking decode+write loop (they only
// ran once the track hit EOF). See docs/superpowers/specs/2026-06-25-issue-28-*.
//
// The ONLY cross-thread touch of the line is a best-effort thread-safe `line.stop()` in
// pause/stop/skip/release (the "write-interrupt seam") — it unblocks a stuck write() so the
// actor can make progress; the actor remains the authority on all subsequent line state.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.AudioFrame
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
private const val BUFFER_MS = 100  // SourceDataLine internal buffer size (latency budget)

private val realLineFactory: (AudioFormat) -> SourceDataLine = { format ->
    AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
}

/**
 * Construct the Desktop [PlatformPlayer]. Keeps [JavaSoundPlayerImpl] internal — consumers
 * receive only the [PlatformPlayer] interface. Caller supplies the single-thread
 * [audioDispatcher] (MAX_PRIORITY recommended) for the actor + line writes, a single-thread
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

    private val _queue = MutableStateFlow(
        QueueState(items = emptyList(), currentIndex = -1, repeatMode = RepeatMode.Off, shuffleEnabled = false),
    )
    override val queue: StateFlow<QueueState> = _queue.asStateFlow()

    private val _volume = MutableStateFlow(VolumeState(linear = 1.0f, muted = false))
    override val volume: StateFlow<VolumeState> = _volume.asStateFlow()

    private val _processors = MutableStateFlow<List<AudioProcessor>>(emptyList())
    override val processors: StateFlow<List<AudioProcessor>> = _processors.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private val commands = Channel<PlayerCommand>(Channel.UNLIMITED)

    // ----- actor-owned state — mutated ONLY on the actor (audioDispatcher). -----
    // `line` + `released` are @Volatile because the write-interrupt seam (pause/stop/skip/
    // release) and awaitDrained() read them from other threads.
    @Volatile private var line: SourceDataLine? = null
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

    // ---------- PlatformPlayer: every op posts a command (non-blocking) ----------
    override suspend fun loadQueue(items: List<MediaItem>, startIndex: Int, autoPlay: Boolean) {
        runCatching { line?.stop() }  // write-interrupt seam: unblock a stuck write so the switch is instant
        commands.trySend(PlayerCommand.LoadQueue(items, startIndex, autoPlay))
    }
    override suspend fun play() { commands.trySend(PlayerCommand.Play) }
    override suspend fun pause() { runCatching { line?.stop() }; commands.trySend(PlayerCommand.Pause) }
    override suspend fun stop() { runCatching { line?.stop() }; commands.trySend(PlayerCommand.Stop) }
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
    override suspend fun release() {
        if (releaseRequested.compareAndSet(false, true)) {
            runCatching { line?.stop() }
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
            is PlayerCommand.SkipToNext -> handleSkip(nextIndexOrNull(_queue.value))
            is PlayerCommand.SkipToPrevious -> handleSkip(previousIndexOrNull(_queue.value))
            is PlayerCommand.SkipTo -> handleSkip(cmd.index.takeIf { it in _queue.value.items.indices })
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
        // Triple(originalIndex, item, playable) preserves the user's pre-filter index mapping
        // through the resolve (so "play track 5" doesn't drift if track 2 failed to resolve).
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

    private suspend fun handleSkip(targetIndex: Int?) {
        if (targetIndex == null) {
            // No target (Next on last track / Prev on first / invalid skipTo): the public skip method
            // already fired the write-interrupt line.stop() seam, so resume the current track instead
            // of leaving it stopped-but-Ready(playing) with frames written silently (codex round 3).
            if (mode == Mode.PLAYING) runCatching { line?.start() }
            return
        }
        _queue.value = _queue.value.copy(currentIndex = targetIndex)
        val item = _queue.value.currentItem ?: return
        when (val r = source.getPlayable(item.itemId)) {
            is Either.Left -> {
                teardownActivePlayback(); mode = Mode.IDLE
                _state.value = PlayerState.Error(PlayerError.Internal("resolve ${item.itemId.value}: ${r.value}"))
            }
            is Either.Right -> openAndStart(r.value, autoPlay = true)
        }
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
        runCatching { line?.flush() }
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
    private suspend fun openAndStart(playable: Playable, autoPlay: Boolean) {
        teardownActivePlayback()
        generation++
        // ACCEPTED (codex): a slow/hung decoder.open (bad or network-backed file) suspends the actor
        // here, so commands queued during Loading wait until it returns. libFLAC open isn't
        // cancellable; on this transitional engine that's accepted (the WASAPI engine can revisit).
        val stream = when (val r = withContext(decodeDispatcher) { decoder.open(playable) }) {
            is Either.Left -> {
                log.w { "openAndStart: decoder.open failed for ${playable.itemId.value}: ${r.value}" }
                mode = Mode.IDLE
                _state.value = PlayerState.Error(toPlayerError(r.value))
                return
            }
            is Either.Right -> r.value
        }
        currentStream = stream
        currentPlayable = playable
        rgProcessor.onFormatChange(stream.format)
        // RG gain for THIS generation, applied BEFORE the first frame is written (no async
        // launch racing a newer transition — retires the bug_003 class).
        applyRgGain(playable, settings.replayGainMode.first(), settings.replayGainPreAmpDb.first())

        val format = audioFormatOf(stream)
        val sourceLine = try {
            lineFactory(format).also { it.open(format, bufferBytesOf(stream)) }
        } catch (e: Exception) {
            withContext(decodeDispatcher) { runCatching { stream.close() } }
            currentStream = null
            currentPlayable = null
            mode = Mode.IDLE
            log.e(e) { "Could not open SourceDataLine for $format" }
            _state.value = PlayerState.Error(PlayerError.DeviceUnavailable(e.message ?: "audio line open failed"))
            return
        }
        line = sourceLine
        lastTickedMs = 0L
        _positionMs.value = 0L
        val ps = CoroutineScope(decodeDispatcher + SupervisorJob())
        producerScope = ps
        frameChan = stream.frames.produceIn(ps)
        applyGain()
        if (autoPlay) {
            runCatching { sourceLine.start() }
            mode = Mode.PLAYING
            _state.value = PlayerState.Ready(isPlaying = true)
        } else {
            mode = Mode.PAUSED
            _state.value = PlayerState.Ready(isPlaying = false)
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
            _state.value = PlayerState.Error(PlayerError.DecodeFailed(e))
        }
    }

    private suspend fun advanceOnEof() {
        val next = nextIndexOrNull(_queue.value)
        // Play out the buffered tail of the finished track — but ONLY if the line is still running.
        // If EOF coincides with a Pause/Stop/Skip/Release (which fire the line.stop() write-interrupt
        // seam), drain() on a stopped line with buffered audio blocks forever → the actor stalls and
        // a release() caller hangs (codex round 3). A stopped line means a command is incoming anyway.
        if (line?.isRunning == true) runCatching { line?.drain() }
        teardownActivePlayback()
        if (next == null) {
            mode = Mode.IDLE
            _state.value = PlayerState.Idle
            return
        }
        _queue.value = _queue.value.copy(currentIndex = next)
        val item = _queue.value.currentItem ?: run {
            mode = Mode.IDLE
            _state.value = PlayerState.Idle
            return
        }
        when (val r = source.getPlayable(item.itemId)) {
            is Either.Left -> {
                mode = Mode.IDLE
                _state.value = PlayerState.Error(PlayerError.Internal("resolve ${item.itemId.value}: ${r.value}"))
            }
            is Either.Right -> openAndStart(r.value, autoPlay = true)
        }
    }

    private fun resumePlayback() {
        if (currentStream == null) return
        runCatching { line?.takeUnless { it.isRunning }?.start() }
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
        line?.let { l -> runCatching { l.stop() }; runCatching { l.close() } }
        line = null
    }

    // ----- helpers carried over from the pre-actor impl (unchanged behaviour) -----

    private fun applyGain() {
        val l = line ?: return
        if (!l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val ctl = l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val v = _volume.value
        val effectiveLinear = if (v.muted) 0f else v.linear
        // Convert linear 0..1 to dB attenuation. 0 → most-negative (silence); 1 → 0 dB (unity).
        // Clamp to the control's reported range.
        val targetDb = if (effectiveLinear <= 0f) ctl.minimum else 20.0f * kotlin.math.log10(effectiveLinear)
        ctl.value = targetDb.coerceIn(ctl.minimum, ctl.maximum)
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

    private fun toPlayerError(decoderError: DecoderError): PlayerError = when (decoderError) {
        is DecoderError.UnsupportedCodec -> PlayerError.FormatUnsupported(decoderError.codec.name)
        is DecoderError.CorruptStream -> PlayerError.DecodeFailed(IllegalStateException(decoderError.message))
        is DecoderError.IoError -> PlayerError.IoError(decoderError.cause)
        is DecoderError.NativeBindingFailed -> PlayerError.DecodeFailed(IllegalStateException(decoderError.message))
        is DecoderError.Internal -> PlayerError.Internal(decoderError.message)
    }

    private fun audioFormatOf(stream: DecodedStream): AudioFormat = AudioFormat(
        stream.format.sampleRateHz.toFloat(),
        stream.format.bitDepth,
        stream.format.channels,
        /* signed = */ true,
        /* bigEndian = */ false,
    )

    private fun bufferBytesOf(stream: DecodedStream): Int {
        val bytesPerSecond = stream.format.sampleRateHz * stream.format.channels * (stream.format.bitDepth / 8)
        return (bytesPerSecond * BUFFER_MS / 1000).coerceAtLeast(4096)
    }
}
