// :app-desktop — Compose-MP desktop application module (pure JVM, not KMP).
// Consumes KMP libraries via their jvm("desktop") artifacts.
//
// Hello Kiln Desktop entry point (com.clayworks.kiln.desktop.MainKt) lands
// at MVP Session 2 (Task #10). Native libFLAC.dll vendored under
// :audio:playback at MVP Session 4-7.

plugins {
    id("kiln.desktop.app")
    alias(libs.plugins.ksp)
}

dependencies {
    // Compose-MP common bundle — provides foundation/material3/ui +
    // ui.window (Window, application, exitApplication for Desktop). Project
    // deps from :ui:* expose these as implementation (not api).
    implementation(libs.bundles.compose.mp.common)
    // Compose Desktop runtime (compose.desktop.currentOs supplies the
    // platform-specific Skia + window toolkit jars).
    implementation(compose.desktop.currentOs)

    implementation(libs.kotlinInject.runtime)
    ksp(libs.kotlinInject.compiler)

    // SQLDelight JVM driver — graph constructs JdbcSqliteDriver directly.
    implementation(libs.sqldelight.sqlite.driver)
    // SQLDelight coroutines extensions — asFlow() + mapToOne() used in
    // DesktopSettingsRoute to reactively observe countTracksMissingReplayGain.
    implementation(libs.bundles.sqldelight.common)

    // Swing dispatcher — JFileChooser folder picker in Main.kt's
    // DesktopSettingsRoute runs on Dispatchers.Swing (EDT). Required by
    // kotlinx.coroutines.swing import / Dispatchers.Swing access.
    implementation(libs.kotlinx.coroutines.swing)

    implementation(libs.bundles.jna)

    implementation(project(":audio:dsp"))
    implementation(project(":audio:playback"))
    implementation(project(":data:library"))
    implementation(project(":ui:components"))
    implementation(project(":ui:theme"))

    // === Desktop host-side tests (pure JVM JUnit 4) ===
    // :app-desktop is com.android.application's JVM counterpart (kiln.desktop.app
    // → org.jetbrains.kotlin.jvm). The default `test` source set lives at
    // src/test/kotlin/... and runs via `./gradlew :app-desktop:test`. JUnit 4
    // is the project's standard runner (matches :app-android + audio:playback
    // desktopTest). No Robolectric — desktop tests run on a real JVM.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
}
