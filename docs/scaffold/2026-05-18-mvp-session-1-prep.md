# MVP Session 1-3 Scaffold Prep — Actionable Consolidation

**Date:** 2026-05-18 (Post-Pre-MVP-Research synthesis)
**Author:** Claude Opus 4.7 (1M context) for Clay Haworth
**Status:** Ready for MVP Session 1 once Clay reviews + acknowledges Pre-MVP Research decisions
**Authoritative sources:**
- Locked spec: [`docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`](../superpowers/specs/2026-05-18-kiln-rebuild-design.md)
- Locked plan: [`docs/superpowers/plans/2026-05-18-kiln-execution-plan.md`](../superpowers/plans/2026-05-18-kiln-execution-plan.md)
- Vetting log (12 items): [`docs/decisions/2026-05-18-library-vetting.md`](../decisions/2026-05-18-library-vetting.md)
- Schema sketch: [`docs/decisions/2026-05-18-sqldelight-schema-sketch.md`](../decisions/2026-05-18-sqldelight-schema-sketch.md)

This document consolidates every Pre-MVP Research decision into an actionable artifact for MVP Sessions 1-3 (the Gradle KMP scaffold + "Hello Kiln" first build). A future Claude or Clay opens this doc and can execute Sessions 1-3 without rereading the 1500-line vetting log.

---

## 1. One-page decision summary

| # | Item | Decision | Vetting log § |
|---|---|---|---|
| 1 | Compose Multiplatform desktop | Compose MP stable (verify version at scaffold time; expect JB Compose 1.10.x) — first-class Android+JVM Desktop+iOS+JS support; MenuBar, Tray, AsyncImage via Coil; LazyColumn 40k spike pending | Item 1 |
| 2 | Image loader | **Coil 3.4.0** — `io.coil-kt.coil3:coil-compose` + `coil` core. Local-files-only at MVP; no network engine. Coil owns disk cache — do NOT pair with OkHttp Cache | Item 2 |
| 3 | Palette extractor | **kmpalette 4.0.0-beta02** (jordond) — Compose-MP port of AndroidX Palette; WCAG contrast post-processing in-house | Item 3 |
| 4 | Navigation | **Voyager** confirmed; specific version pinned at scaffold time | Item 4 |
| 5 | Presenter / state | **Circuit 0.33.1** (Slack) for Now Playing showcase + **Molecule 2.2.0** (Cash App) opt-in. Both Apache 2.0. Pin finalized at MVP Session 12-15 | Item 5 |
| 6 | SQLDelight | **SQLDelight 2.3.2** — JDBC driver (desktop) + android-driver. FTS5 contentless table with `unicode61 remove_diacritics 2` tokenizer | Item 6 + schema sketch |
| 7 | Screenshot testing | **Roborazzi 1.61.0** — Compose-MP Desktop via `runDesktopComposeUiTest`. Adopted at Phase 2a Flight A; pinned in libs now | Item 7 |
| 9 | Java Sound + FLAC | Java Sound output (30-100 ms latency, acceptable for MVP). **FLAC decoder = JNA 5.14.0 + vendored Xiph libFLAC 1.5.0 BSD-3 Win-x64 DLL** | Item 9 + addendum |
| 10 | Windows distribution | Compose-MP `nativeDistributions { targetFormats(AppImage, Msi) }`. WiX Toolset required for MSI. No code signing at MVP | Item 10 |
| 11 | System integration | Android: Media3 `MediaSessionService` + Audio Focus + BLE-disconnect via `setHandleAudioBecomingNoisy`. Windows SMTC: JIT decision before MVP Session 23 | Item 11 |
| 12 | LazyColumn 40k | **Paged loading via SQLDelight LIMIT/OFFSET is the architectural default**. Spike at MVP Session 1-3 confirms whether un-paged "view all" is also viable | Item 12 |
| 13 | Audio backend | `PlatformPlayer` interface with engine-swap-shaped boundary. MVP: Media3 ExoPlayer (Android) + Java Sound (Desktop). Phase 2b H+I (AAudio/WASAPI) soft-lock revisit at end of Phase 2a | Item 13 |

**Hard locks from spec §2 (do not change):** Project name, platform targets (Android + Windows Desktop via KMP), design system (Kiln Dynamic), MVP shape (minimal-done-right), license (Apache 2.0), why-now anchor.

**Soft locks (revisitable with explicit conversation):** Differentiator (room correction), tech stack pieces, test strategy depth.

---

## 2. `gradle/libs.versions.toml` skeleton

Drop the following into `gradle/libs.versions.toml` at scaffold time. Verify each `*-VERIFY` version against Maven Central / GitHub Releases on the day of scaffold; the Kotlin/Compose/AGP ecosystem moves weekly. Pre-MVP-locked pins are explicit.

```toml
[versions]
# === Language / build core (verify at scaffold time — these move weekly) ===
kotlin = "2.3.21"                       # VERIFY — match what JB Compose plugin requires
kotlinx-coroutines = "1.11.0"           # VERIFY
kotlinx-serialization = "1.9.0"         # VERIFY
agp = "9.2.1"                           # VERIFY — Android Gradle Plugin
ksp = "2.3.8"                           # VERIFY — must align with kotlin
android-compileSdk = "36"               # spec §2 hard lock
android-minSdk = "21"                   # spec §2 hard lock; verify Coil 3.4.0 still supports
android-targetSdk = "36"
jvmTarget = "21"                        # CLAUDE.md JDK 21 (NOT JBR — TLS/SSL issues)

# === Compose Multiplatform (Item 1 — verify exact at scaffold) ===
jb-compose = "1.10.3"                   # VERIFY — JetBrains Compose Multiplatform plugin
jb-compose-material3 = "1.9.0"          # VERIFY
compose-runtime = "1.11.1"              # VERIFY — keep in sync with jb-compose's androidx runtime
jb-lifecycle = "2.10.0"                 # VERIFY

# === Image loading (Item 2 — Pre-MVP locked) ===
coil = "3.4.0"

# === Palette / Dynamic theming (Item 3 — Pre-MVP locked; revisit at Phase 2a Flight A) ===
kmpalette = "4.0.0-beta02"              # PIN: verify 4.0.0 stable before Phase 2a Flight A

# === Navigation (Item 4 — Pre-MVP confirmed; pin at scaffold) ===
voyager = "1.1.0-beta02"                # VERIFY — common-stable line; check for newer betas

# === Presenter / state (Item 5 — Pre-MVP locked; finalize at MVP Session 12-15) ===
circuit = "0.33.1"                      # PIN — revisit at MVP Session 12 adoption
molecule = "2.2.0"                      # PIN — opt-in companion

# === Data layer (Item 6 — Pre-MVP locked) ===
sqldelight = "2.3.2"

# === DI ===
kotlinInject = "0.9.0"                  # spec §4 — same as Slack's stack

# === Functional / showcase ===
arrow = "2.0.1"                         # VERIFY — :audio:dsp Arrow showcase per spec

# === Testing ===
kotest = "5.9.1"                        # VERIFY — property-based tests for DSP math
turbine = "1.2.1"                       # Flow testing; matches Slack's stack
mokkery = "2.3.0"                       # VERIFY — KMP-friendly mocking
roborazzi = "1.61.0"                    # PIN — Phase 2a Flight A adoption

# === Audio (Item 9 — Pre-MVP locked) ===
jna = "5.14.0"                          # JVM ↔ libFLAC bridge
# Native libFLAC 1.5.0 vendored under :audio:playback/src/jvmMain/resources/native/win-x64/

# === Android (MediaSession + Audio Focus) ===
androidx-media3 = "1.10.6"              # VERIFY — Media3 for ExoPlayer + MediaSessionService

# === Tag reader (desktop FLAC metadata) ===
jaudiotagger = "3.0.1"                  # VERIFY — Maven Central artifact path

# === Logging ===
kermit = "2.0.4"                        # VERIFY — KMP-friendly logger from Touchlab

# === Utility ===
appdirs = "1.5.0"                       # Slack uses this — Desktop user-data-dir resolution

[libraries]
# === Kotlin core ===
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-swing = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }

# === Compose Multiplatform ===
compose-runtime = { module = "androidx.compose.runtime:runtime", version.ref = "compose-runtime" }
compose-runtime-saveable = { module = "org.jetbrains.compose.runtime:runtime-saveable", version.ref = "jb-compose" }
compose-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "jb-compose" }
compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "jb-compose-material3" }
compose-material-icons = { module = "org.jetbrains.compose.material:material-icons-core", version = "1.7.3" }
compose-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "jb-compose" }
compose-ui-tooling = { module = "org.jetbrains.compose.ui:ui-tooling", version.ref = "jb-compose" }
compose-ui-tooling-preview = { module = "org.jetbrains.compose.ui:ui-tooling-preview", version.ref = "jb-compose" }
compose-ui-test-junit4 = { module = "org.jetbrains.compose.ui:ui-test-junit4", version.ref = "jb-compose" }
compose-resources = { module = "org.jetbrains.compose.components:components-resources", version.ref = "jb-compose" }
compose-lifecycle-runtime = { module = "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose", version.ref = "jb-lifecycle" }
compose-lifecycle-viewmodel = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "jb-lifecycle" }

# === Coil (Item 2) ===
coil = { module = "io.coil-kt.coil3:coil", version.ref = "coil" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
# coil-network-ktor3 — add ONLY if a remote source ever lands; not at MVP

# === kmpalette (Item 3) ===
kmpalette-core = { module = "dev.jordond.kmpalette:kmpalette-core", version.ref = "kmpalette" }

# === Voyager (Item 4) ===
voyager-navigator = { module = "cafe.adriel.voyager:voyager-navigator", version.ref = "voyager" }
voyager-tab-navigator = { module = "cafe.adriel.voyager:voyager-tab-navigator", version.ref = "voyager" }
voyager-transitions = { module = "cafe.adriel.voyager:voyager-transitions", version.ref = "voyager" }
voyager-screenmodel = { module = "cafe.adriel.voyager:voyager-screenmodel", version.ref = "voyager" }

# === Circuit + Molecule (Item 5) ===
circuit-foundation = { module = "com.slack.circuit:circuit-foundation", version.ref = "circuit" }
circuit-runtime = { module = "com.slack.circuit:circuit-runtime", version.ref = "circuit" }
circuit-codegen-annotations = { module = "com.slack.circuit:circuit-codegen-annotations", version.ref = "circuit" }
# circuit-codegen — KSP processor; add ONLY when codegen earns its keep
molecule-runtime = { module = "app.cash.molecule:molecule-runtime", version.ref = "molecule" }

# === SQLDelight (Item 6) ===
sqldelight-coroutines-extensions = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
sqldelight-primitive-adapters = { module = "app.cash.sqldelight:primitive-adapters", version.ref = "sqldelight" }
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-sqlite-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }   # JVM Desktop

# === DI ===
kotlinInject-compiler = { module = "me.tatarka.inject:kotlin-inject-compiler-ksp", version.ref = "kotlinInject" }
kotlinInject-runtime = { module = "me.tatarka.inject:kotlin-inject-runtime", version.ref = "kotlinInject" }

# === Arrow showcase (audio:dsp) ===
arrow-core = { module = "io.arrow-kt:arrow-core", version.ref = "arrow" }

# === Testing ===
kotest-runner-junit5 = { module = "io.kotest:kotest-runner-junit5", version.ref = "kotest" }
kotest-assertions-core = { module = "io.kotest:kotest-assertions-core", version.ref = "kotest" }
kotest-property = { module = "io.kotest:kotest-property", version.ref = "kotest" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
mokkery-runtime = { module = "dev.mokkery:mokkery-runtime", version.ref = "mokkery" }

# === Roborazzi (Item 7) ===
roborazzi = { module = "io.github.takahirom.roborazzi:roborazzi", version.ref = "roborazzi" }
roborazzi-compose = { module = "io.github.takahirom.roborazzi:roborazzi-compose", version.ref = "roborazzi" }
roborazzi-junit-rule = { module = "io.github.takahirom.roborazzi:roborazzi-junit-rule", version.ref = "roborazzi" }

# === Native FLAC bridge (Item 9 addendum) ===
jna = { module = "net.java.dev.jna:jna", version.ref = "jna" }
jna-platform = { module = "net.java.dev.jna:jna-platform", version.ref = "jna" }

# === Android Media3 (Item 11 + Item 13 PlatformPlayer Android adapter) ===
androidx-media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "androidx-media3" }
androidx-media3-session = { module = "androidx.media3:media3-session", version.ref = "androidx-media3" }
androidx-media3-common = { module = "androidx.media3:media3-common", version.ref = "androidx-media3" }
androidx-media3-decoder = { module = "androidx.media3:media3-decoder", version.ref = "androidx-media3" }
androidx-media3-datasource = { module = "androidx.media3:media3-datasource", version.ref = "androidx-media3" }

# === Tag reader (desktop FLAC metadata) ===
jaudiotagger = { module = "net.jthink:jaudiotagger", version.ref = "jaudiotagger" }

# === Logging ===
kermit = { module = "co.touchlab:kermit", version.ref = "kermit" }

# === Desktop utility ===
appdirs = { module = "net.harawata:appdirs", version.ref = "appdirs" }

[bundles]
compose-mp-common = [
    "compose-foundation",
    "compose-material3",
    "compose-material-icons",
    "compose-ui",
    "compose-resources",
    "compose-lifecycle-runtime",
    "compose-lifecycle-viewmodel",
    "compose-runtime-saveable",
]
voyager = [
    "voyager-navigator",
    "voyager-tab-navigator",
    "voyager-transitions",
    "voyager-screenmodel",
]
circuit = [
    "circuit-foundation",
    "circuit-runtime",
    "circuit-codegen-annotations",
]
sqldelight-common = [
    "sqldelight-coroutines-extensions",
    "sqldelight-primitive-adapters",
]
kotest = [
    "kotest-runner-junit5",
    "kotest-assertions-core",
    "kotest-property",
]
roborazzi = [
    "roborazzi",
    "roborazzi-compose",
    "roborazzi-junit-rule",
]
android-media3 = [
    "androidx-media3-exoplayer",
    "androidx-media3-session",
    "androidx-media3-common",
    "androidx-media3-decoder",
    "androidx-media3-datasource",
]
jna = [
    "jna",
    "jna-platform",
]

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
android-kmp = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }   # VERIFY presence
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-plugin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-plugin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-plugin-parcelize = { id = "org.jetbrains.kotlin.plugin.parcelize", version.ref = "kotlin" }
jb-compose = { id = "org.jetbrains.compose", version.ref = "jb-compose" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
mokkery = { id = "dev.mokkery", version.ref = "mokkery" }
```

**JIT verifications at scaffold time** (search-and-update before committing the toml):
- Kotlin / kotlinx-coroutines / kotlinx-serialization (move weekly)
- AGP + KSP (must align with Kotlin)
- jb-compose / compose-runtime (Compose MP version)
- jb-compose-material3 (Material3 multiplatform — separate version from jb-compose since 2025)
- jb-lifecycle (Compose MP lifecycle artifacts; "androidx" packaged differently for KMP)
- Voyager (check for stable; currently 1.1.0-beta02 line)
- Media3 (Android side — verify latest stable)
- jaudiotagger, kermit, appdirs (less-frequently-updated; sanity check)

---

## 3. `settings.gradle.kts` sketch

```kotlin
@file:Suppress("UnstableApiUsage")

rootProject.name = "kiln"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jitpack only if we ever consume a JitPack-only artifact; not at MVP
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

include(
    ":app-android",
    ":app-desktop",
    ":audio:dsp",
    ":audio:visualizer",
    ":audio:playback",
    ":data:library",
    ":ui:theme",
    ":ui:components",
)

// Future modules (post-Phase 2a Flight G library extraction): adjust includes here.
```

---

## 4. Root `build.gradle.kts` sketch

```kotlin
plugins {
    // Apply false at root so subprojects can configure
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kotlin.plugin.parcelize) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jb.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.mokkery) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
```

---

## 5. `build-logic/` convention plugins

`build-logic/` is an included build (per `settings.gradle.kts`) that defines reusable plugins for module shape. Five planned conventions:

### 5.1 `kiln.kmp.library` — base KMP library

Applied to every `:audio:*`, `:data:*`, `:ui:*` module. Establishes:
- Kotlin Multiplatform plugin
- Android library target with `compileSdk = 36`, `minSdk = 21`, `targetSdk = 36`
- JVM Desktop target with `jvmTarget = 21`
- Common test source sets wired up with kotlin-test
- `allWarningsAsErrors = true` for `:ui:components` (Circuit `@ComposableTarget` enforcement) — apply via override per spec §4

```kotlin
// build-logic/src/main/kotlin/kiln.kmp.library.gradle.kts
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "21"
            }
        }
    }
    jvm("desktop") {
        compilations.all {
            kotlinOptions {
                jvmTarget = "21"
            }
        }
    }

    sourceSets {
        commonMain.dependencies { /* per-module */ }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 21
    }
    namespace = "com.clayworks.kiln.${project.name.replace(":", ".")}"
}
```

### 5.2 `kiln.kmp.compose` — KMP with Compose

Inherits `kiln.kmp.library` and adds:
- `org.jetbrains.compose` plugin
- `kotlin-plugin-compose` plugin
- Compose-MP common dependencies via `bundles.compose-mp-common`

Used by `:ui:theme`, `:ui:components`. NOT used by `:audio:*` or `:data:library` (which must remain Compose-free per Concentric Modules invariant).

### 5.3 `kiln.android.app` — Android application

Used by `:app-android`. Configures:
- `com.android.application` + `org.jetbrains.kotlin.android`
- Compose plugin
- `applicationId = "com.clayworks.kiln"`
- `versionCode` + `versionName`
- Signing config placeholder (no signing at MVP)
- Media3 dependencies via `bundles.android-media3`

### 5.4 `kiln.desktop.app` — Compose-MP desktop application

Used by `:app-desktop`. Configures:
- `org.jetbrains.kotlin.jvm` + `org.jetbrains.compose`
- `compose.desktop.application` block per Item 10 decision
- jpackage `nativeDistributions { targetFormats(AppImage, Msi) }`
- Native libFLAC.dll vendored as `resources/native/win-x64/libFLAC.dll`

```kotlin
// build-logic/src/main/kotlin/kiln.desktop.app.gradle.kts
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

compose.desktop {
    application {
        mainClass = "com.clayworks.kiln.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
            )
            packageName = "Kiln"
            packageVersion = project.version.toString()
            vendor = "Clay Haworth / Clayworks"
            copyright = "Copyright (c) 2026 Clay Haworth. Licensed Apache 2.0."
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.sql", "java.naming")  // expand as deps require
            windows {
                console = false
                perUserInstall = true
                shortcut = true
                menu = true
                upgradeUuid = "REPLACE_WITH_STABLE_UUIDv5"   // generate once, never change
                iconFile.set(rootProject.file("docs/assets/kiln.ico"))   // create at MVP Session 26-28
            }
        }
    }
}
```

### 5.5 `kiln.tests.screenshot` — Roborazzi screenshot tests (sketched; lands Phase 2a Flight A)

Used by `:ui:theme`, `:ui:components` jvmTest source sets when screenshot tests start landing. Configures:
- `io.github.takahirom.roborazzi` plugin
- `RoborazziOptions(resizeScale = 0.5, changeThreshold = 0F)` default
- Snapshot directory under `src/jvmTest/snapshots/`

---

## 6. Per-module dependency assignments

The Concentric Modules invariant (spec §3.4) drives placement: inner modules platform-free, outer modules add platform deps. Adapters in `androidMain` / `jvmMain` per source set.

| Module | Convention | commonMain deps | androidMain deps | jvmMain deps |
|---|---|---|---|---|
| `:audio:dsp` | `kiln.kmp.library` | `arrow-core` (showcase), Kotlin stdlib only | (nothing yet; Media3 BaseAudioProcessor adapter at MVP Session 16-22) | (nothing yet; Java Sound processor adapter at Session 16-22) |
| `:audio:visualizer` | `kiln.kmp.library` | Kotlin stdlib (pure-Kotlin FFT) | (Media3 adapter Phase 2a Flight E) | (Java Sound adapter Phase 2a Flight E) |
| `:audio:playback` | `kiln.kmp.library` | `kotlinx-coroutines-core`, `kermit` | `bundles.android-media3` | `bundles.jna`, `jaudiotagger` (for FLAC metadata), `kotlinx-coroutines-swing` (Java Sound thread interop); **vendored `libFLAC.dll` under `resources/native/win-x64/`** |
| `:data:library` | `kiln.kmp.library` | `bundles.sqldelight-common`, `kotlinx-coroutines-core`, `kermit` | `sqldelight-android-driver`, Android MediaStore APIs (no extra dep) | `sqldelight-sqlite-driver`, `jaudiotagger`, `appdirs` |
| `:ui:theme` | `kiln.kmp.compose` | `bundles.compose-mp-common`, `kmpalette-core`, `coil-compose` (for ImageBitmap pipeline) | (nothing extra) | (nothing extra) |
| `:ui:components` | `kiln.kmp.compose` | `bundles.compose-mp-common`, `bundles.voyager`, `bundles.circuit`, `molecule-runtime`, `kermit`, `coil-compose` | (nothing extra) | (nothing extra) |
| `:app-android` | `kiln.android.app` | implementation projects: `:ui:components`, `:ui:theme`, `:data:library`, `:audio:playback`, `:audio:dsp` (visualizer Phase 2a) | `bundles.android-media3`, `kotlin-inject` runtime | n/a |
| `:app-desktop` | `kiln.desktop.app` | implementation projects: same as `:app-android` | n/a | `kotlin-inject` runtime, `bundles.jna` |

**KSP-applied modules** (kotlin-inject + SQLDelight + Circuit codegen):
- `:data:library` — SQLDelight plugin + KSP for query-class generation
- `:ui:components` — KSP for kotlin-inject (Circuit codegen NOT applied at MVP; defer to Session 12-15 if multiple presenters)
- `:app-android` + `:app-desktop` — kotlin-inject KSP for graph generation

**Modules with `allWarningsAsErrors = true`:**
- `:ui:components` — Circuit `@ComposableTarget("presenter")` enforcement per Item 5

---

## 7. Native binary vendoring — libFLAC.dll

Per Item 9 addendum, `:audio:playback/src/jvmMain/` carries `libFLAC.dll` for Win-x64. Steps at MVP Session 4-7:

1. Download `libFLAC.dll` from xiph/flac 1.5.0 release archive (Win-x64 build):
   - URL: `https://github.com/xiph/flac/releases/tag/1.5.0` → `flac-1.5.0-win.zip`
   - Extract `Win64\libFLAC.dll`
2. Place at `audio/playback/src/jvmMain/resources/native/win-x64/libFLAC.dll`
3. Add `audio/playback/src/jvmMain/resources/native/win-x64/LICENSE-libflac.txt` with the BSD-3-Clause text
4. Write `NativeLibraryLoader.kt` in `:audio:playback/src/jvmMain/kotlin/com/clayworks/kiln/audio/playback/native/`:
   ```kotlin
   internal object NativeLibraryLoader {
       fun loadLibFlac() {
           val arch = detectArch()  // "win-x64", later "linux-x64", "macos-arm64"
           val tempDir = File(System.getProperty("java.io.tmpdir"), "kiln/native").apply { mkdirs() }
           val target = File(tempDir, "libFLAC-1.5.0.dll")
           if (!target.exists()) {
               javaClass.classLoader
                   .getResourceAsStream("native/$arch/libFLAC.dll")
                   ?.use { it.copyTo(target.outputStream()) }
                   ?: error("libFLAC.dll not bundled for arch=$arch")
           }
           System.load(target.absolutePath)
       }
   }
   ```
5. Wire `Native.load("FLAC", LibFlac::class.java)` via JNA after the system load (`LibFlac` is the Kotlin interface declaring libFLAC's public C API)
6. jpackage `nativeDistributions` automatically picks up `resources/` content; native binary ships inside the bundle
7. `THIRD_PARTY_LICENSES.md` at the repo root lists xiph/flac BSD-3 + Kotlin/Compose/Coil/etc. attributions (generate at Phase 2a Flight A or once a license-scanner gradle plugin is wired)

**Future architectures** (deferred per Item 9 addendum):
- `linux-x64/libFLAC.so` — when desktop Linux target is added
- `macos-arm64/libFLAC.dylib` — when macOS target is added
- `win-x86/libFLAC.dll` — only if 32-bit Windows users emerge

---

## 8. CI workflow sketch (`.github/workflows/build.yml`)

```yaml
name: build

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  build:
    strategy:
      fail-fast: false
      matrix:
        os: [ubuntu-latest, windows-latest]
        jdk: [21]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: ${{ matrix.jdk }}
      - uses: gradle/actions/setup-gradle@v5
      - name: Android build (Ubuntu only)
        if: matrix.os == 'ubuntu-latest'
        run: ./gradlew :app-android:assembleDebug --no-daemon
      - name: Desktop build (Windows only — for MSI/jpackage)
        if: matrix.os == 'windows-latest'
        run: ./gradlew :app-desktop:packageDistributionForCurrentOS --no-daemon
      - name: Tests
        run: ./gradlew check --no-daemon

  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v5
      - name: Lint
        run: ./gradlew lintDebug --no-daemon
```

**CI matrix expands at:**
- MVP Session 4-6 — add Android API 28 / 33 / 36 test variants per spec §8.3
- Phase 2a Flight A — Roborazzi screenshot verification job
- Phase 2b Flight G — Maven publication dry-run on library modules

---

## 9. JIT-check matrix (Pre-MVP follow-ups carried into MVP)

| When | What | If it fails |
|---|---|---|
| **MVP Session 1-3 scaffold** (~before committing `libs.versions.toml`) | Verify Kotlin / AGP / Compose-MP / KSP / Media3 / Voyager / Arrow / Kotest / kermit / jaudiotagger versions on Maven Central; pick latest stables | Bump versions in skeleton; rerun |
| **MVP Session 1-3** | Generate stable `upgradeUuid` UUIDv5 from `kiln-windows-upgrade` namespace; commit as constant in `:app-desktop` | Use `uuidgen -n "kiln-windows-upgrade" -N kiln-msi` style approach |
| **MVP Session 1-3** | Install WiX Toolset 3.x on Clay's i5-13400F (`choco install wixtoolset` or wixtoolset.org); verify `WIX_HOME` env var; run `:app-desktop:packageMsi` smoke test | If MSI build fails, debug WiX path or jpackage args |
| **MVP Session 1-3** | LazyColumn 40k spike (Item 12): synthetic 40k-track `LazyColumn` with `items(list, key = { it.id })`; measure 95p/99p frame time during hot scroll | If 95p > 33ms: adopt Mitigation A (paged loading) or B (sectioned grouping) from Day One of Session 8 |
| **MVP Session 4** | Confirm Coil 3.4.0 still supports `minSdk = 21` (check Coil release notes for 3.4.x minSdk bumps) | If 23+, raise with Clay before adopting; may force spec §2 platform-target soft-lock revisit |
| **MVP Session 4** | Verify Pixel 10 Pro XL bundled SQLite has FTS5: `adb shell sqlite3 :memory: 'CREATE VIRTUAL TABLE t USING fts5(x);'` from a test app | Extremely unlikely on API 21+. If absent, fall back to LIKE-based search at MVP; FTS5 in Phase 2a |
| **MVP Session 4** | Probe Clay's Windows desktop for Java Sound supported formats: `AudioSystem.getMixerInfo()` enumeration code, log 16/24-bit + 44.1/48/96/192 kHz availability | Document the actual ceiling; fall back to universally-safe 24/96 stereo signed PCM |
| **MVP Session 4-7** | Empirical FLAC decode test: ≥10 of Clay's tracks across 16/44, 24/96, 24/192 rates through `:audio:playback/src/jvmMain/JvmFlacDecoderImpl` (JNA + libFLAC); compare PCM output bytes vs `ffmpeg -i file.flac -f f32le -` reference | If any track fails to decode: file issue against xiph/flac (extremely unlikely); meanwhile inspect tracker bug |
| **MVP Session 23** | Decide Windows SMTC binding: jsign + JNA direct, OR community KMP wrapper (vet on day) | Default: JNA direct to Win32 SMTC APIs; ~15-20 hrs total |
| **Phase 2a Flight A** | Confirm kmpalette 4.0.0 stability — has it reached stable, or is the beta API still churning? | Cascade per Item 3 soft-lock: stable → pin; betaN API stable → pin betaN; betaN API churning → roll-our-own (~16-24 hrs in Flight A) |
| **Phase 2a Flight A** | Confirm Compose-MP `ExperimentalTestApi` status — has it stabilized, or still `@OptIn` required for `runDesktopComposeUiTest`? | Either way: pin per situation; not a blocker |
| **End of Phase 2a** | Phase 2b Flights H+I commitment per Item 13: dogfooded ExoPlayer/Java Sound for 3-6+ months — any audible quality complaints? | Yes → build Flights H+I (~160-240 hrs Phase 2b). No → cut them; recognize abstraction was still worth doing |

---

## 10. Risk register quick-reference

(Copied from plan §9 for self-containment.)

| Risk | Mitigation | Revisit |
|---|---|---|
| FLAC desktop decoder requires vendored native libFLAC.dll bundling | JNA + extract-from-JAR pattern; THIRD_PARTY_LICENSES.md ships BSD-3 attribution | MVP Session 4-7 (build), MVP Session 26-28 (jpackage verification) |
| kmpalette 4.0.0 still in beta at adoption time | Pin latest betaN; fallback to roll-our-own (~16-24 hrs) | Before Phase 2a Flight A |
| Coil 3 may have raised `minSdk` from 21 to 23 in 3.4.x | Probe before MVP Session 4; spec §2 implication if 23 | MVP Session 4 |
| Compose-MP LazyColumn perf at 40k unverified | Paged loading default; sectioned grouping fallback | MVP Session 1-3 spike |
| Phase 2b Flights H+I may not earn their keep | Engine-swap abstraction is cheap (~10-15 hrs MVP cost); cut H+I at end of Phase 2a if ExoPlayer/Java Sound proves "good enough" | End of Phase 2a |
| Windows SMTC binding still TBD | JIT before MVP Session 23 | Before MVP Session 23 |
| Windows SmartScreen warning on unsigned MSI/EXE | Acceptable for MVP dogfooding; revisit if adoption grows | Phase 2a Flight E or first non-Clay downloader |

---

## 11. Soft-lock revisit calendar

| When | Soft-lock | Decision criterion |
|---|---|---|
| Before MVP Session 4 | Coil minSdk = 21 (spec §2 implication) | Coil release notes |
| MVP Session 1-3 | LazyColumn 40k mitigation choice | Spike frame-time histogram |
| MVP Session 4-7 | JustFLAC fallback (already eliminated by Item 9 addendum) | n/a — closed |
| **Before Phase 2a Flight A** | kmpalette 4.0.0 stability vs beta vs roll-our-own (Item 3) | API stability across `4.0.0-betaN` to `4.0.0-betaN+M` |
| Phase 2a Flight A | Compose-MP `ExperimentalTestApi` for Roborazzi (Item 7) | Compose-MP release notes |
| **End of Phase 2a** | AAudio/WASAPI commitment (Item 13) — Phase 2b Flights H+I stay or cut | Clay's ear after 3-6+ months dogfooding ExoPlayer/Java Sound |
| End of Phase 2a | Test strategy soft lock (spec §2 item 7) per plan §9 | Tests still adding value, not absorbing time? |
| **End of Phase 2b** | Differentiator soft lock (spec §2 item 2) | Still want room correction, or new priority? |

---

## 12. Step-by-step scaffold sequence (MVP Sessions 1-3)

This is the ordered action sequence for the scaffold session(s). Each numbered item is a discrete commit-worthy unit of work.

### Session 1 (~4-6 hrs)

1. **Repo decision** — Clay's call: GitHub repo name (`clayboicardi/kiln`?). Create empty public repo. Recommendation per plan §3.1: develop publicly from day one (Software-as-Self-Portrait pattern).
2. **Local git init + LICENSE + NOTICE + README + CLAUDE.md** — Apache 2.0 LICENSE text; NOTICE attributing Clay Haworth / Clayworks; README minimal; copy current CLAUDE.md from `kiln/` (it's already there).
3. **Gradle wrapper + JDK toolchain** — `gradle wrapper --gradle-version 8.11` (or current stable); commit `.gradle/`, `gradle/`, `gradlew`, `gradlew.bat`.
4. **JIT verify versions** — every `*-VERIFY` line in §2's `libs.versions.toml` skeleton checked against Maven Central / GitHub. Update versions. Commit `gradle/libs.versions.toml`.
5. **`settings.gradle.kts` + root `build.gradle.kts`** — drop in §3 + §4 sketches verbatim. Commit.
6. **`build-logic/` convention plugins** — implement `kiln.kmp.library`, `kiln.kmp.compose`, `kiln.android.app`, `kiln.desktop.app` per §5. Commit each plugin separately (4 commits).
7. **Module skeletons** — create `:audio:dsp`, `:audio:visualizer`, `:audio:playback`, `:data:library`, `:ui:theme`, `:ui:components`, `:app-android`, `:app-desktop` directories with empty `build.gradle.kts` applying the right convention.
8. **Smoke build** — `./gradlew build --dry-run` to confirm graph resolves. Then `./gradlew tasks` to confirm tasks exist.

**Session 1 end-state:** `./gradlew build --dry-run` succeeds without errors. No actual compilation yet.

### Session 2 (~4-6 hrs)

9. **"Hello Kiln" Android** — minimal `MainActivity.kt` in `:app-android/src/main/kotlin/` displaying "Hello Kiln" via Compose. AndroidManifest minimal. Run `./gradlew :app-android:assembleDebug` until it produces an APK.
10. **"Hello Kiln" Desktop** — minimal `Main.kt` in `:app-desktop/src/jvmMain/kotlin/` displaying "Hello Kiln" via Compose-MP `application { Window { Text("Hello Kiln") } }`. Run `./gradlew :app-desktop:run` until it opens a window.
11. **First-build milestone** — install APK on Pixel 10 Pro XL via `adb install`; launch desktop binary via `./gradlew :app-desktop:run`. Both display "Hello Kiln". Take screenshots, commit them to `docs/sessions/2026-MM-DD-session-2.md`.
12. **GitHub Actions CI workflow** — drop in §8 sketch as `.github/workflows/build.yml`. Push, watch CI pass.

**Session 2 end-state:** "Hello Kiln" runs on both platforms, builds in CI.

### Session 3 (~4-8 hrs)

13. **`upgradeUuid` generation** — generate a stable UUIDv5 from namespace `kiln-windows-upgrade` + name `kiln-msi`; commit as constant. Verify MSI build via `./gradlew :app-desktop:packageMsi` (requires WiX on the machine).
14. **LazyColumn 40k spike** — write a throwaway `:app-desktop/src/jvmTest/kotlin/SpikeLazyColumn40k.kt` that materializes 40k synthetic `Track` objects and renders them. Capture frame-time histogram. Document results in a new appendix at `docs/decisions/2026-MM-DD-item-12-spike-results.md`. Adjust Session 8 plan based on results.
15. **Plan Session 8 mitigation** — based on spike results, decide: pure LazyColumn (pass), paged loading (marginal/fail), sectioned grouping (severe fail). Update `docs/superpowers/plans/2026-05-18-kiln-execution-plan.md` §3.2 Session 8 in place.
16. **`docs/sessions/` close-out** — write `docs/sessions/2026-MM-DD-session-3.md` per plan §11 session-end checklist.

**Session 3 end-state:** Scaffold + first build + CI + LazyColumn spike documented. Ready for MVP Session 4 (Library + playback vertical slice begins).

---

## 13. Verification checklist — "Sessions 1-3 done" exit criteria

- ✅ `gradle/libs.versions.toml` committed with all Pre-MVP-decided pins + JIT-verified other versions
- ✅ `settings.gradle.kts` includes all 8 modules
- ✅ `build-logic/` contains 4 convention plugins (kmp.library, kmp.compose, android.app, desktop.app)
- ✅ All 8 modules build empty (no actual code yet) via `./gradlew build`
- ✅ `:app-android:assembleDebug` produces a working APK that installs on Pixel 10 Pro XL and displays "Hello Kiln"
- ✅ `:app-desktop:run` opens a Compose-MP window displaying "Hello Kiln"
- ✅ GitHub Actions CI workflow at `.github/workflows/build.yml` passes on both Ubuntu (Android build) and Windows (Desktop MSI build) runners
- ✅ `upgradeUuid` constant committed for jpackage MSI upgrade detection
- ✅ WiX Toolset installed on Clay's i5-13400F; `./gradlew :app-desktop:packageMsi` produces a working MSI
- ✅ LazyColumn 40k spike results documented at `docs/decisions/2026-MM-DD-item-12-spike-results.md` with frame-time histogram + Session 8 architectural decision
- ✅ `docs/sessions/2026-MM-DD-session-1.md` through `session-3.md` committed per plan §11
- ✅ Tag `v0.1.0-scaffold` on the final scaffold commit (or whatever Clay prefers as the milestone marker)

---

## 14. What this prep doc does NOT do

- Does NOT execute any scaffold code — that happens at MVP Session 1-3 actual work
- Does NOT pin the JIT-verify versions — those land at scaffold-day Maven Central queries
- Does NOT define the SQLDelight `.sq` files — those land at MVP Session 4-7 per schema sketch
- Does NOT include `MusicSource` / `PlatformPlayer` / `MusicDecoder` interface definitions — those are MVP Session 4-7 design work (spec §3.3 and §13 give the shape)
- Does NOT write the `kotlin-inject` graph — assembled at Session 4-7 once dependencies exist
- Does NOT include the LazyColumn 40k spike code itself — that's MVP Session 3 throwaway work
- Does NOT include any FLAC decoder JNI/JNA bindings — those land at MVP Session 4-7 per Item 9 addendum
- Does NOT replace the vetting log — this is a derived artifact; the vetting log is the canonical decision document

---

## 15. Effort projection

| Session | Plan estimate | Actual scope per this doc |
|---|---|---|
| Session 1 | 4-6 hrs | Items 1-8 — repo, Gradle wrapper, libs toml, settings + root build, build-logic conventions, module skeletons, smoke `--dry-run` |
| Session 2 | 4-6 hrs | Items 9-12 — Hello Kiln Android + Desktop, CI workflow |
| Session 3 | 4-8 hrs | Items 13-16 — upgradeUuid, LazyColumn spike, results doc, session close-out |
| **Total Sessions 1-3** | **12-20 hrs** (matches plan §3.1) | **same** |

If the actual Sessions 1-3 underrun or overrun, document the variance in the session close-out files per plan §11.

---

End of MVP Session 1-3 Scaffold Prep.
