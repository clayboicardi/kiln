// Queue + volume + repeat-mode state types. Surfaces via PlatformPlayer
// StateFlows. Queue is materialized as List<MediaItem>; the underlying
// MusicSource resolves each item to a Playable just-in-time before
// decoder open.

package com.clayworks.kiln.audio.playback

import com.clayworks.kiln.library.source.MediaItem

data class QueueState(
    val items: List<MediaItem>,
    val currentIndex: Int,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
) {
    val currentItem: MediaItem? get() = items.getOrNull(currentIndex)

    val hasNext: Boolean get() = when (repeatMode) {
        RepeatMode.All, RepeatMode.One -> items.isNotEmpty()
        RepeatMode.Off -> currentIndex < items.lastIndex
    }

    val hasPrevious: Boolean get() = currentIndex > 0 || repeatMode == RepeatMode.All
}

enum class RepeatMode { Off, One, All }

data class VolumeState(
    val linear: Float,  // 0.0..1.0
    val muted: Boolean,
)
