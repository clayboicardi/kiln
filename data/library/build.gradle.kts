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
    alias(libs.plugins.kotlin.plugin.serialization)
}

sqldelight {
    databases {
        create("KilnDatabase") {
            packageName.set("com.clayworks.kiln.data.library.db")
            // Migration infrastructure baked in Phase 2a Track A. The .db snapshots
            // committed under databases/ let CI's verifyCommonMainKilnDatabaseMigration
            // task diff target schema (current .sq files) against
            // initial-snapshot + sequential .sqm migrations. Catches drift before merge.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.bundles.sqldelight.common)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
            // arrow.core is `api` because Either<SourceError, X> + Either<ScanError, X>
            // are part of the public surface of MusicSource and LibraryScanner.
            // App-module consumers need to be able to pattern-match Either.Right /
            // Either.Left when invoking these. Was implementation; H7 surfaced the
            // gap when MainActivity / Main.kt tried to consume scanIncremental().
            api(libs.arrow.core)
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
        // Desktop-only tests for JvmFilesystemScanner regression coverage —
        // need JdbcSqliteDriver in test scope. commonTest deps inherit through.
        val desktopTest by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        // Android host-side tests (Robolectric on JVM). Verifies KilnDatabase
        // schema + FTS5 creates cleanly with bundled SQLite — the exact gap
        // that let the Pixel FTS5 surprise go latent for 5 sessions (P1-3).
        // AGP 9 KMP renamed androidUnitTest → androidHostTest; the matching
        // Gradle task is testAndroidHostTest (NOT testDebugUnitTest).
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.requery.sqlite.android)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)            // ApplicationProvider
            }
        }
    }
}
