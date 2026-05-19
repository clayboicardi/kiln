// kiln.android.app — Android application convention.
// Applied to: :app-android.
//
// Pure Android module (not KMP) consuming KMP libraries via their androidTarget
// artifacts. Uses Android-classic Compose (androidx.compose.*) via the Kotlin
// Compose Compiler plugin.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    // Note: org.jetbrains.kotlin.android plugin is no longer required as of AGP 9.0 —
    // Kotlin support is built into AGP. See https://kotl.in/gradle/agp-built-in-kotlin
}

android {
    namespace = "com.clayworks.kiln"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.clayworks.kiln"
        minSdk = 23   // revised 21 → 23 on 2026-05-19; vetting log Item 1 addendum
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-scaffold"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}
