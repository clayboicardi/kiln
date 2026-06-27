// PlayerCommand — the messages the JavaSoundPlayerImpl actor processes. The actor
// is the SOLE mutator of the SourceDataLine + DecodedStream + queue + state flows,
// so every control op is posted as one of these (non-suspending trySend) rather than
// touching that state cross-thread. Retires the withContext(audioDispatcher) control
// path that the blocking playback loop starved (#28 items 1 + 2).

package com.clayworks.kiln.audio.playback

import com.clayworks.kiln.audio.dsp.AudioProcessor
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.CompletableDeferred

internal sealed interface PlayerCommand {
    /** [resolved] = (originalIndex, item, playable) triples, resolved OFF the actor in
     *  JavaSoundPlayerImpl.loadQueue() so the ~500-item page isn't resolved on the audio thread
     *  (codex round 4). originalIndex preserves the pre-filter index for startIndex coercion. */
    data class LoadQueue(
        val resolved: List<Triple<Int, MediaItem, Playable>>,
        val startIndex: Int,
        val autoPlay: Boolean,
    ) : PlayerCommand
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data object Stop : PlayerCommand
    data class SeekTo(val positionMs: Long) : PlayerCommand
    data object SkipToNext : PlayerCommand
    data object SkipToPrevious : PlayerCommand
    data class SkipTo(val index: Int) : PlayerCommand
    data class SetRepeat(val mode: RepeatMode) : PlayerCommand
    data class SetShuffle(val enabled: Boolean) : PlayerCommand
    data class SetVolume(val linear: Float) : PlayerCommand
    data class SetMuted(val muted: Boolean) : PlayerCommand
    data class AddProcessor(val processor: AudioProcessor) : PlayerCommand
    data class RemoveProcessor(val processor: AudioProcessor) : PlayerCommand

    /** Recompute + apply ReplayGain for the current track. Posted by the settings-flow
     *  collector so the actor (sole owner of currentPlayable) applies the change. */
    data class ReapplyGain(val mode: ReplayGainMode, val preAmpDb: Double) : PlayerCommand

    data object Release : PlayerCommand

    /** Test-only barrier: the actor completes [ack] once this command is processed, so a
     *  test can await that all previously-sent commands have drained. Never sent by prod. */
    data class Barrier(val ack: CompletableDeferred<Unit>) : PlayerCommand
}
