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

    // ===== S24 LE coverage =====

    private fun pcm24(samples: IntArray): ByteArray {
        // Pack int24 samples as little-endian 3-byte each. Caller passes
        // values in the int24 signed range [-8388608, 8388607].
        val out = ByteArray(samples.size * 3)
        for (i in samples.indices) {
            val s = samples[i]
            out[i * 3] = (s and 0xFF).toByte()
            out[i * 3 + 1] = ((s ushr 8) and 0xFF).toByte()
            out[i * 3 + 2] = ((s ushr 16) and 0xFF).toByte()
        }
        return out
    }

    private fun bytesToInt24s(b: ByteArray): IntArray {
        val out = IntArray(b.size / 3)
        for (i in out.indices) {
            val b0 = b[i * 3].toInt() and 0xFF
            val b1 = b[i * 3 + 1].toInt() and 0xFF
            val b2 = b[i * 3 + 2].toInt()  // signed → sign-extend
            out[i] = (b2 shl 16) or (b1 shl 8) or b0
        }
        return out
    }

    @Test
    fun `processor with gain 0_5 halves S24 samples`() {
        val processor = ReplayGainProcessor()
        processor.onFormatChange(DecodedAudioFormat(96000, 24, 2, SampleFormat.PCM_S24_LE))
        processor.setLinearGain(0.5)

        val samples = intArrayOf(0, 100_000, -100_000, 8_000_000, -8_000_000)
        val frame = AudioFrame(pcm24(samples), samples.size * 3, samples.size, 0L)
        val out = processor.process(frame)

        val outSamples = bytesToInt24s(out.bytes.copyOfRange(0, out.byteCount))
        for (i in samples.indices) {
            val expected = (samples[i] * 0.5).toInt()
            assertEquals(expected, outSamples[i], "S24 0.5x at index $i")
        }
    }

    @Test
    fun `processor with gain 2_0 clamps S24 samples at envelope`() {
        val processor = ReplayGainProcessor()
        processor.onFormatChange(DecodedAudioFormat(96000, 24, 2, SampleFormat.PCM_S24_LE))
        processor.setLinearGain(2.0)

        // 5_000_000 * 2 = 10_000_000, exceeds int24 max (8_388_607) — must clamp.
        val samples = intArrayOf(100_000, 5_000_000, -5_000_000)
        val frame = AudioFrame(pcm24(samples), samples.size * 3, samples.size, 0L)
        val out = processor.process(frame)

        val outSamples = bytesToInt24s(out.bytes.copyOfRange(0, out.byteCount))
        assertEquals(200_000, outSamples[0])
        assertEquals(8_388_607, outSamples[1], "S24 positive clip at max")
        assertEquals(-8_388_608, outSamples[2], "S24 negative clip at min")
    }

    // ===== S32 LE coverage =====

    private fun pcm32(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 4)
        for (i in samples.indices) {
            val s = samples[i]
            out[i * 4] = (s and 0xFF).toByte()
            out[i * 4 + 1] = ((s ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((s ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((s ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun bytesToInt32s(b: ByteArray): IntArray {
        val out = IntArray(b.size / 4)
        for (i in out.indices) {
            out[i] = (b[i * 4].toInt() and 0xFF) or
                ((b[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((b[i * 4 + 2].toInt() and 0xFF) shl 16) or
                (b[i * 4 + 3].toInt() shl 24)
        }
        return out
    }

    @Test
    fun `processor with gain 0_5 halves S32 samples`() {
        val processor = ReplayGainProcessor()
        processor.onFormatChange(DecodedAudioFormat(96000, 32, 2, SampleFormat.PCM_S32_LE))
        processor.setLinearGain(0.5)

        val samples = intArrayOf(0, 1_000_000_000, -1_000_000_000)
        val frame = AudioFrame(pcm32(samples), samples.size * 4, samples.size, 0L)
        val out = processor.process(frame)

        val outSamples = bytesToInt32s(out.bytes.copyOfRange(0, out.byteCount))
        for (i in samples.indices) {
            // Long arithmetic in the impl avoids overflow; result rounds toward zero
            // via .toLong() on the Double — same as S16/S24 conventions.
            val expected = (samples[i] * 0.5).toLong().toInt()
            assertEquals(expected, outSamples[i], "S32 0.5x at index $i")
        }
    }

    // ===== F32 LE coverage =====

    private fun pcmF32(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 4)
        for (i in samples.indices) {
            val bits = samples[i].toBits()
            out[i * 4] = (bits and 0xFF).toByte()
            out[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun bytesToFloats(b: ByteArray): FloatArray {
        val out = FloatArray(b.size / 4)
        for (i in out.indices) {
            val bits = (b[i * 4].toInt() and 0xFF) or
                ((b[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((b[i * 4 + 2].toInt() and 0xFF) shl 16) or
                (b[i * 4 + 3].toInt() shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
    }

    @Test
    fun `processor with gain 0_5 halves F32 samples`() {
        val processor = ReplayGainProcessor()
        processor.onFormatChange(DecodedAudioFormat(44100, 32, 2, SampleFormat.PCM_F32_LE))
        processor.setLinearGain(0.5)

        val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 0.9f, -0.9f)
        val frame = AudioFrame(pcmF32(samples), samples.size * 4, samples.size, 0L)
        val out = processor.process(frame)

        val outSamples = bytesToFloats(out.bytes.copyOfRange(0, out.byteCount))
        for (i in samples.indices) {
            val expected = samples[i] * 0.5f
            assertTrue(
                kotlin.math.abs(outSamples[i] - expected) < 1e-6,
                "F32 0.5x at index $i: expected $expected, got ${outSamples[i]}",
            )
        }
    }
}
