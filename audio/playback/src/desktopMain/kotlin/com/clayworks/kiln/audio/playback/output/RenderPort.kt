// RenderPort: the device side of WasapiOutputLine (#37). WasapiClient implements it over COM,
// and the unit tests implement it with a fake. That lets the write/interrupt/flush logic be
// tested without an audio device. All methods are called on the thread that opened the port.

package com.clayworks.kiln.audio.playback.output

internal data class MixFormat(val sampleRateHz: Int, val bitsPerSample: Int, val isFloat: Boolean)

internal interface RenderPort : AutoCloseable {
    /** Endpoint buffer size, in frames of the client (source) format. */
    val bufferFrames: Int

    /** The stream's (client-side) sample rate. Padding and buffer frames are counted in it. */
    val clientRateHz: Int

    /** The engine's shared-mode mix format (what Windows actually runs the device at). */
    val mix: MixFormat

    fun paddingFrames(): Int
    fun writeFrames(src: ByteArray, off: Int, frames: Int)
    fun start()
    fun stop()
    fun reset()
    fun setVolume(level: Float)
    override fun close()
}
