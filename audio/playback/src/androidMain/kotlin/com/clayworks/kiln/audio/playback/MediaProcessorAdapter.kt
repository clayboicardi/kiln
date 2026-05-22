// MediaProcessorAdapter — bridges Kiln's com.clayworks.kiln.audio.dsp.AudioProcessor
// (single-call process(frame) returning the mutated frame) to Media3's
// androidx.media3.common.audio.AudioProcessor (buffer-rotation contract:
// queueInput accumulates input → getOutput returns processed bytes; framework
// drains).
//
// Extends androidx.media3.common.audio.BaseAudioProcessor to inherit the
// configure/getOutput/isActive/isEnded plumbing. Subclass implements:
//  - onConfigure: translate Media3 AudioFormat → Kiln DecodedAudioFormat; call
//    kilnProcessor.onFormatChange(...); return the same Media3 format (Kiln
//    processors are pass-through in shape — they multiply per-sample without
//    changing rate / channels / encoding).
//  - queueInput: copy input bytes into a fresh ByteArray; wrap in AudioFrame;
//    call kilnProcessor.process(frame) which mutates the bytes in-place; write
//    the mutated bytes into the output buffer from replaceOutputBuffer(...).
//
// Buffer rotation impedance: Kiln's process(frame) is synchronous + in-place.
// Media3 expects "accumulate input across multiple queueInput calls, then yield
// output progressively via getOutput". Our implementation makes the round-trip
// inside one queueInput call — the framework's next getOutput() drains all
// processed bytes for that input chunk. This is the simplest correct mapping;
// throughput is fine because RG processing is bounded by per-sample arithmetic
// on a hot loop (~tens of nanoseconds per sample on a Pixel 10).

package com.clayworks.kiln.audio.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import java.nio.ByteBuffer

internal class MediaProcessorAdapter(
    private val kilnProcessor: com.clayworks.kiln.audio.dsp.AudioProcessor,
) : BaseAudioProcessor() {

    /** Bytes-per-sample for the current encoding. Cached on configure to avoid a `when` per queueInput. */
    private var bytesPerSample: Int = 0

    /** Reusable buffer for copying input bytes into a ByteArray (Kiln's AudioFrame holds a ByteArray, not a ByteBuffer). */
    private var scratch: ByteArray = ByteArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val sampleFormat = pcmEncodingToSampleFormat(inputAudioFormat.encoding)
            ?: throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        bytesPerSample = bytesPerSampleFor(sampleFormat)

        // Translate to Kiln's DecodedAudioFormat. Bit-depth = 8 * bytesPerSample
        // (S16 = 16, S24 = 24, S32 = 32, F32 = 32). The Kiln processor uses this
        // to size its per-sample arithmetic.
        val kilnFormat = DecodedAudioFormat(
            sampleRateHz = inputAudioFormat.sampleRate,
            bitDepth = 8 * bytesPerSample,
            channels = inputAudioFormat.channelCount,
            sampleFormat = sampleFormat,
        )
        kilnProcessor.onFormatChange(kilnFormat)

        // RG is pass-through in shape: sampleRate, channelCount, encoding all unchanged.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Copy input bytes into a fresh ByteArray. AudioFrame holds ByteArray
        // (not ByteBuffer) because Kiln's commonMain processors run on every
        // platform; ByteBuffer is JVM-only. The copy is a single memcpy in JNI;
        // negligible vs the per-sample arithmetic that follows.
        if (scratch.size < remaining) scratch = ByteArray(remaining)
        inputBuffer.get(scratch, 0, remaining)

        // Wrap in AudioFrame. sampleCount and timestampMs aren't used by
        // ReplayGainProcessor (it only reads bytes + byteCount), but the
        // AudioFrame data class requires them — pass byte-derived sample count
        // and a placeholder timestamp.
        //
        // Integer division silently truncates for S24 when `remaining` is not a
        // multiple of 3. That's currently benign because ReplayGainProcessor
        // iterates by `byteCount`, not `sampleCount`. A future processor that
        // consumes `sampleCount` as a loop bound must verify
        // `remaining % bytesPerSample == 0` (and reject or pad if not) — Media3
        // does not guarantee chunk-size alignment to the sample boundary.
        val sampleCount = remaining / bytesPerSample
        val frame = AudioFrame(
            bytes = scratch,
            byteCount = remaining,
            sampleCount = sampleCount,
            timestampMs = 0L,
        )

        // Mutate the frame's bytes in place via the Kiln processor.
        kilnProcessor.process(frame)

        // Write the mutated bytes into BaseAudioProcessor's output buffer.
        // replaceOutputBuffer allocates / reuses a native-order direct ByteBuffer
        // sized to fit the request.
        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.put(scratch, 0, remaining)
        outputBuffer.flip()
    }

    override fun onFlush() {
        // No internal state to clear — bytesPerSample stays valid until the
        // next onConfigure. scratch is reused as-is (its size is monotonic).
    }

    override fun onReset() {
        // Full reset: drop the scratch buffer + clear bytesPerSample. The
        // adapter will re-init on the next onConfigure.
        scratch = ByteArray(0)
        bytesPerSample = 0
    }

    private fun pcmEncodingToSampleFormat(encoding: @C.PcmEncoding Int): SampleFormat? = when (encoding) {
        C.ENCODING_PCM_16BIT -> SampleFormat.PCM_S16_LE
        C.ENCODING_PCM_24BIT -> SampleFormat.PCM_S24_LE
        C.ENCODING_PCM_32BIT -> SampleFormat.PCM_S32_LE
        C.ENCODING_PCM_FLOAT -> SampleFormat.PCM_F32_LE
        else -> null
    }

    private fun bytesPerSampleFor(sampleFormat: SampleFormat): Int = when (sampleFormat) {
        SampleFormat.PCM_S16_LE -> 2
        SampleFormat.PCM_S24_LE -> 3
        SampleFormat.PCM_S32_LE -> 4
        SampleFormat.PCM_F32_LE -> 4
    }
}
