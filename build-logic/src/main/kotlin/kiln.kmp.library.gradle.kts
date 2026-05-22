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
        // AGP 9 KMP host-side test opt-in. Without this, the androidHostTest
        // source set is not created and commonTest is not executed on the
        // Android target. (Gradle emits a warning when commonTest exists but
        // host tests aren't enabled.) Task name: testAndroidHostTest.
        // androidUnitTest (AGP 8.x) was renamed to androidHostTest in AGP 9.
        //
        // Side-effect: enabling host tests causes commonTest classes to ALSO
        // execute on the Android target's testAndroidHostTest task (per-target
        // test multiplication is standard KMP behavior — the same test runs on
        // every target that has a test source set). This is welcome free
        // Android-target coverage for platform-neutral commonTest classes, but
        // can surprise a maintainer wondering why one test name appears in two
        // task reports (e.g. desktopTest + testAndroidHostTest).
        withHostTest { }
    }
    jvm("desktop")
}
