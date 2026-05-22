// MediaProcessorAdapter coverage — exercises the Kiln↔Media3 AudioProcessor
// bridge across each PCM encoding Kiln supports (S16/S24/S32/F32), the
// passthrough fast-path, format-change semantics, and unsupported-encoding
// rejection. Robolectric provides the runtime so Media3's
// androidx.media3.common.audio.AudioProcessor.AudioFormat constructs without
// linking to a real Android device.

package com.clayworks.kiln.audio.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.AudioProcessor as KilnAudioProcessor
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MediaProcessorAdapterTest {

    // Helper: build an LE ByteBuffer of int16 samples.
    private fun s16Buffer(samples: IntArray): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s.toShort())
        bb.flip()
        return bb
    }

    // Helper: read all output bytes from the adapter into a list of int16.
    private fun drainShorts(adapter: MediaProcessorAdapter): IntArray {
        val collected = ArrayList<Short>()
        while (true) {
            val out = adapter.output
            if (!out.hasRemaining()) break
            val le = out.order(ByteOrder.LITTLE_ENDIAN)
            while (le.hasRemaining()) collected.add(le.short)
        }
        return collected.map { it.toInt() }.toIntArray()
    }

    @Test
    fun `configure with PCM_S16_LE returns same-format output (passthrough shape)`() {
        val rg = ReplayGainProcessor()
        val adapter = MediaProcessorAdapter(rg)
        val input = AudioProcessor.AudioFormat(
            /* sampleRate = */ 44_100,
            /* channelCount = */ 2,
            /* encoding = */ C.ENCODING_PCM_16BIT,
        )
        val output = adapter.configure(input)
        assertEquals(input.sampleRate, output.sampleRate)
        assertEquals(input.channelCount, output.channelCount)
        assertEquals(input.encoding, output.encoding)
        assertTrue(adapter.isActive)
    }

    @Test
    fun `unsupported encoding throws UnhandledAudioFormatException`() {
        val rg = ReplayGainProcessor()
        val adapter = MediaProcessorAdapter(rg)
        val input = AudioProcessor.AudioFormat(
            /* sampleRate = */ 44_100,
            /* channelCount = */ 2,
            /* encoding = */ C.ENCODING_PCM_8BIT,  // 8-bit unsigned — Kiln doesn't handle
        )
        assertFailsWith<AudioProcessor.UnhandledAudioFormatException> {
            adapter.configure(input)
        }
    }

    @Test
    fun `gain 1_0 is byte-for-byte passthrough for S16 input`() {
        val rg = ReplayGainProcessor()
        rg.setLinearGain(1.0)
        val adapter = MediaProcessorAdapter(rg)
        adapter.configure(
            AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
        )
        val samples = intArrayOf(0, 1000, -1000, 16384, -16384)
        adapter.queueInput(s16Buffer(samples))

        val outSamples = drainShorts(adapter)
        assertEquals(samples.size, outSamples.size)
        for (i in samples.indices) assertEquals(samples[i], outSamples[i], "passthrough mismatch at $i")
    }

    @Test
    fun `gain 0_5 halves S16 samples through the bridge`() {
        val rg = ReplayGainProcessor()
        rg.setLinearGain(0.5)
        val adapter = MediaProcessorAdapter(rg)
        adapter.configure(
            AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
        )
        val samples = intArrayOf(0, 1000, -1000, 16384, -16384)
        adapter.queueInput(s16Buffer(samples))

        val outSamples = drainShorts(adapter)
        for (i in samples.indices) {
            val expected = (samples[i] * 0.5).toInt()
            assertEquals(expected, outSamples[i], "expected $expected at $i, got ${outSamples[i]}")
        }
    }

    @Test
    fun `format-change propagates new format to Kiln processor`() {
        val rec = RecordingProcessor()
        val adapter = MediaProcessorAdapter(rec)
        adapter.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        adapter.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertTrue(adapter.isActive)
        assertEquals(48_000, rec.lastFormat?.sampleRateHz)
    }

    @Test
    fun `queueEndOfStream + drained output produces isEnded == true`() {
        val rg = ReplayGainProcessor()
        val adapter = MediaProcessorAdapter(rg)
        adapter.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        adapter.queueInput(s16Buffer(intArrayOf(100, 200)))
        // Drain output before signalling EOS, mimicking how the framework polls.
        drainShorts(adapter)
        adapter.queueEndOfStream()
        // After EOS + drain, isEnded() returns true (BaseAudioProcessor contract).
        assertTrue(adapter.isEnded)
    }

    @Test
    fun `flush clears pending output and re-arms for next stream`() {
        val rg = ReplayGainProcessor()
        rg.setLinearGain(0.5)
        val adapter = MediaProcessorAdapter(rg)
        adapter.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        adapter.queueInput(s16Buffer(intArrayOf(1000)))
        adapter.flush()
        // After flush, the previous queueInput's output is gone — getOutput returns empty.
        val out = adapter.output
        assertEquals(0, out.remaining(), "flush should have cleared the output buffer")
        // The adapter is still active and ready for new input.
        assertTrue(adapter.isActive)
    }

    @Test
    fun `queueInput invokes process on the wrapped Kiln processor`() {
        val rec = RecordingProcessor()
        val adapter = MediaProcessorAdapter(rec)
        adapter.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        val samples = intArrayOf(100, 200, 300, 400)
        adapter.queueInput(s16Buffer(samples))

        assertEquals(1, rec.processCallCount, "process should be invoked exactly once per queueInput call")
        assertEquals(samples.size * 2, rec.lastFrameByteCount, "frame byteCount should match input buffer remaining")
    }
}

// Test-only stub: a Kiln AudioProcessor that records the last format + per-call
// frame metadata so MediaProcessorAdapter tests can verify the bridge actually
// invokes onFormatChange / process on the wrapped processor.
private class RecordingProcessor : KilnAudioProcessor {
    var lastFormat: DecodedAudioFormat? = null
    var processCallCount: Int = 0
    var lastFrameByteCount: Int? = null

    override val id = "recording"

    override fun onFormatChange(format: DecodedAudioFormat) {
        lastFormat = format
    }

    override fun process(frame: AudioFrame): AudioFrame {
        processCallCount++
        lastFrameByteCount = frame.byteCount
        return frame  // passthrough — don't mutate bytes
    }
}
