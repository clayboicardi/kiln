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
            implementation(libs.coil.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
