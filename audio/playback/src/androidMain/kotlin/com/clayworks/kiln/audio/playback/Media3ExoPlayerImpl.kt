// Media3ExoPlayerImpl — the Android PlatformPlayer impl. Delegates audio
// rendering + decoding to Media3 ExoPlayer; wires audio focus + BLE
// becoming-noisy handling + MediaSession; observes ExoPlayer state via
// Player.Listener and republishes through the PlatformPlayer StateFlows.
//
// loadQueue resolves each MediaItem to a Playable via the injected MusicSource,
// converts to androidx.media3.common.MediaItem, and hands the list to ExoPlayer
// via setMediaItems + prepare. Items that fail to resolve are skipped with a
// log warning; the published queue reflects only the resolved items so that
// QueueState.currentIndex remains aligned with ExoPlayer's media-item indices.
//
// Audio chain (Phase 2a Track D-B Android): KilnRenderersFactory wraps Media3's
// DefaultAudioSink with Kiln's AudioProcessor chain. MediaProcessorAdapter
// bridges Kiln AudioProcessor (single-call process(frame)) to Media3
// AudioProcessor (queueInput/getOutput rotation). ReplayGainProcessor is wired
// at construction; its gain updates flow from the settings repository via a
// scope-launched collector and from per-track Playable resolution via
// onMediaItemTransition's playablesById lookup.
//
// What's still STUBBED: the MediaSessionService surface (deferred — MediaSession
// instance is constructed but service binding is a separate file).
//
// All ExoPlayer methods are called via Dispatchers.Main.immediate per the
// "ExoPlayer is single-thread accessed via its application looper" contract.

package com.clayworks.kiln.audio.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import com.clayworks.kiln.audio.dsp.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainPipelineMode
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.audio.dsp.replaygain.resolveGainLinear
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = Logger.withTag("Media3ExoPlayerImpl")

private const val POSITION_TICK_MS = 250L

class Media3ExoPlayerImpl(
    context: Context,
    private val source: MusicSource,
    private val settings: SettingsRepository,
    private val rgProcessor: ReplayGainProcessor,
) : PlatformPlayer {

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _queue = MutableStateFlow(
        QueueState(
            items = emptyList(),
            currentIndex = -1,
            repeatMode = RepeatMode.Off,
            shuffleEnabled = false,
        ),
    )
    override val queue: StateFlow<QueueState> = _queue.asStateFlow()

    private val _volume = MutableStateFlow(VolumeState(linear = 1.0f, muted = false))
    override val volume: StateFlow<VolumeState> = _volume.asStateFlow()

    private val _processors = MutableStateFlow<List<AudioProcessor>>(emptyList())
    override val processors: StateFlow<List<AudioProcessor>> = _processors.asStateFlow()

    // Released-state guard. ExoPlayer + MediaSession both throw
    // IllegalStateException if methods are called after their release().
    // Mirrors JavaSoundPlayerImpl's pattern so post-release no-ops are
    // safe and the public API is forgiving (e.g., Compose recomposition
    // racing with shutdown).
    @Volatile private var released: Boolean = false

    /**
     * Per-track Playable cache. Populated during loadQueue's resolution loop;
     * keyed by Media3 MediaItem.mediaId (we set that to itemId.value when
     * building the androidx.media3.common.MediaItem). Read by
     * onMediaItemTransition to recompute RG gain on track change.
     *
     * Map is rebuilt on each loadQueue (previous queue's entries are cleared).
     * Lookup is single-threaded — onMediaItemTransition runs on Main per
     * ExoPlayer's single-thread-access contract; loadQueue also runs on Main
     * via withContext(Dispatchers.Main.immediate).
     */
    private val playablesById: MutableMap<String, Playable> = mutableMapOf()

    // Monotonic loadQueue generation (Main-thread-only). Captured before the off-Main resolve and
    // re-checked after, so a slow first resolve can't apply on top of a newer click's result (codex).
    private var loadGeneration = 0L

    // Main-thread-only. Set true by pause() and cleared at the start of each loadQueue, so a pause()
    // that lands during a load's off-Main resolve window suppresses that load's autoplay instead of
    // being overridden by it (codex round 6). A stop() in the window is handled by loadGeneration.
    private var suppressAutoPlayForLoad = false

    /**
     * The currently-playing Playable, set on every onMediaItemTransition
     * and cleared on release. The settings-flow collector closes over this
     * to recompute gain when the user changes mode / pre-amp during playback.
     */
    @Volatile private var currentPlayable: Playable? = null

    // Player methods must run on the looper Exo was built on. Default is the
    // main looper; we marshal suspend entry points through Main.immediate to
    // match.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /**
     * Kiln's AudioProcessor chain — built once at construction. Currently
     * holds [MediaProcessorAdapter] wrapping [rgProcessor]; future processors
     * (EQ, room correction, visualizer fanout) join the array via DI when
     * they ship. The order in the array IS the processing order.
     */
    private val mediaAudioProcessors: Array<androidx.media3.common.audio.AudioProcessor> = arrayOf(
        MediaProcessorAdapter(rgProcessor),
    )

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(KilnRenderersFactory(context, mediaAudioProcessors))
        .setAudioAttributes(
            // USAGE_MEDIA + CONTENT_TYPE_MUSIC per spec §6.1 / vetting Item 11.
            // handleAudioFocus = true lets Media3 manage ducking + transient loss.
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // Pause when output device disconnects (BLE headphones, USB DAC unplug).
        // Vetting Item 11.
        .setHandleAudioBecomingNoisy(true)
        .build()

    // MediaSession surfaces the player to system controls (lockscreen, BT
    // headset, Android Auto, etc.). The full MediaSessionService wrapper is
    // deferred — this instance still emits playback state for system
    // observers that bind via the session's token.
    private val mediaSession: MediaSession = MediaSession.Builder(context, exo).build()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = when (playbackState) {
                Player.STATE_IDLE -> PlayerState.Idle
                Player.STATE_BUFFERING -> PlayerState.Buffering
                Player.STATE_READY -> PlayerState.Ready(isPlaying = exo.isPlaying)
                Player.STATE_ENDED -> PlayerState.Idle  // queue advanced past last item
                else -> PlayerState.Idle
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Only re-publish when ready; buffering/idle states already correct above.
            val current = _state.value
            if (current is PlayerState.Ready && current.isPlaying != isPlaying) {
                _state.value = PlayerState.Ready(isPlaying)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            log.e(error) { "ExoPlayer error: ${error.errorCodeName}" }
            _state.value = PlayerState.Error(
                PlayerError.DecodeFailed(error),
            )
        }

        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            val newIndex = exo.currentMediaItemIndex
            val current = _queue.value
            if (newIndex != current.currentIndex) {
                _queue.value = current.copy(currentIndex = newIndex)
            }

            // Update currentPlayable + apply RG gain for the new track. The
            // mediaId is itemId.value (set explicitly during loadQueue's
            // resolution loop below); playablesById is keyed by that.
            val mediaId = mediaItem?.mediaId
            val playable = mediaId?.let { playablesById[it] }
            currentPlayable = playable
            if (playable != null) {
                // Settings reads are suspend — launch a one-shot child coroutine
                // on the player's scope. We re-read currentPlayable inside the
                // launch body so a rapid track-skip sequence applies the LATEST
                // track's gain, not whichever transition's launch completes
                // first. @Volatile on currentPlayable provides the cross-coroutine
                // visibility this read needs.
                scope.launch {
                    val mode = settings.replayGainMode.first()
                    val preAmpDb = settings.replayGainPreAmpDb.first()
                    val latest = currentPlayable ?: return@launch
                    applyRgGain(latest, mode, preAmpDb)
                }
            } else {
                // Unknown mediaId or end-of-queue: zero the gain so a settings
                // change between tracks doesn't leave a stale multiplier on the
                // processor. The next onMediaItemTransition with a known playable
                // re-applies the correct gain before audio output resumes.
                rgProcessor.setLinearGain(1.0)
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _queue.value = _queue.value.copy(repeatMode = repeatMode.toKilnRepeatMode())
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _queue.value = _queue.value.copy(shuffleEnabled = shuffleModeEnabled)
        }

        override fun onVolumeChanged(volume: Float) {
            // Only sync the stored linear when NOT muted. When muted, the
            // user-initiated setMuted(true) forced exo.volume = 0.0f, which
            // triggers this callback with volume=0.0; if we wrote that into
            // linear we'd lose the user's pre-mute desired volume — unmuting
            // would then restore silence instead of the original level.
            // Source-of-truth for the user's desired linear is _volume.value;
            // ExoPlayer's actual volume is a derived signal.
            val v = _volume.value
            if (!v.muted) {
                _volume.value = v.copy(linear = volume)
            }
        }
    }

    private val positionTicker: Job = scope.launch {
        while (isActive) {
            delay(POSITION_TICK_MS)
            if (exo.isPlaying) {
                _positionMs.value = exo.currentPosition.coerceAtLeast(0L)
            }
        }
    }

    init {
        exo.addListener(playerListener)

        // Surface the Kiln processor in the public flow so Compose surfaces
        // can render the chain (mirror desktop). The Media3-side injection
        // already happened above via KilnRenderersFactory; this is the
        // observation surface only.
        _processors.value = _processors.value + rgProcessor

        // Observe settings changes; recompute + apply RG gain whenever mode
        // or pre-amp changes (while a track is playing). Mirrors
        // JavaSoundPlayerImpl.init's collector — desktop precedent.
        scope.launch {
            combine(
                settings.replayGainMode.distinctUntilChanged(),
                settings.replayGainPreAmpDb.distinctUntilChanged(),
            ) { mode, preAmp -> mode to preAmp }
                .collect { (mode, preAmp) ->
                    val playable = currentPlayable ?: return@collect
                    applyRgGain(playable, mode, preAmp)
                }
        }
    }

    /**
     * Resolve [playable]'s RG values + current settings into a linear gain
     * and apply it to [rgProcessor]. Translates :data:library's ReplayGainMode
     * to :audio:dsp's ReplayGainPipelineMode at the seam (the two enums are
     * isomorphic; see desktop precedent in JavaSoundPlayerImpl).
     */
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

    override suspend fun loadQueue(
        items: List<MediaItem>,
        startIndex: Int,
        autoPlay: Boolean,
    ) {
        if (released) return
        val gen = withContext(Dispatchers.Main.immediate) {
            suppressAutoPlayForLoad = false  // fresh load: clear any prior pause's autoplay-suppression (codex r6 P6-3)
            _state.value = PlayerState.Loading
            ++loadGeneration
        }

        try {
            // Resolve each MediaItem to a Playable OFF the Main thread. source.getPlayable runs a
            // SYNCHRONOUS SQLDelight query (LocalLibrarySource.getPlayable → executeAsOneOrNull, no
            // internal dispatch), and a "play from here" click now passes the whole loaded page
            // (~500 items, #28 item 2a) — resolving them on Main.immediate would block the UI thread for
            // hundreds of DB reads (codex). Items that fail to resolve are skipped; the published queue
            // reflects only playable items. Triple(originalIndex, item, playable) preserves the user's
            // pre-filter index mapping (U1) — see JavaSoundPlayerImpl for the analogous rationale.
            val resolved: List<Triple<Int, MediaItem, Playable>> = withContext(Dispatchers.IO) {
                items.mapIndexedNotNull { idx, item ->
                    when (val r = source.getPlayable(item.itemId)) {
                        is Either.Right -> Triple(idx, item, r.value)
                        is Either.Left -> {
                            log.w { "loadQueue: skipping ${item.itemId.value}: ${r.value}" }
                            null
                        }
                    }
                }
            }

            withContext(Dispatchers.Main.immediate) {
                // Drop a stale resolution: a newer loadQueue (e.g. a second rapid click) bumped the
                // generation while we resolved on IO, so applying ours would clobber the user's latest
                // selection (codex round 2).
                if (released || gen != loadGeneration) return@withContext
                // Repopulate the Playable cache (Main-thread state per ExoPlayer's single-thread contract)
                // from the off-Main resolution. onMediaItemTransition looks Playables up here by mediaId.
                playablesById.clear()
                resolved.forEach { (_, item, playable) -> playablesById[item.itemId.value] = playable }

                // Explicit mediaId = itemId.value so onMediaItemTransition can map back to the Playable.
                // Default Builder.setUri(...) sets mediaId = uri, which would alias two ItemIds at the
                // same URI. (Future MediaSessionService onGetItem must resolve via playablesById, not URI.)
                val media3Items = resolved.map { (_, item, playable) ->
                    androidx.media3.common.MediaItem.Builder()
                        .setUri(playable.uri)
                        .setMediaId(item.itemId.value)
                        .build()
                }

                val resolvedItems = resolved.map { it.second }
                // Map user's startIndex (original-list space) to resolved-list space:
                // empty → -1; ≤0 → first; exact → that index; missing → fall FORWARD; none after → last.
                val coercedStart = when {
                    resolvedItems.isEmpty() -> -1
                    startIndex <= 0 -> 0
                    else -> {
                        val matchOrSuccessor = resolved.indexOfFirst { (originalIdx, _, _) -> originalIdx >= startIndex }
                        if (matchOrSuccessor != -1) matchOrSuccessor else resolvedItems.lastIndex
                    }
                }

                _queue.value = _queue.value.copy(items = resolvedItems, currentIndex = coercedStart)

                if (media3Items.isEmpty()) {
                    _state.value = PlayerState.Idle
                    return@withContext
                }

                exo.setMediaItems(media3Items, coercedStart.coerceAtLeast(0), /* startPositionMs = */ 0L)
                exo.prepare()
                // Respect a pause() that landed during the off-Main resolve window: applying autoplay
                // here would override the user's intent, leaving the track playing instead of cued+paused
                // (codex round 6 P6-3). A stop() in the window instead bumps loadGeneration, so we'd have
                // already returned at the stale-check above.
                if (autoPlay && !suppressAutoPlayForLoad) exo.play()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The caller scope (composition-bound in LibraryTab/SearchTab) was cancelled mid-resolve —
            // e.g. the user navigated away while the off-Main resolution ran. Without this the apply
            // block never executes and the player is left stuck in Loading forever (codex round 6 P6-2).
            withContext(kotlinx.coroutines.NonCancellable) {
                withContext(Dispatchers.Main.immediate) {
                    if (!released && gen == loadGeneration && _state.value is PlayerState.Loading) {
                        _state.value = PlayerState.Idle
                    }
                }
            }
            throw e
        }
    }

    override suspend fun play() = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        exo.play()
    }

    override suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        // If a loadQueue is mid-resolve, mark its autoplay suppressed so it cues the track paused
        // instead of overriding this pause when it applies (codex round 6 P6-3).
        suppressAutoPlayForLoad = true
        exo.pause()
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        // Supersede any in-flight loadQueue still resolving on IO: bump the generation so its
        // Main-thread apply sees gen != loadGeneration and drops, instead of clobbering this stop
        // with a late setMediaItems + play (codex round 4).
        ++loadGeneration
        exo.stop()
        // Publish Idle explicitly: loadQueue already set _state = Loading, and if exo is already idle
        // (the dropped load never started playback) exo.stop() fires no STATE_IDLE transition for the
        // listener — without this the UI stays stuck in Loading after the stale load drops (codex r5 C4).
        _state.value = PlayerState.Idle
    }

    override suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        exo.seekTo(positionMs.coerceAtLeast(0L))
        _positionMs.value = exo.currentPosition.coerceAtLeast(0L)
    }

    override suspend fun skipToNext() = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
    }

    override suspend fun skipToPrevious() = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        if (exo.hasPreviousMediaItem()) exo.seekToPreviousMediaItem()
    }

    override suspend fun skipTo(queueIndex: Int) = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        if (queueIndex in 0 until exo.mediaItemCount) {
            exo.seekTo(queueIndex, /* positionMs = */ 0L)
        }
    }

    override suspend fun setRepeatMode(mode: RepeatMode) = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        exo.repeatMode = mode.toExoRepeatMode()
    }

    override suspend fun setShuffleMode(enabled: Boolean) = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        exo.shuffleModeEnabled = enabled
    }

    override suspend fun setVolume(linear: Float) = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        val clamped = linear.coerceIn(0.0f, 1.0f)
        // Update _volume FIRST so the listener's onVolumeChanged (fired by the
        // exo.volume write below) sees the post-state and applies its !muted
        // guard correctly. If we wrote to exo first, the listener would observe
        // the pre-update _volume.value and could overwrite linear with whatever
        // exo set (typically the same value, but the ordering is fragile).
        _volume.value = _volume.value.copy(linear = clamped)
        exo.volume = if (_volume.value.muted) 0.0f else clamped
    }

    override suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        // Update _volume.muted FIRST so the listener's onVolumeChanged sees the
        // post-mute state and skips the linear update (preserving the user's
        // pre-mute volume so unmute restores it correctly). See U4 — without
        // this ordering, setMuted(true) → exo.volume=0 → listener fires
        // → linear gets clobbered with 0.0 → setMuted(false) restores silence.
        _volume.value = _volume.value.copy(muted = muted)
        exo.volume = if (muted) 0.0f else _volume.value.linear
    }

    override fun addAudioProcessor(processor: AudioProcessor) {
        if (released) return
        // The Media3 audio chain is fixed at construction time via
        // KilnRenderersFactory + DefaultAudioSink.Builder.setAudioProcessors.
        // Adding a processor here only updates the observation flow — the
        // actual audio path runs the chain built in init.
        //
        // To add a new processor dynamically (e.g., a future EQ that the user
        // toggles on/off), the right shape is either: (1) tear down + rebuild
        // the player with the new chain, or (2) make individual processors
        // toggleable via a setEnabled(Boolean) method that doesn't change the
        // chain shape. ReplayGainProcessor uses approach (2) — gain == 1.0
        // is a hard-coded passthrough fast-path in the impl itself.
        _processors.value = _processors.value + processor
    }

    override fun removeAudioProcessor(processor: AudioProcessor) {
        if (released) return
        _processors.value = _processors.value - processor
    }

    override suspend fun release() = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext  // idempotent: repeat-release is a safe no-op
        released = true
        positionTicker.cancel()
        // playablesById and currentPlayable are written by loadQueue +
        // onMediaItemTransition, both on Main. release() also runs on Main via
        // withContext(Dispatchers.Main.immediate), so these mutations are
        // sequenced — no synchronization needed.
        playablesById.clear()
        currentPlayable = null
        exo.removeListener(playerListener)
        mediaSession.release()
        exo.release()
        scope.cancel()
    }

    override suspend fun enterMeasurementMode(): MeasurementSession? = null
}

// ---------- mapping helpers ----------

private fun RepeatMode.toExoRepeatMode(): Int = when (this) {
    RepeatMode.Off -> Player.REPEAT_MODE_OFF
    RepeatMode.One -> Player.REPEAT_MODE_ONE
    RepeatMode.All -> Player.REPEAT_MODE_ALL
}

private fun Int.toKilnRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_OFF -> RepeatMode.Off
    Player.REPEAT_MODE_ONE -> RepeatMode.One
    Player.REPEAT_MODE_ALL -> RepeatMode.All
    else -> RepeatMode.Off
}
