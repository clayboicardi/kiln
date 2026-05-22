package com.clayworks.kiln.audio.dsp.replaygain

import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayGainProcessorTest {

    private fun pcm16(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i]
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun bytesToShorts(b: ByteArray): IntArray {
        val out = IntArray(b.size / 2)
        for (i in out.indices) {
            val lo = b[i * 2].toInt() and 0xFF
            val hi = b[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort().toInt()
        }
        return out
    }

    @Test
    fun `processor with gain 1_0 is passthrough for S16`() {
        val processor = ReplayGainProcessor()
        processor.onFormatChange(DecodedAudioFormat(44100, 16, 2, SampleFormat.PCM_S16_LE))
        processor.setLinearGain(1.0)

        val samples = intArrayOf(0, 1000, -1000, 16384, -16384)
        val frame = AudioFrame(pcm16(samples), samples.size * 2, samples.size, 0L)
        val out = processor.process(frame)

        val outSamples = bytesToShorts(out.bytes.copyOfRange(0, out.byteCount))
        for (i in samples.indices) {
            assertEquals(samples[i], outSamples[i], "passthrough at index $i")
        }
    }

    @Test
    fun `processor with gain 0_5 halves S16 samples`() {
        val processor = ReplayGainProcessor()
        processor.onFormatChange(DecodedAudioFormat(44100, 16, 2, SampleFormat.PCM_S16_LE))
        processor.setLinearGain(0.5)

        val samples = intArrayOf(0, 1000, -1000, 16384, -16384)
        val frame = AudioFrame(pcm16(samples), samples.size * 2, samples.size, 0L)
        val out = processor.process(frame)

        val outSamples = bytesToShorts(out.bytes.copyOfRange(0, out.byteCount))
        for (i in samples.indices) {
            val expected = (samples[i] * 0.5).toInt()
            assertEquals(expected, outSamples[i], "expected ${expected} at index $i, got ${outSamples[i]}")
        }
    }

    @Test
    fun `processor with gain 2_0 doubles S16 samples and clamps at extremes`() {
        val processor = ReplayGainProcessor()
        processor.onFormatChange(DecodedAudioFormat(44100, 16, 2, SampleFormat.PCM_S16_LE))
        processor.setLinearGain(2.0)

        val samples = intArrayOf(1000, 20000, -20000)
        val frame = AudioFrame(pcm16(samples), samples.size * 2, samples.size, 0L)
        val out = processor.process(frame)

        val outSamples = bytesToShorts(out.bytes.copyOfRange(0, out.byteCount))
        assertEquals(2000, outSamples[0])
        assertEquals(32767, outSamples[1], "positive clip")
        assertEquals(-32768, outSamples[2], "negative clip")
    }

    @Test
    fun `processor format change preserves gain`() {
        val processor = ReplayGainProcessor()
        processor.onFormatChange(DecodedAudioFormat(44100, 16, 2, SampleFormat.PCM_S16_LE))
        processor.setLinearGain(0.5)
        processor.onFormatChange(DecodedAudioFormat(48000, 16, 2, SampleFormat.PCM_S16_LE))

        val samples = intArrayOf(1000, 2000)
        val frame = AudioFrame(pcm16(samples), samples.size * 2, samples.size, 0L)
        val out = processor.process(frame)

        val outSamples = bytesToShorts(out.bytes.copyOfRange(0, out.byteCount))
        assertEquals(500, outSamples[0])
        assertEquals(1000, outSamples[1])
    }

    @Test
    fun `processor has stable id`() {
        val p1 = ReplayGainProcessor()
        val p2 = ReplayGainProcessor()
        assertEquals(p1.id, p2.id)
        assertTrue(p1.id.isNotBlank())
    }
}
