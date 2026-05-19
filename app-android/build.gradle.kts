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

    implementation(libs.bundles.android.media3)

    implementation(project(":audio:dsp"))
    implementation(project(":audio:playback"))
    implementation(project(":data:library"))
    implementation(project(":ui:components"))
    implementation(project(":ui:theme"))
}
