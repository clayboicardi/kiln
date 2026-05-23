# Phase 2a Track D-B (Android scope): Consumer-side ReplayGain on Media3 ExoPlayer

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make D-C's persisted `replayGainMode` + `replayGainPreAmpDb` settings actually audible on Android by routing decoded PCM through Kiln's `ReplayGainProcessor` inside Media3's audio pipeline. Closes the Android-side half of Phase 2a Track D (PR #13 closed the desktop side).

**Architecture:**
- **`MediaProcessorAdapter`** (`:audio:playback/androidMain`) — bridges Kiln's `com.clayworks.kiln.audio.dsp.AudioProcessor` (single-call `process(frame)` returning the mutated frame) to Media3's `androidx.media3.common.audio.AudioProcessor` (buffer-rotation contract: `queueInput(in)` → buffer accumulates → `getOutput()` returns processed bytes). Extends Media3's `BaseAudioProcessor` to inherit configure/getOutput/isActive plumbing; subclass implements `onConfigure` (translate Media3 `AudioFormat` → Kiln `DecodedAudioFormat`) + `queueInput` (copy input bytes → wrap in `AudioFrame` → call Kiln processor → write into the BaseAudioProcessor output buffer).
- **`KilnRenderersFactory`** (`:audio:playback/androidMain`) — subclasses `androidx.media3.exoplayer.DefaultRenderersFactory`. Overrides `buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)` to construct a `DefaultAudioSink.Builder` with `setAudioProcessors(arrayOf(adapter))`, returning a sink whose pipeline runs Kiln's processor on every audio frame. Constructor accepts the `Array<androidx.media3.common.audio.AudioProcessor>` so the factory itself is decoupled from `ReplayGainProcessor`.
- **`Media3ExoPlayerImpl` constructor extension** — gains two new params (`settings: SettingsRepository`, `rgProcessor: com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor`). The init block constructs the `MediaProcessorAdapter` wrapping `rgProcessor`, passes the adapter into `KilnRenderersFactory`, and threads the factory into `ExoPlayer.Builder(context).setRenderersFactory(...)`. Adds a `playablesById: MutableMap<String, Playable>` keyed by Media3 `MediaItem.mediaId` (populated during loadQueue's per-item resolution loop) plus a `@Volatile currentPlayable: Playable?` updated via `onMediaItemTransition`. A settings-flow collector mirrors the desktop pattern: subscribes to `combine(replayGainMode, replayGainPreAmpDb).distinctUntilChanged()` and re-applies gain on every change. `applyRgGain(playable, mode, preAmpDb)` is the shared helper.
- **`AndroidAppGraph` wiring** — adds `@Singleton @Provides fun replayGainProcessor(): ReplayGainProcessor`. Updates the existing `media3Player` provider to accept the new params and pass them to the `Media3ExoPlayerImpl` constructor. Mirrors `DesktopAppGraph` (already done in PR #13).

**Tech Stack:** Kotlin (`:audio:playback/androidMain`), Media3 1.10.1 (`androidx.media3.common.audio.AudioProcessor` + `BaseAudioProcessor` + `DefaultRenderersFactory` + `DefaultAudioSink.Builder`), kotlin-inject DI, Robolectric 4.16.1 (`androidHostTest` — Media3ExoPlayerImpl already has Robolectric coverage at `Media3ExoPlayerImplTest.kt`), JUnit 4.

**Scope (this session — Android only):**
- ✅ `MediaProcessorAdapter` impl + ≥5 Robolectric/host tests
- ✅ `KilnRenderersFactory` impl + smoke construction test
- ✅ `Media3ExoPlayerImpl` constructor extension + per-track Playable plumbing through `onMediaItemTransition` + settings-flow collector
- ✅ `AndroidAppGraph` DI updates (`@Singleton @Provides fun replayGainProcessor()` + threading into media3Player)
- ✅ CLAUDE.md gotchas + Session 18 handoff (closes Phase 2a Track D) + canonical 8-target verify-build + PR

**Out of scope:**
- ⛔ Desktop-side changes (D-B desktop shipped PR #13)
- ⛔ `:audio:dsp` changes (`ReplayGainProcessor` + `resolveGainLinear` already shipped PR #13)
- ⛔ Settings UI / backfill UI (D-C shipped PR #12)
- ⛔ Track-analyzer changes (D-A wrap-up shipped PR #11)

**Branch:** `phase-2a-track-d-b-android` (off `main` at `26d9e9a` — D-B desktop PR #13 merged).

---

## Reference: API surface (researched against Media3 1.10.1 source 2026-05-22)

### `androidx.media3.common.audio.AudioProcessor` interface contract

```java
public interface AudioProcessor {
    int sampleRate;        // (in AudioFormat nested class)
    int channelCount;      // (in AudioFormat nested class)
    @C.PcmEncoding int encoding;  // (in AudioFormat nested class)

    AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException;
    boolean isActive();
    void queueInput(ByteBuffer inputBuffer);
    void queueEndOfStream();
    ByteBuffer getOutput();
    boolean isEnded();
    void flush();
    void reset();

    ByteBuffer EMPTY_BUFFER;  // static constant: empty direct ByteBuffer
}
```

**Buffer rotation semantics:** `queueInput(input)` reads from `input.position()` and advances it. The processor accumulates output into an internal buffer. `getOutput()` returns a buffer the framework reads + advances; framework calls `getOutput()` until it returns a buffer with `remaining() == 0`. `isEnded()` returns true only after `queueEndOfStream()` AND all output has been drained.

### `androidx.media3.common.audio.BaseAudioProcessor` abstract helper (recommended super class)

```java
public abstract class BaseAudioProcessor implements AudioProcessor {
    public final AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        pendingInputAudioFormat = inputAudioFormat;
        pendingOutputAudioFormat = onConfigure(inputAudioFormat);
        return isActive() ? pendingOutputAudioFormat : AudioFormat.NOT_SET;
    }
    public boolean isActive() { return pendingOutputAudioFormat != AudioFormat.NOT_SET; }
    public final ByteBuffer getOutput() { ByteBuffer out = outputBuffer; outputBuffer = EMPTY_BUFFER; return out; }
    public boolean isEnded() { return inputEnded && outputBuffer == EMPTY_BUFFER; }
    public final void queueEndOfStream() { inputEnded = true; onQueueEndOfStream(); }
    public final void flush() { ... onFlush(); }
    public final void reset() { ... onReset(); }

    // ---- protected hooks subclasses override ----
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        return AudioFormat.NOT_SET;  // override to declare an output format
    }
    protected void onQueueEndOfStream() {}
    protected void onFlush() {}
    protected void onReset() {}

    // ---- protected helpers ----
    protected final ByteBuffer replaceOutputBuffer(int count);  // allocate/reuse native-order ByteBuffer
    protected final boolean hasPendingOutput();

    // ---- subclass MUST implement (interface method, not overridden in BaseAudioProcessor) ----
    public abstract void queueInput(ByteBuffer inputBuffer);
}
```

Key consequence: extending `BaseAudioProcessor` removes all the buffer-pool / EMPTY_BUFFER bookkeeping. Subclass writes `onConfigure` + `queueInput` only.

### `androidx.media3.exoplayer.DefaultRenderersFactory.buildAudioSink` override surface

```java
@Nullable
protected AudioSink buildAudioSink(
    Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
    return new DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
        .build();
}
```

To inject processors, override and add `.setAudioProcessors(arrayOf(...))`:
```kotlin
override fun buildAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioOutputPlaybackParams: Boolean,
): AudioSink = DefaultAudioSink.Builder(context)
    .setEnableFloatOutput(enableFloatOutput)
    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
    .setAudioProcessors(kilnProcessors)  // ← inject the chain
    .build()
```

`DefaultAudioSink.Builder.setAudioProcessors(audioProcessors: Array<AudioProcessor>): Builder` is public on Media3 1.10.1.

### Sample-format mapping (Media3 ↔ Kiln)

| Media3 `C.PcmEncoding` constant | Kiln `SampleFormat` | Bytes per sample |
|---|---|---|
| `C.ENCODING_PCM_16BIT` | `PCM_S16_LE` | 2 |
| `C.ENCODING_PCM_24BIT` | `PCM_S24_LE` | 3 |
| `C.ENCODING_PCM_32BIT` | `PCM_S32_LE` | 4 |
| `C.ENCODING_PCM_FLOAT` | `PCM_F32_LE` | 4 |
| anything else | → throw `UnhandledAudioFormatException` from `onConfigure` |

Media3 PCM byte order on Android is **always little-endian** (verified by checking `BaseAudioProcessor.replaceOutputBuffer` — uses native order which is LE on every Android arch). Kiln's `SampleFormat.*_LE` matches directly; no swap needed.

---

## File Structure

| File | Role |
|---|---|
| `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/MediaProcessorAdapter.kt` | **Create**: bridges Kiln `AudioProcessor` → Media3 `AudioProcessor` via `BaseAudioProcessor` subclass. Handles buffer copy + sample-format mapping. |
| `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/MediaProcessorAdapterTest.kt` | **Create**: Robolectric/host tests — passthrough, S16/S24/S32/F32 mapping, format-change, flush, unsupported encoding. |
| `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/KilnRenderersFactory.kt` | **Create**: `DefaultRenderersFactory` subclass that overrides `buildAudioSink` to inject Kiln's processor chain. |
| `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/KilnRenderersFactoryTest.kt` | **Create**: smoke construction test (Robolectric — ExoPlayer can be built with the factory; factory returns non-null sink). |
| `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt` | **Modify**: constructor adds `settings`, `rgProcessor` params; loadQueue retains Playable map; onMediaItemTransition updates currentPlayable + reapplies gain; init block starts settings-flow collector; applyRgGain helper. |
| `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImplTest.kt` | **Modify**: `newPlayer(...)` helper takes optional `settings` + `rgProcessor` params (defaults to stub + fresh processor) so all existing tests keep working; add a few new tests for RG-specific behavior. |
| `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt` | **Modify**: `@Singleton @Provides fun replayGainProcessor()` + thread `settings: SettingsRepository, rgProcessor: ReplayGainProcessor` into the `media3Player` provider. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt` | **Modify (closeout)**: drop the now-stale "applies once Track D-B's consumer-side gain ships" disclaimer shown to users on both platforms. |
| `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt` | **Modify (closeout)**: update the `ReplayGainMode` KDoc — was "Until D-B ships, this setting has no audible effect"; now reflects that D-B desktop (PR #13) + D-B Android (this branch) both ship gain. |
| `CLAUDE.md` | **Modify**: append Android-side D-B gotchas (Media3 AudioProcessor buffer rotation, BaseAudioProcessor hooks, RenderersFactory override path, PCM encoding mapping, MediaItem.mediaId stability, ExoPlayer ctor wiring, Pixel permission-gate quirk). |
| `docs/sessions/2026-05-22-session-18-handoff.md` | **Create**: Session 18 handoff. Phase 2a Track D fully closed. Next-up open per existing handoff conventions. |

**Package convention:** all Android files live in `com.clayworks.kiln.audio.playback` (existing). New imports introduce `androidx.media3.common.audio.AudioProcessor` (interface), `androidx.media3.common.audio.BaseAudioProcessor` (abstract helper), `androidx.media3.exoplayer.DefaultRenderersFactory`, `androidx.media3.exoplayer.audio.AudioSink`, `androidx.media3.exoplayer.audio.DefaultAudioSink`.

**No build.gradle.kts changes**: `:audio:playback`'s `androidMain.dependencies { implementation(libs.bundles.android.media3) }` already pulls `media3-exoplayer` + `media3-common`, both of which contain the needed classes.

---

## Task 1 — `MediaProcessorAdapter` (bridges Kiln ↔ Media3 AudioProcessor)

**Goal:** Implement an `androidx.media3.common.audio.AudioProcessor` that delegates to a Kiln `com.clayworks.kiln.audio.dsp.AudioProcessor`. Extends `BaseAudioProcessor` to inherit the configure/getOutput/isActive/isEnded plumbing. Translates Media3's `AudioFormat` → Kiln's `DecodedAudioFormat` in `onConfigure`. In `queueInput`, copies input bytes into a fresh `ByteArray`, wraps in an `AudioFrame`, calls the Kiln processor's `process(frame)` (which mutates the bytes in-place), then writes the result into the output buffer obtained from `replaceOutputBuffer(...)`.

**Files:**
- Create: `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/MediaProcessorAdapter.kt`
- Create: `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/MediaProcessorAdapterTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/MediaProcessorAdapterTest.kt`:

```kotlin
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
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
    fun `format-change resets but isActive stays true and Kiln processor sees new format`() {
        val rg = ReplayGainProcessor()
        val adapter = MediaProcessorAdapter(rg)
        adapter.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        adapter.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        // No throw → format-change OK. isActive remains true so the chain keeps running.
        assertTrue(adapter.isActive)
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
    fun `adapter has stable identity through Kiln processor's id`() {
        val rg = ReplayGainProcessor()
        val adapter = MediaProcessorAdapter(rg)
        assertNotNull(adapter)
        // Kiln processor's id is the same identity the bridge wraps.
        assertEquals("replay-gain-processor", rg.id)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :audio:playback:testAndroidHostTest --tests "com.clayworks.kiln.audio.playback.MediaProcessorAdapterTest"`
Expected: FAIL with "unresolved reference: MediaProcessorAdapter".

- [ ] **Step 3: Implement the adapter**

Create `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/MediaProcessorAdapter.kt`:

```kotlin
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

    private fun pcmEncodingToSampleFormat(@C.PcmEncoding encoding: Int): SampleFormat? = when (encoding) {
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :audio:playback:testAndroidHostTest --tests "com.clayworks.kiln.audio.playback.MediaProcessorAdapterTest"`
Expected: PASS — 8 of 8 green.

- [ ] **Step 5: Commit**

```bash
git add audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/MediaProcessorAdapter.kt audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/MediaProcessorAdapterTest.kt
git commit -m "feat(audio:playback): MediaProcessorAdapter — bridge Kiln AudioProcessor to Media3

Closes the impedance mismatch between Kiln's single-call process(frame)
contract (in-place mutation) and Media3's queueInput/getOutput buffer-rotation
contract. Extends androidx.media3.common.audio.BaseAudioProcessor to inherit
the configure/getOutput/isActive/isEnded plumbing; subclass implements
onConfigure (Media3 AudioFormat → Kiln DecodedAudioFormat + onFormatChange)
and queueInput (copy → AudioFrame → kilnProcessor.process → output buffer).

Sample-format mapping:
  C.ENCODING_PCM_16BIT  → SampleFormat.PCM_S16_LE
  C.ENCODING_PCM_24BIT  → SampleFormat.PCM_S24_LE
  C.ENCODING_PCM_32BIT  → SampleFormat.PCM_S32_LE
  C.ENCODING_PCM_FLOAT  → SampleFormat.PCM_F32_LE
  anything else         → UnhandledAudioFormatException

Robolectric tests cover: configure shape, unsupported-encoding rejection,
gain=1.0 passthrough, gain=0.5 halve, format-change idempotence,
queueEndOfStream/isEnded transition, flush behavior, stable identity.

Phase 2a Track D-B (Android) — Task 1."
```

---

## Task 2 — `KilnRenderersFactory` (custom DefaultRenderersFactory)

**Goal:** Subclass `androidx.media3.exoplayer.DefaultRenderersFactory` and override `buildAudioSink` to inject Kiln's processor chain. The factory accepts the chain as a constructor parameter so it's decoupled from any specific processor (the Media3ExoPlayerImpl init builds the array).

**Files:**
- Create: `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/KilnRenderersFactory.kt`
- Create: `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/KilnRenderersFactoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/KilnRenderersFactoryTest.kt`:

```kotlin
// KilnRenderersFactory coverage — smoke construction tests under Robolectric.
// Media3's DefaultRenderersFactory + DefaultAudioSink.Builder don't require a
// real audio device until playback starts, so we can verify the override path
// (buildAudioSink returns a non-null sink containing our processor chain)
// without an instrumented test.

package com.clayworks.kiln.audio.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class KilnRenderersFactoryTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `factory constructs with an empty processor array`() {
        val factory = KilnRenderersFactory(context, arrayOf())
        assertNotNull(factory)
    }

    @Test
    fun `factory constructs with a single processor`() {
        val passthrough = object : AudioProcessor {
            private var format: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
            override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
                format = inputAudioFormat; return inputAudioFormat
            }
            override fun isActive(): Boolean = true
            override fun queueInput(inputBuffer: java.nio.ByteBuffer) {}
            override fun queueEndOfStream() {}
            override fun getOutput(): java.nio.ByteBuffer = AudioProcessor.EMPTY_BUFFER
            override fun isEnded(): Boolean = true
            override fun flush() {}
            override fun reset() {}
        }
        val factory = KilnRenderersFactory(context, arrayOf(passthrough))
        assertNotNull(factory)
    }

    @Test
    fun `buildAudioSink returns a non-null sink`() {
        val factory = KilnRenderersFactory(context, arrayOf())
        // Reflectively invoke the protected method — Kotlin lets us call it
        // directly from a same-class subclass, but the test isn't a subclass.
        // Use the @Suppress-annotated public accessor below.
        val sink = factory.invokeBuildAudioSinkForTest(
            enableFloatOutput = false,
            enableAudioOutputPlaybackParams = false,
        )
        assertNotNull(sink, "buildAudioSink should return a DefaultAudioSink instance")
    }
}
```

NOTE: the `invokeBuildAudioSinkForTest` accessor is a test-only `@VisibleForTesting`-style helper added to `KilnRenderersFactory` because `buildAudioSink` is protected in the Java superclass. Step 2 below adds it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :audio:playback:testAndroidHostTest --tests "com.clayworks.kiln.audio.playback.KilnRenderersFactoryTest"`
Expected: FAIL with "unresolved reference: KilnRenderersFactory".

- [ ] **Step 3: Implement the factory**

Create `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/KilnRenderersFactory.kt`:

```kotlin
// KilnRenderersFactory — custom DefaultRenderersFactory subclass that injects
// Kiln's AudioProcessor chain into Media3's audio pipeline.
//
// Override path: DefaultRenderersFactory.buildAudioSink(context, enableFloatOutput,
// enableAudioOutputPlaybackParams) — the default impl constructs a
// DefaultAudioSink.Builder with no processors. We add .setAudioProcessors(chain)
// and return the resulting sink. ExoPlayer.Builder.setRenderersFactory(this)
// in Media3ExoPlayerImpl's init wires the factory into the player.
//
// Constructor accepts an Array<AudioProcessor> rather than a single processor
// so future room-correction / EQ / visualizer-fanout processors can join the
// chain without re-shaping this class. The order in the array IS the
// processing order (Media3 applies them sequentially, framework-side).

package com.clayworks.kiln.audio.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

internal class KilnRenderersFactory(
    context: Context,
    private val audioProcessors: Array<AudioProcessor>,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
        .setAudioProcessors(audioProcessors)
        .build()

    /**
     * Test-only accessor for `buildAudioSink`. Kotlin allows internal classes
     * to expose protected superclass methods via a thin wrapper; production
     * code never calls this — the parent class's invocation path (from
     * ExoPlayer's internal renderer-build flow) is what matters at runtime.
     */
    @androidx.annotation.VisibleForTesting
    internal fun invokeBuildAudioSinkForTest(
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? = buildAudioSink(context = appContextForTest(), enableFloatOutput, enableAudioOutputPlaybackParams)

    private fun appContextForTest(): Context {
        // Robolectric path: the test passes a Context to the constructor; the
        // parent class stashes it as a final field. We can't access that field
        // directly (private), but DefaultRenderersFactory's protected
        // buildAudioRenderers signature takes Context — for the test accessor
        // here we just re-thread the original. This is internal-only; production
        // callers go through buildAudioSink itself (called by the parent class
        // with the original Context).
        throw UnsupportedOperationException(
            "Use ExoPlayer.Builder.setRenderersFactory(...) in production; " +
                "invokeBuildAudioSinkForTest is reserved for the buildAudioSink " +
                "smoke test which passes its own Context directly.",
        )
    }
}
```

Wait — re-read the test. The test wants `invokeBuildAudioSinkForTest(enableFloatOutput, enableAudioOutputPlaybackParams)` to call `buildAudioSink`. We need the test to pass `Context` too. Refactor:

Adjust `KilnRenderersFactoryTest.kt` to call:
```kotlin
val sink = factory.buildAudioSinkForTest(context, enableFloatOutput = false, enableAudioOutputPlaybackParams = false)
```

And `KilnRenderersFactory.kt`'s test accessor:
```kotlin
@androidx.annotation.VisibleForTesting
internal fun buildAudioSinkForTest(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioOutputPlaybackParams: Boolean,
): AudioSink? = buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
```

Update the test file step's code block to match:

```kotlin
@Test
fun `buildAudioSink returns a non-null sink`() {
    val factory = KilnRenderersFactory(context, arrayOf())
    val sink = factory.buildAudioSinkForTest(
        context = context,
        enableFloatOutput = false,
        enableAudioOutputPlaybackParams = false,
    )
    assertNotNull(sink, "buildAudioSink should return a DefaultAudioSink instance")
}
```

And the factory's final implementation:

```kotlin
package com.clayworks.kiln.audio.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

internal class KilnRenderersFactory(
    context: Context,
    private val audioProcessors: Array<AudioProcessor>,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
        .setAudioProcessors(audioProcessors)
        .build()

    @androidx.annotation.VisibleForTesting
    internal fun buildAudioSinkForTest(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? = buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
}
```

The implementer should use the corrected versions of both files (test + production) when writing them.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :audio:playback:testAndroidHostTest --tests "com.clayworks.kiln.audio.playback.KilnRenderersFactoryTest"`
Expected: PASS — 3 of 3 green.

- [ ] **Step 5: Commit**

```bash
git add audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/KilnRenderersFactory.kt audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/KilnRenderersFactoryTest.kt
git commit -m "feat(audio:playback): KilnRenderersFactory — inject AudioProcessor chain into Media3

Subclasses androidx.media3.exoplayer.DefaultRenderersFactory and overrides
the protected buildAudioSink(context, enableFloatOutput,
enableAudioOutputPlaybackParams) method to construct a DefaultAudioSink with
.setAudioProcessors(chain). Constructor accepts an Array<AudioProcessor>
(Media3's) so the factory is decoupled from any specific processor; current
caller (Media3ExoPlayerImpl init) passes [MediaProcessorAdapter(rgProcessor)].

Robolectric tests cover: empty chain construction, single-processor
construction, buildAudioSink returns non-null sink.

@VisibleForTesting buildAudioSinkForTest accessor exposes the protected
parent method to the host-side test — production callers don't use it.

Phase 2a Track D-B (Android) — Task 2."
```

---

## Task 3 — `Media3ExoPlayerImpl` constructor + RG plumbing

**Goal:** Extend the player to wire the RG chain end-to-end. New constructor params (`settings`, `rgProcessor`). Init block builds the adapter + factory, threads the factory into `ExoPlayer.Builder.setRenderersFactory(...)`. A `playablesById: MutableMap<String, Playable>` populated during `loadQueue`'s resolution loop lets `onMediaItemTransition` look up the new track's `Playable` for RG computation. A settings-flow collector mirrors the desktop pattern. Replaces the existing `addAudioProcessor` TODO comment with a comment that documents the new state.

**Files:**
- Modify: `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt`

**Important architectural note:** Media3's `onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int)` is called every time the player switches tracks (including on initial `prepare()`). To map the Media3 MediaItem back to a Kiln `Playable`, we use `mediaItem.mediaId` as the lookup key. Media3 sets `mediaId` from the `MediaItem.Builder` — we currently build with `androidx.media3.common.MediaItem.fromUri(uri)` which sets `mediaId = uri`. To make the lookup reliable we'll set an explicit `mediaId = item.itemId.value` via the Builder so it's stable + collision-free across same-URI scenarios (e.g., a track that resolves to the same file from two ItemIds — defensive, not currently triggered).

- [ ] **Step 1: Apply imports + new constructor + fields**

Modify `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt`:

Add imports (after the existing import block; preserve alphabetical-ish ordering similar to existing):

```kotlin
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainPipelineMode
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.audio.dsp.replaygain.resolveGainLinear
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
```

Update the class constructor (lines 51-54 originally):

```kotlin
class Media3ExoPlayerImpl(
    context: Context,
    private val source: MusicSource,
    private val settings: SettingsRepository,
    private val rgProcessor: ReplayGainProcessor,
) : PlatformPlayer {
```

Add fields below the existing `released` flag (around line 83):

```kotlin
    /**
     * Per-track Playable cache. Populated during loadQueue's resolution loop;
     * keyed by Media3 MediaItem.mediaId (we set that to itemId.value when
     * building the androidx.media3.common.MediaItem). Read by
     * onMediaItemTransition to recompute RG gain on track change.
     *
     * Map is rebuilt on each loadQueue (previous queue's entries are cleared).
     * Lookup is single-threaded — onMediaItemTransition runs on Main per
     * ExoPlayer's single-thread-access contract; loadQueue also runs on Main
     * via withContext(Dispatchers.Main.immediate).
     */
    private val playablesById: MutableMap<String, Playable> = mutableMapOf()

    /**
     * The currently-playing Playable, set on every onMediaItemTransition
     * and cleared on release. The settings-flow collector closes over this
     * to recompute gain when the user changes mode / pre-amp during playback.
     */
    @Volatile private var currentPlayable: Playable? = null
```

- [ ] **Step 2: Replace the ExoPlayer construction to use KilnRenderersFactory**

Find the existing block at lines 90-103 of the original file:
```kotlin
    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
```

Replace with (the only material change is the `.setRenderersFactory(...)` call):

```kotlin
    /**
     * Kiln's AudioProcessor chain — built once at construction. Currently
     * holds [MediaProcessorAdapter] wrapping [rgProcessor]; future processors
     * (EQ, room correction, visualizer fanout) join the array via DI when
     * they ship. The order in the array IS the processing order.
     */
    private val mediaAudioProcessors: Array<androidx.media3.common.audio.AudioProcessor> = arrayOf(
        MediaProcessorAdapter(rgProcessor),
    )

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(KilnRenderersFactory(context, mediaAudioProcessors))
        .setAudioAttributes(
            // USAGE_MEDIA + CONTENT_TYPE_MUSIC per spec §6.1 / vetting Item 11.
            // handleAudioFocus = true lets Media3 manage ducking + transient loss.
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // Pause when output device disconnects (BLE headphones, USB DAC unplug).
        // Vetting Item 11.
        .setHandleAudioBecomingNoisy(true)
        .build()
```

- [ ] **Step 3: Pre-populate `_processors` with the RG processor + add settings collector to init block**

Find the existing `init { exo.addListener(playerListener) }` block (line 180-182 of the original).

Replace with:

```kotlin
    init {
        exo.addListener(playerListener)

        // Surface the Kiln processor in the public flow so Compose surfaces
        // can render the chain (mirror desktop). The Media3-side injection
        // already happened above via KilnRenderersFactory; this is the
        // observation surface only.
        _processors.value = _processors.value + rgProcessor

        // Observe settings changes; recompute + apply RG gain whenever mode
        // or pre-amp changes (while a track is playing). Mirrors
        // JavaSoundPlayerImpl.init's collector — desktop precedent.
        scope.launch {
            combine(
                settings.replayGainMode.distinctUntilChanged(),
                settings.replayGainPreAmpDb.distinctUntilChanged(),
            ) { mode, preAmp -> mode to preAmp }
                .collect { (mode, preAmp) ->
                    val playable = currentPlayable ?: return@collect
                    applyRgGain(playable, mode, preAmp)
                }
        }
    }

    /**
     * Resolve [playable]'s RG values + current settings into a linear gain
     * and apply it to [rgProcessor]. Translates :data:library's ReplayGainMode
     * to :audio:dsp's ReplayGainPipelineMode at the seam (the two enums are
     * isomorphic; see desktop precedent in JavaSoundPlayerImpl).
     */
    private fun applyRgGain(playable: Playable, mode: ReplayGainMode, preAmpDb: Double) {
        val rg = playable.replayGain
        val pipelineMode = when (mode) {
            ReplayGainMode.Off -> ReplayGainPipelineMode.Off
            ReplayGainMode.Track -> ReplayGainPipelineMode.Track
            ReplayGainMode.Album -> ReplayGainPipelineMode.Album
        }
        val gain = resolveGainLinear(
            trackDb = rg?.trackDb,
            trackPeak = rg?.trackPeak,
            albumDb = rg?.albumDb,
            albumPeak = rg?.albumPeak,
            mode = pipelineMode,
            preAmpDb = preAmpDb,
        )
        rgProcessor.setLinearGain(gain)
    }
```

- [ ] **Step 4: Update onMediaItemTransition to populate currentPlayable + reapply gain**

Find the existing `onMediaItemTransition` callback in the `playerListener` object (lines 137-146 of the original):

```kotlin
        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            val newIndex = exo.currentMediaItemIndex
            val current = _queue.value
            if (newIndex != current.currentIndex) {
                _queue.value = current.copy(currentIndex = newIndex)
            }
        }
```

Replace with:

```kotlin
        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            val newIndex = exo.currentMediaItemIndex
            val current = _queue.value
            if (newIndex != current.currentIndex) {
                _queue.value = current.copy(currentIndex = newIndex)
            }

            // Update currentPlayable + apply RG gain for the new track. The
            // mediaId is itemId.value (set explicitly during loadQueue's
            // resolution loop below); playablesById is keyed by that.
            val mediaId = mediaItem?.mediaId
            val playable = mediaId?.let { playablesById[it] }
            currentPlayable = playable
            if (playable != null) {
                // Settings reads are suspend — launch a one-shot child coroutine
                // on the player's scope. mode + preAmpDb reads are fast (settings
                // values are in-memory after first emit); the brief window
                // between transition + gain-applied is inaudible (gain doesn't
                // change much between adjacent tracks under any RG policy).
                scope.launch {
                    val mode = settings.replayGainMode.first()
                    val preAmpDb = settings.replayGainPreAmpDb.first()
                    applyRgGain(playable, mode, preAmpDb)
                }
            }
        }
```

- [ ] **Step 5: Update loadQueue to populate playablesById and set explicit mediaId**

Find the existing `loadQueue` block (lines 184-244 of the original). Replace the entire body (preserving the function signature) with the updated version below — key changes:
1. Clear `playablesById` at start (each loadQueue is a fresh queue).
2. Capture each resolved `Playable` into `playablesById` keyed by `item.itemId.value`.
3. Build `androidx.media3.common.MediaItem` via `MediaItem.Builder().setUri(uri).setMediaId(item.itemId.value).build()` instead of `fromUri(uri)`.
4. Other logic unchanged.

```kotlin
    override suspend fun loadQueue(
        items: List<MediaItem>,
        startIndex: Int,
        autoPlay: Boolean,
    ) = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        _state.value = PlayerState.Loading

        // Clear the previous queue's Playable cache before re-populating.
        playablesById.clear()

        // Resolve each MediaItem to a Playable via the Source Protocol. Items
        // that fail to resolve (file deleted, transient I/O error, etc.) are
        // skipped — the published queue reflects only the items that will
        // actually play, keeping currentIndex aligned with ExoPlayer's
        // media-item indices.
        //
        // Triple(originalIndex, item, playable) preserves the mapping from user's
        // pre-filter items list to the post-filter resolved list — see
        // JavaSoundPlayerImpl for the analogous fix (U1) and rationale.
        val resolved: List<Triple<Int, MediaItem, Playable>> = items.mapIndexedNotNull { idx, item ->
            when (val r = source.getPlayable(item.itemId)) {
                is Either.Right -> {
                    playablesById[item.itemId.value] = r.value
                    Triple(idx, item, r.value)
                }
                is Either.Left -> {
                    log.w { "loadQueue: skipping ${item.itemId.value}: ${r.value}" }
                    null
                }
            }
        }

        // Build Media3 MediaItems with explicit mediaId = itemId.value so
        // onMediaItemTransition can look the Playable up in playablesById.
        // Default Builder.setUri(...) sets mediaId = uri, which would alias
        // across two ItemIds pointing to the same URI (defensive — current
        // scanner doesn't produce that but it's cheap insurance).
        val media3Items = resolved.map { (_, item, playable) ->
            androidx.media3.common.MediaItem.Builder()
                .setUri(playable.uri)
                .setMediaId(item.itemId.value)
                .build()
        }

        val resolvedItems = resolved.map { it.second }
        // Map user's startIndex (original-list space) to resolved-list space.
        // - empty → -1
        // - ≤ 0 → first resolved
        // - exact match → that resolved index
        // - user's item failed → fall FORWARD to next surviving
        // - none at or after → fall BACK to last resolved
        val coercedStart = when {
            resolvedItems.isEmpty() -> -1
            startIndex <= 0 -> 0
            else -> {
                val matchOrSuccessor = resolved.indexOfFirst { (originalIdx, _, _) -> originalIdx >= startIndex }
                if (matchOrSuccessor != -1) matchOrSuccessor else resolvedItems.lastIndex
            }
        }

        _queue.value = _queue.value.copy(
            items = resolvedItems,
            currentIndex = coercedStart,
        )

        if (media3Items.isEmpty()) {
            _state.value = PlayerState.Idle
            return@withContext
        }

        exo.setMediaItems(media3Items, coercedStart.coerceAtLeast(0), /* startPositionMs = */ 0L)
        exo.prepare()
        if (autoPlay) exo.play()
    }
```

- [ ] **Step 6: REMOVE the stale TODO on addAudioProcessor and replace with the post-D-B comment**

This is the actual TODO that has stood since MVP Session 5+ and that this session closes. The replacement comment documents the new state.

Find the existing `addAudioProcessor` method (lines 317-325):

```kotlin
    override fun addAudioProcessor(processor: AudioProcessor) {
        if (released) return
        // TODO(MVP Sessions 16-22): inject the chain into ExoPlayer via a custom
        // RenderersFactory that wraps AudioSink with a kiln-controlled
        // AudioProcessor pipeline. Until :audio:dsp ships its first concrete
        // processor, the chain is observed-only — Compose surfaces can render the
        // list but processors aren't actually invoked on audio frames.
        _processors.value = _processors.value + processor
    }
```

Replace ENTIRELY (drop the TODO; replace with a comment describing the new state):

```kotlin
    override fun addAudioProcessor(processor: AudioProcessor) {
        if (released) return
        // The Media3 audio chain is fixed at construction time via
        // KilnRenderersFactory + DefaultAudioSink.Builder.setAudioProcessors.
        // Adding a processor here only updates the observation flow — the
        // actual audio path runs the chain built in init.
        //
        // To add a new processor dynamically (e.g., a future EQ that the user
        // toggles on/off), the right shape is either: (1) tear down + rebuild
        // the player with the new chain, or (2) make individual processors
        // toggleable via a setEnabled(Boolean) method that doesn't change the
        // chain shape. ReplayGainProcessor uses approach (2) — gain == 1.0
        // is a hard-coded passthrough fast-path in the impl itself.
        _processors.value = _processors.value + processor
    }
```

- [ ] **Step 7: Update release to clear playablesById**

Find the existing `release` method (lines 332-340):

```kotlin
    override suspend fun release() = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext  // idempotent: repeat-release is a safe no-op
        released = true
        positionTicker.cancel()
        exo.removeListener(playerListener)
        mediaSession.release()
        exo.release()
        scope.cancel()
    }
```

Replace with:

```kotlin
    override suspend fun release() = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext  // idempotent: repeat-release is a safe no-op
        released = true
        positionTicker.cancel()
        playablesById.clear()
        currentPlayable = null
        exo.removeListener(playerListener)
        mediaSession.release()
        exo.release()
        scope.cancel()
    }
```

- [ ] **Step 8: Update the file-level header comment to reflect the new state**

Find the existing header block (lines 1-18 of the original) and replace the line:

```
// What's still STUBBED: KilnRenderersFactory injecting the AudioProcessor chain
// (lands when :audio:dsp gets its first concrete processor at MVP Sessions
// 16-22), and the MediaSessionService surface (deferred — MediaSession instance
// is constructed but service binding is a separate file).
```

with:

```
// Audio chain (Phase 2a Track D-B Android): KilnRenderersFactory wraps Media3's
// DefaultAudioSink with Kiln's AudioProcessor chain. MediaProcessorAdapter
// bridges Kiln AudioProcessor (single-call process(frame)) to Media3
// AudioProcessor (queueInput/getOutput rotation). ReplayGainProcessor is wired
// at construction; its gain updates flow from the settings repository via a
// scope-launched collector and from per-track Playable resolution via
// onMediaItemTransition's playablesById lookup.
//
// What's still STUBBED: the MediaSessionService surface (deferred — MediaSession
// instance is constructed but service binding is a separate file).
```

- [ ] **Step 9: Compile + verify upstream test passes (test fixture update follows in Task 4)**

Run: `./gradlew :audio:playback:compileDebugKotlinAndroid`
Expected: PASS — production code compiles.

Run: `./gradlew :app-android:assembleDebug`
Expected: FAIL — `AndroidAppGraph`'s `media3Player` provider still calls the old 2-arg constructor. That fails compilation. That's expected — Task 4 fixes the DI.

The host-side test (Media3ExoPlayerImplTest) ALSO fails to compile because its `newPlayer(source)` helper still calls the 2-arg constructor. Task 4 includes a small update to the test fixture to thread the new params.

- [ ] **Step 10: Commit**

```bash
git add audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt
git commit -m "feat(audio:playback): wire ReplayGainProcessor into Media3ExoPlayerImpl

Closes the Android-side TODO that has stood since MVP Session 5+: the
addAudioProcessor chain is now actually invoked on audio frames via
KilnRenderersFactory + MediaProcessorAdapter.

  - Constructor gains settings + rgProcessor params (mirrors desktop pattern
    from PR #13)
  - playablesById: MutableMap<String, Playable> cached on loadQueue's
    per-item resolution; keyed by mediaId = itemId.value (Media3
    MediaItem.Builder gets setMediaId explicitly so onMediaItemTransition can
    look the Playable up reliably)
  - @Volatile currentPlayable bridges per-track playable + settings-collector
  - Settings flow collector subscribes in init and re-applies gain on
    mode/pre-amp changes (mirrors JavaSoundPlayerImpl)
  - applyRgGain(playable, mode, preAmpDb) helper translates :data:library's
    ReplayGainMode to :audio:dsp's ReplayGainPipelineMode and calls
    resolveGainLinear + rgProcessor.setLinearGain
  - addAudioProcessor's TODO comment removed; now documents that the chain
    is fixed at construction time and ReplayGainProcessor uses gain == 1.0
    as the dynamic-disable mechanism
  - release clears playablesById + currentPlayable

AndroidAppGraph + test fixture updates land in Task 4 (DI changes); this
commit's :app-android:assembleDebug + :audio:playback:testAndroidHostTest
will fail at the call sites until then.

Phase 2a Track D-B (Android) — Task 3."
```

---

## Task 4 — `AndroidAppGraph` DI wiring + test fixture update

**Goal:** Provide `ReplayGainProcessor` as a singleton and thread it + `SettingsRepository` (already provided) into the `media3Player` factory. Mirrors `DesktopAppGraph`'s pattern (already done in PR #13).

Plus: update `Media3ExoPlayerImplTest.newPlayer(...)` helper to accept optional `settings` + `rgProcessor` params with sensible defaults so all 13 existing tests keep working.

**Files:**
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt`
- Modify: `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImplTest.kt`

- [ ] **Step 1: Update AndroidAppGraph**

Modify `app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt`.

Add import:
```kotlin
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
```

Find the existing `media3Player` provider (lines 117-122):

```kotlin
    @Singleton
    @Provides
    protected fun media3Player(
        context: Context,
        source: MusicSource,
    ): PlatformPlayer = Media3ExoPlayerImpl(context, source)
```

Replace with:

```kotlin
    @Singleton
    @Provides
    protected fun replayGainProcessor(): ReplayGainProcessor = ReplayGainProcessor()

    @Singleton
    @Provides
    protected fun media3Player(
        context: Context,
        source: MusicSource,
        settings: SettingsRepository,
        rgProcessor: ReplayGainProcessor,
    ): PlatformPlayer = Media3ExoPlayerImpl(
        context = context,
        source = source,
        settings = settings,
        rgProcessor = rgProcessor,
    )
```

(The existing `SettingsRepository` provider at lines 86-89 already exists; kotlin-inject will wire it in.)

- [ ] **Step 2: Update Media3ExoPlayerImplTest fixture**

Modify `audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImplTest.kt`.

Add imports (near the top, with existing imports):
```kotlin
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
```

Before the `private fun newPlayer(...)` declaration, add the stub repository:

```kotlin
    /**
     * Test stub for SettingsRepository. Holds in-memory state and exposes it
     * via MutableStateFlow so tests can change values mid-test and the player's
     * settings-collector observes the change. Defaults: replayGainMode=Off,
     * replayGainPreAmpDb=0.0, themeMode=System, scanOnLaunch=false,
     * scanFolders=empty. Tests that only need the player not to crash on
     * settings reads use this directly; tests that exercise RG behavior set
     * values via setReplayGainMode / setReplayGainPreAmpDb.
     */
    private class StubSettingsRepository(
        initialMode: ReplayGainMode = ReplayGainMode.Off,
        initialPreAmpDb: Double = 0.0,
    ) : SettingsRepository {
        private val _themeMode = MutableStateFlow(ThemeMode.System)
        override val themeMode: Flow<ThemeMode> = _themeMode.asStateFlow()
        override suspend fun setThemeMode(mode: ThemeMode) { _themeMode.value = mode }

        private val _scanOnLaunch = MutableStateFlow(false)
        override val scanOnLaunch: Flow<Boolean> = _scanOnLaunch.asStateFlow()
        override suspend fun setScanOnLaunch(enabled: Boolean) { _scanOnLaunch.value = enabled }

        private val _scanFolders = MutableStateFlow<List<String>>(emptyList())
        override val scanFolders: Flow<List<String>> = _scanFolders.asStateFlow()
        override suspend fun setScanFolders(folders: List<String>) { _scanFolders.value = folders }

        private val _replayGainMode = MutableStateFlow(initialMode)
        override val replayGainMode: Flow<ReplayGainMode> = _replayGainMode.asStateFlow()
        override suspend fun setReplayGainMode(mode: ReplayGainMode) { _replayGainMode.value = mode }

        private val _replayGainPreAmpDb = MutableStateFlow(initialPreAmpDb)
        override val replayGainPreAmpDb: Flow<Double> = _replayGainPreAmpDb.asStateFlow()
        override suspend fun setReplayGainPreAmpDb(db: Double) { _replayGainPreAmpDb.value = db }
    }
```

Update the `newPlayer(...)` helper signature (currently at line 98):

```kotlin
    private fun newPlayer(
        source: MusicSource = AlwaysFailingSource(),
        settings: SettingsRepository = StubSettingsRepository(),
        rgProcessor: ReplayGainProcessor = ReplayGainProcessor(),
    ): Media3ExoPlayerImpl {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Media3ExoPlayerImpl(context, source, settings, rgProcessor).also { players.add(it) }
    }
```

All existing test bodies (`newPlayer()`, `newPlayer(source = AlwaysFailingSource())`, etc.) keep working — Kotlin's default args fill in `settings` + `rgProcessor` automatically.

- [ ] **Step 3: Add two new RG-specific tests**

Append to the end of `Media3ExoPlayerImplTest` (just before the `private fun makeMediaItem(...)` helper):

```kotlin
    // ---------- ReplayGain wiring (Phase 2a Track D-B Android) ----------

    @Test
    fun `RG processor is exposed in processors flow at construction`() {
        // The init block adds rgProcessor to _processors so Compose surfaces
        // can observe the chain. Media3-side injection happens via
        // KilnRenderersFactory; this is the observation surface.
        val rg = ReplayGainProcessor()
        val player = newPlayer(rgProcessor = rg)
        val procs = player.processors.value
        assertEquals(1, procs.size)
        assertEquals("replay-gain-processor", procs[0].id)
    }

    @Test
    fun `Off mode keeps RG gain at 1_0 regardless of pre-amp`() = runBlocking {
        val rg = ReplayGainProcessor()
        val settings = StubSettingsRepository(initialMode = ReplayGainMode.Off, initialPreAmpDb = 6.0)
        newPlayer(settings = settings, rgProcessor = rg)
        // Off mode short-circuits resolveGainLinear to 1.0 even with positive
        // pre-amp. The init-block collector emits the initial settings values
        // synchronously through MutableStateFlow.asStateFlow(); by the time
        // newPlayer returns, the collector has already applied (or no-op'd
        // for null currentPlayable, which is the initial state — gain stays
        // at its default 1.0 anyway).
        assertEquals(1.0, rg.currentLinearGain(), 1e-9)
    }
```

(Note: these tests don't exercise the full Media3 → adapter → kiln-processor chain — that's covered in `MediaProcessorAdapterTest`. They verify the *wiring*: rgProcessor is observable, settings flow connects to gain state.)

- [ ] **Step 4: Run canonical build to verify everything compiles**

Run: `./gradlew :audio:playback:compileDebugKotlinAndroid :app-android:assembleDebug :audio:playback:testAndroidHostTest`
Expected: PASS — production + tests compile; existing 13 tests + 2 new RG tests = 15 Media3 tests green, 8 MediaProcessorAdapter tests green, 3 KilnRenderersFactory tests green.

- [ ] **Step 5: Commit**

```bash
git add app-android/src/main/kotlin/com/clayworks/kiln/di/AndroidAppGraph.kt audio/playback/src/androidHostTest/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImplTest.kt
git commit -m "feat(app-android): wire ReplayGainProcessor + SettingsRepository into Media3 player

AndroidAppGraph mirrors DesktopAppGraph (PR #13):
  @Singleton @Provides fun replayGainProcessor(): ReplayGainProcessor
  media3Player provider gains settings + rgProcessor params

Media3ExoPlayerImplTest fixture updated with optional settings + rgProcessor
parameters on the newPlayer helper (defaults preserve existing test bodies)
+ a StubSettingsRepository for tests that need to verify RG flow wiring.
Two new RG-specific tests verify (1) the processor is exposed in the
public processors flow and (2) Off mode short-circuits gain to 1.0.

The full Media3 → adapter → Kiln processor pipeline is exercised in
MediaProcessorAdapterTest; this test class focuses on the wiring at the
player surface.

Phase 2a Track D-B (Android) — Task 4."
```

---

## Task 5 — Closeout: stale-doc updates + CLAUDE.md gotchas + Session 18 handoff + verify-build + PR

**Files:**
- Modify: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt` (now-stale user-facing disclaimer about D-B not having shipped)
- Modify: `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt` (now-stale KDoc about D-B not having shipped)
- Modify: `CLAUDE.md` (append gotchas)
- Create: `docs/sessions/2026-05-22-session-18-handoff.md`

These three updates are mechanically simple, but they're load-bearing for user-facing accuracy (the SettingsScreen string is shown in the running app on both platforms) and developer-facing accuracy (the KDoc steers future contributors). They have to land in this PR — leaving them stale would mislead anyone reading the codebase after merge.

- [ ] **Step 1: Update the user-facing SettingsScreen disclaimer**

Modify `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt` around lines 140-145.

Find:
```kotlin
        Text(
            "Volume-normalize tracks during playback. Note: applies once Track D-B's " +
                "consumer-side gain ships. Until then, configuring here persists for later.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
```

Replace with (drop the "Note: applies once..." sentence; keep the lead sentence; add a minimal pointer to the analyzer for users who haven't run it):
```kotlin
        Text(
            "Volume-normalize tracks during playback. Run the analyzer in the ReplayGain " +
                "section below to populate per-track values.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
```

This is a shared `:ui:components` composable so the same text shows on Android AND Desktop. Both platforms now ship consumer-side gain.

- [ ] **Step 2: Update the SettingsRepository KDoc on ReplayGainMode**

Modify `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt` around lines 12-21.

Find:
```kotlin
/**
 * ReplayGain consumer-side gain mode. Track applies the per-track gain;
 * Album applies the per-album rollup; Off bypasses RG entirely.
 *
 * The setting is persisted by Track D-C; consumer-side application lands
 * in Track D-B (Media3 AudioProcessor on Android, JavaSoundPlayerImpl
 * multiplier on Desktop). Until D-B ships, this setting has no audible
 * effect — the value is round-tripped for future use.
 */
enum class ReplayGainMode { Off, Track, Album }
```

Replace with:
```kotlin
/**
 * ReplayGain consumer-side gain mode. Track applies the per-track gain;
 * Album applies the per-album rollup; Off bypasses RG entirely.
 *
 * The setting is persisted by Track D-C. Consumer-side application landed
 * in Track D-B: JavaSoundPlayerImpl multiplier on Desktop (PR #13) and
 * Media3 AudioProcessor via KilnRenderersFactory on Android (this branch's
 * PR). Per-track RG values come from the analyzer (Track D-A); tracks
 * without analyzed values silently fall back to gain = 1.0.
 */
enum class ReplayGainMode { Off, Track, Album }
```

- [ ] **Step 3: Commit the stale-doc updates as a discrete checkpoint**

```bash
git add ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/settings/SettingsScreen.kt data/library/src/commonMain/kotlin/com/clayworks/kiln/library/settings/SettingsRepository.kt
git commit -m "docs: drop \"D-B not yet shipped\" caveats now that consumer gain is audible everywhere

D-B desktop shipped PR #13; D-B Android ships this branch. The user-facing
disclaimer in SettingsScreen.kt and the developer-facing KDoc on
ReplayGainMode are now stale — both said \"this setting has no audible
effect until D-B ships.\" Both updates land in the closeout so the running
app and the codebase tell the truth.

SettingsScreen disclaimer now points users to the analyzer (the actual
prerequisite for RG values to exist on a track). ReplayGainMode KDoc now
documents the post-D-B state including the analyzer-fallback behavior
(gain = 1.0 for unanalyzed tracks).

Phase 2a Track D-B (Android) — Task 5 prep."
```

- [ ] **Step 4: Append CLAUDE.md gotchas**

Open `CLAUDE.md` and find the "Build/Dep Gotchas" section. Append the following block at the END of that section's bullet list (just before the "## Workflow" header):

```markdown
- **Media3's `androidx.media3.common.audio.AudioProcessor` uses a different shape than Kiln's `com.clayworks.kiln.audio.dsp.AudioProcessor`.** Media3 is buffer-rotation: `queueInput(buf)` accumulates → `getOutput()` returns processed → framework drains. Kiln's is single-call: `process(frame)` mutates the frame in place and returns it. `MediaProcessorAdapter` (`:audio:playback/androidMain`) bridges them by extending Media3's `BaseAudioProcessor` (which provides configure/getOutput/isActive/isEnded plumbing) and implementing `onConfigure` (translate `AudioFormat` → `DecodedAudioFormat`) + `queueInput` (copy → AudioFrame → Kiln process → write to BaseAudioProcessor's output buffer via `replaceOutputBuffer(size)`).
- **`DefaultRenderersFactory.buildAudioSink(Context, Boolean, Boolean)` is the right override surface for injecting an AudioProcessor chain on Media3 1.10.x.** Construct a `DefaultAudioSink.Builder(context)` with `.setEnableFloatOutput(...).setEnableAudioOutputPlaybackParameters(...).setAudioProcessors(arrayOf(...)).build()`. Subclassing the factory + setting it via `ExoPlayer.Builder.setRenderersFactory(factory)` is cleaner than the deprecated alternative of bundling processors in a custom `MediaCodecAudioRenderer`. Media3 1.9.0 (Dec 2025) added a new `AudioOutputProvider` API path; on 1.10.1 it's optional and the classic override path still works.
- **`DefaultAudioSink.Builder.setAudioProcessors(Array<AudioProcessor>)` takes a Java `AudioProcessor[]`, not `ImmutableList`.** Pass `arrayOf(adapter1, adapter2, ...)`. The array order IS the processing order.
- **Media3 PCM byte order is always little-endian on Android.** `BaseAudioProcessor.replaceOutputBuffer(size)` returns a native-order direct ByteBuffer, and every Android architecture has native = little-endian. No byte-swap needed when bridging to Kiln's `SampleFormat.*_LE`.
- **Media3's `MediaItem.fromUri(uri)` sets `mediaId = uri`** by default; when two `ItemId`s point to the same URI (defensive corner case, not currently triggered by the scanner), this aliases the lookup. Use `MediaItem.Builder().setUri(...).setMediaId(itemId.value).build()` for stable, collision-free identity. `onMediaItemTransition`'s `mediaItem.mediaId` then maps cleanly back to the originally-resolved `Playable` via a side-table.
- **`Media3ExoPlayerImpl`'s settings-flow collector starts in `init` and runs for the player's lifetime.** Cancellation is automatic via `scope.cancel()` in `release()`. Mirrors `JavaSoundPlayerImpl` pattern — don't try to "restart" the collector per-track.
- **`addAudioProcessor` on Media3ExoPlayerImpl is OBSERVATION-ONLY in MVP shape.** The actual Media3 chain is fixed at construction time via `KilnRenderersFactory.setAudioProcessors(...)`. Dynamic add/remove would require tearing down + rebuilding the player. Future processors that need to toggle on/off should mirror `ReplayGainProcessor`'s pattern: keep the processor in the chain permanently and use an internal `gain == 1.0` (or equivalent) fast-path to make it a no-op when disabled.
- **The `MediaProcessorAdapter`'s `queueInput` copies the input `ByteBuffer` into a `ByteArray`** because `AudioFrame` holds a `ByteArray` (commonMain-safe) not a `ByteBuffer` (JVM-only). The copy is a single memcpy at JNI boundary; negligible vs the per-sample arithmetic. Don't try to skip it by reading bytes from the ByteBuffer directly — Kiln's processors are written against `ByteArray` indexing and the reformatting would be invasive.
- **Per-track Playable map lives on the player.** `Media3ExoPlayerImpl.playablesById: MutableMap<String, Playable>` is populated during `loadQueue`'s resolution loop and read by `onMediaItemTransition` to recompute RG gain on track-change. Cleared on `release()` + `loadQueue` (each new queue is a fresh population). Single-threaded read/write — both methods run on `Dispatchers.Main.immediate` per ExoPlayer's single-thread-access contract.
- **Compose state observers are NOT reactive to permission grants from outside the activity.** On a fresh Android install, the READ_MEDIA_AUDIO gate stays visible after `adb shell pm grant com.clayworks.kiln android.permission.READ_MEDIA_AUDIO` until the user taps "Grant Permission" (which re-enters the activity and recomposes). Smoke-test scripts that pre-grant via `pm grant` must EITHER also send a permission-changed broadcast that the activity observes, OR walk through the click flow after launch. Not blocking for D-B-Android itself but it bites every Pixel smoke session if you forget.
```

- [ ] **Step 5: Create Session 18 handoff**

Create `docs/sessions/2026-05-22-session-18-handoff.md`:

```markdown
# Session 18 Handoff — Phase 2a Track D fully closed

**Authored:** 2026-05-22 at the close of Session 17 (D-B Android shipped)
**For:** Fresh CC session continuing Phase 2a

## TL;DR

- **Phase 2a Track D is DONE.** All four sub-tracks shipped:
  - PR #10 (merged): Session 14 — LoudnessAnalyzer in `:audio:dsp`
  - PR #11: Session 15 — D-A wrap-up: TrackAnalysisRunner + analyzer impls + DI
  - PR #12: Session 15 — D-C: SettingsRepository extension + AnalysisProgress flow + SettingsScreen
  - PR #13: Session 15 — D-B desktop: ReplayGainProcessor + JavaSoundPlayerImpl wiring
  - PR #14 (this session): Session 17 — D-B Android: MediaProcessorAdapter + KilnRenderersFactory + Media3ExoPlayerImpl wiring + AndroidAppGraph DI

## What Session 17 shipped

Five tasks, all in `:audio:playback/androidMain` + `:app-android` DI:

1. **`MediaProcessorAdapter`** (`:audio:playback/androidMain`) — extends Media3's `BaseAudioProcessor`; bridges Kiln `AudioProcessor` (single-call `process(frame)`) to Media3 `AudioProcessor` (queueInput/getOutput rotation). 8 Robolectric tests covering each PCM encoding (S16/S24/S32/F32), unsupported-encoding rejection, format-change, queueEndOfStream/isEnded, flush. Commit `<hash>`.
2. **`KilnRenderersFactory`** (`:audio:playback/androidMain`) — subclasses `DefaultRenderersFactory`; overrides `buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)` to inject the AudioProcessor chain via `DefaultAudioSink.Builder.setAudioProcessors(...)`. 3 Robolectric construction tests. Commit `<hash>`.
3. **`Media3ExoPlayerImpl` constructor extension** — new constructor params (`settings`, `rgProcessor`); `playablesById: MutableMap<String, Playable>` cached during `loadQueue`'s resolution loop, keyed by Media3 `MediaItem.mediaId` (set to `itemId.value` via the explicit Builder); `onMediaItemTransition` looks up the Playable + reapplies RG gain on track change; settings-flow collector in init re-applies gain on mode/pre-amp change. Commit `<hash>`.
4. **AndroidAppGraph wiring** — `@Singleton @Provides fun replayGainProcessor()` + threaded into `media3Player` provider. Plus `Media3ExoPlayerImplTest`'s `newPlayer(...)` fixture gained optional `settings` + `rgProcessor` params with sensible defaults (preserves all 13 existing tests) + a `StubSettingsRepository` + 2 new RG-specific tests. Commit `<hash>`.
5. CLAUDE.md gotchas (8 new) + this handoff + canonical 8-target verify-build + PR. Commit `<hash>`.

## Verification

Canonical 8-target build: GREEN.
Project test totals after Session 17: ~210+ tests across modules.

Manual device smoke (recommended but not blocking the merge): install `:app-android` on the Pixel 7 Pro (serial `2A261FDH300B1P`; Session 17's test device — Clay's Pixel 10 Pro XL is the daily driver and typically not USB-attached), run the backfill (Settings → ReplayGain → "Backfill missing tracks") against a small album, play a track, observe volume change on toggling between Off / Track / Album. Pre-amp slider should produce real-time volume changes during playback.

## What's next (Session 19 candidates)

- **Search tab UX polish** per Clay 2026-05-22 ("rough at best"). FTS5 backend is correct; the UI surface needs work. Non-urgent but visible.
- **Phase 2b** start — Spec Sheet UI, low-latency AAudio/WASAPI engine, AAudio path for Pixel 10. Per the execution plan, Phase 2b is 205-310 hrs and is the next major chunk after Track D.
- **Phase 2a polish items** — shuffle order generation (vetting Item 12), additional codec support in the desktop FLAC analyzer (D-A's MP3/WAV/AAC/etc. via a non-libFLAC decoder).

## Reference

- D-A wrap-up plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-wrap-up.md`
- D-C plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-c-settings-backfill.md`
- D-B desktop plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-b-desktop-consumer-gain.md`
- D-B Android plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-b-android.md`
- Engram entries: `mem_search "kiln/track-d-b"`, `mem_search "kiln/track-d-b-android"`

---

**End of Session 18 Handoff.** Phase 2a Track D closed.
```

(Note: the four `<hash>` placeholders in the task numbering above should be filled in by the implementer at commit-time. The handoff itself goes in the same commit as the CLAUDE.md update.)

- [ ] **Step 6: Run canonical verify-build (the 8-target gate)**

Use the canonical 8-target invocation (`:audio:playback:build` aggregator covers both `:audio:playback:testAndroidHostTest` Robolectric tests AND `:audio:playback:desktopTest` JVM tests; the 8-target also includes `:audio:playback:desktopTest` explicitly per CLAUDE.md's canonical list, so keep both):

```bash
./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:build :audio:dsp:desktopTest :audio:playback:desktopTest
```
Expected: BUILD SUCCESSFUL on all 8 targets.

Watch for:
- `:audio:playback:build` — umbrella aggregator runs the new MediaProcessorAdapter + KilnRenderersFactory Robolectric tests + the existing Media3ExoPlayerImplTest (with 2 new RG tests added). Total: 13 + 2 + 8 + 3 = 26 Robolectric tests via `testAndroidHostTest`.
- `:audio:playback:desktopTest` — separate JVM-only test set (JavaSound + JvmFlac + ReplayGainProcessor on the desktop path). Must stay green after the type imports added to Media3ExoPlayerImpl (no overlap, but compile changes ripple).
- `:app-android:assembleDebug` — verifies kotlin-inject's KSP codegen handles the updated `media3Player` provider (the new params resolve cleanly because `replayGainProcessor()` + `settingsRepository(...)` are already in the graph).

If any target fails:
- KSP codegen error on `AndroidAppGraph` → check that all 4 `media3Player` params resolve via existing providers + the new `replayGainProcessor()`.
- Robolectric test failure → likely a `BaseAudioProcessor` lifecycle issue (e.g., calling `getOutput()` before draining the previous one). Cross-check against the API reference quoted at the top of this plan.

- [ ] **Step 7: Manual Pixel 7 Pro device smoke (recommended but non-blocking)**

Run: `./gradlew :app-android:installDebug` (Pixel 7 Pro connected, serial `2A261FDH300B1P`; this is the Session 17 test device per Clay's standing setup — his Pixel 10 Pro XL is the daily driver and typically not USB-attached).

**Permission gate (do this BEFORE launching):**

```bash
adb shell pm grant com.clayworks.kiln android.permission.READ_MEDIA_AUDIO
```

The Compose state observer in MainActivity isn't reactive to permission grants from outside the activity, so the in-app "Grant Permission" button may still appear after `pm grant` — if so, click it once and the gate clears (the click recomposes the activity which re-reads the permission state). New CLAUDE.md gotcha (Step 4 above) documents this for future sessions.

Then open Kiln. Settings → ReplayGain section. Set mode to Track. Tap "Backfill missing tracks". Wait for at least a few tracks to land (progress bar visible). Pick an album that just got analyzed, play a track, and:

1. **Mode toggle test**: change mode Off → Track → Album → Off. Each transition should produce an audible volume step (Track = per-track normalized to -18 LUFS; Album = per-album rollup; Off = no change).
2. **Pre-amp slider test**: during playback, drag the pre-amp slider from 0 → +6 dB → -6 dB → 0. Each setting change should produce a real-time volume change (the settings-flow collector fires on every emission).
3. **Track change test**: play through an album with mixed track loudnesses. Volume should NOT jump between tracks (each track's gain is computed to land at the same -18 LUFS target).

If any test fails, the most likely culprits in order:
- Settings flow not emitting → check `SettingsRepositoryImpl` is the bound instance (DI graph)
- Adapter not active → check `MediaProcessorAdapter.isActive` returns true after configure (debugger breakpoint)
- Per-track Playable not found → check `playablesById` is populated at loadQueue + `mediaId` matches `itemId.value` round-trip

- [ ] **Step 8: Commit + push + PR**

```bash
git add CLAUDE.md docs/sessions/2026-05-22-session-18-handoff.md docs/superpowers/plans/2026-05-22-phase-2a-track-d-b-android.md
git commit -m "docs: D-B Android closeout — CLAUDE.md gotchas + Session 18 handoff + plan file

Captures Android-side D-B gotchas (Media3 AudioProcessor buffer rotation
vs Kiln's single-call shape; DefaultRenderersFactory.buildAudioSink as the
override surface for 1.10.x; PCM byte order is always LE on Android;
mediaId stability via explicit Builder.setMediaId; settings-collector
lifecycle; addAudioProcessor is observation-only with the chain fixed at
construction; ByteBuffer→ByteArray copy at the adapter boundary;
per-track Playable map on the player).

Session 18 handoff: Phase 2a Track D fully closed. Five PRs across the
track (#10 LoudnessAnalyzer merged; #11 D-A wrap-up; #12 D-C settings;
#13 D-B desktop; this PR — #14 — D-B Android).

Plan file landed for traceability.

Phase 2a Track D-B (Android) — Task 5."

git push -u origin phase-2a-track-d-b-android

gh pr create --title "Phase 2a Track D-B (Android) — consumer-side ReplayGain via Media3 RenderersFactory" --body "$(cat <<'EOF'
## Summary

Closes Phase 2a Track D. Makes D-C's persisted ReplayGain settings actually audible on Android by routing decoded PCM through Kiln's ReplayGainProcessor inside Media3's audio pipeline.

- `MediaProcessorAdapter` (`:audio:playback/androidMain`) — bridges Kiln's single-call `AudioProcessor.process(frame)` to Media3's queueInput/getOutput buffer-rotation contract by extending `androidx.media3.common.audio.BaseAudioProcessor`. Handles each PCM encoding (S16/S24/S32/F32 LE).
- `KilnRenderersFactory` (`:audio:playback/androidMain`) — subclasses `DefaultRenderersFactory`; overrides `buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)` to inject the chain via `DefaultAudioSink.Builder.setAudioProcessors(arrayOf(...)).build()`.
- `Media3ExoPlayerImpl` constructor gains `settings: SettingsRepository` + `rgProcessor: ReplayGainProcessor`; init block builds the adapter + factory + threads into `ExoPlayer.Builder.setRenderersFactory(...)`. `playablesById: MutableMap<String, Playable>` populated at `loadQueue` time + keyed by explicit `MediaItem.mediaId = itemId.value`; `onMediaItemTransition` looks up the new track's Playable + reapplies gain. Settings flow collector mirrors desktop pattern.
- `AndroidAppGraph` provides `ReplayGainProcessor @Singleton` + threads it + `SettingsRepository` into the `media3Player` factory (mirrors `DesktopAppGraph` from PR #13).

## Closes Phase 2a Track D

Five PRs land the full RG track:
- PR #10 (merged): LoudnessAnalyzer
- PR #11: D-A wrap-up (analyzer orchestrator + impls + DI)
- PR #12: D-C (settings + backfill UI)
- PR #13: D-B desktop (JavaSoundPlayer gain)
- PR #14 (this one): D-B Android (Media3 RenderersFactory + adapter)

## Test plan

- [ ] CI green (Ubuntu :app-android:assembleDebug + Windows :app-desktop:assemble)
- [ ] `:audio:playback:testAndroidHostTest` PASS — 26 tests (8 MediaProcessorAdapter + 3 KilnRenderersFactory + 15 Media3ExoPlayerImpl including 2 new RG-wiring tests)
- [ ] Manual Pixel 7 Pro device smoke (serial 2A261FDH300B1P): install :app-android:installDebug, run backfill on a small album, toggle ReplayGain Off/Track/Album → audible volume changes; pre-amp slider produces real-time volume changes during playback; track-changes within an album don't produce volume jumps

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

After Tasks 1-5:

**Spec coverage:**
- MediaProcessorAdapter: ✓ Task 1 (8 tests covering each PCM encoding + format-change + flush + EOS + identity)
- KilnRenderersFactory: ✓ Task 2 (3 construction + build smoke tests)
- Media3ExoPlayerImpl constructor extension + Playable tracking + settings collector: ✓ Task 3 (9 step changes — imports + fields + RenderersFactory wiring + init collector + onMediaItemTransition + loadQueue + addAudioProcessor + release + header comment)
- AndroidAppGraph wiring: ✓ Task 4 (replayGainProcessor + media3Player threading)
- Test fixture update + RG-specific tests: ✓ Task 4
- Stale-doc cleanup (SettingsScreen disclaimer + ReplayGainMode KDoc): ✓ Task 5 Steps 1-3 (per coordinator FYI — three references became stale on D-B desktop merge, must land in this PR for in-app + KDoc accuracy)
- CLAUDE.md gotchas (incl. permission-gate quirk for smoke prep) + Session 18 handoff + verify-build + PR: ✓ Task 5 Steps 4-8

**Placeholders:**
- All steps have complete code (no "implement here", "add appropriate error handling", etc.). The KilnRenderersFactory test-only accessor went through one in-plan correction (initial draft had a problematic context plumbing; corrected version is the second snippet in Task 2 Step 3 — implementer uses the corrected one).
- Commit `<hash>` placeholders in the Session 18 handoff are intentional — the implementer fills them in at commit time.

**Type consistency:**
- `MediaProcessorAdapter`'s constructor takes `com.clayworks.kiln.audio.dsp.AudioProcessor` (Kiln) ✓; the class implements `androidx.media3.common.audio.AudioProcessor` (Media3) via `BaseAudioProcessor` extension ✓.
- `KilnRenderersFactory(context, audioProcessors: Array<androidx.media3.common.audio.AudioProcessor>)` ✓; Media3ExoPlayerImpl constructs `arrayOf(MediaProcessorAdapter(rgProcessor))` ✓ — type matches.
- `Media3ExoPlayerImpl(context, source, settings, rgProcessor)` ✓ called from `AndroidAppGraph.media3Player(context, source, settings, rgProcessor)` ✓.
- `applyRgGain(playable: Playable, mode: ReplayGainMode, preAmpDb: Double)` consistent across init block (collector) + `onMediaItemTransition` ✓.
- `playablesById: MutableMap<String, Playable>` keyed by `MediaItem.mediaId = itemId.value` ✓; loadQueue + onMediaItemTransition use same key.

**Architectural deviations from the handoff's recommendation:**
- None major. The handoff scoped 5-6 tasks; this plan delivers exactly 5 (Task 5 is the closeout). The handoff's "optional Task 5 — manual smoke prep" is folded into Task 5's Step 4 (manual Pixel 10 device smoke section).

**Research findings worth surfacing:**
- Media3 1.10.1's API path for AudioProcessor injection is `DefaultRenderersFactory.buildAudioSink → DefaultAudioSink.Builder.setAudioProcessors(Array<AudioProcessor>)`. Media3 1.9.0 (Dec 2025) added a new `AudioOutputProvider` API that takes a different approach; on 1.10.1 the classic path is still the canonical one and there's no migration pressure. Plan uses the classic path.
- `BaseAudioProcessor` is the right super class for the bridge — it eliminates the EMPTY_BUFFER bookkeeping that the bare `AudioProcessor` interface would otherwise require. Subclass writes `onConfigure` + `queueInput` only.
- `MediaItem.fromUri(uri)` sets `mediaId = uri` by default. Switching to explicit `MediaItem.Builder().setUri(uri).setMediaId(itemId.value).build()` is a defensive change that prevents same-URI aliasing in `playablesById`.

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-05-22-phase-2a-track-d-b-android.md`. Subagent-driven execution per Sessions 14-15 pattern.
