// :audio:dsp — DSP primitives (filters, EQ math, room-correction kernels).
// Concentric Modules invariant per spec §3.4: platform-free Kotlin only.
// NO androidx imports in commonMain. Arrow Either spine showcase.

plugins {
    id("kiln.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.arrow.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
