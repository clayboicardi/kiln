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
    implementation(libs.kotlinInject.runtime)
    ksp(libs.kotlinInject.compiler)

    implementation(libs.bundles.jna)

    implementation(project(":audio:dsp"))
    implementation(project(":audio:playback"))
    implementation(project(":data:library"))
    implementation(project(":ui:components"))
    implementation(project(":ui:theme"))
}
