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
        // jitpack only if we ever consume a JitPack-only artifact; not at MVP
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
