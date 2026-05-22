// KilnRenderersFactory — custom DefaultRenderersFactory subclass that injects
// Kiln's AudioProcessor chain into Media3's audio pipeline.
//
// Override path: DefaultRenderersFactory.buildAudioSink(context, enableFloatOutput,
// enableAudioOutputPlaybackParams) — the default impl constructs a
// DefaultAudioSink.Builder with no processors. We add .setAudioProcessors(chain)
// and return the resulting sink. ExoPlayer.Builder.setRenderersFactory(this)
// in Media3ExoPlayerImpl's init wires the factory into the player.
//
// Constructor accepts an Array<AudioProcessor> rather than a single processor
// so future room-correction / EQ / visualizer-fanout processors can join the
// chain without re-shaping this class. The order in the array IS the
// processing order (Media3 applies them sequentially, framework-side).

package com.clayworks.kiln.audio.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

internal class KilnRenderersFactory(
    context: Context,
    private val audioProcessors: Array<AudioProcessor>,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
        .setAudioProcessors(audioProcessors)
        .build()

    /** Test-only accessor exposing the protected parent [buildAudioSink]. Production code does not use this. */
    internal fun buildAudioSinkForTest(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
}
