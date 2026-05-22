package com.clayworks.kiln.audio.dsp.replaygain

import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.AudioProcessor
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat

/**
 * Linear-gain audio processor for ReplayGain consumer-side gain.
 *
 * Holds a single Double `linearGain` value (1.0 = passthrough; 0.5 = -6 dB
 * attenuation; 2.0 = +6 dB amplification with hard clipping at the bit-depth
 * envelope). Update via [setLinearGain] from any thread — the processor uses
 * a volatile var so the playback thread's `process(frame)` reads the latest
 * value with each invocation.
 *
 * Clipping policy: positive gain that would push a sample past the bit-depth
 * envelope clamps to the envelope. This is a hard clip — the upstream
 * [resolveGainLinear] is responsible for peak-limiting so this never
 * triggers in normal operation. If it does trigger, the result is audible
 * distortion (intentional — the alternative would be silent failure).
 *
 * Gain = 1.0 is a fast-path — `process(frame)` returns the frame unchanged
 * without any byte mutation. The playback hot path takes zero overhead when
 * RG is in Off mode.
 *
 * Implementation note: all per-sample format handlers use pure Kotlin integer
 * arithmetic (no java.nio.ByteBuffer) to satisfy the :audio:dsp Concentric
 * Modules invariant — platform-free code in commonMain only.
 */
class ReplayGainProcessor : AudioProcessor {

    @Volatile
    private var linearGain: Double = 1.0

    @Volatile
    private var format: DecodedAudioFormat? = null

    override val id: String = "replay-gain-processor"

    override fun onFormatChange(format: DecodedAudioFormat) {
        this.format = format
    }

    fun setLinearGain(gain: Double) {
        linearGain = gain
    }

    fun currentLinearGain(): Double = linearGain

    override fun process(frame: AudioFrame): AudioFrame {
        val gain = linearGain
        if (gain == 1.0) return frame

        val fmt = format ?: return frame

        val bytes = frame.bytes
        val byteCount = frame.byteCount

        when (fmt.sampleFormat) {
            SampleFormat.PCM_S16_LE -> applyGainS16(bytes, byteCount, gain)
            SampleFormat.PCM_S24_LE -> applyGainS24(bytes, byteCount, gain)
            SampleFormat.PCM_S32_LE -> applyGainS32(bytes, byteCount, gain)
            SampleFormat.PCM_F32_LE -> applyGainF32(bytes, byteCount, gain)
        }
        return frame
    }

    private fun applyGainS16(bytes: ByteArray, byteCount: Int, gain: Double) {
        var i = 0
        while (i < byteCount) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toInt()
            val scaled = (s * gain).toInt().coerceIn(-32768, 32767)
            bytes[i] = (scaled and 0xFF).toByte()
            bytes[i + 1] = ((scaled ushr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun applyGainS24(bytes: ByteArray, byteCount: Int, gain: Double) {
        var i = 0
        while (i < byteCount) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt()
            val s = (b2 shl 16) or (b1 shl 8) or b0
            val scaled = (s * gain).toInt().coerceIn(-8388608, 8388607)
            bytes[i] = (scaled and 0xFF).toByte()
            bytes[i + 1] = ((scaled ushr 8) and 0xFF).toByte()
            bytes[i + 2] = ((scaled ushr 16) and 0xFF).toByte()
            i += 3
        }
    }

    private fun applyGainS32(bytes: ByteArray, byteCount: Int, gain: Double) {
        var i = 0
        while (i < byteCount) {
            val s = (bytes[i].toInt() and 0xFF) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                (bytes[i + 3].toInt() shl 24)
            val scaled = (s * gain).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            bytes[i] = (scaled and 0xFF).toByte()
            bytes[i + 1] = ((scaled ushr 8) and 0xFF).toByte()
            bytes[i + 2] = ((scaled ushr 16) and 0xFF).toByte()
            bytes[i + 3] = ((scaled ushr 24) and 0xFF).toByte()
            i += 4
        }
    }

    /**
     * F32 LE gain via pure bit-manipulation — reads IEEE 754 float from four
     * little-endian bytes, applies gain, writes back. No java.nio.ByteBuffer,
     * keeping this commonMain-safe per Concentric Modules.
     *
     * No clamping: float overflow produces ±Infinity which is graceful for
     * downstream consumers (DAC typically clips transparently). The upstream
     * resolveGainLinear peak-limit guard should prevent this in practice.
     */
    private fun applyGainF32(bytes: ByteArray, byteCount: Int, gain: Double) {
        var i = 0
        while (i < byteCount) {
            // Read 4 bytes as little-endian IEEE 754 float.
            val bits = (bytes[i].toInt() and 0xFF) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                (bytes[i + 3].toInt() shl 24)
            val f = Float.fromBits(bits)
            val scaled = (f.toDouble() * gain).toFloat()
            val scaledBits = scaled.toBits()
            bytes[i] = (scaledBits and 0xFF).toByte()
            bytes[i + 1] = ((scaledBits ushr 8) and 0xFF).toByte()
            bytes[i + 2] = ((scaledBits ushr 16) and 0xFF).toByte()
            bytes[i + 3] = ((scaledBits ushr 24) and 0xFF).toByte()
            i += 4
        }
    }
}
