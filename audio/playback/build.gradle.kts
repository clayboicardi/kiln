// :audio:playback — PlatformPlayer + Decoder interfaces (commonMain) +
// platform adapters: Media3 ExoPlayer (androidMain), Java Sound + JNA
// libFLAC bridge (jvmMain). Engine-swap-shaped boundary per Item 13.
//
// Native libFLAC.dll vendored under src/jvmMain/resources/native/win-x64/
// — lands at MVP Session 4-7 (currently absent).

plugins {
    id("kiln.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.bundles.android.media3)
        }
        desktopMain.dependencies {
            implementation(libs.bundles.jna)
            implementation(libs.jaudiotagger)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}
