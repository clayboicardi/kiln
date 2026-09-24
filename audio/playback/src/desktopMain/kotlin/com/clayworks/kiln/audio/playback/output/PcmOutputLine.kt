// PcmOutputLine: the only thing the desktop player actor writes audio into (#37). Kiln owns
// this contract instead of javax.sound's SourceDataLine, so the engine behind it can change
// (WASAPI shared now, Java Sound as the fallback, exclusive mode later) without touching the actor.
//
// Threading: every member except interrupt() is called ONLY from the actor thread. interrupt()
// is the #28 write-interrupt seam and may be called from any thread.
//
// Byte-rate math lives here, not in the actor. The actor writes source-format bytes and only
// ever sees durations, so a line whose device layout differs from the source (e.g. a 24-in-32
// repack) can't mis-time the EOF tail.

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.playback.OutputRoute

internal interface PcmOutputLine : AutoCloseable {
    /** What the actor writes: decoded PCM, after the DSP chain. */
    val sourceFormat: DecodedAudioFormat

    /** Device-side buffer duration. The actor caps the EOF tail at twice this. */
    val bufferNanos: Long

    val isRunning: Boolean

    fun start()

    /** Stop playback, KEEPING buffered audio (gapless pause/resume). Actor thread only. */
    fun stop()

    /** Discard buffered audio. Keeps the running/stopped state. Actor thread only. */
    fun flush()

    /** Write whole frames of [sourceFormat] PCM. Blocks until written or interrupted; returns bytes written. */
    fun write(bytes: ByteArray, off: Int, len: Int): Int

    /** Duration of audio written but not yet played. */
    fun queuedNanos(): Long

    /** 0.0..1.0; implementations clamp. */
    fun setVolume(linear: Float)

    /** The negotiated output, for PlaybackDiagnostics. */
    fun route(): OutputRoute

    /**
     * Cross-thread: make a blocked [write] return promptly. [discard] = the caller is changing
     * tracks or tearing down (buffered audio may be dropped); false = pause. The actor follows
     * up with its own on-actor calls (stop/flush/start/close), which stay authoritative.
     */
    fun interrupt(discard: Boolean)

    /** Idempotent. */
    override fun close()
}

internal fun interface PcmOutputLineFactory {
    fun open(format: DecodedAudioFormat): PcmOutputLine
}

internal fun framesToNanos(frames: Long, sampleRateHz: Int): Long = frames * 1_000_000_000L / sampleRateHz
