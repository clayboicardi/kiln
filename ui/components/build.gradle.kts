// :ui:components — Voyager navigation + Circuit/Molecule presenter
// showcase (Now Playing) + shared Compose-MP UI components.
//
// allWarningsAsErrors enforced to catch Circuit @ComposableTarget
// misuse per Item 5 + spec §4.

plugins {
    id("kiln.kmp.compose")
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.bundles.compose.mp.common)
            implementation(libs.compose.material.icons.extended)
            // Material 3 extended icon set — core is missing Pause/SkipNext/SkipPrevious/
            // PlayCircle/LibraryMusic etc., all of which Track C transport UI needs.
            // ~1-2 MB APK cost; acceptable for a portfolio-oriented audiophile player.
            implementation(libs.bundles.voyager)
            implementation(libs.bundles.circuit)
            implementation(libs.molecule.runtime)
            implementation(libs.kermit)
            // ThemeMode + (future) shared types — SettingsScreen consumes the
            // ThemeMode enum from :data:library:settings.
            implementation(project(":data:library"))
            // PlatformPlayer + PlayerState/QueueState consumed by Voyager Tab
            // wrappers (LibraryTab → loadQueue, NowPlayingTab → state flows +
            // transport, SearchTab → loadQueue). Phase 2a Track C addition.
            implementation(project(":audio:playback"))
            // KilnTheme for previews + (future) Compose-UI tests. Adds the
            // theme module here so test code can wrap SettingsScreen in the
            // real theme rather than a bare MaterialTheme.
            implementation(project(":ui:theme"))
            // coil-core-jvm:3.4.0 declares skiko:0.9.22.2; Compose-MP 1.11 brings
            // 0.144.6. The JetBrains checkDesktopMainComposeLibrariesCompatibility
            // task inspects requested-vs-resolved edges and warns on the major.minor
            // mismatch. Excluding skiko from coil removes the offending edge —
            // Compose-MP supplies skiko directly. Review P2-1.
            implementation(libs.coil.compose.get().toString()) {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.turbine)
        }
        // Desktop-only Compose-UI tests (Phase 2a Track A — first tests in
        // :ui:components). compose-ui-test-junit4 provides createComposeRule()
        // + finders (onNodeWithText, performClick). compose.desktop.currentOs
        // pulls the platform-specific Skia + window toolkit jars Compose needs
        // for headless rendering on the JVM target.
        val desktopTest by getting {
            dependencies {
                implementation(libs.compose.ui.test.junit4)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko" && requested.name == "skiko") {
            useVersion(libs.versions.skiko.get())
            because("Pin skiko to Compose-MP's transitive version; review P2-1")
        }
    }
}
