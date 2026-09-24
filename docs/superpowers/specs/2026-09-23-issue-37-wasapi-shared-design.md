# Desktop WASAPI Shared-Mode Output (#37, WASAPI slice 1) Design

**Date:** 2026-09-23 · **Issue:** #37 · **Modules:** `:audio:playback` (desktopMain + commonMain), `:ui:components` (commonMain), `:app-desktop`
**Status:** design. Pending Clay's review, then the implementation plan.
**Decision record:** [`../../decisions/2026-09-23-wasapi-shared-pull-forward.md`](../../decisions/2026-09-23-wasapi-shared-pull-forward.md) (why WASAPI is pulled ahead of the plan §11 Phase 2c gate, why shared mode first, why pure Kotlin/JNA).

---

## 1. Problem

The desktop player (`JavaSoundPlayerImpl`) can't play any 24-bit or 32-bit FLAC. `AudioSystem.getLine(...)` throws `IllegalArgumentException: No line matching interface SourceDataLine supporting format PCM_SIGNED 44100.0 Hz, 24 bit…`. Java Sound on Windows only exposes DirectSound-backed 8/16-bit lines. On Cortex, 16-bit opens at 44.1–192 kHz, while 24-bit, 32-bit, and `PCM_FLOAT` are rejected at every rate. That leaves **5,760 of 27,766 tracks (~21%)** unplayable. This is pre-existing, not a #28 regression.

The failure path has three UX bugs of its own (issue comment on #37):
1. **Stale position.** `openAndStart` fails before resetting `positionMs`, so Now Playing shows the previous track's position against the new track's duration.
2. **Silent failure.** `PlayerState.Error` is never shown. It's also mis-typed: every line-open failure becomes `DeviceUnavailable`, even when the device is fine and the *format* is what failed.
3. **Auto-advance halts** at the first unplayable track.

## 2. Goal and success criteria

1. **Every FLAC in the library plays on desktop at full precision:** 16/24/32-bit at 44.1–192 kHz. There's no truncation or dither in Kiln's path. Windows' shared-mode mixer converts integer PCM to 32-bit float, which is lossless for ≤24-bit and exact to 24 significant bits for 32-bit.
2. **No regression** in #28's behavior: prompt track switching, skip/previous, pause/resume gapless, seek, EOF advance, the RG backfill, and ReplayGain modes.
3. **Failures are honest and visible:** correct error type, position reset, an on-screen message, and capped skip-ahead with a notice.
4. **The output path is disclosed:** Now Playing and the Spec Sheet show what the audio is actually going through (e.g. `WASAPI shared · 48 kHz float mix · resampled from 44.1 kHz`).
5. **`PlatformPlayer` is unchanged** (Engine-Swap-Shaped Boundary; plan §12's STOP signal isn't tripped). Android is untouched.

Non-goals: bit-perfect output (that's slice 2, exclusive mode), low latency, device selection, hot-plug listeners, and AAC/MP3 decode on desktop. The 398 AAC files stay unplayable, but they now fail visibly and get skipped.

## 3. Scope

**In:**
- A `PcmOutputLine` seam. `JavaSoundOutputLine` is a behavior-preserving move of the existing `SourceDataLine` code; `WasapiOutputLine` is new, pure Kotlin over `jna-platform` COM.
- A production line factory: WASAPI first, with Java Sound as the fallback only when WASAPI can't initialize.
- Actor changes: position reset, honest error types, capped skip-ahead.
- A `PlaybackDiagnostics` side-channel (output route + transient notices) and its UI: Now Playing error, notice, and output line; a Spec Sheet output row.
- Tests (§8), plus `CLAUDE.md` gotcha updates.

**Out / deferred:**
- **Exclusive mode, i.e. bit-perfect (slice 2).** It gets a new issue when #37 closes.
- **`IAudioClient3` low-latency:** not planned.
- **Output device picker, device friendly name, `IMMNotificationClient` hot-plug:** YAGNI for slice 1.
- **Renaming `JavaSoundPlayerImpl` → `DesktopPlayerImpl`:** churn with no behavior change. It can ride along with slice 2.
- **Android:** no change. It gets the default no-op `PlaybackDiagnostics`.

## 4. Architecture

```
                 ┌───────────── actor (audioDispatcher, sole owner) ─────────────┐
 commands ──────▶│ LoadQueue/Skip/Seek/Pause/…   openAndStart ─▶ lineFactory(fmt) │
                 │ frames ◀── decode producer     write(frame) ─▶ PcmOutputLine  │
                 └────────────────────────────────────────────────────────────────┘
                                                    │
                                ┌───────────────────┴───────────────────┐
                        WasapiOutputLine                         JavaSoundOutputLine
                   (default: shared, AUTOCONVERTPCM)        (fallback: WASAPI unusable)
```

The actor and its command model, the decode dispatcher, RG processing, and the write-interrupt seam all stay as #28 left them. Only the object the actor writes into changes, from `javax.sound.sampled.SourceDataLine` to Kiln's `PcmOutputLine`.

## 5. Components

### 5.1 `PcmOutputLine` (desktopMain, `internal`)

```kotlin
internal interface PcmOutputLine : AutoCloseable {
    val format: DecodedAudioFormat
    val bufferBytes: Int                       // total device-side buffer, for the EOF tail cap
    val isRunning: Boolean
    fun start()
    fun stop()                                 // keeps buffered audio (gapless pause/resume)
    fun flush()                                // discards buffered audio
    fun write(bytes: ByteArray, off: Int, len: Int): Int   // blocks until written or stopped/flushed
    fun queuedBytes(): Int                     // audio still buffered, not yet played
    fun setVolume(linear: Float)               // 0.0..1.0; implementations clamp
    override fun close()                       // idempotent
}
internal fun interface PcmOutputLineFactory { fun open(format: DecodedAudioFormat): PcmOutputLine }
```

**The thread contract is the #28 invariant, made explicit.** Every method may be called from the actor thread. **`stop()` and `flush()` must ALSO be safe from any thread**, because they form the write-interrupt seam. A call from another thread must make a blocked `write()` return promptly and must not corrupt line state. The actor stays the authority: after an interrupt, it issues its own on-actor calls.

**The factory `open` throws a typed `OutputLineException`:**

| Subtype | Meaning | Maps to |
|---|---|---|
| `FormatRejected(format, detail)` | The engine is up but won't take this PCM layout | `PlayerError.FormatUnsupported(describe(format))` |
| `DeviceProblem(detail)` | No endpoint, device invalidated, device in use | `PlayerError.DeviceUnavailable(detail)` |

The same types are thrown from `write`/`start` when the device dies mid-stream.

### 5.2 `JavaSoundOutputLine` (desktopMain)

A behavior-preserving move of today's code:
- **Open:** `AudioSystem.getLine(DataLine.Info(SourceDataLine, AudioFormat(...)))` + `open(format, bufferBytes)`.
- **Volume:** the `MASTER_GAIN` control from `applyGain()`.
- **`queuedBytes`:** `bufferSize - available()`.
- **Exceptions:** `IllegalArgumentException`/`LineUnavailableException` for an unsupported format become `FormatRejected`, and the rest become `DeviceProblem`.

It inherits the known Java Sound gotchas unchanged: `isRunning` is false until the first write, `drain()` is never used, and `write()` to a stopped line returns immediately.

### 5.3 WASAPI binding (desktopMain, `nativeio/wasapi/`)

- **`WasapiGuids`:** `CLSID_MMDeviceEnumerator`, `IID_IMMDeviceEnumerator`, `IID_IAudioClient`, `IID_IAudioRenderClient`, `IID_ISimpleAudioVolume`, `KSDATAFORMAT_SUBTYPE_PCM`.
- **`WasapiVtable`:** named vtable indices, one constant per method used, each commented with its header (`mmdeviceapi.h`, `audioclient.h`, `audiopolicy.h`) and its declaration order after `IUnknown`'s 0–2:
  - `IMMDeviceEnumerator.GetDefaultAudioEndpoint`
  - `IMMDevice.Activate`
  - `IAudioClient.{Initialize, GetBufferSize, GetCurrentPadding, GetMixFormat, Start, Stop, Reset, GetService}`
  - `IAudioRenderClient.{GetBuffer, ReleaseBuffer}`
  - `ISimpleAudioVolume.SetMasterVolume`

  Calls go through `com.sun.jna.platform.win32.COM.Unknown._invokeNativeInt(index, args)`. **Plan task 1 verifies every index and constant against the Windows SDK headers.**
- **`WaveFormatExtensible.encode(format): ByteArray`:** builds the 40-byte packed struct by hand, **not** as a JNA `Structure`, because `WAVEFORMATEX` is `#pragma pack(1)` and JNA's default alignment would pad it. The layout:
  - `wFormatTag = WAVE_FORMAT_EXTENSIBLE`, `nChannels`, `nSamplesPerSec`, `nAvgBytesPerSec`, `nBlockAlign`, `wBitsPerSample` = container bits (16/24/32), `cbSize = 22`
  - `wValidBitsPerSample` = source bit depth
  - `dwChannelMask` = `SPEAKER_FRONT_CENTER` for mono, `FRONT_LEFT|FRONT_RIGHT` for stereo, otherwise 0
  - `SubFormat = KSDATAFORMAT_SUBTYPE_PCM`

  `decodeMixFormat(ptr)` reads `GetMixFormat` back for the route report (rate, bits, float vs PCM subformat).
- **`HResults`:** a `check(hr, what)` helper that maps failures to the `OutputLineException` types:
  - `AUDCLNT_E_UNSUPPORTED_FORMAT` → `FormatRejected`
  - `AUDCLNT_E_DEVICE_INVALIDATED`, `AUDCLNT_E_DEVICE_IN_USE`, `E_NOTFOUND`, and `AUDCLNT_E_SERVICE_NOT_RUNNING` → `DeviceProblem`
  - Anything else → `DeviceProblem` carrying the hex HRESULT

  It's a pure function, so it's unit-tested.

### 5.4 `WasapiOutputLine` (desktopMain)

**Open (on the actor thread):**
1. `CoInitializeEx(null, COINIT_MULTITHREADED)`, once per thread and idempotent. `S_FALSE` and `RPC_E_CHANGED_MODE` are tolerated. The actor thread is a dedicated long-lived executor thread, so there's never a `CoUninitialize`.
2. `CoCreateInstance(MMDeviceEnumerator)` → `GetDefaultAudioEndpoint(eRender, eMultimedia)` → `Activate(IAudioClient)`. The default endpoint is re-resolved on **every** open, so a replugged or changed default device is picked up at the next track.
3. `Initialize(AUDCLNT_SHAREMODE_SHARED, AUTOCONVERTPCM | SRC_DEFAULT_QUALITY, hnsBufferDuration = BUFFER_MS (200 ms), 0, wfx, null)` → `GetBufferSize` → `GetService(IAudioRenderClient)` and `GetService(ISimpleAudioVolume)` → `GetMixFormat` (for the route report).

**Write model: push, polled.** There are no event handles and no native callbacks into the JVM.
```
write(bytes, off, len):
  while remaining > 0 and not interrupted:
      free = bufferFrames - GetCurrentPadding()
      if free == 0: park(~5 ms, or until unpark from stop/flush); continue
      n = min(free, remainingFrames)
      p = GetBuffer(n); p.write(0, bytes, off', n*frameBytes); ReleaseBuffer(n, 0)
  return bytesWritten
```
- About 3 JNA calls per decoded block (~40–50 Hz), which is negligible next to decode.
- A 200 ms buffer absorbs any realistic G1 pause, so no real-time thread is needed.
- The position display can lead audible output by up to one buffer. That was already true with Java Sound's 100 ms buffer, and it's below the 250 ms position tick.

**Interrupt seam (cross-thread `stop()`/`flush()`).** An off-actor call makes **no COM calls**. It sets `@Volatile interrupted = true` (plus `flushRequested` for `flush`) and `LockSupport.unpark(writerThread)`, so a blocked `write()` returns at once.

The actor then issues its own on-actor calls. `stop()` on the actor is `IAudioClient.Stop`. `flush()` on the actor is `Stop` → `Reset` → `Start` if the line was running, because `Reset` requires a stopped stream. On-actor `start()`/`flush()` clear `interrupted`.

Which thread is "the actor" is recorded at `open()` (the owning thread).

While `interrupted` is set, `write()` returns immediately with the bytes written so far, and the rest of that in-flight frame is dropped. This matches Java Sound's existing write-to-a-stopped-line behavior, so a pause loses at most the remainder of one decoded block (≤ ~100 ms). This isn't a regression, and gapless pause/resume refers to the device buffer, which `stop()` keeps.

**Other semantics:**
- `start()` = `IAudioClient.Start`. It's valid with an empty buffer; the engine renders silence until the first write.
- `queuedBytes()` = `GetCurrentPadding() × blockAlign`. This feeds the existing bounded EOF-tail park; the cap becomes `2 × line buffer duration` instead of the `BUFFER_MS` constant.
- `setVolume` = `ISimpleAudioVolume.SetMasterVolume`, the session volume.
- `close()`: `Stop`, then `Release` of the render client, volume, audio client, device, and enumerator, in reverse order. Idempotent.

**24-bit packed risk.** It's unverified whether `AUTOCONVERTPCM` accepts a 24-bit sample in a 24-bit container (`nBlockAlign = 3 × ch`). **Plan task 1 probes it on Cortex** for 16/24/32 at 44.1/96/192. If the 24-in-24 layout is rejected, `WasapiOutputLine` repacks S24 into a 32-bit container (`wBitsPerSample = 32`, `wValidBitsPerSample = 24`, sample left-justified). That's lossless, and a pure `repack24in32(src, dst)` function gets unit tests. The chosen layout is recorded in `CLAUDE.md`.

### 5.5 Line factory selection (desktopMain)

`DesktopOutputLineFactory` decides **once, lazily, on the actor thread at the first open**. If `CoInitializeEx` + `CoCreateInstance(MMDeviceEnumerator)` succeed, WASAPI is the engine for the session. Otherwise it's Java Sound, logged once at `warn` with the HRESULT.

Endpoint availability is **not** part of that decision. "No device right now" is a transient `DeviceProblem` and must not permanently downgrade the session to 16-bit Java Sound. A WASAPI `FormatRejected` never falls back to Java Sound either, because Java Sound would be strictly worse. It surfaces honestly.

### 5.6 Actor changes (`JavaSoundPlayerImpl`)

- `lineFactory: PcmOutputLineFactory` replaces `(AudioFormat) -> SourceDataLine`. `audioFormatOf`, `bufferBytesOf`, and `applyGain`'s `FloatControl` code move into `JavaSoundOutputLine`.
- **Position reset:** `openAndStart` sets `_positionMs = 0` and `lastTickedMs = 0` right after `teardownActivePlayback()`, **before** decoder open, so every failure path shows 0:00.
- **Honest errors:** line-open `OutputLineException` maps per §5.1. A decoder `UnsupportedCodec` already maps to `FormatUnsupported`.
- **`openAndStart` returns an `OpenResult`:** `Opened` | `TrackFailed(PlayerError)` (per-track: format, decode, IO, resolve) | `DeviceFailed(PlayerError)`. Setting the `Error` state stays inside it, as today.
- **Capped skip-ahead.** A new `openWithSkip(startIndex, direction)` is used by `advanceOnEof` (forward), `SkipToNext` (forward), and `SkipToPrevious` (backward):
  - It loops: resolve and open. On `TrackFailed`, it emits `PlaybackNotice.Skipped(title, reason)` and moves one step in `direction`, following the queue order and `RepeatMode.All` wrap, but **ignoring `RepeatMode.One`** so a failing track isn't retried.
  - It stops after **`MAX_CONSECUTIVE_SKIPS = 3`** failures: the last `Error` stays and `PlaybackNotice.GaveUp(count)` is emitted.
  - It also stops with that track's `Error` if there's no next index.
  - `DeviceFailed` stops immediately with no skip.
  - **`LoadQueue` (an explicit click) and `SkipTo(index)` never skip.** They open exactly the requested track and show its error.
  - Every skip still goes through `teardownActivePlayback()` + `openAndStart`, so the #28 stale-frame guarantee is unchanged.
- **Route reporting:** after a successful open, `_outputRoute.value = line.route()`. On teardown to Idle/Error it's set to `null`.

### 5.7 `PlaybackDiagnostics` side-channel (commonMain, `:audio:playback`)

```kotlin
data class OutputRoute(
    val engine: OutputEngine,          // WasapiShared | JavaSoundFallback
    val deviceRateHz: Int?,            // mix rate (WASAPI) / line rate (Java Sound)
    val deviceFormat: String,          // "32-bit float mix" | "16-bit PCM"
    val sourceRateHz: Int, val sourceBitDepth: Int,
) { val resampled: Boolean get() = deviceRateHz != null && deviceRateHz != sourceRateHz }

sealed interface PlaybackNotice {
    data class Skipped(val title: String, val reason: String) : PlaybackNotice
    data class GaveUp(val consecutiveFailures: Int) : PlaybackNotice
}

interface PlaybackDiagnostics {
    val outputRoute: StateFlow<OutputRoute?>
    val notices: SharedFlow<PlaybackNotice>     // replay = 0, extraBufferCapacity = 8, DROP_OLDEST
}
object NoPlaybackDiagnostics : PlaybackDiagnostics { /* null route, empty notices */ }
```

- **Desktop:** `createJavaSoundPlayer(...)` returns a public `DesktopPlayer : PlatformPlayer, PlaybackDiagnostics`, and `DesktopAppGraph` binds both types to the same instance. That's a typed binding, not an `is`-cast, so the Source Protocol invariant holds.
- **UI:** reads it through `LocalPlaybackDiagnostics` (a `staticCompositionLocalOf`, defaulting to `NoPlaybackDiagnostics`) in `LocalLibraryStats.kt`, next to `LocalPlayer`. Android needs no change.
- **`PlatformPlayer` is untouched.**

### 5.8 UI (`:ui:components`, commonMain)

- **`playerErrorMessage(PlayerError): String`** is a pure, unit-tested function. Examples: `FormatUnsupported` → "Can't play this file: 24-bit PCM at 44.1 kHz isn't supported by the output" (or the codec name for AAC); `DeviceUnavailable` → "Output device unavailable: …".
- **Now Playing:**
  - When `state is Error`, one line under the title in the error color.
  - The newest notice, shown for ~4 s ("Skipped *2morrow*: AAC isn't supported on desktop").
  - A quiet output line when the route is non-null: `Output · WASAPI shared · 48 kHz float mix`, plus ` · resampled from 44.1 kHz` when `resampled`, or `Output · Java Sound · 16-bit` on the fallback.
  - Formatting lives in a pure `formatOutputRoute(OutputRoute): String`, which is unit-tested.
- **Spec Sheet:** an "Output" row with `formatOutputRoute(route)`, shown only when the Spec Sheet's track is the queue's current item and the route is non-null.

## 6. Threading and COM invariants

1. All COM calls happen on the actor thread (the MTA-initialized `audioDispatcher` thread). There are no COM calls from the UI, decode, or caller threads.
2. The cross-thread interrupt is flags + `unpark` only.
3. A `PcmOutputLine` is owned by exactly one actor generation. `teardownActivePlayback()` closes it before any new open, which is unchanged from #28.
4. No JVM code runs on a Windows audio-engine thread (no callbacks), so there's no JNA callback-lifetime hazard (cf. the libFLAC callback gotcha).

## 7. Error handling summary

| Where | Condition | Result |
|---|---|---|
| Factory decision | COM/enumerator unusable | Java Sound for the session; `warn` once |
| `open` | No default endpoint / device in use | `DeviceFailed` → `Error(DeviceUnavailable)`, no skip |
| `open` | `AUDCLNT_E_UNSUPPORTED_FORMAT` (or Java Sound no-line) | `TrackFailed` → `Error(FormatUnsupported)`; skip if auto/Next/Prev |
| Decoder open | Unsupported codec (AAC) / corrupt / IO | `TrackFailed` (as above) |
| `write`/`start` mid-stream | `AUDCLNT_E_DEVICE_INVALIDATED` | teardown → `Error(DeviceUnavailable)`, no skip; next play re-resolves the endpoint |
| Skip loop | 3 consecutive `TrackFailed` | Stop on the last error + `GaveUp` notice |

## 8. Testing

**Device-free (runs on CI):**
- `WaveFormatExtensibleTest`: exact bytes for 16/24/32 × mono/stereo × 44.1/96/192, checking `nBlockAlign`, `nAvgBytesPerSec`, `cbSize = 22`, the channel mask, and the SubFormat GUID bytes. It also covers `repack24in32` if that's needed (sign preserved, left-justified).
- `HResultsTest`: each named HRESULT maps to the right `OutputLineException`, and unknown codes carry their hex.
- **Actor tests** (existing `JavaSoundPlayerActorTest`/`JavaSoundPlayerImplTest` migrated to a `FakePcmOutputLine`, which is simpler than today's `SourceDataLine` fakes). New cases:
  - a failed open zeroes the position
  - a format rejection gives `FormatUnsupported`, and a device problem gives `DeviceUnavailable`
  - EOF into two failing tracks then a good one → plays the good one with 2 `Skipped` notices
  - 3 consecutive failures → `Error` + `GaveUp`
  - a device failure never skips
  - `LoadQueue` and `SkipTo` onto a failing track don't skip
  - Previous skips backward
  - `RepeatMode.One` + a failing track → no retry loop
  - the route is published on open and cleared on stop
  - a cross-thread `stop()`/`flush()` unblocks a blocked fake `write()`
- **`:ui:components` commonTest:** `playerErrorMessage` and `formatOutputRoute` cases.

**Device-backed (Cortex; skipped on CI):**
- `WasapiOutputLineTest`, via `Assume.assumeTrue(defaultEndpointAvailable())`. It opens 16/24/32 at 44.1/96/192, writes 250 ms of silence, and exercises start/stop/flush/close and queued-bytes monotonicity. It also runs a cross-thread `stop()` while `write()` is blocked on a full buffer and asserts return in < 50 ms.

**Manual smoke (Cortex, real library):**
- *The 27 Club* (16/44.1), *2morrow* (id 7593, 24/44.1), a 24/96, a 24/192, and the one 32-bit track
- a queue containing an AAC (skip notice) and three AACs in a row (GaveUp)
- pause/resume gapless, seek, Next/Previous mid-24-bit, RG Off/Track/Album audible
- the Output line on Now Playing and the Spec Sheet

**#37 closes when this smoke passes.**

## 9. Commit sequencing (for the plan)

0. **Probe (throwaway, not committed):** verify the vtable indices and 24-bit container acceptance on Cortex. The result is recorded in the plan and `CLAUDE.md`.
1. `PcmOutputLine` + `JavaSoundOutputLine`, with the actor switched over and tests migrated. **A pure refactor: all existing tests green, no behavior change.**
2. Position reset + honest error types (`OutputLineException` → `PlayerError`).
3. `OpenResult` + capped skip-ahead + `PlaybackNotice` (actor tests).
4. WASAPI binding: GUIDs, vtable, `WaveFormatExtensible`, `HResults` (+ unit tests).
5. `WasapiOutputLine` (+ the device-backed test).
6. `DesktopOutputLineFactory` selection + `OutputRoute` publication + the `DesktopPlayer`/graph binding.
7. UI: `LocalPlaybackDiagnostics`, the Now Playing error/notice/output lines, the Spec Sheet row (+ commonTest).
8. Docs: `CLAUDE.md` gotchas (Java Sound becomes fallback-only; the WASAPI threading and container facts), the session handoff, and filing the slice-2 exclusive-mode issue.

Each step builds and passes the canonical build on its own.

## 10. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| A wrong vtable index or constant → crash or garbage HRESULT | Medium | Task 0 header check; the device-backed test on Cortex; indices centralized in one file |
| 24-in-24 rejected by `AUTOCONVERTPCM` | Medium | Lossless 24-in-32 repack (§5.4), decided by the probe |
| CI can't exercise WASAPI (no endpoint on the runner) | Certain | Device-free units + fakes on CI; the device test and smoke on Cortex |
| Shared mode resamples when track rate ≠ device mix rate (e.g. 44.1 → 48) | Certain for mismatched rates | Disclosed in the UI; slice 2 (exclusive) removes it |
| `COINIT` conflict if something else initialized the actor thread as STA | Low | `RPC_E_CHANGED_MODE` is tolerated (the calls still work from that apartment); logged |
| JNA per-write overhead | Low | ~3 calls per block at ~40–50 Hz, measured in the smoke (CPU unchanged vs Java Sound) |
