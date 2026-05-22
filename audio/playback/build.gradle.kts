// :audio:playback — PlatformPlayer + Decoder interfaces (commonMain) +
// platform adapters: Media3 ExoPlayer (androidMain), Java Sound + JNA
// libFLAC bridge (desktopMain). Engine-swap-shaped boundary per Item 13.
//
// Native libFLAC 1.5.0 vendored under src/desktopMain/resources/native/win-x64/
// (BSD-3, sourced from xiph/flac GitHub release 1.5.0; see README.md there
// for provenance + SHA256).

plugins {
    id("kiln.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.arrow.core)  // Either<DecoderError, DecodedStream> in Decoder
            implementation(project(":data:library"))  // MediaItem type used in Queue + PlatformPlayer
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
        // Android host-side tests (Robolectric on JVM). Phase-5 source-set
        // scaffolding only; the Media3 instantiation test lands in Phase 7
        // (Media3ExoPlayerImpl coverage). AGP 9 KMP renamed androidUnitTest
        // → androidHostTest; the matching Gradle task is testAndroidHostTest.
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)            // ApplicationProvider
            }
        }
    }
}
