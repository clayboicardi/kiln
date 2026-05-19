// kiln.kmp.library — base KMP library convention.
// Applied to: :audio:*, :data:library, :ui:* (when not Compose), and base of kiln.kmp.compose.
//
// Establishes:
// - Kotlin Multiplatform plugin
// - Android library target with compileSdk=36, minSdk=21 (spec §2 hard locks)
// - JVM Desktop target with jvmTarget=21
// - Namespace derived from project path: com.clayworks.kiln${project.path}

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    jvmToolchain(21)

    androidTarget()
    jvm("desktop")
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // project.path = ":audio:dsp" → namespace = com.clayworks.kiln.audio.dsp
    // project.path = ":data:library" → namespace = com.clayworks.kiln.data.library
    namespace = "com.clayworks.kiln${project.path.replace(":", ".")}"
}
