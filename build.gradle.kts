// Root build.gradle.kts — applies no plugins itself; subprojects opt in via convention plugins (build-logic/, lands at Task #6).
// All plugin refs declared with `apply false` to register them in the build's plugin classpath.

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // kotlin.android removed — built into AGP 9.0+ (see kiln.android.app convention note)
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kotlin.plugin.parcelize) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp) apply false  // AGP 9.0+ requirement for KMP modules (replaces android.library when paired with kotlin.multiplatform)
    alias(libs.plugins.jb.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.mokkery) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
