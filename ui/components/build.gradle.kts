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
            implementation(libs.bundles.voyager)
            implementation(libs.bundles.circuit)
            implementation(libs.molecule.runtime)
            implementation(libs.kermit)
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
