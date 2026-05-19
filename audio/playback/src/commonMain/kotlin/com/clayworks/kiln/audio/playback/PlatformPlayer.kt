// PlatformPlayer — the engine-swap-shaped boundary per spec §13 / vetting
// Item 13 decision. MVP impls: Media3 ExoPlayer (androidMain), Java Sound +
// JNA libFLAC (desktopMain). Phase 2b Flights H+I may swap to AAudio MMAP +
// WASAPI behind this interface without touching consumers.
//
// Measurement-mode bits (Phase 3 room correction) deferred until that phase.

package com.clayworks.kiln.audio.playback

import com.clayworks.kiln.library.source.MediaItem
import kotlinx.coroutines.flow.StateFlow

interface PlatformPlayer {
    val state: StateFlow<PlayerState>
    val positionMs: StateFlow<Long>
    val queue: StateFlow<QueueState>
    val volume: StateFlow<VolumeState>
    val processors: StateFlow<List<AudioProcessor>>

    suspend fun loadQueue(
        items: List<MediaItem>,
        startIndex: Int = 0,
        autoPlay: Boolean = true,
    )

    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun skipToNext()
    suspend fun skipToPrevious()
    suspend fun skipTo(queueIndex: Int)
    suspend fun setRepeatMode(mode: RepeatMode)
    suspend fun setShuffleMode(enabled: Boolean)
    suspend fun setVolume(linear: Float)  // 0.0..1.0
    suspend fun setMuted(muted: Boolean)

    /** Insert a processor into the audio pipeline (EQ, ReplayGain, visualizer fan-out, etc.). */
    fun addAudioProcessor(processor: AudioProcessor)
    fun removeAudioProcessor(processor: AudioProcessor)

    /** Release platform resources. After release the player is unusable. */
    suspend fun release()
}
