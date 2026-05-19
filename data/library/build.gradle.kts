// :data:library — SQLDelight-backed library cache + FTS5 search +
// MediaStore (Android) / filesystem (Desktop) scanners.
//
// Six tables per docs/decisions/2026-05-18-sqldelight-schema-sketch.md:
// artist, album, track, playlist, playlist_track, listening_history +
// FTS5 contentless virtual table. SQLDelight .sq files land at MVP
// Session 4-7.

plugins {
    id("kiln.kmp.library")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("KilnDatabase") {
            packageName.set("com.clayworks.kiln.data.library.db")
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.bundles.sqldelight.common)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.arrow.core)  // Either<SourceError, X> in MusicSource per spec §3.3
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        desktopMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.jaudiotagger)
            implementation(libs.appdirs)
        }
    }
}
