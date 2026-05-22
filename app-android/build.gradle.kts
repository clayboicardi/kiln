// :app-android — Android application module (pure Android, not KMP).
// Consumes KMP libraries via their androidTarget artifacts.
//
// Hello Kiln Android entry point lands at MVP Session 2 (Task #9).

plugins {
    id("kiln.android.app")
    alias(libs.plugins.ksp)
}

dependencies {
    // Compose-MP common bundle — provides foundation/material3/ui on Android
    // target (compiles to androidx.compose.* artifacts). Project deps from
    // :ui:* expose these as implementation (not api), so :app-android adds
    // them directly.
    implementation(libs.bundles.compose.mp.common)

    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinInject.runtime)
    ksp(libs.kotlinInject.compiler)

    // SQLDelight Android driver — graph constructs AndroidSqliteDriver directly.
    implementation(libs.sqldelight.android.driver)
    // Bundled SQLite for Android — Session 10 H8 discovery: Pixel 10 / Android 16
    // system SQLite reported "no such module: fts5" at schema-creation time,
    // blocking the whole DB. requery's library bundles SQLite 3.49.x with FTS5
    // enabled. AndroidAppGraph passes RequerySQLiteOpenHelperFactory to the
    // SQLDelight driver so all DB I/O goes through the bundled SQLite.
    implementation(libs.requery.sqlite.android)

    implementation(libs.bundles.android.media3)

    implementation(project(":audio:dsp"))
    implementation(project(":audio:playback"))
    implementation(project(":data:library"))
    implementation(project(":ui:components"))
    implementation(project(":ui:theme"))

    // === Android host-side tests (Robolectric) ===
    // :app-android uses com.android.application (legacy), not the KMP
    // library plugin. The legacy convention places host-side unit tests
    // under src/test/kotlin/... wired via testImplementation; the Gradle
    // task is testDebugUnitTest (NOT testAndroidHostTest — that's the
    // AGP 9 KMP form, used in :data:library + :audio:playback).
    //
    // junit4 must be declared explicitly here — unlike the KMP plugin's
    // androidHostTest source set, com.android.application's
    // testImplementation doesn't surface JUnit 4 transitively via
    // Robolectric. Confirmed empirically Phase 5.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)                 // ApplicationProvider
    testImplementation(libs.requery.sqlite.android)             // bundled SQLite parity with production
}
