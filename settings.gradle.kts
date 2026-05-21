@file:Suppress("UnstableApiUsage")

rootProject.name = "kiln"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack — required by `com.github.requery:sqlite-android`
        // (bundled SQLite for Android FTS5; Session 10 H8 finding —
        // Pixel 10/Android 16 system SQLite was missing FTS5 module).
        // Restrict to the requery group so JitPack isn't a fallback for
        // everything else (faster + safer dependency resolution).
        maven(url = "https://jitpack.io") {
            content {
                includeGroup("com.github.requery")
            }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
// STABLE_CONFIGURATION_CACHE removed — configuration cache is stable default in Gradle 9.x

include(
    ":app-android",
    ":app-desktop",
    ":audio:dsp",
    ":audio:visualizer",
    ":audio:playback",
    ":data:library",
    ":ui:theme",
    ":ui:components",
)
