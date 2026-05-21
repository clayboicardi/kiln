// JavaSoundPlayerImpl — Desktop PlatformPlayer backed by javax.sound.sampled +
// the JNA libFLAC Decoder pipeline.
//
// Architecture mirrors Media3ExoPlayerImpl (Android side): 5 MutableStateFlows
// for state/positionMs/queue/volume/processors; the playbackJob owns the
// SourceDataLine + DecodedStream for the duration of one track; loadQueue
// resolves MediaItems via the injected MusicSource and starts playback by
// opening the Decoder for the start item; advanceOnEof chains tracks.
//
// MVP scope (H5): loadQueue + autoPlay path is the load-bearing functional
// requirement (H7 will call it from a single button). play/pause/stop/release
// work. seekTo, skipToNext/Previous/skipTo, setRepeatMode, setShuffleMode,
// setVolume, setMuted, addAudioProcessor are implemented but only loosely
// validated; full polish lands in Phase 2a.
//
// Threading: javax.sound.sampled Line + Control methods are documented as
// thread-safe ("Implementations of this interface must be safe for use by
// multiple threads" — Line javadoc). The audioDispatcher (single-thread
// MAX_PRIORITY executor from DI) is still used as the playback-loop's
// dispatcher AND for ops that mutate non-line internal state (queue swap,
// teardown). Flag-only control methods (play/pause/setRepeatMode/
// setShuffleMode) do NOT marshal through audioDispatcher — they would
// otherwise queue behind a blocking sourceLine.write() call and stall for
// up to ~100ms (or indefinitely if write blocks pathologically) per the
// /ultrareview + Gemini findings at Session 9 close.
//
// Pause signaling: `_paused: MutableStateFlow<Boolean>` replaces an earlier
// `@Volatile pauseRequested` flag + busy-wait `while (pauseRequested) delay(...)`
// loop. The playback loop now suspends on `_paused.first { !it }`, which is
// idiomatic Kotlin Flow and consumes zero CPU while paused. play()/pause()
// just flip the flow value + call line.start()/stop() directly (the latter
// for instant-pause UX — the loop's flip-based check would otherwise add
// ~one-frame latency).

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.Playable
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = Logger.withTag("JavaSoundPlayer")

private const val POSITION_TICK_MS = 250L
private const val BUFFER_MS = 100   // SourceDataLine internal buffer size (latency budget)

/**
 * Construct the Desktop [PlatformPlayer]. Public factory keeps the
 * [JavaSoundPlayerImpl] class itself internal — consumers receive only the
 * [PlatformPlayer] interface from commonMain. Caller supplies the audio
 * dispatcher (a single-thread MAX_PRIORITY executor is recommended; the DI
 * graph provides one), the [Decoder] to use (typically [createJvmFlacDecoder]),
 * and the [MusicSource] for resolving [MediaItem]s into [Playable]s.
 */
fun createJavaSoundPlayer(
    audioDispatcher: CoroutineDispatcher,
    decoder: Decoder,
    source: MusicSource,
): PlatformPlayer = JavaSoundPlayerImpl(audioDispatcher, decoder, source)

internal class JavaSoundPlayerImpl(
    private val audioDispatcher: CoroutineDispatcher,
    private val decoder: Decoder,
    private val source: MusicSource,
) : PlatformPlayer {

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

    private val scope = CoroutineScope(audioDispatcher + SupervisorJob())

    @Volatile private var line: SourceDataLine? = null
    @Volatile private var currentStream: DecodedStream? = null
    @Volatile private var playbackJob: Job? = null

    /**
     * Pause signal observed by the playback loop. `true` = loop should stop the
     * line + suspend on `.first { !it }`; `false` = loop should start the line
     * (if not running) and write frames. Flipped by play()/pause() without
     * marshalling through the audioDispatcher — those control methods MUST
     * remain non-blocking so they can interrupt the loop even when it's
     * currently in `sourceLine.write`.
     */
    private val _paused = MutableStateFlow(false)

    @Volatile private var released: Boolean = false

    // ---------- PlatformPlayer ----------

    override suspend fun loadQueue(
        items: List<MediaItem>,
        startIndex: Int,
        autoPlay: Boolean,
    ) = withContext(audioDispatcher) {
        if (released) return@withContext

        cancelCurrentPlayback()
        _state.value = PlayerState.Loading

        // Triple(originalIndex, item, playable) preserves the mapping from
        // the user's pre-filter items list to the post-filter resolved list.
        // Without it, a user clicking "play track 5" when track 2 failed to
        // resolve would get track 6 (or worse, the clamped-to-last item).
        val resolved: List<Triple<Int, MediaItem, Playable>> = items.mapIndexedNotNull { idx, item ->
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
            _queue.value = _queue.value.copy(items = emptyList(), currentIndex = -1)
            _state.value = PlayerState.Idle
            return@withContext
        }

        // Map user's startIndex (original-list space) to resolved-list space.
        // - startIndex ≤ 0 → start at the first resolved item (safe default).
        // - exact match found → start at that resolved index.
        // - user's requested item failed to resolve → fall FORWARD to the next
        //   surviving item (preserves "start at or after this position" intent).
        // - no surviving item at or after startIndex → fall BACK to the last
        //   surviving item (rather than wrap or clamp incorrectly).
        val coercedStart = if (startIndex <= 0) {
            0
        } else {
            val matchOrSuccessor = resolved.indexOfFirst { (originalIdx, _, _) -> originalIdx >= startIndex }
            if (matchOrSuccessor != -1) matchOrSuccessor else resolvedItems.lastIndex
        }
        _queue.value = _queue.value.copy(items = resolvedItems, currentIndex = coercedStart)

        // Pass the already-resolved Playable for the start item to avoid the
        // redundant getPlayable round-trip in startPlaybackForCurrentIndex.
        // Skip-next/prev/skipTo paths still resolve on demand because they
        // target a different item than the originally-loaded start.
        startPlaybackForCurrentIndex(autoPlay, preResolved = resolved[coercedStart].third)
    }

    override suspend fun play() {
        // No withContext(audioDispatcher) — would otherwise stall behind a
        // blocking sourceLine.write() call in the playback loop. Line.start()
        // is thread-safe per javax.sound spec; setting _paused.value is
        // thread-safe via MutableStateFlow.
        if (released) return
        _paused.value = false
        line?.takeUnless { it.isRunning }?.start()
        updateReadyState()
    }

    override suspend fun pause() {
        // No withContext(audioDispatcher) — see play() rationale above. The
        // loop also observes _paused via its `.first { !it }` gate and will
        // stop the line on its next iteration; calling line.stop() here
        // additionally is for instant-pause UX. Both are idempotent.
        if (released) return
        _paused.value = true
        line?.stop()
        updateReadyState()
    }

    override suspend fun stop() = withContext(audioDispatcher) {
        if (released) return@withContext
        cancelCurrentPlayback()
        _state.value = PlayerState.Idle
    }

    override suspend fun seekTo(positionMs: Long) = withContext(audioDispatcher) {
        if (released) return@withContext
        currentStream?.seekTo(positionMs.coerceAtLeast(0L))
        line?.flush()
        _positionMs.value = positionMs.coerceAtLeast(0L)
    }

    override suspend fun skipToNext() = withContext(audioDispatcher) {
        if (released) return@withContext
        val q = _queue.value
        val nextIndex = nextIndexOrNull(q) ?: return@withContext
        cancelCurrentPlayback()
        _queue.value = q.copy(currentIndex = nextIndex)
        startPlaybackForCurrentIndex(autoPlay = true)
    }

    override suspend fun skipToPrevious() = withContext(audioDispatcher) {
        if (released) return@withContext
        val q = _queue.value
        val prevIndex = previousIndexOrNull(q) ?: return@withContext
        cancelCurrentPlayback()
        _queue.value = q.copy(currentIndex = prevIndex)
        startPlaybackForCurrentIndex(autoPlay = true)
    }

    override suspend fun skipTo(queueIndex: Int) = withContext(audioDispatcher) {
        if (released) return@withContext
        val q = _queue.value
        if (queueIndex !in q.items.indices) return@withContext
        cancelCurrentPlayback()
        _queue.value = q.copy(currentIndex = queueIndex)
        startPlaybackForCurrentIndex(autoPlay = true)
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        // Pure flag-flip on a thread-safe StateFlow — no dispatcher needed.
        _queue.value = _queue.value.copy(repeatMode = mode)
    }

    override suspend fun setShuffleMode(enabled: Boolean) {
        // Pure flag-flip; shuffle ORDER generation is deferred to Phase 2a
        // (vetting Item 12). Queue order does not yet actually shuffle.
        _queue.value = _queue.value.copy(shuffleEnabled = enabled)
    }

    override suspend fun setVolume(linear: Float) = withContext(audioDispatcher) {
        val clamped = linear.coerceIn(0.0f, 1.0f)
        _volume.value = _volume.value.copy(linear = clamped)
        applyGain()
    }

    override suspend fun setMuted(muted: Boolean) = withContext(audioDispatcher) {
        _volume.value = _volume.value.copy(muted = muted)
        applyGain()
    }

    override fun addAudioProcessor(processor: AudioProcessor) {
        // Processors are recorded but not yet invoked on frames — :audio:dsp has
        // no concrete processors before MVP Sessions 16-22. Once they exist, the
        // playback loop's `processors.value.forEach { it.process(frame) }` call
        // (already wired below) will start transforming output.
        _processors.value = _processors.value + processor
    }

    override fun removeAudioProcessor(processor: AudioProcessor) {
        _processors.value = _processors.value - processor
    }

    override suspend fun release() = withContext(audioDispatcher) {
        if (released) return@withContext
        released = true
        cancelCurrentPlayback()
        scope.cancel()
    }

    // ---------- internals ----------

    /**
     * Open the decoder for the current queue item + launch playback.
     *
     * @param preResolved if non-null, used directly instead of calling
     *   source.getPlayable again — set by loadQueue to skip a redundant DB
     *   round-trip for the queue's start item (it was already resolved during
     *   the queue's eager-validation mapNotNull). advanceOnEof / skipToNext /
     *   skipToPrevious / skipTo leave it null because they target a different
     *   item than the originally-loaded start.
     */
    private suspend fun startPlaybackForCurrentIndex(
        autoPlay: Boolean,
        preResolved: Playable? = null,
    ) {
        val q = _queue.value
        val item = q.currentItem ?: run {
            _state.value = PlayerState.Idle
            return
        }
        val playable = preResolved ?: when (val r = source.getPlayable(item.itemId)) {
            is Either.Left -> {
                log.w { "startPlayback: getPlayable failed for ${item.itemId.value}: ${r.value}" }
                _state.value = PlayerState.Error(
                    PlayerError.Internal("Could not resolve ${item.itemId.value}: ${r.value}"),
                )
                return
            }
            is Either.Right -> r.value
        }
        val stream = when (val r = decoder.open(playable)) {
            is Either.Left -> {
                log.w { "startPlayback: decoder.open failed for ${item.itemId.value}: ${r.value}" }
                _state.value = PlayerState.Error(toPlayerError(r.value))
                return
            }
            is Either.Right -> r.value
        }
        startStream(stream, autoPlay)
    }

    private fun startStream(stream: DecodedStream, autoPlay: Boolean) {
        val format = AudioFormat(
            stream.format.sampleRateHz.toFloat(),
            stream.format.bitDepth,
            stream.format.channels,
            /* signed = */ true,
            /* bigEndian = */ false,
        )
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val sourceLine = try {
            (AudioSystem.getLine(info) as SourceDataLine).also {
                val bytesPerSecond = stream.format.sampleRateHz *
                    stream.format.channels *
                    (stream.format.bitDepth / 8)
                val bufferBytes = (bytesPerSecond * BUFFER_MS / 1000).coerceAtLeast(4096)
                it.open(format, bufferBytes)
            }
        } catch (e: Exception) {
            stream.close()
            log.e(e) { "Could not open SourceDataLine for $format" }
            _state.value = PlayerState.Error(PlayerError.DeviceUnavailable(e.message ?: "audio line open failed"))
            return
        }

        line = sourceLine
        currentStream = stream
        _paused.value = !autoPlay
        applyGain()
        if (autoPlay) sourceLine.start()

        _state.value = PlayerState.Ready(isPlaying = autoPlay)
        _positionMs.value = 0L

        playbackJob = scope.launch {
            var lastTickedMs = 0L
            try {
                stream.frames.collect { frame ->
                    // Apply processors in order. Each may return a new (or
                    // mutated) AudioFrame; we chain them so the final write
                    // reflects the cumulative pipeline. Currently
                    // `_processors.value` is always empty (no concrete
                    // processors land before :audio:dsp Sessions 16-22), so
                    // this fold is a no-op — but wiring it now is the
                    // load-bearing prep for when EQ / ReplayGain / etc. arrive.
                    var processedFrame = frame
                    _processors.value.forEach { processor ->
                        processedFrame = processor.process(processedFrame)
                    }
                    // Pause gate. _paused.first { !it } suspends the coroutine
                    // (zero CPU while paused) until pause() flips the flow to
                    // false. StateFlow.first(predicate) replays the current
                    // value first, so this returns immediately if not paused.
                    if (_paused.value) {
                        runCatching { sourceLine.stop() }
                        _paused.first { !it }
                        if (!isActive) return@collect
                        runCatching { sourceLine.start() }
                    }
                    if (!isActive) return@collect
                    sourceLine.write(processedFrame.bytes, 0, processedFrame.byteCount)
                    val pos = stream.positionMs
                    if (pos - lastTickedMs >= POSITION_TICK_MS) {
                        _positionMs.value = pos
                        lastTickedMs = pos
                    }
                }
                // End of stream: try to advance to next item.
                sourceLine.drain()
                advanceOnEof()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.e(e) { "playback loop error" }
                // CRITICAL: close the line + stream BEFORE flipping state. Otherwise
                // OS-level resources leak (audio device handle via SourceDataLine,
                // libFLAC native decoder handle via JvmFlacDecodedStream) until the
                // next loadQueue / stop / skip*. play() and pause() do NOT call
                // cancelCurrentPlayback, so if the user reacts to the error state
                // by hitting play they'd operate on a dead line and the leaked
                // resources would persist indefinitely.
                teardownActivePlayback(stopLineFirst = true)
                _state.value = PlayerState.Error(PlayerError.DecodeFailed(e))
            }
        }
    }

    /**
     * Idempotent teardown of the active line + stream. Used by the EOF path,
     * the cancel path, AND the catch-on-error path so all three converge on
     * identical resource hygiene. Pass `stopLineFirst = true` for paths where
     * the line might still be RUNNING (cancel + error); the EOF path already
     * called drain() so a stop() before close() is redundant but harmless.
     */
    private fun teardownActivePlayback(stopLineFirst: Boolean) {
        line?.let { l ->
            if (stopLineFirst) {
                runCatching { l.stop() }
            }
            runCatching { l.close() }
        }
        line = null
        runCatching { currentStream?.close() }
        currentStream = null
    }

    private suspend fun advanceOnEof() {
        val q = _queue.value
        val nextIndex = nextIndexOrNull(q)
        teardownActivePlayback(stopLineFirst = false)
        if (nextIndex == null) {
            _state.value = PlayerState.Idle
            return
        }
        _queue.value = q.copy(currentIndex = nextIndex)
        startPlaybackForCurrentIndex(autoPlay = true)
    }

    private fun cancelCurrentPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        teardownActivePlayback(stopLineFirst = true)
        _paused.value = false
    }

    private fun updateReadyState() {
        val l = line ?: return
        val current = _state.value
        if (current is PlayerState.Ready) {
            val isPlaying = l.isRunning && !_paused.value
            if (current.isPlaying != isPlaying) {
                _state.value = PlayerState.Ready(isPlaying)
            }
        }
    }

    private fun applyGain() {
        val l = line ?: return
        if (!l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val ctl = l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val v = _volume.value
        val effectiveLinear = if (v.muted) 0f else v.linear
        // Convert linear 0..1 to dB attenuation. 0 → most-negative (silence);
        // 1 → 0 dB (unity). Clamp to the control's reported range.
        val targetDb = if (effectiveLinear <= 0f) ctl.minimum else 20.0f * kotlin.math.log10(effectiveLinear)
        ctl.value = targetDb.coerceIn(ctl.minimum, ctl.maximum)
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
}
