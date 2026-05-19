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
            implementation(libs.kmpalette.core)
            implementation(libs.coil.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
