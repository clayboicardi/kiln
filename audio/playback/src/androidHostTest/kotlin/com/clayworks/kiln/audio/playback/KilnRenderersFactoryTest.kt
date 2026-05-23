// KilnRenderersFactory coverage — smoke construction tests under Robolectric.
// Media3's DefaultRenderersFactory + DefaultAudioSink.Builder don't require a
// real audio device until playback starts, so we can verify the override path
// (buildAudioSink returns a non-null sink containing our processor chain)
// without an instrumented test.

package com.clayworks.kiln.audio.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KilnRenderersFactoryTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `factory constructs with an empty processor array`() {
        KilnRenderersFactory(context, arrayOf())
        // Construction not throwing IS the assertion.
    }

    @Test
    fun `factory constructs with a single processor`() {
        val passthrough = object : AudioProcessor {
            private var format: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
            override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
                format = inputAudioFormat; return inputAudioFormat
            }
            override fun isActive(): Boolean = true
            override fun queueInput(inputBuffer: java.nio.ByteBuffer) {}
            override fun queueEndOfStream() {}
            override fun getOutput(): java.nio.ByteBuffer = AudioProcessor.EMPTY_BUFFER
            override fun isEnded(): Boolean = true
            override fun flush() {}
            override fun reset() {}
        }
        KilnRenderersFactory(context, arrayOf(passthrough))
        // Construction not throwing IS the assertion.
    }

    @Test
    fun `buildAudioSink returns a DefaultAudioSink`() {
        val factory = KilnRenderersFactory(context, arrayOf())
        val sink = factory.buildAudioSinkForTest(
            context = context,
            enableFloatOutput = false,
            enableAudioOutputPlaybackParams = false,
        )
        assertTrue(sink is DefaultAudioSink, "buildAudioSink should return a DefaultAudioSink, got ${sink::class.simpleName}")
    }
}
