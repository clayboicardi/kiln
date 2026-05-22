// Desktop DI graph wiring test. Verifies all @Provides chains resolve
// without exceptions when create() is called with realistic value-class
// constructor args. Closes review P2-10.
//
// Note: `graph.player` construction goes through createJvmFlacDecoder()
// which calls LibFlacLoader.load() — this extracts and loads the vendored
// FLAC.dll from the :audio:playback resources, identical to the existing
// FlacDecodeSmokeTest path. Idempotent across the JVM lifetime; the load
// already happens for :audio:playback's desktopTest task, so wiring it
// here for :app-desktop's `test` task confirms the path also works when
// invoked from a separate module's test classpath.
//
// Track A change: dropped the ScanFolders ctor arg (scanner now reads scan
// folders from SettingsRepository on demand). Added a settings-resolution
// assertion so the new graph surface is pinned by a test.

package com.clayworks.kiln.desktop.di

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertNotNull

class DesktopAppGraphTest {

    @Test
    fun graph_provides_full_chain() {
        val tempDir = Files.createTempDirectory("kiln-graph-test-")
        try {
            val graph = DesktopAppGraph::class.create(
                userDataDir = UserDataDir(tempDir),
            )
            assertNotNull(graph.musicSource)
            assertNotNull(graph.scanner)
            assertNotNull(graph.player)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun graph_exposes_settings_repository() {
        // Track A surface contract: graph.settings resolves to the singleton
        // SettingsRepository instance backed by the SQLDelight settings table.
        // Other consumers (Main.kt's first-launch seed, SettingsScreen route)
        // depend on this binding existing on the graph surface; a regression
        // would surface as a KSP error at compile time, but the assertion
        // pins the runtime contract too.
        val tempDir = Files.createTempDirectory("kiln-graph-test-")
        try {
            val graph = DesktopAppGraph::class.create(
                userDataDir = UserDataDir(tempDir),
            )
            assertNotNull(graph.settings)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
