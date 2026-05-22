# Phase 2a Track D-B (desktop scope): Consumer-side ReplayGain on JavaSoundPlayer

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make D-C's mode + pre-amp settings actually audible on Desktop by applying the persisted `track.replay_gain_*` values to the playback pipeline as a linear pre-line-write gain. Includes peak limiting to prevent clipping on positive gain.

**Architecture:**
- **Pure math in `:audio:dsp/commonMain`**: `resolveGainLinear(rg, mode, preAmpDb)` combines per-track or per-album dB + the pre-amp + the peak-limit guard into a single linear multiplier. Mode=Off → returns 1.0. Album mode falls back to track when album values are null.
- **`ReplayGainProcessor` in `:audio:dsp/commonMain`**: implements the existing `com.clayworks.kiln.audio.playback.AudioProcessor` interface. Holds a `linearGain: AtomicReference<Double>` (or `volatile var Double` on JVM); `process(frame)` reads samples per `format.sampleFormat`, multiplies, writes back, returns the same frame. `setLinearGain(v)` is thread-safe so the player can update it on track change without locking.
- **JavaSoundPlayerImpl wiring**: constructor gains two new params (`settings: SettingsRepository`, `rgProcessor: ReplayGainProcessor`). On `startStream()` it computes the effective gain from the just-resolved Playable's `replayGain` + current settings, sets it on the processor, and ensures the processor is in the chain. A collector subscribes to `settings.replayGainMode` + `settings.replayGainPreAmpDb` and re-applies whenever they change while a track is playing.
- **DI graph update**: `DesktopAppGraph` provides `ReplayGainProcessor` as `@Singleton`, threads it + `SettingsRepository` into the `JavaSoundPlayer` factory. The processor is added to the player's chain at init.

**Tech Stack:** Kotlin Multiplatform (commonMain), Arrow `Either` (no new uses — already in the path), kotlin.test, kotlin-inject DI.

**Scope (this session — desktop only):**
- ✅ `resolveGainLinear` pure-math + ≥6 tests
- ✅ `ReplayGainProcessor` impl + ≥4 tests (one per SampleFormat + passthrough)
- ✅ JavaSoundPlayerImpl wiring: processor in chain, recompute on track-change + setting-change
- ✅ DI graph: ReplayGainProcessor @Singleton + threaded into player factory
- ✅ CLAUDE.md gotchas + Session 17 handoff (now scopes Android D-B) + verify-build + PR

**Out of scope (Android D-B = Session 17):**
- ⛔ Media3 `androidx.media3.common.audio.AudioProcessor` impl
- ⛔ Custom `RenderersFactory` that wraps `DefaultAudioSink` with Kiln's processor chain
- ⛔ The general "actually invoke the AudioProcessor chain on Android" infrastructure work (currently TODO'd at `Media3ExoPlayerImpl.kt:318`)

This split is intentional: the Android Media3 work is **general processor-chain infrastructure** (not RG-specific) and benefits from its own session. Once that infrastructure ships, `ReplayGainProcessor` plugs in unchanged because Kiln's `AudioProcessor` interface is platform-neutral.

**Branch:** `phase-2a-track-d-b-consumer-gain` (off D-C HEAD `3d8dad5` — stacks on PRs #11 + #12).

---

## Reference: gain resolution math

Per ReplayGain v2:

```
effectiveDb = (mode == Album ? album_db ?? track_db : track_db) ?? 0.0       // mode-driven choice with fallback
totalDb     = effectiveDb + preAmpDb                                          // user pre-amp on top
linearGain  = 10^(totalDb / 20)                                               // dB → linear
peakLinear  = (mode == Album ? album_peak ?? track_peak : track_peak) ?? 1.0  // matching peak
// Peak-limit: prevent clipping if positive gain pushes the peak over 1.0
if (linearGain * peakLinear > 1.0) {
    linearGain = 1.0 / peakLinear
}
// Mode == Off short-circuits to 1.0 (no gain change)
```

Implementation note: `peakLinear` defaults to 1.0 (not 0.0) when the column is null — a null peak means "unknown peak, conservatively assume signal could be at full scale." This is the safest default for the peak-limit guard.

`mode == Off` returns 1.0 without any computation — zero overhead on the playback hot path.

---

## File Structure

| File | Role |
|---|---|
| `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/GainResolver.kt` | **Create**: top-level `resolveGainLinear(rg, mode, preAmpDb)` function. |
| `audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/GainResolverTest.kt` | **Create**: 6+ tests. |
| `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/ReplayGainProcessor.kt` | **Create**: `AudioProcessor` impl + factory. |
| `audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/ReplayGainProcessorTest.kt` | **Create**: ≥4 tests. |
| `audio/dsp/build.gradle.kts` | **Modify**: add `implementation(project(":audio:playback"))` to commonMain so we can implement `AudioProcessor`. **WAIT** — that creates a cycle. See note below. |
| `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/AudioProcessor.kt` | (no change — but referenced. Already on `:audio:playback/commonMain`.) |
| `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt` | **Modify**: constructor + factory + startStream + new collector for settings flows. |
| `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt` | **Modify**: `@Singleton @Provides fun replayGainProcessor()` + thread SettingsRepository + processor into the `JavaSoundPlayer` factory. |
| `CLAUDE.md` | **Modify**: append D-B gotchas. |
| `docs/sessions/2026-05-22-session-17-track-d-handoff.md` | **Modify**: re-scope to "Android D-B only" — desktop D-B is now done. |

**Cycle resolution:** `:audio:playback` already depends on `:audio:dsp` (from D-A wrap-up). `:audio:dsp` cannot depend back. **Therefore `ReplayGainProcessor` and `AudioProcessor` must both live on `:audio:dsp` OR `:audio:playback`.** Since `AudioProcessor` is currently in `:audio:playback/commonMain`, the simplest fix is to **move `AudioProcessor` to `:audio:dsp/commonMain`** (where the concrete impls have always been intended to live per its KDoc). Then `:audio:playback` re-exports the interface to maintain API stability.

Looking more carefully: the `AudioProcessor.kt` file at `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/AudioProcessor.kt` says in its header comment:

> Per spec §3.4 Concentric Modules: actual processor implementations live in `:audio:dsp` (platform-free Kotlin). PlatformPlayer adapters in `:audio:playback` own the chain ordering and dispatcher binding. The interface itself is here because PlatformPlayer references it.

That's correct — the interface lives in `:audio:playback` because `PlatformPlayer.addAudioProcessor(processor)` references it. Concrete impls per the spec live in `:audio:dsp`. So `:audio:dsp` does need a dependency on `:audio:playback` for the interface.

BUT `:audio:playback` already depends on `:audio:dsp` (added Track D-A Task 5). Adding `:audio:dsp` → `:audio:playback` would create a cycle.

**Resolution**: Move `AudioProcessor.kt` (interface only) from `:audio:playback/commonMain` to `:audio:dsp/commonMain`. The interface is platform-neutral pure-Kotlin; `:audio:dsp` is the correct home per Concentric Modules. `:audio:playback`'s `PlatformPlayer` then imports `AudioProcessor` from `:audio:dsp` instead of locally.

This is a 1-file move + 1 import update in PlatformPlayer.kt (or wherever the interface is referenced from). Implementer should verify the move + update all import sites.

**Update file structure**:
| `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/AudioProcessor.kt` | **Create** (moved from `:audio:playback`). Package changes from `com.clayworks.kiln.audio.playback` to `com.clayworks.kiln.audio.dsp`. |
| `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/AudioProcessor.kt` | **Delete** (moved). |

Then update import sites in `:audio:playback` (PlatformPlayer + JavaSoundPlayerImpl + Media3ExoPlayerImpl + any tests).

---

## Task 1 — `resolveGainLinear` pure-math function

**Files:**
- Create: `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/GainResolver.kt`
- Create: `audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/GainResolverTest.kt`

**Note**: To avoid `:audio:dsp` depending on `:data:library` (which defines `ReplayGainMode` + `ReplayGain` data class), we duplicate or copy the relevant types. Cleanest: **`ReplayGainMode` enum moves into `:audio:dsp`**, and `:data:library/settings/SettingsRepository.kt` imports it. That's a 1-import-line change in :data:library. The `ReplayGain` data class stays in `:data:library` (it lives next to MediaItem); `GainResolver` accepts the individual fields (trackDb, trackPeak, albumDb, albumPeak) as nullable parameters instead of the data class — looser coupling.

Or even simpler: `ReplayGainMode` stays in `:data:library`. `:audio:dsp` already depends on Arrow Core only. Adding `:data:library` as a dep on `:audio:dsp` is undesirable.

**Choice**: keep `ReplayGainMode` in `:data:library` BUT pass it to `resolveGainLinear` as a nullable enum-like Int (0=Off, 1=Track, 2=Album) OR introduce a local `:audio:dsp` `ReplayGainPipelineMode` enum and translate at the call site. The cleanest path is the latter — `:audio:dsp` defines its own `ReplayGainPipelineMode { Off, Track, Album }`; JavaSoundPlayerImpl translates `ReplayGainMode → ReplayGainPipelineMode` at the call site (one-line `when` block).

This keeps `:audio:dsp` self-contained (no `:data:library` dep) and the two enums are isomorphic.

- [ ] **Step 1: Write the failing tests**

Create `GainResolverTest.kt`:

```kotlin
package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GainResolverTest {

    @Test
    fun `Off mode returns 1_0 regardless of inputs`() {
        assertEquals(1.0, resolveGainLinear(
            trackDb = -6.0, trackPeak = 0.5, albumDb = -8.0, albumPeak = 0.9,
            mode = ReplayGainPipelineMode.Off, preAmpDb = 0.0,
        ), 1e-9)
    }

    @Test
    fun `Track mode applies track_db and pre-amp`() {
        // -6 dB + 3 dB pre-amp = -3 dB → linear ≈ 0.708
        val result = resolveGainLinear(
            trackDb = -6.0, trackPeak = 0.5, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Track, preAmpDb = 3.0,
        )
        assertTrue(abs(result - 0.708) < 0.01, "expected ~0.708, got $result")
    }

    @Test
    fun `Album mode prefers album_db when present`() {
        val result = resolveGainLinear(
            trackDb = -6.0, trackPeak = 0.5, albumDb = -3.0, albumPeak = 0.7,
            mode = ReplayGainPipelineMode.Album, preAmpDb = 0.0,
        )
        // -3 dB → linear ≈ 0.708 (uses album_db = -3.0, NOT track_db = -6.0)
        assertTrue(abs(result - 0.708) < 0.01, "expected ~0.708 (album path), got $result")
    }

    @Test
    fun `Album mode falls back to track_db when album_db is null`() {
        val result = resolveGainLinear(
            trackDb = -6.0, trackPeak = 0.5, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Album, preAmpDb = 0.0,
        )
        // Falls back to track -6 dB → ~0.501
        assertTrue(abs(result - 0.501) < 0.01, "expected ~0.501 (track fallback), got $result")
    }

    @Test
    fun `peak limit caps positive gain so peak times gain stays under 1`() {
        // +6 dB → linear = 2.0; track_peak = 0.7 → product = 1.4 → would clip.
        // Limit: gain = 1.0 / 0.7 ≈ 1.428.
        val result = resolveGainLinear(
            trackDb = 6.0, trackPeak = 0.7, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Track, preAmpDb = 0.0,
        )
        assertTrue(abs(result - (1.0 / 0.7)) < 0.01, "expected ~1.428 (peak-limited), got $result")
        // And confirm result * peak <= 1.0 with a small tolerance:
        assertTrue(result * 0.7 <= 1.0001, "peak-limited result * peak = ${result * 0.7} must be <= 1.0")
    }

    @Test
    fun `null track_db with Track mode returns 1_0 (no gain to apply)`() {
        val result = resolveGainLinear(
            trackDb = null, trackPeak = 0.5, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Track, preAmpDb = 0.0,
        )
        assertEquals(1.0, result, 1e-9)
    }

    @Test
    fun `null peak defaults conservatively to 1_0 (no peak-limit override)`() {
        // -6 dB → 0.501; peak = null → treated as 1.0 → no peak-limit triggered.
        val result = resolveGainLinear(
            trackDb = -6.0, trackPeak = null, albumDb = null, albumPeak = null,
            mode = ReplayGainPipelineMode.Track, preAmpDb = 0.0,
        )
        assertTrue(abs(result - 0.501) < 0.01, "expected ~0.501, got $result")
    }
}
```

- [ ] **Step 2: Verify the tests fail**

Run: `./gradlew :audio:dsp:desktopTest --tests "com.clayworks.kiln.audio.dsp.replaygain.GainResolverTest"`
Expected: FAIL with unresolved reference: `resolveGainLinear` / `ReplayGainPipelineMode`.

- [ ] **Step 3: Implement the function**

Create `GainResolver.kt`:

```kotlin
package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.ln
import kotlin.math.pow

/**
 * Mirror of [com.clayworks.kiln.library.settings.ReplayGainMode] kept in
 * `:audio:dsp` to avoid a back-dep on `:data:library`. JavaSoundPlayerImpl
 * (and any other PlatformPlayer impl) translates one to the other at the
 * call site — the two enums are isomorphic.
 */
enum class ReplayGainPipelineMode { Off, Track, Album }

/**
 * Compute the linear gain multiplier to apply to PCM samples for ReplayGain
 * v2 consumer-side gain.
 *
 * Inputs are the per-track and per-album fields from the `track` table (any
 * of which may be null on tracks that haven't been analyzed yet) plus the
 * user's settings (mode + pre-amp).
 *
 * Returns 1.0 (no gain change) when:
 *  - [mode] is [ReplayGainPipelineMode.Off]
 *  - The mode-specific gain field is null with no fallback available
 *
 * Otherwise:
 *   effectiveDb = (mode == Album ? albumDb ?? trackDb : trackDb) ?? 0.0
 *   totalDb     = effectiveDb + preAmpDb
 *   linearGain  = 10^(totalDb / 20)
 *
 * Peak-limit guard: if [linearGain] * (matching peak) > 1.0, the gain is
 * capped at 1.0 / peak so the loudest sample stays within the 0 dBFS
 * envelope. Null peaks default to 1.0 conservatively (= "treat as full
 * scale; no limit needed").
 */
fun resolveGainLinear(
    trackDb: Double?,
    trackPeak: Double?,
    albumDb: Double?,
    albumPeak: Double?,
    mode: ReplayGainPipelineMode,
    preAmpDb: Double,
): Double {
    if (mode == ReplayGainPipelineMode.Off) return 1.0

    val (effectiveDb, effectivePeak) = when (mode) {
        ReplayGainPipelineMode.Off -> return 1.0  // unreachable; here for exhaustiveness
        ReplayGainPipelineMode.Track -> trackDb to trackPeak
        ReplayGainPipelineMode.Album -> (albumDb ?: trackDb) to (albumPeak ?: trackPeak)
    }

    if (effectiveDb == null) return 1.0  // no usable gain → no-op

    val totalDb = effectiveDb + preAmpDb
    val linearGain = 10.0.pow(totalDb / 20.0)
    val peak = effectivePeak ?: 1.0  // conservative default (no peak → assume full-scale)

    return if (linearGain * peak > 1.0) {
        // Peak-limit: cap so signal doesn't clip.
        1.0 / peak
    } else {
        linearGain
    }
}
```

- [ ] **Step 4: Verify tests pass**

Run: `./gradlew :audio:dsp:desktopTest --tests "com.clayworks.kiln.audio.dsp.replaygain.GainResolverTest"`
Expected: PASS — 7/7 green.

- [ ] **Step 5: Commit**

```bash
git add audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/GainResolver.kt audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/GainResolverTest.kt
git commit -m "feat(audio:dsp): resolveGainLinear pure-math function

Computes linear gain multiplier for ReplayGain v2 consumer-side gain.
Inputs: per-track + per-album dB/peak + mode (Off/Track/Album) + pre-amp dB.
Outputs: linear multiplier (1.0 = no change), with peak-limit guard
preventing positive gain from clipping the signal.

Uses :audio:dsp-local ReplayGainPipelineMode (isomorphic to :data:library's
ReplayGainMode) to avoid a back-dependency. PlatformPlayer impls translate
at the call site.

Phase 2a Track D-B (desktop) — Task 1."
```

---

## Task 2 — `ReplayGainProcessor` (AudioProcessor impl)

**Pre-step: move `AudioProcessor` interface from `:audio:playback/commonMain` to `:audio:dsp/commonMain`**

Move `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/AudioProcessor.kt` to `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/AudioProcessor.kt`. Change the package declaration from `com.clayworks.kiln.audio.playback` to `com.clayworks.kiln.audio.dsp`. Update all import sites (PlatformPlayer.kt, JavaSoundPlayerImpl.kt, Media3ExoPlayerImpl.kt, any test files in `:audio:playback`) to `import com.clayworks.kiln.audio.dsp.AudioProcessor` instead of `import com.clayworks.kiln.audio.playback.AudioProcessor`.

The interface itself is unchanged — only the package + file location.

**Note**: `AudioProcessor.kt` also references `AudioFrame` and `DecodedAudioFormat` which currently live in `:audio:playback/commonMain/.../Decoder.kt`. To avoid creating a third dep cycle, KEEP those references — `:audio:dsp` will need to depend on the two types as well.

But `:audio:dsp` cannot depend on `:audio:playback` (cycle). So `AudioFrame` + `DecodedAudioFormat` ALSO need to move to `:audio:dsp`, OR the interface signature changes to not reference them.

**Cleanest option**: Move the `AudioFrame` + `DecodedAudioFormat` + `SampleFormat` definitions OUT of `Decoder.kt` and into a new file at `:audio:dsp/commonMain/.../AudioFrame.kt`. Then both interfaces (AudioProcessor's, Decoder's DecodedStream) can reference them without a cycle.

Files to extract from `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/Decoder.kt`:
- `data class DecodedAudioFormat(...)`
- `enum class SampleFormat { ... }`
- `data class AudioFrame(...)` (with its custom equals/hashCode logic + the private `byteArrayPrefixEquals` helper)

Move these to a new file: `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/AudioFrame.kt`, package `com.clayworks.kiln.audio.dsp`. Then update all import sites — anywhere that says `import com.clayworks.kiln.audio.playback.AudioFrame` → `import com.clayworks.kiln.audio.dsp.AudioFrame` (similarly for the other two types).

This is a mechanical refactor: ~15-25 import-line updates across the codebase. Use `Grep` to find all usages, then update each file's import.

After this move, `:audio:dsp` can safely declare its own `AudioProcessor` impls without a dep cycle.

- [ ] **Step 1: Refactor AudioFrame + DecodedAudioFormat + SampleFormat + AudioProcessor into :audio:dsp**

Create `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/AudioFrame.kt` with the three types (DecodedAudioFormat, SampleFormat, AudioFrame) — exact contents from `Decoder.kt`. Package declaration changes to `com.clayworks.kiln.audio.dsp`.

Create `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/AudioProcessor.kt` with the AudioProcessor interface — exact contents from old location. Package declaration changes to `com.clayworks.kiln.audio.dsp`.

Delete the original `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/AudioProcessor.kt`.

Remove the three classes (DecodedAudioFormat, SampleFormat, AudioFrame) from `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/Decoder.kt` (keep Decoder + DecodedStream + DecoderError; just remove the three moved types).

Update all import sites. Use:
```
grep -rln "com.clayworks.kiln.audio.playback.AudioProcessor\|com.clayworks.kiln.audio.playback.AudioFrame\|com.clayworks.kiln.audio.playback.SampleFormat\|com.clayworks.kiln.audio.playback.DecodedAudioFormat" --include="*.kt" .
```
to find affected files, then update each import. Production files likely affected:
- `audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlatformPlayer.kt` (references AudioProcessor)
- `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt`
- `audio/playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt`
- `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacDecodedStream.kt`
- `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/*.kt`

And the `JvmFlacTrackAnalyzer.kt` we wrote in D-A wrap-up uses `SampleFormat` — verify + update.

The `PcmByteToFloat` helper in `JvmFlacTrackAnalyzer.kt` references `SampleFormat`. The `AudioFrame` references in `JvmFlacDecodedStream.kt`. The `DecodedAudioFormat` returned from `DecodedStream.format`.

After the moves: run `./gradlew :audio:dsp:build :audio:playback:build` and fix any remaining import-resolution errors.

- [ ] **Step 2: Commit the refactor independently (clean checkpoint before the new code)**

```bash
git add audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/AudioFrame.kt audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/AudioProcessor.kt audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/Decoder.kt
# (deletion of audio/playback/.../AudioProcessor.kt is also part of this commit)
git rm audio/playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/AudioProcessor.kt 2>/dev/null || true
# add any import-updated files
git add audio/playback/src audio/dsp/src

git commit -m "refactor: move AudioFrame + AudioProcessor types from :audio:playback to :audio:dsp

Spec §3.4 Concentric Modules: pure-math types belong in :audio:dsp.
Originally placed in :audio:playback only because PlatformPlayer references
them. The cycle that prevented :audio:dsp from depending on :audio:playback
is resolved by relocating the types — :audio:playback now imports them
from :audio:dsp (the dependency direction it already has).

Files moved:
  AudioProcessor.kt: :audio:playback/.../audio/playback → :audio:dsp/.../audio/dsp
  AudioFrame + DecodedAudioFormat + SampleFormat: extracted from
    :audio:playback/.../Decoder.kt to new :audio:dsp/.../AudioFrame.kt

No behavioral change — pure code-relocation refactor. Required by D-B's
ReplayGainProcessor (which lives in :audio:dsp per Concentric Modules and
implements AudioProcessor).

Phase 2a Track D-B (desktop) — Task 2 prep."
```

- [ ] **Step 3: Write the failing ReplayGainProcessor tests**

Create `audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/ReplayGainProcessorTest.kt`:

```kotlin
package com.clayworks.kiln.audio.dsp.replaygain

import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayGainProcessorTest {

    private fun pcm16(samples: IntArray): ByteArray {
        // Pack int16 samples into little-endian bytes.
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

        // 20000 * 2 = 40000, but int16 max is 32767 — must clamp.
        val samples = intArrayOf(1000, 20000, -20000)
        val frame = AudioFrame(pcm16(samples), samples.size * 2, samples.size, 0L)
        val out = processor.process(frame)

        val outSamples = bytesToShorts(out.bytes.copyOfRange(0, out.byteCount))
        assertEquals(2000, outSamples[0])
        assertEquals(32767, outSamples[1], "positive clip")
        assertEquals(-32768, outSamples[2], "negative clip")
    }

    @Test
    fun `processor format change resets internal state without losing gain`() {
        val processor = ReplayGainProcessor()
        processor.onFormatChange(DecodedAudioFormat(44100, 16, 2, SampleFormat.PCM_S16_LE))
        processor.setLinearGain(0.5)
        processor.onFormatChange(DecodedAudioFormat(48000, 16, 2, SampleFormat.PCM_S16_LE))

        // Gain should persist across format change.
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
```

- [ ] **Step 4: Implement `ReplayGainProcessor`**

Create `audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/ReplayGainProcessor.kt`:

```kotlin
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
 * envelope (e.g., +6 dB on a 0.8-amplitude signal in S16) clamps to the
 * envelope. This is a hard clip — the upstream [resolveGainLinear] is
 * responsible for peak-limiting so this never triggers in normal operation.
 * If it does trigger, the result is audible distortion (intentional — the
 * alternative would be silent failure).
 *
 * Per-bit-depth handling:
 *  - S16 LE: read int16, multiply, clamp [-32768, 32767], write back.
 *  - S24 LE: read int24 (signed), multiply, clamp [-8388608, 8388607], write back.
 *  - S32 LE: read int32, multiply, clamp [Int.MIN_VALUE, Int.MAX_VALUE], write back.
 *  - F32 LE: read float, multiply, no clamping (float overflow is graceful).
 *
 * Gain = 1.0 is a fast-path — `process(frame)` returns the frame unchanged
 * without any byte mutation. The playback hot path takes zero overhead when
 * RG is in Off mode.
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

    /**
     * Thread-safe setter for the gain multiplier. The playback thread reads
     * this on every `process(frame)` call; volatile ensures the update is
     * visible without locking.
     */
    fun setLinearGain(gain: Double) {
        linearGain = gain
    }

    fun currentLinearGain(): Double = linearGain

    override fun process(frame: AudioFrame): AudioFrame {
        val gain = linearGain
        // Fast-path: gain = 1.0 (Off mode or no RG values available) → passthrough.
        if (gain == 1.0) return frame

        val fmt = format ?: return frame  // no format yet → passthrough (defensive)

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
            val b2 = bytes[i + 2].toInt()  // signed
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
            // Use Long to avoid overflow when multiplying near Int.MAX_VALUE.
            val scaled = (s * gain).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            bytes[i] = (scaled and 0xFF).toByte()
            bytes[i + 1] = ((scaled ushr 8) and 0xFF).toByte()
            bytes[i + 2] = ((scaled ushr 16) and 0xFF).toByte()
            bytes[i + 3] = ((scaled ushr 24) and 0xFF).toByte()
            i += 4
        }
    }

    private fun applyGainF32(bytes: ByteArray, byteCount: Int, gain: Double) {
        // Use ByteBuffer for IEEE 754 float read/write. Little-endian.
        val bb = java.nio.ByteBuffer.wrap(bytes, 0, byteCount).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < byteCount) {
            val pos = i
            val f = bb.getFloat(pos)
            val scaled = (f.toDouble() * gain).toFloat()
            bb.putFloat(pos, scaled)
            i += 4
        }
    }
}
```

**Note**: `applyGainF32` uses `java.nio.ByteBuffer` which is JVM-only. Since `:audio:dsp` is `commonMain`, this would break Kotlin/JS or Native targets. But `:audio:dsp` currently has no JS/Native targets configured (verify by reading its `build.gradle.kts`); if it does add them later, the F32 path needs a multiplatform impl. For now this is fine.

Alternative if it does fail to compile: move `applyGainF32` to a small `actual fun` with `expect` in commonMain and `actual` in jvmMain. Implementer should check the current target set and choose the path that works.

- [ ] **Step 5: Verify tests pass**

Run: `./gradlew :audio:dsp:desktopTest --tests "com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessorTest"`
Expected: PASS — 5/5 green.

- [ ] **Step 6: Commit**

```bash
git add audio/dsp/src/commonMain/kotlin/com/clayworks/kiln/audio/dsp/replaygain/ReplayGainProcessor.kt audio/dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/replaygain/ReplayGainProcessorTest.kt
git commit -m "feat(audio:dsp): ReplayGainProcessor AudioProcessor impl

Linear-gain audio processor for ReplayGain consumer-side gain. Implements
the AudioProcessor interface from :audio:dsp (moved earlier this branch).

  - Volatile linearGain Double, thread-safe setter
  - Fast-path: gain == 1.0 returns frame unchanged (zero overhead in Off mode)
  - Per-bit-depth: S16/S24 use direct byte arithmetic + Int.coerceIn clamp;
    S32 uses Long arithmetic to avoid overflow; F32 uses ByteBuffer with
    IEEE 754 read/write (no clamp — float overflow is graceful)

Tests cover: passthrough (gain=1.0), attenuation (0.5x), amplification with
clipping (2.0x clamps at ±32767), gain persistence across format change.

Phase 2a Track D-B (desktop) — Task 2."
```

---

## Task 3 — Wire ReplayGainProcessor into JavaSoundPlayerImpl

**Files:**
- Modify: `audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt`

**Changes:**
1. Add constructor params: `private val settings: SettingsRepository`, `private val rgProcessor: ReplayGainProcessor`.
2. Update the factory `fun createJavaSoundPlayer(...)` to accept the new params.
3. In `init` block: add `_processors.value = _processors.value + rgProcessor` so the processor is in the chain.
4. In `startStream`: after the stream's format is known, call `rgProcessor.onFormatChange(stream.format)`. Then compute the initial gain via `resolveGainLinear(playable.replayGain.trackDb, ..., currentMode, currentPreAmpDb)` and call `rgProcessor.setLinearGain(...)`.
   - Need access to the just-resolved Playable inside startStream. Pass it as a new parameter to `startStream` from the callers `startPlaybackForCurrentIndex` (which already has it).
5. Add a coroutine in `scope` that collects `settings.replayGainMode` + `settings.replayGainPreAmpDb` (combine flow) — whenever either changes, recompute gain using the CURRENT playable and call `rgProcessor.setLinearGain`. Hold a reference to the current Playable so this collector can read its replayGain values.

**Note on the "current playable" reference:** add a `@Volatile private var currentPlayable: Playable? = null` field. Set it in `startStream`, clear it in `teardownActivePlayback`. The settings collector reads it inside a closure.

**Note on collector scope:** start the collector once in `init` (not per-track). It runs for the player's lifetime; cancels on `release()` via `scope.cancel()`.

- [ ] **Step 1: Read current JavaSoundPlayerImpl to anchor edits**

Skim the file once. Note where:
- `createJavaSoundPlayer` factory is defined (top of file)
- `JavaSoundPlayerImpl` class constructor + fields
- `startStream(stream, autoPlay)` method signature
- `teardownActivePlayback` method
- `init` block (or where the scope is constructed — may need an init block added)

- [ ] **Step 2: Apply all changes in a single edit pass**

Detailed edits (the implementer should organize these into discrete `Edit` tool calls):

a) Imports to add:
```kotlin
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainPipelineMode
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.audio.dsp.replaygain.resolveGainLinear
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.SettingsRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
```

b) Update the factory signature:
```kotlin
fun createJavaSoundPlayer(
    audioDispatcher: CoroutineDispatcher,
    decoder: Decoder,
    source: MusicSource,
    settings: SettingsRepository,
    rgProcessor: ReplayGainProcessor,
): PlatformPlayer = JavaSoundPlayerImpl(audioDispatcher, decoder, source, settings, rgProcessor)
```

c) Update the class constructor + add `currentPlayable` field + add init block to start the settings collector:
```kotlin
internal class JavaSoundPlayerImpl(
    private val audioDispatcher: CoroutineDispatcher,
    private val decoder: Decoder,
    private val source: MusicSource,
    private val settings: SettingsRepository,
    private val rgProcessor: ReplayGainProcessor,
) : PlatformPlayer {

    // ... existing fields ...

    @Volatile private var currentPlayable: Playable? = null

    init {
        // Add the RG processor to the chain at construction time.
        _processors.value = _processors.value + rgProcessor

        // Observe settings changes; recompute + apply RG gain whenever mode
        // or pre-amp changes (while a track is playing).
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

d) Update `startStream(stream, autoPlay)` to accept the `playable` it was opened from:

Find the existing `startStream(stream, autoPlay)` signature and add a `playable: Playable` parameter. Update the call site in `startPlaybackForCurrentIndex` to pass the resolved `playable`.

Then inside `startStream`, after `currentStream = stream`:
```kotlin
        currentPlayable = playable
        rgProcessor.onFormatChange(stream.format)

        // Compute initial gain from current settings + playable RG values.
        // suspend collect via .first() — the playback init is already in an
        // audioDispatcher context (suspend boundary acceptable).
        val mode = settings.replayGainMode.first()
        val preAmpDb = settings.replayGainPreAmpDb.first()
        applyRgGain(playable, mode, preAmpDb)
```

Add import `import kotlinx.coroutines.flow.first` if not present.

**Note**: The init block's `scope.launch { ... }` runs settings-change collection concurrently with the playback loop. Both update `rgProcessor.linearGain` via the volatile setter — no contention.

e) Update `teardownActivePlayback` to clear `currentPlayable`:

```kotlin
    private fun teardownActivePlayback(stopLineFirst: Boolean) {
        line?.let { l ->
            if (stopLineFirst) {
                runCatching { l.stop() }
            }
            runCatching { l.close() }
        }
        line = null
        runCatching { currentStream?.close() }
        currentStream = null
        currentPlayable = null  // new
        // NOTE: Don't reset rgProcessor.linearGain to 1.0 here — the next
        // startStream call will set it anyway, and resetting between tracks
        // could produce a transient pop.
    }
```

- [ ] **Step 3: Update the call site of `startStream`**

In `startPlaybackForCurrentIndex` (already in the file), find where `startStream(stream, autoPlay)` is called. Update to pass the `playable` (which is in scope at that point):
```kotlin
        startStream(stream, autoPlay, playable)
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew :audio:playback:build`
Expected: PASS — file compiles against the new types and the new constructor.

The `:app-desktop:assemble` will FAIL until Task 4 updates the DI graph to pass the new constructor args. That's expected.

- [ ] **Step 5: Commit**

```bash
git add audio/playback/src/desktopMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt
git commit -m "feat(audio:playback): wire ReplayGainProcessor into JavaSoundPlayerImpl

JavaSoundPlayer's constructor gains two new params: SettingsRepository +
ReplayGainProcessor. On init, the processor is added to the chain. On
startStream, the processor's onFormatChange is called and an initial gain
is computed from the playable's RG values + current settings. A coroutine
in the player's scope observes settings.replayGainMode + replayGainPreAmpDb
and re-applies gain on change (while a track is playing).

currentPlayable @Volatile field holds the active Playable across the
settings-collector + playback-loop seam.

DesktopAppGraph wiring lands in Task 4; :app-desktop:assemble will fail
until then.

Phase 2a Track D-B (desktop) — Task 3."
```

---

## Task 4 — DesktopAppGraph wiring

**Files:**
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt`

**Changes:**
1. Add `@Singleton @Provides fun replayGainProcessor(): ReplayGainProcessor = ReplayGainProcessor()`.
2. Update the existing JavaSoundPlayer factory `@Provides fun` to pass the new params:
```kotlin
@Provides
fun javaSoundPlayer(
    audioDispatcher: CoroutineDispatcher,
    decoder: Decoder,
    source: MusicSource,
    settings: SettingsRepository,
    rgProcessor: ReplayGainProcessor,
): PlatformPlayer = createJavaSoundPlayer(audioDispatcher, decoder, source, settings, rgProcessor)
```

(adjust function name + parameter list to match the existing graph's convention).

3. Add the imports.

- [ ] **Step 1: Read DesktopAppGraph.kt**

- [ ] **Step 2: Apply edits**

Imports:
```kotlin
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
```

Find the existing player provider and update. Add the `@Singleton @Provides fun replayGainProcessor()` next to other singleton providers.

- [ ] **Step 3: Build**

Run: `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:build :audio:dsp:desktopTest`
Expected: BUILD SUCCESSFUL.

`:app-android` should still build because Media3ExoPlayerImpl doesn't use ReplayGainProcessor (Android side is the deferred work). The Android side may need an `import com.clayworks.kiln.audio.dsp.AudioProcessor` update if Task 2's refactor touched it — verify that compile.

- [ ] **Step 4: Commit**

```bash
git add app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/di/DesktopAppGraph.kt
git commit -m "feat(app-desktop): wire ReplayGainProcessor into DI graph

DesktopAppGraph adds @Singleton @Provides for ReplayGainProcessor and
threads it + SettingsRepository into the JavaSoundPlayer factory. The
processor is a shared singleton across the player's lifetime.

Android side (AndroidAppGraph) is not updated — Media3ExoPlayerImpl's
processor chain injection is general infrastructure (TODO since MVP
Session 5+) and lands in Session 17 D-B-Android.

Phase 2a Track D-B (desktop) — Task 4."
```

---

## Task 5 — Closeout: CLAUDE.md gotchas + Session 17 handoff update + verify-build + PR

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/sessions/2026-05-22-session-17-track-d-handoff.md` (re-scope to Android D-B only)

- [ ] **Step 1: Append CLAUDE.md gotchas**

```markdown
- **`:audio:dsp` cannot depend on `:audio:playback`** — would create a cycle (`:audio:playback` already deps on `:audio:dsp` from D-A wrap-up Task 5). When new processor types in `:audio:dsp` need to reference types currently in `:audio:playback` (like `AudioProcessor`, `AudioFrame`, `DecodedAudioFormat`, `SampleFormat`), move those types DOWN to `:audio:dsp` first. Done by D-B Task 2: AudioFrame + AudioProcessor + DecodedAudioFormat + SampleFormat now live in `com.clayworks.kiln.audio.dsp.*`.
- **`ReplayGainProcessor.process(frame)` is a HOT path** invoked once per audio frame (~50-100 Hz). The `gain == 1.0` fast-path (Off mode) returns the frame unchanged with zero byte mutation — keep this guard intact across future modifications.
- **`@Volatile private var linearGain: Double` is the right concurrency primitive** here. The setter is called from the settings-flow collector coroutine; the getter is called from the playback loop coroutine. Both coroutines may run on different dispatchers (settings on the player's scope = `audioDispatcher`; playback loop also on `audioDispatcher` — but `withContext(Dispatchers.Main)` calls in play()/pause() can cross thread boundaries). `@Volatile` is sufficient for a single Double; no locking needed.
- **Settings flow collector starts in JavaSoundPlayerImpl.init** and runs for the player's lifetime. Cancellation is automatic via `scope.cancel()` in `release()`. Don't try to "restart" it per-track — it's a player-lifetime subscription.
- **`Playable.replayGain` is the canonical source of per-track RG values for the consumer-side gain.** It's populated by `LocalLibrarySourceMappers.kt` from the `replay_gain_*` columns. The `JavaSoundPlayer`'s settings-collector closes over `currentPlayable` to read these on settings change. If a future source (network, etc.) doesn't have RG values, `Playable.replayGain` is null and `resolveGainLinear` returns 1.0 (no-op).
```

- [ ] **Step 2: Update Session 17 handoff**

Edit `docs/sessions/2026-05-22-session-17-track-d-handoff.md`. Change the TL;DR to reflect that desktop D-B is now done; only Android D-B remains. Update the task breakdown to focus on Android.

Suggested new content:

```markdown
# Session 17 Handoff — Phase 2a Track D-B Android (Media3 RenderersFactory)

**Authored:** 2026-05-22 at the close of Session 15 (D-A wrap-up + D-C + D-B desktop all shipped)
**For:** Fresh CC session continuing Phase 2a Track D
**Goal:** Implement Android-side consumer-side ReplayGain. Requires general Media3 infrastructure work (custom RenderersFactory + AudioSink wrapper) that's been TODO'd since MVP Session 5+.

## TL;DR

- Sessions 14+15 shipped **all of Track D except the Android-side audio pipeline**.
- PR #10 (merged): Session 14 — LoudnessAnalyzer in :audio:dsp.
- PR #11: Session 15 — D-A wrap-up: TrackAnalysisRunner orchestrator + desktop FLAC + Android MediaCodec analyzer impls + DI wiring.
- PR #12: Session 15 — D-C: SettingsRepository extension, AnalysisProgress flow, SettingsScreen "ReplayGain" section, app-route wiring.
- PR #13: Session 15 — D-B desktop: ReplayGainProcessor in :audio:dsp + wired into JavaSoundPlayerImpl. Audible RG on desktop.

## What Session 17 ships

Android-side equivalent of D-B desktop. Three sub-pieces, all in `:audio:playback/androidMain`:

1. **`KilnRenderersFactory`** — custom `androidx.media3.exoplayer.RenderersFactory` that wraps `DefaultAudioSink` with Kiln's AudioProcessor chain. Constructed in `Media3ExoPlayerImpl`'s init; passed to `ExoPlayer.Builder(context).setRenderersFactory(...)`.
2. **`MediaProcessorAdapter`** — adapts Kiln's `com.clayworks.kiln.audio.dsp.AudioProcessor` interface to Media3's `androidx.media3.common.audio.AudioProcessor` interface (different shape — Media3 uses `AudioFormat` + `ByteBuffer` cycles). Allows the same `ReplayGainProcessor` to plug into both pipelines.
3. **AndroidAppGraph wiring** — provide `ReplayGainProcessor` (singleton), thread it + `SettingsRepository` into `Media3ExoPlayerImpl`'s constructor (mirror desktop's pattern). The Media3 player's init builds the `KilnRenderersFactory` with the processor's adapter.

## Recommendation

5-6 tasks. The Media3 surface is intricate but well-documented. Reference materials:

- Media3 `AudioProcessor`: https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor (Kotlin)
- Media3 `DefaultAudioSink` configuration: https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink
- `RenderersFactory` extension: https://developer.android.com/media/media3/exoplayer/customization

## Other known follow-ups

- **`@Singleton` annotation** still missing on `trackAnalyzer` + `analysisRunner` providers in both DI graphs — addressed in D-C plan/handoff but actually applied to both in D-C fix-up commit `3d8dad5`. Mark this as done.
- **Search tab "rough at best"** per Clay 2026-05-22. FTS5 backend correct; UI/UX needs work. Non-urgent.

## Reference

- D-A wrap-up plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-a-wrap-up.md`
- D-C plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-c-settings-backfill.md`
- D-B desktop plan: `docs/superpowers/plans/2026-05-22-phase-2a-track-d-b-desktop-consumer-gain.md`
- Engram entries: `mem_search "kiln/track-d-b"`, `mem_search "kiln/track-d-c"`, `mem_search "kiln/track-d-a-wrap-up"`
- Reference math: see D-B plan §Reference.

---

**End of Session 17 Handoff.**
```

- [ ] **Step 3: Run canonical verify-build**

Run: `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:build :audio:dsp:desktopTest :audio:playback:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual desktop smoke (optional but recommended)**

Run: `./gradlew :app-desktop:run`
- Open settings, set ReplayGain mode to Track.
- Play a track that has `replay_gain_track_db` populated (Clay can run the backfill on a small subset first if D:\tiddl is too large).
- Verify volume changes.
- Move the pre-amp slider; verify volume changes in real-time.
- Set mode back to Off; verify volume returns to baseline.

If the smoke test fails (e.g., no audible change), the most likely culprit is the settings-collector not actually receiving emissions — check that the collector is correctly scoped + that `currentPlayable` is being set on track-start.

- [ ] **Step 5: Commit + push + PR**

```bash
git add CLAUDE.md docs/sessions/2026-05-22-session-17-track-d-handoff.md docs/superpowers/plans/2026-05-22-phase-2a-track-d-b-desktop-consumer-gain.md
git commit -m "docs: D-B desktop closeout — CLAUDE.md gotchas + Session 17 handoff updated + plan file

Captures D-B-specific gotchas (module dep cycle resolution, processor
hot-path discipline, @Volatile sufficiency, settings collector lifecycle,
Playable.replayGain canonical source).

Session 17 handoff re-scoped to Android D-B only — desktop D-B shipped
this session. Android side requires general Media3 RenderersFactory work
that's been TODO since MVP Session 5+.

Plan file landed for traceability.

Phase 2a Track D-B (desktop) — Task 5."

git push -u origin phase-2a-track-d-b-consumer-gain

gh pr create --title "Phase 2a Track D-B (desktop) — consumer-side ReplayGain on JavaSoundPlayer" --body "$(cat <<'EOF'
## Summary

Stacks on PRs #11 + #12. Makes D-C's mode + pre-amp settings actually audible on Desktop.

- `resolveGainLinear` pure-math in :audio:dsp (combines per-track/album dB + peak + mode + pre-amp into a linear multiplier with peak-limit)
- `ReplayGainProcessor` AudioProcessor impl in :audio:dsp (per-bit-depth sample multiplier with clipping clamp)
- Refactor: `AudioProcessor`, `AudioFrame`, `DecodedAudioFormat`, `SampleFormat` types moved from :audio:playback to :audio:dsp (resolves the dep cycle that prevented :audio:dsp from hosting processor impls)
- JavaSoundPlayerImpl: gains constructor params + settings-flow collector + currentPlayable tracking + applies gain on track-change + settings-change
- DesktopAppGraph: provides ReplayGainProcessor @Singleton + threads it + SettingsRepository into the player factory

## Android side is deferred

Custom Media3 `RenderersFactory` + AudioSink-wrapping AudioProcessor is general infrastructure (TODO since MVP Session 5+); Session 17 owns that. ReplayGainProcessor plugs in unchanged when it lands.

## Test plan

- [ ] CI green (Ubuntu :app-android:assembleDebug + Windows :app-desktop:assemble)
- [ ] `:audio:dsp:desktopTest` PASS — 7 new GainResolver tests + 5 new ReplayGainProcessor tests + existing
- [ ] Manual desktop smoke: ReplayGain mode toggles between Off/Track/Album produce audible volume changes during playback
- [ ] Manual smoke: pre-amp slider produces real-time volume changes

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

After Tasks 1-5:

- **Spec coverage**: pure-math gain ✓, processor impl ✓, player wiring ✓, DI wiring ✓, docs + handoff ✓. Android out of scope per architectural decision.
- **Placeholders**: every step has complete code.
- **Type consistency**: `ReplayGainPipelineMode` (in :audio:dsp) ↔ `ReplayGainMode` (in :data:library) translated 1:1 in JavaSoundPlayerImpl. `Playable.replayGain` reads through cleanly to `resolveGainLinear`. `ReplayGainProcessor`'s `setLinearGain(Double)` matches what the player calls.

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-05-22-phase-2a-track-d-b-desktop-consumer-gain.md`. Subagent-driven execution per Session 14+15 pattern.
