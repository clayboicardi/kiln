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
            implementation(libs.coil.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.turbine)
        }
    }
}
