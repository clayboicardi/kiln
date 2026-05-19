# Error Handling Patterns — Arrow Either as Spine

**Date:** 2026-05-18 (living reference)
**Type:** Reference
**Source:** Synthesis from [spec §4 tech stack (Arrow showcase)](../superpowers/specs/2026-05-18-kiln-rebuild-design.md), [vertical-slice prep §2.6 SourceError + §5.1 DecoderError](../scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md), [glossary entry on Arrow showcase](../reference/2026-05-18-named-patterns-glossary.md).

Kiln's error-handling model uses **Arrow `Either<Error, Success>` as the spine** for fallible operations, with throws reserved for invariant violations and platform-boundary surprises. This doc consolidates the rules so future Claude (or Clay) doesn't have to re-derive them per session.

The Arrow showcase is `:audio:dsp` per spec §4. The Either pattern extends BEYOND `:audio:dsp` into `:data:library` and `:audio:playback` because those modules also have well-bounded fallible operations (`MusicSource.getPlayable`, `Decoder.open`, `LibraryScanner.scanIncremental`). Spec §4's "Arrow only in `:audio:dsp`" is about the **dependency** scope; the Either **pattern** is broader and uses arrow-core's Either type across modules that already depend on it.

---

## TL;DR — the three rules

1. **Use `Either<XxxError, Success>` for any operation that can fail in a *meaningful, business-modelable way*** — domain failures the caller may want to handle differently per case
2. **Use `throw` for invariant violations** — bugs, impossible-state assertions, broken contracts. Don't `throw` for predictable failures
3. **Never mix the two in a single function signature** — pick one. `Either<Error, T> + throws` confuses callers and breaks the showcase

When in doubt, ask: "Will any caller want to do something different per failure type?" If yes → `Either`. If every caller just bubbles or logs the same way → `throw`.

---

## When to use `Either<Error, Success>`

### Pattern A: Source / decoder / scanner operations (`:data:library`, `:audio:playback`)

These have well-bounded, sealed error hierarchies. Per vertical-slice prep §2.6:

```kotlin
sealed interface SourceError {
    data class ItemNotFound(val itemId: ItemId) : SourceError
    data class ResourceUnavailable(val reason: String) : SourceError
    data class SourceUnavailable(val reason: String) : SourceError
    data class IoError(val cause: Throwable) : SourceError
    data class Internal(val message: String, val cause: Throwable? = null) : SourceError
}

suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable>
```

**Why Either here:**
- Caller may handle `ItemNotFound` (show snackbar "track removed") differently from `ResourceUnavailable` ("file missing; remount drive?")
- The sealed hierarchy gives exhaustive `when` at the call site
- Tests can assert on specific error types without exception-handling boilerplate
- Composes cleanly with Arrow's `bind`/`zipOrAccumulate` for multi-step pipelines

### Pattern B: DSP math with bounded preconditions (`:audio:dsp`)

The Arrow showcase canonical use case:

```kotlin
sealed interface EqError {
    data class InvalidFrequency(val hz: Double, val sampleRateHz: Int) : EqError  // hz > Nyquist
    data class InvalidQ(val q: Double) : EqError                                    // q ≤ 0
    data class InvalidGain(val gainDb: Double) : EqError                            // out of [-30, +30] range
    data class FilterUnstable(val coefficients: BiquadCoefficients) : EqError       // post-design check
}

fun biquadFilter(sampleRateHz: Int, freqHz: Double, q: Double, gainDb: Double): Either<EqError, BiquadFilter>
```

**Why Either here:**
- Filter construction has well-defined invariants; violations are caller-actionable (clamp to range, show user feedback, log)
- Property-based tests via Kotest verify the precondition coverage
- Arrow showcase mandate

### Pattern C: Library scan with mixed-success semantics

```kotlin
sealed interface ScanError {
    data class PermissionDenied(val message: String) : ScanError
    data class IoError(val cause: Throwable) : ScanError
    data class Internal(val message: String) : ScanError
}

suspend fun scanIncremental(): Either<ScanError, ScanResult>
```

The scanner can also produce per-file partial failures within a successful overall scan (one file fails, others succeed). That nuance lives inside `ScanResult.failures: List<FileScanFailure>` — NOT the top-level Either. The top-level Either is "did the scan run as a whole?"; the result struct carries per-file outcomes.

---

## When NOT to use `Either` — use `throw`

### Anti-pattern: throw-and-also-catch for control flow

```kotlin
// Bad: using exceptions for normal control flow
try { source.getPlayable(itemId) } catch (e: ItemNotFoundException) { /* ... */ }
```

### Pattern D: Invariant violations

```kotlin
// Good — invariant assertion
fun process(input: FloatArray, output: FloatArray, sampleCount: Int) {
    require(input.size >= sampleCount) { "input too small: ${input.size} < $sampleCount" }
    require(output.size >= sampleCount) { "output too small: ${output.size} < $sampleCount" }
    // process ...
}
```

A caller passing mismatched buffer sizes has a bug — throw immediately. Don't model "your caller is broken" as a domain error.

### Pattern E: Platform-boundary surprises

```kotlin
// Good — let the platform exception bubble where it's unexpected
private fun openDataLine(format: AudioFormat): SourceDataLine {
    // LineUnavailableException IS a domain error we model — wrap in Either
    // But if AudioSystem.getLine returns something fundamentally wrong, throw
    val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format))
    return line as? SourceDataLine ?: error("AudioSystem returned non-SourceDataLine type: $line")
}
```

Domain-modelable platform exceptions (`LineUnavailableException` → `PlayerError.DeviceUnavailable`) get caught and converted to Either. Truly unexpected types (`error("...")`) throw.

### Pattern F: Cold-start / configuration boot

```kotlin
// At app startup, configuration errors are unrecoverable
val database = JdbcSqliteDriver(url = dbUrl)
    .also { KilnDatabase.Schema.create(it) }  // SQL syntax errors here = bug, throw
```

If `Schema.create` throws, the build is broken — there's no caller-meaningful recovery. Throw lets the crash report surface the bug.

---

## How to compose Either chains

### Single-step propagation

```kotlin
suspend fun loadAndPlay(itemId: ItemId): Either<PlayerError, Unit> = either {
    val source = ...
    val playable = source.getPlayable(itemId).bind()  // unwrap or short-circuit
    player.loadQueue(listOf(playable.toMediaItem())).bind()
    player.play().bind()
}
```

Arrow's `either { ... }` block + `.bind()` extension is the Kotlin equivalent of monadic do-notation. Failure in any `.bind()` short-circuits the whole block.

### Mapping error types

```kotlin
val playable: Either<PlayerError, Playable> = source.getPlayable(itemId).mapLeft { sourceErr ->
    when (sourceErr) {
        is SourceError.ItemNotFound -> PlayerError.ResourceMissing("Track no longer in library")
        is SourceError.ResourceUnavailable -> PlayerError.IoError(IllegalStateException(sourceErr.reason))
        // ...
    }
}
```

Use `.mapLeft` to translate one error hierarchy to another at a layer boundary. Don't make every layer use `SourceError` — each layer owns its own error type.

### Accumulating errors (multi-track operations)

```kotlin
import arrow.core.raise.zipOrAccumulate

suspend fun loadMany(itemIds: List<ItemId>): Either<NonEmptyList<SourceError>, List<Playable>> = either {
    zipOrAccumulate(
        *itemIds.map { id -> { source.getPlayable(id).bind() } }.toTypedArray()
    ) { *args -> args.toList() }
}
```

For batch operations where you want ALL the errors, not just the first, use `zipOrAccumulate`. Returns `Either<NonEmptyList<Error>, List<Success>>`.

---

## Surfacing errors to UI

### Pattern G: Error → user-facing message

```kotlin
@Composable
fun NowPlayingScreen(state: NowPlayingState) {
    when (state) {
        is NowPlayingState.Error -> {
            val message = state.cause.userMessage()
            SnackbarHost { Snackbar { Text(message) } }
        }
        // ...
    }
}

fun PlayerError.userMessage(): String = when (this) {
    is PlayerError.DeviceUnavailable -> "Audio device disconnected"
    is PlayerError.FormatUnsupported -> "Format not supported: $codec"
    is PlayerError.DecodeFailed -> "Couldn't decode this track"
    is PlayerError.IoError -> "Couldn't read the file"
    is PlayerError.Internal -> "Something went wrong: $message"
}
```

The `.userMessage()` extension lives near the sealed-hierarchy declaration. UI calls it; never reads the technical `cause` directly.

### Pattern H: Error → Telegram-style operator alert (Clay-only debug)

Vertical-slice prep §10 + risk playbook reference Telegram alerts for anomalies in Clay's broader ClaydeClaw agent. Kiln Desktop could surface "this user's app crashed" to a debug overlay or a structured log entry — NOT enabled at MVP; placeholder for Phase 2b operations.

---

## Anti-patterns to reject in code review

### A1: Bare `Result<T>` from `kotlin.Result`

```kotlin
// Bad
fun foo(): Result<Int> = Result.success(42)
```

`kotlin.Result` is intentionally limited (no proper try/catch composition, no map-error). Use Arrow's `Either` instead. The Kotlin standard library's `Result` is best for low-level coroutine continuations, not domain modeling.

### A2: Sentinel values instead of Either

```kotlin
// Bad — null as failure
suspend fun getPlayable(itemId: ItemId): Playable? = ...

// Good — explicit error type
suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> = ...
```

Nullable returns conflate "operation succeeded with no result" with "operation failed." `Either<SourceError, Playable>` distinguishes them.

### A3: `runCatching` as default

```kotlin
// Bad — eats all exceptions including bugs
fun foo(): Result<Int> = runCatching { riskyOp() }

// Good — catch only known domain failures, rethrow surprises
fun foo(): Either<DomainError, Int> = Either.catch { riskyOp() }
    .mapLeft { e ->
        when (e) {
            is IllegalArgumentException -> DomainError.InvalidInput(e.message ?: "")
            is IOException -> DomainError.IoError(e)
            else -> throw e   // rethrow surprises
        }
    }
```

`Either.catch` followed by `mapLeft` with rethrow-on-unknown is the controlled pattern. Don't blanket-catch `Throwable`.

### A4: Error types as `String` messages

```kotlin
// Bad
data class Error(val message: String)
suspend fun foo(): Either<Error, Int>

// Good
sealed interface FooError {
    data class InputInvalid(val input: String) : FooError
    data class NetworkDown(val reason: String) : FooError
    data class TimedOut(val durationMs: Long) : FooError
}
```

Strings can't be exhaustively matched and can't carry typed payload. Sealed hierarchies survive renames and let consumers branch precisely.

### A5: Mixing `throw` and `Either` in one signature

```kotlin
// Bad
fun foo(): Either<Error, Int> {
    require(precondition()) { "..." }  // throws IllegalStateException
    // ...
}
```

If `require` throws, the function's signature is a lie. Either model preconditions as `Either.Left(Error.PreconditionFailed)` OR document `@throws` clearly. Don't surprise callers.

---

## Module-specific guidance

### `:audio:dsp` (the Arrow showcase)

- All public functions that can fail use `Either<EqError, T>` (or similar bounded error types)
- BiquadFilter construction returns `Either<EqError, BiquadFilter>` — DOES NOT throw on invalid params
- Internal helpers within `:audio:dsp` may throw on invariant violations (sanity checks against bugs in our own code); externally-facing functions don't
- The error types live in `commonMain` next to the function they describe

### `:audio:playback`

- `Decoder.open(playable: Playable): Either<DecoderError, DecodedStream>` per vertical-slice prep §5.1
- `PlatformPlayer` operations (`loadQueue`, `play`, etc.) are `suspend fun` and CAN throw on truly unexpected errors; surface domain errors via `state: StateFlow<PlayerState>` (where `PlayerState.Error(cause: PlayerError)` is a state, not a return value)
- Why `suspend fun` returns Unit, not Either: playback is fire-and-state-flow — the operations don't have a single point-in-time result; the player's StateFlow IS the result. Either at the function-signature level would be redundant.

### `:data:library`

- `MusicSource.getPlayable(itemId)`: `Either<SourceError, Playable>`
- `LibraryScanner.scanIncremental()`: `Either<ScanError, ScanResult>`
- `Repository`-style aggregators: convert `Either` from underlying sources to repository-specific error types via `mapLeft`. Don't propagate `SourceError` directly to `:ui:components`

### `:ui:theme`

- Palette extraction (Phase 2a Flight A): may fail (decode error, low-saturation, contrast post-processing exhaustion). Either over a `PaletteError` sealed hierarchy
- WCAG contrast post-processing is deterministic and produces a result OR a "give up; return idle palette" — model the give-up explicitly

### `:ui:components`

- Compose composables can have errors as state (`NowPlayingState.Error(cause)`) but NOT typically as return values
- Event sinks return `Unit` and let the presenter handle errors via state transition
- Side-effect functions called from `LaunchedEffect` MAY return `Either` if the caller needs to react

---

## Testing the Either pattern

### Kotest-style happy-path + sad-path

```kotlin
class GetPlayableTest : FreeSpec({
    val source = createInMemorySourceWith(tracks = listOf(trackFixture))

    "returns Playable for known item" {
        source.getPlayable(trackFixture.id) shouldBe Either.Right(trackFixture.toPlayable())
    }

    "returns ItemNotFound for unknown item" {
        source.getPlayable(ItemId("nonexistent")) shouldBe
            Either.Left(SourceError.ItemNotFound(ItemId("nonexistent")))
    }
})
```

### Exhaustive when-match testing

For sealed error hierarchies, write a test that pattern-matches on every variant — that breaks if a new variant is added without test coverage.

```kotlin
@Test
fun userMessage_handles_all_PlayerError_variants() {
    val cases: List<PlayerError> = listOf(
        PlayerError.DeviceUnavailable("test"),
        PlayerError.FormatUnsupported("test"),
        PlayerError.DecodeFailed(RuntimeException("test")),
        PlayerError.IoError(RuntimeException("test")),
        PlayerError.Internal("test"),
    )
    // when PlayerError gains a new variant, this `cases` list compile-fails
    // because the sealed hierarchy's `when` exhaustiveness check forces an update
    cases.forEach { err ->
        assertNotNull(err.userMessage())
    }
}
```

---

## When patterns evolve

If a new pattern emerges from MVP work:
1. Add an entry here following the format above
2. Note any breaking departure from the rules
3. Engram-save under `kiln/error-handling/<pattern-name>`

---

End of error-handling patterns.
