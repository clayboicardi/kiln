// kiln.kmp.library — base KMP library convention.
// Applied to: :audio:*, :data:library, :ui:* (via kiln.kmp.compose).
//
// Uses com.android.kotlin.multiplatform.library (AGP 9.0+ requirement when
// paired with org.jetbrains.kotlin.multiplatform — com.android.library
// plugin is no longer compatible per AGP 9.0 breaking change).
//
// Spec §2 hard locks: compileSdk = 36, minSdk = 23, JVM target = 21.
// (minSdk revised 21 → 23 on 2026-05-19; vetting log Item 1 addendum.)
// Namespace derived from project.path (e.g., :audio:dsp → com.clayworks.kiln.audio.dsp).

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        compileSdk = 36
        minSdk = 23
        namespace = "com.clayworks.kiln${project.path.replace(":", ".")}"
    }
    jvm("desktop")
}
