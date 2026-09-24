// JavaSoundOutputLine: PcmOutputLine over javax.sound's SourceDataLine. Since #37 this is the
// FALLBACK engine, used only when WASAPI can't initialize (and on Windows it's 16-bit-only; see
// CLAUDE.md). Behavior matches the pre-#37 player: 100 ms buffer, MASTER_GAIN volume, and
// interrupt() = stop() (+ flush() when discarding).

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.playback.OutputEngine
import com.clayworks.kiln.audio.playback.OutputRoute
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

internal class JavaSoundOutputLine(
    private val line: SourceDataLine,
    override val sourceFormat: DecodedAudioFormat,
) : PcmOutputLine {

    private val frameBytes = sourceFormat.channels * (sourceFormat.bitDepth / 8)

    override val bufferNanos: Long =
        framesToNanos((line.bufferSize / frameBytes).toLong(), sourceFormat.sampleRateHz)

    override val isRunning: Boolean get() = line.isRunning

    override fun start() = line.start()
    override fun stop() = line.stop()
    override fun flush() = line.flush()
    override fun write(bytes: ByteArray, off: Int, len: Int): Int = line.write(bytes, off, len)

    override fun queuedNanos(): Long {
        val queuedBytes = (line.bufferSize - line.available()).coerceAtLeast(0)
        return framesToNanos((queuedBytes / frameBytes).toLong(), sourceFormat.sampleRateHz)
    }

    override fun setVolume(linear: Float) {
        if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val ctl = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        // 0 → the control's floor (silence); 1 → 0 dB (unity). Clamped to the control's range.
        val targetDb = if (linear <= 0f) ctl.minimum else 20.0f * log10(linear)
        ctl.value = targetDb.coerceIn(ctl.minimum, ctl.maximum)
    }

    override fun route(): OutputRoute = OutputRoute(
        engine = OutputEngine.JavaSoundFallback,
        deviceRateHz = sourceFormat.sampleRateHz,
        deviceFormat = "${sourceFormat.bitDepth}-bit PCM",
        sourceRateHz = sourceFormat.sampleRateHz,
        sourceBitDepth = sourceFormat.bitDepth,
    )

    override fun interrupt(discard: Boolean) {
        // SourceDataLine.stop()/flush() are thread-safe and make a blocked write() return.
        runCatching { line.stop() }
        if (discard) runCatching { line.flush() }
    }

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.close() }
    }

    companion object {
        const val BUFFER_MS = 100

        fun open(
            format: DecodedAudioFormat,
            provider: (AudioFormat) -> SourceDataLine = ::realSourceDataLine,
        ): JavaSoundOutputLine {
            val af = AudioFormat(format.sampleRateHz.toFloat(), format.bitDepth, format.channels, true, false)
            val bytesPerSecond = format.sampleRateHz * format.channels * (format.bitDepth / 8)
            val line = provider(af)
            line.open(af, (bytesPerSecond * BUFFER_MS / 1000).coerceAtLeast(4096))
            return JavaSoundOutputLine(line, format)
        }

        private fun realSourceDataLine(format: AudioFormat): SourceDataLine =
            AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
    }
}
