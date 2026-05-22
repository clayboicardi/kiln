// :ui:theme — Kiln Dynamic theming (kmpalette album-art → palette →
// WCAG-AA-contrasted Material 3 ColorScheme). Coil-compose for
// ImageBitmap → kmpalette pipeline.

plugins {
    id("kiln.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.bundles.compose.mp.common)
            // kmpalette-core: dep deferred to Phase 2a Flight A adoption. Item 3
            // vetting decided 4.0.0-beta02 but the artifact is NOT on Maven
            // Central (only tagged on GitHub). Phase 2a Flight A resolves via
            // JitPack (com.github.jordond.kmpalette:kmpalette-core:4.0.0-beta02)
            // OR roll-our-own per Item 3 soft-lock cascade. Vetting log Item 3
            // addendum pending.
            // implementation(libs.kmpalette.core)
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
