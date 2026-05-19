# Performance Budgets

**Date:** 2026-05-18 (living reference; revised as empirical data lands)
**Type:** Reference
**Source:** Consolidates perf targets from [spec §8.2 coverage table](../superpowers/specs/2026-05-18-kiln-rebuild-design.md), [vetting log Item 12 LazyColumn](../decisions/2026-05-18-library-vetting.md), [schema sketch §7 perf projection](../decisions/2026-05-18-sqldelight-schema-sketch.md), [vertical-slice prep §10 FLAC smoke test](../scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md), [test cookbook §7 JMH gates](../reference/2026-05-18-test-infrastructure-cookbook.md).

Per Clay's "Accuracy-Maximizer" preference: concrete numeric targets are accountability anchors. This doc gathers all performance commitments in one place — a single grep target for "what's the perf bar I should be hitting on this code path?"

When a measurement misses budget by >20%, raise it in the session closeout per plan §11. Persistent misses trigger the relevant risk-playbook entry (R14 effort overrun if also a scope miss).

---

## How to use

Each phase's table lists concrete numeric targets for hot code paths. Three columns: **Target** (must-hit), **Stretch** (nice-to-have), **Fail threshold** (raise as risk if exceeded).

**Measurement disciplines:**
- Wall-clock vs CPU time: prefer wall-clock unless specifically benchmarking algorithms (`JMH` annotated `Mode.AverageTime`)
- 95p vs mean: prefer 95p for user-perceivable latency; mean for throughput tests
- Hardware reference: Clay's Pixel 10 Pro XL (Tensor G5, Android 14+) AND Clay's desktop (i5-13400F, 32GB DDR5, NVMe SSD, RTX 4060). Numbers below are calibrated for both unless noted

---

## MVP-1.0 budgets

### Library scan (cold + incremental)

Per spec §6.1 MVP exit criterion: "Library scans his FLAC folders, indexes 39.5k tracks within ~5 minutes."

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| Cold full scan, 39,500 FLAC files, Desktop | <300s (5 min) | <120s (2 min) | >600s | spec §6.1 MVP exit |
| Cold full scan, 39,500 FLAC files, Android | <600s (10 min) | <240s (4 min) | >1200s | bounded by MediaStore + storage I/O variability |
| Incremental scan (no changes since last) | <10s | <3s | >30s | new — set at MVP Session 5 |
| Incremental scan (<100 file changes) | <30s | <10s | >120s | new — set at MVP Session 5 |
| Single-track tag read (jaudiotagger, Desktop) | <50ms 95p | <20ms 95p | >200ms 95p | new — set at MVP Session 5 |

Bottleneck for cold scan: tag-reader I/O. Optimization at MVP Session 5-6 if needed: parallel tag reads via `flatMapMerge(concurrency = 8)`.

### Database (SQLDelight) query latency on 39.5k tracks

Per schema sketch §7 perf projection.

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| FTS5 type-ahead "rad*" returns 50 tracks | <20ms 95p | <10ms 95p | >100ms 95p | schema sketch §7 |
| Full-track-list page (LIMIT 100 OFFSET N, indexed sort) | <5ms 95p | <2ms 95p | >20ms 95p | schema sketch §7 |
| All tracks in an album (avg ~13 rows) | <2ms 95p | <1ms 95p | >10ms 95p | schema sketch §7 |
| All tracks by an artist (avg ~80 rows) | <5ms 95p | <2ms 95p | >20ms 95p | schema sketch §7 |
| Recently added LIMIT 50 (DESC index scan) | <2ms 95p | <1ms 95p | >10ms 95p | schema sketch §7 |
| Single-track upsert (transaction, including FTS update) | <5ms 95p | <2ms 95p | >20ms 95p | new — verify at MVP Session 5 |

**Storage budget:** DB on disk for 39.5k tracks ≤50MB. Schema sketch projects 35-40MB; allow ~25% margin for B-tree index overhead.

### Audio playback hot path

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| Java Sound output latency (write→DAC), Desktop | <100ms 95p | <50ms 95p | >300ms 95p | vetting Item 9 (MME/WaveOut is 30-100ms typical) |
| Media3 ExoPlayer output latency, Android | <150ms 95p | <80ms 95p | >300ms 95p | bounded by Android audio stack |
| Buffer underrun count per 30-min playback | 0 | 0 | >5 | new — set at MVP Session 6-7 |
| `PlayerState.Ready → Ready(playing=true)` after play() | <50ms 95p | <20ms 95p | >200ms 95p | new — set at MVP Session 6-7 |
| Skip-to-next track transition | <100ms 95p | <50ms 95p | >300ms 95p | gapless playback not required at MVP |

### FLAC decoding (JNA libFLAC, Desktop)

Per vertical-slice prep §10 smoke test gate.

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| Decode 1 second of 24/96 stereo FLAC | <100ms wall | <50ms wall | >500ms wall | new — set at MVP Session 6-7 |
| Decode 10-second 24/96 buffer (smoke test) | <500ms wall | <200ms wall | >2000ms wall | new — set at MVP Session 6-7 |
| Decoder open (first frame produced) | <100ms 95p | <50ms 95p | >500ms 95p | new — set at MVP Session 6-7 |
| Seek to arbitrary position (libFLAC seek_absolute) | <30ms 95p | <10ms 95p | >100ms 95p | new — set at MVP Session 6-7 |
| Byte-equivalence vs ffmpeg reference | **100% match** | n/a | **any mismatch fails** | vertical-slice prep §10 |

### Compose UI (both platforms)

Per vetting Item 12 LazyColumn gate.

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| LazyColumn 40k items hot scroll, 95p frame time | <16.6ms (60fps) | <10ms | >33ms (sustained jank) | vetting Item 12 |
| LazyColumn 40k items hot scroll, 99p frame time | <33ms | <16.6ms | >50ms | vetting Item 12 |
| First app-launch to Library view visible | <2s | <1s | >5s | new — set at MVP Session 8 |
| Now Playing screen open latency | <300ms | <150ms | >800ms | new — set at MVP Session 12-15 |
| Mini-player update on track-change | <100ms | <50ms | >300ms | new — set at MVP Session 12-15 |

---

## Phase 2a budgets

### Flight A — Kiln Dynamic theming

Per spec §5.3 / §5.4 + vetting Item 3 + vertical-slice prep §2 capability discussion.

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| kmpalette extraction on 500×500 album art | <150ms 95p | <80ms 95p | >500ms 95p | vetting Item 3 (50-150ms projected) |
| WCAG contrast post-processing | <5ms 95p | <2ms 95p | >20ms 95p | new (deterministic algorithm) |
| Theme transition animation frame budget | <16.6ms 95p | <10ms 95p | >33ms | Compose-MP 60fps target |
| Palette cache lookup (memory hit) | <0.1ms 95p | <0.05ms 95p | >1ms 95p | new (hash map lookup) |
| Palette cache miss → extraction → cache write | <200ms 95p | <100ms 95p | >800ms 95p | sum of extract + post-process |

### Flight C — EQ refinements

Per spec §6.1 EQ + plan §3.2 Sessions 16-22 DSP perf smoke gate.

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| 31-band parametric EQ on 10-sec 44.1kHz stereo buffer (JMH) | <50ms wall | <20ms wall | >100ms wall | plan §3.2 Sessions 16-22 |
| Single biquad filter on 1-sec 44.1kHz mono (JMH baseline) | <2ms wall | <1ms wall | >5ms wall | new — baseline for chain math |
| EQ preset switch (energy-preserving crossfade window) | 10ms ± 1ms | n/a | >50ms | spec §6.1 / plan §4 Flight C |
| GC pauses during 30-min playback with EQ active | 0 | 0 | ≥1 | plan §3.2 Sessions 16-22 mandate |

### Flight E — FFT visualizer

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| FFT (1024-point, Cooley-Tukey) on hot path | <0.5ms 95p | <0.2ms 95p | >2ms 95p | new — Phase 2a Flight E gate |
| Visualizer Canvas redraw at 60fps | <16.6ms 95p | <10ms 95p | >33ms | Compose-MP visualizer target |
| FFT property-test correctness (Kotest) | 100% pass | n/a | any failure | test cookbook §6 |

---

## Phase 2b budgets

### Flight F — Hardware Spec Sheet (no perf gate; documentation surface)

### Flight G — Library extraction

Per library-extraction roadmap §3f + test cookbook §11.

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| Mutation score on `:audio:dsp` hot-path classes (Pitest) | ≥85% | ≥90% | <75% | library-extraction roadmap §3f |
| Mutation score on `:audio:visualizer` hot-path classes | ≥85% | ≥90% | <75% | same |
| Module cold-build time (clean → JAR) | <30s | <15s | >60s | new |
| Public API surface size (count of `public` declarations) | <50 per module | <30 per module | >100 per module | enforces "explain in 200 words" |

### Flight H — AAudio MMAP (IF built — soft-lock revisit at end of Phase 2a)

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| AAudio MMAP output latency, Android (USB DAC) | <5ms 95p | <2ms 95p | >20ms 95p | bit-perfect commitment if built |
| Track transition (gapless, custom-impl) | <50ms wall | <20ms wall | >200ms wall | re-implementation from MVP gapless |

### Flight I — WASAPI shared mode (IF built)

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| WASAPI shared-mode output latency, Windows | <30ms 95p | <15ms 95p | >60ms 95p | improvement over MME's 30-100ms |
| Sample-rate conversion accuracy (vs ffmpeg `-resampler` reference) | <-90dB error | <-100dB error | >-80dB error | audiophile-grade SRC commitment |

---

## Phase 3 budgets

### Room correction measurement workflow

Per plan §6 Phase 3 risks (DSP R&D, not 3-week sprint).

| Operation | Target | Stretch | Fail threshold | Source |
|---|---|---|---|---|
| Sample-accurate latency measurement (mic capture, single round trip) | ±5 samples (at 48kHz, ±0.1ms) | ±1 sample | >±20 samples | plan §6 |
| Sweep generation (logarithmic, 20Hz-20kHz, 10sec) | <100ms wall | n/a | n/a | offline; not user-blocking |
| FFT-based response analysis (sweep capture → frequency response) | <5s wall | <2s wall | >30s | offline; user waits |
| Correction curve synthesis (minimum-phase or linear-phase, TBD) | <2s wall | <1s wall | >10s | offline |

**Note on Phase 3 honesty:** plan §6 explicitly flags Phase 3 as DSP R&D. May require dropping to C++ (Oboe). Performance budgets here are aspirational; revisit when Phase 3 prep actually begins.

---

## Cross-cutting budgets

### Memory

| Operation | Target | Fail threshold | Source |
|---|---|---|---|
| Total app memory (Pixel 10 Pro XL, idle Now Playing) | <300MB | >800MB | new — set at MVP Session 8 |
| Total app memory (Desktop, idle Now Playing) | <500MB | >1.5GB | bounded by JRE + Skia |
| Library `List<Track>` materialization (39.5k tracks) | ~16MB | >50MB | schema sketch §7 estimate |
| Palette cache (3k albums × ~50 bytes) | ~150KB | >5MB | vetting Item 3 |
| Audio decoder buffer pool | <8MB | >32MB | depends on chunk size |

### Build / CI

| Operation | Target | Fail threshold | Source |
|---|---|---|---|
| Clean Gradle build (all modules, Ubuntu CI) | <5 min | >15 min | new |
| Incremental Gradle build (single-file change) | <30s | >2 min | new |
| `./gradlew check` (all tests + lint) | <8 min | >20 min | new |
| Mutation testing (Pitest, Phase 2b libraries) | <30 min per module | >2 hr | mutation testing is inherently slow |
| Roborazzi screenshot verification | <2 min | >10 min | Phase 2a Flight A onward |

### Distribution artifact

| Operation | Target | Fail threshold | Source |
|---|---|---|---|
| Compose-MP Desktop bundle size (Win-x64 MSI) | <120MB | >250MB | bundled JRE + Skia + libFLAC |
| Android APK size (debug) | <50MB | >120MB | bounded by Media3 |
| Android APK size (release, R8 enabled) | <30MB | >80MB | Phase 2a or later |
| `:audio:dsp` published JAR size | <500KB | >2MB | pure Kotlin |
| `:audio:visualizer` published JAR size | <500KB | >2MB | pure Kotlin |

---

## Measurement implementation patterns

### Wall-clock measurement (informal)

```kotlin
val start = System.nanoTime()
operationUnderTest()
val elapsedMs = (System.nanoTime() - start) / 1_000_000
println("op took ${elapsedMs}ms")
```

### Frame-time histogram (LazyColumn spike at MVP Session 1-3)

```kotlin
val frameTimesNs = mutableListOf<Long>()
val choreographer = ... // platform-specific
choreographer.postFrameCallback { now -> 
    frameTimesNs.add(now - lastFrameNs); lastFrameNs = now 
    // ... continue scrolling
}
// Compute percentiles from frameTimesNs after scroll completes
val p95Ms = frameTimesNs.sorted()[(frameTimesNs.size * 0.95).toInt()] / 1_000_000
```

### JMH benchmark (DSP perf smoke, Phase 2a Flight A onward)

See [test cookbook §7](../reference/2026-05-18-test-infrastructure-cookbook.md#7-microbenchmark--jmh--dsp-perf-smoke-test) for full setup. Output:

```
Benchmark                                Mode  Cnt  Score   Error  Units
BiquadChainBenchmark.process_31band_10s  avgt    5  47.2 ± 1.8   ms/op   ← within target 50ms
```

### Memory measurement

```kotlin
Runtime.getRuntime().run {
    val usedBytes = totalMemory() - freeMemory()
    println("Used heap: ${usedBytes / 1024 / 1024}MB")
}
```

Native memory (libFLAC allocations, Skia, etc.) NOT captured this way — use platform memory profilers.

---

## When a budget misses

Decision tree:
1. **Single sample / one-off miss:** ignore; likely noise
2. **Persistent <20% over target:** log in session closeout; continue
3. **Persistent 20-50% over target:** raise as P2 risk; investigate at next opportunity
4. **Persistent >50% over target:** raise as P1; likely effort-overrun trigger per [risk playbook R14](../reference/2026-05-18-risk-playbook.md#r14-effort-overrun-on-a-flight-50-over-estimate)
5. **Fail threshold exceeded:** halt feature work; address the perf issue first

Document misses in:
- Session closeout (`docs/sessions/`)
- Vetting log addendum if the miss reflects a wrong Pre-MVP assumption
- Engram entry under `kiln/perf/<area>` topic key

---

## When to revisit this doc

- New empirical data lands (LazyColumn spike completed; FLAC smoke test ran; first JMH benchmark recorded)
- Hardware reference changes (Clay swaps Pixel or upgrades desktop)
- Phase boundary reached (each phase's prep session refreshes targets)

End of performance budgets.
