// :audio:visualizer — Pure-Kotlin FFT + visualizer geometry primitives.
// Concentric Modules invariant per spec §3.4: platform-free Kotlin only.
// NO androidx imports in commonMain. Adapters (Media3, Java Sound) in
// androidMain / jvmMain at Phase 2a Flight E.

plugins {
    id("kiln.kmp.library")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
