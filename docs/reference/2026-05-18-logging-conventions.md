# Logging Conventions — Kermit Wiring

**Date:** 2026-05-18 (living reference)
**Type:** Reference
**Source:** Synthesis from [scaffold prep `libs.versions.toml` (`kermit = "2.0.4"`)](../scaffold/2026-05-18-mvp-session-1-prep.md#2-gradlelibsversionstoml-skeleton), [vertical-slice prep §7 threading + lifecycle](../scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md), [risk playbook entries for telemetry needs](../reference/2026-05-18-risk-playbook.md).

Kiln uses **kermit** (Touchlab's KMP-friendly logger) for all logging. This doc consolidates: what to log at what level, where filters live, how to surface debug telemetry to the Hardware Spec Sheet, log-rotation and PII discipline.

Clay's "Clinical Information Density" preference means logs are evidence, not commentary. Per-log-line discipline matters because logs accumulate and Clay's "Algorithmic Operational Discipline" trait expects log streams to be useful for SPC-style analysis.

---

## Why kermit

Per Slack's `slackhq/circuit` libs.versions.toml — kermit is the de facto KMP logger in 2026. Alternatives considered:
- `android.util.Log` — Android-only; commonMain can't use it
- `java.util.logging.Logger` — JVM-only; commonMain can't use it
- `slf4j` — heavyweight; bridging on KMP non-trivial
- Custom log abstraction — wasted effort vs adopting kermit

kermit is Apache 2.0, actively maintained by Touchlab, supports per-platform log writers (logcat on Android, stdout on JVM), structured log payloads, and tag-based filtering.

---

## Setup

Per scaffold prep `libs.versions.toml`:

```toml
kermit = "2.0.4"   # VERIFY at scaffold time

kermit = { module = "co.touchlab:kermit", version.ref = "kermit" }
```

Module-level setup (every commonMain that logs):

```kotlin
// build.gradle.kts (per the kiln.kmp.library convention plugin)
kotlin.sourceSets.commonMain.dependencies {
    implementation(libs.kermit)
}
```

App-startup wiring (`:app-android` + `:app-desktop`):

```kotlin
// :app-android/src/main/kotlin/.../KilnApplication.kt
class KilnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.setLogWriters(
            // Android: logcat with our app tag
            platformLogWriter(NoTagFormatter),
            // Optional: persistent file writer for crash reports
            // FileLogWriter(filesDir.resolve("kiln.log")),
        )
        Logger.setMinSeverity(if (BuildConfig.DEBUG) Severity.Verbose else Severity.Info)
    }
}
```

```kotlin
// :app-desktop/src/jvmMain/kotlin/.../Main.kt
fun main() {
    Logger.setLogWriters(
        platformLogWriter(),     // stdout via simple println on JVM
        // FileLogWriter(userDataDir.resolve("kiln-desktop.log")),
    )
    Logger.setMinSeverity(if (kilnIsDebug()) Severity.Verbose else Severity.Info)
    application { /* Compose-MP Window */ }
}
```

---

## Log levels — when to use each

kermit exposes 6 severity levels. Use them deliberately.

| Level | When to use | Examples |
|---|---|---|
| **Verbose** | Tracing per-frame / per-sample / per-row events; deep debug | "frame N processed: 1024 samples in 0.42ms"; "row 12345 scanned" |
| **Debug** | Per-operation lifecycle; developer-facing diagnostics | "MusicSource.getPlayable(id=local:42) → Right"; "scanner started, candidates=39500" |
| **Info** | Notable events any user might care about during normal operation | "library scan completed: 39500 tracks indexed in 4m 12s"; "EQ preset loaded: ${name}" |
| **Warn** | Recoverable conditions worth flagging | "track ${id} has corrupt FLAC stream; skipping"; "USB DAC disconnected; pausing" |
| **Error** | Operation failed with caller-actionable failure | "FLAC decode failed for ${path}: ${cause}"; "SQLDelight migration v3→v4 failed" |
| **Assert** | Invariant violations indicating a bug; reserved for `error()` callers | "AudioSystem returned non-SourceDataLine type" |

**Default minimum severity:**
- Debug builds: `Verbose` (every log line written)
- Release builds: `Info` (skip Verbose + Debug to reduce log volume)

**Production filtering** (per scaffold prep §9 once an opt-in debug overlay ships): user can toggle verbose logging on for troubleshooting; default is Info.

---

## What to log where — module-by-module

### `:audio:dsp`

**Default: don't log in hot paths.** DSP processes thousands of samples per call; logging per-sample destroys cache locality. Reserve logs for:
- Filter construction (Debug): `"BiquadFilter.lowpass(sr=44100, cutoff=1000, q=0.707) constructed"`
- Filter destruction / reset (Debug)
- Numerical anomalies (Warn): `"BiquadFilter coefficients indicate instability"`

NEVER log inside `process(input, output, sampleCount)` — this is the hot loop. If you need per-buffer diagnostics, use a counter + log-on-threshold pattern.

### `:audio:visualizer`

Same discipline as `:audio:dsp`. FFT runs at 60fps; per-frame logs would flood.

### `:audio:playback`

- Decoder lifecycle (Debug): `"Opening decoder for ${playable.uri}, codec=${playable.codec}"`
- Decoder errors (Error): `"FLAC decode failed at offset ${pos}: ${libFlacErrorCode}"`
- Player state transitions (Info): `"Player: Idle → Loading → Ready(playing=true)"`
- Audio device events (Warn): `"AudioDevice disconnected; pausing"`
- Buffer underruns (Warn): `"SourceDataLine underrun count=${count}"`
- JNA native library load (Info on success; Error on failure)

Audio-dispatcher logs are particularly hot — keep them minimal:

```kotlin
// Bad — logs every frame
private fun playbackLoop() {
    decoder.frames.collect { frame ->
        Logger.v { "Frame produced: ${frame.byteCount} bytes" }   // floods logs
        line.write(frame.bytes, 0, frame.byteCount)
    }
}

// Good — log every N frames or via a counter
private fun playbackLoop() {
    var frameCounter = 0
    decoder.frames.collect { frame ->
        if (++frameCounter % 1000 == 0) {
            Logger.v { "Played $frameCounter frames so far (${frame.timestampMs}ms)" }
        }
        line.write(frame.bytes, 0, frame.byteCount)
    }
}
```

### `:data:library`

- Library scan lifecycle (Info): "scan started", "scan completed: ${added} added, ${updated} updated, ${deleted} removed in ${duration}ms"
- Per-file scan errors (Warn): `"Failed to read tags for ${path}: ${cause}; skipping"`
- SQLDelight migration events (Info): `"DB migrated v${from} → v${to}"`
- Query errors (Error)
- FTS5 sanitization edge cases (Debug): `"FTS query sanitized: '${raw}' → '${sanitized}'"` — useful for debugging type-ahead bugs

### `:ui:theme`

- Palette extraction events (Debug): `"Palette extracted from ${albumId}: vibrant=${swatch.color}"`
- WCAG contrast post-processing failures (Warn): `"All extracted swatches below WCAG AA against text-primary; falling back to accent-soft"`
- Theme transitions (Verbose at most): per-track theme changes are noisy

### `:ui:components`

- Component-level events at Debug: navigation transitions, screen mounts
- User actions at Info: `"User clicked play on track ${id}"`
- Compose recomposition warnings (Warn): if any composable triggers excessive recomposition during dev
- Don't log in `@Composable` function bodies — recomposition runs them many times

### `:app-android` + `:app-desktop`

- App lifecycle (Info): `"KilnApplication.onCreate"`, `"Main started"`
- DI graph construction errors (Assert/Error)
- Permission events on Android (Info)
- Unhandled exception crash reports (Error)

---

## Tagging — module-prefix convention

kermit supports per-call tags. Use them for grep-friendliness:

```kotlin
private val log = Logger.withTag("audio.playback")

internal class JavaSoundPlayerImpl(...) : PlatformPlayer {
    override suspend fun play() {
        log.i { "play() called; current state=${state.value}" }
        // ...
    }
}
```

Tag conventions:
- `audio.dsp`, `audio.visualizer`, `audio.playback`
- `data.library`, `data.library.scan`
- `ui.theme`, `ui.components`
- `app.android`, `app.desktop`
- Reserved for hot paths: `audio.playback.hot` (filter at lower severity in prod)

Tags become especially useful with platform log viewers — `adb logcat -s "audio.playback"` filters to one module.

---

## What NOT to log

- **PII (per Clay's Compliance-First Architect trait):** even though Kiln is single-user, don't log full file paths if they could reveal user-system structure to a future shared log file. Path-redact: log relative paths or hashes when possible
- **Decoder hot paths:** sub-millisecond budget; no logs
- **Recomposition triggers:** floods logs
- **Successful operations at Error level:** `Logger.e { "operation succeeded" }` defeats the level system
- **Stack traces for expected failures:** `Logger.e(throwable) { ... }` is for surprises; modeled domain errors via Either don't need stack traces
- **Verbose-level events in release builds:** filter via `Logger.setMinSeverity(Severity.Info)`

---

## Structured payloads — when string interpolation isn't enough

kermit's lambda-form `{ ... }` makes log construction lazy (skipped if level filters it out). For structured data, kermit doesn't have first-class structured-logging like `logstash` — emulate with consistent prefixes:

```kotlin
log.i { "scan.completed tracks_added=$added tracks_updated=$updated tracks_deleted=$deleted duration_ms=$durationMs" }
```

When Hardware Spec Sheet or future telemetry dashboard reads logs, the `key=value` format greps cleanly. Don't invent ad-hoc formats per call.

---

## Lazy-eval log messages

kermit's `{ ... }` block form is lazy — the string is only constructed if the severity passes the filter. Use it always:

```kotlin
// Bad — string built unconditionally
log.v("Processed frame: ${frame.byteCount} bytes, ${frame.timestampMs}ms")

// Good — string built only when Verbose passes filter
log.v { "Processed frame: ${frame.byteCount} bytes, ${frame.timestampMs}ms" }
```

This matters: when prod builds filter Verbose+Debug, lazy form has zero cost; eager form pays the string-build cost on every call.

---

## File logging — persistent crash records

Not enabled at MVP. Phase 2a Flight A consideration if needed.

**If enabled:** rotate log files daily; max 7 days retained; max 50MB total. Otherwise logs accumulate indefinitely on Clay's machine.

Implementation: kermit doesn't ship a default `FileLogWriter` — would need a custom implementation per platform (or vet `co.touchlab:kermit-koin` style ecosystem extensions at the time).

---

## Surfacing debug telemetry to the Hardware Spec Sheet

Per spec §6.3 Phase 2b Flight F, the Hardware Spec Sheet replaces a conventional About screen with audiophile-grade telemetry: DSP internal float bit-depth, filter algorithms used, pipeline latency, codec support, sample rate handling.

This is **read-from-state**, not read-from-logs. Each telemetry data point is exposed via a `StateFlow<T>` from the relevant module:

```kotlin
// :audio:playback
val playbackLatencyMs: StateFlow<Double>
val decoderInUse: StateFlow<DecoderInfo>
val sampleRateHz: StateFlow<Int>

// Hardware Spec Sheet composable reads these
@Composable
fun HardwareSpecSheet(player: PlatformPlayer) {
    val latency by player.playbackLatencyMs.collectAsState()
    Text("Pipeline latency: ${latency.format(2)}ms")
    // ...
}
```

Logs feed crash reports and post-mortem analysis; StateFlows feed live UI. Different audiences.

---

## Log-level adjustment at runtime

For troubleshooting in Phase 2a+, expose a dev-menu setting (long-press app icon, or hidden URL scheme):

```kotlin
@Composable
fun DebugSettings() {
    var verboseLogging by rememberSaveable { mutableStateOf(false) }
    Switch(checked = verboseLogging, onCheckedChange = {
        verboseLogging = it
        Logger.setMinSeverity(if (it) Severity.Verbose else Severity.Info)
    })
}
```

Verbose-on impacts perf (more string building, more log writes) — surface this in the UI so users know.

---

## CI + GitHub Actions

CI logs are stdout — kermit's `platformLogWriter()` on JVM goes to stdout, which the CI captures and surfaces in the Actions UI. No additional wiring needed.

For tests that need to capture log output:

```kotlin
class CapturingLogWriter : LogWriter() {
    val captured = mutableListOf<String>()
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        captured.add("[$severity][$tag] $message")
    }
}

@Test
fun scanner_logs_per_file_failures() {
    val capturer = CapturingLogWriter()
    Logger.setLogWriters(capturer)
    // ... run scanner with a known bad file
    assertTrue(capturer.captured.any { it.contains("Failed to read tags") })
}
```

Don't make this an everywhere pattern — log assertions are brittle; reserve for cases where logging IS the contract (e.g., scanner-per-file-failure surfacing).

---

## What this doc does NOT do

- Does NOT specify a log-aggregation backend (no Sentry, no Datadog) — Kiln is single-user; aggregation is premature
- Does NOT prescribe specific `Logger.withTag()` strings per source file — module-level prefix is sufficient
- Does NOT include a config schema for verbose-level toggling — UI surface decision happens at Phase 2a or later
- Does NOT cover platform-specific tracing (`Trace.beginSection` on Android, JFR on JVM) — those are perf tools, not logging

---

End of logging conventions.
