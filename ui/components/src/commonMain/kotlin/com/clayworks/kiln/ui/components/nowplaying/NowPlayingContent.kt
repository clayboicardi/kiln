// NowPlayingContent — minimal player surface (no FFT visualizer). Track C2
// adds the Fluid Canvas viz; Track C3 adds queue/reorder/mini-player overlay.
//
// State hoisted via NowPlayingState — Tab wrapper collects PlatformPlayer
// flows into this shape and passes in. Transport callbacks fire through
// rememberCoroutineScope().launch in the Tab.

package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.library.source.MediaItem

data class NowPlayingState(
    val playerState: PlayerState,
    val currentItem: MediaItem?,
    val positionMs: Long,
    val durationMs: Long,
)

@Composable
fun NowPlayingContent(
    state: NowPlayingState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.currentItem
    if (item == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Nothing playing",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val isPlaying = (state.playerState as? PlayerState.Ready)?.isPlaying == true

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Title + subtitle (artist - album when subtitle is "artist — album")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            item.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Position slider + time labels
        Column(modifier = Modifier.fillMaxWidth()) {
            val duration = state.durationMs.coerceAtLeast(1L).toFloat()
            val position = state.positionMs.coerceIn(0L, state.durationMs).toFloat()
            Slider(
                value = position,
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..duration,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = formatMs(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(text = formatMs(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        // Transport
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSkipPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Skip previous")
            }
            IconButton(onClick = onPlayPause) {
                val (icon, label) = if (isPlaying) {
                    Icons.Filled.Pause to "Pause"
                } else {
                    Icons.Filled.PlayArrow to "Play"
                }
                Icon(icon, contentDescription = label)
            }
            IconButton(onClick = onSkipNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Skip next")
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
