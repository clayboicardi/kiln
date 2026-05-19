// build-logic — Kiln's convention plugins. Included build referenced from
// root settings.gradle.kts via pluginManagement.includeBuild("build-logic").
// Each .gradle.kts file under src/main/kotlin/ becomes a plugin id matching
// the filename (e.g., kiln.kmp.library.gradle.kts → id("kiln.kmp.library")).

plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Gradle plugins as classpath deps — required for precompiled script plugins
    // to apply other plugins by id (since they're not pre-resolved by Gradle).
    implementation(libs.gradle.plugin.kotlin)
    implementation(libs.gradle.plugin.kotlin.compose.compiler)
    implementation(libs.gradle.plugin.android)
    implementation(libs.gradle.plugin.compose)
    implementation(libs.gradle.plugin.ksp)
}
