// FakePlatformPlayer — shared test double for :ui:components UI tests.

package com.clayworks.kiln.ui.components.nowplaying

import com.clayworks.kiln.audio.dsp.AudioProcessor
import com.clayworks.kiln.audio.playback.MeasurementSession
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.audio.playback.QueueState
import com.clayworks.kiln.audio.playback.RepeatMode
import com.clayworks.kiln.audio.playback.VolumeState
import com.clayworks.kiln.library.source.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal in-memory [PlatformPlayer] stub shared by the `:ui:components` UI tests.
 * Only the StateFlow surface is meaningful; transport methods are no-ops.
 */
internal class FakePlatformPlayer(
    initialItem: MediaItem,
    initialState: PlayerState,
) : PlatformPlayer {
    private val _state = MutableStateFlow(initialState)
    private val _queue = MutableStateFlow(
        QueueState(
            items = listOf(initialItem),
            currentIndex = 0,
            repeatMode = RepeatMode.Off,
            shuffleEnabled = false,
        ),
    )
    private val _positionMs = MutableStateFlow(0L)
    private val _volume = MutableStateFlow(VolumeState(linear = 1.0f, muted = false))
    private val _processors = MutableStateFlow<List<AudioProcessor>>(emptyList())

    override val state: StateFlow<PlayerState> = _state
    override val queue: StateFlow<QueueState> = _queue
    override val positionMs: StateFlow<Long> = _positionMs
    override val volume: StateFlow<VolumeState> = _volume
    override val processors: StateFlow<List<AudioProcessor>> = _processors

    override suspend fun loadQueue(
        items: List<MediaItem>,
        startIndex: Int,
        autoPlay: Boolean,
    ) {
    }
    override suspend fun play() {}
    override suspend fun pause() {}
    override suspend fun stop() {}
    override suspend fun seekTo(positionMs: Long) {}
    override suspend fun skipToNext() {}
    override suspend fun skipToPrevious() {}
    override suspend fun skipTo(queueIndex: Int) {}
    override suspend fun setRepeatMode(mode: RepeatMode) {}
    override suspend fun setShuffleMode(enabled: Boolean) {}
    override suspend fun setVolume(linear: Float) {}
    override suspend fun setMuted(muted: Boolean) {}
    override fun addAudioProcessor(processor: AudioProcessor) {}
    override fun removeAudioProcessor(processor: AudioProcessor) {}
    override suspend fun release() {}
    override suspend fun enterMeasurementMode(): MeasurementSession? = null
}
