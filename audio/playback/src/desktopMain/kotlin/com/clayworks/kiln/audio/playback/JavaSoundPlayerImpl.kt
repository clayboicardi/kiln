// JavaSoundPlayerImpl — Desktop PlatformPlayer backed by javax.sound.sampled +
// the JNA libFLAC Decoder pipeline.
//
// Architecture mirrors Media3ExoPlayerImpl (Android side): 5 MutableStateFlows
// for state/positionMs/queue/volume/processors; suspend methods marshal through
// the audioDispatcher; a single playbackJob owns the SourceDataLine + the
// Decoder's DecodedStream for the duration of one track. loadQueue resolves
// MediaItems via the injected MusicSource and starts playback by opening the
// Decoder for the start item; advanceToNext is invoked on EOF to chain tracks.
//
// MVP scope (H5): loadQueue + autoPlay path is the load-bearing functional
// requirement (H7 will call it from a single button). play/pause/stop/release
// work. seekTo, skipToNext/Previous/skipTo, setRepeatMode, setShuffleMode,
// setVolume, setMuted, addAudioProcessor are implemented but only loosely
// validated; full polish lands in Phase 2a.
//
// Threading: all PlatformPlayer methods marshal through withContext(audioDispatcher).
// javax.sound.sampled lines aren't documented as thread-safe; the single-thread
// MAX_PRIORITY audio executor (provided by DI) gives us that guarantee.

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    @Volatile private var pauseRequested: Boolean = false
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

        val resolved: List<Pair<MediaItem, Playable>> = items.mapNotNull { item ->
            when (val r = source.getPlayable(item.itemId)) {
                is Either.Right -> item to r.value
                is Either.Left -> {
                    log.w { "loadQueue: skipping ${item.itemId.value}: ${r.value}" }
                    null
                }
            }
        }
        val resolvedItems = resolved.map { it.first }
        if (resolved.isEmpty()) {
            _queue.value = _queue.value.copy(items = emptyList(), currentIndex = -1)
            _state.value = PlayerState.Idle
            return@withContext
        }

        val coercedStart = startIndex.coerceIn(0, resolvedItems.lastIndex)
        _queue.value = _queue.value.copy(items = resolvedItems, currentIndex = coercedStart)

        startPlaybackForCurrentIndex(autoPlay)
    }

    override suspend fun play() = withContext(audioDispatcher) {
        if (released) return@withContext
        val l = line ?: return@withContext
        pauseRequested = false
        if (!l.isRunning) l.start()
        updateReadyState()
    }

    override suspend fun pause() = withContext(audioDispatcher) {
        if (released) return@withContext
        pauseRequested = true
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

    override suspend fun setRepeatMode(mode: RepeatMode) = withContext(audioDispatcher) {
        _queue.value = _queue.value.copy(repeatMode = mode)
    }

    override suspend fun setShuffleMode(enabled: Boolean) = withContext(audioDispatcher) {
        // Shuffle order generation is deferred to Phase 2a (vetting Item 12).
        // This only flips the flag; queue order does not yet shuffle.
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

    private suspend fun startPlaybackForCurrentIndex(autoPlay: Boolean) {
        val q = _queue.value
        val item = q.currentItem ?: run {
            _state.value = PlayerState.Idle
            return
        }
        val playable = when (val r = source.getPlayable(item.itemId)) {
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
        pauseRequested = !autoPlay
        applyGain()
        if (autoPlay) sourceLine.start()

        _state.value = PlayerState.Ready(isPlaying = autoPlay)
        _positionMs.value = 0L

        playbackJob = scope.launch {
            var lastTickedMs = 0L
            try {
                stream.frames.collect { frame ->
                    _processors.value.forEach { it.process(frame) }
                    while (pauseRequested && isActive) {
                        delay(POSITION_TICK_MS)
                    }
                    if (!isActive) return@collect
                    sourceLine.write(frame.bytes, 0, frame.byteCount)
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
        pauseRequested = false
    }

    private fun updateReadyState() {
        val l = line ?: return
        val current = _state.value
        if (current is PlayerState.Ready) {
            val isPlaying = l.isRunning && !pauseRequested
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
