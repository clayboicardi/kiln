# Desktop WASAPI Shared-Mode Output (#37, slice 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every FLAC in the library (16/24/32-bit, 44.1–192 kHz) play on Windows desktop at full precision by writing to a WASAPI shared-mode stream instead of Java Sound, with honest, visible failure handling and an on-screen output-path readout.

**Architecture:** The desktop player's actor stops writing to `javax.sound.sampled.SourceDataLine` and writes to a Kiln-owned `PcmOutputLine`. The production factory opens a pure-Kotlin `WasapiOutputLine` (JNA/COM, shared mode, `AUTOCONVERTPCM`) and falls back to a `JavaSoundOutputLine` only if WASAPI can't initialize. Output facts and skip notices reach the UI through a `PlaybackDiagnostics` side-channel, so `PlatformPlayer` is unchanged.

**Tech Stack:** Kotlin (KMP, desktop JVM 21), kotlinx.coroutines, JNA 5.17.0 + `jna-platform` (already on `:audio:playback`'s classpath via `libs.bundles.jna`), Compose Multiplatform, kotlin.test/JUnit4, kermit.

**Spec:** [`docs/superpowers/specs/2026-09-23-issue-37-wasapi-shared-design.md`](../specs/2026-09-23-issue-37-wasapi-shared-design.md). Decision record: [`docs/decisions/2026-09-23-wasapi-shared-pull-forward.md`](../../decisions/2026-09-23-wasapi-shared-pull-forward.md). Read both before Task 1.

**Two refinements found while planning** (applied to the spec in the same commit as this plan):
1. **Cross-thread interrupt is an explicit method.** The spec said `stop()`/`flush()` "must ALSO be safe from any thread". The plan instead adds `PcmOutputLine.interrupt(discard: Boolean)`, the only cross-thread member, and `stop()`/`flush()` become actor-only. This avoids thread-identity guessing inside the line, which would misfire under the tests' `Unconfined` dispatchers.
2. **`PlaybackNotice.Skipped(title, cause: PlayerError)`** carries the error, not a pre-rendered `reason: String`. The wording lives in `:ui:components` (`noticeMessage`), next to `playerErrorMessage`.

## Global Constraints

- **Gradle:** always prefix `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot';` (PowerShell) or `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"` (Git Bash). Never pipe Gradle through `Select-Object`; redirect to a file (`*> "$env:TEMP\x.log"`).
- **Canonical build for this plan:** `.\gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`. Every task ends green on it.
- **No new dependencies.** JNA/jna-platform 5.17.0 are already on the classpath.
- **`PlatformPlayer` gets no new members** (Phase 2b plan §12 STOP signal). Output facts go through `PlaybackDiagnostics`.
- **No `if (x is SomeSource/SomePlayer)` branching** (Source Protocol). The desktop graph binds `DesktopPlayer` to both `PlatformPlayer` and `PlaybackDiagnostics` by type.
- **All WASAPI COM calls happen on the thread that opened the line** (the actor's `kiln-audio-out` thread in production). The only cross-thread member of `PcmOutputLine` is `interrupt()`, and it makes no COM calls.
- **The actor only sees durations** from a line (`bufferNanos`, `queuedNanos()`). It never computes output bytes/sec from the source format.
- **Device-backed tests** must `Assume` an available audio endpoint, so they skip on CI's headless Windows runner and run on Cortex.
- **One commit per task**, message ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commit bodies must not contain `close #N`, `fixes #N`, or `resolves #N` (GitHub auto-closes).
- `MAX_CONSECUTIVE_FAILURES = 3`, the WASAPI buffer is `200 ms`, and the Java Sound buffer stays `100 ms`.

## Review Focus

The five inputs most likely to bite a real listener that the spec implies but doesn't spell out. Each has its test in the owning task.

1. **The output device disappears mid-track** (a USB DAC is unplugged, or the Windows default device is switched). Playback must stop with a visible "Output device unavailable", the queue position must stay put (no skip storm), and the next explicit play must use the new default device. *Task 2, "device problem mid-stream" test; Task 6, open re-resolves the endpoint on every open.*
2. **Tracks shorter than the output buffer** (≤ 200 ms stingers and hidden-track gaps). EOF must wait only for the audio actually queued, capped at twice the buffer, and must not clip or hang. *Task 1, the two EOF-tail tests.*
3. **Sample rates the device doesn't run natively** (88.2 and 176.4 kHz on a 48 kHz mix). They must still open, via Windows' SRC, and the UI must say "resampled". *Task 5 probe matrix includes 88.2/176.4; Task 8 route-format test.*
4. **Mono files.** The format must carry the `SPEAKER_FRONT_CENTER` mask and open. *Task 4 byte-layout test; Task 5 probe includes mono.*
5. **Skip-spam** (holding Next through a playlist) creates and destroys a WASAPI client per track. This must not leak COM objects or start failing after N tracks. *Task 6, device test that opens and closes 50 lines.*

---

## File map

**Create (desktopMain, `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/`):**

| File | Responsibility |
|---|---|
| `output/PcmOutputLine.kt` | The `PcmOutputLine` contract, its factory, `framesToNanos` |
| `output/OutputLineException.kt` | The typed open/write failures `FormatRejected` and `DeviceProblem` |
| `output/JavaSoundOutputLine.kt` | Fallback line over `SourceDataLine` (today's behavior, moved) |
| `output/RenderPort.kt` | The device-side seam `WasapiOutputLine` drives, plus `MixFormat` |
| `output/WasapiOutputLine.kt` | Write/interrupt/flush logic over a `RenderPort` (device-free, unit-tested) |
| `output/DesktopOutputLineFactory.kt` | Chooses WASAPI or the Java Sound fallback, once per session |
| `nativeio/wasapi/WaveFormatExtensible.kt` | Pure byte encoder for `WAVEFORMATEXTENSIBLE` and decoder for the mix format |
| `nativeio/wasapi/HResults.kt` | Named HRESULTs and HRESULT → `OutputLineException` mapping (pure) |
| `nativeio/wasapi/ComBinding.kt` | GUIDs, vtable slots, flags, `ComPtr`, `ComInit` (JNA) |
| `nativeio/wasapi/WasapiClient.kt` | The COM `RenderPort`: enumerator → device → `IAudioClient` → render client + volume |
| `DesktopPlayer.kt` | `interface DesktopPlayer : PlatformPlayer, PlaybackDiagnostics` |

**Create (commonMain):**
- `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlaybackDiagnostics.kt`: `OutputEngine`, `OutputRoute`, `PlaybackNotice`, `PlaybackDiagnostics`, `NoPlaybackDiagnostics`
- `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/playback/PlaybackMessages.kt`: `playerErrorMessage`, `noticeMessage`, `formatOutputRoute`

**Modify:**
- `audio/playback/src/desktopMain/.../JavaSoundPlayerImpl.kt`: the actor
- `audio/playback/src/commonMain/.../PlayerState.kt`: `PlayerError.OutputFormatUnsupported`
- `audio/playback/src/desktopTest/.../PlayerTestDoubles.kt`: `FakeLine` becomes `FakeOutputLine`; `FakeDecoder` rejects reopening a closed stream
- `audio/playback/src/desktopTest/.../JavaSoundPlayerActorTest.kt`: uses `FakeOutputLine`, plus the EOF-tail and error tests
- `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt`, `.../desktop/Main.kt`
- `ui/components/src/commonMain/.../specsheet/LocalLibraryStats.kt`, `.../nowplaying/NowPlayingContent.kt`, `.../nowplaying/NowPlayingTab.kt`, `.../specsheet/SpecSheetContent.kt`, `.../specsheet/SpecSheetScreen.kt`
- `CLAUDE.md`, and the spec (the refinements above)

**Create (tests):**
- `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/`: `output/JavaSoundOutputLineTest.kt`, `output/WasapiOutputLineTest.kt`, `output/DesktopOutputLineFactoryTest.kt`, `JavaSoundPlayerSkipTest.kt`, `nativeio/wasapi/WaveFormatExtensibleTest.kt`, `nativeio/wasapi/HResultsTest.kt`, `nativeio/wasapi/WasapiFormatProbeTest.kt`, `nativeio/wasapi/WasapiDeviceTest.kt`
- `ui/components/src/commonTest/kotlin/com/clayworks/kiln/ui/components/playback/PlaybackMessagesTest.kt`
- `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingDiagnosticsTest.kt`

---

### Task 1: `PcmOutputLine` seam, `JavaSoundOutputLine`, and the actor migration (pure refactor)

Behavior-preserving. Every existing test stays green. The two new EOF-tail tests pin the duration-based tail.

**Files:**
- Create: `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlaybackDiagnostics.kt` (route types only; Task 3 adds the rest)
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/output/PcmOutputLine.kt`
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/output/JavaSoundOutputLine.kt`
- Modify: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt`
- Modify: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/PlayerTestDoubles.kt`
- Modify: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerActorTest.kt`
- Test: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/output/JavaSoundOutputLineTest.kt`

**Interfaces:**
- Produces: `internal interface PcmOutputLine` (members below), `internal fun interface PcmOutputLineFactory { fun open(format: DecodedAudioFormat): PcmOutputLine }`, `internal fun framesToNanos(frames: Long, sampleRateHz: Int): Long`, `internal class JavaSoundOutputLine(line: SourceDataLine, sourceFormat: DecodedAudioFormat)` with `companion fun open(format, provider = real): JavaSoundOutputLine` and `const val BUFFER_MS = 100`, `enum class OutputEngine { WasapiShared, JavaSoundFallback }`, `data class OutputRoute(engine, deviceRateHz: Int?, deviceFormat: String, sourceRateHz: Int, sourceBitDepth: Int)` with `val resampled: Boolean`, and the test double `internal class FakeOutputLine`.
- `JavaSoundPlayerImpl`'s constructor param becomes `lineFactory: PcmOutputLineFactory = PcmOutputLineFactory { JavaSoundOutputLine.open(it) }`.

- [ ] **Step 1: Create the route types (commonMain)**

`audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlaybackDiagnostics.kt`:

```kotlin
// Output-path facts a player publishes through the PlaybackDiagnostics side-channel (#37).
// Kept off PlatformPlayer on purpose (Engine-Swap-Shaped Boundary; Phase 2b plan §12).

package com.clayworks.kiln.audio.playback

enum class OutputEngine { WasapiShared, JavaSoundFallback }

/** The negotiated output for the current track, as opposed to the file's own format. */
data class OutputRoute(
    val engine: OutputEngine,
    val deviceRateHz: Int?,
    val deviceFormat: String,
    val sourceRateHz: Int,
    val sourceBitDepth: Int,
) {
    val resampled: Boolean get() = deviceRateHz != null && deviceRateHz != sourceRateHz
}
```

- [ ] **Step 2: Create the `PcmOutputLine` contract**

`audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/output/PcmOutputLine.kt`:

```kotlin
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
```

- [ ] **Step 3: Write the failing `JavaSoundOutputLine` tests**

`audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/output/JavaSoundOutputLineTest.kt`:

```kotlin
// JavaSoundOutputLine: the Java Sound fallback line. Durations come from the line's own frame
// size and rate; interrupt() keeps the pre-#37 stop()+flush() seam.

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaSoundOutputLineTest {

    private val fmt = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE)

    @Test
    fun `open sizes the line buffer to 100 ms of the source format`() {
        val sdl = FakeSourceDataLine()
        JavaSoundOutputLine.open(fmt) { sdl }
        assertEquals(17_640, sdl.openedBufferSize) // 44100 * 2 ch * 2 B / 10
    }

    @Test
    fun `bufferNanos and queuedNanos are durations in the line's own frames`() {
        val sdl = FakeSourceDataLine(bufferSize = 17_640, available = 8_820)
        val line = JavaSoundOutputLine(sdl, fmt)
        assertEquals(100_000_000L, line.bufferNanos)
        assertEquals(50_000_000L, line.queuedNanos()) // 8820 queued bytes = 2205 frames = 50 ms
    }

    @Test
    fun `interrupt with discard stops and flushes, without discard only stops`() {
        val sdl = FakeSourceDataLine()
        val line = JavaSoundOutputLine(sdl, fmt)
        line.interrupt(discard = false)
        assertEquals(listOf("stop"), sdl.calls)
        line.interrupt(discard = true)
        assertEquals(listOf("stop", "stop", "flush"), sdl.calls)
    }

    @Test
    fun `setVolume without a gain control is a no-op`() {
        JavaSoundOutputLine(FakeSourceDataLine(), fmt).setVolume(0.5f) // must not throw
    }

    /** No-hardware SourceDataLine. Records calls; no gain control, so setVolume short-circuits. */
    private class FakeSourceDataLine(
        private val bufferSize: Int = 4096,
        private val available: Int = 0,
    ) : SourceDataLine {
        val calls = mutableListOf<String>()
        var openedBufferSize: Int = -1
        override fun open(format: AudioFormat, bufferSize: Int) { openedBufferSize = bufferSize }
        override fun open(format: AudioFormat) = Unit
        override fun write(b: ByteArray, off: Int, len: Int): Int = len
        override fun open() = Unit
        override fun drain() = Unit
        override fun flush() { calls += "flush" }
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun available(): Int = available
        override fun isRunning(): Boolean = false
        override fun isActive(): Boolean = false
        override fun getFormat(): AudioFormat = AudioFormat(44_100f, 16, 2, true, false)
        override fun getBufferSize(): Int = bufferSize
        override fun getFramePosition(): Int = 0
        override fun getLongFramePosition(): Long = 0L
        override fun getMicrosecondPosition(): Long = 0L
        override fun getLevel(): Float = -1.0f
        override fun close() { calls += "close" }
        override fun isOpen(): Boolean = true
        override fun getLineInfo(): Line.Info = Line.Info(SourceDataLine::class.java)
        override fun getControls(): Array<Control> = arrayOf()
        override fun isControlSupported(control: Control.Type): Boolean = false
        override fun getControl(control: Control.Type): Control = throw IllegalArgumentException("no $control")
        override fun addLineListener(listener: LineListener) = Unit
        override fun removeLineListener(listener: LineListener) = Unit
    }
}
```

- [ ] **Step 4: Run it and confirm it fails to compile**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'; .\gradlew :audio:playback:desktopTest --tests "*JavaSoundOutputLineTest" *> "$env:TEMP\t1.log"; Select-String -Path "$env:TEMP\t1.log" -Pattern '^e: |FAILED|BUILD'`
Expected: `e: ... Unresolved reference 'JavaSoundOutputLine'`.

- [ ] **Step 5: Implement `JavaSoundOutputLine`**

`audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/output/JavaSoundOutputLine.kt`:

```kotlin
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
```

- [ ] **Step 6: Run the line tests and confirm they pass**

Run: `.\gradlew :audio:playback:desktopTest --tests "*JavaSoundOutputLineTest" *> "$env:TEMP\t1.log"`
Expected: `BUILD SUCCESSFUL`, 4 tests.

- [ ] **Step 7: Replace `FakeLine` with `FakeOutputLine` in the test doubles**

In `PlayerTestDoubles.kt`, delete the whole `FakeLine` class and the `javax.sound.sampled.*` imports. Update the header comment's "no-hardware SourceDataLine" to "no-hardware PcmOutputLine", and add:

```kotlin
/**
 * No-hardware [PcmOutputLine]. Records writes, start/stop, interrupts, volume, and closes.
 * [queued] and [bufferNanos] drive the actor's EOF-tail park. [writeFailure], when set, is
 * thrown from write() (a device dying mid-stream).
 */
internal class FakeOutputLine(
    override val sourceFormat: DecodedAudioFormat = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE),
    override val bufferNanos: Long = 100_000_000L,
    @Volatile var queued: Long = 0L,
) : PcmOutputLine {
    @Volatile var running: Boolean = false
        private set
    @Volatile var closedCount: Int = 0
        private set
    @Volatile var interrupts: Int = 0
        private set
    @Volatile var lastVolume: Float? = null
        private set
    @Volatile var writeFailure: Exception? = null
    val written = ArrayList<Byte>()

    override val isRunning: Boolean get() = running
    override fun start() { running = true }
    override fun stop() { running = false }
    override fun flush() = Unit
    override fun write(bytes: ByteArray, off: Int, len: Int): Int {
        writeFailure?.let { throw it }
        for (i in off until off + len) written.add(bytes[i])
        return len
    }
    override fun queuedNanos(): Long = queued
    override fun setVolume(linear: Float) { lastVolume = linear }
    override fun route(): OutputRoute = OutputRoute(
        engine = OutputEngine.WasapiShared,
        deviceRateHz = 48_000,
        deviceFormat = "32-bit float mix",
        sourceRateHz = sourceFormat.sampleRateHz,
        sourceBitDepth = sourceFormat.bitDepth,
    )
    override fun interrupt(discard: Boolean) { interrupts++ }
    override fun close() { closedCount++; running = false }
}
```

Add the imports `com.clayworks.kiln.audio.playback.output.PcmOutputLine`. `OutputEngine` and `OutputRoute` are in the same package as the test file, so they need no import.

- [ ] **Step 8: Point the actor tests at `FakeOutputLine` and add the EOF-tail tests**

In `JavaSoundPlayerActorTest.kt`, change `newPlayer`'s parameter to `line: FakeOutputLine = FakeOutputLine()` and keep `lineFactory = { line }` (SAM-converts to `PcmOutputLineFactory`). Append:

```kotlin
    @Test
    fun `EOF tail waits for the line's queued audio before advancing`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val line = FakeOutputLine(bufferNanos = 100_000_000L, queued = 80_000_000L) // 80 ms queued
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to FakeDecodedStream(fmt)), line)
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        val t0 = System.nanoTime()
        sA.signalEof()
        awaitStreamEvent { player.queue.first { it.currentIndex == 1 } }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(elapsedMs >= 70, "EOF must play out the ~80 ms still queued; advanced after $elapsedMs ms")
    }

    @Test
    fun `EOF tail is capped at twice the line buffer`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val line = FakeOutputLine(bufferNanos = 50_000_000L, queued = 5_000_000_000L) // claims 5 s queued
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to FakeDecodedStream(fmt)), line)
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        val t0 = System.nanoTime()
        sA.signalEof()
        awaitStreamEvent { player.queue.first { it.currentIndex == 1 } }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(elapsedMs < 1_000, "tail must cap at 2 × 50 ms, not wait 5 s; took $elapsedMs ms")
    }
```

- [ ] **Step 9: Run the actor tests and confirm they fail to compile**

Run: `.\gradlew :audio:playback:desktopTest --tests "*JavaSoundPlayer*" *> "$env:TEMP\t1.log"`
Expected: `e:` type mismatch, because `lineFactory` still expects `(AudioFormat) -> SourceDataLine`.

- [ ] **Step 10: Migrate the actor to `PcmOutputLine`**

Edits in `JavaSoundPlayerImpl.kt`, in file order:

1. Imports: delete `javax.sound.sampled.AudioFormat`, `AudioSystem`, `DataLine`, `FloatControl`, `SourceDataLine`. Add `com.clayworks.kiln.audio.playback.output.JavaSoundOutputLine`, `com.clayworks.kiln.audio.playback.output.PcmOutputLine`, and `com.clayworks.kiln.audio.playback.output.PcmOutputLineFactory`.
2. Delete `private const val BUFFER_MS = 100` and the whole `realLineFactory` val.
3. Constructor param: `private val lineFactory: PcmOutputLineFactory = PcmOutputLineFactory { JavaSoundOutputLine.open(it) },`
4. `@Volatile private var line: SourceDataLine? = null` → `@Volatile private var line: PcmOutputLine? = null`
5. `interruptAndFlushLine()` body → `runCatching { line?.interrupt(discard = true) }`. Update its comment: "Write-interrupt seam for track-changing / teardown ops: `interrupt(discard = true)` makes a blocked write return and lets the line drop buffered audio. `pause()` uses `discard = false` to keep the buffer for a gapless resume."
6. `pause()` → `override suspend fun pause() { runCatching { line?.interrupt(discard = false) }; commands.trySend(PlayerCommand.Pause) }`
7. In `openAndStart`, replace the `val format = audioFormatOf(stream)` / `lineFactory(format).also { it.open(format, bufferBytesOf(stream)) }` block with:

```kotlin
        val sourceLine = try {
            lineFactory.open(stream.format)
        } catch (e: Exception) {
            withContext(decodeDispatcher) { runCatching { stream.close() } }
            currentStream = null
            currentPlayable = null
            mode = Mode.IDLE
            log.e(e) { "Could not open output line for ${stream.format}" }
            _state.value = PlayerState.Error(PlayerError.DeviceUnavailable(e.message ?: "audio line open failed"))
            return
        }
```

   In the `start()` failure log, change `"SourceDataLine.start() failed for $format"` to `"Output line start() failed for ${stream.format}"`.
8. In `advanceOnEof`, replace the `val l = line` … `parkNanos` block with:

```kotlin
        val l = line
        if (l != null && l.isRunning) {
            // Durations come from the line (it owns output byte-rate math, #37). Capped at twice
            // the line buffer, so a pause landing mid-tail can only end the tail early.
            val tailNanos = l.queuedNanos().coerceAtMost(2 * l.bufferNanos)
            if (tailNanos > 0L) java.util.concurrent.locks.LockSupport.parkNanos(tailNanos)
        }
```

   Keep the long comment above it, but replace "(<=BUFFER_MS)" with "(the line's queued audio)".
9. In `resumePlayback`, change the log `"SourceDataLine.start() failed on resume"` to `"Output line start() failed on resume"`.
10. In `teardownActivePlayback`, change `line?.let { l -> runCatching { l.stop() }; runCatching { l.close() } }` to `line?.let { l -> runCatching { l.close() } }`. `close()` stops first.
11. Replace `applyGain()` with:

```kotlin
    private fun applyGain() {
        val l = line ?: return
        val v = _volume.value
        l.setVolume(if (v.muted) 0f else v.linear)
    }
```

12. Delete `audioFormatOf(...)` and `bufferBytesOf(...)`.
13. File header comment: replace "SourceDataLine" with "output line (PcmOutputLine)" in the concurrency paragraph, and replace the write-interrupt paragraph with: "The ONLY cross-thread touch of the line is `PcmOutputLine.interrupt()` in pause/stop/skip/release (the write-interrupt seam). It unblocks a stuck write() so the actor can make progress; the actor remains the authority on all subsequent line state."

- [ ] **Step 11: Run all `:audio:playback` desktop tests**

Run: `.\gradlew :audio:playback:desktopTest *> "$env:TEMP\t1.log"; Select-String -Path "$env:TEMP\t1.log" -Pattern 'FAILED|BUILD|tests completed'`
Expected: `BUILD SUCCESSFUL`. All pre-existing actor/impl tests pass, plus the 2 EOF-tail tests and 4 line tests.

- [ ] **Step 12: Run the canonical build**

Run the Global Constraints canonical build to a log file. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 13: Commit**

```bash
git add audio/playback/src
git commit -F - <<'EOF'
refactor(player): PcmOutputLine seam; Java Sound behind JavaSoundOutputLine (#37 task 1)

The desktop actor now writes to a Kiln-owned PcmOutputLine instead of
javax.sound's SourceDataLine. JavaSoundOutputLine carries the existing
behavior unchanged (100 ms buffer, MASTER_GAIN volume, stop()+flush()
interrupt). The line owns output byte-rate math: the actor's EOF tail
now parks for line.queuedNanos(), capped at 2 x line.bufferNanos,
instead of computing bytes/sec from the source format.

Tests: JavaSoundOutputLineTest (4); the actor tests use FakeOutputLine;
two EOF-tail tests (waits for queued audio; capped at twice the buffer).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Honest error types, position reset, device failures mid-stream

**Files:**
- Modify: `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlayerState.kt`
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/output/OutputLineException.kt`
- Modify: `audio/playback/src/desktopMain/.../output/JavaSoundOutputLine.kt` (`open` maps exceptions)
- Modify: `audio/playback/src/desktopMain/.../JavaSoundPlayerImpl.kt`
- Test: `audio/playback/src/desktopTest/.../JavaSoundPlayerActorTest.kt`, `.../output/JavaSoundOutputLineTest.kt`

**Interfaces:**
- Consumes: `PcmOutputLine`, `PcmOutputLineFactory`, `FakeOutputLine` (Task 1).
- Produces: `PlayerError.OutputFormatUnsupported(sampleRateHz: Int, bitDepth: Int, channels: Int, reason: String)`, `internal sealed class OutputLineException` with `FormatRejected(format: DecodedAudioFormat, detail: String, cause: Throwable? = null)` and `DeviceProblem(detail: String, cause: Throwable? = null)`. Also the rule that position resets inside `teardownActivePlayback()`.

- [ ] **Step 1: Write the failing actor tests**

Append to `JavaSoundPlayerActorTest.kt` (add the imports `com.clayworks.kiln.audio.dsp.AudioFrame`, `com.clayworks.kiln.audio.playback.output.OutputLineException`, and `com.clayworks.kiln.audio.playback.output.PcmOutputLineFactory`):

```kotlin
    private val fmt24 = DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE)

    private fun frameAt(ms: Long) = AudioFrame(bytes = ByteArray(4), byteCount = 4, sampleCount = 1, timestampMs = ms)

    private fun playerWith(
        ids: List<String>,
        streams: Map<String, FakeDecodedStream>,
        factory: PcmOutputLineFactory,
    ) = JavaSoundPlayerImpl(
        audioDispatcher = Dispatchers.Unconfined,
        decodeDispatcher = Dispatchers.Unconfined,
        decoder = FakeDecoder(streams),
        source = ResolvingSource(ids.toSet()),
        settings = StubSettings(),
        rgProcessor = ReplayGainProcessor(),
        lineFactory = factory,
    )

    /** Accepts 16-bit (returns [line]); rejects 24-bit the way WASAPI/Java Sound do. */
    private fun rejecting24(line: FakeOutputLine) = PcmOutputLineFactory { f ->
        if (f.bitDepth == 24) throw OutputLineException.FormatRejected(f, "24-bit rejected") else line
    }

    @Test
    fun `failed decoder open resets the position to zero`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA)) // no stream for b → decoder fails
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        sA.emit(frameAt(5_000))
        awaitStreamEvent { player.positionMs.first { it == 5_000L } }
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 1, autoPlay = true)
        player.awaitDrained()
        assertTrue(player.state.value is PlayerState.Error)
        assertEquals(0L, player.positionMs.value, "a failed open must not keep the previous track's position")
    }

    @Test
    fun `failed line open resets the position and reports OutputFormatUnsupported`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val player = playerWith(
            listOf("a", "b"),
            mapOf("a" to sA, "b" to FakeDecodedStream(fmt24)),
            rejecting24(FakeOutputLine()),
        )
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        sA.emit(frameAt(5_000))
        awaitStreamEvent { player.positionMs.first { it == 5_000L } }
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 1, autoPlay = true)
        player.awaitDrained()
        val error = (player.state.value as PlayerState.Error).cause
        assertEquals(PlayerError.OutputFormatUnsupported(44_100, 24, 2, "24-bit rejected"), error)
        assertEquals(0L, player.positionMs.value)
    }

    @Test
    fun `device problem at open reports DeviceUnavailable`() = runBlocking {
        val player = playerWith(
            listOf("a"),
            mapOf("a" to FakeDecodedStream(fmt)),
            PcmOutputLineFactory { throw OutputLineException.DeviceProblem("no audio output device") },
        )
        player.loadQueue(listOf(item("a")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        assertEquals(
            PlayerError.DeviceUnavailable("no audio output device"),
            (player.state.value as PlayerState.Error).cause,
        )
    }

    @Test
    fun `device problem mid-stream reports DeviceUnavailable and does not advance`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val line = FakeOutputLine()
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to FakeDecodedStream(fmt)), line)
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        line.writeFailure = OutputLineException.DeviceProblem("device invalidated")
        sA.emit(frameAt(1_000))
        awaitStreamEvent { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertEquals(PlayerError.DeviceUnavailable("device invalidated"), (player.state.value as PlayerState.Error).cause)
        assertEquals(0, player.queue.value.currentIndex, "a dead device must not trigger any advance")
    }
```

Append to `JavaSoundOutputLineTest.kt`:

```kotlin
    @Test
    fun `a format Java Sound can't open becomes FormatRejected`() {
        val e = kotlin.runCatching {
            JavaSoundOutputLine.open(DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE)) {
                throw IllegalArgumentException("No line matching interface SourceDataLine supporting format PCM_SIGNED 44100.0 Hz, 24 bit")
            }
        }.exceptionOrNull()
        kotlin.test.assertIs<OutputLineException.FormatRejected>(e)
    }

    @Test
    fun `an unavailable line becomes DeviceProblem`() {
        val e = kotlin.runCatching {
            JavaSoundOutputLine.open(fmt) { throw javax.sound.sampled.LineUnavailableException("in use") }
        }.exceptionOrNull()
        kotlin.test.assertIs<OutputLineException.DeviceProblem>(e)
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `.\gradlew :audio:playback:desktopTest --tests "*JavaSoundPlayerActorTest" --tests "*JavaSoundOutputLineTest" *> "$env:TEMP\t2.log"`
Expected: compile errors, because `OutputLineException` and `PlayerError.OutputFormatUnsupported` don't exist yet.

- [ ] **Step 3: Add the error types**

In `PlayerState.kt`, add inside `sealed interface PlayerError` (after `FormatUnsupported`):

```kotlin
    /** The output engine rejected this PCM layout (vs [FormatUnsupported]: the decoder rejected a codec). */
    data class OutputFormatUnsupported(
        val sampleRateHz: Int,
        val bitDepth: Int,
        val channels: Int,
        val reason: String,
    ) : PlayerError
```

`audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/output/OutputLineException.kt`:

```kotlin
// Typed output-line failures (#37). FormatRejected = the engine works but won't take this PCM
// layout (a per-track problem). DeviceProblem = no usable device (it would fail every track).

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat

internal sealed class OutputLineException(message: String, cause: Throwable?) : Exception(message, cause) {
    class FormatRejected(val format: DecodedAudioFormat, val detail: String, cause: Throwable? = null) :
        OutputLineException("output rejected $format: $detail", cause)

    class DeviceProblem(val detail: String, cause: Throwable? = null) :
        OutputLineException("output device problem: $detail", cause)
}
```

- [ ] **Step 4: Map Java Sound open failures**

In `JavaSoundOutputLine.open`, wrap the provider/open calls:

```kotlin
            val line = try {
                provider(af).also { it.open(af, (bytesPerSecond * BUFFER_MS / 1000).coerceAtLeast(4096)) }
            } catch (e: IllegalArgumentException) {
                // "No line matching interface SourceDataLine supporting format …": this layout is unsupported.
                throw OutputLineException.FormatRejected(format, e.message ?: "Java Sound has no line for this format", e)
            } catch (e: Exception) {
                throw OutputLineException.DeviceProblem(e.message ?: "Java Sound line unavailable", e)
            }
            return JavaSoundOutputLine(line, format)
```

- [ ] **Step 5: Map errors and reset the position in the actor**

In `JavaSoundPlayerImpl.kt`:

1. `openAndStart`'s line-open `catch`: replace the `_state.value = PlayerState.Error(PlayerError.DeviceUnavailable(...))` line with:

```kotlin
            _state.value = PlayerState.Error(outputError(e, stream.format))
```

   Add the helper next to `toPlayerError`:

```kotlin
    private fun outputError(e: Exception, format: com.clayworks.kiln.audio.dsp.DecodedAudioFormat): PlayerError =
        when (e) {
            is OutputLineException.FormatRejected ->
                PlayerError.OutputFormatUnsupported(format.sampleRateHz, format.bitDepth, format.channels, e.detail)
            is OutputLineException.DeviceProblem -> PlayerError.DeviceUnavailable(e.detail)
            else -> PlayerError.DeviceUnavailable(e.message ?: "audio line open failed")
        }
```

2. `onFrameResult`'s `catch`: replace `_state.value = PlayerState.Error(PlayerError.DecodeFailed(e))` with:

```kotlin
            _state.value = PlayerState.Error(
                if (e is OutputLineException.DeviceProblem) PlayerError.DeviceUnavailable(e.detail) else PlayerError.DecodeFailed(e),
            )
```

3. **Position reset moves into teardown.** In `openAndStart`, delete `lastTickedMs = 0L` and `_positionMs.value = 0L` (after `line = sourceLine`). In `teardownActivePlayback()`, after `line = null`, add:

```kotlin
        // No active track → position 0. openAndStart tears down first, so every failed open
        // (decoder, line open, line start) shows 0:00 instead of the previous track's position.
        lastTickedMs = 0L
        _positionMs.value = 0L
```

4. Import `com.clayworks.kiln.audio.playback.output.OutputLineException`.

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `.\gradlew :audio:playback:desktopTest *> "$env:TEMP\t2.log"`
Expected: `BUILD SUCCESSFUL`, with the 4 new actor tests and 2 new line tests green.

- [ ] **Step 7: Canonical build, then commit**

```bash
git add audio/playback/src
git commit -F - <<'EOF'
fix(player): honest output errors + position reset on failed opens (#37 task 2)

- New PlayerError.OutputFormatUnsupported(rate, bits, channels, reason),
  distinct from the decoder's FormatUnsupported(codec). Line-open format
  rejections map to it, and device problems map to DeviceUnavailable
  (previously every line-open failure was DeviceUnavailable).
- OutputLineException.FormatRejected / DeviceProblem; Java Sound's
  "No line matching" becomes FormatRejected.
- A device dying mid-stream is DeviceUnavailable, not DecodeFailed.
- Position resets in teardownActivePlayback, so a failed open (decoder,
  line open, or start) shows 0:00 instead of the previous track's time.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 3: `PlaybackDiagnostics`, capped skip-ahead, and skip notices

**Files:**
- Modify: `audio/playback/src/commonMain/.../PlaybackDiagnostics.kt` (add notices + interface)
- Modify: `audio/playback/src/desktopMain/.../JavaSoundPlayerImpl.kt`
- Modify: `audio/playback/src/desktopTest/.../PlayerTestDoubles.kt` (`FakeDecoder` rejects a closed stream)
- Test: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerSkipTest.kt`

**Interfaces:**
- Consumes: `OutputLineException` and `outputError` (Task 2); `PcmOutputLine.route()` (Task 1).
- Produces: `sealed interface PlaybackNotice { Skipped(title: String, cause: PlayerError); GaveUp(consecutiveFailures: Int) }`, `interface PlaybackDiagnostics { val outputRoute: StateFlow<OutputRoute?>; val notices: SharedFlow<PlaybackNotice> }`, and `object NoPlaybackDiagnostics`. `JavaSoundPlayerImpl` implements `PlaybackDiagnostics`, with the constant `MAX_CONSECUTIVE_FAILURES = 3`.

- [ ] **Step 1: Make `FakeDecoder` fail a reopen of a consumed stream**

In `PlayerTestDoubles.kt`'s `FakeDecoder.open`, after resolving `s`:

```kotlin
        // A torn-down stream can't be replayed (its frame channel is consumed). Model that as an
        // unreadable file, so RepeatMode.One / RepeatMode.All replays exercise the skip path.
        if (s.closed) return Either.Left(DecoderError.IoError(IllegalStateException("stream ${playable.itemId.value} already consumed")))
```

- [ ] **Step 2: Write the failing skip tests**

`audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerSkipTest.kt`:

```kotlin
// Capped skip-ahead (#37). Auto-advance and Next/Previous step past tracks that fail for a
// per-track reason, up to MAX_CONSECUTIVE_FAILURES opens. Device failures and explicit picks
// (LoadQueue / SkipTo) never skip. Every skip emits a PlaybackNotice.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.audio.playback.output.OutputLineException
import com.clayworks.kiln.audio.playback.output.PcmOutputLineFactory
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.settings.ThemeMode
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.BrowseScope
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.LocalSourceCapabilities
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.Playable
import com.clayworks.kiln.library.source.SearchResult
import com.clayworks.kiln.library.source.SourceCapabilities
import com.clayworks.kiln.library.source.SourceError
import com.clayworks.kiln.library.source.SourceId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaSoundPlayerSkipTest {

    private val f16 = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE)
    private val f24 = DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE)

    private class StubSettings : SettingsRepository {
        override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.System)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override val scanOnLaunch: Flow<Boolean> = flowOf(false)
        override suspend fun setScanOnLaunch(enabled: Boolean) = Unit
        override val autoScanOnFolderAdd: Flow<Boolean> = flowOf(true)
        override suspend fun setAutoScanOnFolderAdd(enabled: Boolean) = Unit
        override val scanFolders: Flow<List<String>> = flowOf(emptyList())
        override suspend fun setScanFolders(folders: List<String>) = Unit
        override val replayGainMode: Flow<ReplayGainMode> = flowOf(ReplayGainMode.Off)
        override suspend fun setReplayGainMode(mode: ReplayGainMode) = Unit
        override val replayGainPreAmpDb: Flow<Double> = flowOf(0.0)
        override suspend fun setReplayGainPreAmpDb(db: Double) = Unit
    }

    private class ResolvingSource(private val ids: Set<String>) : MusicSource {
        override val id = SourceId("test")
        override val displayName = "test"
        override val capabilities: SourceCapabilities = LocalSourceCapabilities
        override suspend fun search(query: String, limit: Int): Flow<SearchResult> = emptyFlow()
        override suspend fun browse(scope: BrowseScope): Flow<MediaItem> = emptyFlow()
        override suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> =
            if (itemId.value in ids) {
                Either.Right(
                    Playable(
                        itemId = itemId, sourceId = id, uri = "file:///${itemId.value}.flac",
                        codec = AudioCodec.FLAC, sampleRateHz = 44_100, bitDepth = 16, channels = 2,
                        bitrateKbps = null, durationMs = 0L, replayGain = null,
                    ),
                )
            } else {
                Either.Left(SourceError.ItemNotFound(itemId))
            }
    }

    private fun item(id: String) = MediaItem(
        itemId = ItemId(id), sourceId = SourceId("test"), kind = MediaItem.Kind.Track,
        title = "Track $id", subtitle = null, durationMs = null, artUri = null, metadata = emptyMap(),
    )

    /** 16-bit opens on [line]; 24-bit is rejected as a per-track format failure (or as a device failure). */
    private fun factory(line: FakeOutputLine, deviceFailureOn24: Boolean = false) = PcmOutputLineFactory { f ->
        when {
            f.bitDepth != 24 -> line
            deviceFailureOn24 -> throw OutputLineException.DeviceProblem("device in use")
            else -> throw OutputLineException.FormatRejected(f, "24-bit rejected")
        }
    }

    /** ids ending in "24" get a 24-bit stream; everything else is 16-bit. */
    private fun setup(ids: List<String>, deviceFailureOn24: Boolean = false): Pair<JavaSoundPlayerImpl, Map<String, FakeDecodedStream>> {
        val streams = ids.associateWith { FakeDecodedStream(if (it.endsWith("24")) f24 else f16) }
        val player = JavaSoundPlayerImpl(
            audioDispatcher = Dispatchers.Unconfined,
            decodeDispatcher = Dispatchers.Unconfined,
            decoder = FakeDecoder(streams),
            source = ResolvingSource(ids.toSet()),
            settings = StubSettings(),
            rgProcessor = ReplayGainProcessor(),
            lineFactory = factory(FakeOutputLine(), deviceFailureOn24),
        )
        return player to streams
    }

    private fun CoroutineScope.collectNotices(player: JavaSoundPlayerImpl): MutableList<PlaybackNotice> {
        val out = CopyOnWriteArrayList<PlaybackNotice>()
        launch(Dispatchers.Unconfined) { player.notices.collect { out += it } }
        return out
    }

    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(5_000) { block() }

    @Test
    fun `EOF skips two unplayable tracks and plays the next good one`() = runBlocking {
        val ids = listOf("a", "b24", "c24", "d")
        val (player, streams) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        streams.getValue("a").signalEof()
        within { player.queue.first { it.currentIndex == 3 } }
        player.awaitDrained()
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
        assertEquals(listOf("Track b24", "Track c24"), notices.map { (it as PlaybackNotice.Skipped).title })
        assertIs<PlayerError.OutputFormatUnsupported>((notices[0] as PlaybackNotice.Skipped).cause)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `three consecutive failures stop on the error and report GaveUp`() = runBlocking {
        val ids = listOf("a", "b24", "c24", "d24", "e")
        val (player, streams) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        streams.getValue("a").signalEof()
        within { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertEquals(3, player.queue.value.currentIndex, "stops ON the third failure (d24), never reaching e")
        assertEquals(PlaybackNotice.GaveUp(3), notices.last())
        assertEquals(2, notices.count { it is PlaybackNotice.Skipped })
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a device failure never skips`() = runBlocking {
        val ids = listOf("a", "b24", "c")
        val (player, streams) = setup(ids, deviceFailureOn24 = true)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        streams.getValue("a").signalEof()
        within { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertIs<PlayerError.DeviceUnavailable>((player.state.value as PlayerState.Error).cause)
        assertEquals(1, player.queue.value.currentIndex)
        assertTrue(notices.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `an explicit pick of an unplayable track shows its error without skipping`() = runBlocking {
        val ids = listOf("a24", "b")
        val (player, _) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        assertIs<PlayerError.OutputFormatUnsupported>((player.state.value as PlayerState.Error).cause)
        assertEquals(0, player.queue.value.currentIndex)
        assertTrue(notices.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `skipTo an unplayable track shows its error without skipping`() = runBlocking {
        val ids = listOf("a", "b", "c24", "d")
        val (player, _) = setup(ids)
        player.loadQueue(ids.map(::item), startIndex = 1, autoPlay = true)
        player.awaitDrained()
        player.skipTo(2)
        player.awaitDrained()
        assertTrue(player.state.value is PlayerState.Error)
        assertEquals(2, player.queue.value.currentIndex)
    }

    @Test
    fun `previous skips backward past an unplayable track`() = runBlocking {
        val ids = listOf("a", "b24", "c")
        val (player, _) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 2, autoPlay = true)
        player.awaitDrained()
        player.skipToPrevious()
        player.awaitDrained()
        assertEquals(0, player.queue.value.currentIndex)
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
        assertEquals(listOf("Track b24"), notices.map { (it as PlaybackNotice.Skipped).title })
        coroutineContext.cancelChildren()
    }

    @Test
    fun `RepeatMode One does not retry a track that became unplayable`() = runBlocking {
        // a plays, ends, and (RepeatOne) is reopened. Its stream is consumed, so it now fails.
        // The skip must move FORWARD (b24 fails too, then c plays), not retry a until the cap.
        val ids = listOf("a", "b24", "c")
        val (player, streams) = setup(ids)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.setRepeatMode(RepeatMode.One)
        player.awaitDrained()
        streams.getValue("a").signalEof()
        within { player.queue.first { it.currentIndex == 2 } }
        player.awaitDrained()
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
    }

    @Test
    fun `a single-track RepeatAll queue that fails does not loop`() = runBlocking {
        val ids = listOf("a")
        val (player, streams) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.setRepeatMode(RepeatMode.All)
        player.awaitDrained()
        streams.getValue("a").signalEof() // replay of a fails (consumed); stepping wraps back to a itself
        within { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertTrue(notices.isEmpty(), "no skip when stepping lands on the same track")
        coroutineContext.cancelChildren()
    }

    @Test
    fun `the output route is published on open and cleared on stop`() = runBlocking {
        val (player, _) = setup(listOf("a"))
        player.loadQueue(listOf(item("a")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        assertEquals(OutputEngine.WasapiShared, player.outputRoute.value?.engine)
        assertEquals(44_100, player.outputRoute.value?.sourceRateHz)
        player.stop()
        player.awaitDrained()
        assertEquals(null, player.outputRoute.value)
    }
}
```

Add `import kotlinx.coroutines.cancelChildren` to the imports.

- [ ] **Step 3: Run the tests and confirm they fail**

Run: `.\gradlew :audio:playback:desktopTest --tests "*JavaSoundPlayerSkipTest" *> "$env:TEMP\t3.log"`
Expected: compile errors, because `PlaybackNotice`, `notices`, and `outputRoute` are unresolved.

- [ ] **Step 4: Add the diagnostics types**

Append to `PlaybackDiagnostics.kt` (commonMain) and add the imports `kotlinx.coroutines.flow.MutableSharedFlow`, `MutableStateFlow`, `SharedFlow`, `StateFlow`:

```kotlin
/** Transient playback events worth telling the listener about (shown briefly, never persisted). */
sealed interface PlaybackNotice {
    /** [title] couldn't be played for a per-track reason, so playback moved on. */
    data class Skipped(val title: String, val cause: PlayerError) : PlaybackNotice

    /** Playback stopped after this many unplayable tracks in a row. */
    data class GaveUp(val consecutiveFailures: Int) : PlaybackNotice
}

/** Side-channel for output-path facts and notices. Not part of PlatformPlayer (plan §12). */
interface PlaybackDiagnostics {
    val outputRoute: StateFlow<OutputRoute?>
    val notices: SharedFlow<PlaybackNotice>
}

/** For platforms (Android) and previews with nothing to report. */
object NoPlaybackDiagnostics : PlaybackDiagnostics {
    override val outputRoute: StateFlow<OutputRoute?> = MutableStateFlow(null)
    override val notices: SharedFlow<PlaybackNotice> = MutableSharedFlow()
}
```

- [ ] **Step 5: Implement diagnostics and skip-ahead in the actor**

In `JavaSoundPlayerImpl.kt`:

1. Class header: `internal class JavaSoundPlayerImpl(...) : PlatformPlayer, PlaybackDiagnostics {`
2. Top-level constant next to `POSITION_TICK_MS`: `internal const val MAX_CONSECUTIVE_FAILURES = 3`
3. Fields (after `_processors`):

```kotlin
    private val _outputRoute = MutableStateFlow<OutputRoute?>(null)
    override val outputRoute: StateFlow<OutputRoute?> = _outputRoute.asStateFlow()

    private val _notices = MutableSharedFlow<PlaybackNotice>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val notices: SharedFlow<PlaybackNotice> = _notices.asSharedFlow()

    private sealed interface OpenResult {
        data object Opened : OpenResult
        data class TrackFailed(val error: PlayerError) : OpenResult
        data class DeviceFailed(val error: PlayerError) : OpenResult
    }

    private enum class Direction { FORWARD, BACKWARD }
```

   Imports: `kotlinx.coroutines.channels.BufferOverflow`, `kotlinx.coroutines.flow.MutableSharedFlow`, `kotlinx.coroutines.flow.SharedFlow`, `kotlinx.coroutines.flow.asSharedFlow`.
4. `openAndStart` returns `OpenResult`: change the signature to `private suspend fun openAndStart(playable: Playable, autoPlay: Boolean): OpenResult`. Then:
   - Decoder `Left` branch: keep the body, then `return OpenResult.TrackFailed(toPlayerError(r.value))`. Compute the error once and use it for both `_state` and the return.
   - Line-open `catch`: compute `val error = outputError(e, stream.format)`, set `_state.value = PlayerState.Error(error)`, then `return if (e is OutputLineException.FormatRejected) OpenResult.TrackFailed(error) else OpenResult.DeviceFailed(error)`.
   - `start()` failure `catch`: `val error = PlayerError.DeviceUnavailable(e.message ?: "audio line start failed")`, set the state, `return OpenResult.DeviceFailed(error)`.
   - After `line = sourceLine`, add `_outputRoute.value = sourceLine.route()`.
   - The end of the function (both autoPlay branches): `return OpenResult.Opened`.
5. In `teardownActivePlayback`, after `line = null`: `_outputRoute.value = null`.
6. Add the helpers (after `openAndStart`):

```kotlin
    /** Resolve [item] and open it. A resolve failure is a per-track failure. */
    private suspend fun resolveAndOpen(item: MediaItem): OpenResult =
        when (val r = source.getPlayable(item.itemId)) {
            is Either.Left -> {
                teardownActivePlayback()
                mode = Mode.IDLE
                val error = PlayerError.Internal("resolve ${item.itemId.value}: ${r.value}")
                _state.value = PlayerState.Error(error)
                OpenResult.TrackFailed(error)
            }
            is Either.Right -> openAndStart(r.value, autoPlay = true)
        }

    /**
     * Open the item at [startIndex]. On a per-track failure (unsupported format, decode/IO error,
     * unresolvable item), step one item in [direction] and try again, up to
     * MAX_CONSECUTIVE_FAILURES opens in total. Stepping follows queue order and RepeatMode.All
     * wrap-around but ignores RepeatMode.One, so a broken track is never retried. A device
     * failure stops at once, since it would fail every track. Only auto-advance and
     * Next/Previous use this. An explicit pick (LoadQueue / SkipTo) opens exactly that item and
     * shows its error.
     */
    private suspend fun openWithSkip(startIndex: Int, direction: Direction) {
        var index = startIndex
        var attempts = 0
        while (true) {
            _queue.value = _queue.value.copy(currentIndex = index)
            val item = _queue.value.currentItem ?: return
            attempts++
            val result = resolveAndOpen(item)
            if (result !is OpenResult.TrackFailed) return
            if (attempts >= MAX_CONSECUTIVE_FAILURES) {
                _notices.tryEmit(PlaybackNotice.GaveUp(attempts))
                return
            }
            val next = stepIndex(index, direction)
            if (next == null || next == index) return
            _notices.tryEmit(PlaybackNotice.Skipped(item.title, result.error))
            index = next
        }
    }

    /** One step through the queue for skip-ahead: RepeatMode.All wraps; RepeatMode.One is ignored. */
    private fun stepIndex(index: Int, direction: Direction): Int? {
        val q = _queue.value
        if (q.items.isEmpty()) return null
        return when (direction) {
            Direction.FORWARD -> when {
                index < q.items.lastIndex -> index + 1
                q.repeatMode == RepeatMode.All -> 0
                else -> null
            }
            Direction.BACKWARD -> when {
                index > 0 -> index - 1
                q.repeatMode == RepeatMode.All -> q.items.lastIndex
                else -> null
            }
        }
    }
```

7. `applyCommand`: change these three lines:

```kotlin
            is PlayerCommand.SkipToNext -> handleSkip(nextIndexOrNull(_queue.value), Direction.FORWARD)
            is PlayerCommand.SkipToPrevious -> handleSkip(previousIndexOrNull(_queue.value), Direction.BACKWARD)
            is PlayerCommand.SkipTo -> handleSkip(cmd.index.takeIf { it in _queue.value.items.indices }, direction = null)
```

8. Replace `handleSkip`'s signature and body after its `targetIndex == null` block:

```kotlin
    private suspend fun handleSkip(targetIndex: Int?, direction: Direction?) {
        if (targetIndex == null) {
            // (unchanged comment + body)
            if (mode == Mode.PLAYING) runCatching { line?.start() }
            return
        }
        if (direction != null) {
            openWithSkip(targetIndex, direction)
            return
        }
        // Explicit SkipTo: open exactly this item and surface its error, never skipping.
        _queue.value = _queue.value.copy(currentIndex = targetIndex)
        val item = _queue.value.currentItem ?: return
        resolveAndOpen(item)
    }
```

9. In `advanceOnEof`, replace everything after the `next == null` Idle block (the `_queue.value = …copy(currentIndex = next)` through the `when (source.getPlayable…)`) with:

```kotlin
        openWithSkip(next, Direction.FORWARD)
```

   (`handleLoadQueue` keeps calling `openAndStart(...)` directly and ignores the result: an explicit pick never skips.)

- [ ] **Step 6: Run the whole `:audio:playback` suite**

Run: `.\gradlew :audio:playback:desktopTest *> "$env:TEMP\t3.log"`
Expected: `BUILD SUCCESSFUL`. All 9 skip tests pass, and the pre-existing actor/impl tests stay green, including "decode error flips to Error and does NOT advance" (a mid-stream decode error isn't an open failure, so it never enters `openWithSkip`).

- [ ] **Step 7: Canonical build, then commit**

```bash
git add audio/playback/src
git commit -F - <<'EOF'
feat(player): capped skip-ahead + PlaybackDiagnostics side-channel (#37 task 3)

Auto-advance and Next/Previous now step past tracks that fail for a
per-track reason (unsupported output format, decode/IO error,
unresolvable), up to MAX_CONSECUTIVE_FAILURES = 3 opens. They step in the
direction of travel, following RepeatMode.All wrap-around and ignoring
RepeatMode.One. Device failures stop at once. Explicit picks (LoadQueue,
SkipTo) never skip. Each skip emits PlaybackNotice.Skipped(title, cause);
hitting the cap emits GaveUp.

JavaSoundPlayerImpl implements PlaybackDiagnostics: outputRoute
(published on open, cleared on teardown) and notices. PlatformPlayer is
unchanged.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 4: WASAPI binding: format bytes, HRESULTs, COM plumbing

**Files:**
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/output/RenderPort.kt` (`MixFormat` + `RenderPort`)
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/nativeio/wasapi/WaveFormatExtensible.kt`
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/nativeio/wasapi/HResults.kt`
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/nativeio/wasapi/ComBinding.kt`
- Test: `.../nativeio/wasapi/WaveFormatExtensibleTest.kt`, `.../nativeio/wasapi/HResultsTest.kt`

**Interfaces:**
- Consumes: `OutputLineException` (Task 2).
- Produces:
  - `internal data class MixFormat(sampleRateHz: Int, bitsPerSample: Int, isFloat: Boolean)`
  - `internal interface RenderPort : AutoCloseable { val bufferFrames: Int; val clientRateHz: Int; val mix: MixFormat; fun paddingFrames(): Int; fun writeFrames(src: ByteArray, off: Int, frames: Int); fun start(); fun stop(); fun reset(); fun setVolume(level: Float) }`
  - `internal object WaveFormatExtensible { SIZE; encode(sampleRateHz, channels, containerBits, validBits): ByteArray; decodeMix(bytes): MixFormat; channelMask(channels) }`
  - `internal object HResults { S_OK; S_FALSE; RPC_E_CHANGED_MODE; E_NOTFOUND; AUDCLNT_E_*; hex(hr); toException(hr, what, format?); check(hr, what, format? = null) }`
  - `internal class ComPtr(p: Pointer) : Unknown(p) { fun call(vtableIndex: Int, vararg args: Any?): Int }`, `internal object ComInit { fun ensure() }`, `internal object WasapiIds`, `internal object Vt`, `internal object WasapiConst`

- [ ] **Step 1: Write the failing pure tests**

`.../nativeio/wasapi/WaveFormatExtensibleTest.kt`:

```kotlin
// WAVEFORMATEXTENSIBLE is #pragma pack(1), 40 bytes, little-endian. Encoded by hand (not a JNA
// Structure) so the layout is exact. These tests pin every field.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.playback.output.MixFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WaveFormatExtensibleTest {

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    @Test
    fun `stereo 24-bit 96 kHz in a 24-bit container`() {
        val b = WaveFormatExtensible.encode(96_000, channels = 2, containerBits = 24, validBits = 24)
        assertEquals(40, b.size)
        assertEquals(0xFFFE, u16(b, 0))        // WAVE_FORMAT_EXTENSIBLE
        assertEquals(2, u16(b, 2))             // nChannels
        assertEquals(96_000, u32(b, 4))        // nSamplesPerSec
        assertEquals(576_000, u32(b, 8))       // nAvgBytesPerSec = 96000 * 6
        assertEquals(6, u16(b, 12))            // nBlockAlign
        assertEquals(24, u16(b, 14))           // wBitsPerSample (container)
        assertEquals(22, u16(b, 16))           // cbSize
        assertEquals(24, u16(b, 18))           // wValidBitsPerSample
        assertEquals(0x3, u32(b, 20))          // SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT
        assertContentEquals(WaveFormatExtensible.SUBTYPE_PCM, b.copyOfRange(24, 40))
    }

    @Test
    fun `mono 16-bit 44_1 kHz uses the front-center mask`() {
        val b = WaveFormatExtensible.encode(44_100, channels = 1, containerBits = 16, validBits = 16)
        assertEquals(1, u16(b, 2))
        assertEquals(88_200, u32(b, 8))
        assertEquals(2, u16(b, 12))
        assertEquals(0x4, u32(b, 20))          // SPEAKER_FRONT_CENTER
    }

    @Test
    fun `24 valid bits in a 32-bit container`() {
        val b = WaveFormatExtensible.encode(192_000, channels = 2, containerBits = 32, validBits = 24)
        assertEquals(8, u16(b, 12))
        assertEquals(1_536_000, u32(b, 8))
        assertEquals(32, u16(b, 14))
        assertEquals(24, u16(b, 18))
    }

    @Test
    fun `the PCM subtype GUID is in Windows memory order`() {
        // {00000001-0000-0010-8000-00AA00389B71}: Data1..3 little-endian, Data4 as written.
        val expected = byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)
        assertContentEquals(expected, WaveFormatExtensible.SUBTYPE_PCM)
    }

    @Test
    fun `decodeMix reads an extensible float mix`() {
        val b = WaveFormatExtensible.encode(48_000, 2, 32, 32)
        WaveFormatExtensible.SUBTYPE_IEEE_FLOAT.copyInto(b, 24)
        assertEquals(MixFormat(48_000, 32, isFloat = true), WaveFormatExtensible.decodeMix(b))
    }

    @Test
    fun `decodeMix reads a plain 18-byte float header`() {
        val b = ByteArray(18)
        b[0] = 3 // WAVE_FORMAT_IEEE_FLOAT
        b[4] = 0x80.toByte(); b[5] = 0xBB.toByte() // 48000
        b[14] = 32
        val m = WaveFormatExtensible.decodeMix(b)
        assertEquals(48_000, m.sampleRateHz)
        assertEquals(true, m.isFloat)
    }
}
```

`.../nativeio/wasapi/HResultsTest.kt`:

```kotlin
package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.output.OutputLineException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HResultsTest {
    private val fmt = DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE)

    @Test
    fun `unsupported format with a known format is FormatRejected`() {
        val e = HResults.toException(HResults.AUDCLNT_E_UNSUPPORTED_FORMAT, "Initialize", fmt)
        assertIs<OutputLineException.FormatRejected>(e)
        assertEquals(fmt, e.format)
    }

    @Test
    fun `device errors are DeviceProblem with a readable reason`() {
        val cases = mapOf(
            HResults.AUDCLNT_E_DEVICE_INVALIDATED to "device invalidated",
            HResults.AUDCLNT_E_DEVICE_IN_USE to "device in use",
            HResults.E_NOTFOUND to "no audio output device",
            HResults.AUDCLNT_E_SERVICE_NOT_RUNNING to "audio service not running",
        )
        for ((hr, words) in cases) {
            val e = HResults.toException(hr, "Op", fmt)
            assertIs<OutputLineException.DeviceProblem>(e)
            assertTrue(words in e.detail, "'${e.detail}' should mention '$words'")
        }
    }

    @Test
    fun `unknown failures carry their hex code`() {
        val e = HResults.toException(0x80004005L.toInt(), "Start", null) // E_FAIL
        assertIs<OutputLineException.DeviceProblem>(e)
        assertTrue("0x80004005" in e.detail)
    }

    @Test
    fun `check passes S_OK and S_FALSE`() {
        HResults.check(HResults.S_OK, "x")
        HResults.check(HResults.S_FALSE, "x")
    }
}
```

- [ ] **Step 2: Run them and confirm the compile failures**

Run: `.\gradlew :audio:playback:desktopTest --tests "*WaveFormatExtensibleTest" --tests "*HResultsTest" *> "$env:TEMP\t4.log"`
Expected: `Unresolved reference 'WaveFormatExtensible'` / `'HResults'`.

- [ ] **Step 3: Implement `RenderPort`, `WaveFormatExtensible`, `HResults`**

`output/RenderPort.kt`:

```kotlin
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
```

`nativeio/wasapi/WaveFormatExtensible.kt`:

```kotlin
// WAVEFORMATEXTENSIBLE encoder + mix-format decoder (#37). mmreg.h declares WAVEFORMATEX under
// #pragma pack(1), so the struct is built byte-by-byte (a JNA Structure would pad it).
// Layout: wFormatTag u16 @0, nChannels u16 @2, nSamplesPerSec u32 @4, nAvgBytesPerSec u32 @8,
// nBlockAlign u16 @12, wBitsPerSample u16 @14, cbSize u16 @16, wValidBitsPerSample u16 @18,
// dwChannelMask u32 @20, SubFormat GUID @24..40.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.playback.output.MixFormat

internal object WaveFormatExtensible {
    const val SIZE = 40
    private const val HEADER = 18
    private const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val SPEAKER_FRONT_LEFT = 0x1
    private const val SPEAKER_FRONT_RIGHT = 0x2
    private const val SPEAKER_FRONT_CENTER = 0x4

    /** KSDATAFORMAT_SUBTYPE_PCM {00000001-0000-0010-8000-00AA00389B71}, in GUID memory order. */
    val SUBTYPE_PCM: ByteArray = subtype(0x01)

    /** KSDATAFORMAT_SUBTYPE_IEEE_FLOAT {00000003-0000-0010-8000-00AA00389B71}. */
    val SUBTYPE_IEEE_FLOAT: ByteArray = subtype(0x03)

    fun encode(sampleRateHz: Int, channels: Int, containerBits: Int, validBits: Int): ByteArray {
        require(containerBits % 8 == 0 && validBits in 1..containerBits) { "bad bits $validBits/$containerBits" }
        val blockAlign = channels * containerBits / 8
        val b = ByteArray(SIZE)
        putU16(b, 0, WAVE_FORMAT_EXTENSIBLE)
        putU16(b, 2, channels)
        putU32(b, 4, sampleRateHz)
        putU32(b, 8, sampleRateHz * blockAlign)
        putU16(b, 12, blockAlign)
        putU16(b, 14, containerBits)
        putU16(b, 16, SIZE - HEADER)
        putU16(b, 18, validBits)
        putU32(b, 20, channelMask(channels))
        SUBTYPE_PCM.copyInto(b, 24)
        return b
    }

    fun channelMask(channels: Int): Int = when (channels) {
        1 -> SPEAKER_FRONT_CENTER
        2 -> SPEAKER_FRONT_LEFT or SPEAKER_FRONT_RIGHT
        else -> 0
    }

    /** [bytes] = the header plus cbSize bytes, as read from GetMixFormat's allocation. */
    fun decodeMix(bytes: ByteArray): MixFormat {
        val tag = getU16(bytes, 0)
        val isFloat = when (tag) {
            WAVE_FORMAT_IEEE_FLOAT -> true
            WAVE_FORMAT_EXTENSIBLE -> bytes.size >= SIZE && bytes.copyOfRange(24, SIZE).contentEquals(SUBTYPE_IEEE_FLOAT)
            else -> false
        }
        return MixFormat(sampleRateHz = getU32(bytes, 4), bitsPerSample = getU16(bytes, 14), isFloat = isFloat)
    }

    private fun subtype(data1: Int) = byteArrayOf(
        data1.toByte(), 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71,
    )

    private fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun putU32(b: ByteArray, o: Int, v: Int) { putU16(b, o, v and 0xFFFF); putU16(b, o + 2, v ushr 16) }
    private fun getU16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun getU32(b: ByteArray, o: Int) = getU16(b, o) or (getU16(b, o + 2) shl 16)
}
```

`nativeio/wasapi/HResults.kt`:

```kotlin
// Named HRESULTs Kiln acts on, and HRESULT → OutputLineException (#37). AUDCLNT_E_* codes are
// MAKE_HRESULT(SEVERITY_ERROR, FACILITY_AUDCLNT = 0x889, n) from audioclient.h.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.playback.output.OutputLineException

internal object HResults {
    const val S_OK = 0
    const val S_FALSE = 1
    val RPC_E_CHANGED_MODE = 0x80010106L.toInt()
    val E_NOTFOUND = 0x80070490L.toInt()                    // HRESULT_FROM_WIN32(ERROR_NOT_FOUND)
    val AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004L.toInt()
    val AUDCLNT_E_UNSUPPORTED_FORMAT = 0x88890008L.toInt()
    val AUDCLNT_E_DEVICE_IN_USE = 0x8889000AL.toInt()
    val AUDCLNT_E_SERVICE_NOT_RUNNING = 0x88890010L.toInt()

    fun hex(hr: Int): String = "0x%08X".format(hr)

    fun toException(hr: Int, what: String, format: DecodedAudioFormat?): OutputLineException = when (hr) {
        AUDCLNT_E_UNSUPPORTED_FORMAT ->
            if (format != null) OutputLineException.FormatRejected(format, "$what: unsupported format (${hex(hr)})")
            else OutputLineException.DeviceProblem("$what: unsupported format (${hex(hr)})")
        AUDCLNT_E_DEVICE_INVALIDATED -> OutputLineException.DeviceProblem("$what: device invalidated (${hex(hr)})")
        AUDCLNT_E_DEVICE_IN_USE -> OutputLineException.DeviceProblem("$what: device in use (${hex(hr)})")
        E_NOTFOUND -> OutputLineException.DeviceProblem("$what: no audio output device (${hex(hr)})")
        AUDCLNT_E_SERVICE_NOT_RUNNING ->
            OutputLineException.DeviceProblem("$what: Windows audio service not running (${hex(hr)})")
        else -> OutputLineException.DeviceProblem("$what failed: ${hex(hr)}")
    }

    /** Throws for a failed HRESULT (negative). S_OK and S_FALSE pass. */
    fun check(hr: Int, what: String, format: DecodedAudioFormat? = null) {
        if (hr < 0) throw toException(hr, what, format)
    }
}
```

- [ ] **Step 4: Implement the JNA COM plumbing (no unit tests: exercised on-device in Tasks 5 and 6)**

`nativeio/wasapi/ComBinding.kt`:

```kotlin
// COM plumbing for WASAPI over jna-platform (#37). ComPtr wraps an interface pointer and calls
// vtable slots by index (COMInvoker._invokeNativeInt is protected, hence the subclass; the
// `this` pointer is passed explicitly as the first argument, as jna-platform's own wrappers do).
// Slot indices follow each interface's declaration order after IUnknown's 0-2 (QueryInterface,
// AddRef, Release). Verified against the Windows SDK headers and on Cortex (Task 5 probe).

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.playback.output.OutputLineException
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32

internal class ComPtr(p: Pointer) : Unknown(p) {
    fun call(vtableIndex: Int, vararg args: Any?): Int = _invokeNativeInt(vtableIndex, arrayOf<Any?>(pointer, *args))
}

internal object WasapiIds {
    val CLSID_MMDeviceEnumerator = Guid.GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    val IID_IMMDeviceEnumerator = Guid.GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    val IID_IAudioClient = Guid.GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")
    val IID_IAudioRenderClient = Guid.GUID("{F294ACFC-3146-4483-A7BF-ADDCA7C260E2}")
    val IID_ISimpleAudioVolume = Guid.GUID("{87CE5498-68D6-44E5-9215-6DA47EF883D8}")
}

/** vtable slots. */
internal object Vt {
    // IMMDeviceEnumerator (mmdeviceapi.h): EnumAudioEndpoints=3, GetDefaultAudioEndpoint=4
    const val ENUM_GET_DEFAULT_AUDIO_ENDPOINT = 4
    // IMMDevice (mmdeviceapi.h): Activate=3
    const val DEVICE_ACTIVATE = 3
    // IAudioClient (audioclient.h): Initialize=3, GetBufferSize=4, GetStreamLatency=5,
    // GetCurrentPadding=6, IsFormatSupported=7, GetMixFormat=8, GetDevicePeriod=9, Start=10,
    // Stop=11, Reset=12, SetEventHandle=13, GetService=14
    const val AC_INITIALIZE = 3
    const val AC_GET_BUFFER_SIZE = 4
    const val AC_GET_CURRENT_PADDING = 6
    const val AC_GET_MIX_FORMAT = 8
    const val AC_START = 10
    const val AC_STOP = 11
    const val AC_RESET = 12
    const val AC_GET_SERVICE = 14
    // IAudioRenderClient (audioclient.h): GetBuffer=3, ReleaseBuffer=4
    const val RC_GET_BUFFER = 3
    const val RC_RELEASE_BUFFER = 4
    // ISimpleAudioVolume (audioclient.h): SetMasterVolume=3
    const val SAV_SET_MASTER_VOLUME = 3
}

internal object WasapiConst {
    const val E_RENDER = 0
    const val E_MULTIMEDIA = 1
    const val AUDCLNT_SHAREMODE_SHARED = 0
    private val AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000L.toInt()
    private const val AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000
    /** Let Windows convert any PCM to its float mix, resampling (default quality) if needed. */
    val SHARED_STREAM_FLAGS = AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM or AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY
}

/** COM is initialized once per thread (MTA). A thread that's already STA (RPC_E_CHANGED_MODE) still works. */
internal object ComInit {
    private val initialized = ThreadLocal.withInitial { false }

    fun ensure() {
        if (initialized.get()) return
        val hr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED).toInt()
        if (hr < 0 && hr != HResults.RPC_E_CHANGED_MODE) {
            throw OutputLineException.DeviceProblem("CoInitializeEx failed: ${HResults.hex(hr)}")
        }
        initialized.set(true)
    }
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `.\gradlew :audio:playback:desktopTest --tests "*WaveFormatExtensibleTest" --tests "*HResultsTest" *> "$env:TEMP\t4.log"`
Expected: `BUILD SUCCESSFUL`, 10 tests.

- [ ] **Step 6: Canonical build, then commit**

```bash
git add audio/playback/src
git commit -F - <<'EOF'
feat(player): WASAPI binding: WAVEFORMATEXTENSIBLE, HRESULTs, COM plumbing (#37 task 4)

Pure, unit-tested: the byte-exact WAVEFORMATEXTENSIBLE encoder (packed,
40 bytes, channel masks, PCM subtype GUID) plus a mix-format decoder, and
the HRESULT -> OutputLineException mapping. JNA plumbing: GUIDs, named
vtable slots per the SDK headers, ComPtr (vtable calls via
COMInvoker._invokeNativeInt), and ComInit (per-thread MTA). RenderPort is
the device seam WasapiOutputLine will drive.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 5: On-device probe: which layouts does `AUTOCONVERTPCM` accept?

A device-gated test that runs on Cortex (and skips on CI). Its printed matrix decides whether Task 6R (the 24-in-32 repack) runs.

**Files:**
- Create: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/nativeio/wasapi/WasapiFormatProbeTest.kt`

**Interfaces:**
- Consumes: `ComPtr`, `ComInit`, `WasapiIds`, `Vt`, `WasapiConst`, `WaveFormatExtensible`, `HResults` (Task 4).

- [ ] **Step 1: Write the probe**

```kotlin
// On-device probe (#37): which PCM layouts shared-mode IAudioClient.Initialize accepts with
// AUTOCONVERTPCM. Skips when there's no Windows audio endpoint (CI). The matrix is printed to the
// test's stdout (build/test-results/.../TEST-*WasapiFormatProbeTest.xml, <system-out>). It also
// verifies the vtable slots and GUIDs: a wrong slot crashes or returns garbage here, not in prod.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.sun.jna.Memory
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.PointerByReference
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertTrue

class WasapiFormatProbeTest {

    private fun defaultDevice(): ComPtr? = runCatching {
        ComInit.ensure()
        val ppv = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            WasapiIds.CLSID_MMDeviceEnumerator, null, WTypes.CLSCTX_ALL, WasapiIds.IID_IMMDeviceEnumerator, ppv,
        ).toInt()
        if (hr < 0) return@runCatching null
        val enumerator = ComPtr(ppv.value)
        val hr2 = enumerator.call(Vt.ENUM_GET_DEFAULT_AUDIO_ENDPOINT, WasapiConst.E_RENDER, WasapiConst.E_MULTIMEDIA, ppv)
        enumerator.Release()
        if (hr2 < 0) null else ComPtr(ppv.value)
    }.getOrNull()

    /** Activate a fresh IAudioClient, Initialize with [wfx], release; returns Initialize's HRESULT. */
    private fun tryInitialize(device: ComPtr, wfx: ByteArray): Int {
        val ppv = PointerByReference()
        HResults.check(device.call(Vt.DEVICE_ACTIVATE, WasapiIds.IID_IAudioClient, WTypes.CLSCTX_ALL, null, ppv), "Activate")
        val client = ComPtr(ppv.value)
        try {
            val mem = Memory(wfx.size.toLong()).apply { write(0, wfx, 0, wfx.size) }
            return client.call(
                Vt.AC_INITIALIZE, WasapiConst.AUDCLNT_SHAREMODE_SHARED, WasapiConst.SHARED_STREAM_FLAGS,
                200 * 10_000L, 0L, mem, null,
            )
        } finally {
            client.Release()
        }
    }

    @Test
    fun `report which layouts shared-mode AUTOCONVERTPCM accepts`() {
        Assume.assumeTrue("Windows only", System.getProperty("os.name").startsWith("Windows"))
        val device = defaultDevice()
        Assume.assumeTrue("no default audio endpoint", device != null)
        device!!
        val layouts = listOf(16 to 16, 24 to 24, 32 to 24, 32 to 32) // container to valid bits
        val accepted = mutableMapOf<Pair<Int, Int>, Boolean>()
        try {
            for (rate in listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)) {
                for (ch in listOf(1, 2)) {
                    for ((container, valid) in layouts) {
                        val hr = tryInitialize(device, WaveFormatExtensible.encode(rate, ch, container, valid))
                        println("PROBE ${rate}Hz ${ch}ch ${container}c/${valid}v -> ${HResults.hex(hr)}")
                        accepted[container to valid] = (accepted[container to valid] ?: true) && hr >= 0
                    }
                }
            }
        } finally {
            device.Release()
        }
        println("PROBE SUMMARY (accepted at every rate/channel count): $accepted")
        assertTrue(accepted.getValue(16 to 16), "16-bit must be accepted everywhere")
        assertTrue(accepted.getValue(32 to 32), "32-bit int must be accepted everywhere")
        assertTrue(
            accepted.getValue(24 to 24) || accepted.getValue(32 to 24),
            "some 24-bit layout must be accepted everywhere",
        )
    }
}
```

- [ ] **Step 2: Run it on Cortex and read the matrix**

Run: `.\gradlew :audio:playback:desktopTest --tests "*WasapiFormatProbeTest" *> "$env:TEMP\t5.log"`, then:
`Select-String -Path audio\playback\build\test-results\desktopTest\TEST-*WasapiFormatProbeTest.xml -Pattern 'PROBE SUMMARY|FAILED'`
Expected: the test PASSES (not SKIPPED; Cortex has an endpoint), and the summary line reads e.g. `{(16, 16)=true, (24, 24)=?, (32, 24)=?, (32, 32)=true}`.

**If the JVM crashes or an HRESULT is garbage** (e.g. `0x00000000` for every layout including nonsense ones), a vtable slot or GUID is wrong. Stop and re-check `Vt`/`WasapiIds` against `audioclient.h`/`mmdeviceapi.h` before going further.

- [ ] **Step 3: Record the decision**

- `(24, 24)=true` → **skip Task 6R.** 24-bit is written as-is.
- `(24, 24)=false` and `(32, 24)=true` → **do Task 6R** (lossless 24-in-32 repack).

Write the summary line into the Task 5 commit message.

- [ ] **Step 4: Commit**

```bash
git add audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/nativeio/wasapi/WasapiFormatProbeTest.kt
git commit -F - <<'EOF'
test(player): on-device WASAPI format-acceptance probe (#37 task 5)

Device-gated (skips without a Windows audio endpoint, so on CI). It also
verifies the COM vtable slots and GUIDs on real hardware.

Cortex result: <paste the PROBE SUMMARY line>
Decision: <24-bit written as-is | 24-in-32 repack needed (task 6R)>

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 6: `WasapiClient` + `WasapiOutputLine`

**Files:**
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/nativeio/wasapi/WasapiClient.kt`
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/output/WasapiOutputLine.kt`
- Test: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/output/WasapiOutputLineTest.kt` (fake port)
- Test: `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/nativeio/wasapi/WasapiDeviceTest.kt` (device-gated)

**Interfaces:**
- Consumes: `RenderPort`, `MixFormat`, `PcmOutputLine`, `framesToNanos`, `OutputRoute`, `OutputEngine`, `WaveFormatExtensible`, `HResults`, and the COM plumbing.
- Produces: `internal class WasapiClient : RenderPort` with `companion fun open(format: DecodedAudioFormat, wfx: ByteArray, bufferMs: Int): WasapiClient` and `companion fun isAvailable(): Boolean`; `internal class WasapiOutputLine(port: RenderPort, sourceFormat: DecodedAudioFormat) : PcmOutputLine` with `companion fun open(format: DecodedAudioFormat): WasapiOutputLine` and `const val BUFFER_MS = 200`.

- [ ] **Step 1: Write the failing fake-port tests**

`.../output/WasapiOutputLineTest.kt`:

```kotlin
// WasapiOutputLine's write/interrupt/flush logic against a fake RenderPort. No device needed.

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.OutputEngine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasapiOutputLineTest {

    private val fmt = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE) // 4-byte frames

    private class FakePort(
        override val bufferFrames: Int = 1_000,
        override val clientRateHz: Int = 44_100,
        override val mix: MixFormat = MixFormat(48_000, 32, isFloat = true),
    ) : RenderPort {
        @Volatile var padding = 0
        val calls = CopyOnWriteArrayList<String>()
        val framesWritten = AtomicInteger()
        override fun paddingFrames() = padding
        override fun writeFrames(src: ByteArray, off: Int, frames: Int) { framesWritten.addAndGet(frames); padding += frames }
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun reset() { calls += "reset"; padding = 0 }
        override fun setVolume(level: Float) { calls += "volume:$level" }
        override fun close() { calls += "close" }
    }

    @Test
    fun `write fills free space and returns the full length`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        assertEquals(1_600, line.write(ByteArray(1_600), 0, 1_600)) // 400 frames
        assertEquals(400, port.framesWritten.get())
    }

    @Test
    fun `write waits on a full buffer until space frees up`() {
        val port = FakePort().apply { padding = 1_000 }
        val line = WasapiOutputLine(port, fmt)
        var result = -1
        val t = thread { result = line.write(ByteArray(1_600), 0, 1_600) }
        Thread.sleep(60)
        assertTrue(t.isAlive, "must block while the buffer is full")
        port.padding = 0
        t.join(2_000)
        assertFalse(t.isAlive)
        assertEquals(1_600, result)
    }

    @Test
    fun `a cross-thread interrupt makes a blocked write return promptly`() {
        val port = FakePort().apply { padding = 1_000 } // full forever
        val line = WasapiOutputLine(port, fmt)
        var result = -1
        val t = thread { result = line.write(ByteArray(1_600), 0, 1_600) }
        Thread.sleep(60)
        val t0 = System.nanoTime()
        line.interrupt(discard = false)
        t.join(2_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertFalse(t.isAlive)
        assertTrue(ms < 200, "interrupt took $ms ms")
        assertEquals(0, result)
        assertTrue(port.calls.none { it == "stop" }, "interrupt must not touch the device (no COM off the actor)")
    }

    @Test
    fun `start after an interrupt re-arms writes`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.interrupt(discard = true)
        assertEquals(0, line.write(ByteArray(40), 0, 40))
        line.start()
        assertEquals(40, line.write(ByteArray(40), 0, 40))
    }

    @Test
    fun `start is idempotent while running`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.start(); line.start()
        assertEquals(listOf("start"), port.calls)
        assertTrue(line.isRunning)
    }

    @Test
    fun `flush while running stops, resets and restarts`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.start()
        line.flush()
        assertEquals(listOf("start", "stop", "reset", "start"), port.calls)
        assertTrue(line.isRunning)
    }

    @Test
    fun `flush while stopped only resets`() {
        val port = FakePort()
        WasapiOutputLine(port, fmt).flush()
        assertEquals(listOf("reset"), port.calls)
    }

    @Test
    fun `stop keeps buffered audio and is idempotent`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.start(); line.stop(); line.stop()
        assertEquals(listOf("start", "stop"), port.calls)
        assertFalse(line.isRunning)
    }

    @Test
    fun `bufferNanos and queuedNanos are durations at the client rate`() {
        val port = FakePort(bufferFrames = 8_820).apply { padding = 4_410 }
        val line = WasapiOutputLine(port, fmt)
        assertEquals(200_000_000L, line.bufferNanos)
        assertEquals(100_000_000L, line.queuedNanos())
    }

    @Test
    fun `route reports the device mix and flags resampling`() {
        val route = WasapiOutputLine(FakePort(), fmt).route()
        assertEquals(OutputEngine.WasapiShared, route.engine)
        assertEquals(48_000, route.deviceRateHz)
        assertEquals("32-bit float mix", route.deviceFormat)
        assertTrue(route.resampled)
    }

    @Test
    fun `write rejects a partial frame`() {
        assertFailsWith<IllegalArgumentException> { WasapiOutputLine(FakePort(), fmt).write(ByteArray(6), 0, 6) }
    }

    @Test
    fun `setVolume clamps to the 0-1 range`() {
        val port = FakePort()
        val line = WasapiOutputLine(port, fmt)
        line.setVolume(1.5f); line.setVolume(-1f)
        assertEquals(listOf("volume:1.0", "volume:0.0"), port.calls)
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `.\gradlew :audio:playback:desktopTest --tests "*WasapiOutputLineTest" *> "$env:TEMP\t6.log"`
Expected: `Unresolved reference 'WasapiOutputLine'`.

- [ ] **Step 3: Implement `WasapiOutputLine`**

`output/WasapiOutputLine.kt`:

```kotlin
// WasapiOutputLine: PcmOutputLine over a WASAPI shared-mode stream (#37). Push model: write()
// copies into the endpoint buffer and parks briefly while it's full. There are no event handles
// and no native callbacks into the JVM, and the 200 ms buffer absorbs GC pauses.
//
// Threading: everything except interrupt() runs on the thread that opened the line (the actor).
// interrupt() only sets a flag and unparks the writer. It makes no COM calls; the actor follows
// up with stop()/flush()/close() itself. While interrupted, write() returns at once with the
// bytes written so far. That matches Java Sound's write-to-a-stopped-line behavior, and
// start()/flush() re-arm it.

package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
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
            port.stop()
            running = false
        }
    }

    override fun flush() {
        interrupted = false
        // IAudioClient.Reset requires a stopped stream.
        if (running) {
            port.stop(); port.reset(); port.start()
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
```

- [ ] **Step 4: Implement `WasapiClient`**

`nativeio/wasapi/WasapiClient.kt`:

```kotlin
// WasapiClient: the COM RenderPort (#37). Opens the DEFAULT multimedia render endpoint on every
// open (so a replugged/changed default device is picked up at the next track), initializes a
// shared-mode stream with AUTOCONVERTPCM, and exposes the render client + session volume. Every
// call happens on the opening thread (COM initialized there by ComInit). close() releases in
// reverse acquisition order and is idempotent.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.playback.output.MixFormat
import com.clayworks.kiln.audio.playback.output.RenderPort
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

internal class WasapiClient private constructor(
    private val acquired: List<ComPtr>, // enumerator, device, audioClient, renderClient, volume
    private val blockAlign: Int,
    override val bufferFrames: Int,
    override val clientRateHz: Int,
    override val mix: MixFormat,
) : RenderPort {

    private val audioClient = acquired[2]
    private val renderClient = acquired[3]
    private val volume = acquired[4]
    private val padding = IntByReference()
    private val data = PointerByReference()
    private var closed = false

    override fun paddingFrames(): Int {
        HResults.check(audioClient.call(Vt.AC_GET_CURRENT_PADDING, padding), "GetCurrentPadding")
        return padding.value
    }

    override fun writeFrames(src: ByteArray, off: Int, frames: Int) {
        HResults.check(renderClient.call(Vt.RC_GET_BUFFER, frames, data), "GetBuffer")
        data.value.write(0, src, off, frames * blockAlign)
        HResults.check(renderClient.call(Vt.RC_RELEASE_BUFFER, frames, 0), "ReleaseBuffer")
    }

    override fun start() = HResults.check(audioClient.call(Vt.AC_START), "Start")
    override fun stop() = HResults.check(audioClient.call(Vt.AC_STOP), "Stop")
    override fun reset() = HResults.check(audioClient.call(Vt.AC_RESET), "Reset")

    override fun setVolume(level: Float) =
        HResults.check(volume.call(Vt.SAV_SET_MASTER_VOLUME, level, null), "SetMasterVolume")

    override fun close() {
        if (closed) return
        closed = true
        runCatching { audioClient.call(Vt.AC_STOP) }
        acquired.asReversed().forEach { runCatching { it.Release() } }
    }

    companion object {
        /** True if COM + the MMDevice enumerator work on this thread. Endpoint presence isn't checked. */
        fun isAvailable(): Boolean = try {
            ComInit.ensure()
            val ppv = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                WasapiIds.CLSID_MMDeviceEnumerator, null, WTypes.CLSCTX_ALL, WasapiIds.IID_IMMDeviceEnumerator, ppv,
            ).toInt()
            if (hr >= 0) ComPtr(ppv.value).Release()
            hr >= 0
        } catch (t: Throwable) {
            false // not Windows (UnsatisfiedLinkError), COM unusable, …
        }

        fun open(format: DecodedAudioFormat, wfx: ByteArray, bufferMs: Int): WasapiClient {
            ComInit.ensure()
            val acquired = ArrayList<ComPtr>(5)
            try {
                val ppv = PointerByReference()
                HResults.check(
                    Ole32.INSTANCE.CoCreateInstance(
                        WasapiIds.CLSID_MMDeviceEnumerator, null, WTypes.CLSCTX_ALL, WasapiIds.IID_IMMDeviceEnumerator, ppv,
                    ).toInt(),
                    "CoCreateInstance(MMDeviceEnumerator)",
                )
                val enumerator = ComPtr(ppv.value).also(acquired::add)
                HResults.check(
                    enumerator.call(Vt.ENUM_GET_DEFAULT_AUDIO_ENDPOINT, WasapiConst.E_RENDER, WasapiConst.E_MULTIMEDIA, ppv),
                    "GetDefaultAudioEndpoint",
                )
                val device = ComPtr(ppv.value).also(acquired::add)
                HResults.check(device.call(Vt.DEVICE_ACTIVATE, WasapiIds.IID_IAudioClient, WTypes.CLSCTX_ALL, null, ppv), "Activate")
                val audioClient = ComPtr(ppv.value).also(acquired::add)

                val wfxMem = Memory(wfx.size.toLong()).apply { write(0, wfx, 0, wfx.size) }
                HResults.check(
                    audioClient.call(
                        Vt.AC_INITIALIZE, WasapiConst.AUDCLNT_SHAREMODE_SHARED, WasapiConst.SHARED_STREAM_FLAGS,
                        bufferMs * 10_000L, 0L, wfxMem, null,
                    ),
                    "Initialize",
                    format,
                )
                val frames = IntByReference()
                HResults.check(audioClient.call(Vt.AC_GET_BUFFER_SIZE, frames), "GetBufferSize")
                HResults.check(audioClient.call(Vt.AC_GET_SERVICE, WasapiIds.IID_IAudioRenderClient, ppv), "GetService(render)")
                ComPtr(ppv.value).also(acquired::add)
                HResults.check(audioClient.call(Vt.AC_GET_SERVICE, WasapiIds.IID_ISimpleAudioVolume, ppv), "GetService(volume)")
                ComPtr(ppv.value).also(acquired::add)

                HResults.check(audioClient.call(Vt.AC_GET_MIX_FORMAT, ppv), "GetMixFormat")
                val mixPtr = ppv.value
                val mix = try {
                    val cbSize = mixPtr.getShort(16).toInt() and 0xFFFF
                    WaveFormatExtensible.decodeMix(mixPtr.getByteArray(0, 18 + cbSize))
                } finally {
                    Ole32.INSTANCE.CoTaskMemFree(mixPtr)
                }

                val blockAlign = (wfx[12].toInt() and 0xFF) or ((wfx[13].toInt() and 0xFF) shl 8)
                return WasapiClient(acquired, blockAlign, frames.value, format.sampleRateHz, mix)
            } catch (e: Throwable) {
                acquired.asReversed().forEach { runCatching { it.Release() } }
                throw e
            }
        }
    }
}
```

- [ ] **Step 5: Run the fake-port tests and confirm they pass**

Run: `.\gradlew :audio:playback:desktopTest --tests "*WasapiOutputLineTest" *> "$env:TEMP\t6.log"`
Expected: `BUILD SUCCESSFUL`, 12 tests.

- [ ] **Step 6: Write the device-gated test**

`.../nativeio/wasapi/WasapiDeviceTest.kt`:

```kotlin
// Real-device checks for WasapiOutputLine (#37). Skips without a Windows audio endpoint (CI).
// Each test runs its COM work on one worker thread, as the actor does in production. Output is
// silence.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.output.OutputLineException
import com.clayworks.kiln.audio.playback.output.WasapiOutputLine
import org.junit.Assume
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasapiDeviceTest {

    private val audioThread = Executors.newSingleThreadExecutor { Thread(it, "test-audio").apply { isDaemon = true } }

    @AfterTest fun tearDown() { audioThread.shutdownNow() }

    private fun <T> onAudioThread(block: () -> T): T = audioThread.submit(Callable(block)).get(30, TimeUnit.SECONDS)

    private fun fmt(rate: Int, bits: Int, ch: Int = 2) = DecodedAudioFormat(
        rate, bits, ch,
        when (bits) { 16 -> SampleFormat.PCM_S16_LE; 24 -> SampleFormat.PCM_S24_LE; else -> SampleFormat.PCM_S32_LE },
    )

    private fun assumeDevice() {
        Assume.assumeTrue("Windows only", System.getProperty("os.name").startsWith("Windows"))
        val ok = onAudioThread {
            WasapiClient.isAvailable() &&
                runCatching { WasapiOutputLine.open(fmt(48_000, 16)).close() }
                    .exceptionOrNull().let { it !is OutputLineException.DeviceProblem }
        }
        Assume.assumeTrue("no usable default audio endpoint", ok)
    }

    @Test
    fun `opens, plays 250 ms of silence and closes for every depth and rate`() {
        assumeDevice()
        for (rate in listOf(44_100, 96_000, 192_000)) {
            for (bits in listOf(16, 24, 32)) {
                onAudioThread {
                    val line = WasapiOutputLine.open(fmt(rate, bits))
                    line.use {
                        it.start()
                        val silence = ByteArray(rate / 4 * 2 * bits / 8)
                        assertEquals(silence.size, it.write(silence, 0, silence.size), "$rate/$bits")
                        assertTrue(it.queuedNanos() <= it.bufferNanos, "$rate/$bits queued ≤ buffer")
                        it.stop()
                        it.flush()
                    }
                }
            }
        }
    }

    @Test
    fun `mono opens and plays`() {
        assumeDevice()
        onAudioThread {
            WasapiOutputLine.open(fmt(44_100, 16, ch = 1)).use {
                it.start()
                assertEquals(8_820, it.write(ByteArray(8_820), 0, 8_820)) // 100 ms
            }
        }
    }

    @Test
    fun `a cross-thread interrupt returns a blocked write promptly`() {
        assumeDevice()
        val line = onAudioThread { WasapiOutputLine.open(fmt(48_000, 16)).also { it.start() } }
        val twoSeconds = ByteArray(48_000 * 4 * 2) // far more than the 200 ms buffer, so write blocks
        val pending = audioThread.submit(Callable { line.write(twoSeconds, 0, twoSeconds.size) })
        Thread.sleep(400)
        val t0 = System.nanoTime()
        line.interrupt(discard = true) // from the test thread, like pause()/skip()
        val written = pending.get(2, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 200, "interrupt took $ms ms")
        assertTrue(written < twoSeconds.size)
        onAudioThread { line.close() }
    }

    @Test
    fun `fifty open-close cycles don't fail or leak`() {
        assumeDevice()
        onAudioThread { repeat(50) { WasapiOutputLine.open(fmt(44_100, 16)).close() } }
    }
}
```

- [ ] **Step 7: Run the device test on Cortex**

Run: `.\gradlew :audio:playback:desktopTest --tests "*WasapiDeviceTest" *> "$env:TEMP\t6d.log"`, then check `TEST-*WasapiDeviceTest.xml`.
Expected: 4 tests **passed, not skipped**. (If Task 5 said 24-in-24 is rejected, the 24-bit iteration fails here with `FormatRejected`. That's expected until Task 6R.)

- [ ] **Step 8: Canonical build, then commit**

```bash
git add audio/playback/src
git commit -F - <<'EOF'
feat(player): WASAPI shared-mode output line (#37 task 6)

WasapiClient opens the default multimedia endpoint on every open,
initializes a shared stream with AUTOCONVERTPCM + SRC_DEFAULT_QUALITY
and a 200 ms buffer, and exposes the render client and session volume.
WasapiOutputLine drives it through the RenderPort seam. The write is a
polled push. interrupt() only flags and unparks (no COM off the actor);
flush() is Stop/Reset/Start when running.

Tests: WasapiOutputLineTest (12, fake port: blocking, prompt interrupt,
re-arm, idempotent start/stop, flush sequences, durations, route, partial
frames, volume clamp) and WasapiDeviceTest (4, device-gated, run on
Cortex: 16/24/32 at 44.1/96/192, mono, cross-thread interrupt < 200 ms,
50 open/close cycles).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 6R (only if the Task 5 probe rejected 24-in-24): lossless 24-in-32 repack

**Files:**
- Create: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/output/Repack24in32.kt`
- Modify: `audio/playback/src/desktopMain/.../output/WasapiOutputLine.kt`
- Test: `audio/playback/src/desktopTest/.../output/Repack24in32Test.kt`

**Interfaces:**
- Produces: `internal fun repack24in32(src: ByteArray, srcOff: Int, len: Int, dst: ByteArray): Int`, and `WasapiOutputLine(port, sourceFormat, repack24: Boolean = false)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// 24-bit little-endian samples → left-justified in a 32-bit container (value << 8). Lossless,
// terminal (runs inside WasapiOutputLine.write, after all DSP), and allocation-free.

package com.clayworks.kiln.audio.playback.output

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Repack24in32Test {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `golden samples - full scale, zero, and one LSB either side`() {
        val src = b(0xFF, 0xFF, 0x7F, 0x00, 0x00, 0x80, 0, 0, 0, 0x01, 0, 0, 0xFF, 0xFF, 0xFF)
        val dst = ByteArray(20)
        assertEquals(20, repack24in32(src, 0, src.size, dst))
        assertContentEquals(
            b(0, 0xFF, 0xFF, 0x7F, 0, 0x00, 0x00, 0x80, 0, 0, 0, 0, 0, 0x01, 0, 0, 0, 0xFF, 0xFF, 0xFF),
            dst,
        )
    }

    @Test
    fun `honors the source offset`() {
        val src = b(0xAA, 0xBB, 0x01, 0x02, 0x03)
        val dst = ByteArray(4)
        repack24in32(src, 2, 3, dst)
        assertContentEquals(b(0, 0x01, 0x02, 0x03), dst)
    }

    @Test
    fun `rejects a length that isn't whole samples`() {
        assertFailsWith<IllegalArgumentException> { repack24in32(ByteArray(4), 0, 4, ByteArray(8)) }
    }

    @Test
    fun `rejects a destination that's too small`() {
        assertFailsWith<IllegalArgumentException> { repack24in32(ByteArray(6), 0, 6, ByteArray(7)) }
    }

    @Test
    fun `allocates nothing per call`() {
        val src = ByteArray(24_576) // 4096 stereo frames
        val dst = ByteArray(32_768)
        repeat(100) { repack24in32(src, 0, src.size, dst) } // warm up (JIT)
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = mx.currentThreadAllocatedBytes
        repeat(1_000) { repack24in32(src, 0, src.size, dst) }
        val allocated = mx.currentThreadAllocatedBytes - before
        assertTrue(allocated < 4_096, "allocated $allocated bytes over 1000 calls")
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail** (`Unresolved reference 'repack24in32'`).

- [ ] **Step 3: Implement it**

`output/Repack24in32.kt`:

```kotlin
// Lossless 24-in-32 repack for WASAPI (#37 task 6R; needed because shared-mode AUTOCONVERTPCM
// rejects 24-bit samples in a 24-bit container: see the Task 5 probe). Each 3-byte little-endian
// sample becomes a 4-byte little-endian value (sample << 8): a zero low byte, then the original
// three bytes. Returns bytes written to dst.

package com.clayworks.kiln.audio.playback.output

internal fun repack24in32(src: ByteArray, srcOff: Int, len: Int, dst: ByteArray): Int {
    require(len % 3 == 0) { "len $len is not whole 24-bit samples" }
    val outLen = len / 3 * 4
    require(dst.size >= outLen) { "dst ${dst.size} < $outLen" }
    var i = srcOff
    var o = 0
    val end = srcOff + len
    while (i < end) {
        dst[o] = 0
        dst[o + 1] = src[i]
        dst[o + 2] = src[i + 1]
        dst[o + 3] = src[i + 2]
        i += 3
        o += 4
    }
    return outLen
}
```

- [ ] **Step 4: Use it in `WasapiOutputLine`**

- Constructor: add `private val repack24: Boolean = false`.
- Field: `private val scratch: ByteArray? = if (repack24) ByteArray(port.bufferFrames * sourceFormat.channels * 4) else null`
- In `write`, replace `port.writeFrames(bytes, off + written, frames)` with:

```kotlin
                val s = scratch
                if (s != null) {
                    repack24in32(bytes, off + written, frames * srcFrameBytes, s)
                    port.writeFrames(s, 0, frames)
                } else {
                    port.writeFrames(bytes, off + written, frames)
                }
```

- In `open`: `val repack = format.bitDepth == 24`. Then `containerBits = if (repack) 32 else format.bitDepth`, `validBits = format.bitDepth`, and `return WasapiOutputLine(WasapiClient.open(format, wfx, BUFFER_MS), format, repack24 = repack)`.

Add to `WasapiOutputLineTest`:

```kotlin
    @Test
    fun `24-bit writes are repacked into 32-bit frames`() {
        val f24 = DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE) // 6-byte source frames
        val port = FakePort()
        val line = WasapiOutputLine(port, f24, repack24 = true)
        assertEquals(600, line.write(ByteArray(600), 0, 600)) // 100 source frames
        assertEquals(100, port.framesWritten.get())
    }
```

- [ ] **Step 5: Run the unit tests and the device test on Cortex.** All pass, including the 24-bit rows of `WasapiDeviceTest`.

- [ ] **Step 6: Canonical build, then commit** as `feat(player): lossless 24-in-32 repack for WASAPI (#37 task 6R)`, with a body quoting the probe result.

---

### Task 7: Production factory, `DesktopPlayer`, graph wiring

**Files:**
- Create: `audio/playback/src/desktopMain/.../output/DesktopOutputLineFactory.kt`
- Create: `audio/playback/src/desktopMain/.../DesktopPlayer.kt`
- Modify: `audio/playback/src/desktopMain/.../JavaSoundPlayerImpl.kt`
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt`
- Test: `audio/playback/src/desktopTest/.../output/DesktopOutputLineFactoryTest.kt`

**Interfaces:**
- Consumes: `WasapiClient.isAvailable`, `WasapiOutputLine.open`, `JavaSoundOutputLine.open`, `PlaybackDiagnostics`.
- Produces: `internal class DesktopOutputLineFactory(wasapiAvailable: () -> Boolean, openWasapi: (DecodedAudioFormat) -> PcmOutputLine, openJavaSound: (DecodedAudioFormat) -> PcmOutputLine) : PcmOutputLineFactory`; `interface DesktopPlayer : PlatformPlayer, PlaybackDiagnostics` (public); `fun createJavaSoundPlayer(...): DesktopPlayer`; and `DesktopAppGraph.playbackDiagnostics: PlaybackDiagnostics`.

- [ ] **Step 1: Write the failing factory tests**

```kotlin
package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.FakeOutputLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopOutputLineFactoryTest {
    private val fmt = DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE)

    @Test
    fun `uses WASAPI when it's available`() {
        val opened = mutableListOf<String>()
        val f = DesktopOutputLineFactory({ true }, { opened += "wasapi"; FakeOutputLine() }, { opened += "javasound"; FakeOutputLine() })
        f.open(fmt)
        assertEquals(listOf("wasapi"), opened)
    }

    @Test
    fun `falls back to Java Sound when WASAPI can't initialize`() {
        val opened = mutableListOf<String>()
        val f = DesktopOutputLineFactory({ false }, { opened += "wasapi"; FakeOutputLine() }, { opened += "javasound"; FakeOutputLine() })
        f.open(fmt)
        assertEquals(listOf("javasound"), opened)
    }

    @Test
    fun `decides once per session`() {
        var probes = 0
        val f = DesktopOutputLineFactory({ probes++; true }, { FakeOutputLine() }, { FakeOutputLine() })
        repeat(3) { f.open(fmt) }
        assertEquals(1, probes)
    }

    @Test
    fun `a WASAPI format rejection is not retried on Java Sound`() {
        var javaSoundOpens = 0
        val f = DesktopOutputLineFactory(
            { true },
            { throw OutputLineException.FormatRejected(it, "nope") },
            { javaSoundOpens++; FakeOutputLine() },
        )
        assertFailsWith<OutputLineException.FormatRejected> { f.open(fmt) }
        assertEquals(0, javaSoundOpens)
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail.**

- [ ] **Step 3: Implement the factory and `DesktopPlayer`**

`output/DesktopOutputLineFactory.kt`:

```kotlin
// DesktopOutputLineFactory: the production PcmOutputLineFactory (#37). Decides ONCE, lazily on
// the first open (so on the actor thread, where COM gets initialized), whether WASAPI works on
// this machine. Endpoint presence is not part of that decision: "no device right now" is a
// transient DeviceProblem and must not downgrade the session to 16-bit Java Sound. A WASAPI
// format rejection is never retried on Java Sound, which would be strictly worse.

package com.clayworks.kiln.audio.playback.output

import co.touchlab.kermit.Logger
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.playback.nativeio.wasapi.WasapiClient

private val log = Logger.withTag("OutputLine")

internal class DesktopOutputLineFactory(
    private val wasapiAvailable: () -> Boolean = { WasapiClient.isAvailable() },
    private val openWasapi: (DecodedAudioFormat) -> PcmOutputLine = { WasapiOutputLine.open(it) },
    private val openJavaSound: (DecodedAudioFormat) -> PcmOutputLine = { JavaSoundOutputLine.open(it) },
) : PcmOutputLineFactory {

    private val useWasapi: Boolean by lazy {
        wasapiAvailable().also { ok ->
            if (ok) log.i { "Output engine: WASAPI shared mode" }
            else log.w { "WASAPI unavailable; falling back to Java Sound (16-bit only on Windows)" }
        }
    }

    override fun open(format: DecodedAudioFormat): PcmOutputLine =
        if (useWasapi) openWasapi(format) else openJavaSound(format)
}
```

`DesktopPlayer.kt`:

```kotlin
// DesktopPlayer: the desktop PlatformPlayer, which also reports its output path and notices
// (#37). The graph binds this one instance to both interfaces by type (no casts).

package com.clayworks.kiln.audio.playback

interface DesktopPlayer : PlatformPlayer, PlaybackDiagnostics
```

- [ ] **Step 4: Switch the player's defaults**

In `JavaSoundPlayerImpl.kt`:
- `fun createJavaSoundPlayer(...): DesktopPlayer = JavaSoundPlayerImpl(...)`
- `internal class JavaSoundPlayerImpl(...) : DesktopPlayer {` (replaces `PlatformPlayer, PlaybackDiagnostics`)
- Constructor default: `private val lineFactory: PcmOutputLineFactory = DesktopOutputLineFactory(),`
- Import `com.clayworks.kiln.audio.playback.output.DesktopOutputLineFactory`, and drop the now-unused `JavaSoundOutputLine` import if nothing else uses it.
- Header comment line 1: "Desktop PlatformPlayer: WASAPI shared-mode output (Java Sound fallback) + the JNA libFLAC Decoder pipeline."

- [ ] **Step 5: Wire the graph**

In `DesktopAppGraph.kt`:
- Rename the provider `player(...)` to `desktopPlayer(...)`, changing its return type to `DesktopPlayer` (keep `@Singleton @Provides` and the body unchanged).
- Add:

```kotlin
    @Provides
    protected fun platformPlayer(player: DesktopPlayer): PlatformPlayer = player

    @Provides
    protected fun playbackDiagnostics(player: DesktopPlayer): PlaybackDiagnostics = player
```

- In the abstract members, add `abstract val playbackDiagnostics: PlaybackDiagnostics` under `abstract val player: PlatformPlayer`.
- Import `com.clayworks.kiln.audio.playback.DesktopPlayer` and `com.clayworks.kiln.audio.playback.PlaybackDiagnostics`.

- [ ] **Step 6: Run the tests, then the canonical build**

Expected: the factory tests (4) pass; `:app-desktop:assemble` passes (KSP resolves the new bindings); all other suites stay green.

- [ ] **Step 7: Desktop sanity launch**

Run `.\gradlew :app-desktop:run *> "$env:TEMP\kiln-run.log"` in the background. Play *2morrow* (id 7593, 24/44.1) from the Library tab: it must now play. Check the log for `Output engine: WASAPI shared mode`. Stop the app before committing.

- [ ] **Step 8: Commit**

```bash
git add audio/playback/src app-desktop/src
git commit -F - <<'EOF'
feat(player): WASAPI is the desktop output engine; Java Sound is the fallback (#37 task 7)

DesktopOutputLineFactory picks WASAPI once per session and falls back to
Java Sound only if COM/the device enumerator is unusable (endpoint
absence is transient and never downgrades; a WASAPI format rejection is
never retried on Java Sound). The desktop player is now a DesktopPlayer
(PlatformPlayer + PlaybackDiagnostics), which the graph binds to both
types; it exposes playbackDiagnostics.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 8: UI: error line, skip notices, output readout

**Files:**
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/playback/PlaybackMessages.kt`
- Modify: `ui/components/src/commonMain/.../specsheet/LocalLibraryStats.kt`
- Modify: `ui/components/src/commonMain/.../nowplaying/NowPlayingContent.kt`, `.../nowplaying/NowPlayingTab.kt`
- Modify: `ui/components/src/commonMain/.../specsheet/SpecSheetContent.kt`, `.../specsheet/SpecSheetScreen.kt`
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt`
- Test: `ui/components/src/commonTest/kotlin/com/clayworks/kiln/ui/components/playback/PlaybackMessagesTest.kt`
- Test: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingDiagnosticsTest.kt`
- Test: `ui/components/src/desktopTest/.../specsheet/SpecSheetContentTest.kt` (add cases)

**Interfaces:**
- Consumes: `PlayerError` (incl. `OutputFormatUnsupported`), `PlaybackNotice`, `OutputRoute`, `OutputEngine`, `PlaybackDiagnostics`, `NoPlaybackDiagnostics`, and `DesktopAppGraph.playbackDiagnostics` (Tasks 2, 3, 7). Also `FakePlatformPlayer` (`ui/components` desktopTest, `nowplaying` package; it already exists from PR #39. If that PR isn't merged yet, rebase first).
- Produces: `fun playerErrorMessage(error: PlayerError): String`, `fun noticeMessage(notice: PlaybackNotice): String`, `fun formatOutputRoute(route: OutputRoute): String`, `val LocalPlaybackDiagnostics`, `NowPlayingState(errorMessage, notice, outputLine)`, and `SpecSheetContent(…, outputLine: String? = null)`.

- [ ] **Step 1: Write the failing message tests (commonTest)**

```kotlin
package com.clayworks.kiln.ui.components.playback

import com.clayworks.kiln.audio.playback.OutputEngine
import com.clayworks.kiln.audio.playback.OutputRoute
import com.clayworks.kiln.audio.playback.PlaybackNotice
import com.clayworks.kiln.audio.playback.PlayerError
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackMessagesTest {

    @Test
    fun `WASAPI route that resamples`() {
        val r = OutputRoute(OutputEngine.WasapiShared, 48_000, "32-bit float mix", 44_100, 24)
        assertEquals("Output · WASAPI shared · 48 kHz 32-bit float mix · resampled from 44.1 kHz", formatOutputRoute(r))
    }

    @Test
    fun `WASAPI route at the native rate`() {
        val r = OutputRoute(OutputEngine.WasapiShared, 48_000, "32-bit float mix", 48_000, 16)
        assertEquals("Output · WASAPI shared · 48 kHz 32-bit float mix", formatOutputRoute(r))
    }

    @Test
    fun `Java Sound fallback route`() {
        val r = OutputRoute(OutputEngine.JavaSoundFallback, 44_100, "16-bit PCM", 44_100, 16)
        assertEquals("Output · Java Sound fallback · 44.1 kHz 16-bit PCM", formatOutputRoute(r))
    }

    @Test
    fun `rates format without trailing zeros`() {
        val r = OutputRoute(OutputEngine.WasapiShared, 176_400, "32-bit float mix", 88_200, 24)
        assertEquals("Output · WASAPI shared · 176.4 kHz 32-bit float mix · resampled from 88.2 kHz", formatOutputRoute(r))
    }

    @Test
    fun `output format rejection names the layout`() {
        assertEquals(
            "The output can't take 24-bit / 44.1 kHz stereo",
            playerErrorMessage(PlayerError.OutputFormatUnsupported(44_100, 24, 2, "x")),
        )
    }

    @Test
    fun `codec rejection names the codec`() {
        assertEquals("Can't play this file: AAC isn't supported yet", playerErrorMessage(PlayerError.FormatUnsupported("AAC")))
    }

    @Test
    fun `device error keeps its reason`() {
        assertEquals("Output device unavailable: device in use", playerErrorMessage(PlayerError.DeviceUnavailable("device in use")))
    }

    @Test
    fun `notices`() {
        assertEquals(
            "Skipped 2morrow: Can't play this file: AAC isn't supported yet",
            noticeMessage(PlaybackNotice.Skipped("2morrow", PlayerError.FormatUnsupported("AAC"))),
        )
        assertEquals("Stopped after 3 unplayable tracks in a row", noticeMessage(PlaybackNotice.GaveUp(3)))
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail** (`:ui:components:desktopTest --tests "*PlaybackMessagesTest"`: unresolved references).

- [ ] **Step 3: Implement `PlaybackMessages.kt`**

```kotlin
// User-facing wording for playback errors, skip notices, and the output-path readout (#37).
// Pure functions, shared by Now Playing and the Spec Sheet.

package com.clayworks.kiln.ui.components.playback

import com.clayworks.kiln.audio.playback.OutputEngine
import com.clayworks.kiln.audio.playback.OutputRoute
import com.clayworks.kiln.audio.playback.PlaybackNotice
import com.clayworks.kiln.audio.playback.PlayerError

fun playerErrorMessage(error: PlayerError): String = when (error) {
    is PlayerError.FormatUnsupported -> "Can't play this file: ${error.codec} isn't supported yet"
    is PlayerError.OutputFormatUnsupported ->
        "The output can't take ${error.bitDepth}-bit / ${khz(error.sampleRateHz)} kHz ${channelsWord(error.channels)}"
    is PlayerError.DeviceUnavailable -> "Output device unavailable: ${error.reason}"
    is PlayerError.DecodeFailed -> "Couldn't decode this file"
    is PlayerError.IoError -> "Couldn't read this file"
    is PlayerError.Internal -> "Playback error: ${error.message}"
}

fun noticeMessage(notice: PlaybackNotice): String = when (notice) {
    is PlaybackNotice.Skipped -> "Skipped ${notice.title}: ${playerErrorMessage(notice.cause)}"
    is PlaybackNotice.GaveUp -> "Stopped after ${notice.consecutiveFailures} unplayable tracks in a row"
}

fun formatOutputRoute(route: OutputRoute): String = buildString {
    append("Output · ")
    append(
        when (route.engine) {
            OutputEngine.WasapiShared -> "WASAPI shared"
            OutputEngine.JavaSoundFallback -> "Java Sound fallback"
        },
    )
    append(" · ")
    route.deviceRateHz?.let { append("${khz(it)} kHz ") }
    append(route.deviceFormat)
    if (route.resampled) append(" · resampled from ${khz(route.sourceRateHz)} kHz")
}

/** 44100 → "44.1", 48000 → "48", 176400 → "176.4". Pure Kotlin (no String.format). */
private fun khz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else "${hz / 1000}.${(hz % 1000) / 100}"

private fun channelsWord(channels: Int): String = when (channels) {
    1 -> "mono"
    2 -> "stereo"
    else -> "$channels-channel"
}
```

- [ ] **Step 4: Run the message tests and confirm they pass.**

- [ ] **Step 5: Write the failing Now Playing UI test**

`ui/components/src/desktopTest/.../nowplaying/NowPlayingDiagnosticsTest.kt`:

```kotlin
// Now Playing shows the error line, skip notices, and the output readout (#37).

package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.clayworks.kiln.audio.playback.OutputEngine
import com.clayworks.kiln.audio.playback.OutputRoute
import com.clayworks.kiln.audio.playback.PlaybackDiagnostics
import com.clayworks.kiln.audio.playback.PlaybackNotice
import com.clayworks.kiln.audio.playback.PlayerError
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SourceId
import com.clayworks.kiln.ui.components.specsheet.LocalPlaybackDiagnostics
import com.clayworks.kiln.ui.components.specsheet.LocalPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import kotlin.test.Test

class NowPlayingDiagnosticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val track = MediaItem(
        itemId = ItemId("7593"), sourceId = SourceId("local"), kind = MediaItem.Kind.Track,
        title = "2morrow", subtitle = "Artist", durationMs = 251_000L,
    )

    private class FakeDiagnostics(route: OutputRoute?) : PlaybackDiagnostics {
        override val outputRoute = MutableStateFlow(route)
        // replay = 1 so the notice test can't race the LaunchedEffect collector's subscription.
        override val notices = MutableSharedFlow<PlaybackNotice>(replay = 1)
    }

    private fun show(state: PlayerState, diagnostics: FakeDiagnostics) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalPlayer provides FakePlatformPlayer(initialItem = track, initialState = state),
                LocalPlaybackDiagnostics provides diagnostics,
            ) {
                NowPlayingTab().Content()
            }
        }
    }

    @Test
    fun `an error state shows its message`() {
        show(PlayerState.Error(PlayerError.OutputFormatUnsupported(44_100, 24, 2, "x")), FakeDiagnostics(null))
        composeRule.onNodeWithText("The output can't take 24-bit / 44.1 kHz stereo").assertIsDisplayed()
    }

    @Test
    fun `the output route is shown while playing`() {
        val route = OutputRoute(OutputEngine.WasapiShared, 48_000, "32-bit float mix", 44_100, 24)
        show(PlayerState.Ready(isPlaying = true), FakeDiagnostics(route))
        composeRule.onNodeWithText("Output · WASAPI shared · 48 kHz 32-bit float mix · resampled from 44.1 kHz").assertIsDisplayed()
    }

    @Test
    fun `a skip notice appears`() {
        val diagnostics = FakeDiagnostics(null)
        show(PlayerState.Ready(isPlaying = true), diagnostics)
        composeRule.mainClock.autoAdvance = false
        diagnostics.notices.tryEmit(PlaybackNotice.Skipped("Hidden Track", PlayerError.FormatUnsupported("AAC")))
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Skipped Hidden Track: Can't play this file: AAC isn't supported yet").assertIsDisplayed()
    }
}
```

- [ ] **Step 6: Run the test and confirm it fails** (`LocalPlaybackDiagnostics` unresolved).

- [ ] **Step 7: Implement the Now Playing changes**

`LocalLibraryStats.kt`, after `LocalPlayer`:

```kotlin
/**
 * Supplies the output-path side-channel (#37). Defaults to [NoPlaybackDiagnostics] so platforms
 * with nothing to report (Android today) needn't provide it.
 */
val LocalPlaybackDiagnostics = staticCompositionLocalOf<PlaybackDiagnostics> { NoPlaybackDiagnostics }
```

(imports: `com.clayworks.kiln.audio.playback.NoPlaybackDiagnostics`, `com.clayworks.kiln.audio.playback.PlaybackDiagnostics`)

`NowPlayingContent.kt`: extend the state and render three optional lines under the subtitle (inside the title `Column`, after the `item.subtitle?.let { … }` block):

```kotlin
data class NowPlayingState(
    val playerState: PlayerState,
    val currentItem: MediaItem?,
    val positionMs: Long,
    val durationMs: Long,
    val errorMessage: String? = null,
    val notice: String? = null,
    val outputLine: String? = null,
)
```

```kotlin
            state.errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            state.notice?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.outputLine?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

(import `androidx.compose.ui.text.font.FontFamily`)

`NowPlayingTab.kt`, in `NowPlayingHomeScreen.Content()`: after `positionMs`:

```kotlin
        val diagnostics = LocalPlaybackDiagnostics.current
        val route by diagnostics.outputRoute.collectAsState()
        var notice by remember { mutableStateOf<PlaybackNotice?>(null) }
        LaunchedEffect(diagnostics) {
            diagnostics.notices.collectLatest { n ->
                notice = n
                delay(NOTICE_VISIBLE_MS)
                notice = null
            }
        }
```

   Add to the `NowPlayingState(...)` construction:

```kotlin
            errorMessage = (playerState as? PlayerState.Error)?.let { playerErrorMessage(it.cause) },
            notice = notice?.let(::noticeMessage),
            outputLine = route?.let(::formatOutputRoute),
```

   File-level: `private const val NOTICE_VISIBLE_MS = 4_000L`. Imports: `androidx.compose.runtime.LaunchedEffect`, `mutableStateOf`, `remember`, `setValue`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.flow.collectLatest`, `com.clayworks.kiln.audio.playback.PlaybackNotice`, `com.clayworks.kiln.ui.components.playback.formatOutputRoute`, `…playback.noticeMessage`, `…playback.playerErrorMessage`, `com.clayworks.kiln.ui.components.specsheet.LocalPlaybackDiagnostics`.

- [ ] **Step 8: Run the Now Playing tests** (`NowPlayingDiagnosticsTest` 3 plus `NowPlayingNavigationTest`); all pass.

- [ ] **Step 9: Spec Sheet output row (test first)**

Add to `SpecSheetContentTest.kt`. It uses the file's existing `hiresFlacEntry()`/`sampleAggregate()` fixtures and its `assertExists()` convention (the body scrolls, so `assertIsDisplayed()` is unreliable there):

```kotlin
    @Test
    fun loaded_with_output_line_shows_playback_section() {
        composeRule.setContent {
            SpecSheetContent(
                state = SpecSheetUiState.Loaded(entry = hiresFlacEntry(), aggregate = sampleAggregate()),
                onBack = {},
                outputLine = "Output · WASAPI shared · 48 kHz 32-bit float mix",
            )
        }
        composeRule.onNodeWithText("Playback").assertExists()
        composeRule.onNodeWithText("Output · WASAPI shared · 48 kHz 32-bit float mix").assertExists()
    }

    @Test
    fun loaded_without_output_line_has_no_playback_section() {
        composeRule.setContent {
            SpecSheetContent(
                state = SpecSheetUiState.Loaded(entry = hiresFlacEntry(), aggregate = sampleAggregate()),
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Playback").assertDoesNotExist()
    }
```

Implement it. `SpecSheetContent` gets the parameter `outputLine: String? = null` and passes it to `LoadedBody(entry, aggregate, outputLine)`. In `LoadedBody`, after the "File" section:

```kotlin
        outputLine?.let { line ->
            SpecSection(title = "Playback") {
                LabeledRow("Output", line)
            }
        }
```

`SpecSheetScreen.Content()`: before `SpecSheetContent(...)`:

```kotlin
        val player = LocalPlayer.current
        val diagnostics = LocalPlaybackDiagnostics.current
        val queue by player.queue.collectAsState()
        val route by diagnostics.outputRoute.collectAsState()
        // Only when this sheet is for the track actually playing: the route describes playback, not the file.
        val outputLine = route?.takeIf { queue.currentItem?.itemId?.value == trackId }?.let(::formatOutputRoute)
```

   Then call `SpecSheetContent(state = state, onBack = { navigator.pop() }, outputLine = outputLine)` (import `…playback.formatOutputRoute`).

   `LabeledRow` renders "Output" as a label plus the value in a separate `Text`, so the test's full-string lookup matches the value `Text`.

- [ ] **Step 10: Provide it on desktop**

`Main.kt`, in the `CompositionLocalProvider(...)` around `KilnHomeScreen`, add `LocalPlaybackDiagnostics provides graph.playbackDiagnostics,` (import `com.clayworks.kiln.ui.components.specsheet.LocalPlaybackDiagnostics`). Android is unchanged (it gets the default).

- [ ] **Step 11: Canonical build, then commit**

```bash
git add ui/components/src app-desktop/src
git commit -F - <<'EOF'
feat(ui): playback error line, skip notices, output readout (#37 task 8)

Now Playing shows the error for PlayerState.Error, each skip notice for
4 s, and the output path (e.g. "Output · WASAPI shared · 48 kHz 32-bit
float mix · resampled from 44.1 kHz"). The Spec Sheet gets a Playback >
Output row when it's showing the track that's playing. Wording lives in
pure, tested functions (playerErrorMessage, noticeMessage,
formatOutputRoute). LocalPlaybackDiagnostics defaults to
NoPlaybackDiagnostics, so Android needs no change.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 9: Cortex smoke, docs, and close-out

**Files:**
- Modify: `CLAUDE.md`
- Create: `docs/sessions/<date>-session-<N>-37-smoke.md` (or fold the smoke into the session handoff)

- [ ] **Step 1: Run the full suite, device tests included, on Cortex**

Run the canonical build. Then confirm `WasapiFormatProbeTest` and `WasapiDeviceTest` **ran (not skipped)** in their XML reports.

- [ ] **Step 2: Manual smoke (Clay drives, CC observes the log and DB)**

Launch `.\gradlew :app-desktop:run *> "$env:TEMP\kiln-37-smoke.log"` in the background, then walk through this checklist:

| # | Check | Pass criteria |
|---|---|---|
| 1 | *The 27 Club* (16/44.1) | Plays; output line shows WASAPI shared |
| 2 | *2morrow* (id 7593, 24/44.1) | **Plays** (the #37 failure) |
| 3 | A 24/96 and a 24/192 track (`SELECT id,title FROM track WHERE bit_depth=24 AND sample_rate_hz IN (96000,192000) LIMIT 4`) | Both play; "resampled from …" shown if the device mix differs |
| 4 | The 32-bit track (`SELECT id,title FROM track WHERE bit_depth=32`) | Plays |
| 5 | A queue that runs into an AAC (`codec='AAC'`) | Skip notice shown, next track plays |
| 6 | Three AACs in a row | Stops with "Stopped after 3 unplayable tracks in a row" |
| 7 | Pause/resume mid-24-bit; seek; Next/Previous | Responsive; resume continues where it paused |
| 8 | RG Off/Track/Album mid-24-bit | Audible level change |
| 9 | Spec Sheet of the playing track | Playback > Output row matches Now Playing |

- [ ] **Step 3: Update `CLAUDE.md` gotchas**

- Replace the "Java Sound on Windows only exposes 8/16-bit PCM" bullet's action clause ("…until #37 adds a 16-bit fallback…") with: "Since #37 the desktop player outputs through WASAPI shared mode, and Java Sound is only the fallback when COM/WASAPI can't initialize."
- In "The desktop player is a command/actor" bullet, replace "The ONLY cross-thread touch of the line is the write-interrupt seam: `stop()`+`flush()` for track-change and teardown, `stop()` only for pause." with "The ONLY cross-thread touch of the line is `PcmOutputLine.interrupt(discard)` (discard = track change/teardown; false = pause)."
- Add a bullet: "**WASAPI (#37): all COM calls on the line's opening thread (the actor's `kiln-audio-out`); `interrupt()` makes none.** `WasapiClient` re-resolves the default endpoint on every open. Shared mode uses `AUTOCONVERTPCM | SRC_DEFAULT_QUALITY` with a 200 ms buffer. 24-bit: <probe result: written as 24-in-24 | repacked 24-in-32>. Device-backed tests (`WasapiFormatProbeTest`, `WasapiDeviceTest`) skip on CI (no endpoint) and must be run on Cortex before merging player-output changes. Vtable slots live in one place (`ComBinding.kt` `Vt`); a wrong slot crashes the JVM, so re-run the probe after touching them."

- [ ] **Step 4: Commit the docs**

`docs(claude-md): WASAPI output gotchas; Java Sound now fallback-only (#37 task 9)`.

- [ ] **Step 5: Close-out (after the smoke passes)**

- Comment the smoke results on #37 and close it as completed.
- File the slice-2 issue, "Desktop: WASAPI exclusive mode (bit-perfect)", linking the decision doc and noting `IAudioClient.IsFormatSupported(EXCLUSIVE)` plus per-track format negotiation as its core.
- Push the branch and open the PR (no closing keywords in the body except an intentional `Fixes #37`).
