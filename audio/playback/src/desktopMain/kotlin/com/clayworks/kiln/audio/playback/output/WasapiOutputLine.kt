// WasapiOutputLine: PcmOutputLine over a WASAPI shared-mode stream (#37). Push model: write()
// copies into the endpoint buffer and parks briefly while it's full. There are no event handles
// and no native callbacks into the JVM, and the 200 ms buffer absorbs GC pauses.
//
// Threading: everything except interrupt() runs on the thread that opened the line (the actor).
// interrupt() only sets a flag and unparks the writer. It makes no COM calls; the actor follows
// up with stop()/flush()/close() itself. While interrupted, write() returns at once with the
// bytes written so far, and start()/flush() re-arm it. Unlike Java Sound, which returns at once
// on ANY stopped line, a stopped-but-not-interrupted line with a full buffer blocks until
// interrupt() (see PcmOutputLine.write).

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.OutputEngine
import com.clayworks.kiln.audio.playback.OutputRoute
import com.clayworks.kiln.audio.playback.nativeio.wasapi.WasapiClient
import com.clayworks.kiln.audio.playback.nativeio.wasapi.WaveFormatExtensible
import java.util.concurrent.locks.LockSupport

internal class WasapiOutputLine(
    private val port: RenderPort,
    override val sourceFormat: DecodedAudioFormat,
) : PcmOutputLine {

    @Volatile private var interrupted = false
    @Volatile private var writer: Thread? = null
    private var running = false // actor thread only

    private val srcFrameBytes = sourceFormat.channels * (sourceFormat.bitDepth / 8)

    override val bufferNanos: Long = framesToNanos(port.bufferFrames.toLong(), port.clientRateHz)
    override val isRunning: Boolean get() = running

    override fun start() {
        interrupted = false
        if (!running) {
            port.start()
            running = true
        }
    }

    override fun stop() {
        if (running) {
            // Cleared BEFORE the device call (final review M1). A Stop that throws means the device
            // is dead either way. Left true, the next start() would skip port.start() and play silence
            // instead of surfacing the device error.
            running = false
            port.stop()
        }
    }

    override fun flush() {
        interrupted = false
        // IAudioClient.Reset requires a stopped stream. `running` tracks each step, so a failed
        // reset()/start() leaves the line honestly reporting stopped.
        if (running) {
            port.stop()
            running = false
            port.reset()
            port.start()
            running = true
        } else {
            port.reset()
        }
    }

    override fun write(bytes: ByteArray, off: Int, len: Int): Int {
        require(len % srcFrameBytes == 0) { "len $len is not a whole number of $srcFrameBytes-byte frames" }
        writer = Thread.currentThread()
        try {
            var written = 0
            while (written < len && !interrupted) {
                val free = port.bufferFrames - port.paddingFrames()
                if (free <= 0) {
                    LockSupport.parkNanos(this, FULL_BUFFER_PARK_NANOS)
                    continue
                }
                val frames = minOf(free, (len - written) / srcFrameBytes)
                port.writeFrames(bytes, off + written, frames)
                written += frames * srcFrameBytes
            }
            return written
        } finally {
            writer = null
        }
    }

    override fun queuedNanos(): Long = framesToNanos(port.paddingFrames().toLong(), port.clientRateHz)

    override fun setVolume(linear: Float) = port.setVolume(linear.coerceIn(0f, 1f))

    override fun route(): OutputRoute = OutputRoute(
        engine = OutputEngine.WasapiShared,
        deviceRateHz = port.mix.sampleRateHz,
        deviceFormat = "${port.mix.bitsPerSample}-bit ${if (port.mix.isFloat) "float" else "PCM"} mix",
        sourceRateHz = sourceFormat.sampleRateHz,
        sourceBitDepth = sourceFormat.bitDepth,
    )

    override fun interrupt(discard: Boolean) {
        // discard is irrelevant here: the actor always follows up with flush() or close().
        interrupted = true
        writer?.let(LockSupport::unpark)
    }

    override fun close() = port.close()

    companion object {
        const val BUFFER_MS = 200
        private const val FULL_BUFFER_PARK_NANOS = 5_000_000L

        fun open(format: DecodedAudioFormat): WasapiOutputLine {
            // The format below declares integer PCM, so float samples would play as noise. Checked
            // before any COM call.
            if (format.sampleFormat == SampleFormat.PCM_F32_LE) {
                throw OutputLineException.FormatRejected(format, "float PCM sources are not supported by this line")
            }
            val wfx = WaveFormatExtensible.encode(
                sampleRateHz = format.sampleRateHz,
                channels = format.channels,
                containerBits = format.bitDepth,
                validBits = format.bitDepth,
            )
            return WasapiOutputLine(WasapiClient.open(format, wfx, BUFFER_MS), format)
        }
    }
}
