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
// What's still STUBBED: KilnRenderersFactory injecting the AudioProcessor chain
// (lands when :audio:dsp gets its first concrete processor at MVP Sessions
// 16-22), and the MediaSessionService surface (deferred — MediaSession instance
// is constructed but service binding is a separate file).
//
// All ExoPlayer methods are called via Dispatchers.Main.immediate per the
// "ExoPlayer is single-thread accessed via its application looper" contract.

package com.clayworks.kiln.audio.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import arrow.core.Either
import co.touchlab.kermit.Logger
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

private val log = Logger.withTag("Media3ExoPlayerImpl")

private const val POSITION_TICK_MS = 250L

class Media3ExoPlayerImpl(
    context: Context,
    private val source: MusicSource,
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

    // Player methods must run on the looper Exo was built on. Default is the
    // main looper; we marshal suspend entry points through Main.immediate to
    // match.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
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
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _queue.value = _queue.value.copy(repeatMode = repeatMode.toKilnRepeatMode())
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _queue.value = _queue.value.copy(shuffleEnabled = shuffleModeEnabled)
        }

        override fun onVolumeChanged(volume: Float) {
            _volume.value = _volume.value.copy(linear = volume)
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
    }

    override suspend fun loadQueue(
        items: List<MediaItem>,
        startIndex: Int,
        autoPlay: Boolean,
    ) = withContext(Dispatchers.Main.immediate) {
        _state.value = PlayerState.Loading

        // Resolve each MediaItem to a Playable via the Source Protocol. Items
        // that fail to resolve (file deleted, transient I/O error, etc.) are
        // skipped — the published queue reflects only the items that will
        // actually play, keeping currentIndex aligned with ExoPlayer's
        // media-item indices.
        val resolved: List<Pair<MediaItem, String>> = items.mapNotNull { item ->
            when (val r = source.getPlayable(item.itemId)) {
                is Either.Right -> item to r.value.uri
                is Either.Left -> {
                    log.w { "loadQueue: skipping ${item.itemId.value}: ${r.value}" }
                    null
                }
            }
        }

        val media3Items = resolved.map { (_, uri) ->
            androidx.media3.common.MediaItem.fromUri(uri)
        }

        val resolvedItems = resolved.map { it.first }
        val coercedStart = if (resolvedItems.isEmpty()) {
            -1
        } else {
            startIndex.coerceIn(0, resolvedItems.lastIndex)
        }

        _queue.value = _queue.value.copy(
            items = resolvedItems,
            currentIndex = coercedStart,
        )

        if (media3Items.isEmpty()) {
            _state.value = PlayerState.Idle
            return@withContext
        }

        exo.setMediaItems(media3Items, coercedStart.coerceAtLeast(0), /* startPositionMs = */ 0L)
        exo.prepare()
        if (autoPlay) exo.play()
    }

    override suspend fun play() = withContext(Dispatchers.Main.immediate) {
        exo.play()
    }

    override suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        exo.pause()
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        exo.stop()
    }

    override suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.Main.immediate) {
        exo.seekTo(positionMs.coerceAtLeast(0L))
        _positionMs.value = exo.currentPosition.coerceAtLeast(0L)
    }

    override suspend fun skipToNext() = withContext(Dispatchers.Main.immediate) {
        if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
    }

    override suspend fun skipToPrevious() = withContext(Dispatchers.Main.immediate) {
        if (exo.hasPreviousMediaItem()) exo.seekToPreviousMediaItem()
    }

    override suspend fun skipTo(queueIndex: Int) = withContext(Dispatchers.Main.immediate) {
        if (queueIndex in 0 until exo.mediaItemCount) {
            exo.seekTo(queueIndex, /* positionMs = */ 0L)
        }
    }

    override suspend fun setRepeatMode(mode: RepeatMode) = withContext(Dispatchers.Main.immediate) {
        exo.repeatMode = mode.toExoRepeatMode()
    }

    override suspend fun setShuffleMode(enabled: Boolean) = withContext(Dispatchers.Main.immediate) {
        exo.shuffleModeEnabled = enabled
    }

    override suspend fun setVolume(linear: Float) = withContext(Dispatchers.Main.immediate) {
        val clamped = linear.coerceIn(0.0f, 1.0f)
        exo.volume = if (_volume.value.muted) 0.0f else clamped
        _volume.value = _volume.value.copy(linear = clamped)
    }

    override suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.Main.immediate) {
        exo.volume = if (muted) 0.0f else _volume.value.linear
        _volume.value = _volume.value.copy(muted = muted)
    }

    override fun addAudioProcessor(processor: AudioProcessor) {
        // TODO(MVP Sessions 16-22): inject the chain into ExoPlayer via a custom
        // RenderersFactory that wraps AudioSink with a kiln-controlled
        // AudioProcessor pipeline. Until :audio:dsp ships its first concrete
        // processor, the chain is observed-only — Compose surfaces can render the
        // list but processors aren't actually invoked on audio frames.
        _processors.value = _processors.value + processor
    }

    override fun removeAudioProcessor(processor: AudioProcessor) {
        _processors.value = _processors.value - processor
    }

    override suspend fun release() = withContext(Dispatchers.Main.immediate) {
        positionTicker.cancel()
        exo.removeListener(playerListener)
        mediaSession.release()
        exo.release()
        scope.cancel()
    }
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
