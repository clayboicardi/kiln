// kiln.kmp.compose — KMP library + Compose Multiplatform convention.
// Inherits kiln.kmp.library; adds JetBrains Compose plugin + Kotlin Compose
// Compiler plugin so commonMain can use @Composable functions.
//
// Applied to: :ui:theme, :ui:components.
// NOT applied to: :audio:*, :data:library (Concentric Modules invariant per
// spec §3.4 — inner modules stay platform-free).

plugins {
    id("kiln.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Compose-MP common dependencies (bundles.compose-mp-common) are added at
// the module level — convention provides plugin application only, modules
// pick what they need from the bundle to avoid forcing unused deps.
