# Phase 2b Flight G — Library Extraction Roadmap

**Date:** 2026-05-18 (Pre-positioning for Phase 2b; revisit before Flight G start)
**Author:** Claude Opus 4.7 (1M context) for Clay Haworth
**Type:** Forward-looking roadmap (Phase 2b is ~17-40 months out per plan §13)
**Authoritative sources:**
- Plan §5 Phase 2b Flight G: [`docs/superpowers/plans/2026-05-18-kiln-execution-plan.md`](../superpowers/plans/2026-05-18-kiln-execution-plan.md)
- Spec §2 hard locks (license, project name): [`docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`](../superpowers/specs/2026-05-18-kiln-rebuild-design.md)
- Concentric Modules invariant: [glossary](../reference/2026-05-18-named-patterns-glossary.md#concentric-modules)

This document pre-positions Phase 2b Flight G. The actual work happens 17-40 months from now per plan §13; this is the roadmap so Flight G doesn't start from scratch.

Plan §5 Flight G summary: "Publish `:audio:dsp` and `:audio:visualizer` to JitPack (2 libraries, reduced from 3 after `:data:streaming-tidal` cut on 2026-05-18). Each library gets its own README, CONTRIBUTING, CHANGELOG. Versioning strategy: semver starting from `0.1.0` (pre-1.0 to signal API-stabilizing). Code Connect / Maven coordinates documented. Multi-LLM review checkpoint before each library's first tagged release (Gemini second-opinion on API surface)."

---

## 1. Why extract — the portfolio-and-discipline case

Both reasons stack — extraction earns its keep only when BOTH hold.

**Portfolio reason:** Published Kotlin libraries on JitPack with their own README, CHANGELOG, and CONTRIBUTING are the strongest signal an engineer can show. They demonstrate: API design under public-API-stability discipline, multi-consumer-aware code, documentation as a deliverable, semver competence, license attribution rigor.

**Discipline reason (Bus-Factor-of-One pattern):** the "explain in 200 words" test is forcing. If `:audio:dsp` can't be explained in 200 words for a third-party README, the module is doing too much OR the API isn't clear. Extraction-readiness is a forcing function for module clarity.

**Skip reason:** if either of the above doesn't hold at Flight G start, defer or cancel. Specifically:
- If the modules' shape has accumulated implicit Kiln-specific assumptions (Curator's Trap), extraction will surface them painfully — fix the assumptions FIRST, then extract
- If Clay's interest in portfolio publishing has cooled (genuine reassessment, not avoidance), cut Flight G; the modules still stay in the Kiln monorepo and don't lose their architectural cleanliness

---

## 2. Which modules — and in what order

Per plan §4 Flight E (Phase 2a) and plan §5 Flight G (Phase 2b):

| Module | Proven via | Maturity at Flight G | Extraction priority |
|---|---|---|---|
| `:audio:dsp` | MVP Sessions 16-22 (parametric EQ port + Arrow showcase + Kotest property tests) | High — battle-tested by Clay's daily use through Phase 2a | **Extract first** |
| `:audio:visualizer` | Phase 2a Flight E (pure-Kotlin FFT + Canvas-based visualizer) | Medium-high — proven via Phase 2a daily use | **Extract second** |

Extracting `:audio:dsp` first lets the publishing infrastructure (Gradle conventions, CI release job, JitPack-compatible repo layout) get debugged on the more-mature module. `:audio:visualizer` follows once the first extraction's process is documented.

**Extraction order is sequential** — not parallel. Each extraction is a 30-50 hr flight; combined ~60-100 hrs for both. Plan §5 estimates 30-50 hrs for the whole Flight G but that was before this granularity — revise at Flight G prep time.

---

## 3. Pre-extraction module-prep checklist (per module)

Before either module ships as a published library, walk the following gate:

### 3a. Concentric Modules invariant verified

- ✅ `commonMain` of the target module imports nothing from `androidx.*`
- ✅ `commonMain` imports nothing platform-specific (no `java.*` outside the bare stdlib subset Kotlin/JS+Native can consume; no `android.*`)
- ✅ Any platform-specific code lives in `androidMain` / `jvmMain` adapter modules
- Verify via: `grep -r "androidx" :audio:<mod>/src/commonMain/` returns nothing

### 3b. Public API surface review

- Every `public` declaration in `commonMain` is intentional
- `internal` is preferred for impl details — `internal` does NOT leak across module boundaries even when published
- Data classes are marked `@ConsistentCopyVisibility` (Kotlin 2.x default) if `internal` constructors exist
- Document API surface in `MODULE_API.md` at the module root (curated extract of the dokka output)

### 3c. API stability commitment

- Semver from `0.1.0` per plan §5 — pre-1.0 signals "API may change" but each minor release is functionally stable
- No `1.0.0` until Clay (and possibly Gemini second-opinion per plan §12) feels the API has settled
- Breaking changes between `0.x.0` releases are allowed but each one MUST be documented in CHANGELOG

### 3d. Bus-Factor-of-One readability test

- Open the module's `README.md` (to be written per §7) and the 5 most important `commonMain` source files
- Can future-Clay (or a third-party Kotlin developer) read these and understand what the module does in <10 minutes?
- If no: rework. Either simplify the API, improve doc-comments, or add a `MODULE_OVERVIEW.md`

### 3e. License attribution complete

- Module's `LICENSE` file is Apache 2.0 (spec §2 hard lock)
- Module's `NOTICE` file lists transitive dependencies (Coil, kotlinx-coroutines, etc.) AND any native binaries shipped (libFLAC.dll if `:audio:dsp` ever ships native binaries — currently it doesn't)
- xiph/flac BSD-3 attribution NOT needed in `:audio:dsp`; that's `:audio:playback` only and that module ISN'T being extracted

### 3f. Test coverage at spec §8.2 target

- `:audio:dsp` target: ~90% + property-based
- `:audio:visualizer` target: ~90% + property-based
- Mutation testing target (per [test cookbook §11](../reference/2026-05-18-test-infrastructure-cookbook.md#11-mutation-testing--phase-2b-flight-g-library-quality-gate)): ≥85% mutation score on hot-path classes before first tagged release

---

## 4. Maven coordinates strategy

JitPack publishing model: GitHub repo coordinates map to Maven coordinates via JitPack's URL convention.

**JitPack format:** `com.github.<github-user>:<repo>:<tag>`

For Kiln, that resolves to:
- `com.github.clayboicardi:kiln-audio-dsp:v0.1.0`
- `com.github.clayboicardi:kiln-audio-visualizer:v0.1.0`

**OR** — and worth deciding at Flight G prep time — publish to Maven Central via Sonatype OSSRH using `com.clayworks` groupId. Pros and cons:

| Path | Pros | Cons |
|---|---|---|
| **JitPack** (`com.github.clayboicardi:kiln-audio-dsp`) | Zero-setup: tag a release, JitPack builds + serves it. No Maven Central account needed. Effort: ~2-4 hrs setup | Discoverability lower (people search Maven Central first). Verification at build-time slows first-consume |
| **Maven Central** (`com.clayworks:kiln-audio-dsp`) | Industry-standard discoverability. Faster consumer builds (binary cached on CDN). The Clayworks brand owns its coords | Sonatype OSSRH onboarding (~1-3 weeks lead time including identity verification). Need ownership proof of `clayworks.com` domain (or equivalent). Effort: ~10-20 hrs setup including first-publish |

**Recommendation at Flight G prep time:** JitPack first. Validate the publish/consume cycle is smooth. Upgrade to Maven Central if/when Kiln's libraries earn real third-party usage.

The Maven coordinates DO embed Kiln branding — the artifact ID is `kiln-audio-dsp` not `audio-dsp`. Per Clay's Buyer-Grade Quality Standard, the brand stays attached.

---

## 5. Publishing infrastructure — Vanniktech maven-publish plugin

Slack's `slackhq/circuit` uses `com.vanniktech.maven.publish` (see scaffold prep [§2 libs.versions.toml skeleton](./2026-05-18-mvp-session-1-prep.md#2-gradlelibsversionstoml-skeleton)) — the de facto standard for KMP library publishing in 2026.

**Setup sketch (at Flight G prep time):**

```kotlin
// :audio:dsp/build.gradle.kts
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    coordinates("com.github.clayboicardi", "kiln-audio-dsp", "0.1.0")
    publishToMavenCentral(automaticRelease = false)   // false until cert is set up
    signAllPublications()                              // requires GPG key

    pom {
        name.set("Kiln Audio DSP")
        description.set("Pure-Kotlin parametric EQ + filter math for KMP. Compose Multiplatform compatible.")
        url.set("https://github.com/clayboicardi/kiln")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("clayboicardi")
                name.set("Clay Haworth")
                email.set("clayhaworth1@gmail.com")
            }
        }
        scm {
            url.set("https://github.com/clayboicardi/kiln")
            connection.set("scm:git:git://github.com/clayboicardi/kiln.git")
            developerConnection.set("scm:git:ssh://github.com:clayboicardi/kiln.git")
        }
    }
}
```

The plugin handles the multi-platform-artifact dance — separate JARs for `commonMain`, `jvmMain`, `androidMain`, and a Gradle Module Metadata file that resolves consumers transparently.

---

## 6. Versioning strategy

Per plan §5 Flight G: semver from `0.1.0`, pre-1.0 to signal API-stabilizing.

**Version bump rules** during pre-1.0:
- `0.X.0` — major release; may include breaking API changes
- `0.X.Y` — patch release; bug fixes and non-breaking enhancements only

**1.0.0 graduation criteria** (when to consider stabilizing):
- ≥3 stable `0.X.0` releases without breaking changes (proves API stability empirically)
- Library has at least one external consumer not maintained by Clay (proves third-party utility)
- Mutation score ≥90% on hot-path classes (proves test quality)
- Documentation covers all `public` APIs (proves consumer-experience completeness)

Don't graduate to 1.0 early — pre-1.0 is honest signaling that the API is fluid. 1.0 is a promise of long-term API stability.

**Tag conventions:**
- Repo tag: `v0.1.0-kiln-audio-dsp` (module-prefixed to disambiguate multi-library tags)
- Or: per-library tag stream in a separate orphan branch per module — investigate at Flight G time

---

## 7. README + CHANGELOG + CONTRIBUTING templates

### 7a. README template

Each extracted module ships a `README.md` at the module root. Template structure (sketch — refine at Flight G):

```markdown
# Kiln Audio DSP

**Pure-Kotlin parametric EQ + filter math for Kotlin Multiplatform.** Compose
Multiplatform compatible; zero `androidx.*` dependencies in commonMain.

[![Maven Central](badge)](link)
[![License](badge)](link)
[![Kotlin](badge)](link)

## What this is

Kiln Audio DSP is the DSP core extracted from the [Kiln music player](https://github.com/clayboicardi/kiln). 
It provides a pure-Kotlin implementation of biquad filters, parametric EQ, and 
related audio signal-processing primitives suitable for embedding in any 
Kotlin/JVM, Kotlin/Native, or Compose Multiplatform application.

## Quickstart

\`\`\`kotlin
dependencies {
    implementation("com.github.clayboicardi:kiln-audio-dsp:0.1.0")
}
\`\`\`

\`\`\`kotlin
import com.clayworks.kiln.audio.dsp.BiquadFilter
import com.clayworks.kiln.audio.dsp.ParametricEqChain

val filter = BiquadFilter.lowpass(sampleRateHz = 44100, cutoffHz = 1000.0, q = 0.707)
val output = FloatArray(input.size)
filter.process(input, output, input.size)
\`\`\`

## API surface

[See MODULE_API.md](MODULE_API.md) for the curated public API reference.

## Why pure Kotlin?

(The Concentric Modules story — 200 words)

## Performance

JMH benchmarks demonstrate <X ms processing of 10-second 44.1kHz stereo buffer
through a 31-band parametric EQ on Y hardware. Pre-allocated primitive arrays
in the hot loop; zero GC pauses verified.

## License

Apache 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Acknowledgments

DSP algorithms re-derived from standard references; no GPL or LGPL code
incorporated. Extracted from the Kiln music player project.
```

### 7b. CHANGELOG.md template

Use Keep-a-Changelog format. Each entry per `0.X.0` (or `0.X.Y`) release:

```markdown
# Changelog

All notable changes to Kiln Audio DSP are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- (description of additions in trunk)

## [0.1.0] - 2027-XX-XX

### Added
- Initial public release.
- `BiquadFilter` with lowpass/highpass/bandpass/notch/peak/shelf modes
- `ParametricEqChain` with 31-band default configuration
- `EnergyPreservingCrossfade` for smooth preset transitions
- Property-based tests via Kotest for all filter math
- JMH benchmarks for parametric EQ chain processing

### Known limitations
- Pre-1.0: API may change in `0.X.0` releases
- Float-precision only; double-precision in roadmap
```

### 7c. CONTRIBUTING.md template

Short, opinionated. Sample:

```markdown
# Contributing to Kiln Audio DSP

This library is maintained by Clay Haworth as part of the Kiln music player
project. Contributions are welcome but the bar is high — the library carries
audiophile-credibility commitments.

## Before opening a PR

- ☐ Open an issue first to discuss the change (especially for new public APIs)
- ☐ All commonMain code stays platform-free (no androidx.*, no java.* outside
  the basic stdlib subset)
- ☐ New filter math has Kotest property-based tests
- ☐ New hot-path code has JMH benchmarks
- ☐ Public API changes include CHANGELOG entries

## Code style

- ktlint format (run `./gradlew ktlintFormat` before pushing)
- All warnings as errors (`allWarningsAsErrors = true`)

## License

By contributing, you agree your contributions are licensed under Apache 2.0.
```

---

## 8. API surface review process

Per plan §8 + §12: "Multi-LLM second opinion warranted on: Public library APIs before stable tags (Phase 2b Flight H)." Translation: before tagging `0.1.0` of a library, run a multi-LLM API review.

**Process at Flight G prep time:**

1. Generate API documentation: `./gradlew :audio:dsp:dokkaHtml` produces a browsable API HTML site
2. Curate the surface: hand-write a `MODULE_API.md` listing every `public` declaration with a one-sentence description. Anything that doesn't earn its sentence is a candidate for `internal`
3. Multi-LLM review (per plan §8): submit the `MODULE_API.md` + `README.md` to Gemini (and Codex if installed by then) for second-opinion review. Look for:
   - Naming consistency issues
   - Missing edge-case documentation
   - API ergonomics concerns
   - Hidden coupling to Kiln-specific concepts (Curator's Trap)
4. Synthesize feedback; revise API
5. Repeat once if material changes

The multi-LLM gate is plan-mandated. Don't skip it.

---

## 9. Compatibility matrix

Each release's README pins compatible versions:

```markdown
| Kiln Audio DSP | Kotlin       | Compose-MP    | kotlinx-coroutines |
|----------------|--------------|---------------|--------------------|
| 0.1.0          | 2.3.x        | 1.10.x        | 1.11.x             |
| 0.2.0          | 2.4.x        | 1.11.x        | 1.12.x             |
```

The compatibility matrix is binding — if a consumer pulls in incompatible versions, the build fails or runtime breaks. Gradle Module Metadata's version constraints help here.

---

## 10. Release process — CI integration

GitHub Actions release workflow sketch (at Flight G prep time):

```yaml
# .github/workflows/release-library.yml
name: release-library

on:
  push:
    tags: ['v*-kiln-audio-dsp', 'v*-kiln-audio-visualizer']

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v5
      - name: Build + verify mutation score
        run: |
          MODULE=$(echo "${{ github.ref_name }}" | sed 's/v[0-9.]*-//')
          ./gradlew ":audio:${MODULE#kiln-audio-}:check" ":audio:${MODULE#kiln-audio-}:pitest"
      - name: Publish to Maven Central (or JitPack)
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.SONATYPE_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.SONATYPE_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.GPG_PASSWORD }}
        run: |
          MODULE=$(echo "${{ github.ref_name }}" | sed 's/v[0-9.]*-//')
          ./gradlew ":audio:${MODULE#kiln-audio-}:publishAllPublicationsToMavenCentralRepository"
```

Tag-driven publishing means a `git tag v0.1.0-kiln-audio-dsp && git push --tags` triggers the release. No manual artifact upload.

---

## 11. Post-extraction maintenance model

Once extracted, the libraries are NOT separate repos. They live in the Kiln monorepo at `audio/dsp/` and `audio/visualizer/`. Benefits:
- Single source of truth — Kiln itself is always the canonical consumer
- Single CI pipeline
- Single CHANGELOG flow per library
- No "library is out of sync with the app using it" problem

Downside:
- Releases are coupled — fixing a bug in `:audio:dsp` means publishing a new version of the library AND updating Kiln to use it
- Tag conventions get busy: `v1.0.0-mvp`, `v1.0.1-dynamic-theming`, `v0.1.0-kiln-audio-dsp`, ... — disambiguate carefully

**Maintenance commitments** for each extracted library:
- Respond to GitHub issues opened against the modules within 1-2 weeks
- Tag a patch release within 4 weeks if a critical bug surfaces
- Pre-announce any planned breaking changes 1 minor release in advance (`0.X.0` includes deprecation; `0.(X+1).0` removes)

If/when maintenance burden exceeds Clay's appetite: archive the libraries with a clear "no longer maintained" notice. Don't quietly drop.

---

## 12. Open questions deferred to Flight G start

| # | Question | When to decide |
|---|---|---|
| 1 | JitPack vs Maven Central | At Flight G prep, after weighing 2-4 hr vs 10-20 hr setup |
| 2 | Are mutation-testing tools still Pitest-based, or has the Kotlin-specific landscape matured? | At Flight G prep, check current state |
| 3 | Is Gemini still the right multi-LLM reviewer, or is Codex/another installed? | At Flight G prep, per plan §12 provider involvement |
| 4 | Does Clay have a `clayworks.com` domain registered for Maven Central groupId? | At Flight G prep if pursuing Maven Central |
| 5 | Does Vanniktech maven-publish still exist + maintained? | At Flight G prep, verify |
| 6 | What's the third-party adoption signal at Flight G time — anyone using these modules outside Kiln? | At Flight G prep — informs whether extraction is worth the effort, NOT whether to skip |

---

## 13. Effort projection

Per plan §5 Flight G estimate: ~30-50 hrs total.

**Revised estimate** with this roadmap's granularity:

| Sub-flight | Effort | Scope |
|---|---|---|
| 13a. `:audio:dsp` extraction prep | ~8-12 hrs | API review + MODULE_API.md + README + CHANGELOG + multi-LLM gate |
| 13b. `:audio:dsp` publishing infra setup | ~6-10 hrs | Vanniktech plugin + JitPack OR Maven Central onboarding (Maven Central adds ~4-8 hrs) |
| 13c. `:audio:dsp` first tagged release | ~4-8 hrs | Final smoke, tag, monitor first build at JitPack/Sonatype, document any gotchas |
| 13d. `:audio:visualizer` extraction prep | ~6-10 hrs | Same shape, less work (process is debugged) |
| 13e. `:audio:visualizer` first tagged release | ~3-6 hrs | Re-use infrastructure from 13b |
| **Total Flight G** | **~27-46 hrs** | matches plan's 30-50 hr range |

If Maven Central onboarding is chosen: add ~5-10 hrs for initial Sonatype account verification + domain proof.

---

## 14. What this roadmap does NOT do

- Does NOT prescribe Maven coordinates definitively — that's a Flight G decision based on conditions then
- Does NOT lock the publishing tool (Vanniktech today; may shift by Phase 2b)
- Does NOT enumerate every public API of each module — that comes from `MODULE_API.md` written at Flight G
- Does NOT include the actual readme/changelog/contributing content — templates only; bodies written at Flight G when the modules have full feature sets
- Does NOT cover post-1.0 stability commitments — those come when 1.0 is in sight

---

End of roadmap.
