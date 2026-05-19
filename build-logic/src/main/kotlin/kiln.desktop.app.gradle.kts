// kiln.desktop.app — Compose-MP desktop application convention.
// Applied to: :app-desktop.
//
// Pure JVM module consuming KMP libraries via their jvm("desktop") artifacts.
// Wires compose.desktop.application block with jpackage nativeDistributions
// (AppImage + Msi targets per Item 10). Windows distribution requires WiX
// Toolset 3.x for MSI builds (installs anytime before MVP Session 3 smoke test).

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "com.clayworks.kiln.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
            )
            packageName = "Kiln"
            packageVersion = "0.1.0"
            vendor = "Clay Haworth / Clayworks"
            copyright = "Copyright (c) 2026 Clay Haworth. Licensed Apache 2.0."
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.sql", "java.naming")
            windows {
                console = false
                perUserInstall = true
                shortcut = true
                menu = true
                // Stable UUIDv5 computed via PowerShell .NET SHA-1 on 2026-05-19.
                // Derivation: uuid5(uuid5(NAMESPACE_DNS, "clayworks.com"), "kiln-msi-upgrade").
                // NEVER MODIFY — future MSI upgrades detect installed-Kiln-to-be-replaced
                // via this UUID. Item 10 decision + scaffold prep §3a + engram
                // kiln/msi-upgrade-uuid.
                upgradeUuid = "611fd94b-756e-561d-ba94-af658a225268"
                // iconFile.set(rootProject.file("docs/assets/kiln.ico"))  // adds at MVP Session 26-28 polish
            }
        }
    }
}
