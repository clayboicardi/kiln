# Test Infrastructure Cookbook

**Date:** 2026-05-18 (living reference; expanded as test patterns emerge)
**Type:** Reference (synthesis from plan §7 timeline + spec §8.2 coverage targets)
**Source:** [plan §7](../superpowers/plans/2026-05-18-kiln-execution-plan.md#7-test-infrastructure-timeline) + [spec §8.2](../superpowers/specs/2026-05-18-kiln-rebuild-design.md) + [vetting log Item 7](../decisions/2026-05-18-library-vetting.md#item-7-compose-mp-screenshot-testing-roborazzi)

Plan §7 establishes WHEN each test tool comes in. This cookbook adds the HOW — for each tool, the concrete pattern, setup, gotchas, and what NOT to test.

**Core principle (plan §0):** test infrastructure spreads gradually. Don't pre-install testing tools before a use case justifies them. The tools land in the sessions where their killer-app use case appears.

---

## When each tool lands (plan §7 timeline)

| When | Tool | Killer-app use case |
|---|---|---|
| MVP Session 1-3 | `kotlin.test` + JUnit5 + GitHub Actions CI | Smoke tests; build matrix passes |
| MVP Session 4 | `MusicSource` interface conformance tests | First contract test |
| MVP Session 5-6 | SQLDelight in-memory testing | Library scan + browse queries |
| MVP Session 6 | Mokkery | Mock `PlatformPlayer`, `Decoder` for repository tests |
| MVP Session 6-7 | JNA-libFLAC empirical smoke test | Vetting Item 9 addendum verification |
| MVP Session 8 | Compose UI test (`compose-ui-test`) | Critical-path UI tests |
| MVP Session 12-15 | Turbine | Now Playing Circuit presenter Flow assertions |
| MVP Session 16-22 | Kotest property-based | BiquadFilter math correctness — DSP is the killer-app for property tests |
| **MVP Session 16-22** (revised post-Gemini) | **Microbenchmark/JMH** | **DSP perf smoke test BEFORE building EQ UI on top** |
| Phase 2a Flight A | Roborazzi | Kiln Dynamic theming regression across diverse album art |
| Phase 2a Flight E | JMH extended | FFT hot-path perf |
| Phase 2b Flight G | Mutation testing | Library-quality gate before tagged publish |
| Phase 2b ongoing | Compose accessibility checks | A11y audit |
| Phase 3 | E2E flow tests | Measurement workflow validation |

Spec §8.2 coverage targets:

| Module | Target | Notes |
|---|---|---|
| `:audio:dsp` | ~90% + property-based | DSP math is deterministic and benefits from generative testing |
| `:audio:visualizer` | ~90% + property-based | FFT math correctness |
| `:audio:playback` | ~80% | Platform adapters can't be fully tested without instrumentation |
| `:data:library` | ~75% | `MusicSource` interface conformance tests |
| `:ui:theme` | ~85% | Palette extraction + WCAG post-processing is deterministic |
| `:ui:components` | UI tests + screenshot tests + Circuit presenter tests | Mixed approach |
| `:app-android`, `:app-desktop` | Light smoke + E2E | Wiring code; verify end-to-end happy paths |

---

## 1. `kotlin.test` + JUnit5 — the baseline

**Lands at:** MVP Session 1-3 scaffold

**Setup** (already in libs.versions.toml skeleton per scaffold prep §2):

```kotlin
// In a module's build.gradle.kts:
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Or as part of the kiln.kmp.library convention plugin
```

`kotlin.test` is a thin wrapper around platform-native test frameworks (JUnit5 on JVM, XCTest on iOS, etc.). Use it as the default; reach for Kotest only when property-based / data-driven tests earn their keep.

**Pattern — basic unit test:**

```kotlin
// :audio:dsp/src/commonTest/kotlin/com/clayworks/kiln/audio/dsp/BiquadFilterTest.kt
package com.clayworks.kiln.audio.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiquadFilterTest {
    @Test
    fun lowpass_at_0Hz_passes_unchanged() {
        val filter = BiquadFilter.lowpass(sampleRateHz = 44100, cutoffHz = 1000.0, q = 0.707)
        val dc = FloatArray(64) { 1.0f }
        val out = FloatArray(64)
        filter.process(dc, out, 64)
        // After a few warmup samples, output should converge to 1.0
        for (i in 16..63) {
            assertTrue(out[i] in 0.99f..1.01f, "sample $i: ${out[i]}")
        }
    }
}
```

**Gotchas:**
- `kotlin-test` in `commonTest` uses JUnit5 on JVM; matches the AGP test-runner expectation
- Avoid platform-specific imports in `commonTest` (Java assertions, etc.) — use `kotlin.test.*` only

**Don't test:**
- Trivial getters / setters — wastes lines
- Compose UI internals — that's Compose's job
- Coroutines machinery — use `runTest` and trust it

---

## 2. `kotlinx-coroutines-test` — for `suspend` and Flow code

**Lands at:** MVP Session 4 (first `MusicSource` tests)

**Setup:** already pulled by `kotlin.test` block above.

**Pattern — `runTest` with virtual time:**

```kotlin
// :data:library/src/commonTest/kotlin/.../LocalLibrarySourceTest.kt
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalLibrarySourceTest {
    @Test
    fun browse_all_tracks_returns_indexed_tracks() = runTest {
        val source = createInMemorySource(seedTracks = listOf(/* synthetic tracks */))

        val items = source.browse(BrowseScope.AllTracks()).toList()

        assertEquals(2, items.size)
    }
}
```

**Gotchas:**
- `runTest` provides virtual time — `delay(1000)` doesn't actually sleep
- For real-time-dependent code (audio playback), use `runBlocking` instead — never `runTest` for the audio dispatcher path
- `runTest` plus actual file I/O is fine; the virtual time only affects `delay` and `withTimeout`

---

## 3. SQLDelight in-memory testing

**Lands at:** MVP Session 5-6 (first library scan + query tests)

**Setup:** SQLDelight ships an in-memory JDBC driver for jvm tests:

```kotlin
// :data:library/src/commonTest/kotlin/.../TestDatabaseFactory.kt
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.clayworks.kiln.library.db.KilnDatabase

internal fun createInMemoryDatabase(): KilnDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        KilnDatabase.Schema.create(it)
    }
    return KilnDatabase(driver)
}
```

Wrap in a test fixture pattern so tests don't repeat the boilerplate.

**Pattern — repository test against in-memory DB:**

```kotlin
class TrackQueriesTest {
    private lateinit var db: KilnDatabase

    @BeforeTest
    fun setup() {
        db = createInMemoryDatabase()
        seedSchemaWithTestData(db)
    }

    @Test
    fun searchTracks_matches_partial_title() {
        db.trackSearchQueries.replace(rowid = 1L, title = "Radiohead Karma Police", /* ... */)
        db.trackSearchQueries.replace(rowid = 2L, title = "Beatles Hey Jude", /* ... */)

        val results = db.trackSearchQueries.searchTracks("radio*", limit = 10L).executeAsList()

        assertEquals(listOf(1L), results.map { it.rowid })
    }
}
```

**Gotchas:**
- The in-memory driver is JVM-only; for `commonTest` that targets both JVM and Android, this test belongs in `jvmTest` source set
- Migration tests use SQLDelight's `verifyMigrations` Gradle task, not unit tests
- FTS5 is available in the in-memory driver; if the platform driver lacks it (vetting log Item 6 sanity check), in-memory tests will still pass even when the real device fails

**Don't test:**
- Schema constraints in unit tests — `verifyMigrations` catches schema drift
- SQLDelight's generated code — that's their job

---

## 4. Mokkery — KMP-friendly mocking

**Lands at:** MVP Session 6 (mocking `PlatformPlayer`, `Decoder` for higher-layer tests)

**Why Mokkery, not MockK:** Mokkery works in KMP `commonTest` (MockK is JVM-only). Setup goes through KSP, so the plugin is applied at the module level.

**Setup:**

```kotlin
// :ui:components/build.gradle.kts (when this module first needs mocking)
plugins {
    alias(libs.plugins.mokkery)
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            // mokkery-runtime auto-applied by plugin
        }
    }
}
```

**Pattern — mocking a Decoder for a player test:**

```kotlin
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import dev.mokkery.verify

class JavaSoundPlayerImplTest {
    @Test
    fun loadQueue_resolves_decoder_for_FLAC() = runBlocking {
        val mockDecoder = mock<Decoder> {
            every { supports(AudioCodec.FLAC) } returns true
            every { open(any()) } returns Either.Right(fakeDecodedStream())
        }
        val resolver = DefaultDecoderResolver(listOf(mockDecoder))
        val player = JavaSoundPlayerImpl(resolver, testAudioDispatcher)

        player.loadQueue(listOf(flacMediaItem()))

        verify { mockDecoder.open(any()) }
    }
}
```

**Gotchas:**
- Mokkery requires `interface` or `open class` to mock (can't mock final classes by default)
- Coroutine `verify` blocks must use `verifySuspend` for `suspend` function calls
- Don't mock data classes — construct them directly

**Don't mock:**
- The thing under test (mock its collaborators)
- Standard library types (`List`, `Map`, etc.) — construct real instances
- Anything in `:audio:dsp/commonMain` — these are pure functions; test them directly

---

## 5. Turbine — Flow assertion library

**Lands at:** MVP Session 12-15 (Now Playing Circuit presenter Flow tests)

**Why Turbine:** asserting on Flow emissions in tests is verbose without help. Turbine adds a clean `expectItem()` / `expectError()` API.

**Setup:** dep already in scaffold prep `libs.versions.toml`:

```kotlin
// :ui:components/build.gradle.kts
kotlin.sourceSets.commonTest.dependencies {
    implementation(libs.turbine)
}
```

**Pattern — Now Playing presenter state transitions:**

```kotlin
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow

class NowPlayingPresenterTest {
    @Test
    fun presenter_emits_Loading_then_Ready_when_player_state_settles() = runTest {
        val playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
        val fakePlayer = fakePlatformPlayer(stateFlow = playerState)

        moleculeFlow(mode = RecompositionMode.Immediate) {
            NowPlayingPresenter(fakePlayer, fakeQueueRepo)
        }.test {
            assertEquals(NowPlayingState.Loading, awaitItem())

            playerState.value = PlayerState.Ready(isPlaying = false)
            val ready = awaitItem() as NowPlayingState.Ready
            assertEquals(false, ready.isPlaying)

            playerState.value = PlayerState.Ready(isPlaying = true)
            val playing = awaitItem() as NowPlayingState.Ready
            assertEquals(true, playing.isPlaying)
        }
    }
}
```

**Gotchas:**
- Turbine's `test { … }` block is a coroutine receiver; `runTest` outside it provides the dispatcher
- For Compose-MP presenters via Molecule, use `RecompositionMode.Immediate` to flush state changes eagerly
- `awaitItem()` blocks until emission; if it times out, the test fails — usually means the flow didn't emit what you expected

---

## 6. Kotest property-based — generative testing

**Lands at:** MVP Session 16-22 (BiquadFilter math). DSP correctness is the killer-app for property tests.

**Why Kotest:** `kotlin.test` doesn't have generative testing. Kotest's property module provides arbitraries (`Arb.int(...)`, `Arb.float(...)`, etc.) and `checkAll { … }` to run hundreds of randomized cases.

**Setup:**

```kotlin
// :audio:dsp/build.gradle.kts
kotlin.sourceSets.commonTest.dependencies {
    implementation(libs.bundles.kotest)   // runner + assertions + property
}
```

`kotest-runner-junit5` plus the JUnit5 platform configuration on the test task.

**Pattern — BiquadFilter property:**

```kotlin
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

class BiquadFilterPropertyTest : FreeSpec({
    "lowpass with cutoff above Nyquist degenerates to identity" {
        checkAll(
            Arb.int(min = 8000, max = 96000),     // sampleRateHz
            Arb.float(min = 0.0f, max = 1.0f),    // sample value
        ) { sampleRate, sampleValue ->
            val nyquist = sampleRate / 2.0
            val cutoffWayAboveNyquist = nyquist * 1.5
            val filter = BiquadFilter.lowpass(sampleRate, cutoffWayAboveNyquist, q = 0.707)

            val input = FloatArray(64) { sampleValue }
            val output = FloatArray(64)
            filter.process(input, output, 64)

            // After warmup, output equals input
            output[63] shouldBeWithinPercentageOf sampleValue.toDouble() 1.0
        }
    }

    "two cascaded filters are NOT equivalent to a single filter with squared response" {
        // (a property documenting a known truth — cascading biquads doesn't simply square response)
    }
})
```

**Gotchas:**
- Use `FreeSpec` for nested "describe / when / it" structure; or `StringSpec` for flat single-line names
- `checkAll` defaults to 1000 iterations on JVM — keep test bodies fast
- Floating-point comparison: use `shouldBeWithinPercentageOf` or `plusOrMinus`, not `==`
- Define `Arb<T>` for domain types (e.g., `Arb<BiquadCoefficients>`) when same generators recur

**Don't property-test:**
- Inherently sequential / ordering-dependent code (use specific scenarios)
- I/O — generative testing on file paths or DB rows is a recipe for flaky tests
- Compose UI — that's screenshot tests

---

## 7. Microbenchmark / JMH — DSP perf smoke test

**Lands at:** MVP Session 16-22 (revised post-Gemini critique — moved from Phase 2a Flight F to MVP)

**Why:** the 31-band parametric EQ runs in the playback hot path. Plan §3.2 mandates a perf smoke test BEFORE building the EQ UI: verify the BiquadFilter chain processes a 10-second float-PCM buffer without GC pauses. Use pre-allocated primitive arrays.

**Setup (JVM-only — Android side uses androidx.benchmark separately):**

```kotlin
// :audio:dsp/build.gradle.kts
plugins {
    id("me.champeau.jmh") version "0.7.2"
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
}
```

**Pattern — BiquadFilter chain benchmark:**

```kotlin
// :audio:dsp/src/jmh/kotlin/com/clayworks/kiln/audio/dsp/BiquadChainBenchmark.kt
package com.clayworks.kiln.audio.dsp

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class BiquadChainBenchmark {
    private val sampleRate = 44100
    private val tenSeconds = sampleRate * 10
    private val buffer = FloatArray(tenSeconds)
    private val output = FloatArray(tenSeconds)
    private val chain = ParametricEqChain.thirtyOneBand(sampleRate)

    @Setup
    fun fillBuffer() {
        for (i in buffer.indices) buffer[i] = (Math.random() * 2.0 - 1.0).toFloat()
    }

    @Benchmark
    fun process_31band_10sec_buffer(): FloatArray {
        chain.process(buffer, output, tenSeconds)
        return output
    }
}
```

**Exit criteria for the smoke test:** processes the 10-sec buffer in <100ms wall-clock with zero GC pauses (verify via `-XX:+PrintGC`). Anything slower threatens the playback hot path.

**Gotchas:**
- JMH benchmarks must escape result via `@Benchmark fun foo(): T` or `Blackhole.consume(...)` — otherwise JIT may dead-code-eliminate
- `@State(Scope.Benchmark)` shared across iterations — `@Setup` runs once before all
- Pre-allocated buffers MUST be reused — allocation inside `@Benchmark` measures GC, not the algorithm

**Don't benchmark:**
- Compose UI — JMH is JVM-only and Compose lifecycle isn't a fit
- I/O-bound code — variance is dominated by external factors

---

## 8. Roborazzi — Compose-MP screenshot regression

**Lands at:** Phase 2a Flight A (first use case is Kiln Dynamic theming regression across diverse album art)

**Setup** (per vetting log Item 7 + scaffold prep §5.5 convention plugin):

```kotlin
// :ui:theme/build.gradle.kts
plugins {
    alias(libs.plugins.roborazzi)
}

kotlin.sourceSets.jvmTest.dependencies {
    implementation(libs.bundles.roborazzi)
    implementation(libs.compose.ui.test.junit4)
}
```

**Pattern — theme regression test:**

```kotlin
// :ui:theme/src/jvmTest/kotlin/.../KilnDynamicThemeScreenshotTest.kt
package com.clayworks.kiln.ui.theme

import androidx.compose.ui.test.junit4.runDesktopComposeUiTest
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.test.Test

class KilnDynamicThemeScreenshotTest {
    @Test
    @OptIn(ExperimentalTestApi::class)
    fun dynamic_theme_handles_high_saturation_album_art() = runDesktopComposeUiTest {
        setContent {
            KilnTheme(dynamicPalette = paletteFromFixture("high-saturation.png")) {
                ThemePreviewSurface()
            }
        }
        val opts = RoborazziOptions(
            recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0F),
        )
        onRoot().captureRoboImage(roborazziOptions = opts)
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun dynamic_theme_falls_back_on_low_saturation_album_art() = runDesktopComposeUiTest {
        setContent {
            KilnTheme(dynamicPalette = paletteFromFixture("low-saturation-monochrome.png")) {
                ThemePreviewSurface()
            }
        }
        onRoot().captureRoboImage(roborazziOptions = defaultRoborazziOpts)
    }
}
```

**Test fixture organization:**

```
:ui:theme/src/jvmTest/
  kotlin/.../KilnDynamicThemeScreenshotTest.kt
  resources/test-album-art/
    high-saturation.png
    low-saturation-monochrome.png
    bright-pastel.png
    no-art-fallback.png
  snapshots/    ← committed PNG snapshots
```

**Gradle tasks:**
- `./gradlew :ui:theme:recordRoborazziDebug` — regenerate snapshots after intentional UI changes
- `./gradlew :ui:theme:verifyRoborazziDebug` — CI gate; fails if snapshots drift
- `./gradlew :ui:theme:compareRoborazziDebug` — generates visual diff HTML

**Gotchas:**
- `@OptIn(ExperimentalTestApi::class)` required — Compose-MP's test API is still experimental (vetting Item 7 note)
- `changeThreshold = 0F` for theme tests = zero pixel tolerance; loosen to `0.001F` for animation/transition tests
- Snapshots are platform-specific; only run on the platform where snapshots were recorded (Roborazzi gates by `os.name`)

**Don't screenshot-test:**
- Animated UI mid-animation (capture stable states only)
- Anything platform-themed (system fonts, system colors) that varies between dev machines
- UI consuming live system state (clocks, network status) — fix the state before capture

---

## 9. Compose UI test — interaction testing

**Lands at:** MVP Session 8 (first library views)

**Setup:** Compose-MP ships `compose-ui-test-junit4`; already in `libs.versions.toml`.

**Pattern — interaction test on a list:**

```kotlin
@Test
@OptIn(ExperimentalTestApi::class)
fun clicking_track_calls_onPlay_callback() = runDesktopComposeUiTest {
    var clickedTrackId: TrackId? = null
    setContent {
        TrackList(
            tracks = listOf(track1, track2, track3),
            onPlayClicked = { clickedTrackId = it },
        )
    }
    onNodeWithText(track2.title).performClick()
    assertEquals(track2.id, clickedTrackId)
}
```

**Gotchas:**
- `runComposeUiTest` (multiplatform) vs `runDesktopComposeUiTest` (JVM-specific) — use the multiplatform variant if the test runs across platforms; the desktop one for desktop-only specifics
- `testTag` on composables makes finding nodes deterministic; better than relying on text matches in i18n contexts
- Don't put critical assertions inside `LaunchedEffect` — use a `produceState`-style hook the test can observe

---

## 10. JNA-libFLAC empirical smoke test

**Lands at:** MVP Session 4-7 (vetting Item 9 addendum verification gate)

**Why this is special:** plan §3.2 Sessions 4-7 explicitly gates exit on this test passing. Different from a unit test — it's an end-to-end byte-equivalence check against ffmpeg.

**Setup:** test class lives in `:audio:playback/src/jvmTest/`. Reference files NOT committed (Clay's library); test reads from a config file specifying paths.

**Pattern:**

```kotlin
// :audio:playback/src/jvmTest/kotlin/.../FlacDecodeSmokeTest.kt
@DisabledIf("Not running on Clay's machine (no test corpus)", value = "noFlacCorpus")
class FlacDecodeSmokeTest {
    @ParameterizedTest
    @MethodSource("flacTestFixtures")
    fun decodes_byte_equivalent_to_ffmpeg(fixture: FlacFixture) {
        val (libFlacPcm, format) = decodeViaJnaLibFlac(fixture.path)
        val ffmpegPcm = decodeViaFfmpegSubprocess(fixture.path, targetFormat = format.sampleFormat)

        assertArrayEquals(ffmpegPcm, libFlacPcm,
            "Byte mismatch for ${fixture.path} at format ${format}")
    }

    companion object {
        @JvmStatic
        fun flacTestFixtures(): List<FlacFixture> = readTestCorpusConfig()
            .files.map { FlacFixture(Path.of(it.path), it.expectedBitDepth, it.expectedSampleRate) }
    }
}

data class FlacFixture(val path: Path, val expectedBitDepth: Int, val expectedSampleRate: Int)
```

**Test corpus config** (Clay-local file, gitignored):

```yaml
# :audio:playback/src/jvmTest/resources/flac-test-corpus.yaml.example
files:
  - path: C:\Users\chawo\Music\test-corpus\cd-quality.flac
    expectedBitDepth: 16
    expectedSampleRate: 44100
  - path: C:\Users\chawo\Music\test-corpus\hi-res-24-96.flac
    expectedBitDepth: 24
    expectedSampleRate: 96000
  # ... 10 fixtures total per vertical-slice prep §10
```

The `.gitignore` entry: `flac-test-corpus.yaml` (Clay's actual paths); the `.example` template is committed.

**Gotchas:**
- ffmpeg must be on PATH for the comparison; CI skips this test (Clay-local only)
- 24-bit PCM byte packing is subtle: 3 bytes per sample, little-endian, sign-extended; verify both sides use the same packing
- Different libFLAC versions may differ in their MD5 output ordering even with byte-identical PCM; compare the actual PCM bytes, not metadata-block hashes

---

## 11. Mutation testing — Phase 2b Flight G library-quality gate

**Lands at:** Phase 2b Flight G (`:audio:dsp` + `:audio:visualizer` extraction to JitPack)

**Why mutation testing then:** test coverage is necessary but not sufficient. Mutation testing seeds bugs into the code and verifies tests catch them. Earned its keep for libraries the world depends on; over-engineering for app modules.

**Tool candidates:**
- **Pitest** (`pitest`) — JVM-targeted mutation testing; well-maintained; integrates with Gradle. JVM-only — won't run on `commonMain` directly but works on JVM-compiled output
- **Kotlin-specific tooling** — emerging space; check at Phase 2b Flight G time for the current best option

**Setup (Pitest, sketch — verify at Phase 2b Flight G):**

```kotlin
// :audio:dsp/build.gradle.kts
plugins {
    id("info.solidsoft.pitest") version "1.15.0"
}

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(listOf("com.clayworks.kiln.audio.dsp.*"))
    mutators.set(listOf("DEFAULTS"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    avoidCallsTo.set(listOf("kotlin.jvm.internal"))
}
```

**Exit criteria for the gate:** ≥85% mutation score on the library's hot-path classes before tagging the first stable release.

**Gotchas:**
- Mutation testing is slow (10-100× slower than unit tests); run as a periodic CI job, not on every PR
- Kotlin-specific mutators are less mature than Java mutators; some Kotlin idioms (when expressions, sealed hierarchies) don't get mutated effectively
- Coverage shows "lines hit"; mutation shows "bugs caught" — two different signals

---

## 12. Accessibility audits — Phase 2b ongoing

**Lands at:** Phase 2b ongoing (plan §7)

**Tooling:**
- Android: `androidx.compose.ui:ui-test` includes `assertIsDisplayed`, accessibility node tree, `SemanticsActions`
- Desktop: more manual — Compose-MP accessibility surface is less mature on JVM as of 2026; check at Phase 2b time

**Pattern — basic Android accessibility audit:**

```kotlin
@Test
fun all_interactive_elements_have_content_descriptions() = runComposeUiTest {
    setContent { KilnApp() }

    onAllNodes(isClickable()).fetchSemanticsNodes().forEach { node ->
        assertNotNull(
            node.config.getOrNull(SemanticsProperties.ContentDescription),
            "Clickable node ${node.id} missing contentDescription",
        )
    }
}
```

**Gotchas:**
- Phase 2b is far-future; vet the current best tooling at that time
- Manual user testing complements automated audits — Clay's mother / sister may surface accessibility issues automation misses (CLAUDE.md notes mother is a business owner; Clay's "Technological Altruist" trait applies)

---

## 13. E2E flow tests — Phase 3

**Lands at:** Phase 3 measurement workflow (place phone → measure → save preset)

**Tooling:** likely Maestro or Appium for cross-platform E2E. Vet at Phase 3 time.

**Pattern:** out of scope for this cookbook; revisit at Phase 3 prep.

---

## Cross-cutting conventions

### Test fixture organization

```
<module>/src/<sourceSet>Test/
  kotlin/
    com/clayworks/kiln/<module>/
      <ClassName>Test.kt              ← unit tests
      <ClassName>PropertyTest.kt      ← Kotest property tests
      <ClassName>ScreenshotTest.kt    ← Roborazzi tests (jvmTest only)
      fixtures/
        FakePlatformPlayer.kt          ← reusable test doubles
        TestData.kt                    ← seed data builders
  resources/
    test-album-art/                    ← image fixtures for theme tests
    test-corpus/                       ← FLAC files (gitignored except .example)
  snapshots/                           ← Roborazzi PNG snapshots (committed)
```

### Naming conventions

| Test type | File suffix | Class suffix |
|---|---|---|
| Unit | `<Name>Test.kt` | `<Name>Test` |
| Property | `<Name>PropertyTest.kt` | `<Name>PropertyTest` |
| Screenshot | `<Name>ScreenshotTest.kt` | `<Name>ScreenshotTest` |
| Smoke (empirical) | `<Name>SmokeTest.kt` | `<Name>SmokeTest` |
| Integration | `<Name>IntegrationTest.kt` | `<Name>IntegrationTest` |
| Benchmark (JMH) | `<Name>Benchmark.kt` | `<Name>Benchmark` |

### Test method naming

Use sentence-case names with underscores, NOT `should_do_X_when_Y` (verbose). Examples from this doc:
- `lowpass_at_0Hz_passes_unchanged`
- `searchTracks_matches_partial_title`
- `clicking_track_calls_onPlay_callback`
- `decodes_byte_equivalent_to_ffmpeg`

Reads like a clinical observation; matches Clay's "Clinical Information Density" preference.

### CI integration

Per scaffold prep §8, the CI workflow runs `./gradlew check` which transitively runs all enabled test types. Mutation testing and JMH benchmarks are separate jobs that run on a slower cadence (weekly nightly cron).

---

## What this cookbook does NOT do

- Does NOT prescribe an exact coverage percentage per module beyond spec §8.2 targets — strict coverage gating breeds bad tests
- Does NOT include test patterns for code that doesn't yet exist — `LibraryScanner`, `JvmFlacDecodedStream`, `Kiln Dynamic` palette extraction patterns get tests written at the session that implements them
- Does NOT replace plan §7 timeline — that's the canonical "when each tool comes in" doc; this cookbook is "how to write the tests"
- Does NOT specify Android instrumentation testing (Espresso, etc.) — Kiln's MVP architecture pushes most logic to `commonMain` where unit tests suffice; instrumentation revisits at Phase 2b if it earns its keep

---

End of cookbook.
